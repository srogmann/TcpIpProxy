package org.rogmann.tcpipproxy;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;

/** Partial implementation of a WebSocket-client (see RFC 6455). */
public class WebSocketClient {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int FIN_BIT = 0x80;
    private static final int MASK_BIT = 0x80;
    private static final int CLOSE_FRAME = 0x08;
    private static final int PING_FRAME = 0x09;
    private static final int PONG_FRAME = 0x0A;

    static boolean isVerbose;

    private Socket socket;
    private OutputStream outputStream;
    private BufferedInputStream inputStream;
    
    private final boolean useMask;
    private final byte[] maskKey;

    /**
     * Constructor
     * @param socket underlying socket
     * @param useMask <code>true</code> to use a mask when writing messages
     * @throws IOException in case of an IO-error
     */
    public WebSocketClient(Socket socket, boolean useMask) throws IOException {
        this.socket = socket;
        this.outputStream = socket.getOutputStream();
        this.inputStream = new BufferedInputStream(socket.getInputStream());
        this.useMask = useMask;
        this.maskKey = generateRandomMaskKey();
    }

    public static WebSocketClient connect(String host, int port, String requestUri, String origin) throws IOException {
        Socket socket = new Socket(host, port);
        boolean isOk = false;
        try {
            String secWebSocketKey = generateSecWebSocketKey();
            StringBuilder request = new StringBuilder();

            request.append("GET ").append(requestUri).append(" HTTP/1.1\r\n");
            addHeader(request, "Host", host + ":" + port);
            addHeader(request, "Upgrade", "websocket");
            addHeader(request, "Connection", "keep-alive, Upgrade");
            addHeader(request, "Sec-WebSocket-Version", "13");
            addHeader(request, "Sec-WebSocket-Key", secWebSocketKey);
            addHeader(request, "Sec-Fetch-Dest", "empty");
            addHeader(request, "Sec-Fetch-Mode", "websocket");
            addHeader(request, "Sec-Fetch-Site", "same-origin");
            addHeader(request, "Origin", origin);
            request.append("\r\n");

            OutputStream outputStream = socket.getOutputStream();
            outputStream.write(request.toString().getBytes(StandardCharsets.UTF_8));
            outputStream.flush();

            BufferedInputStream inputStream = new BufferedInputStream(socket.getInputStream());

            // Read status line
            String statusLine = readLine(inputStream);
            if (statusLine == null) {
                throw new IOException("No status line received from server");
            }

            // Parse status line
            String[] statusParts = statusLine.split("\\s+", 3);
            if (statusParts.length < 3) {
                throw new IOException("Invalid status line: " + statusLine);
            }
            int statusCode = Integer.parseInt(statusParts[1]);
            String statusMessage = statusParts[2];

            System.out.println("Status Line: " + statusLine);

            if (statusCode != 101) {
                // Read response headers
                Map<String, String> headers = readResponseHeaders(inputStream);
                System.out.println("Response Headers: " + headers);

                // Read response body
                StringBuilder responseBody = new StringBuilder();
                String line;
                while ((line = readLine(inputStream)) != null) {
                    responseBody.append(line).append("\n");
                }
                System.out.println("Response Body: " + responseBody.toString());

                throw new IOException("WebSocket handshake failed: HTTP " + statusCode + " " + statusMessage + "\n" + responseBody.toString());
            }

            // Read headers
            Map<String, String> headers = readResponseHeaders(inputStream);
            String acceptKey = headers.get("Sec-WebSocket-Accept");

            System.out.println("Response Headers: " + headers);
            if (acceptKey == null || !acceptKey.equals(computeAcceptKey(secWebSocketKey))) {
                throw new IOException("WebSocket handshake failed: Invalid Sec-WebSocket-Accept header: " + acceptKey);
            }

            isOk = true;
            return new WebSocketClient(socket, true);
        } finally {
            if (!isOk) {
                socket.close();
            }
        }
    }

    private static String generateSecWebSocketKey() {
        byte[] randomKey = new byte[16];
        java.util.Random random = new java.util.Random();
        random.nextBytes(randomKey);
        return Base64.getEncoder().encodeToString(randomKey);
    }

    private static void addHeader(StringBuilder request, String name, String value) {
        request.append(name).append(": ").append(value).append("\r\n");
    }

    private static Map<String, String> readResponseHeaders(BufferedInputStream inputStream) throws IOException {
        Map<String, String> headers = new HashMap<>();

        // Read headers
        while (true) {
            String line = readLine(inputStream);
            if (line == null || line.isEmpty()) {
                break;
            }
            String[] parts = line.split(":\\s+", 2);
            headers.put(parts[0], parts[1]);
        }

        return headers;
    }

    private static String readLine(BufferedInputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int c;
        while ((c = inputStream.read()) != -1) {
            if (c == '\r') {
                int nextChar = inputStream.read();
                if (nextChar == '\n') {
                    break;
                } else {
                    buffer.write(c);
                    buffer.write(nextChar);
                }
            } else {
                buffer.write(c);
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static String computeAcceptKey(String secWebSocketKey) {
        try {
            String acceptKey = secWebSocketKey + GUID;
            byte[] sha1Hash = MessageDigest.getInstance("SHA-1").digest(acceptKey.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sha1Hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm not found", e);
        }
    }

    public void write(byte[] buf) throws IOException {
        int length = buf.length;
        byte[] frame;
        int offset = 2;

        // FIN bit and opcode (0x80 for continuation, 0x01 for text, 0x02 for binary)
        byte finOpcode = (byte) (0x80 | 0x01); // Text frame with FIN bit set

        int lenMask = useMask ? 4 : 0;
        if (length < 126) {
            // Length fits in one byte
            frame = new byte[offset + lenMask + length];
            frame[1] = (byte) length;
        } else if (length <= 0xFFFF) {
            // Length fits in two bytes
            offset += 2;
            frame = new byte[offset + lenMask + length];
            frame[1] = (byte) 126;
            frame[2] = (byte) ((length >> 8) & 0xFF);
            frame[3] = (byte) (length & 0xFF);
        } else {
            // Length fits in eight bytes
            offset += 8;
            frame = new byte[offset + lenMask + length];
            frame[1] = (byte) 127;
            for (int i = 7; i >= 0; i--) {
                frame[2 + i] = (byte) ((length >> (8 * i)) & 0xFF);
            }
        }

        if (useMask) {
            frame[1] |= MASK_BIT; // Set the mask bit
            // Add masking key after the payload length
            for (int i = 0; i < lenMask; i++) {
                frame[offset + i] = maskKey[i];
            }
            offset += lenMask; // Adjust offset to account for the masking key
        }

        frame[0] = finOpcode;
        System.arraycopy(buf, 0, frame, offset, length);
        if (useMask) {
            for (int i = 0; i < length; i++) {
                int idxFrame = offset + i;
                frame[idxFrame] = (byte) (frame[idxFrame] ^ maskKey[i % lenMask]);
            }
        }

        outputStream.write(frame);
        outputStream.flush();
    }


    /**
     * Method to generate a random 4-byte key if needed
     * @return mask-key
     */
    public static byte[] generateRandomMaskKey() {
        Random random = new Random();
        byte[] key = new byte[4];
        random.nextBytes(key);
        return key;
    }
    
    record WebSocketRead(int opcode, byte[] data) { }

    public byte[] read() throws IOException {
        while (true) {
            WebSocketRead wsRead = readInternal();
            if (wsRead.opcode() == CLOSE_FRAME) {
                return null;
            }
            if (wsRead.opcode() == PING_FRAME) {
                continue;
            }
            return wsRead.data();
        }
    }

    private WebSocketRead readInternal() throws IOException {
        byte[] buffer = new byte[10];
        readBlock(buffer, 0, 2);

        int opcode = buffer[0] & 0x0F;
        int length = buffer[1] & 0x7F;
        boolean isMasked = (buffer[1] & 0x80) != 0;

        int lengthFieldSize = 0;
        if (length == 126) {
            lengthFieldSize = 2;
            readBlock(buffer, 2, lengthFieldSize);
            length = ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
        } else if (length == 127) {
            lengthFieldSize = 8;
            readBlock(buffer, 2, lengthFieldSize);
            length = 0;
            for (int i = 0; i < 8; i++) {
                length |= ((buffer[2 + i] & 0xFF) << (56 - 8 * i));
            }
        }
        String threadName = Thread.currentThread().getName();
        if (isVerbose) {
            System.out.format("#ws-read/%s: opcode=%d, len=%d, isMasked=%s, socket=%s%n", threadName, opcode, length, isMasked, socket);
        }

        // Compute mask key start and payload start based on lengthFieldSize
        int maskKeyStart = 2 + lengthFieldSize;
        int payloadStart = maskKeyStart;
        if (isMasked) {
            readBlock(buffer, maskKeyStart, 4);
            payloadStart += 4; // Add mask key length (4 bytes)
        }

        if (opcode == CLOSE_FRAME) {
            close();
            return new WebSocketRead(opcode, null);
        } else if (opcode == PING_FRAME) {
            handlePingFrame(buffer, length);
            return new WebSocketRead(opcode, new byte[0]);
        } else if (opcode != 0x01) { // Expecting a text frame
            throw new IOException("Unsupported opcode " + opcode);
        }

        if (isVerbose) {
            System.out.format("#ws-read/%s: Ermittle payload mit len %d, payloadStart=%d%n", threadName, length, payloadStart);
        }

        byte[] payload = new byte[length];
        readBlock(payload, 0, length);

        if (isMasked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= buffer[maskKeyStart + (i % 4)];
            }
        }
        if (isVerbose) {
            System.out.format("#ws-read: payload=%s[...]%n", HexFormat.of().formatHex(Arrays.copyOfRange(payload, 0, (payload.length >=60) ? 60 : payload.length)));
        }

        return new WebSocketRead(opcode, payload);
    }
    
    /**
     * Reads exactly the specified number of bytes from the input stream and stores them in the buffer, 
     * starting at the given offset. This method ensures that exactly {@code len} bytes are read or throws
     * an exception if the end of the stream is reached prematurely.
     * 
     * @param buf         the buffer where the bytes will be stored
     * @param offset      the starting position in {@code buf} to begin storing bytes
     * @param len         the number of bytes to read
     * @throws IOException if an I/O error occurs or the end of the stream is reached before
     *                     the requested number of bytes is read
     */
    private void readBlock(byte[] buf, int offset, int len) throws IOException {
        int totalRead = 0;
        while (totalRead < len) {
            int bytesRead = inputStream.read(buf, offset + totalRead, len - totalRead);
            if (bytesRead < 0) {
                throw new EOFException("Unexpected end of stream while reading " + (len - totalRead) + " bytes");
            }
            totalRead += bytesRead;
        }
    }

    private void handlePingFrame(byte[] buffer, int length) throws IOException {
        // Respond with a pong frame
        byte[] pongFrame = new byte[length + 2];
        pongFrame[0] = (byte) (PONG_FRAME | FIN_BIT);
        pongFrame[1] = (byte) length;

        System.arraycopy(buffer, 2, pongFrame, 2, length);

        if (isVerbose) {
            System.out.format("#ws-read: send pong %s%n", Arrays.toString(pongFrame));
        }
        outputStream.write(pongFrame);
        outputStream.flush();
    }

    public void close() throws IOException {
        try {
            // Send a close frame
            byte[] closeFrame = new byte[2];
            closeFrame[0] = (byte) (CLOSE_FRAME | FIN_BIT);
            closeFrame[1] = 0x00; // No status code

            try {
                outputStream.write(closeFrame);
            } catch (IOException e) {
                if (!"Socket closed".equals(e.getMessage())) {
                    throw e;
                }
            }
            outputStream.flush();
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    /**
     * Checks whether bytes are available to be read from the input stream without blocking.
     * 
     * @return {@code true} if at least one byte is immediately available; {@code false} otherwise
     * @throws IOException if an I/O error occurs while checking availability
     */
    public boolean isAvailable() throws IOException {
        return inputStream.available() > 0;
    }
}

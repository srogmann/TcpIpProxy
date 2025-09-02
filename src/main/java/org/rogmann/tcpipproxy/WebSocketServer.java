package org.rogmann.tcpipproxy;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.rogmann.tcpipproxy.http.HttpHandler;
import org.rogmann.tcpipproxy.http.HttpHeaders;
import org.rogmann.tcpipproxy.http.HttpServerDispatch;
import org.rogmann.tcpipproxy.http.HttpServerDispatchExchange;

/**
 * Implementation of a WebSocket server (RFC 6455, partial).
 */
public class WebSocketServer {
    private static final Logger LOG = Logger.getLogger(WebSocketServer.class.getName());

    /** Default port for WebSocket servers */
    public static final int DEFAULT_PORT = 8080;
    /** GUID for computing WebSocket key as per RFC 6455 */
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final InetSocketAddress addr;

    private final String pathWebSocket;
    private final WebSocketHandler handler;

    private final HttpServerDispatch httpServer;

    private final AtomicBoolean isStopped = new AtomicBoolean(false);

    /**
     * Constructor for WebSocket server.
     * @param host host/ip to listen on
     * @param port port to listen on
     * @param pathWebSocket path for WebSocket end-point
     * @param handler handler for WebSocket messages
     * @throws IOException if an I/O error occurs
     */
    public WebSocketServer(String host, int port, HttpHandler httpHandler, String pathWebSocket, WebSocketHandler handler) throws IOException {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        if (pathWebSocket == null || pathWebSocket.isEmpty()) {
            throw new IllegalArgumentException("WebSocket-path cannot be null or empty");
        }
        if (handler == null) {
            throw new IllegalArgumentException("WebSocketHandler cannot be null");
        }

        this.pathWebSocket = pathWebSocket;
        this.handler = handler;

        this.addr = new InetSocketAddress(host, port);
        httpServer = new HttpServerDispatch(addr, new UpgradeHandler(httpHandler));
        httpServer.start();
    }

    /**
     * Starts the WebSocket server.
     * @throws IOException if an I/O error occurs
     */
    public void start() throws IOException {
        httpServer.start();
        LOG.info("WebSocket server started on port " + addr.getPort() + " with path " + pathWebSocket);
    }

    /**
     * Stops the WebSocket server.
     * @param timeout timeout in seconds
     * @throws IOException if an I/O error occurs
     */
    public void stop(int timeout) throws IOException {
        httpServer.stop(timeout);
        LOG.info("WebSocket server stopped");
    }

    /**
     * Gets the bind-address of this server-instancd.
     * @return server address
     */
    public InetSocketAddress getServerAddress() {
        return addr;
    }

    /**
     * Interface for handling WebSocket messages.
     */
    public static interface WebSocketHandler {
        /**
         * Called when a new WebSocket connection is established.
         * @param client the client connection
         */
        void onOpen(WebSocketConnection client);

        /**
         * Called when a WebSocket message is received.
         * @param client the client connection
         * @param message the received message
         */
        void onMessage(WebSocketConnection client, String message);

        /**
         * Called when a WebSocket connection is closed.
         * @param client the client connection
         * @param code the close code
         * @param reason the close reason
         */
        void onClose(WebSocketConnection client, int code, String reason);

        /**
         * Called when an error occurs on a WebSocket connection.
         * @param client the client connection
         * @param t the exception
         */
        void onError(WebSocketConnection client, Throwable t);
    }

    /**
     * Class for managing a single WebSocket connection.
     */
    public static class WebSocketConnection {
        private final Socket socket;
        private final BufferedInputStream inputStream;
        private final OutputStream outputStream;
        private final AtomicBoolean isClosed = new AtomicBoolean();

        /** buffer to be used for short outgoing frames */
        private final byte[] bufTmpOut = new byte[4096];
        /** buffer to be used for short incoming frames */
        private final byte[] bufTmpIn = new byte[4096];
        /** buffer to be used for masks */
        private final byte[] bufTmpMask = new byte[4];
        /** server-is-stopped flag */
        private final AtomicBoolean isStopped;

        /** Queue of outgoing messages */
        private final BlockingQueue<WsPayload> outgoingMessages = new LinkedBlockingQueue<>();

        /**
         * Payload of a WS-message.
         * @param payload text data or binary data
         * @param opcode opcode of the payload
         */
        record WsPayload(byte[] payload, int opcode) { }

        /**
         * Constructor.
         * @param socket the socket connection
         * @param inputStream input stream from client
         * @param outputStream output stream to client
         */
        public WebSocketConnection(Socket socket, BufferedInputStream inputStream, OutputStream outputStream,
                AtomicBoolean isStopped) {
            this.socket = socket;
            this.inputStream = inputStream;
            this.outputStream = outputStream;
            this.isStopped = isStopped;
        }

        /**
         * Sends a text message to the client.
         * @param message the message to send
         * @throws IOException if an I/O error occurs
         */
        public void send(String message) throws IOException {
            byte[] payload = message.getBytes(StandardCharsets.UTF_8);
            outgoingMessages.add(new WsPayload(payload, 0x01)); // 0x01 is text frame opcode
        }

        /**
         * Sends a binary message to the client.
         * @param payload the payload to send
         * @throws IOException if an I/O error occurs
         */
        public void send(byte[] payload) throws IOException {
            outgoingMessages.add(new WsPayload(payload, 0x02)); // 0x02 is binary frame opcode
        }

        /**
         * Sends a message if the outgoind queue is not empty.
         * Waits maximal 200ms for an outgoing message.
         * <p>This method is to be used in the sender-thread only.</p>
         * @return <code>true</code> if a message has been sent
         * @throws IOException in case of an IO-error
         */
        boolean sendOutgoingMessage() throws IOException {
            WsPayload payload;
            try {
                payload = outgoingMessages.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                LOG.info("Interrupt occurred: " + e.getMessage());
                Thread.currentThread().interrupt();
                return false;
            }
            if (payload == null) {
                return false;
            }
            send(payload.payload(), payload.opcode());
            return true;
        }

        private void send(byte[] payload, int opcode) throws IOException {
            if (isClosed.get()) {
                throw new IOException("WebSocket is closed");
            }

            int length = payload.length;
            byte[] frame;
            int offset = 2;

            // FIN bit and opcode (0x80 for continuation, 0x01 for text, 0x02 for binary)
            byte finOpcode = (byte) (0x80 | (byte) opcode);

            if (length < 126) {
                // Length fits in one byte
                frame = bufTmpOut;
                frame[1] = (byte) length;
            } else if (length <= 0xFFFF) {
                // Length fits in two bytes
                offset += 2;
                frame = (offset + length <= bufTmpOut.length) ? bufTmpOut : new byte[offset + length];
                frame[1] = (byte) 126;
                frame[2] = (byte) ((length >> 8) & 0xFF);
                frame[3] = (byte) (length & 0xFF);
            } else {
                // Length fits in eight bytes
                offset += 8;
                frame = new byte[offset + length];
                frame[1] = (byte) 127;
                for (int i = 0; i < 8; i++) {
                    frame[2 + i] = (byte) (length >> (56 - i * 8));
                }
            }

            frame[0] = finOpcode;
            System.arraycopy(payload, 0, frame, offset, length);

            outputStream.write(frame, 0, offset + length);
            outputStream.flush();
        }

        /**
         * Closes the WebSocket connection.
         * @throws IOException if an I/O error occurs
         */
        public void close() throws IOException {
            if (isClosed.getAndSet(true)) {
                return;
            }
            byte[] closeFrame = new byte[2];
            closeFrame[0] = (byte) 0x88; // Close frame with FIN bit set
            closeFrame[1] = 0x00; // No status code

            try {
                outputStream.write(closeFrame, 0, 2);
                outputStream.flush();
            } finally {
                socket.close();
            }
        }

        /**
         * Reads a message from the WebSocket connection.
         * @return the message or null if the connection is closed
         * @throws IOException if an I/O error occurs
         */
        public String readMessage() throws IOException {
            if (isClosed.get()) {
                return null;
            }

            while (!isStopped.get()) {
                byte[] buffer = bufTmpIn;
                readBlock(buffer, 0, 2);

                int opcode = buffer[0] & 0x0F;
                int length = buffer[1] & 0x7F;
                boolean isMasked = (buffer[1] & 0x80) != 0;

                if (opcode == 0x08) { // Close frame
                    close();
                    return null;
                } else if (opcode == 0x01) { // Text frame
                    // Process the frame
                } else if (opcode == 0x09) { // Ping frame
                    handlePingFrame(buffer, length);
                    continue;
                } else if (opcode == 0x0A) { // Pong frame
                    continue;
                } else {
                    throw new IOException("Unsupported opcode " + opcode);
                }

                // Process the frame
                int lengthFieldSize = 0;
                if (length == 126) {
                    lengthFieldSize = 2;
                    readBlock(buffer, 2, lengthFieldSize);
                    length = ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
                } else if (length == 127) {
                    lengthFieldSize = 8;
                    readBlock(buffer, 2, lengthFieldSize);
                    long lengthLong = 0;
                    for (int i = 0; i < 8; i++) {
                        lengthLong = (lengthLong << 8) | (buffer[2 + i] & 0xFF);
                    }
                    length = (int) lengthLong;
                }

                if (isMasked) {
                    // Read the mask key
                    readBlock(bufTmpMask, 0, 4);
                }

                byte[] payload = (length <= bufTmpIn.length) ? bufTmpIn : new byte[length];
                readBlock(payload, 0, length);

                if (isMasked) {
                    // Unmask the payload
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= bufTmpMask[i % 4];
                    }
                }

                // Convert the payload to string if it's a text frame
                if (opcode == 0x01) {
                    return new String(payload, 0, length, StandardCharsets.UTF_8);
                }

                return null;
            }
            return null;
        }

        private void handlePingFrame(byte[] buffer, int length) throws IOException {
            // Respond with a pong frame
            byte[] pongFrame = new byte[length + 2];
            pongFrame[0] = (byte) 0x8A; // Pong frame with FIN bit set
            pongFrame[1] = (byte) length;

            System.arraycopy(buffer, 2, pongFrame, 2, length);

            outputStream.write(pongFrame);
            outputStream.flush();
        }

        /**
         * Checks if the connection is closed.
         * @return <code>true</code> if closed
         */
        public boolean isClosed() {
            return isClosed.get();
        }

        private void readBlock(byte[] buf, int offset, int len) throws IOException {
            int totalRead = 0;
            while (totalRead < len && !isStopped.get()) {
                int bytesRead = inputStream.read(buf, offset + totalRead, len - totalRead);
                if (bytesRead < 0) {
                    throw new IOException("Unexpected end of stream while reading " + (len - totalRead) + " bytes");
                }
                totalRead += bytesRead;
            }
            if (isStopped.get()) {
                throw new IOException("Server has been stopped");
            }
        }
    }

    /**
     * HTTP handler to perform the WebSocket handshake.
     */
    private class UpgradeHandler implements HttpHandler {
        private final HttpHandler httpHandler;

        public UpgradeHandler(HttpHandler httpHandler) {
            this.httpHandler = httpHandler;
        }

        @Override
        public void handle(HttpServerDispatchExchange exchange) throws IOException {
            String path = exchange.getRequestRawPath().replaceFirst("[?].*", "");
            if ("/".equals(path)) {
                path = "/index.html";
            }
            if (!"GET".equals(exchange.getRequestMethod()) || !pathWebSocket.equals(path)) {
                httpHandler.handle(exchange);
                return;
            }

            // Check for WebSocket upgrade request
            if (!"websocket".equals(exchange.getRequestHeaders().getFirst("Upgrade"))) {
                sendError(exchange, 400, "Invalid WebSocket upgrade request");
                return;
            }

            String secWebSocketKey = exchange.getRequestHeaders().getFirst("Sec-WebSocket-Key");
            if (secWebSocketKey == null) {
                sendError(exchange, 400, "Missing Sec-WebSocket-Key header");
                return;
            }

            String secWebSocketAccept = computeAcceptKey(secWebSocketKey);
            HttpHeaders responseHeaders = exchange.getResponseHeaders();
            responseHeaders.set("Upgrade", "websocket");
            responseHeaders.set("Connection", "keep-alive, Upgrade");
            responseHeaders.set("Sec-WebSocket-Accept", secWebSocketAccept);

            exchange.sendResponseHeaders(101, 0);

            // Create connection
            Socket socket = exchange.getSocket();
            BufferedOutputStream outputStream = exchange.getOutputStream();
            BufferedInputStream inputStream = exchange.getInputStream();

            WebSocketConnection connection = new WebSocketConnection(socket, 
                inputStream, outputStream, isStopped);

            AtomicBoolean wsConnIsActive = new AtomicBoolean(true);

            // Start handling WebSocket messages in a new thread
            Runnable handlerRunnable = () -> {
                try {
                    handler.onOpen(connection);
                    while (!connection.isClosed()) {
                        String message = connection.readMessage();
                        if (message != null) {
                            handler.onMessage(connection, message);
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    handler.onError(connection, e);
                } finally {
                    try {
                        connection.close();
                        handler.onClose(connection, 1000, "Normal closure");
                    } catch (IOException e) {
                        handler.onError(connection, e);
                    }
                    wsConnIsActive.set(false);
                }
            };

            Thread thread = new Thread(handlerRunnable, "WS-Receiver-" + socket.getRemoteSocketAddress());
            thread.start();

            while (wsConnIsActive.get()) {
                connection.sendOutgoingMessage();
            }
            // last outgoing message
            connection.sendOutgoingMessage();
        }

        private void sendError(HttpServerDispatchExchange exchange, int code, String message) throws IOException {
            String response = "HTTP/1.1 " + code + " " + message + "\r\n\r\n";
            exchange.sendResponseHeaders(code, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        }

        private String computeAcceptKey(String secWebSocketKey) {
            try {
                String acceptKey = secWebSocketKey + WEBSOCKET_GUID;
                byte[] sha1Hash = MessageDigest.getInstance("SHA-1").digest(acceptKey.getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(sha1Hash);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("SHA-1 algorithm not found", e);
            }
        }
    }
}

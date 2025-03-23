package org.rogmann.tcpipproxy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages WebSocket-based communication using the Socket.IO protocol (EIO 4).
 * <p>
 * Handles initial handshake, message encoding/decoding, and SID (session ID) management.
 * Uses {@link WebSocketClient} for underlying socket operations and processes packets
 * according to the EIO (Engine.IO) and SIO (Socket.IO) protocol specifications.</p>
 * 
 * <p><strong>This is a partial implementation only!</strong></p>
 */
public class SocketIOClient {

    /** Packet-type of a socket.io-packet (first character) */
    public enum EIOPacketType {
        /** Initial connection open packet /*
        OPEN("0"),
        /** Connection closing packet */
        CLOSE("1"),
        /** Ping for keep-alive */
        PING("2"),
        /** Pong response to a ping */
        PONG("3"),
        /** Data-carrying message packet */
        MESSAGE("4");

        private final String code;

        EIOPacketType(String value) {
            this.code = value;
        }

        public String getCode() {
            return code;
        }

        /**
         * Looks up the EIO packet type based on the packet string prefix.
         * @param packet The packet string (starting with type code)
         * @return Matching packet type, or null if no match
         */
        public static EIOPacketType lookupEIO(String packet) {
            for (EIOPacketType type : EIOPacketType.values()) {
                if (packet.startsWith(type.code)) {
                    return type;
                }
            }
            return null;
        }
    }

    /**
     * Enum representing Socket.IO (SIO) message types within EIO messages.
     * <p>
     * These types define the message category within a {@link EIOPacketType#MESSAGE}.</p>
     */
    public enum SIOPacketType {
        /** Connection request */
        CONNECT('0'),
        /** Disconnection request */
        DISCONNECT('1'),
        /** Custom event message */
        EVENT('2'),
        /** No message type (used for non-message packets) */
        NONE(' ');

        private final char code;

        SIOPacketType(char value) {
            this.code = value;
        }

        public char getCode() {
            return code;
        }

        /**
         * Looks up the SIO packet type based on the character code.
         * @param cSIO The single-character type identifier
         * @return Matching packet type, or {@link SIOPacketType#NONE} if invalid
         */
        public static SIOPacketType lookup(char cSIO) {
            for (SIOPacketType type : SIOPacketType.values()) {
                if (type.code == cSIO) {
                    return type;
                }
            }
            return NONE;
        }
    }

    private final WebSocketClient wsConn;

    /** First session ID obtained during handshake */
    private final String sid;
    /** Second session ID (if used in multi-step handshake) */
    private final String sid2;

    /** Represents a Socket.IO packet with EIO type, SIO type, and payload */
    public record SocketIOPacket(EIOPacketType eioPacketType, SIOPacketType sioPacketType, String message) { }

    /**
     * Initializes the Socket.IO client with an existing WebSocket connection.
     * <p>
     * Performs the following steps:</p>
     * <ul>
     * <li>1. Reads initial OPEN packet to retrieve the first SID.</li>
     * <li>2. Sends a CONNECT message.</li>
     * <li>3. Reads the second handshake packet to retrieve the second SID.</li>
     * </ul>
     * @param wsConn Underlying WebSocket connection
     * @throws IOException If handshake fails or I/O error occurs
     */
    public SocketIOClient(WebSocketClient wsConn) throws IOException {
        this.wsConn = wsConn;

        boolean isOk = false;
        try {
            // We read the initial OPEN packet.
            SocketIOPacket packetInit = read(wsConn.read());
            System.out.println("Init response: " + packetInit);
            Map<String, Object> jsonInit = LightweightJsonHandler.parseJsonDict(packetInit.message());
            sid = LightweightJsonHandler.getJsonValue(jsonInit, "sid", String.class);
            System.out.println("SID: " + sid);

            // We send a MESSAGE-CONNECT packet.
            writeMessage(SIOPacketType.CONNECT, "");

            String context = "message-connect response";
            SocketIOPacket packetInit2 = readMessage(context);
            System.out.println("Init response 2: " + packetInit2);
            Map<String, Object> jsonInit2 = LightweightJsonHandler.parseJsonDict(packetInit2.message());
            sid2 = LightweightJsonHandler.getJsonValue(jsonInit2, "sid", String.class);
            System.out.println("SID 2: " + sid2);
            isOk = true;
        } finally {
            if (!isOk) {
                wsConn.close();
            }
        }
    }

    /**
     * Reads a WebSocket frame and parses it into a Socket.IO packet.
     * @param context Context for error reporting (e.g., "initial handshake")
     * @return Parsed SocketIOPacket
     * @throws IOException If packet is malformed or missing
     */
    private SocketIOPacket readMessage(String context) throws IOException {
        byte[] bufRead = wsConn.read();
        if (bufRead == null) {
            throw new IOException("Missing answer packet: " + context);
        }
        SocketIOPacket packetInit2 = read(bufRead);
        return packetInit2;
    }

    /**
     * Sends a Socket.IO message over the WebSocket connection.
     * @param sioPacketType Type of message (e.g., EVENT)
     * @param message Payload data (JSON string or raw text)
     * @throws IOException If encoding or sending fails
     */
    public void writeMessage(SIOPacketType sioPacketType, String message) throws IOException {
        wsConn.write(write(new SocketIOPacket(EIOPacketType.MESSAGE, sioPacketType, message)));
    }

    /**
     * Decodes a WebSocket byte array into a SocketIOPacket.
     * @param buf Raw byte array from WebSocket
     * @return Parsed SocketIOPacket
     * @throws IOException If packet type is invalid or structure is malformed
     */
    public static SocketIOPacket read(final byte[] buf) throws IOException {
        String sPacket = new String(buf, StandardCharsets.UTF_8);
        if (sPacket.isEmpty()) {
            throw new IOException("Unexpected end-of-stream (empty ws-packet)");
        }
        EIOPacketType eio = EIOPacketType.lookupEIO(sPacket);
        if (eio == null) {
            throw new IOException("Missing EIO-packet-type in packet: " + sPacket);
        }
        String msg;
        SIOPacketType sio;
        if (eio == EIOPacketType.MESSAGE) {
            if (sPacket.length() == 1) {
                throw new IOException("EIO-message too short: " + sPacket);
            }
            sio = SIOPacketType.lookup(sPacket.charAt(1));
            msg = sPacket.substring(2);
        } else {
            sio = SIOPacketType.NONE;
            msg = sPacket.substring(1);
        }
        return new SocketIOPacket(eio, sio, msg);
    }

    /**
     * Encodes a SocketIOPacket into a WebSocket-ready byte array.
     * @param packet Packet to encode
     * @return Encoded byte array
     * @throws IOException If message contains invalid characters
     */
    public static byte[] write(SocketIOPacket packet) throws IOException {
        StringBuilder sb = new StringBuilder();

        // Append the EIOPacketType
        sb.append(packet.eioPacketType().getCode());

        // Append the SIOPacketType if it's a MESSAGE packet
        if (packet.eioPacketType() == EIOPacketType.MESSAGE) {
            sb.append(packet.sioPacketType().getCode());
        }

        // Append the message
        sb.append(packet.message());

        // Convert the StringBuilder to a byte array
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Closes the WebSocket connection and terminates the Socket.IO session.
     * @throws IOException If closing the underlying connection fails
     */
    public void close() throws IOException {
        wsConn.close();
    }

    /**
     * Establishes a new Socket.IO connection to a server.
     * @param destHost Server hostname/IP
     * @param destPort Server port
     * @return Configured SocketIOClient instance
     * @throws IOException If connection fails or handshake errors occur
     */
    public static SocketIOClient connect(String destHost, int destPort) throws IOException {
        WebSocketClient wsConn = WebSocketClient.connect(destHost, destPort,
                "/socket.io/?EIO=4&transport=websocket", destHost + ':' + destPort);
        return new SocketIOClient(wsConn);
    }

    /**
     * Example main method demonstrating Socket.IO client usage:
     * <ol>
     *     <li>Connects to a local Socket.IO server at {@code 127.0.0.1:3000}.</li>
     *     <li>Sends a JSON-formatted event message containing:
     *         <ul>
     *             <li>Type: <code>"message"</code></li>
     *             <li>Payload: <code>{"key": "value", "content": "Hallo Websocket!"}</code></li>
     *         </ul>
     *     </li>
     *     <li>Reads and displays the server's response (parsed as a JSON array).</li>
     *     <li>Gracefully closes the connection.</li>
     * </ol>
     * This serves as a basic usage example for testing/development purposes.
     * @throws Exception If connection, message sending, or parsing errors occur
     */
    public static void main(String[] args) throws Exception {
        System.out.println("Step 1: Init web-socket");
        SocketIOClient client = connect("127.0.0.1", 3000);

        List<Object> listMessage = new ArrayList<>();
        listMessage.add("message");
        Map<String, Object> mapMessage = new LinkedHashMap<>();
        mapMessage.put("key", "value");
        mapMessage.put("content", "Hallo Websocket!");
        listMessage.add(mapMessage);
        StringBuilder sbMsg = new StringBuilder();
        LightweightJsonHandler.dumpJson(sbMsg, listMessage);
        client.writeMessage(SIOPacketType.EVENT, sbMsg.toString());
        
        SocketIOPacket packetMsgResp = client.readMessage("first message-response");
        System.out.println("Response-message: " + LightweightJsonHandler.parseJsonArray(packetMsgResp.message()));

        client.close();
    }

}

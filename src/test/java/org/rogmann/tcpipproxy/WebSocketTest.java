package org.rogmann.tcpipproxy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class WebSocketTest {

    public static void main(String[] args) throws IOException {
        WebSocketClient wsConn = WebSocketClient.connect("127.0.0.1", 3000,
                "/socket.io/?EIO=4&transport=websocket&sid=KPq9qisFRN8PG5-3AAAE", "127.0.0.1:3000");
        wsConn.write("Hallo".getBytes(StandardCharsets.UTF_8));
        byte[] buf = wsConn.read();
        System.out.println("Answer: " + new String(buf, StandardCharsets.UTF_8));
    }

}

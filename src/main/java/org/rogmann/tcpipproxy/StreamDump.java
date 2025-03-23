package org.rogmann.tcpipproxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.rogmann.tcpipproxy.StreamRouter.TransferSockets;

/**
 * Handles bidirectional streaming of data between client and remote sockets,
 * including logging, content modification (search/replace), and dynamic
 * connection routing via a {@link StreamRouter}.
 * <p>
 * This class operates as a thread (Runnable) to read from an input stream,
 * process the data (modify content, log messages), and write to an output stream.
 * It supports directional routing (C2R/R2C) and tracks message statistics.</p>
 */
class StreamDump implements Runnable {
    private static final int MAX_MSGS_DISPLAY = Integer.parseInt(System.getProperty("max.msgs.display", "999999999"));

    private final InputStream is;
    private final OutputStream os;
    private final Direction direction;
    private final String kuerzel;
    private final Consumer<String> ausgabe;
    private final AtomicBoolean doStop;
    private final List<SearchReplace> searchReplaces;
    private StreamRouter router;
    private final AtomicLong msgCounter = new AtomicLong();

    /** Direction enumeration indicating data flow direction between client and remote. */
    enum Direction {
        /** Data from client (input) to remote (output). */
        C2R,
        /** Data from remote (input) to client (output). */
        R2C;
    }

    /**
     * Constructs a StreamDump thread to handle data between client and remote.
     * @param is Input stream to read from.
     * @param os Output stream to write to.
     * @param direction Data flow direction (C2R/R2C).
     * @param kuerzel Short identifier for the connection (e.g., client name).
     * @param ausgabe Output consumer for logging/debugging.
     * @param doStop Atomic flag to signal thread termination.
     * @param searchReplaces List of search/replace operations to apply to data.
     */
    public StreamDump(InputStream is, OutputStream os, Direction direction, String kuerzel, Consumer<String> ausgabe, AtomicBoolean doStop, List<SearchReplace> searchReplaces) {
        this.is = is;
        this.os = os;
        this.direction = direction;
        this.kuerzel = kuerzel;
        this.ausgabe = ausgabe;
        this.doStop = doStop;
        this.searchReplaces = searchReplaces;
    }

    @Override
    public void run() {
        byte[] buf = new byte[65536];
        var df = new SimpleDateFormat("yyyyMMdd-HHmmss.SSS");
        InputStream currIs = is;
        OutputStream currOs = os;

        long totalBytes = 0L;          // Total bytes processed
        long lastStatsTime = 0L;       // Last time statistics were logged
        int maxNumMsgs = MAX_MSGS_DISPLAY;

        try {
            while (!doStop.get()) {
                if (router != null && direction == Direction.C2R) {
                    TransferSockets newSockets = router.pullNewClient();
                    if (newSockets != null) {
                        String timestamp = df.format(new Date());
                        ausgabe.accept("#" + timestamp + " " + direction + ' ' + kuerzel + ": new client " + newSockets.socketNewClient().getRemoteSocketAddress());
                        currIs = newSockets.socketNewClient().getInputStream();

                        Socket sockOldClnt = newSockets.socketMsgsOldClient();
                        String clientName = String.format("MSGS-%s-%s", kuerzel, sockOldClnt.getRemoteSocketAddress());
                        StreamDump streamC2R = new StreamDump(is, sockOldClnt.getOutputStream(), Direction.C2R, clientName, ausgabe, doStop, searchReplaces);
                        new Thread(streamC2R, "C2R-msgs-" + clientName).start();
                    }
                }

                // ausgabe.accept("#" + df.format(new Date()) + " " + direction + ' ' + kuerzel + ": before read");
                int bytesRead = currIs.read(buf);
                // ausgabe.accept("#" + df.format(new Date()) + " " + direction + ' ' + kuerzel + ": read " + bytesRead);
                if (bytesRead == -1) {
                    break;
                }
                if (bytesRead == 0) {
                    continue;
                }
                if (router != null && direction == Direction.C2R) {
                    TransferSockets newSockets = router.pullNewClient();
                    if (newSockets != null) {
                        String timestamp = df.format(new Date());
                        ausgabe.accept("#" + timestamp + " " + direction + ' ' + kuerzel + ": verworfen " + new String(buf, 0, bytesRead, StandardCharsets.ISO_8859_1));
                        ausgabe.accept("#" + timestamp + " " + direction + ' ' + kuerzel + ": new client " + newSockets.socketNewClient().getRemoteSocketAddress());
                        currIs = newSockets.socketNewClient().getInputStream();
                        InputStream prefixedIs = new PrefixedInputStream(Arrays.copyOfRange(buf, 0, bytesRead), is);

                        Socket sockOldClnt = newSockets.socketMsgsOldClient();
                        String clientName = String.format("MSGS-%s-%s", kuerzel, sockOldClnt.getRemoteSocketAddress());
                        StreamDump streamC2R = new StreamDump(prefixedIs, newSockets.socketMsgsOldClient().getOutputStream(), Direction.C2R, clientName, ausgabe, doStop, searchReplaces);
                        new Thread(streamC2R, "C2R-msgs-" + clientName).start();
                        continue;
                    }
                }
                // ausgabe.accept("#" + df.format(new Date()) + " " + direction + ' ' + kuerzel + ": process " + bytesRead);

                totalBytes += bytesRead; // Track total bytes

                long msgNo = msgCounter.incrementAndGet();
                String timestamp = df.format(new Date());
                String sContent = new String(buf, 0, bytesRead, StandardCharsets.ISO_8859_1);
                if (sContent.contains("Connection: upgrade") || sContent.contains("Sec-WebSocket")) {
                    maxNumMsgs = 999;
                }
                String sContentMod = sContent;
                for (SearchReplace sr : searchReplaces) {
                    sContentMod = sContentMod.replace(sr.search(), sr.replace());
                }
                String escapedContent = escapeContent(sContent);
                if (msgNo <= maxNumMsgs || sContent.startsWith("GET ") || sContent.startsWith("POST ")) {
                    String contDispl = (escapedContent.length() < 500) ? escapedContent : escapedContent.substring(0, 500) + "[...]";
                    ausgabe.accept("#" + timestamp + " " + direction + ' ' + kuerzel + ":\n" + contDispl);
                }
                if (sContent.equals(sContentMod)) {
                    currOs.write(buf, 0, bytesRead);
                } else {
                    if (msgNo <= maxNumMsgs) {
                        ausgabe.accept("#" + timestamp + " " + direction + ' ' + kuerzel + " modified\n" + escapeContent(sContentMod));
                    }
                    currOs.write(sContentMod.getBytes(StandardCharsets.ISO_8859_1));
                }
                currOs.flush();

                if (router != null && direction == Direction.R2C) {
                    TransferSockets newSockets = router.checkForSwitchMessage(sContent);
                    if (newSockets != null) {
                        ausgabe.accept("#" + timestamp + " " + direction + ' ' + kuerzel + ": socket llm-ws " + newSockets.socketNewClient());
                        ausgabe.accept("#" + timestamp + " " + direction + ' ' + kuerzel + ": socket msg-ws " + newSockets.socketMsgsOldClient());
                        ausgabe.accept("#" + timestamp + " " + direction + ' ' + kuerzel + ": transfer-socket " + newSockets.socketNewClient());
                        currOs = newSockets.socketNewClient().getOutputStream();

                        Socket sockOldClnt = newSockets.socketMsgsOldClient();
                        String clientName = String.format("MSGS-%s-%s", kuerzel, sockOldClnt.getLocalAddress());
                        StreamDump streamR2C = new StreamDump(sockOldClnt.getInputStream(), os, Direction.R2C, clientName, ausgabe, doStop, searchReplaces);
                        new Thread(streamR2C, "R2C-msgs-" + clientName).start();
                    }
                }

                // Check for statistics logging
                if (msgNo > maxNumMsgs) {
                    long now = System.currentTimeMillis();
                    if (now - lastStatsTime >= 10_000) {
                        String statMessage = String.format(
                            "# %s %s %s Statistics: Packets=%d, Total Bytes=%d",
                            df.format(new Date()), direction, kuerzel,
                            msgCounter.get(), totalBytes
                        );
                        ausgabe.accept(statMessage);
                        lastStatsTime = now;
                    }
                }
            }
        } catch (IOException e) {
            if ("Socket closed".equals(e.getMessage())) {
                String timestamp = df.format(new Date());
                ausgabe.accept("#" + timestamp + " " + kuerzel + ": closed");
            } else if ("Connection or inbound has closed".equals(e.getMessage())) {
                String timestamp = df.format(new Date());
                ausgabe.accept("#" + timestamp + " " + kuerzel + ": " + e.getMessage());
            } else {
                e.printStackTrace();
            }
        } finally {
            doStop.set(true);
            try {
                currIs.close();
                currOs.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        String statMessage = String.format(
                "# %s %s %s Connection closed: Packets=%d, Total Bytes=%d",
                df.format(new Date()), direction, kuerzel,
                msgCounter.get(), totalBytes
            );
        ausgabe.accept(statMessage);
    }

    /**
     * Sets the StreamRouter instance to handle connection routing logic.
     * @param router Router for managing client/remote connections
     */
    public void setRouter(StreamRouter router) {
        this.router = router;
    }

    /**
     * Escapes control characters and non-printable characters in content.
     * @param content Raw content to process
     * @return Escaped content with readable representations for logs
     */
    private String escapeContent(String content) {
        StringBuilder escaped = new StringBuilder();
        for (char c : content.toCharArray()) {
            if (c == '\n') {
                escaped.append("\\n");
            } else if (c == '\t') {
                escaped.append("\\t");
            } else if (c == '\r') {
                escaped.append("\\r");
            } else if (c == '\\') {
                escaped.append("\\\\");
            } else if (c < 32 || c > 126) {
                escaped.append(String.format("\\u%04X", (int) c));
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }
}

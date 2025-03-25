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

    private static final String HEADER_CONTENT_LENGTH = "Content-Length: ";

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
                if (!sContent.equals(sContentMod)) {
                    // Do we have to adjust a Content-Length-header?
                    sContentMod = adjustContentLength(sContent, sContentMod, ausgabe);
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

    record HttpRequestStart(String[] headersLines, int contentLength, int contentLengthIndex, String bodyPart, int bodyLen) { }

    /**
     * Checks if the content-length of the modified body has to be adjusted.
     * @param contentOrig original request
     * @param contentMod modified request
     * @param ausgabe consumer of messages
     * @return modified request
     */
    static String adjustContentLength(String contentOrig, String contentMod, Consumer<String> ausgabe) {
        if (!contentOrig.contains("HTTP/1.")) {
            return contentMod;
        }
        HttpRequestStart reqOrig = parseHttpRequest(contentOrig, ausgabe);
        HttpRequestStart reqMod = parseHttpRequest(contentMod, ausgabe);
        if (reqOrig == null || reqMod == null) {
            return contentMod;
        }
        if (reqOrig.contentLength() != reqOrig.bodyLen()) {
            // The content doesn't contain the complete body.
            return contentMod;
        }

        final int contentLengthAdjusted = reqOrig.contentLength
                + reqMod.bodyLen - reqOrig.bodyLen;

        // Update the header with new length (in bytes)
        reqMod.headersLines()[reqMod.contentLengthIndex] = "Content-Length: " + contentLengthAdjusted;
        String newHeaders = String.join("\r\n", Arrays.asList(reqMod.headersLines()));
        String newContent = newHeaders + "\r\n\r\n" + reqMod.bodyPart;

        // Log the adjustment
        ausgabe.accept(String.format("Content-Length adjusted from %d to %d",
            reqOrig.contentLength, contentLengthAdjusted));

        return newContent;
    }

    static HttpRequestStart parseHttpRequest(String content, Consumer<String> ausgabe) {
        int bodyStartIndex = content.indexOf("\r\n\r\n");
        if (bodyStartIndex == -1) {
            return null; // No end-of-headers present
        }

        String headersPart = content.substring(0, bodyStartIndex);
        String body = content.substring(bodyStartIndex + 4); // Skip \r\n\r\n

        String[] headersLines = headersPart.split("\r\n");
        int contentLengthIndex = -1;
        String contentLengthValue = null;

        // Find case-insensitive "Content-Length" header
        for (int i = 0; i < headersLines.length; i++) {
            String line = headersLines[i];
            if (line.regionMatches(true, 0, HEADER_CONTENT_LENGTH, 0, HEADER_CONTENT_LENGTH.length())) {
                // Extract value (after colon and trimming whitespace)
                int colonPos = line.indexOf(':');
                if (colonPos != -1) {
                    contentLengthValue = line.substring(colonPos + 1).trim();
                    contentLengthIndex = i;
                    break;
                }
            }
        }

        if (contentLengthIndex == -1) {
            return null; // No Content-Length header present
        }

        // Calculate the body length in UTF-8 bytes
        int bodyLength = body.getBytes(StandardCharsets.UTF_8).length;

        int contentLength;
        try {
            contentLength = Integer.parseInt(contentLengthValue);
        } catch (NumberFormatException e) {
            // Invalid current value; skip adjustment
            ausgabe.accept("Invalid content-length: " + contentLengthValue); 
            return null;
        }

        return new HttpRequestStart(headersLines, contentLength, contentLengthIndex, body, bodyLength);
    }

    /**
     * Escapes control characters and non-printable characters in content.
     * @param content Raw content to process
     * @return Escaped content with readable representations for logs
     */
    static String escapeContent(String content) {
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

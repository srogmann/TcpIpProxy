package org.rogmann.tcpipproxy.http;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents a single HTTP request/response exchange.
 * Provides access to the underlying socket for WebSocket upgrades.
 */
public class HttpServerDispatchExchange {
    private static final Logger LOGGER = Logger.getLogger(HttpServerDispatchExchange.class.getName());

    private final Socket socket;
    private final BufferedInputStream inputStream;
    private final BufferedOutputStream outputStream;
    private final String method;
    private final String rawPath;
    private final String protocol;
    private final HttpHeaders requestHeaders;
    private final boolean keepAlive;

    private final HttpHeaders responseHeaders = new HttpHeaders(false);
    private boolean responseHeadersSent = false;
    private boolean upgradeRequested = false;

	private boolean isResponseChunked;

    /**
     * Creates a new HTTP exchange.
     * 
     * @param socket the underlying socket
     * @param inputStream the input stream
     * @param outputStream the output stream
     * @param method the HTTP method
     * @param rawpath raw path of the request URI
     * @param protocol the HTTP protocol version
     * @param requestHeaders the request headers
     * @param keepAlive whether connection should be kept alive
     */
    public HttpServerDispatchExchange(
            Socket socket,
            BufferedInputStream inputStream,
            BufferedOutputStream outputStream,
            String method,
            String rawPath,
            String protocol,
            HttpHeaders requestHeaders,
            boolean keepAlive) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format("method %s, raw path %s, socket %s", method, rawPath, socket));
            LOGGER.finer("HTTP-headers: " + requestHeaders);
        }
        this.socket = socket;
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.method = method;
        this.rawPath = rawPath;;
        this.protocol = protocol;
        this.requestHeaders = requestHeaders;
        this.keepAlive = keepAlive;
    }

    /**
     * Gets the HTTP method.
     * 
     * @return the method (GET, POST, etc.)
     */
    public String getRequestMethod() {
        return method;
    }

    /**
     * Gets the request path.
     * 
     * @return the raw path
     */
    public String getRequestRawPath() {
        return rawPath;
    }

    public URI getRequestURI() {
        return getRequestURI(null);
    }

    public URI getRequestURI(String pathPrefix) {
        String path = rawPath;
        if (pathPrefix != null && path.startsWith(pathPrefix)) {
            path = '/' + path.substring(pathPrefix.length());
        }
        return URI.create(String.format("http://%s:%d%s",
                socket.getLocalAddress().getHostAddress(),
                socket.getLocalPort(),
                path));
    }

    /**
     * Gets the request headers.
     * 
     * @return the request headers
     */
    public HttpHeaders getRequestHeaders() {
        return requestHeaders;
    }

    /**
     * Gets the request body as an InputStream.
     * 
     * @return the request body input stream
     */
    public InputStream getRequestBody() {
        return new HttpInputStream(inputStream, requestHeaders);
    }

    /**
     * Gets the response headers.
     * 
     * @return the response headers
     */
    public HttpHeaders getResponseHeaders() {
        return responseHeaders;
    }

    /**
     * Gets the response body as an OutputStream.
     * 
     * @return the response body output stream
     * @throws IOException if response headers haven't been sent yet
     */
    public OutputStream getResponseBody() throws IOException {
        if (!responseHeadersSent) {
            throw new IllegalStateException("Response headers not sent yet");
        }
        return new HttpOutputStream(outputStream, isResponseChunked);
    }

    public BufferedOutputStream getOutputStream() {
        return outputStream;
    }

    public BufferedInputStream getInputStream() {
        return inputStream;
    }

    /**
     * Sends the response headers.
     *
     * @param statusCode the HTTP status code
     * @param contentLength the content length, or -1 for chunked encoding
     * @throws IOException if an I/O error occurs
     */
    public void sendResponseHeaders(int statusCode, long contentLength) throws IOException {
        // Add status text
        String statusMessage = switch (statusCode) {
            case 101 -> "Upgrade to WebSocket-Connection";
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            default -> "Unknown";
        };
        sendResponseHeaders(statusCode, statusMessage, contentLength);
    }

    /**
     * Sends the response headers.
     * 
     * @param statusCode the HTTP status code
     * @param statusMessage status message
     * @param contentLength the content length, or -1 for chunked encoding
     * @throws IOException if an I/O error occurs
     */
    public void sendResponseHeaders(int statusCode, String statusMessage, long contentLength) throws IOException {
        if (responseHeadersSent) {
            throw new IllegalStateException("Response headers already sent");
        }

        StringBuilder responseLine = new StringBuilder();
        responseLine.append(protocol).append(" ").append(statusCode).append(" ");
        responseLine.append(statusMessage);
        responseLine.append("\r\n");

        if (statusCode != 101) {
            // Add standard headers
            if (!responseHeaders.containsKey("Connection")) {
                responseHeaders.set("Connection", keepAlive ? "keep-alive" : "close");
            }

            if (contentLength > 0) {
                responseHeaders.set("Content-Length", String.valueOf(contentLength));
            } else if (statusCode != 204 && statusCode != 304) {
                responseHeaders.set("Transfer-Encoding", "chunked");
                isResponseChunked = true;
            }
        }

        // Write response line and headers
        outputStream.write(responseLine.toString().getBytes());
        responseHeaders.forEach((key, values) -> {
            for (String value : values) {
                String headerLine = key + ": " + value + "\r\n";
                outputStream.write(headerLine.getBytes(StandardCharsets.ISO_8859_1));
            }
        });
        outputStream.write("\r\n".getBytes());
        outputStream.flush();

        responseHeadersSent = true;
    }

    /**
     * Gets the underlying socket for WebSocket upgrades.
     * 
     * @return the socket
     */
    public Socket getSocket() {
        return socket;
    }

    /**
     * Checks if a WebSocket upgrade was requested.
     * 
     * @return true if upgrade requested
     */
    public boolean isUpgradeRequested() {
        return upgradeRequested;
    }

    /**
     * Requests a protocol upgrade (e.g., to WebSocket).
     * This should be called before sending response headers.
     */
    public void requestUpgrade() {
        upgradeRequested = true;
        responseHeaders.set("Connection", "Upgrade");
        responseHeaders.set("Upgrade", "websocket");
    }

    public void close() throws IOException {
        socket.close();
    }

}
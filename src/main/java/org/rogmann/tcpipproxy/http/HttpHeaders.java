package org.rogmann.tcpipproxy.http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * Headers of a HTTP request or HTTP response.
 */
public class HttpHeaders {

    private final Map<String, List<String>> headers;

    private final boolean isReadOnly;

    @FunctionalInterface
    interface HeaderConsumer {
        void accept(String key, List<String> values) throws IOException;
    }

    /**
     * Constructor
     * @param isReadOnly <code>true</code> if headers a read-only
     */
    public HttpHeaders(boolean isReadOnly) {
        this.headers = new LinkedHashMap<>();
        this.isReadOnly = isReadOnly;
    }

    /**
     * Constructor
     * @param isReadOnly <code>true</code> if headers a read-only
     */
    public HttpHeaders(Map<String, List<String>> headers, boolean isReadOnly) {
        this.headers = headers.entrySet()
                .stream()
                .collect(Collectors.toMap(entry -> normalize(entry.getKey()), Map.Entry::getValue));
        this.isReadOnly = isReadOnly;
    }

    /**
     * Adds the given {@code value} to the list of headers for the given
     * {@code key}. If the mapping does not already exist, then it is created.
     *
     * @param key   the header name
     * @param value the value to add to the header
     */
    public void add(String key, String value) {
        if (isReadOnly) {
            throw new IllegalStateException("headers are read-only");
        }
        List<String> list = headers.computeIfAbsent(normalize(key), k -> new ArrayList<>());
        list.add(value);
    }

    /**
     * Sets the given {@code value} as the sole header value for the given
     * {@code key}. If the mapping does not already exist, then it is created.
     *
     * @param key   the header name
     * @param value the header value to set
     */
    public void set(String key, String value) {
        if (isReadOnly) {
            throw new IllegalStateException("headers are read-only");
        }
        headers.put(key, List.of(value));
    }

    /**
     * Gets the first value of a HTTP headers.
     * @param key key
     * @return value or <code>null</code>
     */
    public String getFirst(String key) {
        List<String> values = headers.get(normalize(key));
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    public boolean containsKey(String key) {
        return headers.containsKey(normalize(key));
    }

    static String normalize(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Invalid key: " + key);
        }
        boolean isOk = true;
        if (key.charAt(0) >= 'a' && key.charAt(0) <= 'z') {
            isOk = false;
        } else {
            for (int i = 1; i < key.length(); i++) {
                char c = key.charAt(i);
                if (c >= 'A' && c <= 'Z') {
                    isOk = false;
                    break;
                }
            }
        }
        if (isOk) {
            return key;
        }
        StringBuilder sb = new StringBuilder(key.length());
        char c = key.charAt(0);
        if (c >= 'a' && c <= 'z') {
            sb.append((char) (c + 'A' - 'a'));
        } else {
            sb.append(c);
        }
        for (int i = 1; i < key.length(); i++) {
            c = key.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                sb.append((char) (c - 'A' + 'a'));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public void setAll(Map<String, String> headers) {
        headers.forEach((k, v) -> set(k, v));
    }
    
    public void forEach(HeaderConsumer consumer) throws IOException{
        for (Entry<String, List<String>> entry : headers.entrySet()) {
            consumer.accept(entry.getKey(), entry.getValue());
        }
    }
}

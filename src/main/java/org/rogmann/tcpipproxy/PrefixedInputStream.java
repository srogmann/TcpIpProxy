package org.rogmann.tcpipproxy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * An input stream that first returns bytes from a given prefix array,
 * and then reads from an underlying input stream once the prefix is exhausted.
 */
public class PrefixedInputStream extends InputStream {
    private final byte[] prefix;
    private final InputStream is;
    private int pos;

    /**
     * Creates a new PrefixedInputStream with the specified prefix and underlying stream.
     * 
     * @param bufPrefix the prefix byte array (must not be null)
     * @param is the underlying input stream (must not be null)
     */
    public PrefixedInputStream(byte[] bufPrefix, InputStream is) {
        this.prefix = Objects.requireNonNull(bufPrefix, "prefix cannot be null");
        this.is = Objects.requireNonNull(is, "underlying stream cannot be null");
        this.pos = 0;
    }

    @Override
    public int read() throws IOException {
        if (pos < prefix.length) {
            return prefix[pos++] & 0xff;
        }
        return is.read();
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        // Read remaining prefix bytes first
        if (pos < prefix.length) {
            int remainingPrefix = prefix.length - pos;
            int copy = Math.min(remainingPrefix, len);
            System.arraycopy(prefix, pos, b, off, copy);
            pos += copy;
            off += copy;
            len -= copy;
            return copy;
        }

        // Read remaining bytes from underlying stream
        if (len > 0) {
            return is.read(b, off, len);
        }

        return -1;
    }

    @Override
    public void close() throws IOException {
        is.close();
    }
}

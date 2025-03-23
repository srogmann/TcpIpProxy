package org.rogmann.tcpipproxy;

/**
 * Represents a search-and-replace operation pair for modifying data streams.
 * <p>
 * This record holds a search string and its replacement string, used to perform
 * literal string substitution in data processed by the proxy.</p>
 */
public record SearchReplace(String search, String replace) {

    /**
     * Constructs a search-replace pair.
     * @param search The literal string to search for
     * @param replace The literal string to substitute in place of {@code search}
     */
    public SearchReplace {
        // No validation shown here; add if needed (e.g., non-null, non-empty checks)
    }
}

package com.confia.imports.application;

/**
 * What the history asks for: a page counted from zero and a size of at most {@link #MAX_SIZE}. The order is fixed
 * (newest first). The edge validates the same bounds first; this is the second line of defense.
 */
public record PageRequest(int page, int size) {

    public static final int MAX_SIZE = 100;

    public PageRequest {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("page from 0 and size from 1 to " + MAX_SIZE);
        }
    }

    /** The offset of the first row, computed in long so a huge page cannot overflow. */
    public long offset() {
        return (long) page * size;
    }
}

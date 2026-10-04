package com.jabiz.runtime.query;

import java.util.Optional;

/**
 * The total of a list told by one of its pages, so that it need not be counted (phase 14q): a page with fewer rows
 * than asked for is the last, and the total is its offset plus its rows. An empty page past the first says nothing
 * (the offset may be beyond the end), nor does a full one.
 */
public final class PageTotals {

    /** The total, when the page tells it. */
    public static Optional<Long> known(int offset, int limit, int rows) {
        return rows < limit && (rows > 0 || offset == 0) ? Optional.of((long) offset + rows) : Optional.empty();
    }

    private PageTotals() {}
}

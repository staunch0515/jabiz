package com.jabiz.dataset;

/**
 * A dataset scope needs a value the request context does not provide (for example a tenant). The request is
 * rejected rather than served without the filter.
 */
public class ScopeUnavailableException extends RuntimeException {

    private final String field;
    private final String source;

    public ScopeUnavailableException(String field, String source) {
        super("Dataset scope field [" + field + "] needs a value from the request context (" + source
            + "), but none is available");
        this.field = field;
        this.source = source;
    }

    public String field() {
        return field;
    }

    public String source() {
        return source;
    }
}

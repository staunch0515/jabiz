package com.jabiz.external;

import java.util.Objects;

/**
 * A call to an external service that did not get an answer the caller can use (decision D38): answered 502
 * {@code EXTERNAL_CALL_FAILED}. The message names the service and what went wrong, never the address's path or
 * query, a header or a body, which may carry credentials.
 */
public class ExternalCallException extends RuntimeException {

    /** What went wrong, also the {@code outcome} of the call's observation where it is one. */
    public enum Kind {
        /** The platform did not send it: a host outside the service's list, a dry run, an undeclared header. */
        REFUSED,
        /** No complete answer within the timeout. */
        TIMEOUT,
        /** The answer was larger than the service's limit. */
        TOO_LARGE,
        /** The connection failed. */
        UNAVAILABLE,
        /** An answer the step cannot use (its own judgement, e.g. a 5xx status). */
        BAD_RESPONSE
    }

    private final String service;
    private final Kind kind;

    public ExternalCallException(String service, Kind kind, String detail) {
        super("External service " + service + ": " + detail);
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
    }

    /** A step's verdict on an answer with an unusable status. */
    public static ExternalCallException badResponse(String service, int status) {
        return new ExternalCallException(service, Kind.BAD_RESPONSE, "answered " + status);
    }

    public String service() {
        return service;
    }

    public Kind kind() {
        return kind;
    }
}

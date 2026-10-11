package com.jabiz.inbound;

import java.time.Instant;

/**
 * What a signature scheme made of an inbound webhook request: accepted, with the time the sender signed it, or
 * rejected for a reason. The reason is for logs and metrics only; the sender is told nothing but that the request
 * was not accepted (decision D37).
 *
 * @param accepted   whether the signature is valid and within the time tolerance
 * @param reason     why not; null when accepted
 * @param signedTime the time the sender signed it; null when rejected
 */
public record InboundVerification(boolean accepted, Reason reason, Instant signedTime) {

    /** Why a request was not accepted. */
    public enum Reason {
        /** A header the scheme needs is absent. */
        MISSING_HEADER,
        /** A header is there but not in the scheme's form (a timestamp twice or not a number, say). */
        MALFORMED,
        /** No signature matches any configured secret. */
        NO_MATCH,
        /** Validly signed, but longer ago than the tolerance (a replay, or a delivery held up too long). */
        TOO_OLD,
        /** Validly signed, but further in the future than the tolerance. */
        TOO_NEW
    }

    public InboundVerification {
        if (accepted == (reason != null) || accepted == (signedTime == null)) {
            throw new IllegalArgumentException("An accepted verification has a signed time and no reason; "
                + "a rejected one a reason and no time");
        }
    }

    public static InboundVerification accepted(Instant signedTime) {
        return new InboundVerification(true, null, signedTime);
    }

    public static InboundVerification rejected(Reason reason) {
        return new InboundVerification(false, reason, null);
    }
}

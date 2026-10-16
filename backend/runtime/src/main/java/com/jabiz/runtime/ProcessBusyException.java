package com.jabiz.runtime;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The queue before a hot named lock did not admit a process (decision D45): it was full, or the wait in it ran out.
 * 503 {@code PROCESS_BUSY} with {@code Retry-After}: a shared resource is overloaded, not this caller asking too much
 * (that is {@link RateLimitedException}, 429). Nothing was read or written; the request may be tried again. A busy item
 * of a job. Carries the family only, never the key (it may name a record).
 */
public class ProcessBusyException extends RuntimeException {

    /** Why the process was not admitted. */
    public enum Reason {
        /** The key's queue, or all queues together, held as many waiters as allowed: refused at once. */
        QUEUE_FULL,
        /** The process waited in the queue as long as allowed. */
        WAIT_TIMEOUT
    }

    private final String family;
    private final Reason reason;
    private final long retryAfterSeconds;

    public ProcessBusyException(String family, Reason reason, long retryAfterSeconds) {
        // Expected under load, often many at once: no stack trace.
        super("Too many runs of " + family + " are queued just now (" + reason + "); try again in "
            + Math.max(1, retryAfterSeconds) + " seconds", null, false, false);
        this.family = Objects.requireNonNull(family, "family");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public String family() {
        return family;
    }

    public Reason reason() {
        return reason;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public List<Violation> violations() {
        return List.of(new Violation(null, PlatformErrorCodes.PROCESS_BUSY, getMessage(),
            Map.of("reason", reason.name(), "retryAfter", retryAfterSeconds)));
    }
}

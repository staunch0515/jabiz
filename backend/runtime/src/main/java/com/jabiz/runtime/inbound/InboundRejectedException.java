package com.jabiz.runtime.inbound;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;

import java.util.List;
import java.util.Objects;

/**
 * An inbound webhook request that is not accepted (decision D37 item 2): 400 {@code INBOUND_REJECTED}, the same answer
 * whatever the reason (a failed signature 401 where the provider is so configured, decision D57), so that a sender probing the endpoint learns nothing. The reason ({@code no_match},
 * {@code too_old}, {@code too_large}, ...) is only logged and tagged on the observation, never stored or answered.
 */
public class InboundRejectedException extends RuntimeException {

    /** The message of every rejection; the reason is not part of it. */
    public static final String MESSAGE = "The webhook request was not accepted";

    private final String reason;
    private final int status;

    public InboundRejectedException(String reason) {
        this(reason, 400);
    }

    /**
     * @param status 400, or 401 for a signature that fails at a provider configured so (decision D57): some senders'
     *               reviews require it
     */
    public InboundRejectedException(String reason, int status) {
        super(MESSAGE, null, false, false);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        if (status != 400 && status != 401) {
            throw new IllegalArgumentException("A rejection is answered 400 or 401, not " + status);
        }
        this.status = status;
    }

    /** The status of the answer: 400, or 401 (decision D57). */
    public int status() {
        return status;
    }

    /** Why, in lower case, for logs and metrics only. */
    public String reason() {
        return reason;
    }

    public List<Violation> violations() {
        return List.of(new Violation(null, PlatformErrorCodes.INBOUND_REJECTED, MESSAGE));
    }
}

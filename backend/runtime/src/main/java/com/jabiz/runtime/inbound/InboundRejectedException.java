package com.jabiz.runtime.inbound;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;

import java.util.List;
import java.util.Objects;

/**
 * An inbound webhook request that is not accepted (decision D37 item 2): 400 {@code INBOUND_REJECTED}, the same answer
 * whatever the reason, so that a sender probing the endpoint learns nothing. The reason ({@code no_match},
 * {@code too_old}, {@code too_large}, ...) is only logged and tagged on the observation, never stored or answered.
 */
public class InboundRejectedException extends RuntimeException {

    /** The message of every rejection; the reason is not part of it. */
    public static final String MESSAGE = "The webhook request was not accepted";

    private final String reason;

    public InboundRejectedException(String reason) {
        super(MESSAGE, null, false, false);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    /** Why, in lower case, for logs and metrics only. */
    public String reason() {
        return reason;
    }

    public List<Violation> violations() {
        return List.of(new Violation(null, PlatformErrorCodes.INBOUND_REJECTED, MESSAGE));
    }
}

package com.jabiz.inbound;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * How one kind of sender signs its webhook requests (decision D37 item 1): a pure function of the request's headers,
 * its body exactly as received, the configured secrets, the current time and the tolerance. Signatures are compared
 * in constant time; the signature is checked before the time, so the time of a forged request tells nothing.
 */
public interface InboundSignatureScheme {

    /** The scheme's name in configuration ({@code jabiz.inbound-webhooks.providers.<name>.scheme}). */
    String name();

    /**
     * Whether the request is signed by one of {@code secrets} and was signed within {@code tolerance} of {@code now},
     * in either direction.
     */
    InboundVerification verify(InboundHeaders headers, byte[] body, List<String> secrets, Instant now,
        Duration tolerance);

    /**
     * The headers a sender holding {@code secret} sends with {@code body} signed at {@code timestamp} (seconds since
     * the epoch): for tests, scenario replay and demonstrations.
     */
    Map<String, String> sign(String secret, long timestamp, byte[] body);

    /** Whether {@code signedAt} (seconds since the epoch) is within {@code tolerance} of {@code now}. */
    static InboundVerification inTime(long signedAt, Instant now, Duration tolerance) {
        long nowSeconds = now.getEpochSecond();
        long allowed = tolerance.getSeconds();
        if (nowSeconds - signedAt > allowed) {
            return InboundVerification.rejected(InboundVerification.Reason.TOO_OLD);
        }
        if (signedAt - nowSeconds > allowed) {
            return InboundVerification.rejected(InboundVerification.Reason.TOO_NEW);
        }
        return InboundVerification.accepted(Instant.ofEpochSecond(signedAt));
    }

    /**
     * A timestamp in seconds: 1 to 12 ASCII digits (until the year 33658), or -1 for anything else. Bounded so that
     * no value overflows the arithmetic of {@link #inTime}.
     */
    static long seconds(String text) {
        if (text == null || text.isEmpty() || text.length() > 12) {
            return -1;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        return Long.parseLong(text);
    }
}

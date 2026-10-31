package com.jabiz.security;

import java.util.regex.Pattern;

/**
 * A {@link SignUpGuard}'s answer.
 *
 * @param allowed whether the account may get the entry's registration roles
 * @param reason  why not: a code ({@link #REASON}) that the caller is shown as the text of the message
 *                {@code SIGN_UP_REFUSED.reason.<reason>} in the request's language (the application's message
 *                resources, in every language it has; the text of {@value #REFUSED} where one is missing); null when
 *                allowed
 */
public record SignUpDecision(boolean allowed, String reason) {

    /** The reason of a refusal that names none, and of a guard that failed. */
    public static final String REFUSED = "refused";

    /** What a reason code looks like. */
    public static final Pattern REASON = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");

    public static final SignUpDecision ALLOW = new SignUpDecision(true, null);

    public SignUpDecision {
        if (allowed) {
            reason = null;
        } else if (reason == null || reason.isBlank()) {
            reason = REFUSED;
        } else if (!REASON.matcher(reason).matches()) {
            throw new IllegalArgumentException("Invalid sign-up refusal reason: " + reason);
        }
    }

    /** Refused for {@code reason}, a code ({@link #REASON}) whose text the caller sees. */
    public static SignUpDecision refuse(String reason) {
        return new SignUpDecision(false, reason);
    }
}

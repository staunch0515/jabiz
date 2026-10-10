package com.jabiz.security;

/**
 * A {@link SignInGuard}'s answer.
 *
 * @param allowed whether the attempt may go on
 * @param reason  why it may not, for the log (never shown to the caller: the answer is the same for every refusal);
 *                null when allowed
 */
public record SignInDecision(boolean allowed, String reason) {

    public static final SignInDecision ALLOW = new SignInDecision(true, null);

    public static SignInDecision refuse(String reason) {
        return new SignInDecision(false, reason == null || reason.isBlank() ? "refused" : reason);
    }
}

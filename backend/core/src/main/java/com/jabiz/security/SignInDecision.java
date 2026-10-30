package com.jabiz.security;

import com.jabiz.context.EntryScope;

/**
 * A {@link SignInGuard}'s answer.
 *
 * @param allowed whether the attempt may go on
 * @param reason  why it may not, for the log (never shown to the caller: the answer is the same for every refusal);
 *                null when allowed
 * @param scope   the session's entry scope (decision D64), set by the platform after the entry's resolver; null for
 *                entries without one and for refusals
 */
public record SignInDecision(boolean allowed, String reason, EntryScope scope) {

    public static final SignInDecision ALLOW = new SignInDecision(true, null);

    public SignInDecision(boolean allowed, String reason) {
        this(allowed, reason, null);
    }

    /** Allowed, in the entry scope {@code scope}. */
    public static SignInDecision allow(EntryScope scope) {
        return new SignInDecision(true, null, scope);
    }

    public static SignInDecision refuse(String reason) {
        return new SignInDecision(false, reason == null || reason.isBlank() ? "refused" : reason);
    }
}

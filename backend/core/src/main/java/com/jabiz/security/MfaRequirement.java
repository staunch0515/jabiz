package com.jabiz.security;

/**
 * Whether an operation needs a recent second factor (docs/design/10-security.md section 10; decision D28 item 4).
 * The entry points check it next to the permissions; nested processes, scenario replays and the system actor are not
 * checked, just as with permissions.
 */
public enum MfaRequirement {
    /** No second factor beyond what the session already has. */
    NONE,
    /** Always: the caller must have passed a second factor recently. */
    ALWAYS,
    /**
     * Platform administration (security entities, user processes, publishing controlled changes, legal holds):
     * required unless the deployment turned {@code jabiz.security.mfa.administration} off.
     */
    ADMINISTRATION;

    /** Whether the requirement applies, given whether administration requires a second factor. */
    public boolean applies(boolean administration) {
        return this == ALWAYS || (this == ADMINISTRATION && administration);
    }
}

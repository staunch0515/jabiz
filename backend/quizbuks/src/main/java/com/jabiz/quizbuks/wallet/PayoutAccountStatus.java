package com.jabiz.quizbuks.wallet;

import java.util.List;

/**
 * The states of a payout account (docs/quizbuks/plans/Q6-payments.md, D-Q6-10), as Stripe's account says:
 * {@code ENABLED} when it takes transfers and pays out, {@code RESTRICTED} when its details were given but Stripe
 * holds it back, {@code PENDING} until then, {@code DEAUTHORIZED} once the user disconnected it (final).
 */
public final class PayoutAccountStatus {

    public static final String PENDING = "PENDING";
    public static final String ENABLED = "ENABLED";
    public static final String RESTRICTED = "RESTRICTED";
    public static final String DEAUTHORIZED = "DEAUTHORIZED";

    public static final List<String> ALL = List.of(PENDING, ENABLED, RESTRICTED, DEAUTHORIZED);

    /** The status an account's report means (a disconnected account stays so). */
    public static String of(PayoutObjects.Account account) {
        if (Boolean.TRUE.equals(account.payoutsEnabled()) && "active".equals(account.transfersCapability())) {
            return ENABLED;
        }
        return Boolean.TRUE.equals(account.detailsSubmitted()) ? RESTRICTED : PENDING;
    }

    /**
     * Whether a report made at {@code reported} (Stripe's {@code created}, in whole seconds) replaces the one recorded
     * at {@code recorded}: a newer one does; an older one does not; of two in the same second, which came first is
     * not known, so the more restrictive state wins (an account is never enabled by a tie).
     */
    public static boolean replaces(String current, java.time.Instant recorded, String reported,
        java.time.Instant reportedAt) {
        if (recorded == null || reportedAt.isAfter(recorded)) {
            return true;
        }
        if (reportedAt.isBefore(recorded)) {
            return false;
        }
        return restriction(reported) >= restriction(current);
    }

    /** How much a state holds the account back: enabled least, disconnected most. */
    static int restriction(String status) {
        return switch (status == null ? "" : status) {
            case ENABLED -> 0;
            case PENDING -> 1;
            case RESTRICTED -> 2;
            case DEAUTHORIZED -> 3;
            default -> 1;
        };
    }

    private PayoutAccountStatus() {}
}

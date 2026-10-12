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

    private PayoutAccountStatus() {}
}

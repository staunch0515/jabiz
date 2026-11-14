package com.jabiz.quizbuks.fee;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * F-M15 b (docs/quizbuks/plans/impl/F-M15.md §4.1, §5.2, §5.6): the wind-up's decisions, in pure Java.
 */
public final class ErasureCloseRules {

    /** The most days {@code qb.erasure.close.alert-days} may say. */
    public static final int MAX_ALERT_DAYS = 60;

    private ErasureCloseRules() {}

    /**
     * How a run ends once nothing is held and the invoices are paid as far as the run went: fees owed while the account
     * still has something to pay them with wait for the next payment ({@link MoneyClose#TO_PAY}: the granted credit is
     * voided only after the last payment, plan H2); otherwise the wind-up is done ({@link MoneyClose#CLOSED}), with fees
     * owed or not.
     */
    public static String end(BigDecimal owed, BigDecimal spendable) {
        return owed.signum() > 0 && spendable.signum() > 0 ? MoneyClose.TO_PAY : MoneyClose.CLOSED;
    }

    /**
     * The closure's reason code after a run that did not close it: why it stopped ({@code SWITCH_OFF},
     * {@code DEPOSIT_OPEN}); a run that only waits for a payment keeps {@code FEE_OWED} (a closure done with fees owed
     * that pays again) and clears a stale one; a run that found nothing to do keeps what was there.
     */
    public static String reasonOf(String outcome, String previous) {
        if (MoneyClose.SWITCHED_OFF.equals(outcome)) {
            return QbSponsorClosures.SWITCH_OFF;
        }
        if (MoneyClose.WAITING_DEPOSITS.equals(outcome)) {
            return QbSponsorClosures.DEPOSIT_OPEN;
        }
        if (MoneyClose.TO_PAY.equals(outcome)) {
            return QbSponsorClosures.FEE_OWED.equals(previous) ? previous : null;
        }
        return previous;
    }

    /**
     * Whether a wind-up not done is taking long: {@code alertDays} (a whole number from 1 to {@value #MAX_ALERT_DAYS};
     * anything else tells nobody) days or more after the erasure.
     */
    public static boolean slow(Instant erasedAt, BigDecimal alertDays, Instant now) {
        if (erasedAt == null || alertDays == null || alertDays.signum() <= 0 || alertDays.stripTrailingZeros().scale() > 0
            || alertDays.compareTo(BigDecimal.valueOf(MAX_ALERT_DAYS)) > 0) {
            return false;
        }
        return !erasedAt.plus(Duration.ofDays(alertDays.longValueExact())).isAfter(now);
    }
}

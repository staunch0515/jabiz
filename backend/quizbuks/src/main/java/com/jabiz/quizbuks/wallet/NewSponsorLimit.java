package com.jabiz.quizbuks.wallet;

import com.jabiz.entity.Violation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The new sponsors' top-up limit (Q10b, review M3; S-3), without I/O: from a sponsor's first paid top-up for
 * {@code qb.topup.new-sponsor.days} days, and before it, what they topped up in all and what is still open, with the
 * new top-up, stays within {@code qb.topup.new-sponsor.max-total}. A stolen card paying large top-ups whose rewards are
 * transferred before the dispute comes is the risk it bounds (with the transfers' hold period). Both values are
 * controlled parameters.
 */
public final class NewSponsorLimit {

    /**
     * Where a sponsor stands.
     *
     * @param limited   whether the limit applies now
     * @param maxTotal  the limit
     * @param used      what is paid and open in all
     * @param remaining what may still be topped up (0 at least); null when not limited
     * @param until     when the limit ends; null before the first paid top-up (it starts then) or when not limited
     */
    public record Standing(boolean limited, BigDecimal maxTotal, BigDecimal used, BigDecimal remaining,
        Instant until) {}

    /**
     * @param firstPaidAt the sponsor's first paid top-up, or null
     * @param used        the amounts of their paid top-ups (refunded or not) and of those still open
     * @return null when the parameters cannot be used (top-ups are then refused)
     */
    public static Standing standing(BigDecimal days, BigDecimal maxTotal, Instant firstPaidAt, BigDecimal used,
        Instant now) {
        if (!RefundMath.wholeAndNotNegative(days) || !RefundMath.wholeAndNotNegative(maxTotal)) {
            return null;
        }
        Instant until = firstPaidAt == null ? null : firstPaidAt.plus(Duration.ofDays(days.longValueExact()));
        if (until != null && !now.isBefore(until)) {
            return new Standing(false, maxTotal, used, null, null);
        }
        BigDecimal spent = used == null ? BigDecimal.ZERO : used;
        return new Standing(true, maxTotal, spent, maxTotal.subtract(spent).max(BigDecimal.ZERO), until);
    }

    /** The refusal of {@code amount}, or null when it fits (422 {@link WalletCodes#TOPUP_NEW_SPONSOR_LIMIT}). */
    public static Violation refusal(Standing standing, BigDecimal amount) {
        if (!standing.limited() || amount.compareTo(standing.remaining()) <= 0) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("remaining", standing.remaining().toPlainString());
        params.put("max", standing.maxTotal().toPlainString());
        params.put("until", standing.until() == null ? "-" : standing.until().toString());
        return new Violation("amount", WalletCodes.TOPUP_NEW_SPONSOR_LIMIT, "A new sponsor may top up "
            + standing.remaining().toPlainString() + " Kudos more", params);
    }

    private NewSponsorLimit() {}
}

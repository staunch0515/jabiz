package com.jabiz.quizbuks.fee;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The rules of the platform fee Kudos granted to sponsors (F-M2), apart from the processes: how much the onboarding
 * gives, until when a lot is valid, whether a grant by hand is within the cap, what an expired lot voids, and whether a
 * sponsor is due a reminder. Pure Java.
 */
public final class FeeCreditRules {

    /** The longest validity a lot may have, in days (as the parameter's bound). */
    public static final int MAX_VALID_DAYS = 999;
    /** The longest a sponsor is reminded ahead of a lot's expiry, in days. */
    public static final int MAX_NOTICE_DAYS = 365;
    /** At most one reminder per sponsor in this time (the spec: once in 24 hours). */
    public static final Duration REMINDER_INTERVAL = Duration.ofHours(24);

    /** The source key of a sponsor's onboarding grant: once per sponsor. */
    public static final String ONBOARDING_KEY = "onboarding";

    private FeeCreditRules() {}

    /**
     * The onboarding amount: the certified one for a certified sponsor, otherwise the basic one. Empty when the amount
     * that applies is not a whole positive number of yen (the grant is refused rather than made of nothing).
     */
    public static Optional<BigDecimal> onboardingAmount(boolean certified, BigDecimal basic, BigDecimal certifiedAmount) {
        BigDecimal amount = certified ? certifiedAmount : basic;
        return whole(amount) ? Optional.of(amount.setScale(0)) : Optional.empty();
    }

    /**
     * F-M3: the top-up due to a Sponsor whose company is verified (D-Q105-6): the certified amount less the onboarding
     * lot's, once. The certified amount is the one recorded on the onboarding lot when it was granted
     * ({@code onboardingCertified}; the amount itself when granted certified, so never topped up), and no more than the
     * parameter now ({@code certifiedAmount}): raising the parameter tops up nothing granted before it (the review of
     * F-M3, M1). A lot granted before the amount was recorded (V111) has none and takes the parameter now. Nothing while
     * the grants' switch is off (the controller, 2026-11-10), to a Sponsor not approved, banned or not verified, without
     * an onboarding lot (confirming them later gives the certified amount), with a top-up already, or when the
     * onboarding was the certified amount or more (or the amounts are not whole yen).
     */
    public static Optional<BigDecimal> certifiedTopUp(boolean enabled, boolean approved, boolean banned,
        boolean verified, BigDecimal onboardingAmount, BigDecimal onboardingCertified, boolean toppedUp,
        BigDecimal certifiedAmount) {
        if (!enabled || !approved || banned || !verified || onboardingAmount == null || toppedUp
            || !whole(certifiedAmount) || onboardingCertified != null && !whole(onboardingCertified)) {
            return Optional.empty();
        }
        BigDecimal target = onboardingCertified == null ? certifiedAmount : onboardingCertified.min(certifiedAmount);
        BigDecimal difference = target.subtract(onboardingAmount).setScale(0, java.math.RoundingMode.DOWN);
        return difference.signum() > 0 ? Optional.of(difference) : Optional.empty();
    }

    /** Until when a lot granted at {@code grantedAt} is valid; empty for a number of days out of 1 to 999. */
    public static Optional<Instant> expiresAt(Instant grantedAt, BigDecimal validDays) {
        if (!whole(validDays) || validDays.compareTo(BigDecimal.valueOf(MAX_VALID_DAYS)) > 0) {
            return Optional.empty();
        }
        return Optional.of(grantedAt.plus(Duration.ofDays(validDays.longValueExact())));
    }

    /** Whether a grant by hand of {@code amount} is within the cap; no cap (null) is no limit. */
    public static boolean withinCap(BigDecimal amount, BigDecimal cap) {
        return cap == null || amount.compareTo(cap) <= 0;
    }

    /** What a lot voids when it expires: what is left of it, once it expired (nothing before, or when spent). */
    public static BigDecimal voidable(BigDecimal remaining, Instant expiresAt, Instant now) {
        return remaining == null || remaining.signum() <= 0 || expiresAt.isAfter(now) ? BigDecimal.ZERO : remaining;
    }

    /**
     * Whether a lot is due its reminder: something left, not reminded yet, not expired, and expiring within the notice.
     * Empty notice days (out of 1 to 365) remind of nothing.
     */
    public static boolean remindable(BigDecimal remaining, Instant expiresAt, Instant remindedAt, Instant now,
        BigDecimal noticeDays) {
        if (!whole(noticeDays) || noticeDays.compareTo(BigDecimal.valueOf(MAX_NOTICE_DAYS)) > 0) {
            return false;
        }
        return remaining != null && remaining.signum() > 0 && remindedAt == null && expiresAt.isAfter(now)
            && !expiresAt.isAfter(now.plus(Duration.ofDays(noticeDays.longValueExact())));
    }

    /** Whether a sponsor last reminded at {@code last} may be reminded again now (at most once in 24 hours). */
    public static boolean reminderAllowed(Instant last, Instant now) {
        return last == null || !last.plus(REMINDER_INTERVAL).isAfter(now);
    }

    /** The source key of a grant by hand: the request it was approved on. */
    public static String manualKey(Object requestId) {
        return "manual:" + requestId;
    }

    private static boolean whole(BigDecimal value) {
        return value != null && value.signum() > 0 && value.stripTrailingZeros().scale() <= 0;
    }
}

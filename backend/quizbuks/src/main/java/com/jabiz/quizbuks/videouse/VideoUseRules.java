package com.jabiz.quizbuks.videouse;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.stats.MinGroup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The rules of the ledger of shared videos' uses (F-S14, docs/quizbuks/plans/impl/F-S14.md), in one place: who writes a
 * use, its states and what each act starts from, the period, the price, the reference amount by the figures, the
 * minimum group of the figures shown (Q9e) and the reminder's days. Plain Java; the templates write the same amount.
 */
public final class VideoUseRules {

    /** Written by the user; sent to the provider; confirmed or sent back (with a reason) by the provider. */
    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String REJECTED = "REJECTED";
    public static final List<String> STATUSES = List.of(DRAFT, SUBMITTED, CONFIRMED, REJECTED);

    /** A fixed price; a price per thousand exposure successes; a price per taker who went on to answer. */
    public static final String FIXED = "FIXED";
    public static final String PER_MILLE = "PER_MILLE";
    public static final String PER_TAKER = "PER_TAKER";
    public static final List<String> PRICINGS = List.of(FIXED, PER_MILLE, PER_TAKER);

    /** The longest period one use covers, in days (ten years: a bound, not a business rule). */
    public static final long MAX_PERIOD_DAYS = 3_660;
    /** The highest price (a bound of the column, numeric(19, 0), with room). */
    public static final BigDecimal MAX_PRICE = new BigDecimal("1000000000000");

    /** {@code qb.video-use.remind-days}: its default and bounds. */
    public static final int DEFAULT_REMIND_DAYS = 7;
    public static final int MIN_REMIND_DAYS = 1;
    public static final int MAX_REMIND_DAYS = 90;

    private VideoUseRules() {}

    // ---- who and when ----------------------------------------------------------------------------------------------

    /**
     * Whether a publication's card is one a use is written for: its video is another sponsor's shared video (F-S13's
     * "another's video": a shared video whose provider is not the publisher).
     */
    public static boolean othersVideo(Object sharedVideoId, Object providerId, Object publisherId) {
        return sharedVideoId != null && providerId != null && !Objects.equals(String.valueOf(providerId),
            String.valueOf(publisherId));
    }

    /** The side the caller is of a use: {@code SPONSOR}, {@code PROVIDER} or none. */
    public static String sideOf(String caller, Object sponsorId, Object providerId) {
        if (caller == null) {
            return null;
        }
        if (caller.equals(String.valueOf(sponsorId))) {
            return QbVideoUses.SPONSOR;
        }
        return caller.equals(String.valueOf(providerId)) ? QbVideoUses.PROVIDER : null;
    }

    /** The user saves (writes or corrects) a use in any state but one waiting for the provider. */
    public static boolean savable(String status) {
        return status == null || !SUBMITTED.equals(status);
    }

    /** The status after the user saved: a draft again, whatever it was (a corrected confirmation asks again). */
    public static String afterSave() {
        return DRAFT;
    }

    public static boolean submittable(String status) {
        return DRAFT.equals(status);
    }

    /** The provider answers a use waiting for it. */
    public static boolean answerable(String status) {
        return SUBMITTED.equals(status);
    }

    // ---- what the user writes --------------------------------------------------------------------------------------

    /** What a save says, before it is written. */
    public record Entry(LocalDate periodFrom, LocalDate periodTo, String pricing, BigDecimal price) {}

    /** The problems of an entry (none: it may be written). */
    public static List<Violation> problems(Entry entry) {
        List<Violation> problems = new ArrayList<>();
        if (entry.periodFrom() == null || entry.periodTo() == null) {
            problems.add(new Violation("periodTo", VideoUseCodes.PERIOD, "The period needs its first and last day",
                Map.of("maxDays", MAX_PERIOD_DAYS)));
        } else if (entry.periodTo().isBefore(entry.periodFrom())
            || ChronoUnit.DAYS.between(entry.periodFrom(), entry.periodTo()) + 1 > MAX_PERIOD_DAYS) {
            problems.add(new Violation("periodTo", VideoUseCodes.PERIOD, "The last day is not before the first, and "
                + "the period is at most " + MAX_PERIOD_DAYS + " days", Map.of("maxDays", MAX_PERIOD_DAYS)));
        }
        if (!PRICINGS.contains(entry.pricing())) {
            problems.add(new Violation("pricing", VideoUseCodes.PRICING, "Unknown pricing " + entry.pricing(),
                Map.of()));
        }
        if (entry.price() == null || entry.price().signum() < 0 || entry.price().compareTo(MAX_PRICE) > 0
            || entry.price().stripTrailingZeros().scale() > 0) {
            problems.add(new Violation("price", VideoUseCodes.PRICE, "The price is a whole number from 0 to "
                + MAX_PRICE.toPlainString(), Map.of("max", MAX_PRICE.toPlainString())));
        }
        return List.copyOf(problems);
    }

    // ---- the figures -----------------------------------------------------------------------------------------------

    /** The four figures of a publication, as counted (never held back here; {@link #shown} holds them back). */
    public record Figures(long opens, long successes, long goes, long qualified) {}

    /** A figure as shown: none below the minimum group {@code k} (Q9e, {@link MinGroup}). */
    public static Long shown(long figure, int k) {
        return MinGroup.show(figure, k) ? figure : null;
    }

    /**
     * The reference amount of a use by the figures: the fixed price; the price per thousand successes, rounded down;
     * the price per taker who went on to answer. None when the figure it is by is held back (below {@code k}), or the
     * pricing unknown. The templates write the same.
     */
    public static BigDecimal amount(String pricing, BigDecimal price, Figures figures, int k) {
        if (price == null || pricing == null) {
            return null;
        }
        return switch (pricing) {
            case FIXED -> price;
            case PER_MILLE -> figures == null || shown(figures.successes(), k) == null ? null
                : price.multiply(BigDecimal.valueOf(figures.successes())).divide(BigDecimal.valueOf(1000), 0,
                    RoundingMode.FLOOR);
            case PER_TAKER -> figures == null || shown(figures.goes(), k) == null ? null
                : price.multiply(BigDecimal.valueOf(figures.goes()));
            default -> null;
        };
    }

    /** Whether the figures stored for a finalised publication are those counted again up to the same cut-off. */
    public static boolean reconciles(Figures stored, Figures counted) {
        return Objects.equals(stored, counted);
    }

    // ---- the reminder ----------------------------------------------------------------------------------------------

    /** {@code qb.video-use.remind-days} as read (none or out of bounds: the default, or the nearest bound). */
    public static int remindDays(Integer configured) {
        if (configured == null) {
            return DEFAULT_REMIND_DAYS;
        }
        return Math.max(MIN_REMIND_DAYS, Math.min(MAX_REMIND_DAYS, configured));
    }

    /** The reminders' tasks of one submitted revision of a use: once each. */
    public static String reminderSource(Object videoUseId, Object revision) {
        return "qb.video-use-remind:" + videoUseId + ":" + revision;
    }
}

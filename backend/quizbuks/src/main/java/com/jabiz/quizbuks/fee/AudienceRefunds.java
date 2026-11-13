package com.jabiz.quizbuks.fee;

import com.jabiz.quizbuks.audience.AudienceBilling;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F-M7 (docs/quizbuks/plans/impl/F-M.md §10.3-1): what a publication that ended early gives back of its audience fee,
 * pure Java. The fee was the quote frozen at the submission (the daily prices of the charged rules and the least fee
 * of the operator's list, its start and end); the days it was on are counted as the fee counted them
 * ({@link AudienceBilling#days}: whole days rounded up, at least 1, from the start it was billed from, paused days
 * included); what it owes for them is the quote for those days, rounded up as the fee was; what it gives back is the
 * fee's amount less that, never below 0. The tax of the credit note is {@link CreditNoteRules#tax} (one formula, C4).
 */
public final class AudienceRefunds {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private AudienceRefunds() {}

    /**
     * The quote frozen with an audience fee's prepayment ({@code detail}): the daily prices of its rules and the least
     * fee, and the start and end it was billed for. A prepayment frozen before F-M7 has no start and end: {@code
     * startAt} / {@code endAt} are the publication's at the time it was frozen (N5), read by the caller; none when
     * that read found nothing. Null when the detail cannot be read.
     */
    public static Quote quote(String detail, Instant startAt, Instant endAt) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(detail, OBJECT);
        } catch (RuntimeException e) {
            return null;
        }
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        if (parsed.get("items") instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> map && map.get("ruleKey") != null
                    && map.get("dailyPrice") instanceof Number price) {
                    prices.put(String.valueOf(map.get("ruleKey")), new BigDecimal(price.toString()));
                }
            }
        }
        BigDecimal minimum = parsed.get("minimumFee") instanceof Number number ? new BigDecimal(number.toString())
            : BigDecimal.ZERO;
        Instant start = instant(parsed.get("startAt"));
        Instant end = instant(parsed.get("endAt"));
        return new Quote(prices, minimum, start != null ? start : startAt, end != null ? end : endAt);
    }

    /** Whether the stored quote has its own period (frozen after F-M7). */
    public static boolean hasPeriod(String detail) {
        Quote own = quote(detail, null, null);
        return own != null && own.startAt() != null && own.endAt() != null;
    }

    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The end reasons that give back the unused days (the requester, 2026-11-12, Q3): a takedown for a violation
     * ({@code ADMIN}) does not; the rest are no fault of the sponsor's or no use of the rules any more. A run that
     * ran out ({@code EXPIRED}) is not early.
     */
    public static final Set<String> REFUNDABLE_REASONS = Set.of(PublicationStatus.PUBLISHER,
        PublicationStatus.QUOTA, PublicationStatus.BUDGET, PublicationStatus.TREE, PublicationStatus.SHARED_VIDEO,
        PublicationStatus.EXPOSURE);

    /** What was decided for a fee (the prepayment's {@code refundOutcome}). */
    public static final String REFUNDED = "REFUNDED";
    public static final String CREDITED = "CREDITED";
    public static final String NONE_SWITCH_OFF = "NONE_SWITCH_OFF";
    public static final String NONE_REASON = "NONE_REASON";
    public static final String NONE_ZERO = "NONE_ZERO";
    public static final String NONE_NO_PERIOD = "NONE_NO_PERIOD";
    /** NONE_NO_PERIOD closed by finance: no note to be issued (QB_AUDIENCE_REFUND_CLOSE, with its reason). */
    public static final String NONE_FINANCE = "NONE_FINANCE";
    public static final List<String> OUTCOMES = List.of(REFUNDED, CREDITED, NONE_SWITCH_OFF, NONE_REASON, NONE_ZERO,
        NONE_NO_PERIOD, NONE_FINANCE);

    /**
     * The quote frozen at the submission, as the prepayment's detail holds it.
     *
     * @param dailyPrices the daily price of each charged rule used
     * @param minimumFee  the list's least fee
     * @param startAt     the start it was billed from; none for an old prepayment without it (read back, else none)
     * @param endAt       the end it was billed to
     */
    public record Quote(Map<String, BigDecimal> dailyPrices, BigDecimal minimumFee, Instant startAt, Instant endAt) {

        public Quote {
            dailyPrices = Map.copyOf(dailyPrices);
        }

        BigDecimal perDay() {
            return dailyPrices.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /**
     * @param outcome   one of {@link #OUTCOMES} ({@link #REFUNDED} also for a postpaid fee: the caller tells them apart
     *                  by where the credit goes)
     * @param usedDays  the days counted as used (0 when not decided on days)
     * @param owed      what the used days come to (the fee's amount when nothing is given back)
     * @param amount    what the credit note is for, before tax (0: none)
     */
    public record Decision(String outcome, int usedDays, BigDecimal owed, BigDecimal amount) {

        public boolean refunds() {
            return amount.signum() > 0;
        }
    }

    /**
     * Decides a fee of {@code feeAmount} (before tax) for a publication that ended at {@code endedAt} for
     * {@code endReason}, with the switch {@code refundOn} as it was at that time.
     */
    public static Decision decide(Quote quote, BigDecimal feeAmount, Instant endedAt, String endReason,
        boolean refundOn) {
        if (!refundOn) {
            return new Decision(NONE_SWITCH_OFF, 0, feeAmount, BigDecimal.ZERO);
        }
        if (endReason == null || !REFUNDABLE_REASONS.contains(endReason)) {
            return new Decision(NONE_REASON, 0, feeAmount, BigDecimal.ZERO);
        }
        if (quote == null || quote.startAt() == null || quote.endAt() == null || endedAt == null) {
            return new Decision(NONE_NO_PERIOD, 0, feeAmount, BigDecimal.ZERO);
        }
        if (!endedAt.isBefore(quote.endAt())) {
            // Not early: it ran to its end.
            return new Decision(NONE_REASON, 0, feeAmount, BigDecimal.ZERO);
        }
        int used = AudienceBilling.days(quote.startAt(), endedAt);
        BigDecimal owed = quote.perDay().multiply(BigDecimal.valueOf(used)).max(quote.minimumFee())
            .setScale(0, RoundingMode.CEILING);
        BigDecimal back = feeAmount.subtract(owed).max(BigDecimal.ZERO);
        return new Decision(back.signum() > 0 ? REFUNDED : NONE_ZERO, used, owed.min(feeAmount), back);
    }
}

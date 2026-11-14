package com.jabiz.quizbuks.fee;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The platform's fees in pure Java (docs/quizbuks/plans/Q87-fee-account.md, D-Q87-4 – D-Q87-7): the kinds of fee,
 * the tax on one, when an invoice is due, the month a charge belongs to, and what unpaid fees stop a sponsor from
 * doing under each billing mode. The processes decide with these; the pages read the same figures.
 */
public final class FeeRules {

    /** The sponsor pays as fees arise: each charge is invoiced at once and paid from the cash account when it covers it. */
    public static final String PREPAID = "PREPAID";
    /** The sponsor is invoiced for a month's fees after it, within a credit limit. */
    public static final String POSTPAID = "POSTPAID";
    public static final List<String> BILLING_MODES = List.of(PREPAID, POSTPAID);

    /** The platform's monthly fee ({@code qb.fee.monthly}), one a month per approved sponsor. */
    public static final String MONTHLY = "MONTHLY";
    /** A fee headquarters records by hand, another administrator approving. */
    public static final String MANUAL = "MANUAL";
    /** F-M7 (with F-M5): a publication's audience rules, charged from what its submission set aside when it goes on. */
    public static final String AUDIENCE = "AUDIENCE";
    /** F-M6: a publication's distribution fee, charged a month at a time for the people who started its quiz. */
    public static final String DISTRIBUTION = "DISTRIBUTION";
    /** F-M8: a publication's exposure fee, charged a month at a time for the exposures of a shared video not its own. */
    public static final String EXPOSURE = "EXPOSURE";
    /** F-M9: the AI fee, charged from the sponsor's AI credit the month after its jobs ended. */
    public static final String AI = "AI";
    public static final List<String> KINDS = List.of(MONTHLY, MANUAL, AUDIENCE, DISTRIBUTION, EXPOSURE, AI);

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);

    private FeeRules() {}

    /** Whether a billing mode is one of the two. */
    public static boolean billingMode(String value) {
        return value != null && BILLING_MODES.contains(value);
    }

    /** The sponsor's billing mode: their own, else the platform's default ({@code qb.fee.default-billing-mode}). */
    public static String billingModeOf(Object own, String defaultMode) {
        if (own != null && billingMode(String.valueOf(own))) {
            return String.valueOf(own);
        }
        return billingMode(defaultMode) ? defaultMode : PREPAID;
    }

    /** The consumption tax on a fee: the amount times the rate, rounded down to the yen; null for a rate not usable. */
    public static BigDecimal tax(BigDecimal amount, BigDecimal rate) {
        if (amount == null || rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            return null;
        }
        return amount.multiply(rate).setScale(0, RoundingMode.FLOOR);
    }

    /** The month ({@code yyyy-MM}) an instant falls in, in the fiscal zone. */
    public static String period(Instant at, ZoneId zone) {
        return MONTH.format(YearMonth.from(at.atZone(zone)));
    }

    /** Whether a text is a month ({@code yyyy-MM}). */
    public static boolean period(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}")) {
            return false;
        }
        try {
            YearMonth.parse(value, MONTH);
            return true;
        } catch (java.time.DateTimeException e) {
            return false;
        }
    }

    public static Instant periodStart(String period, ZoneId zone) {
        return YearMonth.parse(period, MONTH).atDay(1).atStartOfDay(zone).toInstant();
    }

    public static Instant periodEnd(String period, ZoneId zone) {
        return YearMonth.parse(period, MONTH).plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
    }

    /** The day an invoice is issued: that day in the fiscal zone (the instant is kept too). */
    public static LocalDate issuedOn(Instant issuedAt, ZoneId zone) {
        return issuedAt.atZone(zone).toLocalDate();
    }

    /** The day an invoice issued at {@code issuedAt} is due: that day in the fiscal zone plus the due days. */
    public static LocalDate dueOn(Instant issuedAt, BigDecimal dueDays, ZoneId zone) {
        long days = dueDays == null || dueDays.signum() < 0 ? 0 : dueDays.longValue();
        return issuedAt.atZone(zone).toLocalDate().plusDays(days);
    }

    /**
     * Where a sponsor stands with the platform's fees: what is unpaid against what their billing allows.
     *
     * @param unpaid the total of their open and overdue invoices
     */
    public record Standing(String billingMode, BigDecimal creditLimit, BigDecimal unpaid, boolean overdue,
        boolean moreUnpaid) {

        public Standing(String billingMode, BigDecimal creditLimit, BigDecimal unpaid, boolean overdue) {
            this(billingMode, creditLimit, unpaid, overdue, false);
        }

        /**
         * Whether unpaid fees stop the sponsor from converting cash to Kudos and submitting publications (D-Q87-5):
         * prepaid, anything unpaid; postpaid, more unpaid than the credit limit, or anything overdue.
         */
        public boolean blocked() {
            if (moreUnpaid) {
                // More unpaid invoices than were read: whatever their sum, the sponsor is stopped.
                return true;
            }
            BigDecimal owed = unpaid == null ? BigDecimal.ZERO : unpaid;
            if (owed.signum() <= 0) {
                return false;
            }
            if (POSTPAID.equals(billingMode)) {
                BigDecimal limit = creditLimit == null ? BigDecimal.ZERO : creditLimit;
                return overdue || owed.compareTo(limit) > 0;
            }
            return true;
        }
    }
}

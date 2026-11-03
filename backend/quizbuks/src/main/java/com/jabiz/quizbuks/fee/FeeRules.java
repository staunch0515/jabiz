package com.jabiz.quizbuks.fee;

import com.jabiz.entity.Violation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The platform fees' rules (Q87b, D-Q87-4 to D-Q87-7, docs/quizbuks/plans/Q87-fee-account.md), pure Java: the tax of
 * a charge and of an invoice, how a charge is settled, and what a sponsor's unpaid fees stop.
 *
 * <p><b>Tax.</b> A qualified invoice rounds the consumption tax once per rate (ja: 適格請求書の端数処理は税率ごとに一回), so a
 * charge's tax is provisional ({@link #provisionalTax}, rounded toward zero) and is posted with the charge; the invoice
 * computes the tax of each rate's total once and posts the difference ({@link Invoice#adjustment}), a few yen at most,
 * to what is still due, or else to the cash account.
 */
public final class FeeRules {

    public static final String PREPAID = "PREPAID";
    public static final String POSTPAID = "POSTPAID";
    public static final List<String> MODES = List.of(PREPAID, POSTPAID);

    private FeeRules() {}

    /** A charge's provisional tax: its amount times the rate, rounded toward zero (whole yen). */
    public static BigDecimal provisionalTax(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(0, RoundingMode.DOWN);
    }

    /**
     * A charge as an invoice sees it.
     *
     * @param receivable whether it was recorded as owed (1130) rather than paid from the cash account at once (2160)
     */
    public record Line(BigDecimal amount, BigDecimal taxRate, BigDecimal provisionalTax, boolean receivable) {}

    /** One rate of an invoice: the amounts' total and its tax, rounded once. */
    public record RateTotal(BigDecimal rate, BigDecimal amount, BigDecimal tax) {}

    /**
     * An invoice's totals.
     *
     * @param paid       what was paid from the cash account when charged (amounts and provisional taxes)
     * @param due        what is still to pay (the receivable charges, with the adjustment when it goes there)
     * @param adjustment the invoice's tax less the charges' provisional taxes (positive: more tax)
     * @param adjustDue  whether the adjustment goes to what is due (1130), else to the cash account (2160)
     */
    public record Invoice(List<RateTotal> rates, BigDecimal amount, BigDecimal tax, BigDecimal total, BigDecimal paid,
        BigDecimal due, BigDecimal adjustment, boolean adjustDue) {
        public Invoice {
            rates = List.copyOf(rates);
        }
    }

    /**
     * The invoice of {@code lines}. The adjustment goes to what is due when something is (and it stays at least 0),
     * else to the cash account when {@code availableCash} covers a positive one (a negative one is always given back),
     * else it becomes due.
     */
    public static Invoice invoice(List<Line> lines, BigDecimal availableCash) {
        return invoice(lines, BigDecimal.ZERO, availableCash);
    }

    /**
     * As {@link #invoice(List, BigDecimal)}, with {@code paidEarly} of the receivable charges already paid from the cash
     * account before the invoice ({@code QB_FEE_PAY} without an invoice): counted as paid, not as due.
     */
    public static Invoice invoice(List<Line> lines, BigDecimal paidEarly, BigDecimal availableCash) {
        Map<BigDecimal, BigDecimal[]> byRate = new TreeMap<>();
        BigDecimal provisional = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal owed = BigDecimal.ZERO;
        for (Line line : lines) {
            BigDecimal[] sums = byRate.computeIfAbsent(line.taxRate().stripTrailingZeros(),
                rate -> new BigDecimal[] {BigDecimal.ZERO});
            sums[0] = sums[0].add(line.amount());
            provisional = provisional.add(line.provisionalTax());
            BigDecimal gross = line.amount().add(line.provisionalTax());
            if (line.receivable()) {
                owed = owed.add(gross);
            } else {
                paid = paid.add(gross);
            }
        }
        List<RateTotal> rates = new ArrayList<>();
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (Map.Entry<BigDecimal, BigDecimal[]> entry : byRate.entrySet()) {
            BigDecimal rateAmount = entry.getValue()[0];
            BigDecimal rateTax = provisionalTax(rateAmount, entry.getKey());
            rates.add(new RateTotal(entry.getKey(), rateAmount, rateTax));
            amount = amount.add(rateAmount);
            tax = tax.add(rateTax);
        }
        BigDecimal early = paidEarly == null ? BigDecimal.ZERO : paidEarly.min(owed).max(BigDecimal.ZERO);
        owed = owed.subtract(early);
        paid = paid.add(early);
        BigDecimal adjustment = tax.subtract(provisional);
        boolean adjustDue;
        if (adjustment.signum() == 0) {
            adjustDue = owed.signum() > 0;
        } else if (owed.signum() > 0 && owed.add(adjustment).signum() >= 0) {
            adjustDue = true;
        } else if (adjustment.signum() < 0) {
            adjustDue = false;
        } else {
            adjustDue = availableCash == null || availableCash.compareTo(adjustment) < 0;
        }
        BigDecimal due = adjustDue ? owed.add(adjustment) : owed;
        BigDecimal settled = adjustDue ? paid : paid.add(adjustment);
        return new Invoice(rates, amount, tax, amount.add(tax), settled, due, adjustment, adjustDue);
    }

    /**
     * What a sponsor owes now.
     *
     * @param mode        {@link #PREPAID} or {@link #POSTPAID}
     * @param creditLimit how much a postpaid sponsor may owe (0 for none)
     * @param unpaid      what is owed: receivable charges not yet paid, invoiced or not
     * @param overdue     whether an invoice is past its due date
     */
    public record Standing(String mode, BigDecimal creditLimit, BigDecimal unpaid, boolean overdue) {}

    /**
     * Why a sponsor may not buy Kudos or submit a new publication now (D-Q87-5, D-Q87-7); none when they may. An
     * overdue invoice stops them only while {@code qb.fee.overdue.block-submit} is on.
     */
    public static List<Violation> refusals(Standing standing, boolean blockOverdue) {
        List<Violation> problems = new ArrayList<>();
        BigDecimal unpaid = standing.unpaid().max(BigDecimal.ZERO);
        if (standing.overdue() && blockOverdue) {
            problems.add(new Violation(null, FeeCodes.OVERDUE, "An invoice is overdue", Map.of(
                "unpaid", unpaid.toPlainString())));
        } else if (POSTPAID.equals(standing.mode())) {
            if (unpaid.compareTo(limit(standing)) > 0) {
                problems.add(new Violation(null, FeeCodes.CREDIT_EXCEEDED, "The unpaid fees are above the credit",
                    Map.of("unpaid", unpaid.toPlainString(), "credit", limit(standing).toPlainString())));
            }
        } else if (unpaid.signum() > 0) {
            problems.add(new Violation(null, FeeCodes.UNPAID, "Fees are unpaid", Map.of(
                "unpaid", unpaid.toPlainString())));
        }
        return problems;
    }

    /**
     * Whether a charge of {@code total} (amount and provisional tax) is paid from the cash account at once: a prepaid
     * sponsor whose available cash covers it. Otherwise it is owed (1130).
     */
    public static boolean paysFromCash(String mode, BigDecimal availableCash, BigDecimal total) {
        return !POSTPAID.equals(mode) && availableCash.compareTo(total) >= 0;
    }

    /**
     * Why a charge that must be covered (a publication's audience fee, Q47c-2) cannot be: a prepaid sponsor's cash
     * does not cover it, or a postpaid one's credit would be passed. Null when it can be.
     */
    public static Violation uncovered(Standing standing, BigDecimal availableCash, BigDecimal total) {
        if (POSTPAID.equals(standing.mode())) {
            BigDecimal after = standing.unpaid().max(BigDecimal.ZERO).add(total);
            return after.compareTo(limit(standing)) > 0 ? new Violation(null, FeeCodes.CREDIT_EXCEEDED,
                "The fee would pass the credit", Map.of("fee", total.toPlainString(), "credit",
                    limit(standing).toPlainString(), "unpaid", standing.unpaid().max(BigDecimal.ZERO).toPlainString()))
                : null;
        }
        return availableCash.compareTo(total) < 0 ? new Violation(null, FeeCodes.FUNDS, "The cash account does not "
            + "cover the fee", Map.of("fee", total.toPlainString(), "available",
                availableCash.max(BigDecimal.ZERO).toPlainString())) : null;
    }

    private static BigDecimal limit(Standing standing) {
        return standing.creditLimit() == null ? BigDecimal.ZERO : standing.creditLimit().max(BigDecimal.ZERO);
    }
}

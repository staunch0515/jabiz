package com.jabiz.quizbuks.fee;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The AI fee's arithmetic (F-M9, docs/quizbuks/plans/impl/F-M9.md §3), in yen, without the database. A job's fee is the
 * dollars of the calls the provider answered ({@code billableUsd}) times the markup and the exchange rate frozen at its
 * request, rounded up to the yen; its tax is worked out on the month's total of its group (the operator and the month the
 * fee will be charged as), so that the jobs' increments add up to exactly what the one charge comes to (review H2 (a), as
 * the distribution fee's cumulative rounding).
 */
public final class AiFeeRules {

    /**
     * A job's fee as its end settles it.
     *
     * @param fee      the fee before tax charged to the sponsor
     * @param absorbed what of the fee before tax the platform took on, the credit falling short
     * @param withTax  what the fee adds to its group's total with tax: what it takes of the credit
     */
    public record Settled(BigDecimal fee, BigDecimal absorbed, BigDecimal withTax) {}

    /** The fee before tax: the dollars times the markup and the rate, rounded up to the yen (none for no usage). */
    public static BigDecimal fee(BigDecimal billableUsd, BigDecimal markup, BigDecimal usdJpy) {
        if (billableUsd == null || billableUsd.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return billableUsd.multiply(markup).multiply(usdJpy).setScale(0, RoundingMode.UP);
    }

    /**
     * The cost part of a fee (the dollars at the rate, rounded up, without the markup): what of an AI fee is not the
     * operators' to share in (F-M12 §3.2, {@code shareExcluded}).
     */
    public static BigDecimal costPart(BigDecimal billableUsd, BigDecimal usdJpy) {
        if (billableUsd == null || billableUsd.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return billableUsd.multiply(usdJpy).setScale(0, RoundingMode.UP);
    }

    /** An amount before tax with its tax ({@link FeeRules#tax}, rounded down). */
    public static BigDecimal withTax(BigDecimal amount, BigDecimal taxRate) {
        return amount.add(FeeRules.tax(amount, taxRate));
    }

    /**
     * What a request holds of the credit: the most it may cost, as a fee, with its tax rounded up (review M1 of #692: a
     * strict bound). The job's end takes its {@link #increment} within its group, whose tax is the group's rounded down:
     * {@code floor((b + f) t) - floor(b t)} is at most {@code ceil(f t)}, so the increment of any fee up to the most it
     * may cost never exceeds the reservation.
     */
    public static BigDecimal reservation(BigDecimal maxUsd, BigDecimal markup, BigDecimal usdJpy, BigDecimal taxRate) {
        BigDecimal fee = fee(maxUsd, markup, usdJpy);
        return fee.add(fee.multiply(taxRate).setScale(0, RoundingMode.CEILING));
    }

    /**
     * What a fee {@code fee} adds to its group's total with tax when the group's fees before it come to {@code before}.
     */
    public static BigDecimal increment(BigDecimal before, BigDecimal fee, BigDecimal taxRate) {
        return withTax(before.add(fee), taxRate).subtract(withTax(before, taxRate));
    }

    /** What the fees finished and not charged yet will take of the credit: each group's total with its tax. */
    public static BigDecimal unbilledWithTax(Collection<BigDecimal> groupTotals, BigDecimal taxRate) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal group : groupTotals) {
            total = total.add(withTax(group, taxRate));
        }
        return total;
    }

    /** What a sponsor may still use of the credit: its remaining less the unbilled fees and the reservations. */
    public static BigDecimal available(BigDecimal remaining, BigDecimal unbilledWithTax, BigDecimal reserved) {
        return remaining.subtract(unbilledWithTax).subtract(reserved).max(BigDecimal.ZERO);
    }

    /**
     * Settles a job's fee: all of it when its increment fits in {@code room} (its own reservation and what the sponsor
     * may still use besides); otherwise the largest fee before tax whose increment does, the rest taken on by the
     * platform (never charged later, plan §3).
     *
     * @param before the fees of the job's group finished before it and not charged yet
     * @param fee    the job's fee before tax
     * @param room   what the job may take of the credit
     */
    public static Settled settle(BigDecimal before, BigDecimal fee, BigDecimal room, BigDecimal taxRate) {
        BigDecimal full = increment(before, fee, taxRate);
        if (full.compareTo(room) <= 0) {
            return new Settled(fee, BigDecimal.ZERO, full);
        }
        // The increment only grows with the fee: the largest that fits, by halves.
        BigDecimal low = BigDecimal.ZERO;
        BigDecimal high = fee;
        while (high.subtract(low).compareTo(BigDecimal.ONE) > 0) {
            BigDecimal middle = low.add(high).divide(BigDecimal.TWO, 0, RoundingMode.DOWN);
            if (increment(before, middle, taxRate).compareTo(room) <= 0) {
                low = middle;
            } else {
                high = middle;
            }
        }
        BigDecimal fits = increment(before, high, taxRate).compareTo(room) <= 0 ? high : low;
        return new Settled(fits, fee.subtract(fits), increment(before, fits, taxRate));
    }

    // ---- the month's charge (F-M9a-2, plan §4 item 3) ---------------------------------------------------------------

    /**
     * A finished job waiting for its month's charge, as the charge reads it.
     *
     * @param operatorId the operator frozen on it at its request; null: none (asked before the sponsor's onboarding)
     * @param period     the month its outcome ran in (yyyy-MM)
     * @param fee        its fee before tax ({@code feeYen})
     * @param costPart   what of the fee is the provider's cost: its answered dollars at its frozen rate, rounded up
     */
    public record Job(String id, String operatorId, String period, BigDecimal fee, BigDecimal costPart) {}

    /**
     * One charge of a month: a group (operator, month), what is charged and the jobs it takes in.
     *
     * @param amount        the fee before tax (after any part the credit could not hold was taken on)
     * @param tax           its tax, worked out on the group's total as the charge does ({@link FeeRules#tax})
     * @param shareExcluded what of the amount is the provider's cost (F-M12 §3.2): the jobs' cost parts, in
     *                      proportion to what of each job is still charged, never above the amount
     * @param fees          each job's fee as charged (lower than read when the platform took a part on)
     */
    public record Charge(String operatorId, String period, BigDecimal amount, BigDecimal tax, BigDecimal shareExcluded,
        Map<String, BigDecimal> fees) {

        public BigDecimal total() {
            return amount.add(tax);
        }
    }

    /**
     * The month's charges of a sponsor's jobs (in the order they ended), one a group (operator, month), in the order of
     * the months and then the operators. Never more than the credit holds (plan H2 (b): a charge with a prepayment
     * pays what it cannot from the account, so it is stopped here): when a group's total with tax is above what is
     * left, its amount is lowered to the largest whose total fits, taken from the group's latest jobs first; what is
     * taken off is the platform's.
     */
    public static List<Charge> charges(List<Job> jobs, BigDecimal taxRate, BigDecimal remaining) {
        Map<List<String>, List<Job>> groups = new LinkedHashMap<>();
        jobs.stream()
            .sorted(java.util.Comparator.comparing(Job::period)
                .thenComparing(j -> j.operatorId() == null ? "" : j.operatorId()))
            .forEach(j -> groups.computeIfAbsent(java.util.Arrays.asList(j.operatorId(), j.period()),
                k -> new ArrayList<>()).add(j));
        List<Charge> charges = new ArrayList<>();
        BigDecimal left = remaining.max(BigDecimal.ZERO);
        for (List<Job> group : groups.values()) {
            BigDecimal amount = group.stream().map(Job::fee).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal fits = largestWithin(amount, taxRate, left);
            Map<String, BigDecimal> fees = new LinkedHashMap<>();
            group.forEach(j -> fees.put(j.id(), j.fee()));
            BigDecimal cut = amount.subtract(fits);
            // The latest jobs give way first (the group keeps the order the jobs ended in).
            for (int i = group.size() - 1; i >= 0 && cut.signum() > 0; i--) {
                Job job = group.get(i);
                BigDecimal off = job.fee().min(cut);
                fees.put(job.id(), job.fee().subtract(off));
                cut = cut.subtract(off);
            }
            BigDecimal excluded = BigDecimal.ZERO;
            for (Job job : group) {
                if (job.fee().signum() > 0 && job.costPart().signum() > 0) {
                    excluded = excluded.add(job.costPart().multiply(fees.get(job.id()))
                        .divide(job.fee(), 0, RoundingMode.UP));
                }
            }
            BigDecimal tax = Objects.requireNonNullElse(FeeRules.tax(fits, taxRate), BigDecimal.ZERO);
            charges.add(new Charge(group.getFirst().operatorId(), group.getFirst().period(), fits, tax,
                excluded.min(fits), Map.copyOf(fees)));
            left = left.subtract(fits.add(tax));
        }
        return List.copyOf(charges);
    }

    /** The largest amount (at most {@code amount}) whose total with tax is within {@code room}. */
    static BigDecimal largestWithin(BigDecimal amount, BigDecimal taxRate, BigDecimal room) {
        if (withTax(amount, taxRate).compareTo(room) <= 0) {
            return amount;
        }
        BigDecimal low = BigDecimal.ZERO;
        BigDecimal high = amount;
        while (high.subtract(low).compareTo(BigDecimal.ONE) > 0) {
            BigDecimal middle = low.add(high).divide(BigDecimal.TWO, 0, RoundingMode.DOWN);
            if (withTax(middle, taxRate).compareTo(room) <= 0) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return withTax(high, taxRate).compareTo(room) <= 0 ? high : low;
    }

    private AiFeeRules() {}
}

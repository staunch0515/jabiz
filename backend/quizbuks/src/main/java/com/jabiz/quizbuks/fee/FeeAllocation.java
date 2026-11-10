package com.jabiz.quizbuks.fee;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The order the account that pays the platform's fees is spent in (F-M1, spec "使用顺序"): the fee credit first, the
 * earliest to expire first (on the same instant: the earlier grant, then the lot's id), only lots not expired; then the
 * cash in the order it arrived (then the lot's id), never more than the cash the sponsor may spend (the balance less
 * what open disputes hold). Pays as much as there is: a fee may be paid in part.
 */
public final class FeeAllocation {

    private FeeAllocation() {}

    /** A lot of fee credit as the order sees it. */
    public record Credit(String grantId, BigDecimal remaining, Instant grantedAt, Instant expiresAt) {

        public Credit {
            Objects.requireNonNull(grantId, "grantId");
            Objects.requireNonNull(remaining, "remaining");
            Objects.requireNonNull(grantedAt, "grantedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /** A lot of cash as the order sees it. */
    public record Cash(String lotId, BigDecimal remaining, Instant arrivedAt) {

        public Cash {
            Objects.requireNonNull(lotId, "lotId");
            Objects.requireNonNull(remaining, "remaining");
            Objects.requireNonNull(arrivedAt, "arrivedAt");
        }
    }

    /** What one lot pays: {@link QbFeeAccount#FEE_CREDIT} with its grant, or {@link QbFeeAccount#CASH} with its lot. */
    public record Part(String subAccount, String lotId, BigDecimal amount) {}

    /** The parts, in the order they were taken, and their total (at most what was asked). */
    public record Result(List<Part> parts, BigDecimal paid) {

        public BigDecimal credit() {
            return sum(QbFeeAccount.FEE_CREDIT);
        }

        public BigDecimal cash() {
            return sum(QbFeeAccount.CASH);
        }

        private BigDecimal sum(String subAccount) {
            return parts.stream().filter(p -> subAccount.equals(p.subAccount())).map(Part::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /**
     * Pays {@code amount} from the lots: the credit not expired at {@code now} first, then the cash, at most
     * {@code cashSpendable} of it (the cash wallet's available amount).
     */
    public static Result allocate(List<Credit> credits, List<Cash> cash, BigDecimal cashSpendable, BigDecimal amount,
        Instant now) {
        BigDecimal left = amount.signum() > 0 ? amount : BigDecimal.ZERO;
        List<Part> parts = new ArrayList<>();
        List<Credit> creditOrder = credits.stream()
            .filter(c -> c.remaining().signum() > 0 && c.expiresAt().isAfter(now))
            .sorted(Comparator.comparing(Credit::expiresAt).thenComparing(Credit::grantedAt)
                .thenComparing(Credit::grantId))
            .toList();
        for (Credit credit : creditOrder) {
            if (left.signum() == 0) {
                break;
            }
            BigDecimal take = credit.remaining().min(left);
            parts.add(new Part(QbFeeAccount.FEE_CREDIT, credit.grantId(), take));
            left = left.subtract(take);
        }
        BigDecimal spendable = cashSpendable == null || cashSpendable.signum() < 0 ? BigDecimal.ZERO : cashSpendable;
        List<Cash> cashOrder = cash.stream().filter(c -> c.remaining().signum() > 0)
            .sorted(Comparator.comparing(Cash::arrivedAt).thenComparing(Cash::lotId)).toList();
        for (Cash lot : cashOrder) {
            if (left.signum() == 0 || spendable.signum() == 0) {
                break;
            }
            BigDecimal take = lot.remaining().min(left).min(spendable);
            parts.add(new Part(QbFeeAccount.CASH, lot.lotId(), take));
            left = left.subtract(take);
            spendable = spendable.subtract(take);
        }
        BigDecimal paid = parts.stream().map(Part::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Result(List.copyOf(parts), paid);
    }

    /**
     * Cash taken out of the account other than by a fee (Kudos bought with it, a top-up taken back): the lots in the
     * order they arrived, the {@code first} lot (the one the money being taken back came with) before the others.
     * The caller only takes what the balance has and the lots add up to the balance, so it takes the whole amount; if
     * they ever did not, it takes what the lots have and the daily check {@code CASH_LOTS} reports the difference (a
     * purchase of Kudos or a refund is not refused for the platform's own bookkeeping).
     */
    public static List<Part> takeCash(List<Cash> cash, BigDecimal amount, String first) {
        return takeCash(cash, amount, first, java.util.Set.of());
    }

    /**
     * As the other, the lots in {@code last} after every other (F-M4: the cash the Sponsor's operator moved there,
     * which pays the platform's fees only, is the last a top-up taken back reaches), each group in the order it
     * arrived.
     */
    public static List<Part> takeCash(List<Cash> cash, BigDecimal amount, String first,
        java.util.Set<String> last) {
        BigDecimal left = amount;
        List<Part> parts = new ArrayList<>();
        List<Cash> order = new ArrayList<>(cash.stream().filter(c -> c.remaining().signum() > 0)
            .sorted(Comparator.comparing(Cash::arrivedAt).thenComparing(Cash::lotId)).toList());
        // Stable sorts: the top-up's own lot first, the lots of `last` at the end, otherwise as they arrived.
        order.sort(Comparator.comparing(c -> last.contains(c.lotId())));
        if (first != null) {
            order.sort(Comparator.comparing(c -> !first.equals(c.lotId())));
        }
        for (Cash lot : order) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal take = lot.remaining().min(left);
            parts.add(new Part(QbFeeAccount.CASH, lot.lotId(), take));
            left = left.subtract(take);
        }
        return List.copyOf(parts);
    }
}

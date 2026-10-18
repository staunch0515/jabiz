package com.jabiz.quizbuks.wallet;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Which part of a taker's balance is held back from transfers (Q10b, review M3 and its review M-b), without I/O: the
 * model that the templates {@code qb.wallet.taker-transferable} and {@code qb.app.wallet} compute in SQL, written here
 * once more so that it can be tested on its own (docs/quizbuks/02-design.md, "奖励的保留").
 *
 * <p>The taker's lines on account 2120, in booking order, fall into four kinds: a reward held by a dispute of its
 * sponsor ({@link Kind#DISPUTED}: posted from the disputed payment on, while the dispute is open), a reward held by its
 * age only ({@link Kind#RECENT}: within the hold period), every other credit ({@link Kind#FREE}), and the debits
 * ({@link Kind#DEBIT}: transfers). Money is one balance, so which credit a debit took is a matter of convention; the
 * convention here is the one the transfers follow: a debit takes free money first, then money held by age, and money
 * held by a dispute last, and only money credited before it. So a reward transferred before its sponsor was disputed
 * no longer counts (it left with that transfer), and a reward of another sponsor is held only when the balance is too
 * small to keep both. With the running sums {@code D(t)} of the debits and {@code F(t)}, {@code R(t)} of the free and
 * recent credits up to each line, the debits took
 * <pre>
 *   from the held (recent or disputed):  c_held     = max(0, max_t (D(t) - F(t)))
 *   from the disputed:                   c_disputed = max(0, max_t (D(t) - F(t) - R(t)))
 * </pre>
 * and what is held is the held credits less what was taken, never below 0 and never above the balance.
 *
 * <p>Money that comes back from a transfer ({@link Kind#RETURN}: its sending reversed after a failure, or a part Stripe
 * reversed) is the transfer's own, not free money (verification of review M-b): it is netted against the transfer's
 * debit, at the debit's place, as if the transfer had taken only what stayed out. Lines of one transfer share a
 * {@code group} (the transfer, the posting's {@code sourceId}); a return without a debit of its group counts as free.
 */
public final class RewardHolds {

    /** What a line of account 2120 is to the holds. */
    public enum Kind { FREE, RECENT, DISPUTED, DEBIT, RETURN }

    /**
     * A line, in booking order, then in the order the processes ran (their sequence: the clock may not move between
     * two), then credits before debits; the amount is positive.
     *
     * @param group the transfer a debit or return belongs to; null for the other kinds
     */
    public record Line(Kind kind, BigDecimal amount, String group) {
        public Line {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }

        public Line(Kind kind, BigDecimal amount) {
            this(kind, amount, null);
        }
    }

    /**
     * The lines with each transfer's returns netted against its debits (the first debit of the group first), in
     * place; returns without a debit of their group become free credits.
     */
    static List<Line> netted(List<Line> lines) {
        java.util.Map<String, BigDecimal> back = new java.util.HashMap<>();
        java.util.Set<String> sent = new java.util.HashSet<>();
        for (Line line : lines) {
            if (line.kind() == Kind.RETURN && line.group() != null) {
                back.merge(line.group(), line.amount(), BigDecimal::add);
            } else if (line.kind() == Kind.DEBIT && line.group() != null) {
                sent.add(line.group());
            }
        }
        List<Line> out = new java.util.ArrayList<>();
        for (Line line : lines) {
            if (line.kind() == Kind.RETURN) {
                if (line.group() == null || !sent.contains(line.group())) {
                    out.add(new Line(Kind.FREE, line.amount()));
                }
            } else if (line.kind() == Kind.DEBIT && line.group() != null) {
                BigDecimal left = back.getOrDefault(line.group(), BigDecimal.ZERO);
                BigDecimal used = left.min(line.amount());
                back.put(line.group(), left.subtract(used));
                out.add(new Line(Kind.DEBIT, line.amount().subtract(used), line.group()));
            } else {
                out.add(line);
            }
        }
        return out;
    }

    /**
     * The outcome.
     *
     * @param balance  the balance (credits less debits)
     * @param held     what is held back, 0 to the balance
     * @param disputed the part of {@code held} held by disputes
     */
    public record Held(BigDecimal balance, BigDecimal held, BigDecimal disputed) {

        /** What may leave: the balance less what is held. */
        public BigDecimal transferable() {
            return TransferRules.transferable(balance, held);
        }

        /** The part held by its age only. */
        public BigDecimal recent() {
            return held.subtract(disputed);
        }
    }

    /** The holds of the lines, by the closed form the SQL computes. */
    public static Held of(List<Line> given) {
        List<Line> lines = netted(given);
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal free = BigDecimal.ZERO;
        BigDecimal recent = BigDecimal.ZERO;
        BigDecimal disputed = BigDecimal.ZERO;
        BigDecimal takenFromHeld = BigDecimal.ZERO;
        BigDecimal takenFromDisputed = BigDecimal.ZERO;
        for (Line line : lines) {
            switch (line.kind()) {
                case DEBIT -> debits = debits.add(line.amount());
                case FREE -> free = free.add(line.amount());
                case RECENT -> recent = recent.add(line.amount());
                case DISPUTED -> disputed = disputed.add(line.amount());
                case RETURN -> throw new IllegalStateException("netted away");
            }
            takenFromHeld = takenFromHeld.max(debits.subtract(free));
            takenFromDisputed = takenFromDisputed.max(debits.subtract(free).subtract(recent));
        }
        BigDecimal balance = free.add(recent).add(disputed).subtract(debits);
        return bounded(balance, recent.add(disputed).subtract(takenFromHeld),
            disputed.subtract(takenFromDisputed));
    }

    /** {@code held} within 0 and the balance, {@code disputed} within 0 and that. */
    static Held bounded(BigDecimal balance, BigDecimal held, BigDecimal disputed) {
        BigDecimal top = balance.max(BigDecimal.ZERO);
        BigDecimal all = held.max(BigDecimal.ZERO).min(top);
        return new Held(balance, all, disputed.max(BigDecimal.ZERO).min(all));
    }

    private RewardHolds() {}
}

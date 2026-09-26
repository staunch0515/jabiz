package com.jabiz.ledger;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * Running balances per account: debit total, credit total and their difference (debit minus credit, so that the
 * balances of all accounts of a balanced ledger add up to zero). Not thread-safe.
 */
public final class LedgerBalances {

    /** Totals of one account. */
    public record Balance(BigDecimal debit, BigDecimal credit) {

        public static final Balance ZERO = new Balance(BigDecimal.ZERO, BigDecimal.ZERO);

        /** Debit minus credit. */
        public BigDecimal balance() {
            return debit.subtract(credit);
        }

        Balance plus(PostingLine line) {
            return line.direction() == Direction.DEBIT
                ? new Balance(debit.add(line.amount()), credit)
                : new Balance(debit, credit.add(line.amount()));
        }
    }

    private final Map<String, Balance> balances = new TreeMap<>();

    /** Adds the entries of one posted transaction. */
    public LedgerBalances post(Collection<PostingLine> lines) {
        for (PostingLine line : lines) {
            balances.merge(line.accountCode(), Balance.ZERO.plus(line), (old, ignored) -> old.plus(line));
        }
        return this;
    }

    public Balance of(String accountCode) {
        return balances.getOrDefault(accountCode, Balance.ZERO);
    }

    /** Balances by account code, in code order. */
    public Map<String, Balance> all() {
        return Map.copyOf(balances);
    }

    /** Sum of all balances; zero whenever only balanced transactions were posted. */
    public BigDecimal total() {
        return balances.values().stream().map(Balance::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

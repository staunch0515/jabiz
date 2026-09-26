package com.jabiz.ledger;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ROADMAP phase 9 acceptance (docs/design/07-quality.md section 6): after any sequence of attempted postings and
 * reversals, whatever the rules accept is balanced, and the balances of all accounts add up to zero. The same
 * invariant is checked against the database by the ledger integration test.
 */
class LedgerProperties {

    static final List<String> ACCOUNTS = List.of("1000", "1100", "2100", "3000", "4100", "5100");

    /** An attempted operation: a posting of arbitrary lines, or the reversal of an earlier accepted posting. */
    record Attempt(List<PostingLine> lines, int reverseIndex) {}

    @Provide
    Arbitrary<List<Attempt>> attempts() {
        Arbitrary<PostingLine> line = Combinators.combine(
            Arbitraries.of(ACCOUNTS),
            Arbitraries.of(Direction.class),
            // Mostly positive whole amounts, sometimes zero, negative or with cents.
            Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(8, Arbitraries.integers().between(1, 100_000).map(BigDecimal::valueOf)),
                net.jqwik.api.Tuple.of(1, Arbitraries.integers().between(-100, 0).map(BigDecimal::valueOf)),
                net.jqwik.api.Tuple.of(1, Arbitraries.integers().between(1, 10_000)
                    .map(cents -> BigDecimal.valueOf(cents, 2))))).as(PostingLine::new);
        // Half of the postings are balanced by construction: a counter line takes up the difference.
        Arbitrary<List<PostingLine>> balanced = line.list().ofMinSize(1).ofMaxSize(6).map(LedgerProperties::balance);
        Arbitrary<List<PostingLine>> posting = Arbitraries.oneOf(balanced, line.list().ofMinSize(0).ofMaxSize(6));
        Arbitrary<Attempt> attempt = Arbitraries.oneOf(
            posting.map(lines -> new Attempt(lines, -1)),
            Arbitraries.integers().between(0, 30).map(index -> new Attempt(null, index)));
        return attempt.list().ofMaxSize(40);
    }

    static List<PostingLine> balance(List<PostingLine> lines) {
        BigDecimal difference = LedgerPosting.total(lines, Direction.DEBIT)
            .subtract(LedgerPosting.total(lines, Direction.CREDIT));
        List<PostingLine> balanced = new ArrayList<>(lines);
        if (difference.signum() != 0) {
            balanced.add(new PostingLine("3000", difference.signum() > 0 ? Direction.CREDIT : Direction.DEBIT,
                difference.abs()));
        }
        return balanced;
    }

    @Property(tries = 500)
    void acceptedTransactionsBalanceAndAllBalancesAddUpToZero(@ForAll("attempts") List<Attempt> attempts) {
        LedgerBalances balances = new LedgerBalances();
        List<List<PostingLine>> posted = new ArrayList<>();
        List<Boolean> reversed = new ArrayList<>();
        for (Attempt attempt : attempts) {
            List<PostingLine> lines;
            if (attempt.lines() != null) {
                lines = attempt.lines();
                if (!LedgerPosting.validate(lines, 0).isEmpty()) {
                    continue;
                }
                posted.add(lines);
                reversed.add(false);
            } else {
                // Reversal of an accepted posting, at most once: what LEDGER_REVERSE allows.
                if (attempt.reverseIndex() >= posted.size() || reversed.get(attempt.reverseIndex())) {
                    continue;
                }
                reversed.set(attempt.reverseIndex(), true);
                lines = LedgerPosting.reversal(posted.get(attempt.reverseIndex()));
                assertThat(LedgerPosting.validate(lines, 0)).isEmpty();
            }
            assertThat(LedgerPosting.total(lines, Direction.DEBIT))
                .isEqualByComparingTo(LedgerPosting.total(lines, Direction.CREDIT));
            balances.post(lines);
            assertThat(balances.total()).isEqualByComparingTo(BigDecimal.ZERO);
        }
        // A posting and its reversal cancel out exactly.
        LedgerBalances reversedOnly = new LedgerBalances();
        for (int i = 0; i < posted.size(); i++) {
            if (reversed.get(i)) {
                reversedOnly.post(posted.get(i)).post(LedgerPosting.reversal(posted.get(i)));
            }
        }
        reversedOnly.all().values().forEach(balance ->
            assertThat(balance.balance()).isEqualByComparingTo(BigDecimal.ZERO));
    }

    @Property(tries = 300)
    void unbalancedPostingsAreAlwaysRefused(@ForAll("attempts") List<Attempt> attempts) {
        for (Attempt attempt : attempts) {
            if (attempt.lines() == null) {
                continue;
            }
            boolean balancedTotals = LedgerPosting.total(attempt.lines(), Direction.DEBIT)
                .compareTo(LedgerPosting.total(attempt.lines(), Direction.CREDIT)) == 0;
            if (!balancedTotals) {
                assertThat(LedgerPosting.validate(attempt.lines(), 2)).isNotEmpty();
            }
        }
    }
}

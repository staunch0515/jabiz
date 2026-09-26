package com.jabiz.app.it.ledger;

import com.jabiz.ledger.Direction;
import com.jabiz.ledger.LedgerBalances;
import com.jabiz.ledger.LedgerPosting;
import com.jabiz.ledger.PostingLine;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.ledger.LedgerProcesses;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;
import net.jqwik.api.sessions.JqwikSession;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ROADMAP phase 9 acceptance: after any sequence of transactions, every transaction balances and the balances of all
 * accounts add up to zero, in the database. jqwik generates the sequences (the same generators as the core property
 * {@code LedgerProperties}): postings of arbitrary lines, half of them balanced, some with zero, negative or
 * fractional amounts, and reversals of earlier postings, some repeated. Every attempt goes through the ledger
 * processes; what they refuse must leave no trace, what they accept must match a model kept alongside.
 *
 * <p>The seeds are fixed so that a failure reproduces; each sequence runs on accounts of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class LedgerPropertyIT extends LedgerItSupport {

    private static final List<String> ACCOUNTS = List.of("1000", "1100", "2100", "3000", "4100", "5100");
    private static final long[] SEEDS = {9_001L, 9_002L, 9_003L, 9_004L, 9_005L, 9_006L};

    /** A posting of arbitrary lines, or (with {@code lines} null) the reversal of an earlier accepted posting. */
    record Attempt(List<PostingLine> lines, int reverseIndex) {}

    static Arbitrary<List<Attempt>> attempts() {
        Arbitrary<PostingLine> line = Combinators.combine(
            Arbitraries.of(ACCOUNTS),
            Arbitraries.of(Direction.class),
            Arbitraries.frequencyOf(
                Tuple.of(8, Arbitraries.integers().between(1, 100_000).map(BigDecimal::valueOf)),
                Tuple.of(1, Arbitraries.integers().between(-100, 0).map(BigDecimal::valueOf)),
                Tuple.of(1, Arbitraries.integers().between(1, 10_000).map(cents -> BigDecimal.valueOf(cents, 2)))))
            .as(PostingLine::new);
        Arbitrary<List<PostingLine>> balanced = line.list().ofMinSize(1).ofMaxSize(5).map(LedgerPropertyIT::balance);
        Arbitrary<List<PostingLine>> posting = Arbitraries.oneOf(balanced, line.list().ofMinSize(1).ofMaxSize(5));
        return Arbitraries.frequencyOf(
                Tuple.of(3, posting.map(lines -> new Attempt(lines, -1))),
                Tuple.of(1, Arbitraries.integers().between(0, 12).map(index -> new Attempt(null, index))))
            .list().ofMinSize(10).ofMaxSize(20);
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

    @Test
    void everyTransactionBalancesAndAllBalancesAddUpToZero() {
        int accepted = 0;
        int refused = 0;
        for (long seed : SEEDS) {
            List<Attempt> sequence = generate(seed);
            String p = prefix();
            openAccounts(p, ACCOUNTS.toArray(String[]::new));
            LedgerBalances model = new LedgerBalances();
            List<String> posted = new ArrayList<>();
            List<List<PostingLine>> postedLines = new ArrayList<>();
            List<Boolean> reversed = new ArrayList<>();

            for (Attempt attempt : sequence) {
                clock.advance(Duration.ofMinutes(1));
                if (attempt.lines() != null) {
                    List<PostingLine> lines = attempt.lines();
                    boolean valid = LedgerPosting.validate(lines, 0).isEmpty();
                    if (!valid) {
                        assertThatThrownBy(() -> post(p, lines)).isInstanceOf(BusinessRuleViolationException.class);
                        refused++;
                        continue;
                    }
                    posted.add(post(p, lines).transactionId());
                    postedLines.add(lines);
                    reversed.add(false);
                    model.post(lines);
                    accepted++;
                } else {
                    int index = attempt.reverseIndex();
                    if (index >= posted.size()) {
                        continue;
                    }
                    if (reversed.get(index)) {
                        assertThatThrownBy(() -> reverse(posted.get(index)))
                            .isInstanceOf(BusinessRuleViolationException.class);
                        refused++;
                        continue;
                    }
                    reverse(posted.get(index));
                    reversed.set(index, true);
                    model.post(LedgerPosting.reversal(postedLines.get(index)));
                    accepted++;
                }
            }

            // Every transaction in the database balances, and the balances are the model's, adding up to zero.
            assertThat(unbalancedTransactions(p)).isEmpty();
            Map<String, BigDecimal> balances = balances(p, clock.instant());
            for (String account : ACCOUNTS) {
                assertThat(balances.get(account)).as("balance of %s (seed %d)", account, seed)
                    .isEqualByComparingTo(model.of(account).balance());
            }
            assertThat(balances.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("sum of all balances (seed %d)", seed).isEqualByComparingTo("0");
        }
        // The generated sequences exercise both outcomes.
        assertThat(accepted).isGreaterThan(20);
        assertThat(refused).isGreaterThan(5);
    }

    /** One sequence from the generators, outside a jqwik property: a jqwik session provides their context. */
    private static List<Attempt> generate(long seed) {
        JqwikSession.start(String.valueOf(seed));
        try {
            return attempts().generator(1000).next(new Random(seed)).value();
        } finally {
            JqwikSession.finish();
        }
    }

    private LedgerProcesses.PostOutput post(String prefix, List<PostingLine> lines) {
        Object[] flat = new Object[lines.size() * 3];
        for (int i = 0; i < lines.size(); i++) {
            flat[3 * i] = lines.get(i).accountCode();
            flat[3 * i + 1] = lines.get(i).direction();
            flat[3 * i + 2] = lines.get(i).amount().toPlainString();
        }
        return post(prefix, flat);
    }
}

package com.jabiz.quizbuks.wallet;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.jabiz.quizbuks.wallet.RewardHolds.Kind.DEBIT;
import static com.jabiz.quizbuks.wallet.RewardHolds.Kind.DISPUTED;
import static com.jabiz.quizbuks.wallet.RewardHolds.Kind.FREE;
import static com.jabiz.quizbuks.wallet.RewardHolds.Kind.RECENT;
import static org.assertj.core.api.Assertions.assertThat;

/** The holds on a taker's balance (Q10b, reviews M3 and M-b). */
class RewardHoldsTest {

    private static RewardHolds.Line line(RewardHolds.Kind kind, long amount) {
        return new RewardHolds.Line(kind, BigDecimal.valueOf(amount));
    }

    @Test
    void aRewardTransferredBeforeItsSponsorWasDisputedNoLongerHoldsTheOthers() {
        // Sponsor X's reward, transferred; then sponsor Y's; then X's payment is disputed.
        RewardHolds.Held held = RewardHolds.of(List.of(line(DISPUTED, 100), line(DEBIT, 100), line(FREE, 100)));
        assertThat(held.balance()).isEqualByComparingTo("100");
        assertThat(held.held()).isEqualByComparingTo("0");
        assertThat(held.transferable()).isEqualByComparingTo("100");
    }

    @Test
    void aTransferMadeWhileTheDisputeWasOpenTookTheOtherSponsorsMoney() {
        // X's reward (disputed), Y's reward, then a transfer of Y's while X was disputed: X's stays held.
        RewardHolds.Held held = RewardHolds.of(List.of(line(DISPUTED, 100), line(FREE, 100), line(DEBIT, 100)));
        assertThat(held.held()).isEqualByComparingTo("100");
        assertThat(held.disputed()).isEqualByComparingTo("100");
        assertThat(held.transferable()).isEqualByComparingTo("0");
    }

    @Test
    void recentAndDisputedTogether() {
        RewardHolds.Held held = RewardHolds.of(List.of(line(DISPUTED, 100), line(FREE, 100), line(RECENT, 100)));
        assertThat(held.held()).isEqualByComparingTo("200");
        assertThat(held.disputed()).isEqualByComparingTo("100");
        assertThat(held.recent()).isEqualByComparingTo("100");
        assertThat(held.transferable()).isEqualByComparingTo("100");
    }

    @Test
    void aDebitTakesOnlyWhatCameBeforeIt() {
        // A transfer of the old money, then a recent reward: the reward is held whole.
        RewardHolds.Held held = RewardHolds.of(List.of(line(FREE, 300), line(DEBIT, 300), line(RECENT, 50)));
        assertThat(held.held()).isEqualByComparingTo("50");
        assertThat(held.transferable()).isEqualByComparingTo("0");
    }

    @Test
    void neverAboveTheBalanceOrBelowZero() {
        // A ledger that went below zero (it cannot): nothing held, nothing to transfer.
        RewardHolds.Held held = RewardHolds.of(List.of(line(RECENT, 100), line(DEBIT, 150)));
        assertThat(held.held()).isEqualByComparingTo("0");
        assertThat(held.transferable()).isEqualByComparingTo("0");
    }

    /** A sequence of lines whose running balance never goes below 0, as a ledger's. */
    @Provide
    Arbitrary<List<RewardHolds.Line>> ledgers() {
        Arbitrary<RewardHolds.Kind> kinds = Arbitraries.of(FREE, RECENT, DISPUTED, DEBIT);
        Arbitrary<Long> amounts = Arbitraries.longs().between(1, 1_000);
        return Combinators.combine(kinds, amounts).as(Pair::new).list().ofMaxSize(40)
            .map(pairs -> {
                List<RewardHolds.Line> lines = new ArrayList<>();
                long balance = 0;
                for (Pair pair : pairs) {
                    long amount = pair.amount();
                    if (pair.kind() == DEBIT) {
                        amount = Math.min(amount, balance);
                        if (amount == 0) {
                            continue;
                        }
                        balance -= amount;
                    } else {
                        balance += amount;
                    }
                    lines.add(line(pair.kind(), amount));
                }
                return lines;
            });
    }

    record Pair(RewardHolds.Kind kind, long amount) {}

    /** A send that comes back, wholly or in part, at some later line. */
    record Back(int send, int after, long percent) {}

    /**
     * A ledger as {@link #ledgers()}, its debits transfers (each its own group), some of which come back later, in full
     * or in part (a failed sending reversed, a part Stripe reversed).
     */
    @Provide
    Arbitrary<Returned> returned() {
        Arbitrary<Back> backs = Combinators.combine(Arbitraries.integers().between(0, 39),
            Arbitraries.integers().between(0, 40), Arbitraries.of(100L, 100L, 50L, 1L, 99L)).as(Back::new);
        return Combinators.combine(ledgers(), backs.list().ofMaxSize(5)).as((lines, chosen) -> {
            List<RewardHolds.Line> sent = new ArrayList<>();
            int group = 0;
            for (RewardHolds.Line line : lines) {
                sent.add(line.kind() == DEBIT ? new RewardHolds.Line(DEBIT, line.amount(), "t" + group++) : line);
            }
            List<RewardHolds.Line> withReturns = new ArrayList<>(sent);
            java.util.Map<String, Long> returnedOf = new java.util.HashMap<>();
            for (Back back : chosen) {
                List<RewardHolds.Line> debits = sent.stream().filter(l -> l.kind() == DEBIT).toList();
                if (debits.isEmpty()) {
                    break;
                }
                RewardHolds.Line debit = debits.get(back.send() % debits.size());
                if (returnedOf.containsKey(debit.group())) {
                    continue;
                }
                long amount = Math.max(1, debit.amount().longValueExact() * back.percent() / 100);
                returnedOf.put(debit.group(), amount);
                int at = withReturns.indexOf(debit) + 1;
                int place = Math.min(withReturns.size(), at + back.after() % (withReturns.size() - at + 1));
                withReturns.add(place, new RewardHolds.Line(RewardHolds.Kind.RETURN, BigDecimal.valueOf(amount),
                    debit.group()));
            }
            // The same ledger as if each transfer had only ever taken what stayed out.
            List<RewardHolds.Line> smaller = new ArrayList<>();
            for (RewardHolds.Line line : sent) {
                if (line.kind() == DEBIT && returnedOf.containsKey(line.group())) {
                    long left = line.amount().longValueExact() - returnedOf.get(line.group());
                    if (left > 0) {
                        smaller.add(new RewardHolds.Line(DEBIT, BigDecimal.valueOf(left), line.group()));
                    }
                } else {
                    smaller.add(line);
                }
            }
            return new Returned(withReturns, smaller);
        });
    }

    record Returned(List<RewardHolds.Line> lines, List<RewardHolds.Line> asIfSmaller) {}

    /**
     * Verification of review M-b: what comes back from a transfer is the transfer's own money. A ledger with returns
     * holds what the same ledger holds had each transfer sent only what stayed out; a full return is as if never sent.
     */
    @Property(tries = 1000)
    void moneyThatComesBackIsNettedAgainstItsTransfer(@ForAll("returned") Returned ledger) {
        RewardHolds.Held held = RewardHolds.of(ledger.lines());
        RewardHolds.Held expected = RewardHolds.of(ledger.asIfSmaller());
        assertThat(held.balance()).isEqualByComparingTo(expected.balance());
        assertThat(held.held()).isEqualByComparingTo(expected.held());
        assertThat(held.disputed()).isEqualByComparingTo(expected.disputed());
        BigDecimal top = held.balance().max(BigDecimal.ZERO);
        assertThat(held.transferable()).isBetween(BigDecimal.ZERO, top);
    }

    @Test
    void aFailedTransferBringsTheDisputedRewardBackHeld() {
        // The probe of the verification review: the disputed reward left with a transfer that then failed.
        RewardHolds.Held held = RewardHolds.of(List.of(line(DISPUTED, 100),
            new RewardHolds.Line(DEBIT, BigDecimal.valueOf(100), "t1"),
            new RewardHolds.Line(RewardHolds.Kind.RETURN, BigDecimal.valueOf(100), "t1")));
        assertThat(held.balance()).isEqualByComparingTo("100");
        assertThat(held.disputed()).isEqualByComparingTo("100");
        assertThat(held.transferable()).isEqualByComparingTo("0");
        // A part Stripe reversed: the transfer took the free reward first, so the disputed one is back.
        held = RewardHolds.of(List.of(line(DISPUTED, 100), line(FREE, 100),
            new RewardHolds.Line(DEBIT, BigDecimal.valueOf(200), "t2"),
            new RewardHolds.Line(RewardHolds.Kind.RETURN, BigDecimal.valueOf(100), "t2")));
        assertThat(held.held()).isEqualByComparingTo("100");
        assertThat(held.transferable()).isEqualByComparingTo("0");
        // A return of nothing known is free money.
        held = RewardHolds.of(List.of(new RewardHolds.Line(RewardHolds.Kind.RETURN, BigDecimal.TEN, "t9")));
        assertThat(held.transferable()).isEqualByComparingTo("10");
    }

    /**
     * The closed form is the allocation it describes: each debit, in order, takes free money first, then money held
     * by age, then money held by a dispute, of what was credited before it.
     */
    @Property(tries = 1000)
    void theClosedFormIsTheAllocation(@ForAll("ledgers") List<RewardHolds.Line> lines) {
        long free = 0;
        long recent = 0;
        long disputed = 0;
        for (RewardHolds.Line line : lines) {
            long amount = line.amount().longValueExact();
            switch (line.kind()) {
                case FREE -> free += amount;
                case RECENT -> recent += amount;
                case DISPUTED -> disputed += amount;
                case DEBIT -> {
                    long take = Math.min(free, amount);
                    free -= take;
                    amount -= take;
                    take = Math.min(recent, amount);
                    recent -= take;
                    amount -= take;
                    disputed -= amount;
                }
            }
        }
        RewardHolds.Held held = RewardHolds.of(lines);
        assertThat(held.held()).isEqualByComparingTo(BigDecimal.valueOf(recent + disputed));
        assertThat(held.disputed()).isEqualByComparingTo(BigDecimal.valueOf(disputed));
        assertThat(held.balance()).isEqualByComparingTo(BigDecimal.valueOf(free + recent + disputed));
    }

    /** Whatever the lines (a balance below 0 included), the transferable part is between 0 and the balance. */
    @Property(tries = 1000)
    void transferableIsNeverNegativeNorMoreThanTheBalance(@ForAll("anyLines") List<RewardHolds.Line> lines) {
        RewardHolds.Held held = RewardHolds.of(lines);
        BigDecimal top = held.balance().max(BigDecimal.ZERO);
        assertThat(held.held()).isBetween(BigDecimal.ZERO, top);
        assertThat(held.disputed()).isBetween(BigDecimal.ZERO, held.held());
        assertThat(held.transferable()).isBetween(BigDecimal.ZERO, top);
        assertThat(held.transferable().add(held.held())).isEqualByComparingTo(top);
    }

    @Provide
    Arbitrary<List<RewardHolds.Line>> anyLines() {
        return Combinators.combine(Arbitraries.of(RewardHolds.Kind.values()), Arbitraries.longs().between(1, 1_000))
            .as(RewardHoldsTest::line).list().ofMaxSize(40);
    }

    /** The transfers' rule bounds what it is given the same way (a held amount above the balance or below 0). */
    @Property(tries = 500)
    void theTransfersRuleIsBoundedToo(@ForAll("money") BigDecimal balance, @ForAll("anyMoney") BigDecimal held) {
        BigDecimal transferable = TransferRules.transferable(balance, held);
        assertThat(transferable).isBetween(BigDecimal.ZERO, balance.max(BigDecimal.ZERO));
    }

    @Provide
    Arbitrary<BigDecimal> money() {
        return Arbitraries.longs().between(-1_000, 100_000).map(BigDecimal::valueOf);
    }

    @Provide
    Arbitrary<BigDecimal> anyMoney() {
        return Arbitraries.longs().between(-100_000, 100_000).map(BigDecimal::valueOf);
    }
}

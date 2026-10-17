package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.publishing.PublicationRules;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The algebra of the lock split (Q10a4, docs/quizbuks/plans/Q10-lock-split.md 2.3, 6.3), in pure Java: a start checks
 * the limits on its own side of the counters, exact under the start lock, and on the completions' side as of some
 * moment before; the completions run under the other lock. Whatever moment that is, the limits are never exceeded, the
 * publication ends once, and no start gets in after it ended.
 */
class CounterSplitProperties {

    /** The starts' side: starts {@code s}, reserved by every start {@code R}. */
    record Starts(long s, BigDecimal reservedTotal) {}

    /** The completions' side: completions, winners, released by every completion, spent. */
    record Completions(long c, long w, BigDecimal released, BigDecimal spent) {}

    static AttemptRules.Counts counts(Starts starts, Completions completions) {
        return AttemptRules.counts(starts.s(), starts.reservedTotal(), completions.c(), completions.w(),
            completions.released(), completions.spent());
    }

    static BigDecimal d(long value) {
        return BigDecimal.valueOf(value);
    }

    // ---- lemma 1: an older completions' side is only stricter ----------------------------------------------------

    /**
     * For any consistent counters, limits and one completion (reward {@code 0 <= r <= m}): whatever the check refuses
     * on the completions' side after the completion, it refuses on the side before it.
     */
    @Property(tries = 2_000)
    void aCompletionOnlyLoosensTheCheck(@ForAll @LongRange(min = 1, max = 50) long starts,
        @ForAll @LongRange(min = 0, max = 49) long completed, @ForAll @LongRange(min = 0, max = 50) long winners,
        @ForAll @LongRange(min = 1, max = 500) long maxReward, @ForAll @IntRange(min = 0, max = 100) int rewardPercent,
        @ForAll @IntRange(min = 0, max = 100) int spentPercent, @ForAll @IntRange(min = 1, max = 60) int maxWinners,
        @ForAll @LongRange(min = 0, max = 30_000) long budgetCap, @ForAll boolean withWinners,
        @ForAll boolean withBudget) {
        long c = Math.min(completed, starts - 1);
        long w = Math.min(winners, c);
        BigDecimal m = d(maxReward);
        BigDecimal spent = d(c * maxReward * spentPercent / 100);
        Starts s = new Starts(starts, m.multiply(d(starts)));
        Completions before = new Completions(c, w, m.multiply(d(c)), spent);
        BigDecimal r = d(maxReward * rewardPercent / 100);
        Completions after = new Completions(c + 1, w + (r.signum() > 0 ? 1 : 0), before.released().add(m),
            spent.add(r));
        AttemptRules.Limits limits = new AttemptRules.Limits(withBudget ? d(budgetCap) : null,
            withWinners ? maxWinners : null, m);

        AttemptRules.Counts older = counts(s, before);
        AttemptRules.Counts newer = counts(s, after);
        // Q and B of the plan: never larger on the newer side.
        assertThat(newer.winners() + newer.inProgress()).isLessThanOrEqualTo(older.winners() + older.inProgress());
        assertThat(newer.spent().add(newer.reserved())).isLessThanOrEqualTo(older.spent().add(older.reserved()));
        if (AttemptRules.limitRefusal(limits, newer) != null) {
            assertThat(AttemptRules.limitRefusal(limits, older)).as("refused on the newer side, so on the older")
                .isNotNull();
        }
    }

    // ---- the interleaving model ----------------------------------------------------------------------------------

    /**
     * Random runs of starts (each checking on its exact starts' side and on a completions' side of any moment so far),
     * completions with random rewards up to the highest, and abandoned attempts (finished by the expiry with reward 0).
     * After every step: I1 {@code w <= W}, I2 {@code X <= B}, I3 {@code s >= c}, I4 {@code R - L = (s - c) m}, and the
     * stronger {@code w + (s - c) <= W}, {@code X + (R - L) <= B}; the publication ends at most once, and no start is
     * let in once it ended.
     */
    @Property(tries = 1_000)
    void anyInterleavingKeepsTheInvariants(@ForAll("scripts") List<Integer> script,
        @ForAll @LongRange(min = 1, max = 300) long maxReward, @ForAll @IntRange(min = 1, max = 12) int maxWinners,
        @ForAll @IntRange(min = 0, max = 15) int budgetInRewards, @ForAll boolean withWinners,
        @ForAll boolean withBudget, @ForAll @IntRange(min = 1, max = 4) int tiers) {
        BigDecimal m = d(maxReward);
        Integer winnersCap = withWinners ? maxWinners : null;
        // A cap that is often not a multiple of the highest reward, so that a low reward can leave room for one more.
        BigDecimal budgetCap = withBudget ? d(budgetInRewards * maxReward + maxReward / 2) : null;
        AttemptRules.Limits limits = new AttemptRules.Limits(budgetCap, winnersCap, m);

        Starts starts = new Starts(0, BigDecimal.ZERO);
        List<Completions> history = new ArrayList<>();
        history.add(new Completions(0, 0, BigDecimal.ZERO, BigDecimal.ZERO));
        int inProgress = 0;
        int ends = 0;
        boolean ended = false;
        for (int step : script) {
            Completions now = history.getLast();
            int kind = Math.floorMod(step, 3);
            int arg = Math.floorDiv(Math.abs(step), 3);
            if (kind == 0) {
                // A start: its starts' side exact, its completions' side from any moment so far.
                Completions seen = history.get(arg % history.size());
                String refusal = AttemptRules.limitRefusal(limits, counts(starts, seen));
                if (refusal == null) {
                    assertThat(ended).as("a start let in after the publication ended").isFalse();
                    starts = new Starts(starts.s() + 1, starts.reservedTotal().add(m));
                    inProgress++;
                }
            } else if (inProgress > 0) {
                // A completion (kind 1: submitted with a tier's reward; kind 2: expired, a reward from the saved
                // answers, possibly 0), under the publication's lock: the completions' side exact.
                long tier = arg % (tiers + 1);
                BigDecimal r = kind == 2 && arg % 2 == 0 ? BigDecimal.ZERO : d(maxReward * tier / tiers);
                inProgress--;
                Completions next = new Completions(now.c() + 1, now.w() + (r.signum() > 0 ? 1 : 0),
                    now.released().add(m), now.spent().add(r));
                history.add(next);
                if (!ended && PublicationRules.endsBy(budgetCap, winnersCap, m, next.w(), next.spent()) != null) {
                    ended = true;
                    ends++;
                }
            }
            Completions c = history.getLast();
            assertThat(c.c()).as("I3").isLessThanOrEqualTo(starts.s());
            assertThat(starts.s() - c.c()).isEqualTo(inProgress);
            assertThat(starts.reservedTotal().subtract(c.released())).as("I4")
                .isEqualByComparingTo(m.multiply(d(starts.s() - c.c())));
            if (winnersCap != null) {
                assertThat(c.w()).as("I1").isLessThanOrEqualTo(winnersCap);
                assertThat(c.w() + inProgress).isLessThanOrEqualTo(winnersCap);
            }
            if (budgetCap != null) {
                assertThat(c.spent()).as("I2").isLessThanOrEqualTo(budgetCap);
                assertThat(c.spent().add(starts.reservedTotal()).subtract(c.released()))
                    .isLessThanOrEqualTo(budgetCap);
            }
            assertThat(ends).as("ENDED once").isLessThanOrEqualTo(1);
        }
    }

    @Provide
    Arbitrary<List<Integer>> scripts() {
        return Arbitraries.integers().between(0, 3_000).list().ofMinSize(1).ofMaxSize(120);
    }
}

package com.jabiz.quizbuks.fee;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** F-M9 (docs/quizbuks/plans/impl/F-M9.md §3): the AI fee's arithmetic, in yen, without the database. */
class AiFeeRulesTest {

    private static final BigDecimal MARKUP = new BigDecimal("1.50");
    private static final BigDecimal RATE = new BigDecimal("150.00");
    private static final BigDecimal TAX = new BigDecimal("0.10");

    private static BigDecimal yen(long value) {
        return BigDecimal.valueOf(value);
    }

    @Test
    void theFeeIsTheCostTimesTheMarkupAndTheRateRoundedUpToTheYen() {
        // $0.123456 x 1.5 x 150 = 27.7776 -> 28.
        assertThat(AiFeeRules.fee(new BigDecimal("0.123456"), MARKUP, RATE)).isEqualByComparingTo("28");
        // A whole number stays as it is.
        assertThat(AiFeeRules.fee(new BigDecimal("1"), MARKUP, RATE)).isEqualByComparingTo("225");
        // The smallest usage is a yen; none is nothing.
        assertThat(AiFeeRules.fee(new BigDecimal("0.000001"), MARKUP, RATE)).isEqualByComparingTo("1");
        assertThat(AiFeeRules.fee(BigDecimal.ZERO, MARKUP, RATE)).isEqualByComparingTo("0");
        assertThat(AiFeeRules.fee(null, MARKUP, RATE)).isEqualByComparingTo("0");
    }

    @Test
    void theCostPartIsTheDollarsAtTheRateRoundedUpWithoutTheMarkup() {
        // For the operators' share (F-M12 §3.2): $0.123456 x 150 = 18.5184 -> 19.
        assertThat(AiFeeRules.costPart(new BigDecimal("0.123456"), RATE)).isEqualByComparingTo("19");
        assertThat(AiFeeRules.costPart(BigDecimal.ZERO, RATE)).isEqualByComparingTo("0");
    }

    @Test
    void theTaxIsAddedRoundedDown() {
        assertThat(AiFeeRules.withTax(yen(28), TAX)).isEqualByComparingTo("30");
        assertThat(AiFeeRules.withTax(yen(9), TAX)).isEqualByComparingTo("9");
        assertThat(AiFeeRules.withTax(yen(0), TAX)).isEqualByComparingTo("0");
    }

    @Test
    void theReservationIsTheMostItMayCostWithItsTax() {
        // $2 at most: 2 x 1.5 x 150 = 450, with its 45 of tax.
        assertThat(AiFeeRules.reservation(new BigDecimal("2"), MARKUP, RATE, TAX)).isEqualByComparingTo("495");
        // Its tax is rounded up (#692 review M1): $0.123456 at most is a fee of 28, 2.8 of tax -> 3.
        assertThat(AiFeeRules.reservation(new BigDecimal("0.123456"), MARKUP, RATE, TAX)).isEqualByComparingTo("31");
    }

    @Test
    void theIncrementOfAJobIsWhatItAddsToItsGroupsTotalWithTax() {
        // Nothing before: 9 then 9 more. 9 has no tax (0.9 rounds down), 18 has 1.
        assertThat(AiFeeRules.increment(yen(0), yen(9), TAX)).isEqualByComparingTo("9");
        assertThat(AiFeeRules.increment(yen(9), yen(9), TAX)).isEqualByComparingTo("10");
        assertThat(AiFeeRules.increment(yen(5), yen(0), TAX)).isEqualByComparingTo("0");
    }

    @Test
    void theUnbilledTotalIsEachGroupsTotalWithItsTax() {
        // Two groups (operator, month): 18 -> 19, and 9 -> 9.
        assertThat(AiFeeRules.unbilledWithTax(List.of(yen(18), yen(9)), TAX)).isEqualByComparingTo("28");
        assertThat(AiFeeRules.unbilledWithTax(List.of(), TAX)).isEqualByComparingTo("0");
    }

    @Test
    void aJobWithinWhatItMayUseIsChargedItAll() {
        AiFeeRules.Settled settled = AiFeeRules.settle(yen(0), yen(28), yen(495), TAX);
        assertThat(settled.fee()).isEqualByComparingTo("28");
        assertThat(settled.absorbed()).isEqualByComparingTo("0");
        assertThat(settled.withTax()).isEqualByComparingTo("30");
    }

    @Test
    void aJobAboveWhatItMayUseIsChargedTheMostThatFitsAndThePlatformTakesTheRest() {
        // 28 would take 30 with its tax; only 25 may be used: 23 before tax takes 25 (23 + 2), 24 would take 26.
        AiFeeRules.Settled settled = AiFeeRules.settle(yen(0), yen(28), yen(25), TAX);
        assertThat(settled.fee()).isEqualByComparingTo("23");
        assertThat(settled.absorbed()).isEqualByComparingTo("5");
        assertThat(settled.withTax()).isEqualByComparingTo("25");
        // Nothing may be used: nothing is charged, all is taken on.
        AiFeeRules.Settled none = AiFeeRules.settle(yen(40), yen(28), yen(0), TAX);
        assertThat(none.fee()).isEqualByComparingTo("0");
        assertThat(none.absorbed()).isEqualByComparingTo("28");
    }

    /**
     * Review H2 (a): the jobs of a group, settled one after another with each one's increment, take exactly what the group
     * comes to when charged at once, whatever the order and the sizes (no rounding difference builds up).
     */
    @Property
    void theIncrementsOfAGroupAddUpToItsTotalWithTax(@ForAll @Size(max = 40) List<@IntRange(max = 500) Integer> fees,
        @ForAll @IntRange(max = 20) int taxPercent) {
        BigDecimal rate = BigDecimal.valueOf(taxPercent, 2);
        BigDecimal before = BigDecimal.ZERO;
        BigDecimal taken = BigDecimal.ZERO;
        for (int fee : fees) {
            taken = taken.add(AiFeeRules.increment(before, yen(fee), rate));
            before = before.add(yen(fee));
        }
        assertThat(taken).isEqualByComparingTo(AiFeeRules.withTax(before, rate));
    }

    /** The fee that fits is the largest whose increment is within the room, and never above the fee asked. */
    @Property
    void theFeeThatFitsIsTheLargestWithinTheRoom(@ForAll @IntRange(max = 1_000) int before,
        @ForAll @IntRange(max = 1_000) int fee, @ForAll @IntRange(max = 1_200) int room,
        @ForAll @IntRange(max = 20) int taxPercent) {
        BigDecimal rate = BigDecimal.valueOf(taxPercent, 2);
        AiFeeRules.Settled settled = AiFeeRules.settle(yen(before), yen(fee), yen(room), rate);
        assertThat(settled.fee().add(settled.absorbed())).isEqualByComparingTo(yen(fee));
        assertThat(settled.fee()).isBetween(BigDecimal.ZERO, yen(fee));
        assertThat(settled.withTax()).isEqualByComparingTo(AiFeeRules.increment(yen(before), settled.fee(), rate));
        assertThat(settled.withTax()).isLessThanOrEqualTo(yen(room).max(BigDecimal.ZERO));
        if (settled.fee().compareTo(yen(fee)) < 0) {
            assertThat(AiFeeRules.increment(yen(before), settled.fee().add(BigDecimal.ONE), rate))
                .isGreaterThan(yen(room));
        }
    }

    /**
     * #692 review M1: the reservation is a strict bound. Whatever the job actually used (at most the dollars reserved
     * for) and whatever its group held before it, what its end takes of the credit is never above its reservation.
     */
    @Property
    void whatAJobTakesIsNeverAboveItsReservation(@ForAll @IntRange(min = 1, max = 5_000_000) int mostMicros,
        @ForAll @IntRange(max = 1_000_000) int usedPerMillion, @ForAll @IntRange(max = 100_000) int before,
        @ForAll @IntRange(min = 100, max = 1_000) int markupHundredths,
        @ForAll @IntRange(min = 5_000, max = 50_000) int rateHundredths, @ForAll @IntRange(max = 20) int taxPercent) {
        BigDecimal most = BigDecimal.valueOf(mostMicros, 6);
        BigDecimal used = most.multiply(BigDecimal.valueOf(usedPerMillion, 6)).setScale(6, java.math.RoundingMode.DOWN);
        BigDecimal markup = BigDecimal.valueOf(markupHundredths, 2);
        BigDecimal rate = BigDecimal.valueOf(rateHundredths, 2);
        BigDecimal tax = BigDecimal.valueOf(taxPercent, 2);
        BigDecimal reservation = AiFeeRules.reservation(most, markup, rate, tax);
        BigDecimal taken = AiFeeRules.increment(yen(before), AiFeeRules.fee(used, markup, rate), tax);
        assertThat(taken).isLessThanOrEqualTo(reservation);
        // And settled against the reservation alone, the job is charged all of its fee.
        AiFeeRules.Settled settled = AiFeeRules.settle(yen(before), AiFeeRules.fee(used, markup, rate), reservation,
            tax);
        assertThat(settled.absorbed()).isEqualByComparingTo("0");
    }

    @Test
    void whatMayStillBeUsedIsTheRemainingLessTheUnbilledAndTheReservations() {
        assertThat(AiFeeRules.available(yen(1_000), yen(30), yen(495))).isEqualByComparingTo("475");
        // Never less than nothing.
        assertThat(AiFeeRules.available(yen(100), yen(30), yen(495))).isEqualByComparingTo("0");
    }

    // ---- the month's charges (F-M9a-2; #699 review M2, M4) ----------------------------------------------------------

    private static AiFeeRules.Job job(String id, String operator, String period, long fee, long costPart, long absorbed) {
        return new AiFeeRules.Job(id, operator, period, BigDecimal.valueOf(fee), BigDecimal.valueOf(costPart),
            BigDecimal.valueOf(absorbed));
    }

    private static final BigDecimal TEN = new BigDecimal("0.10");

    @Test
    void aChargeIsOneGroupOfOperatorAndMonthInTheOrderOfTheMonthsThenTheOperators() {
        List<AiFeeRules.Charge> charges = AiFeeRules.charges(List.of(
            job("a", "op-b", "2026-10", 101, 0, 0), job("b", null, "2026-10", 50, 0, 0),
            job("c", "op-a", "2026-09", 33, 0, 0), job("d", "op-b", "2026-10", 104, 0, 0)), TEN, BigDecimal.valueOf(10_000));
        assertThat(charges).extracting(c -> c.period() + "/" + c.operatorId() + "/" + c.amount().toPlainString() + "+"
            + c.tax().toPlainString())
            // The tax of each group's total, rounded down once (205 at 10%: 20, not 10 + 10).
            .containsExactly("2026-09/op-a/33+3", "2026-10/null/50+5", "2026-10/op-b/205+20");
        assertThat(charges.get(2).fees()).containsEntry("a", BigDecimal.valueOf(101)).containsEntry("d",
            BigDecimal.valueOf(104));
    }

    @Test
    void theCreditIsSpentOnTheEarliestGroupsFirstAndAGroupGivesWayFromItsLatestJob() {
        // 330 left: the September group's 110 + 11, then October's 300 (with tax 330) lowered to 190 (+19 = 209).
        List<AiFeeRules.Charge> charges = AiFeeRules.charges(List.of(
            job("sep", "op", "2026-09", 110, 0, 0), job("oct-1", "op", "2026-10", 150, 0, 0),
            job("oct-2", "op", "2026-10", 150, 0, 0)), TEN, BigDecimal.valueOf(330));
        assertThat(charges.get(0).amount()).isEqualByComparingTo("110");
        assertThat(charges.get(1).amount()).isEqualByComparingTo("190");
        assertThat(charges.get(1).total()).isEqualByComparingTo("209");
        // The latest job gives way first: oct-2 keeps 40, oct-1 all of its 150.
        assertThat(charges.get(1).fees()).containsEntry("oct-1", BigDecimal.valueOf(150))
            .containsEntry("oct-2", BigDecimal.valueOf(40));
        // Nothing left: a further group is charged nothing, all of it the platform's.
        assertThat(AiFeeRules.charges(List.of(job("x", "op", "2026-10", 80, 0, 0)), TEN, BigDecimal.ZERO))
            .singleElement().satisfies(c -> {
                assertThat(c.amount()).isZero();
                assertThat(c.tax()).isZero();
                assertThat(c.fees()).containsEntry("x", BigDecimal.ZERO);
            });
    }

    @Test
    void aJobWhoseFeeWasAllTakenOnAtItsEndAddsNothing() {
        List<AiFeeRules.Charge> charges = AiFeeRules.charges(List.of(job("gone", "op", "2026-10", 0, 40, 60),
            job("kept", "op", "2026-10", 100, 30, 0)), TEN, BigDecimal.valueOf(1_000));
        assertThat(charges).singleElement().satisfies(c -> {
            assertThat(c.amount()).isEqualByComparingTo("100");
            assertThat(c.shareExcluded()).isEqualByComparingTo("30");
            assertThat(c.fees()).containsEntry("gone", BigDecimal.ZERO);
        });
    }

    /**
     * #699 review M2: a job of which the platform took a part on at its end is charged that part short of its answered
     * usage; its cost part shrinks with it (fee / (fee + absorbed)) and never takes the markup of the group's others.
     */
    @Test
    void theCostPartKeptOutOfTheShareShrinksWithWhatThePlatformTookOn() {
        // Half of the first job's fee was taken on: its cost part counts half (120 * 100 / 200 = 60), the other's all.
        assertThat(AiFeeRules.charges(List.of(job("half", "op", "2026-10", 100, 120, 100),
            job("whole", "op", "2026-10", 100, 10, 0)), TEN, BigDecimal.valueOf(1_000)).getFirst().shareExcluded())
            .isEqualByComparingTo("70");
        // Never more than what is charged of the job itself (the rest is the other jobs' markup).
        assertThat(AiFeeRules.charges(List.of(job("cheap", "op", "2026-10", 10, 50, 0),
            job("dear", "op", "2026-10", 100, 0, 0)), TEN, BigDecimal.valueOf(1_000)).getFirst().shareExcluded())
            .isEqualByComparingTo("10");
        // Lowered by the credit too: what is charged of the job, against its whole fee (20 of 100 + 100 absorbed: 12).
        assertThat(AiFeeRules.charges(List.of(job("short", "op", "2026-10", 100, 120, 100)), TEN, BigDecimal.valueOf(22))
            .getFirst()).satisfies(c -> {
                assertThat(c.amount()).isEqualByComparingTo("20");
                assertThat(c.shareExcluded()).isEqualByComparingTo("12");
            });
    }

    @Test
    void theLargestAmountWithinIsWhatFitsWithItsTax() {
        assertThat(AiFeeRules.largestWithin(BigDecimal.valueOf(10_000), TEN, BigDecimal.valueOf(5_000)))
            .isEqualByComparingTo("4546");
        assertThat(AiFeeRules.largestWithin(BigDecimal.valueOf(100), TEN, BigDecimal.valueOf(5_000)))
            .isEqualByComparingTo("100");
        assertThat(AiFeeRules.largestWithin(BigDecimal.valueOf(100), TEN, BigDecimal.ZERO)).isZero();
        // 9 + 0 fits in 9; 10 + 1 does not.
        assertThat(AiFeeRules.largestWithin(BigDecimal.valueOf(100), TEN, BigDecimal.valueOf(10)))
            .isEqualByComparingTo("9");
    }

    /** Whatever the jobs and the credit: never more than it holds, no group negative, the cost part within the amount. */
    @Property
    void theChargesNeverTakeMoreThanTheCreditHolds(
        @ForAll @Size(max = 250) List<@IntRange(max = 3_000) Integer> fees,
        @ForAll @IntRange(max = 200_000) int remaining) {
        java.util.List<AiFeeRules.Job> jobs = new java.util.ArrayList<>();
        for (int i = 0; i < fees.size(); i++) {
            jobs.add(job("j" + i, i % 3 == 0 ? null : "op" + (i % 3), "2026-" + (10 + i % 2), fees.get(i),
                fees.get(i) / 2, i % 5 == 0 ? fees.get(i) / 3 : 0));
        }
        List<AiFeeRules.Charge> charges = AiFeeRules.charges(jobs, TEN, BigDecimal.valueOf(remaining));
        BigDecimal total = charges.stream().map(AiFeeRules.Charge::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isLessThanOrEqualTo(BigDecimal.valueOf(remaining));
        for (AiFeeRules.Charge c : charges) {
            assertThat(c.amount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(c.shareExcluded()).isBetween(BigDecimal.ZERO, c.amount());
            assertThat(c.fees().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(c.amount());
        }
        // When all fits, all is charged.
        BigDecimal wanted = charges.stream().map(c -> c.fees().values().stream().reduce(BigDecimal.ZERO,
            BigDecimal::add)).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (AiFeeRules.charges(jobs, TEN, BigDecimal.valueOf(Integer.MAX_VALUE)).stream()
            .map(AiFeeRules.Charge::total).reduce(BigDecimal.ZERO, BigDecimal::add)
            .compareTo(BigDecimal.valueOf(remaining)) <= 0) {
            assertThat(wanted).isEqualByComparingTo(BigDecimal.valueOf(fees.stream().mapToInt(Integer::intValue).sum()));
        }
    }

    // ---- what leaves the credit otherwise (F-M9a-2) -----------------------------------------------------------------

    private static AiFeeRules.Lot lot(boolean credit, String id, long amount, String expires) {
        return new AiFeeRules.Lot(credit, id, BigDecimal.valueOf(amount),
            expires == null ? null : java.time.Instant.parse(expires));
    }

    @Test
    void aReturnTakesTheLastCashFirstThenTheFeeCreditLatestToExpireFirst() {
        List<AiFeeRules.Lot> lots = List.of(lot(true, "g-early", 100, "2027-01-01T00:00:00Z"),
            lot(true, "g-late", 100, "2027-06-01T00:00:00Z"), lot(false, "c-1", 50, null), lot(false, "c-2", 50, null));
        assertThat(AiFeeRules.giveBack(lots, BigDecimal.valueOf(180))).extracting(l -> l.lotId() + ":" + l.amount())
            .containsExactly("c-2:50", "c-1:50", "g-late:80");
    }

    @Test
    void expiredFeeCreditIsVoidedButForWhatTheOpenJobsHoldAtTheFront() {
        java.time.Instant now = java.time.Instant.parse("2027-02-01T00:00:00Z");
        List<AiFeeRules.Lot> inOrder = List.of(lot(true, "g-expired", 100, "2027-01-01T00:00:00Z"),
            lot(true, "g-live", 100, "2027-06-01T00:00:00Z"), lot(false, "c-1", 50, null));
        // The jobs hold 30: taken from the expired lot first, the other 70 of it voided.
        assertThat(AiFeeRules.expired(inOrder, BigDecimal.valueOf(30), now)).extracting(l -> l.lotId() + ":" + l.amount())
            .containsExactly("g-expired:70");
        // They hold more than it: nothing of it is voided now.
        assertThat(AiFeeRules.expired(inOrder, BigDecimal.valueOf(150), now)).isEmpty();
        // Not expired yet: nothing.
        assertThat(AiFeeRules.expired(inOrder, BigDecimal.ZERO, java.time.Instant.parse("2026-12-01T00:00:00Z")))
            .isEmpty();
    }

    /** #699 re-review M1: a grant voided before it expired (its expiry still late) is as good as expired. */
    @Test
    void aVoidedGrantsFeeCreditIsNeverGivenBackAndIsVoidedLikeAnExpiredOnes() {
        java.time.Instant now = java.time.Instant.parse("2027-02-01T00:00:00Z");
        AiFeeRules.Lot voided = new AiFeeRules.Lot(true, "g-voided", BigDecimal.valueOf(100),
            java.time.Instant.parse("2027-12-01T00:00:00Z"), true);
        List<AiFeeRules.Lot> inOrder = List.of(lot(true, "g-live", 100, "2027-06-01T00:00:00Z"), voided,
            lot(false, "c-1", 50, null));
        assertThat(voided.expiredBy(now)).isTrue();
        assertThat(lot(true, "g-live", 1, "2027-06-01T00:00:00Z").expiredBy(now)).isFalse();
        assertThat(lot(false, "c", 1, null).expiredBy(now)).isFalse();
        // Not returnable: 250 held, 150 usable; a return of it all never reaches the voided lot.
        assertThat(AiFeeRules.usable(inOrder, BigDecimal.ZERO, now)).isEqualByComparingTo("150");
        assertThat(AiFeeRules.giveBack(inOrder, BigDecimal.valueOf(150))).extracting(l -> l.lotId() + ":" + l.amount())
            .containsExactly("c-1:50", "g-live:100");
        // Voided by the expiry, but for what the open jobs hold at the front of the order.
        assertThat(AiFeeRules.expired(inOrder, BigDecimal.ZERO, now)).extracting(l -> l.lotId() + ":" + l.amount())
            .containsExactly("g-voided:100");
        assertThat(AiFeeRules.expired(inOrder, BigDecimal.valueOf(130), now)).extracting(l -> l.lotId() + ":"
            + l.amount()).containsExactly("g-voided:70");
        assertThat(AiFeeRules.usable(inOrder, BigDecimal.valueOf(130), now)).isEqualByComparingTo("50");
    }

    /** #699 re-review L2: what a new request may reserve leaves out expired fee credit the open jobs do not hold. */
    @Test
    void whatMayBeUsedLeavesOutTheExpiredFeeCreditBeyondWhatTheJobsHold() {
        java.time.Instant now = java.time.Instant.parse("2027-02-01T00:00:00Z");
        List<AiFeeRules.Lot> inOrder = List.of(lot(true, "g-expired", 100, "2027-01-01T00:00:00Z"),
            lot(true, "g-live", 100, "2027-06-01T00:00:00Z"), lot(false, "c-1", 50, null));
        assertThat(AiFeeRules.usable(inOrder, BigDecimal.ZERO, now)).isEqualByComparingTo("150");
        assertThat(AiFeeRules.usable(inOrder, BigDecimal.valueOf(30), now)).isEqualByComparingTo("150");
        assertThat(AiFeeRules.usable(inOrder, BigDecimal.valueOf(120), now)).isEqualByComparingTo("130");
        assertThat(AiFeeRules.usable(inOrder, BigDecimal.valueOf(400), now)).isZero();
        assertThat(AiFeeRules.usable(inOrder, BigDecimal.ZERO, java.time.Instant.parse("2026-12-01T00:00:00Z")))
            .isEqualByComparingTo("250");
    }
}

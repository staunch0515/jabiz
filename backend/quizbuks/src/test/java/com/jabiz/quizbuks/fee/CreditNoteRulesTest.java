package com.jabiz.quizbuks.fee;

import com.jabiz.quizbuks.publishing.PublicationStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** F-M7 (F-M.md §10.3-1, -2, -4): the refund of unused days, a credit note's limits and tax, and where it goes. */
class CreditNoteRulesTest {

    static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
    static final BigDecimal TEN_PERCENT = new BigDecimal("0.1000");

    static BigDecimal yen(long value) {
        return BigDecimal.valueOf(value);
    }

    /** 100 a day for a week, least fee 300: the fee was 700. */
    static AudienceRefunds.Quote week() {
        return new AudienceRefunds.Quote(Map.of("gender", yen(100)), yen(300), START,
            START.plus(Duration.ofDays(7)));
    }

    @Test
    void anEarlyEndGivesBackTheUnusedDaysCountedAsTheFeeCountedThem() {
        // Two days and an hour: three days used, 300 owed, 400 back.
        AudienceRefunds.Decision decision = AudienceRefunds.decide(week(), yen(700),
            START.plus(Duration.ofDays(2)).plusSeconds(3600), PublicationStatus.SHARED_VIDEO, true);
        assertThat(decision.outcome()).isEqualTo(AudienceRefunds.REFUNDED);
        assertThat(decision.usedDays()).isEqualTo(3);
        assertThat(decision.amount()).isEqualByComparingTo("400");
    }

    @Test
    void theLeastFeeAOneDayRunAndTheEndDayGiveWhatTheyShould() {
        // One hour: one day, 100, but the least fee 300 is owed: 400 back.
        assertThat(AudienceRefunds.decide(week(), yen(700), START.plusSeconds(3600), PublicationStatus.TREE, true)
            .amount()).isEqualByComparingTo("400");
        // Six days and a minute: seven days, nothing back.
        AudienceRefunds.Decision late = AudienceRefunds.decide(week(), yen(700),
            START.plus(Duration.ofDays(6)).plusSeconds(60), PublicationStatus.QUOTA, true);
        assertThat(late.outcome()).isEqualTo(AudienceRefunds.NONE_ZERO);
        assertThat(late.amount()).isZero();
        // At its end: not early.
        assertThat(AudienceRefunds.decide(week(), yen(700), START.plus(Duration.ofDays(7)), PublicationStatus.QUOTA,
            true).outcome()).isEqualTo(AudienceRefunds.NONE_REASON);
    }

    @Test
    void aTakedownTheSwitchOffOrNoPeriodGiveNothingBack() {
        Instant early = START.plus(Duration.ofDays(1));
        assertThat(AudienceRefunds.decide(week(), yen(700), early, PublicationStatus.ADMIN, true).outcome())
            .isEqualTo(AudienceRefunds.NONE_REASON);
        assertThat(AudienceRefunds.decide(week(), yen(700), early, PublicationStatus.EXPIRED, true).outcome())
            .isEqualTo(AudienceRefunds.NONE_REASON);
        assertThat(AudienceRefunds.decide(week(), yen(700), early, PublicationStatus.TREE, false).outcome())
            .isEqualTo(AudienceRefunds.NONE_SWITCH_OFF);
        AudienceRefunds.Quote noPeriod = new AudienceRefunds.Quote(Map.of("gender", yen(100)), yen(0), null, null);
        assertThat(AudienceRefunds.decide(noPeriod, yen(700), early, PublicationStatus.TREE, true).outcome())
            .isEqualTo(AudienceRefunds.NONE_NO_PERIOD);
        assertThat(AudienceRefunds.REFUNDABLE_REASONS).doesNotContain(PublicationStatus.ADMIN,
            PublicationStatus.EXPIRED);
    }

    @Test
    void aFeesNotesNeverComeToMoreThanItsAmountNorTheirTaxesToMoreThanItsTax() {
        // A fee of 705 and 70 tax: a note of 333 → tax 33; then the rest (372) takes the rest of the tax (37).
        assertThat(CreditNoteRules.creditable(yen(705), yen(0))).isEqualByComparingTo("705");
        BigDecimal first = CreditNoteRules.tax(yen(333), yen(705), yen(70), TEN_PERCENT, yen(0), yen(0));
        assertThat(first).isEqualByComparingTo("33");
        assertThat(CreditNoteRules.creditable(yen(705), yen(333))).isEqualByComparingTo("372");
        assertThat(CreditNoteRules.tax(yen(372), yen(705), yen(70), TEN_PERCENT, yen(333), first))
            .isEqualByComparingTo("37");
        // A partial note never takes more tax than is left.
        assertThat(CreditNoteRules.tax(yen(100), yen(705), yen(70), TEN_PERCENT, yen(500), yen(65)))
            .isEqualByComparingTo("5");
        assertThat(CreditNoteRules.creditable(yen(705), yen(800))).isZero();
    }

    static CreditNoteAllocation.Part part(String sub, String lot, String prepayment, long amount, long minute,
        int order) {
        return new CreditNoteAllocation.Part(sub, lot, prepayment, yen(amount), START.plusSeconds(minute * 60), order);
    }

    @Test
    void theOwnInvoicesDebtFirstThenBackToWhereItWasPaidFromLastPaidFirst() {
        // Paid 300 from the prepayment (credit 200, cash 100), then 150 from the account (credit 50, cash 100).
        List<CreditNoteAllocation.Part> parts = List.of(
            part(QbFeeAccount.FEE_CREDIT, "g1", "p1", 200, 0, 0),
            part(QbFeeAccount.CASH, "c1", "p1", 100, 0, 1),
            part(QbFeeAccount.FEE_CREDIT, "g2", null, 50, 5, 0),
            part(QbFeeAccount.CASH, "c2", null, 100, 5, 1));
        // The invoice still owes 30: a note of 230 pays it, then gives back 200, the account's cash first.
        CreditNoteAllocation.Result result = CreditNoteAllocation.allocate(yen(230), true, yen(30), parts, List.of());
        assertThat(result.ownInvoice()).isEqualByComparingTo("30");
        assertThat(result.back()).extracting(CreditNoteAllocation.Back::lotId, b -> b.amount().intValue())
            .containsExactly(org.assertj.core.groups.Tuple.tuple("c2", 100),
                org.assertj.core.groups.Tuple.tuple("g2", 50),
                org.assertj.core.groups.Tuple.tuple("c1", 50));
        assertThat(result.back().get(2).prepaymentId()).isEqualTo("p1");
        assertThat(result.unused()).isZero();
    }

    @Test
    void whatWasGivenBackBeforeIsNotGivenAgainAndNeverMoreThanWasPaid() {
        List<CreditNoteAllocation.Part> parts = List.of(
            part(QbFeeAccount.CASH, "c1", null, 100, 0, 0),
            part(QbFeeAccount.CASH, "c1", null, -60, 10, 0));
        CreditNoteAllocation.Result result = CreditNoteAllocation.allocate(yen(80), true, yen(0), parts,
            List.of(new CreditNoteAllocation.Invoice("i9", "QBF-2026-00009", START, yen(10))));
        assertThat(result.backTotal()).isEqualByComparingTo("40");
        // The rest is credit: the other unpaid invoice first, then left for the next.
        assertThat(result.others()).singleElement().satisfies(a -> assertThat(a.amount()).isEqualByComparingTo("10"));
        assertThat(result.unused()).isEqualByComparingTo("30");
    }

    @Test
    void aPostpaidFeeIsNeverGivenCashAndItsCreditPaysTheOldestUnpaidInvoicesFirst() {
        // Paid early from cash (C2): no cash back all the same.
        List<CreditNoteAllocation.Part> parts = List.of(part(QbFeeAccount.CASH, "c1", null, 500, 0, 0));
        List<CreditNoteAllocation.Invoice> unpaid = List.of(
            new CreditNoteAllocation.Invoice("late", "QBF-2026-00020", START.plusSeconds(3600), yen(100)),
            new CreditNoteAllocation.Invoice("early", "QBF-2026-00010", START, yen(50)));
        CreditNoteAllocation.Result result = CreditNoteAllocation.allocate(yen(120), false, yen(0), parts, unpaid);
        assertThat(result.back()).isEmpty();
        assertThat(result.others()).extracting(CreditNoteAllocation.Applied::invoiceId).containsExactly("early",
            "late");
        assertThat(result.others().get(1).amount()).isEqualByComparingTo("70");
        assertThat(result.unused()).isZero();
        assertThat(result.appliedTotal()).isEqualByComparingTo("120");
    }
}

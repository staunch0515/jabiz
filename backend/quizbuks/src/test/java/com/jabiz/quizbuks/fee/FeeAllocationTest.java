package com.jabiz.quizbuks.fee;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** F-M1: the order the account that pays the platform's fees is spent in, in pure Java. */
class FeeAllocationTest {

    static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");

    static FeeAllocation.Credit credit(String id, long remaining, String grantedAt, String expiresAt) {
        return new FeeAllocation.Credit(id, BigDecimal.valueOf(remaining), Instant.parse(grantedAt),
            Instant.parse(expiresAt));
    }

    static FeeAllocation.Cash cash(String id, long remaining, String arrivedAt) {
        return new FeeAllocation.Cash(id, BigDecimal.valueOf(remaining), Instant.parse(arrivedAt));
    }

    static List<String> order(FeeAllocation.Result result) {
        return result.parts().stream().map(p -> p.lotId() + ":" + p.amount().toPlainString()).toList();
    }

    @Test
    void theCreditIsSpentFirstTheEarliestToExpireFirstThenTheCashByArrival() {
        FeeAllocation.Result result = FeeAllocation.allocate(
            List.of(credit("g-late", 300, "2026-10-01T00:00:00Z", "2027-01-30T00:00:00Z"),
                credit("g-soon", 200, "2026-10-05T00:00:00Z", "2026-12-01T00:00:00Z")),
            List.of(cash("c-new", 1000, "2026-11-01T00:00:00Z"), cash("c-old", 100, "2026-10-01T00:00:00Z")),
            BigDecimal.valueOf(5000), BigDecimal.valueOf(700), NOW);
        assertThat(order(result)).containsExactly("g-soon:200", "g-late:300", "c-old:100", "c-new:100");
        assertThat(result.paid()).isEqualByComparingTo("700");
        assertThat(result.credit()).isEqualByComparingTo("500");
        assertThat(result.cash()).isEqualByComparingTo("200");
        assertThat(result.parts()).extracting(FeeAllocation.Part::subAccount)
            .containsExactly(QbFeeAccount.FEE_CREDIT, QbFeeAccount.FEE_CREDIT, QbFeeAccount.CASH, QbFeeAccount.CASH);
    }

    @Test
    void onTheSameExpiryTheEarlierGrantThenTheIdGoesFirst() {
        String expiry = "2027-01-01T00:00:00Z";
        FeeAllocation.Result result = FeeAllocation.allocate(
            List.of(credit("b", 100, "2026-10-02T00:00:00Z", expiry), credit("c", 100, "2026-10-01T00:00:00Z", expiry),
                credit("a", 100, "2026-10-02T00:00:00Z", expiry)),
            List.of(), BigDecimal.ZERO, BigDecimal.valueOf(250), NOW);
        assertThat(order(result)).containsExactly("c:100", "a:100", "b:50");
    }

    @Test
    void anExpiredOrEmptyLotIsNotSpent() {
        FeeAllocation.Result result = FeeAllocation.allocate(
            List.of(credit("gone", 500, "2026-08-01T00:00:00Z", NOW.toString()),
                credit("empty", 0, "2026-10-01T00:00:00Z", "2027-01-01T00:00:00Z"),
                credit("ok", 50, "2026-10-01T00:00:00Z", NOW.plus(Duration.ofSeconds(1)).toString())),
            List.of(cash("zero", 0, "2026-09-01T00:00:00Z"), cash("c", 500, "2026-10-01T00:00:00Z")),
            BigDecimal.valueOf(500), BigDecimal.valueOf(100), NOW);
        assertThat(order(result)).containsExactly("ok:50", "c:50");
    }

    @Test
    void aFeeIsPaidInPartWhenTheAccountIsShortAndTheCashNeverBeyondWhatMayBeSpent() {
        // The cash wallet's available amount is 120: an open dispute holds the rest.
        FeeAllocation.Result result = FeeAllocation.allocate(
            List.of(credit("g", 100, "2026-10-01T00:00:00Z", "2027-01-01T00:00:00Z")),
            List.of(cash("c1", 100, "2026-10-01T00:00:00Z"), cash("c2", 400, "2026-10-02T00:00:00Z")),
            BigDecimal.valueOf(120), BigDecimal.valueOf(1000), NOW);
        assertThat(order(result)).containsExactly("g:100", "c1:100", "c2:20");
        assertThat(result.paid()).isEqualByComparingTo("220");
    }

    @Test
    void nothingIsPaidFromAnEmptyAccountOrForNothing() {
        assertThat(FeeAllocation.allocate(List.of(), List.of(), BigDecimal.ZERO, BigDecimal.valueOf(100), NOW).paid())
            .isEqualByComparingTo("0");
        assertThat(FeeAllocation.allocate(List.of(credit("g", 100, "2026-10-01T00:00:00Z", "2027-01-01T00:00:00Z")),
            List.of(), BigDecimal.ZERO, BigDecimal.ZERO, NOW).parts()).isEmpty();
        assertThat(FeeAllocation.allocate(List.of(), List.of(cash("c", 100, "2026-10-01T00:00:00Z")), null,
            BigDecimal.valueOf(50), NOW).paid()).isEqualByComparingTo("0");
    }

    @Test
    void cashTakenOutOtherwiseTakesTheNamedLotFirstThenByArrival() {
        List<FeeAllocation.Cash> lots = List.of(cash("old", 100, "2026-10-01T00:00:00Z"),
            cash("mine", 300, "2026-11-01T00:00:00Z"), cash("mid", 200, "2026-10-15T00:00:00Z"));
        assertThat(FeeAllocation.takeCash(lots, BigDecimal.valueOf(450), "mine"))
            .extracting(p -> p.lotId() + ":" + p.amount().toPlainString())
            .containsExactly("mine:300", "old:100", "mid:50");
        assertThat(FeeAllocation.takeCash(lots, BigDecimal.valueOf(150), null))
            .extracting(p -> p.lotId() + ":" + p.amount().toPlainString())
            .containsExactly("old:100", "mid:50");
    }

    @Test
    void cashTakenBeyondTheLotsTakesWhatTheyHave() {
        // The daily check CASH_LOTS reports the difference; a Kudos purchase or a refund is not refused for it.
        assertThat(FeeAllocation.takeCash(List.of(cash("a", 100, "2026-10-01T00:00:00Z")), BigDecimal.valueOf(250),
            null)).extracting(p -> p.lotId() + ":" + p.amount().toPlainString()).containsExactly("a:100");
    }
}

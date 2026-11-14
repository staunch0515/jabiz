package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.ReconChecks;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 c (review M2): the checks of the operators' settlements read the history from a window ({@code since}: the last
 * clean run's time less three months). What they find that involves rows older than the window is still found: a fee
 * counted by an old settlement and paid with credit only now, a posting of an old settlement reversed now, a fee no
 * settlement counted that is older than the window. The template is asked with the window a run would give; each test has a
 * year of its own.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperatorSettlementReconWindowIT extends CapItSupport {

    private List<Map<String, Object>> found(String operatorId, Instant since) {
        Map<String, Object> params = new HashMap<>();
        params.put("at", clock.instant().toString());
        params.put("zone", TOKYO.getId());
        params.put("waitingHours", 48);
        params.put("readinessDays", 5);
        if (since != null) {
            params.put("since", since.toString());
        }
        return items(OperatorSettlementReconIT.TEMPLATE, TestTokens.bearer(tokens, "fm12c-window-recon",
            QbPermissions.WALLET_SYSTEM), Map.of("params", params, "limit", 1000)).stream()
            .filter(row -> operatorId.equals(row.get("sponsorId"))).toList();
    }

    private static void assertRow(Map<String, Object> row, String check, String reference, String expected,
        String actual) {
        assertThat(row).containsEntry("checkCode", check).containsEntry("reference", reference);
        assertThat(new BigDecimal(String.valueOf(row.get("expected")))).isEqualByComparingTo(expected);
        assertThat(new BigDecimal(String.valueOf(row.get("actual")))).isEqualByComparingTo(actual);
    }

    /** The settlements of {@code from} to {@code to} (inclusive), each on the 2nd of the next month. */
    private void settleThrough(String operatorId, YearMonth from, YearMonth to) {
        for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
            settle(operatorId, month.toString(), month.plusMonths(1).atDay(2) + "T00:00:00Z");
        }
    }

    @Test
    @Order(1)
    void aFeeCountedByAnOldSettlementAndPaidWithCreditOnlyNowIsStillFound() {
        String[] who = start(2043);
        grant(who[1], 110, Instant.parse("2048-01-01T00:00:00Z"));
        clock.set(Instant.parse("2043-02-10T01:00:00Z"));
        String fee = String.valueOf(manualFee(who[1], 100));
        String zero = UUID.randomUUID().toString();
        OperatorSettlementReconIT.zeroFee(fee, zero);
        // Counted by February's settlement (issued in March 2043): a year before the window of the run below.
        settleThrough(who[0], YearMonth.of(2043, 2), YearMonth.of(2043, 12));
        // Paid with credit now (January 2044): a part no settlement can cut over the fee's total (none), so none writes its line.
        OperatorSettlementReconIT.paymentOn(OperatorSettlementReconIT.paymentOf(fee), UUID.randomUUID().toString(), zero,
            50, Timestamp.from(Instant.parse("2044-01-05T00:00:00Z")));
        settleThrough(who[0], YearMonth.of(2044, 1), YearMonth.of(2044, 2));
        clock.set(Instant.parse("2044-03-05T00:00:00Z"));
        // The window of a run after a clean one in December 2043 begins in September 2043: February's settlement is before it.
        assertThat(found(who[0], Instant.parse("2043-09-01T00:00:00Z"))).singleElement().satisfies(row -> assertRow(row,
            ReconChecks.OPERATOR_SETTLEMENT_COVERAGE, "gift:2044-01", "50", "0"));
        assertThat(found(who[0], null)).singleElement().satisfies(row -> assertRow(row,
            ReconChecks.OPERATOR_SETTLEMENT_COVERAGE, "gift:2044-01", "50", "0"));
    }

    @Test
    @Order(2)
    void aPostingOfAnOldSettlementReversedNowIsStillFound() {
        String[] who = start(2046);
        param(QbParams.operatorGiftTreatment("MANUAL"), "OPERATOR");
        grant(who[1], 1_100, Instant.parse("2050-01-01T00:00:00Z"));
        manualFee(who[1], 1_000);
        Map<String, Object> february = settle(who[0], "2046-02", "2046-03-02T00:00:00Z");
        assertThat(n(february, "gift_recovered")).isEqualByComparingTo("500");
        settleThrough(who[0], YearMonth.of(2046, 3), YearMonth.of(2046, 11));
        clock.set(Instant.parse("2046-12-10T00:00:00Z"));
        assertThat(found(who[0], Instant.parse("2046-09-01T00:00:00Z"))).isEmpty();
        // The take-back's posting is reversed (the reversal keeps the posting's source: the old settlement).
        String posting = String.valueOf(query("SELECT t.transaction_id FROM ledger_transaction_version t WHERE "
            + "t.source_entity = 'QbOperatorSettlement' AND t.source_id = ? AND t.reference LIKE 'opg\\_%'",
            String.valueOf(february.get("settlement_id"))).getFirst().get("transaction_id"));
        run("LEDGER_REVERSE", TestTokens.bearer(tokens, "ledger-test", "ledger.reverse"),
            body("transactionId", posting, "reason", "Reversed (test)"));
        assertThat(found(who[0], Instant.parse("2046-09-01T00:00:00Z"))).singleElement().satisfies(row -> assertRow(row,
            ReconChecks.GIFT_RECOVERY, "recovered", "500", "0"));
    }

    @Test
    @Order(3)
    void aFeeNoSettlementCountedStaysFoundWhateverItsAge() {
        String[] who = start(2049);
        String fee = fees(who[1], 1, 100, "2049-02-10T01:00:00Z").getFirst();
        settleThrough(who[0], YearMonth.of(2049, 2), YearMonth.of(2050, 2));
        clock.set(Instant.parse("2050-03-05T00:00:00Z"));
        assertThat(found(who[0], Instant.parse("2049-12-01T00:00:00Z"))).isEmpty();
        // A fee as of February 2049 that thirteen settlements did not count: older than the window, and found.
        OperatorSettlementCapIT.copyFee(fee, UUID.randomUUID().toString());
        assertThat(found(who[0], Instant.parse("2049-12-01T00:00:00Z"))).singleElement().satisfies(row -> assertRow(row,
            ReconChecks.OPERATOR_SETTLEMENT_COVERAGE, "fee:2049-02", "100", "0"));
    }

    @Test
    @Order(4)
    void aPartPaidBeforeTheWindowIsFoundWhenItsSecondSettlementIsIssuedInsideIt() {
        String[] who = start(2052);
        grant(who[1], 110, Instant.parse("2057-01-01T00:00:00Z"));
        clock.set(Instant.parse("2052-02-10T01:00:00Z"));
        String fee = String.valueOf(manualFee(who[1], 100));
        String zero = UUID.randomUUID().toString();
        OperatorSettlementReconIT.zeroFee(fee, zero);
        // Counted by February's settlement; the part (no settlement can write its line) is paid after it, in March.
        settleThrough(who[0], YearMonth.of(2052, 2), YearMonth.of(2052, 2));
        OperatorSettlementReconIT.paymentOn(OperatorSettlementReconIT.paymentOf(fee), UUID.randomUUID().toString(), zero,
            50, Timestamp.from(Instant.parse("2052-03-05T00:00:00Z")));
        // The two settlements that could have written it (March's and April's) are issued in April and May.
        settleThrough(who[0], YearMonth.of(2052, 3), YearMonth.of(2052, 4));
        clock.set(Instant.parse("2052-06-01T00:00:00Z"));
        // Anchor: a window that begins after both is of a later run, which found the part already (or had none to find).
        assertThat(found(who[0], Instant.parse("2052-06-01T00:00:00Z"))).isEmpty();
        // The next settlements are issued in June to August: the part was paid more than three months before the window
        // of a run that finds it now, and its later settlement is inside that window.
        settleThrough(who[0], YearMonth.of(2052, 5), YearMonth.of(2052, 7));
        clock.set(Instant.parse("2052-09-01T00:00:00Z"));
        Instant since = Instant.parse("2052-06-01T00:00:00Z");
        assertThat(found(who[0], since)).singleElement().satisfies(row -> assertRow(row,
            ReconChecks.OPERATOR_SETTLEMENT_COVERAGE, "gift:2052-03", "50", "0"));
    }

    @Test
    @Order(5)
    void anOperatorWithNothingIssuedInTheWindowIsComparedWhenAPostingOfItsSettlementIsMadeInsideIt() {
        String[] who = start(2054);
        param(QbParams.operatorGiftTreatment("MANUAL"), "OPERATOR");
        grant(who[1], 1_100, Instant.parse("2058-01-01T00:00:00Z"));
        manualFee(who[1], 1_000);
        Map<String, Object> february = settle(who[0], "2054-02", "2054-03-02T00:00:00Z");
        assertThat(n(february, "gift_recovered")).isEqualByComparingTo("500");
        // The operator stops (nothing is issued after February's), and its books differ from the settlement's column.
        execute("UPDATE qb_operator_settlement SET gift_recovered = 700 WHERE settlement_id = ?",
            String.valueOf(february.get("settlement_id")));
        clock.set(Instant.parse("2054-12-10T00:00:00Z"));
        Instant since = Instant.parse("2054-09-01T00:00:00Z");
        // No settlement and no posting inside the window: the operator is not compared (the run that made the difference did).
        assertThat(found(who[0], since).stream().filter(row -> ReconChecks.GIFT_RECOVERY.equals(row.get("checkCode"))))
            .isEmpty();
        // A posting for its settlement made inside the window (a reversal keeps the source): the comparison is made.
        String posting = String.valueOf(query("SELECT t.transaction_id FROM ledger_transaction_version t WHERE "
            + "t.source_entity = 'QbOperatorSettlement' AND t.source_id = ? AND t.reference LIKE 'opg\\_%'",
            String.valueOf(february.get("settlement_id"))).getFirst().get("transaction_id"));
        run("LEDGER_REVERSE", TestTokens.bearer(tokens, "ledger-test", "ledger.reverse"),
            body("transactionId", posting, "reason", "Reversed (test)"));
        assertThat(found(who[0], since).stream().filter(row -> ReconChecks.GIFT_RECOVERY.equals(row.get("checkCode"))))
            .singleElement().satisfies(row -> assertRow(row, ReconChecks.GIFT_RECOVERY, "recovered", "700", "0"));
    }
}

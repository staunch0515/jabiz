package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 d (docs/quizbuks/plans/impl/F-M12.md §6): finance's list of the settlements that wait,
 * {@value OperatorSettlements#WAITING}: each operator's earliest month without a settlement and the steps it still
 * waits for, read with the conditions of the readiness template; only for {@code qb.admin.finance.read}. The current
 * month is the server's, in the fiscal year's zone (#701 review M2); the template is not open to a browser.
 */
class OperatorSettlementWaitingIT extends CreditNoteItSupport {

    private static final String TEMPLATE = OperatorSettlements.WAITING_TEMPLATE;

    private String hq() {
        return TestTokens.bearer(tokens, "fm12d-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    private String financeToken() {
        return TestTokens.bearer(tokens, "fm12d-finance", QbPermissions.ADMIN_FINANCE_READ);
    }

    /** The process's rows for {@code operatorId}, at the test clock (whose month is the current one). */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> waiting(String operatorId) {
        Map<String, Object> output = run(OperatorSettlements.WAITING, financeToken(), Map.of());
        assertThat(output).containsEntry("current", OperatorSettlements.currentMonth(clock.instant(), TOKYO));
        return ((List<Map<String, Object>>) output.get("operators")).stream()
            .filter(row -> operatorId.equals(row.get("operatorId"))).toList();
    }

    @Test
    void onlyFinanceRunsItAndNobodyReadsTheTemplateFromABrowser() {
        for (String token : List.of(hq(), TestTokens.bearer(tokens, "fm12d-nobody", QbPermissions.OPERATOR_SETTLEMENT_SYSTEM))) {
            refused(OperatorSettlements.WAITING, token, Map.of(), 403);
        }
        // The template takes the month and the zone; a browser cannot give them, finance included (#701 review M2).
        for (String token : List.of(financeToken(), hq())) {
            client.post().uri("/api/queries/" + TEMPLATE).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("params", Map.of("current", "9999-12", "zone", "Nowhere/At_All")))
                .exchange().expectStatus().isForbidden();
        }
    }

    @Test
    void theTemplateLooksAtMostTenYearsBackWhateverMonthItIsGiven() {
        newOperator(hq(), unique("Far"));
        // With the system's permission (the process's), a month far ahead reads at most 120 months before it, quickly:
        // a wrong value cannot make the series long (#701 re-review L-3).
        List<Map<String, Object>> rows = items(TEMPLATE, TestTokens.bearer(tokens, "fm12d-system",
            QbPermissions.OPERATOR_SETTLEMENT_SYSTEM), Map.of("params", Map.of("current", "9999-12", "zone",
            TOKYO.getId()), "limit", 1000));
        assertThat(rows).isNotEmpty().allSatisfy(row -> assertThat(String.valueOf(row.get("month")))
            .isGreaterThanOrEqualTo("9989-12").isLessThan("9999-12"));
    }

    @Test
    void anOperatorWaitsForTheInvoiceOfItsSponsorsPostpaidFeeThenDropsOutOfTheList() {
        clock.set(Instant.parse("2041-03-10T01:00:00Z"));
        param(QbParams.FEE_MONTHLY, "1000");
        String operatorId = newOperator(hq(), unique("Waiting"));
        String sponsor = newSponsor(unique("fm12d-wait"));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", operatorId, "reason", "F-M12 d"));
        billing(sponsor, "POSTPAID", 20_000);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        String quiet = newOperator(hq(), unique("Quiet"));

        // Not the current month (March), nor later ones.
        assertThat(waiting(operatorId)).isEmpty();
        clock.set(Instant.parse("2041-04-01T03:00:00Z"));
        // March is the earliest month without a settlement, and the invoice is what it waits for; the quiet one, whose
        // March has nothing to wait for, is listed too (only the daily run is missing), with no step.
        assertThat(waiting(operatorId)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("month", "2041-03").containsEntry("steps", List.of("INVOICE"));
            assertThat(row.get("operatorName")).isNotNull();
        });
        assertThat(waiting(quiet)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("month", "2041-03").containsEntry("steps", List.of()));

        // Only the head of the line: with April over, March is still the one listed.
        clock.set(Instant.parse("2041-05-01T03:00:00Z"));
        assertThat(waiting(operatorId)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("month", "2041-03"));
        clock.set(Instant.parse("2041-04-01T03:00:00Z"));

        assertThat(((FeeProcesses.ItemOutput) systemRun(FeeProcesses.INVOICE_ITEM, body("sponsorId", sponsor,
            "period", "2041-03"))).outcome()).isEqualTo("ISSUED");
        assertThat(waiting(operatorId)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("steps", List.of()));

        clock.set(Instant.parse("2041-04-02T01:00:00Z"));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", "2041-03"));
        assertThat(waiting(operatorId)).isEmpty();
    }
}

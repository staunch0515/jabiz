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
 * {@code qb.admin.operator-settlements-waiting}: each operator's earliest month without a settlement and the steps it
 * still waits for, read with the conditions of the readiness template; only for {@code qb.admin.finance.read}.
 */
class OperatorSettlementWaitingIT extends CreditNoteItSupport {

    private static final String TEMPLATE = "qb.admin.operator-settlements-waiting";

    private String hq() {
        return TestTokens.bearer(tokens, "fm12d-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    private String financeToken() {
        return TestTokens.bearer(tokens, "fm12d-finance", QbPermissions.ADMIN_FINANCE_READ);
    }

    private List<Map<String, Object>> waiting(String operatorId, String current) {
        return items(TEMPLATE, financeToken(), Map.of("params", Map.of("current", current, "zone", TOKYO.getId()),
            "limit", 100)).stream().filter(row -> operatorId.equals(row.get("operatorId"))).toList();
    }

    @Test
    void onlyFinanceReadsIt() {
        for (String token : List.of(hq(), TestTokens.bearer(tokens, "fm12d-nobody", QbPermissions.OPERATOR_SETTLEMENT_SYSTEM))) {
            client.post().uri("/api/queries/" + TEMPLATE).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("params", Map.of("current", "2040-02", "zone", TOKYO.getId())))
                .exchange().expectStatus().isForbidden();
        }
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

        // March is the earliest month without a settlement, and the invoice is what it waits for; the quiet one, whose
        // March has nothing to wait for, is listed too (only the daily run is missing), with no step.
        assertThat(waiting(operatorId, "2041-04")).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("month", "2041-03").containsEntry("steps", "INVOICE");
            assertThat(row.get("operatorName")).isNotNull();
        });
        assertThat(waiting(quiet, "2041-04")).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("month", "2041-03").containsEntry("steps", ""));
        // Not the current month, nor later ones.
        assertThat(waiting(operatorId, "2041-03")).isEmpty();

        // Only the head of the line: with April over, March is still the one listed.
        assertThat(waiting(operatorId, "2041-05")).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("month", "2041-03"));

        assertThat(((FeeProcesses.ItemOutput) systemRun(FeeProcesses.INVOICE_ITEM, body("sponsorId", sponsor,
            "period", "2041-03"))).outcome()).isEqualTo("ISSUED");
        assertThat(waiting(operatorId, "2041-04")).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("steps", ""));

        clock.set(Instant.parse("2041-04-02T01:00:00Z"));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", "2041-03"));
        assertThat(waiting(operatorId, "2041-04")).isEmpty();
    }
}

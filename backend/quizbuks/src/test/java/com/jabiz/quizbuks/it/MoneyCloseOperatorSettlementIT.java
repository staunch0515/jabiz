package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.MoneyClose;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M15c review L-f: a credit note's credit left given back by the wind-up of an erased sponsor's account (a negative
 * FEE_CREDIT part aimed at the note, then the grant voided again) reaches the operator's settlement as F-M7's give-back
 * does ({@code OperatorSettlementMoreIT}'s M3): a negative GIFT line of the note's fee through the note; the voiding
 * again of the grant is no line (the wind-up's voiding is not a fee voided), and the settlement's checks find nothing.
 */
class MoneyCloseOperatorSettlementIT extends MoneyCloseItSupport {

    @Test
    void aNoteGivenBackByTheWindUpIsANegativeGiftLineOfItsFee() {
        param(QbParams.FEE_TAX_RATE, "0.1");
        param(QbParams.operatorGiftTreatment("MANUAL"), "OPERATOR");
        clock.set(Instant.parse("2027-03-10T01:00:00Z"));
        String hq = TestTokens.bearer(tokens, "fm15c-lf-hq", QbPermissions.OPERATORS_WRITE,
            QbPermissions.OPERATORS_READ);
        String operatorId = newOperator(hq, unique("Wind-up L-f"));
        String sponsor = newSponsor(unique("s-fm15c-lf"));
        run(OperatorProcesses.ASSIGN, hq, body("userId", sponsor, "operatorId", operatorId, "reason", "F-M15c"));
        billing(sponsor, "POSTPAID", 50_000);
        String grant = grant(sponsor, 2_000, Instant.parse("2029-01-01T00:00:00Z"));
        Object chargeId = manualFee(sponsor, 1_000);
        invoicePeriod(sponsor, clock.instant());
        erase(sponsor);
        deliverEvents();
        assertThat(close(sponsor).outcome()).isEqualTo(MoneyClose.CLOSED);

        // March: the fee, paid by the grant at the wind-up (1,100 with tax), counted with its credit used.
        Map<String, Object> march = settle(operatorId, "2027-03", "2027-04-02T00:00:00Z");
        assertThat(lines(march, "GIFT")).singleElement().satisfies(line ->
            assertThat(n(line, "gift_gross")).isEqualByComparingTo("1100"));

        // April: a note of 400 (440 with tax) on the fee; the wind-up gives its credit back to the grant and voids it.
        clock.set(Instant.parse("2027-04-10T01:00:00Z"));
        creditNote(chargeId, 400);
        Object noteId = notes(sponsor).getFirst().get("credit_note_id");
        assertThat(close(sponsor).outcome()).isEqualTo(MoneyClose.CLOSED);
        assertThat(query("SELECT amount, grant_id FROM qb_fee_payment_version WHERE target_entity = 'QbFeeCreditNote' "
            + "AND target_id = ? AND sub_account = 'FEE_CREDIT'", String.valueOf(noteId))).singleElement()
            .satisfies(part -> {
                assertThat(part).containsEntry("grant_id", grant);
                assertThat(n(part, "amount")).isEqualByComparingTo("-440");
            });
        Map<String, Object> april = settle(operatorId, "2027-04", "2027-05-02T00:00:00Z");

        // The part reaches the fee through the note: a negative GIFT line of the fee, as F-M7's give-back.
        assertThat(lines(april, "GIFT")).singleElement().satisfies(line -> {
            assertThat(line).containsEntry("charge_id", String.valueOf(chargeId));
            assertThat(n(line, "gift_gross")).isEqualByComparingTo("-440");
            assertThat(n(line, "recover_gross")).isEqualByComparingTo("-440");
        });
        // The note's own deduction: the half the operator was given of its 400.
        assertThat(n(april, "credit_note_deduction")).isEqualByComparingTo("200");
        // The wind-up's voiding (of the rest, then of what came back) is not a fee voided: no VOID line.
        assertThat(lines(march, "VOID")).isEmpty();
        assertThat(lines(april, "VOID")).isEmpty();
        // The credit taken back in March (500 of 1,100, 600 carried) less what came back (440 against the carried
        // first): taken back over both months = 1,100 − 440 less what is still carried.
        BigDecimal recovered = n(march, "gift_recovered").add(n(april, "gift_recovered"));
        assertThat(recovered.add(n(april, "gift_carried_out"))).isEqualByComparingTo("660");
        assertThat(settlementChecks(operatorId)).isEmpty();
        assertBooksBalance();
        assertThat(differences(sponsor)).isEmpty();
    }

    private Map<String, Object> settle(String operatorId, String month, String at) {
        clock.set(Instant.parse(at));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", month));
        return query("SELECT * FROM qb_operator_settlement WHERE operator_id = ? AND month = ?", operatorId, month)
            .getFirst();
    }

    private static List<Map<String, Object>> lines(Map<String, Object> settlement, String kind) {
        return query("SELECT * FROM qb_operator_settlement_line_version WHERE settlement_id = ? AND line_kind = ? "
            + "ORDER BY occurred_at, source_id", String.valueOf(settlement.get("settlement_id")), kind);
    }

    /** The operator's rows of the settlement checks ({@code qb.system.recon-operator-settlement}). */
    private List<Map<String, Object>> settlementChecks(String operatorId) {
        Map<String, Object> params = new HashMap<>();
        params.put("at", clock.instant().toString());
        params.put("zone", "Asia/Tokyo");
        return items("qb.system.recon-operator-settlement", TestTokens.bearer(tokens, "fm15c-lf-recon",
            QbPermissions.WALLET_SYSTEM), Map.of("params", params, "limit", 1000)).stream()
            .filter(row -> row.values().stream().anyMatch(operatorId::equals))
            .toList();
    }

    private static BigDecimal n(Map<String, Object> row, String column) {
        return new BigDecimal(String.valueOf(row.get(column)));
    }
}

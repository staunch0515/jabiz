package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.wallet.ReconChecks;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 (#701 review L3): a fee with no operator is asked about from the earliest switch point of the operators, by time
 * alone: not by the operator that looks after its sponsor now, which changes when the sponsor moves (the difference
 * would go away with nothing put right), and which a sponsor may not have, or whose operator may have no settlement of the
 * new kind yet.
 */
class OperatorSettlementReconSwitchPointIT extends CapItSupport {

    private List<Map<String, Object>> noOperator(String sponsorId) {
        Map<String, Object> params = new HashMap<>();
        params.put("at", clock.instant().toString());
        params.put("zone", TOKYO.getId());
        params.put("waitingHours", 48);
        params.put("readinessDays", 5);
        return items(OperatorSettlementReconIT.TEMPLATE, TestTokens.bearer(tokens, "fm12c-switch-recon",
            QbPermissions.WALLET_SYSTEM), Map.of("params", params, "limit", 1000)).stream()
            .filter(row -> sponsorId.equals(row.get("sponsorId"))
                && ReconChecks.OPERATOR_CHARGE_NO_OPERATOR.equals(row.get("checkCode"))).toList();
    }

    /** A settlement of the old kind of {@code month}, ending at {@code end}: the operator's switch point is that moment. */
    private static void oldSettlement(String operatorId, String month, String end) {
        execute("INSERT INTO qb_operator_settlement (settlement_id, operator_id, month, period_start, period_end, "
            + "revenue, platform_share, operator_share, prospecting_cost, carried_in, payable, carried_out, status, "
            + "issued_at, revision, version) VALUES (gen_random_uuid()::text, ?, ?, ?::timestamptz - interval '1 month', "
            + "?::timestamptz, 0, 0.5, 0, 0, 0, 0, 0, 'CREDITED', now(), 1, 0)", operatorId, month, end, end);
    }

    /** An operator whose switch point is the end of {@code month} (Tokyo), with its first settlement of the new kind. */
    private void switched(String operatorId, String month, String end, String next, String settledAt) {
        oldSettlement(operatorId, month, end);
        settle(operatorId, next, settledAt);
    }

    @Test
    void aFeeWithNoOperatorIsAskedAboutFromTheEarliestSwitchPointAndStaysWhenItsSponsorMoves() {
        String[] early = start(2053);
        String[] late = another(2053);
        clock.set(Instant.parse("2053-04-10T01:00:00Z"));
        OperatorSettlementReconIT.looseFee(String.valueOf(manualFee(early[1], 100)), UUID.randomUUID().toString());
        OperatorSettlementReconIT.looseFee(String.valueOf(manualFee(late[1], 100)), UUID.randomUUID().toString());
        // The switch points: the end of February for one operator (Tokyo), the end of April for the other.
        switched(early[0], "2053-02", "2053-02-28T15:00:00Z", "2053-03", "2053-05-02T00:00:00Z");
        switched(late[0], "2053-04", "2053-04-30T15:00:00Z", "2053-05", "2053-06-02T00:00:00Z");
        clock.set(Instant.parse("2053-06-05T00:00:00Z"));
        // Charged on 10 April, after the earliest switch point: both are asked about, whoever looks after the sponsor.
        for (String sponsor : List.of(early[1], late[1])) {
            assertThat(noOperator(sponsor)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("reference", "2053-04").extracting(r -> new java.math.BigDecimal(String.valueOf(r.get("actual"))))
                .satisfies(actual -> assertThat(actual).isEqualByComparingTo("100")));
        }
        // The sponsor moves: nothing was put right, and the difference stays.
        run(com.jabiz.quizbuks.operator.OperatorProcesses.ASSIGN, hq(), body("userId", early[1], "operatorId", late[0],
            "reason", "F-M12 L3"));
        assertThat(noOperator(early[1])).hasSize(1);
    }

    @Test
    void aSponsorWithNoOperatorAndOneWhoseOperatorHasNoNewSettlementAreAskedAboutToo() {
        String[] switchedOne = start(2054);
        String[] notYet = another(2054);
        String alone = newSponsor(unique("fm12-l3-alone"));
        cash(alone, 1_000);
        clock.set(Instant.parse("2054-04-10T01:00:00Z"));
        OperatorSettlementReconIT.looseFee(String.valueOf(manualFee(notYet[1], 100)), UUID.randomUUID().toString());
        OperatorSettlementReconIT.looseFee(String.valueOf(manualFee(alone, 100)), UUID.randomUUID().toString());
        // Looked after by no operator (none assigned, or its assignment gone).
        execute("DELETE FROM qb_sponsor_operator WHERE user_id = ?", alone);
        switched(switchedOne[0], "2054-02", "2054-02-28T15:00:00Z", "2054-03", "2054-05-02T00:00:00Z");
        clock.set(Instant.parse("2054-05-05T00:00:00Z"));
        // notYet's operator has no settlement of the new kind (no switch point of its own).
        assertThat(noOperator(notYet[1])).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("reference", "2054-04"));
        assertThat(noOperator(alone)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("reference", "2054-04"));
    }
}

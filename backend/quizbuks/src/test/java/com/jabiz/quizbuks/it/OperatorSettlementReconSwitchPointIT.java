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
 * F-M12 c (review L2): a fee with no operator is asked about from the switch point of the operator that looks after its
 * sponsor, not from the earliest of all the operators'. Two operators with switch points a few months apart; a fee charged
 * between them is the old rule's for the later one's sponsor and not for the earlier one's.
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

    @Test
    void aFeeWithNoOperatorIsAskedAboutFromTheSwitchPointOfTheOperatorOfItsSponsor() {
        String[] early = start(2053);
        String[] late = another(2053);
        clock.set(Instant.parse("2053-04-10T01:00:00Z"));
        String earlyFee = String.valueOf(manualFee(early[1], 100));
        OperatorSettlementReconIT.looseFee(earlyFee, UUID.randomUUID().toString());
        String lateFee = String.valueOf(manualFee(late[1], 100));
        OperatorSettlementReconIT.looseFee(lateFee, UUID.randomUUID().toString());
        // The switch points: the end of February for one operator (Tokyo), the end of April for the other.
        oldSettlement(early[0], "2053-02", "2053-02-28T15:00:00Z");
        oldSettlement(late[0], "2053-04", "2053-04-30T15:00:00Z");
        settle(early[0], "2053-03", "2053-05-02T00:00:00Z");
        settle(late[0], "2053-05", "2053-06-02T00:00:00Z");
        clock.set(Instant.parse("2053-06-05T00:00:00Z"));
        // Charged on 10 April: after the first switch point, before the second.
        assertThat(noOperator(early[1])).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("reference", "2053-04").extracting(r -> new java.math.BigDecimal(String.valueOf(r.get("actual"))))
            .satisfies(actual -> assertThat(actual).isEqualByComparingTo("100")));
        assertThat(noOperator(late[1])).isEmpty();
    }
}

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
 * F-M12 c (review M1): the fees with no operator are asked about from the earliest switch point of the operators on; with
 * no settlement of the new kind there is none and nothing is asked, and when the only operators never had a settlement of
 * the old kind (their switch point is the epoch) the epoch is the point. Alone in its schema: any other settlement of the
 * new kind would give a switch point.
 */
class OperatorSettlementReconNoSettlementIT extends CapItSupport {

    private List<Map<String, Object>> itemsOf(String sponsorId) {
        Map<String, Object> params = new HashMap<>();
        params.put("at", clock.instant().toString());
        params.put("zone", TOKYO.getId());
        params.put("waitingHours", 48);
        params.put("readinessDays", 5);
        return items(OperatorSettlementReconIT.TEMPLATE, TestTokens.bearer(tokens, "fm12c-nosettlement-recon",
            QbPermissions.WALLET_SYSTEM), Map.of("params", params, "limit", 1000)).stream()
            .filter(row -> sponsorId.equals(row.get("sponsorId"))).toList();
    }

    @Test
    void aFeeWithNoOperatorIsAskedAboutOnlyOnceThereIsASwitchPointAndTheEpochIsOne() {
        String[] who = start(2048);
        String fee = String.valueOf(manualFee(who[1], 100));
        OperatorSettlementReconIT.looseFee(fee, UUID.randomUUID().toString());
        clock.set(Instant.parse("2048-03-05T00:00:00Z"));
        // No settlement of the new kind: no switch point, nothing is asked.
        assertThat(itemsOf(who[1])).isEmpty();
        // The operator never had one of the old kind: its switch point is the epoch, the only one there is.
        settle(who[0], "2048-02", "2048-03-02T00:00:00Z");
        assertThat(itemsOf(who[1])).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("checkCode", ReconChecks.OPERATOR_CHARGE_NO_OPERATOR).containsEntry("reference", "2048-02"));
    }
}

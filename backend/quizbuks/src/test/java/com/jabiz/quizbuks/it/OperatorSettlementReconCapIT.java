package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.wallet.ReconChecks;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 c (docs/quizbuks/plans/impl/F-M12.md §3.9, §6): the fees a capped settlement left, after its watermark, are not
 * missed while the cap holds; a fee from before the watermark that no settlement counted still is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "quizbuks.operator-settlement.max-lines=4")
class OperatorSettlementReconCapIT extends CapItSupport {

    private List<Map<String, Object>> found(String operatorId) {
        Map<String, Object> params = new HashMap<>();
        params.put("at", clock.instant().toString());
        params.put("zone", TOKYO.getId());
        params.put("waitingHours", 48);
        return items(OperatorSettlementReconIT.TEMPLATE, TestTokens.bearer(tokens, "fm12c-cap-recon",
            QbPermissions.WALLET_SYSTEM), Map.of("params", params, "limit", 1000)).stream()
            .filter(row -> operatorId.equals(row.get("sponsorId"))).toList();
    }

    @Test
    void feesLeftByCappedSettlementsAreNotMissedButAFeeBeforeTheWatermarkIs() {
        String[] who = start(2032);
        // Nine fees, a minute apart, four a settlement: February takes the first four, March the next four, the ninth
        // is left by both (after both watermarks).
        List<String> ids = fees(who[1], 9, 100, "2032-02-10T01:00:00Z");
        Map<String, Object> february = settle(who[0], "2032-02", "2032-03-02T00:00:00Z");
        assertThat(february).containsEntry("capped", true).containsEntry("uncounted", 5);
        Map<String, Object> march = settle(who[0], "2032-03", "2032-04-02T00:00:00Z");
        assertThat(march).containsEntry("capped", true).containsEntry("uncounted", 1);
        assertThat(lineCharges(march.get("settlement_id"), "CHARGE")).containsExactlyElementsOf(ids.subList(4, 8));
        // Two settlements have not counted the ninth, but each was capped and it is after each watermark.
        assertThat(found(who[0])).isEmpty();

        // A fee charged as the first one was (before the watermarks), found by no settlement: not left by the cap.
        String missed = UUID.randomUUID().toString();
        OperatorSettlementCapIT.copyFee(ids.getFirst(), missed);
        assertThat(found(who[0])).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("checkCode", ReconChecks.OPERATOR_SETTLEMENT_COVERAGE)
                .containsEntry("reference", "fee:2032-02");
            assertThat(new BigDecimal(String.valueOf(row.get("expected")))).isEqualByComparingTo("100");
            assertThat(new BigDecimal(String.valueOf(row.get("actual")))).isEqualByComparingTo("0");
        });

        // April counts the ninth and takes the missed one (the first of the oldest, it is the older of the two).
        Map<String, Object> april = settle(who[0], "2032-04", "2032-05-02T00:00:00Z");
        assertThat(lineCharges(april.get("settlement_id"), "CHARGE")).containsExactlyInAnyOrder(ids.get(8), missed);
        assertThat(found(who[0])).isEmpty();
    }
}

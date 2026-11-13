package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.ExposurePrices;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.wallet.ReconChecks;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 c with F-M8c: the daily check names EXPOSURE as the step a month waits for (the exposure fee's settlement of a
 * publication of the operator that bought exposures), and counts from the readiness deadline while it waits.
 */
class OperatorSettlementReconExposureIT extends ExposureFeeItSupport {

    private String hq() {
        return TestTokens.bearer(tokens, "fm12c-exp-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    private List<Map<String, Object>> found(String operatorId) {
        return items(OperatorSettlementReconIT.TEMPLATE, TestTokens.bearer(tokens, "fm12c-exp-recon",
            QbPermissions.WALLET_SYSTEM), Map.of("params", Map.of("at", clock.instant().toString(), "zone", "Asia/Tokyo",
            "waitingHours", 48, "readinessDays", 5), "limit", 1000)).stream()
            .filter(row -> operatorId.equals(row.get("sponsorId"))).toList();
    }

    @Test
    void aMonthWaitingForTheExposureSettlementIsReportedPastTheDeadlineAndNamesTheStep() {
        autoApprove(true);
        clock.set(Instant.parse("2038-02-01T01:00:00Z"));
        String operatorId = newOperator(hq(), unique("Exposure waiting"));
        run(ExposurePrices.SET, TestTokens.bearer(tokens, "fm12c-exp-finance", QbPermissions.FEE_MANAGE),
            body("operatorId", operatorId, "unit", "1", "reason", "F-M12 c"));
        String sponsor = newSponsor(unique("fm12c-exp"));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", operatorId, "reason", "F-M12 c"));
        grant(sponsor, 10_000, clock.instant().plus(Duration.ofDays(900)));
        String video = approvedVideo(reviewedSponsor("fm12c-exp-provider"));
        String publicationId = draft(sponsor, "exposure waiting", video, 200L, clock.instant());
        submit(sponsorToken(sponsor), publicationId);

        // February ended at Mar 1 in Tokyo (Feb 28, 15:00 UTC); the settlement may wait for the exposure fee until Mar 5, 15:00
        // UTC, and the check reports 48 hours after that: not at Mar 7, 14:00, but at 16:00.
        clock.set(Instant.parse("2038-03-07T14:00:00Z"));
        assertThat(found(operatorId)).isEmpty();
        clock.set(Instant.parse("2038-03-07T16:00:00Z"));
        assertThat(found(operatorId)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("checkCode", ReconChecks.OPERATOR_SETTLEMENT_WAITING)
                .containsEntry("reference", "2038-02:EXPOSURE");
        });
    }
}

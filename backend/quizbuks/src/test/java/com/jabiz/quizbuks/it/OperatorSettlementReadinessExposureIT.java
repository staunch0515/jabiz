package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.ExposurePrices;
import com.jabiz.quizbuks.fee.ExposureSettlements;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 b with F-M8c (plan F-M8 §7.1): an operator whose sponsor's publication (frozen on it) bought exposures and is due
 * an exposure fee for the month is not ready until the month's exposure settlement or the publication's final one exists;
 * past the deadline it settles anyway.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperatorSettlementReadinessExposureIT extends ExposureFeeItSupport {

    private String hq() {
        return TestTokens.bearer(tokens, "fm12b-exp-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    /** {operator, sponsor, publication}: a publication that bought 200 exposures of another sponsor's video, on in February. */
    private String[] exposurePublication(int year, String tag) {
        autoApprove(true);
        clock.set(Instant.parse(year + "-02-01T01:00:00Z"));
        String operatorId = newOperator(hq(), unique("Exposure " + tag));
        run(ExposurePrices.SET, TestTokens.bearer(tokens, "fm12b-exp-finance", QbPermissions.FEE_MANAGE),
            body("operatorId", operatorId, "unit", "1", "reason", "F-M12 b"));
        String sponsor = newSponsor(unique("fm12b-exp-" + tag));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", operatorId, "reason", "F-M12 b"));
        grant(sponsor, 10_000, clock.instant().plus(Duration.ofDays(900)));
        String sharedVideoId = approvedVideo(reviewedSponsor("fm12b-exp-provider-" + tag));
        String publicationId = draft(sponsor, "exposure " + tag, sharedVideoId, 200L, clock.instant());
        submit(sponsorToken(sponsor), publicationId);
        return new String[] {operatorId, sponsor, publicationId};
    }

    private OperatorSettlements.SettlementOutput run(String operatorId, int year, String month, String at) {
        clock.set(Instant.parse(at));
        return (OperatorSettlements.SettlementOutput) systemRun(OperatorSettlements.RUN, body("operatorId", operatorId,
            "month", year + "-" + month));
    }

    private static boolean settled(String operatorId, String month) {
        return !query("SELECT 1 FROM qb_operator_settlement WHERE operator_id = ? AND month = ?", operatorId, month)
            .isEmpty();
    }

    @Test
    @Order(1)
    void anOperatorWaitsForTheMonthsExposureSettlementAndSettlesOnceItIsThere() {
        String[] who = exposurePublication(2035, "wait");
        assertThat(run(who[0], 2035, "02", "2035-03-02T00:00:00Z").blockedBy()).contains("EXPOSURE");
        assertThat(settled(who[0], "2035-02")).isFalse();
        // The month's exposure fee settled (no exposure counted, so a row without a charge): the operator is ready.
        systemRun(ExposureSettlements.SETTLE, body("publicationId", who[2], "sponsorId", who[1], "period", "2035-02",
            "last", false));
        clock.set(Instant.parse("2035-03-03T00:00:00Z"));
        assertThat(run(who[0], 2035, "02", "2035-03-03T00:00:00Z").blockedBy()).isEmpty();
        assertThat(settled(who[0], "2035-02")).isTrue();
    }

    @Test
    @Order(2)
    void aFinalExposureSettlementOfAnEndedPublicationCountsAsSettled() {
        String[] who = exposurePublication(2036, "final");
        run("QB_PUBLICATION_ADMIN_END", TestTokens.bearer(tokens, "fm12b-exp-admin", QbPermissions.ADMIN_CONTENT_WRITE),
            body("publicationId", who[2], "reason", "F-M12 b test"));
        assertThat(run(who[0], 2036, "02", "2036-03-02T00:00:00Z").blockedBy()).contains("EXPOSURE");
        systemRun(ExposureSettlements.SETTLE, body("publicationId", who[2], "sponsorId", who[1], "last", true));
        assertThat(query("SELECT 1 FROM qb_exposure_settlement_version WHERE publication_id = ? AND final", who[2]))
            .isNotEmpty();
        assertThat(run(who[0], 2036, "02", "2036-03-03T00:00:00Z").blockedBy()).isEmpty();
        assertThat(settled(who[0], "2036-02")).isTrue();
    }

    @Test
    @Order(3)
    void pastTheDeadlineItSettlesAnywayWithoutTheExposureSettlement() {
        String[] who = exposurePublication(2037, "late");
        // The default 5 days: the 5th is still inside, the 7th is past.
        assertThat(run(who[0], 2037, "02", "2037-03-05T00:00:00Z").blockedBy()).contains("EXPOSURE");
        assertThat(settled(who[0], "2037-02")).isFalse();
        assertThat(run(who[0], 2037, "02", "2037-03-07T00:00:00Z").blockedBy()).isEmpty();
        assertThat(settled(who[0], "2037-02")).isTrue();
    }
}

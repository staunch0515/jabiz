package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.fee.ExposureSettlements;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M8c (the controller's request, F-M12's plan §3.2): the exposure fee enters the operator's share base like the
 * other kinds (it is in the default {@code qb.operator.share.kinds}), under the operator frozen at the submission: its
 * charge is a line of the operator's settlement of the month, none of it excluded.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExposureOperatorShareIT extends ExposureFeeItSupport {

    static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    @Test
    void anExposureFeeIsAFeeOfTheSettlementOfTheOperatorItsPublicationWasSubmittedUnder() {
        autoApprove(true);
        String provider = reviewedSponsor("fm8-share-provider");
        String sharedVideoId = approvedVideo(provider);
        String sponsor = reviewedSponsor("fm8-share");
        grant(sponsor, 10_000, clock.instant().plus(Duration.ofDays(400)));
        String operatorId = String.valueOf(query("SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?",
            sponsor).getFirst().get("operator_id"));
        String publicationId = draft(sponsor, "fm8-share", sharedVideoId, 200L, clock.instant());
        submit(sponsorToken(sponsor), publicationId);
        YearMonth month = YearMonth.parse(com.jabiz.quizbuks.fee.FeeRules.period(clock.instant(), ZONE));
        for (int i = 0; i < 150; i++) {
            execute("INSERT INTO qb_exposure (exposure_id, publication_id, user_id, publisher_id, provider_id, "
                + "counted_at, surface, counted_sec, timer, capped_days, created_at, version) VALUES (?, ?, ?, ?, ?, "
                + "?, 'START', 10, 'SCREEN', 0, ?, 1)", UUID.randomUUID().toString(), publicationId,
                "fm8-" + UUID.randomUUID(), sponsor, provider, Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()));
        }
        // The month is settled (150 exposures, 150 yen), then the operator's month.
        clock.set(month.plusMonths(1).atDay(2).atTime(0, 47).atZone(ZONE).toInstant());
        systemRun(ExposureSettlements.SETTLE, body("publicationId", publicationId, "sponsorId", sponsor, "period",
            month.toString(), "last", false));
        Map<String, Object> charge = query("SELECT * FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = "
            + "'EXPOSURE'", sponsor).getFirst();
        assertThat(charge).containsEntry("operator_id", operatorId);
        clock.set(month.plusMonths(1).atDay(3).atTime(9, 0).atZone(ZONE).toInstant());
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", month.toString()));

        Map<String, Object> settlement = query("SELECT * FROM qb_operator_settlement WHERE operator_id = ? AND month = ?",
            operatorId, month.toString()).getFirst();
        // The base: the fee before tax; none of it is the platform's cost.
        assertThat(dec(settlement.get("platform_fees"))).isEqualByComparingTo("150");
        assertThat(dec(settlement.get("cost_excluded"))).isEqualByComparingTo("0");
        List<Map<String, Object>> lines = query("SELECT * FROM qb_operator_settlement_line_version WHERE "
            + "settlement_id = ? AND line_kind = 'CHARGE'", String.valueOf(settlement.get("settlement_id")));
        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line).containsEntry("charge_id", String.valueOf(charge.get("charge_id")));
            assertThat(dec(line.get("excluded"))).isEqualByComparingTo("0");
        });
    }
}

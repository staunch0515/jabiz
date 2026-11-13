package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.fee.ExposureSettlements;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M8 (docs/quizbuks/plans/impl/F-M8.md §3.5): the exposure fee's settlement. The exposures counted are billed at the
 * price frozen at the submission, no more than were bought, cumulatively (the amount and the tax rounded up and down
 * on the total so far), from the deposit; the final settlement after the end charges exactly the deposit of what was
 * bought in full and gives back the rest of an unused one.
 */
class ExposureSettleIT extends ExposureFeeItSupport {

    static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    @Autowired
    JobDefinition<ExposureSettlements.SettleRef> qbExposureMonthlyJob;
    @Autowired
    JobDefinition<ExposureSettlements.SettleRef> qbExposureFinalJob;
    @Autowired
    JobDefinition<com.jabiz.quizbuks.exposure.ExposureLowNotices.LowRef> qbExposureLowJob;

    void lowRun() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.run(qbExposureLowJob, clock.instant().plusSeconds(lowRuns++));
    }

    private int lowRuns;

    /** A live week-long publication of a new Sponsor using another's video, {@code purchased} exposures bought. */
    String[] boughtPublication(String name, long purchased) {
        autoApprove(true);
        String provider = reviewedSponsor(name + "-provider");
        String sharedVideoId = approvedVideo(provider);
        String sponsor = reviewedSponsor(name);
        grant(sponsor, 100_000, clock.instant().plus(Duration.ofDays(400)));
        String publicationId = draft(sponsor, name, sharedVideoId, purchased, clock.instant());
        submit(sponsorToken(sponsor), publicationId);
        return new String[] {sponsor, publicationId, provider};
    }

    void successes(String publicationId, String sponsor, String provider, int n, Instant at) {
        for (int i = 0; i < n; i++) {
            execute("INSERT INTO qb_exposure (exposure_id, publication_id, user_id, publisher_id, provider_id, "
                + "counted_at, surface, counted_sec, timer, capped_days, created_at, version) VALUES (?, ?, ?, ?, ?, "
                + "?, 'START', 10, 'SCREEN', 0, ?, 1)", UUID.randomUUID().toString(), publicationId,
                "fm8-" + UUID.randomUUID(), sponsor, provider, Timestamp.from(at), Timestamp.from(at));
        }
    }

    static List<Map<String, Object>> settlements(String publicationId) {
        return query("SELECT * FROM qb_exposure_settlement_version WHERE publication_id = ? ORDER BY period, row_id",
            publicationId);
    }

    String period(Instant at) {
        return com.jabiz.quizbuks.fee.FeeRules.period(at, ZONE);
    }

    JobRunner.JobRun monthlyIn(YearMonth month) {
        Instant at = month.atDay(2).atTime(0, 47).atZone(ZONE).toInstant();
        clock.set(at);
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.execute(qbExposureMonthlyJob, at);
    }

    JobRunner.JobRun finalRun(Instant at) {
        clock.set(at);
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.execute(qbExposureFinalJob, at);
    }

    static BigDecimal dec(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    Map<String, Object> ref(String publicationId, String sponsor, String period, boolean last) {
        return period == null ? body("publicationId", publicationId, "sponsorId", sponsor, "last", last)
            : body("publicationId", publicationId, "sponsorId", sponsor, "period", period, "last", last);
    }

    @Test
    void theMonthsAreChargedCumulativelyAndTheFinalOneChargesExactlyTheDeposit() {
        String[] p = boughtPublication("fm8-settle", 100);
        String sponsor = p[0];
        String publicationId = p[1];
        // 100 bought at 1 yen: a deposit of 110.
        assertThat(dec(exposurePrepayment(publicationId).get("amount"))).isEqualByComparingTo("110");
        String month = period(clock.instant());
        YearMonth next = YearMonth.parse(month).plusMonths(1);
        successes(publicationId, sponsor, p[2], 5, clock.instant());

        // The month: 5 exposures, 5 yen and the tax on 5 (0) - rounded on the total, not on the month.
        monthlyIn(next);
        assertThat(settlements(publicationId)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("period", month).containsEntry("final", false);
            assertThat(dec(row.get("to_count"))).isEqualByComparingTo("5");
            assertThat(dec(row.get("amount"))).isEqualByComparingTo("5");
            assertThat(dec(row.get("tax"))).isEqualByComparingTo("0");
            assertThat(row.get("charge_id")).isNotNull();
        });
        assertThat(dec(exposurePrepayment(publicationId).get("remaining"))).isEqualByComparingTo("105");

        // 95 more after the month, and the publication ends: the final settlement counts all 100 (and no more than
        // bought): 95 yen, and the tax on the 100 less none charged: 10, so the 110 of the deposit is used to the yen.
        successes(publicationId, sponsor, p[2], 95, clock.instant().plusSeconds(60));
        successes(publicationId, sponsor, p[2], 30, clock.instant().plusSeconds(120));
        run("QB_PUBLICATION_ADMIN_END", contentAdmin(), body("publicationId", publicationId, "reason", "F-M8 test"));
        finalRun(clock.instant().plus(Duration.ofHours(3)));
        assertThat(settlements(publicationId)).hasSize(2).last().satisfies(row -> {
            assertThat(row).containsEntry("period", next.toString()).containsEntry("final", true);
            assertThat(dec(row.get("to_count"))).isEqualByComparingTo("100");
            assertThat(dec(row.get("counted"))).isEqualByComparingTo("95");
            assertThat(dec(row.get("amount"))).isEqualByComparingTo("95");
            assertThat(dec(row.get("tax"))).isEqualByComparingTo("10");
            assertThat(dec(row.get("total"))).isEqualByComparingTo("105");
        });
        // Nothing is left of the deposit: the release gave back 0 and closed it.
        assertThat(exposurePrepayment(publicationId)).containsEntry("status", "RELEASED");
        assertThat(dec(exposurePrepayment(publicationId).get("remaining"))).isEqualByComparingTo("0");
        assertThat(query("SELECT coalesce(sum(total), 0) AS t FROM qb_fee_charge_version WHERE sponsor_id = ? AND "
            + "kind = 'EXPOSURE'", sponsor).getFirst().get("t")).satisfies(t -> assertThat(dec(t))
            .isEqualByComparingTo("110"));
        assertThat(differences(sponsor)).isEmpty();
        noUpdatesOrDeletes("qb_exposure_settlement_version");
    }

    @Test
    void anUnusedDepositGoesBackAfterTheEndAndAMissedMonthRunLateCountsNothingAgain() {
        String[] p = boughtPublication("fm8-late", 100);
        String sponsor = p[0];
        String publicationId = p[1];
        YearMonth first = YearMonth.parse(period(clock.instant()));
        successes(publicationId, sponsor, p[2], 5, clock.instant());
        successes(publicationId, sponsor, p[2], 3, first.plusMonths(1).atDay(5).atStartOfDay(ZONE).toInstant());
        // The second month is settled first (the first month's run was missed): it counts all 8.
        clock.set(first.plusMonths(2).atDay(2).atTime(0, 47).atZone(ZONE).toInstant());
        systemRun(ExposureSettlements.SETTLE, ref(publicationId, sponsor, first.plusMonths(1).toString(), false));
        assertThat(settlements(publicationId)).singleElement().satisfies(row -> {
            assertThat(dec(row.get("to_count"))).isEqualByComparingTo("8");
            assertThat(dec(row.get("amount"))).isEqualByComparingTo("8");
        });
        // The missed month is run late: a later month has its row, so it counts nothing and charges nothing.
        systemRun(ExposureSettlements.SETTLE, ref(publicationId, sponsor, first.toString(), false));
        assertThat(settlements(publicationId)).hasSize(2).first().satisfies(row -> {
            assertThat(row).containsEntry("period", first.toString());
            assertThat(dec(row.get("counted"))).isEqualByComparingTo("0");
            assertThat(dec(row.get("amount"))).isEqualByComparingTo("0");
            assertThat(row.get("charge_id")).isNull();
        });
        // Run again for the same month: nothing new.
        systemRun(ExposureSettlements.SETTLE, ref(publicationId, sponsor, first.toString(), false));
        assertThat(settlements(publicationId)).hasSize(2);

        // Ended early: the final row is the month it ended in; the unused 92 exposures' deposit (110 - 8 - 0) goes back.
        run("QB_PUBLICATION_ADMIN_END", contentAdmin(), body("publicationId", publicationId, "reason", "F-M8 test"));
        finalRun(clock.instant().plus(Duration.ofHours(3)));
        assertThat(exposurePrepayment(publicationId)).containsEntry("status", "RELEASED");
        assertThat(dec(exposurePrepayment(publicationId).get("remaining"))).isEqualByComparingTo("0");
        // Charged 8 (and no tax on 8: 0.8 rounds down), given back 102 of the 110.
        assertThat(query("SELECT sum(total) AS t FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = 'EXPOSURE'",
            sponsor).getFirst().get("t")).satisfies(t -> assertThat(dec(t)).isEqualByComparingTo("8"));
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void whatGoesBeyondThePurchaseIsNotCharged() {
        String[] p = boughtPublication("fm8-over", 100);
        successes(p[1], p[0], p[2], 130, clock.instant());
        run("QB_PUBLICATION_ADMIN_END", contentAdmin(), body("publicationId", p[1], "reason", "F-M8 test"));
        finalRun(clock.instant().plus(Duration.ofHours(3)));
        assertThat(settlements(p[1])).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("final", true);
            assertThat(dec(row.get("to_count"))).isEqualByComparingTo("100");
            assertThat(dec(row.get("total"))).isEqualByComparingTo("110");
        });
        assertThat(differences(p[0])).isEmpty();
    }

    String contentAdmin() {
        return com.jabiz.runtime.test.TestTokens.bearer(tokens, "fm8-content-admin",
            com.jabiz.quizbuks.QbPermissions.ADMIN_CONTENT_WRITE);
    }

    @Test
    void theSponsorIsToldOnceWhenAtMostATenthIsLeftAndAgainAfterABiggerPurchaseRunsLow() {
        String[] p = boughtPublication("fm8-low", 100);
        long base = events(p[1]);
        // 100 bought, low-ratio 0.1: told at 10 left or fewer; looked at again only when more are counted.
        successes(p[1], p[0], p[2], 50, clock.instant());
        lowRun();
        successes(p[1], p[0], p[2], 30, clock.instant());
        lowRun();
        assertThat(events(p[1])).isEqualTo(base);
        assertThat(query("SELECT notified_for, checked_used FROM qb_exposure_notice WHERE publication_id = ?", p[1]))
            .singleElement().satisfies(row -> {
                assertThat(dec(row.get("notified_for"))).isEqualByComparingTo("0");
                assertThat(dec(row.get("checked_used"))).isEqualByComparingTo("80");
            });
        // 9 left: told, once.
        successes(p[1], p[0], p[2], 11, clock.instant());
        lowRun();
        assertThat(events(p[1])).isEqualTo(base + 1);
        assertThat(query("SELECT notified_for FROM qb_exposure_notice WHERE publication_id = ?", p[1]))
            .singleElement().satisfies(row -> assertThat(dec(row.get("notified_for"))).isEqualByComparingTo("100"));
        successes(p[1], p[0], p[2], 3, clock.instant());
        lowRun();
        assertThat(events(p[1])).isEqualTo(base + 1);
        // A top-up to 200: 106 left of 200 is not low; used up to 185 later it is: told again for 200.
        run(com.jabiz.quizbuks.publishing.PublicationExposureTopUp.TOP_UP, sponsorToken(p[0]), body("publicationId",
            p[1], "expectedPurchased", 100, "additional", 100));
        lowRun();
        assertThat(events(p[1])).isEqualTo(base + 1);
        successes(p[1], p[0], p[2], 91, clock.instant());
        lowRun();
        assertThat(events(p[1])).isEqualTo(base + 2);
        assertThat(query("SELECT notified_for FROM qb_exposure_notice WHERE publication_id = ?", p[1]))
            .singleElement().satisfies(row -> assertThat(dec(row.get("notified_for"))).isEqualByComparingTo("200"));
    }

    long events(String publicationId) {
        return outboxEvents(com.jabiz.quizbuks.publishing.PublicationNotices.EVENT, "publicationId", publicationId);
    }
}

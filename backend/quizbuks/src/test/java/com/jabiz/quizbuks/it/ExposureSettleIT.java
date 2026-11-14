package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.fee.ExposureSettlements;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.setup.QbParams;
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
        // F-M13: the invoice of an exposure fee names the publication ('exposure:<publication>:<month>').
        String exposureInvoice = String.valueOf(query("SELECT i.invoice_id FROM qb_fee_invoice i JOIN "
            + "qb_fee_charge_version c ON CAST(c.charge_id AS text) = i.charge_id WHERE c.sponsor_id = ? AND c.kind = "
            + "'EXPOSURE' ORDER BY i.issued_at", sponsor).getFirst().get("invoice_id"));
        assertThat(items("qb.sponsor.fee-invoice-lines", sponsorToken(sponsor), Map.of("params",
            Map.of("invoiceId", exposureInvoice)))).singleElement().satisfies(line -> assertThat(line)
            .containsEntry("description", String.valueOf(row(publicationId).get("name"))));
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

    @Test
    void theDailyChecksFindWhatIsWrongWithTheExposureFee() {
        String[] p = boughtPublication("fm8-recon", 100);
        String sponsor = p[0];
        assertThat(differences(sponsor)).isEmpty();
        // The deposit in the ledger and what the prepayment holds disagree.
        execute("UPDATE qb_prepayment SET remaining = remaining - 1, version = version + 1 WHERE publication_id = ? "
            + "AND purpose = 'EXPOSURE'", p[1]);
        assertThat(differences(sponsor)).extracting(r -> r.get("checkCode")).contains("EXPOSURE_DEPOSITS");
        execute("UPDATE qb_prepayment SET remaining = remaining + 1, version = version + 1 WHERE publication_id = ? "
            + "AND purpose = 'EXPOSURE'", p[1]);
        assertThat(differences(sponsor)).isEmpty();
        // Ended and no final row for longer than qb.exposure.final-missing-after-hours (48): found; settled: gone.
        run("QB_PUBLICATION_ADMIN_END", contentAdmin(), body("publicationId", p[1], "reason", "F-M8 test"));
        clock.set(clock.instant().plus(Duration.ofHours(60)));
        assertThat(differences(sponsor)).extracting(r -> r.get("checkCode")).contains("EXPOSURE_FINAL_MISSING");
        finalRun(clock.instant().plus(Duration.ofHours(1)));
        assertThat(differences(sponsor)).isEmpty();
    }

    /** Appends a version of the publication as it is now, with some columns overridden (the table only takes inserts). */
    private void publicationVersion(String publicationId, Map<String, String> overrides) {
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'qb_publication_version' AND is_identity = 'NO' "
            + "AND (column_default IS NULL OR column_default NOT LIKE 'nextval%') ORDER BY ordinal_position").stream()
            .map(row -> String.valueOf(row.get("column_name"))).toList();
        String select = String.join(", ", columns.stream().map(c -> switch (c) {
            case "version_no" -> "v.version_no + 1";
            case "effect_start_time", "created_time" -> "?";
            default -> overrides.containsKey(c) ? overrides.get(c) : "v." + c;
        }).toList());
        java.sql.Timestamp at = java.sql.Timestamp.from(clock.instant());
        execute("INSERT INTO qb_publication_version (" + String.join(", ", columns) + ") SELECT " + select
            + " FROM qb_publication_version v WHERE v.publication_id = ?::uuid ORDER BY v.version_no DESC LIMIT 1",
            at, at, publicationId);
    }

    @Test
    void theDailyChecksFindMoreCountedThanBoughtAndAPriceWithoutAPurchase() {
        String[] p = boughtPublication("fm8-recon2", 100);
        String sponsor = p[0];
        String month = period(clock.instant());
        successes(p[1], sponsor, p[2], 5, clock.instant());
        monthlyIn(YearMonth.parse(month).plusMonths(1));
        assertThat(differences(sponsor)).isEmpty();
        // The record says fewer were bought than the settlements counted: expected what was bought, found what was counted.
        publicationVersion(p[1], Map.of("exposure_purchased", "3"));
        assertThat(differences(sponsor)).filteredOn(r -> "EXPOSURE_SETTLED_OVER".equals(r.get("checkCode")))
            .singleElement().satisfies(row -> {
                assertThat(dec(row.get("expected"))).isEqualByComparingTo("3");
                assertThat(dec(row.get("actual"))).isEqualByComparingTo("5");
                assertThat(row.get("reference")).isEqualTo(p[1]);
            });
        // A purchase without its price (they are written together): the sponsor is named.
        publicationVersion(p[1], Map.of("exposure_purchased", "100", "exposure_unit", "NULL"));
        assertThat(differences(sponsor)).extracting(r -> r.get("checkCode")).contains("EXPOSURE_UNIT_MISMATCH")
            .doesNotContain("EXPOSURE_SETTLED_OVER");
    }

    /**
     * F-M8c review H1: a missed month run late, after a later month was settled and the publication has ended, must not
     * write the final row with nothing counted (and give the whole deposit back): the final job settles the rest.
     */
    @Test
    void aMissedMonthRunLateAfterALaterOneAndTheEndLeavesTheFinalSettlementToTheFinalJob() {
        String[] p = boughtPublication("fm8-h1", 100);
        String sponsor = p[0];
        String publicationId = p[1];
        YearMonth m0 = YearMonth.parse(period(clock.instant()));
        YearMonth m1 = m0.plusMonths(1);
        YearMonth m2 = m0.plusMonths(2);
        successes(publicationId, sponsor, p[2], 5, clock.instant());
        successes(publicationId, sponsor, p[2], 3, m1.atDay(10).atTime(12, 0).atZone(ZONE).toInstant());
        successes(publicationId, sponsor, p[2], 20, m2.atDay(10).atTime(12, 0).atZone(ZONE).toInstant());
        // The first month's item was missed; the second month's is run (cumulative: 5 + 3).
        clock.set(m2.atDay(2).atTime(0, 47).atZone(ZONE).toInstant());
        systemRun(ExposureSettlements.SETTLE, ref(publicationId, sponsor, m1.toString(), false));
        assertThat(settlements(publicationId)).singleElement().satisfies(row ->
            assertThat(dec(row.get("to_count"))).isEqualByComparingTo("8"));
        // The publication ends in the third month, and its grace passes; then the missed month is run late.
        clock.set(m2.atDay(20).atTime(9, 0).atZone(ZONE).toInstant());
        run("QB_PUBLICATION_ADMIN_END", contentAdmin(), body("publicationId", publicationId, "reason", "F-M8 test"));
        clock.set(clock.instant().plus(Duration.ofHours(3)));
        systemRun(ExposureSettlements.SETTLE, ref(publicationId, sponsor, m0.toString(), false));
        assertThat(settlements(publicationId)).as("nothing written: the final job settles the rest").hasSize(1);
        assertThat(exposurePrepayment(publicationId)).containsEntry("status", "OPEN");
        // The final job settles all of them: 28 in all.
        finalRun(clock.instant().plus(Duration.ofHours(1)));
        assertThat(settlements(publicationId)).hasSize(2).last().satisfies(row -> {
            assertThat(row).containsEntry("final", true);
            assertThat(dec(row.get("to_count"))).isEqualByComparingTo("28");
            assertThat(dec(row.get("counted"))).isEqualByComparingTo("20");
        });
        assertThat(settlements(publicationId).stream().map(row -> dec(row.get("amount")))
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("28");
        assertThat(exposurePrepayment(publicationId)).containsEntry("status", "RELEASED");
        // The missed month run once more after the final row: nothing is written, and the daily check agrees.
        systemRun(ExposureSettlements.SETTLE, ref(publicationId, sponsor, m0.toString(), false));
        assertThat(settlements(publicationId)).hasSize(2);
        assertThat(exposureChecks(sponsor)).isEmpty();
    }

    /**
     * F-M8c review L3: a publication that ended, past the grace, with a month of its not settled and no later row: the
     * monthly run of that month writes nothing; the daily job writes the final row, in the month it ended in.
     */
    @Test
    void anEndedPublicationsMissedMonthRunLateLeavesTheFinalRowToTheFinalJobInTheMonthItEndedIn() {
        String[] p = boughtPublication("fm8-l3", 100);
        String sponsor = p[0];
        String publicationId = p[1];
        YearMonth m0 = YearMonth.parse(period(clock.instant()));
        YearMonth m1 = m0.plusMonths(1);
        successes(publicationId, sponsor, p[2], 10, clock.instant());
        clock.set(m1.atDay(10).atTime(9, 0).atZone(ZONE).toInstant());
        successes(publicationId, sponsor, p[2], 5, clock.instant());
        run("QB_PUBLICATION_ADMIN_END", contentAdmin(), body("publicationId", publicationId, "reason", "F-M8 test"));
        clock.set(m1.plusMonths(1).atDay(2).atTime(0, 47).atZone(ZONE).toInstant());
        systemRun(ExposureSettlements.SETTLE, ref(publicationId, sponsor, m0.toString(), false));
        assertThat(settlements(publicationId)).as("nothing written by the month's run").isEmpty();
        assertThat(exposurePrepayment(publicationId)).containsEntry("status", "OPEN");
        finalRun(clock.instant().plus(Duration.ofHours(3)));
        assertThat(settlements(publicationId)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("final", true).containsEntry("period", m1.toString());
            assertThat(dec(row.get("to_count"))).isEqualByComparingTo("15");
        });
        assertThat(exposurePrepayment(publicationId)).containsEntry("status", "RELEASED");
        assertThat(exposureChecks(sponsor)).isEmpty();
    }

    /** Copies the settlement row of the publication as a row of its own (another period, another amount). */
    private void extraSettlement(String publicationId, String period, int moreAmount) {
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'qb_exposure_settlement_version' AND is_identity = 'NO' "
            + "AND (column_default IS NULL OR column_default NOT LIKE 'nextval%') ORDER BY ordinal_position").stream()
            .map(row -> String.valueOf(row.get("column_name"))).toList();
        String id = UUID.randomUUID().toString();
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT ?::uuid, "
            + "'QbExposureSettlement', process_seq_id FROM qb_exposure_settlement_version WHERE publication_id = ? "
            + "LIMIT 1", id, publicationId);
        String select = String.join(", ", columns.stream().map(c -> switch (c) {
            case "settlement_id" -> "?::uuid";
            case "version_no" -> "1";
            case "period" -> "?";
            case "final" -> "false";
            case "amount" -> "v.amount + " + moreAmount;
            case "total" -> "v.total + " + moreAmount;
            default -> "v." + c;
        }).toList());
        execute("INSERT INTO qb_exposure_settlement_version (" + String.join(", ", columns) + ") SELECT " + select
            + " FROM qb_exposure_settlement_version v WHERE v.publication_id = ? ORDER BY v.version_no DESC LIMIT 1",
            id, period, publicationId);
    }

    @Test
    void theDailyChecksFindAFinalSettlementThatCountedLessAndAmountsThatDoNotAddUp() {
        String[] p = boughtPublication("fm8-short", 100);
        String sponsor = p[0];
        String publicationId = p[1];
        Instant early = clock.instant().plusSeconds(60);
        successes(publicationId, sponsor, p[2], 20, early);
        run("QB_PUBLICATION_ADMIN_END", contentAdmin(), body("publicationId", publicationId, "reason", "F-M8 test"));
        finalRun(clock.instant().plus(Duration.ofHours(3)));
        assertThat(exposureChecks(sponsor)).isEmpty();
        // A final row that counted fewer than were counted by its time (as a wrongly written row would): found.
        successes(publicationId, sponsor, p[2], 5, early.plusSeconds(60));
        assertThat(exposureChecks(sponsor)).filteredOn(r -> "EXPOSURE_SETTLED_SHORT".equals(r.get("checkCode")))
            .singleElement().satisfies(row -> {
                assertThat(dec(row.get("expected"))).isEqualByComparingTo("25");
                assertThat(dec(row.get("actual"))).isEqualByComparingTo("20");
                assertThat(row.get("reference")).isEqualTo(publicationId);
            });
        // A row of amount that the count does not explain: the sum (20 + 27) is more than the cumulative amount (20).
        extraSettlement(publicationId, "1999-01", 7);
        assertThat(exposureChecks(sponsor)).filteredOn(r -> "EXPOSURE_AMOUNT_MISMATCH".equals(r.get("checkCode")))
            .singleElement().satisfies(row -> {
                assertThat(dec(row.get("expected"))).isEqualByComparingTo("20");
                assertThat(dec(row.get("actual"))).isEqualByComparingTo("47");
            });
    }

    @Test
    void theDailyChecksFindAMonthNotSettledAPriceOutOfRangeAndAPublicationThatBoughtNothing() {
        String[] p = boughtPublication("fm8-checks", 100);
        String sponsor = p[0];
        String month = period(clock.instant());
        successes(p[1], sponsor, p[2], 5, clock.instant());
        assertThat(exposureChecks(sponsor)).isEmpty();
        // The month is over and the hours have passed (30): its settlement is not there.
        clock.set(YearMonth.parse(month).plusMonths(1).atDay(3).atTime(12, 0).atZone(ZONE).toInstant());
        assertThat(exposureChecks(sponsor)).filteredOn(r -> "EXPOSURE_UNSETTLED".equals(r.get("checkCode")))
            .singleElement().satisfies(row -> {
                assertThat(row.get("reference")).isEqualTo(month);
                assertThat(dec(row.get("actual"))).isEqualByComparingTo("1");
            });
        monthlyIn(YearMonth.parse(month).plusMonths(1));
        assertThat(exposureChecks(sponsor)).filteredOn(r -> "EXPOSURE_UNSETTLED".equals(r.get("checkCode"))).isEmpty();

        // The headquarters raises the least price above the operator's: the operator is named (to look at).
        String operator = String.valueOf(query("SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?",
            sponsor).getFirst().get("operator_id"));
        param(QbParams.EXPOSURE_UNIT_MIN, "2");
        assertThat(exposureChecks(operator)).singleElement().satisfies(row ->
            assertThat(row.get("checkCode")).isEqualTo("EXPOSURE_PRICE_RANGE"));
        param(QbParams.EXPOSURE_UNIT_MIN, "1");

        // Submitted while the switch was off: it bought nothing (a note, not a difference).
        param(QbParams.EXPOSURE_BILLING_ENABLED, "false");
        String other = reviewedSponsor("fm8-checks-other");
        grant(other, 100_000, clock.instant().plus(Duration.ofDays(400)));
        String sharedVideoId = approvedVideo(reviewedSponsor("fm8-checks-other-provider"));
        String publicationId = draft(other, "fm8-unbought", sharedVideoId, 100L, clock.instant());
        submit(sponsorToken(other), publicationId);
        param(QbParams.EXPOSURE_BILLING_ENABLED, "true");
        assertThat(exposureChecks(other)).singleElement().satisfies(row -> {
            assertThat(row.get("checkCode")).isEqualTo("EXPOSURE_UNPURCHASED");
            assertThat(dec(row.get("actual"))).isEqualByComparingTo("1");
        });
        assertThat(differences(other)).isEmpty();
    }

    /** F-M8c review M3: the month's report puts the exposure fee's freezes, top-up, charges and release in its own kinds. */
    @Test
    void theMonthlyReportHasTheExposureFeeInItsOwnKinds() {
        // The report is the whole platform's: start a day after what the tests before posted.
        clock.set(clock.instant().plus(Duration.ofDays(1)));
        Instant from = clock.instant();
        String[] p = boughtPublication("fm8-monthly", 100);
        String sponsor = p[0];
        String publicationId = p[1];
        // A top-up of 100 more: the deposit is 220 in all (110 + 110).
        run(com.jabiz.quizbuks.publishing.PublicationExposureTopUp.TOP_UP, sponsorToken(sponsor), body(
            "publicationId", publicationId, "expectedPurchased", 100, "additional", 100));
        assertThat(dec(exposurePrepayment(publicationId).get("amount"))).isEqualByComparingTo("220");
        String month = period(clock.instant());
        successes(publicationId, sponsor, p[2], 150, clock.instant());
        // The month is settled (150 exposures: 150 yen and 15 of tax), the publication ends and the rest is given back.
        monthlyIn(YearMonth.parse(month).plusMonths(1));
        run("QB_PUBLICATION_ADMIN_END", contentAdmin(), body("publicationId", publicationId, "reason", "F-M8 test"));
        finalRun(clock.instant().plus(Duration.ofHours(3)));
        assertThat(exposurePrepayment(publicationId)).containsEntry("status", "RELEASED");
        Instant to = clock.instant().plus(Duration.ofDays(1));
        String token = com.jabiz.runtime.test.TestTokens.bearer(tokens, "fm8-monthly-finance",
            "qb.admin.finance.read");
        Map<String, Map<String, Object>> byKind = new java.util.HashMap<>();
        items("qb.admin.monthly", token, Map.of("params", Map.of("from", from.toString(), "to", to.toString()),
            "limit", 100)).forEach(row -> byKind.put(String.valueOf(row.get("category")), row));
        // Frozen and topped up: two postings of 110; given back: what the settlement did not use (220 - 165).
        assertThat(dec(byKind.get("EXPOSURE_DEPOSIT").get("transactionCount"))).isEqualByComparingTo("2");
        assertThat(dec(byKind.get("EXPOSURE_DEPOSIT").get("amount"))).isEqualByComparingTo("220");
        assertThat(dec(byKind.get("EXPOSURE_RELEASED").get("transactionCount"))).isEqualByComparingTo("1");
        assertThat(dec(byKind.get("EXPOSURE_RELEASED").get("amount"))).isEqualByComparingTo("55");
        assertThat(dec(byKind.get("EXPOSURE_CHARGED").get("amount"))).isEqualByComparingTo("165");
        // The kinds of the other charges do not hold them.
        assertThat(dec(byKind.get("PREPAYMENT_FROZEN").get("amount"))).isEqualByComparingTo("0");
        assertThat(dec(byKind.get("PREPAYMENT_RELEASED").get("amount"))).isEqualByComparingTo("0");
        assertThat(dec(byKind.get("FEE_CHARGED").get("amount"))).isEqualByComparingTo("0");
        // The deposit is used to the yen and given back for the rest: what the month holds in the prepayments' account
        // closes to nothing (frozen -220, used 165, given back 55), and the charge is revenue and tax on 150 yen.
        assertThat(byKind.values().stream().map(row -> dec(row.get("feePrepayments")))
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0");
        assertThat(dec(byKind.get("EXPOSURE_CHARGED").get("feeRevenue"))).isEqualByComparingTo("-150");
        assertThat(dec(byKind.get("EXPOSURE_CHARGED").get("taxPayable"))).isEqualByComparingTo("-15");
        // F-M14b (review L4 of #688): the guard over the whole ledger, the exposure fee's freeze, top-up
        // (QB_PREPAYMENT_TOP_UP), charge, payment from the deposit and release driven.
        MonthlyKinds.assertGuard(items("qb.admin.monthly", token, Map.of("params", Map.of("from",
                "2000-01-01T00:00:00Z", "to", "2100-01-01T00:00:00Z"), "limit", 100)),
            java.util.List.of("EXPOSURE_DEPOSIT", "EXPOSURE_CHARGED", "PREPAYMENT_USED", "EXPOSURE_RELEASED"),
            query(MonthlyKinds.SOURCES_SQL), ((Number) query(MonthlyKinds.NO_SOURCE_SQL).getFirst().get("n"))
                .longValue(), query(MonthlyKinds.TOTALS_SQL).getFirst());
    }
}

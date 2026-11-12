package com.jabiz.quizbuks.it;

import com.jabiz.context.EntryScope;
import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.DistributionPrices;
import com.jabiz.quizbuks.fee.DistributionSettlements;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.operator.OperatorCodes;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M6 (docs/quizbuks/plans/impl/F-M.md §9): the distribution fee - the price per start frozen at the submission and
 * its deposit set aside, the monthly and the final settlement charged from the deposit (the fee credit first) and
 * the account, the release once settled, a postpaid sponsor's, the invoice's wait and a late fee, the prices.
 */
class DistributionIT extends PrepaymentItSupport {

    static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    @Autowired
    JobDefinition<DistributionSettlements.SettleRef> qbDistributionMonthlyJob;
    @Autowired
    JobDefinition<DistributionSettlements.SettleRef> qbDistributionFinalJob;

    void distribution(boolean on) {
        param(QbParams.DISTRIBUTION_BILLING_ENABLED, String.valueOf(on));
    }

    static String operatorOf(String sponsor) {
        return String.valueOf(query("SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?", sponsor)
            .getFirst().get("operator_id"));
    }

    /** Headquarters set the operator's distribution price: 0.05 yen a Kudos, 1 to 20 a start, 500 least deposit. */
    void distributionPrice(String operator) {
        run(DistributionPrices.SET, TestTokens.bearer(tokens, "fm6-hq", QbPermissions.FEE_MANAGE),
            body("operatorId", operator, "rate", "0.05", "unitFloor", "1", "unitCeiling", "20", "minimumDeposit", 500,
                "reason", "F-M6"));
    }

    /** A Sponsor of an operator with the distribution price, with Kudos for the rewards (the top tier is 100). */
    String distributionSponsor(String name) {
        String sponsor = pricedSponsor(name);
        distributionPrice(operatorOf(sponsor));
        credit(sponsor, 5_000);
        return sponsor;
    }

    /** A week-long publication with at most 30 winners (the top tier 100: 5 yen a start, 165 set aside). */
    String publication(String sponsor, String name, Map<String, Object> more) {
        Map<String, Object> overrides = new java.util.HashMap<>(Map.of("maxWinners", 30, "regions", List.of("GLOBAL")));
        overrides.putAll(more);
        return livePublication(sponsor, name, overrides);
    }

    void starts(String publicationId, int count, String name) {
        for (int i = 0; i < count; i++) {
            start(taker(newTaker(unique(name + "-taker-" + i), null)), publicationId);
        }
    }

    static List<Map<String, Object>> settlements(String publicationId) {
        return query("SELECT * FROM qb_distribution_settlement_version WHERE publication_id = ? ORDER BY period",
            publicationId);
    }

    static Map<String, Object> distributionPrepayment(String publicationId) {
        return query("SELECT * FROM qb_prepayment WHERE publication_id = ? AND purpose = 'DISTRIBUTION'",
            publicationId).getFirst();
    }

    static String period(Instant at) {
        return FeeRules.period(at, ZONE);
    }

    /** The monthly job's run on the 2nd at 00:37 of the month after the clock's (the clock moved there). */
    JobRunner.JobRun monthly() {
        return monthlyIn(YearMonth.parse(period(clock.instant())).plusMonths(1));
    }

    /** The monthly job's run on the 2nd of {@code month} at 00:37 (it settles the month before). */
    JobRunner.JobRun monthlyIn(YearMonth month) {
        Instant at = month.atDay(2).atTime(0, 37).atZone(ZONE).toInstant();
        clock.set(at);
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.execute(qbDistributionMonthlyJob, at);
    }

    JobRunner.JobRun finalRun(Instant at) {
        clock.set(at);
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.execute(qbDistributionFinalJob, at);
    }

    void end(String publicationId) {
        run("QB_PUBLICATION_ADMIN_END", TestTokens.bearer(tokens, "fm6-content-admin",
            QbPermissions.ADMIN_CONTENT_WRITE), body("publicationId", publicationId, "reason", "F-M6 test"));
        assertThat(status(publicationId)).isEqualTo("ENDED");
    }

    static BigDecimal dec(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    static void noUpdatesOrDeletes(String table) {
        assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed "
            + "FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname = ?", table))
            .as(table).singleElement()
            .satisfies(row -> assertThat(((Number) row.get("changed")).longValue()).as(table).isZero());
    }

    @Test
    void switchedOffASubmissionFreezesNothing() {
        autoApprove(true);
        distribution(false);
        String sponsor = distributionSponsor("fm6-off");
        cash(sponsor, 1_000);
        String publicationId = publication(sponsor, "FM6 off", Map.of());
        assertThat(row(publicationId).get("distribution_unit")).isNull();
        assertThat(query("SELECT 1 FROM qb_prepayment WHERE publication_id = ?", publicationId)).isEmpty();
        starts(publicationId, 2, "fm6-off");
        monthly();
        assertThat(settlements(publicationId)).isEmpty();
    }

    @Test
    void theDepositIsSetAsideTheFeeCreditFirstAndSettledAMonthAtATimeThenGivenBack() {
        autoApprove(true);
        distribution(true);
        String sponsor = distributionSponsor("fm6-main");
        String grantId = grant(sponsor, 100, clock.instant().plus(Duration.ofDays(400)));
        cash(sponsor, 1_000);
        String publicationId = publication(sponsor, "FM6 main", Map.of());

        // 0.05 × 100 = 5 a start; 30 winners: 150 and 10% tax, 165 set aside, the fee credit's 100 first.
        assertThat((BigDecimal) row(publicationId).get("distribution_unit")).isEqualByComparingTo("5");
        assertThat(row(publicationId)).containsEntry("distribution_at_floor", false);
        Map<String, Object> deposit = distributionPrepayment(publicationId);
        assertThat((BigDecimal) deposit.get("amount")).isEqualByComparingTo("165");
        assertThat(remainingOf(grantId)).isEqualByComparingTo("0");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("165");

        // Turned off after the submission: what was submitted is settled to its end all the same.
        distribution(false);
        String month = period(clock.instant());
        starts(publicationId, 3, "fm6-main");
        assertThat(monthly().outcome()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(settlements(publicationId)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("period", month).containsEntry("final", false);
            assertThat(((Number) row.get("starts")).longValue()).isEqualTo(3);
            assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("15");
            assertThat((BigDecimal) row.get("tax")).isEqualByComparingTo("1");
            assertThat(row.get("charge_id")).isNotNull();
        });
        Map<String, Object> charge = query("SELECT * FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = "
            + "'DISTRIBUTION'", sponsor).getFirst();
        assertThat(charge).containsEntry("period", month).containsEntry("operator_id", operatorOf(sponsor));
        assertThat((BigDecimal) charge.get("total")).isEqualByComparingTo("16");
        // Paid from the deposit, its fee credit first (F-F11: fee credit before cash for the distribution fee).
        assertThat(query("SELECT sub_account, amount FROM qb_fee_payment_version WHERE target_id = ?",
            String.valueOf(charge.get("charge_id")))).singleElement().satisfies(part -> {
                assertThat(part).containsEntry("sub_account", "FEE_CREDIT");
                assertThat((BigDecimal) part.get("amount")).isEqualByComparingTo("16");
            });
        assertThat((BigDecimal) distributionPrepayment(publicationId).get("remaining")).isEqualByComparingTo("149");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("149");
        assertThat(query("SELECT status FROM qb_fee_invoice WHERE charge_id = ?", String.valueOf(
            charge.get("charge_id")))).singleElement().satisfies(row -> assertThat(row).containsEntry("status", "PAID"));

        // A start more, then the end; the final settlement after the grace gives the rest back.
        starts(publicationId, 1, "fm6-main-late");
        // The Sponsor's page: the price, the starts since the last settlement and what they come to now.
        assertThat(items("qb.sponsor.publication-distribution", sponsorToken(sponsor),
            Map.of("params", Map.of("publicationId", publicationId)))).singleElement().satisfies(row -> {
                assertThat(dec(row.get("unit"))).isEqualByComparingTo("5");
                assertThat(((Number) row.get("pendingStarts")).longValue()).isEqualTo(1);
                assertThat(dec(row.get("pendingEstimate"))).isEqualByComparingTo("5");
                assertThat(dec(row.get("charged"))).isEqualByComparingTo("15");
            });
        assertThat(items("qb.sponsor.publication-distribution-settlements", sponsorToken(sponsor),
            Map.of("params", Map.of("publicationId", publicationId)))).hasSize(1);
        // Another Sponsor sees nothing of it.
        assertThat(items("qb.sponsor.publication-distribution-settlements", sponsorToken(newSponsor(
            unique("fm6-other"))), Map.of("params", Map.of("publicationId", publicationId)))).isEmpty();
        assertThat(items("qb.admin.distribution-settlements", TestTokens.bearer(tokens, "fm6-finance",
            QbPermissions.ADMIN_FINANCE_READ), Map.of("params", Map.of("publicationId", publicationId))))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("atFloor", false));
        end(publicationId);
        Instant endedAt = clock.instant();
        assertThat(finalRun(endedAt.plus(Duration.ofMinutes(30))).outcome()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(settlements(publicationId)).hasSize(1);
        finalRun(endedAt.plus(Duration.ofHours(2)));
        List<Map<String, Object>> rows = settlements(publicationId);
        assertThat(rows).hasSize(2);
        Map<String, Object> last = rows.getLast();
        assertThat(last).containsEntry("final", true).containsEntry("period", period(endedAt));
        assertThat((BigDecimal) last.get("amount")).isEqualByComparingTo("5");
        assertThat(distributionPrepayment(publicationId)).containsEntry("status", "RELEASED");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        // Again: nothing more.
        finalRun(endedAt.plus(Duration.ofDays(1)));
        monthly();
        assertThat(settlements(publicationId)).hasSize(2);
        assertThat(differences(sponsor)).isEmpty();
        noUpdatesOrDeletes("qb_distribution_settlement_version");
        noUpdatesOrDeletes("qb_fee_charge_version");
        // The monthly report: the deposit, its charge and its release are each of their kind (none is OTHER).
        everyPostingHasItsKind();
        Instant from = Instant.parse("2000-01-01T00:00:00Z");
        List<Map<String, Object>> monthly = items("qb.admin.monthly", TestTokens.bearer(tokens, "fm6-monthly",
            QbPermissions.ADMIN_FINANCE_READ), Map.of("params", Map.of("from", from.toString(), "to",
            clock.instant().plus(Duration.ofDays(1)).toString()), "limit", 100));
        assertThat(monthly.stream().filter(row -> String.valueOf(row.get("category")).startsWith("DISTRIBUTION_"))
            .map(row -> ((Number) row.get("transactionCount")).longValue()).toList()).allMatch(n -> n >= 1);
    }

    @Test
    void endedJustBeforeTheMonthlyRunTheDailyJobWritesTheFinalRowInTheNextMonthAndGivesBackOnce() {
        autoApprove(true);
        distribution(true);
        String sponsor = distributionSponsor("fm6-n1");
        cash(sponsor, 1_000);
        // From the 25th: a publication of about a week that ends at 00:10 of the 2nd of the next month, so the
        // monthly run at 00:37 is within the grace.
        YearMonth current = YearMonth.parse(period(clock.instant()));
        Instant the25th = current.atDay(25).atStartOfDay(ZONE).toInstant();
        if (clock.instant().isBefore(the25th)) {
            clock.set(the25th);
        } else {
            current = current.plusMonths(1);
            clock.set(current.atDay(25).atStartOfDay(ZONE).toInstant());
        }
        YearMonth next = current.plusMonths(1);
        Instant endAt = next.atDay(2).atTime(0, 10).atZone(ZONE).toInstant();
        String publicationId = publication(sponsor, "FM6 N1", Map.of("endAt", endAt.toString()));
        starts(publicationId, 2, "fm6-n1");
        clock.set(endAt.plusSeconds(60));
        runTick();
        assertThat(status(publicationId)).isEqualTo("ENDED");
        String month = YearMonth.from(endAt.atZone(ZONE)).minusMonths(1).toString();
        monthlyIn(next);
        assertThat(settlements(publicationId)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("period", month).containsEntry("final", false));
        assertThat(distributionPrepayment(publicationId)).containsEntry("status", "OPEN");
        // 03:07: past the grace; the month it ended in has no row yet.
        finalRun(next.atDay(2).atTime(3, 7).atZone(ZONE).toInstant());
        assertThat(settlements(publicationId)).hasSize(2).last()
            .satisfies(row -> assertThat(row).containsEntry("period", next.toString()).containsEntry("final", true));
        assertThat(distributionPrepayment(publicationId)).containsEntry("status", "RELEASED");
        assertThat(query("SELECT 1 FROM qb_fee_payment_version WHERE target_id = ? AND amount < 0",
            String.valueOf(distributionPrepayment(publicationId).get("prepayment_id")))).hasSize(1);
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void endedLongBeforeTheMonthlyRunItWritesTheFinalRowItselfAndTheDailyJobDoesNothing() {
        autoApprove(true);
        distribution(true);
        String sponsor = distributionSponsor("fm6-n1b");
        cash(sponsor, 1_000);
        String publicationId = publication(sponsor, "FM6 N1b", Map.of());
        starts(publicationId, 1, "fm6-n1b");
        String month = period(clock.instant());
        end(publicationId);
        monthly();
        assertThat(settlements(publicationId)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("period", month).containsEntry("final", true));
        assertThat(distributionPrepayment(publicationId)).containsEntry("status", "RELEASED");
        finalRun(clock.instant().plus(Duration.ofHours(3)));
        assertThat(settlements(publicationId)).hasSize(1);
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void aPostpaidSponsorOwesTheFeeAndALateFeeGoesOnTheNextInvoice() {
        autoApprove(true);
        distribution(true);
        String sponsor = distributionSponsor("fm6-postpaid");
        billing(sponsor, "POSTPAID", 100_000);
        String first = publication(sponsor, "FM6 postpaid A", Map.of());
        String second = publication(sponsor, "FM6 postpaid B", Map.of());
        assertThat(distributionPrepayment(first)).containsEntry("mode", "POSTPAID");
        String month = period(clock.instant());
        starts(first, 2, "fm6-pp-a");
        starts(second, 1, "fm6-pp-b");
        YearMonth next = YearMonth.parse(month).plusMonths(1);
        clock.set(next.atDay(2).atTime(0, 37).atZone(ZONE).toInstant());

        // A settled, B not: the month's invoice waits (the job's items leave the sponsor out).
        systemRun(DistributionSettlements.SETTLE, body("publicationId", first, "sponsorId", sponsor, "period", month,
            "last", false));
        assertThat(invoicesDue(next.toString()).stream().filter(row -> sponsor.equals(row.get("sponsorId")))).isEmpty();
        // A's 10 and 1 owed (1130), not paid; its prepayment stops counting by as much.
        assertThat(ledger("1130", sponsor)).isEqualByComparingTo("-11");
        assertThat((BigDecimal) distributionPrepayment(first).get("remaining")).isEqualByComparingTo("154");

        // The month's invoice issued anyway (by hand, for the test), B's fee of the month goes on the next month's.
        assertThat(systemRunItem(FeeProcesses.INVOICE_ITEM, body("sponsorId", sponsor, "period", month)))
            .isEqualTo("ISSUED");
        systemRun(DistributionSettlements.SETTLE, body("publicationId", second, "sponsorId", sponsor, "period", month,
            "last", false));
        assertThat(settlements(second)).singleElement().satisfies(row -> assertThat(row).containsEntry("period", month));
        assertThat(query("SELECT period FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = 'DISTRIBUTION' "
            + "ORDER BY charged_at", sponsor)).extracting(row -> row.get("period"))
            .containsExactly(month, next.toString());
    }

    @Test
    void aZeroAmountWritesTheRowWithoutACharge() {
        autoApprove(true);
        distribution(true);
        String sponsor = distributionSponsor("fm6-zero");
        cash(sponsor, 1_000);
        String publicationId = publication(sponsor, "FM6 zero", Map.of());
        monthly();
        assertThat(settlements(publicationId)).singleElement().satisfies(row -> {
            assertThat(((Number) row.get("starts")).longValue()).isZero();
            assertThat(row.get("charge_id")).isNull();
        });
        assertThat(query("SELECT 1 FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = 'DISTRIBUTION'",
            sponsor)).isEmpty();
    }

    @Test
    void theDailyCheckReportsAMonthNotSettledAndAnEndWithoutItsFinalRow() {
        autoApprove(true);
        distribution(true);
        String sponsor = distributionSponsor("fm6-recon");
        cash(sponsor, 1_000);
        String publicationId = publication(sponsor, "FM6 recon", Map.of());
        starts(publicationId, 1, "fm6-recon");
        String month = period(clock.instant());
        YearMonth next = YearMonth.parse(month).plusMonths(1);
        // The 2nd at 07:00: 31 hours into the month, past qb.distribution.unsettled-after-hours (30); not settled.
        clock.set(next.atDay(2).atTime(7, 0).atZone(ZONE).toInstant());
        assertThat(differences(sponsor)).extracting(row -> row.get("checkCode"))
            .containsExactly("DISTRIBUTION_UNSETTLED");
        systemRun(DistributionSettlements.SETTLE, body("publicationId", publicationId, "sponsorId", sponsor,
            "period", month, "last", false));
        assertThat(differences(sponsor)).isEmpty();
        // Ended and never settled final: reported 48 hours after the end.
        end(publicationId);
        clock.set(clock.instant().plus(Duration.ofHours(49)));
        assertThat(differences(sponsor)).extracting(row -> row.get("checkCode"))
            .containsExactlyInAnyOrder("DISTRIBUTION_FINAL_MISSING", "PREPAYMENT_STALE");
        finalRun(clock.instant());
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void anOperatorsLeadSetsItsPriceWithinTheRange() {
        String hq = TestTokens.bearer(tokens, "fm6-ops", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
        String operator = newOperator(hq, unique("fm6 operator"));
        String lead = member(hq, operator, unique("fm6-lead"), true);
        String staff = member(hq, operator, unique("fm6-staff"), false);
        Map<String, Object> price = body("rate", "0.05", "unitFloor", "1", "unitCeiling", "20", "minimumDeposit", 500,
            "reason", "F-M6");
        assertThat(codes(refused(DistributionPrices.OPERATOR_SET, operatorSession(staff, operator), price, 422)))
            .containsExactly(OperatorCodes.NOT_LEAD);
        run(DistributionPrices.OPERATOR_SET, operatorSession(lead, operator), price);
        assertThat(items("qb.operator.distribution-price", operatorSession(staff, operator), Map.of()))
            .singleElement().satisfies(row -> {
                assertThat(dec(row.get("unitCeiling"))).isEqualByComparingTo("20");
                assertThat(String.valueOf(row.get("range"))).contains("qb.distribution.rate.max");
            });
        assertThat(items("qb.admin.distribution-prices", TestTokens.bearer(tokens, "fm6-billing",
            QbPermissions.FEE_MANAGE), Map.of("limit", 500)).stream()
            .filter(row -> operator.equals(String.valueOf(row.get("operatorId"))))).singleElement()
            .satisfies(row -> assertThat(dec(row.get("minimumDeposit"))).isEqualByComparingTo("500"));
        assertThat(query("SELECT rate FROM qb_distribution_price WHERE operator_id = ?", operator)).singleElement()
            .satisfies(row -> assertThat((BigDecimal) row.get("rate")).isEqualByComparingTo("0.05"));
        // Outside the headquarters' range (the rate at most 1, the floor at least 1).
        assertThat(codes(refused(DistributionPrices.OPERATOR_SET, operatorSession(lead, operator), body("rate", "2",
            "unitFloor", "0.5", "unitCeiling", "20", "minimumDeposit", 500, "reason", "F-M6"), 422)))
            .containsOnly(FeeCodes.DISTRIBUTION_PRICE_RANGE);
    }

    @Test
    void aCapThatComesToMoreThanAFeeMayBeIsRefused() {
        autoApprove(true);
        distribution(true);
        String sponsor = distributionSponsor("fm6-cap");
        // A price beyond any range (set in the table, not through the processes): 1,000 a start; the most winners a
        // publication may have then come to more than a fee may be (N5): refused at the submission, nothing written.
        execute("UPDATE qb_distribution_price SET rate = 10, unit_ceiling = 1000 WHERE operator_id = ?",
            operatorOf(sponsor));
        String token = sponsorToken(sponsor);
        String versionId = version(token, "FM6 cap quiz");
        Map<String, Object> save = complete(versionId, "FM6 cap", clock.instant(),
            clock.instant().plus(Duration.ofDays(7)));
        save.put("maxWinners", 999_999_999);
        save.put("regions", List.of("GLOBAL"));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        Map<String, Object> problem = refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId),
            422);
        assertThat(codes(problem)).contains(FeeCodes.DISTRIBUTION_DEPOSIT_RANGE);
        assertThat(query("SELECT 1 FROM qb_prepayment WHERE publication_id = ?", publicationId)).isEmpty();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    List<Map<String, Object>> invoicesDue(String current) {
        return items(FeeProcesses.INVOICES_DUE_TEMPLATE, TestTokens.bearer(tokens, "fm6-system",
            QbPermissions.FEE_SYSTEM), Map.of("params", Map.of("current", current, "zone", ZONE.getId())));
    }

    Object systemRunItem(String process, Map<String, ?> body) {
        return ((FeeProcesses.ItemOutput) systemRun(process, body)).outcome();
    }

    private String member(String hq, String operatorId, String name, boolean lead) {
        String userId = createUser(name);
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'sec_user_version' AND column_name NOT IN ('row_id', 'version_no', "
            + "'verified_email') ORDER BY ordinal_position").stream().map(row -> (String) row.get("column_name"))
            .toList();
        String list = String.join(", ", columns);
        execute("INSERT INTO sec_user_version (" + list + ", version_no, verified_email) SELECT " + list
            + ", version_no + 1, email FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC LIMIT 1",
            userId);
        run(OperatorProcesses.MEMBER_ADD, hq, body("operatorId", operatorId, "userId", userId, "lead", lead));
        return userId;
    }

    @Test
    void anOperatorNeitherSetsNorReadsAnotherOperatorsPrice() {
        // The operators hold qb.fee.billing (their sponsors' billing mode): headquarters' price process and every
        // operator's prices are headquarters' fee management, which they do not hold.
        String hq = TestTokens.bearer(tokens, "fm6-ops2", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
        String mine = newOperator(hq, unique("fm6 mine"));
        String other = newOperator(hq, unique("fm6 other"));
        String lead = member(hq, mine, unique("fm6-lead2"), true);
        refused(DistributionPrices.SET, operatorSession(lead, mine), body("operatorId", other, "rate", "0.05",
            "unitFloor", "1", "unitCeiling", "20", "minimumDeposit", 500, "reason", "F-M6"), 403);
        assertThat(query("SELECT 1 FROM qb_distribution_price WHERE operator_id = ?", other)).isEmpty();
        client.post().uri("/api/queries/qb.admin.distribution-prices").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, operatorSession(lead, mine)).bodyValue(Map.of("limit", 500))
            .exchange().expectStatus().isForbidden();
    }

    private String operatorSession(String userId, String operatorId) {
        Actor actor = new Actor(userId, null, Set.of(QbRoles.OPERATOR),
            Set.copyOf(QbRoles.of(QbRoles.OPERATOR).permissions()), clock.instant(), null, QbOperators.ENTRY, true)
            .inScope(new EntryScope(QbOperators.SCOPE, operatorId));
        return "Bearer " + tokens.issue(actor).token();
    }
}

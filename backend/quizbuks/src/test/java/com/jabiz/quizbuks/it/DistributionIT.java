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
import com.jabiz.quizbuks.wallet.ReconChecks;
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
    @Autowired
    JobDefinition<FeeProcesses.InvoiceInput> qbFeeInvoiceJob;

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
        // F-M13: what the invoice was paid with: all of it from the deposit, which was set aside of the fee credit; and its
        // line is named by the publication its fee is for (the source key 'distribution:<publication>:<month>').
        String invoiceId = String.valueOf(query("SELECT invoice_id FROM qb_fee_invoice WHERE charge_id = ?",
            String.valueOf(charge.get("charge_id"))).getFirst().get("invoice_id"));
        assertThat(items("qb.sponsor.fee-invoice-sources", sponsorToken(sponsor), Map.of("params",
            Map.of("invoiceId", invoiceId)))).singleElement().satisfies(sources -> assertThat(sources)
            .containsEntry("sourcePrepayment", 16).containsEntry("sourcePrepaymentGift", 16)
            .containsEntry("sourceGift", 0).containsEntry("sourceCash", 0).containsEntry("sourceUnpaid", 0));
        assertThat(items("qb.sponsor.fee-invoice-lines", sponsorToken(sponsor), Map.of("params",
            Map.of("invoiceId", invoiceId)))).singleElement().satisfies(line -> assertThat(line)
            .containsEntry("description", "FM6 main"));

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
    void theDailyCheckReportsAPriceOutsideTheRangeItIsNow() {
        String operator = operatorOf(distributionSponsor("fm6-price-range"));
        String system = TestTokens.bearer(tokens, "fm6-price-recon", QbPermissions.WALLET_SYSTEM);
        assertThat(ReconChecks.coded(feeAccountItems(system, Map.of())).stream()
            .filter(item -> operator.equals(item.partyId()))).isEmpty();
        // The range narrowed after the price was set (here: the price set in the table, beyond the rate at most 1):
        // the price stays and the daily check reports it, under its whole code, so ReconChecks keeps it.
        assertThat(offRangeBeforeDeploying()).doesNotContain(operator);
        execute("UPDATE qb_distribution_price SET rate = 2 WHERE operator_id = ?", operator);
        assertThat(ReconChecks.coded(feeAccountItems(system, Map.of())).stream()
            .filter(item -> operator.equals(item.partyId())))
            .extracting(ReconChecks.Item::check).containsExactly(ReconChecks.DISTRIBUTION_PRICE_RANGE);
        // The query run before deploying (operations.md, launch-checklist.md) finds the same operator.
        assertThat(offRangeBeforeDeploying()).contains(operator);
    }

    /** The operators tools/quizbuks/sql/distribution-price-range.sql lists, at the test's clock. */
    List<String> offRangeBeforeDeploying() {
        try {
            String sql = java.nio.file.Files.readString(java.nio.file.Path.of(
                "../../tools/quizbuks/sql/distribution-price-range.sql"));
            assertThat(sql).contains("now()");
            return query(sql.replace("now()", "CAST('" + clock.instant() + "' AS timestamptz)").replace(";", ""))
                .stream().map(row -> String.valueOf(row.get("operator_id"))).toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
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

    // ---- H1 of the review: the cases of F-M.md §9.9 not covered above ---------------------------------------------

    /** Runs both at once; each one's outcome, or its failure's message. */
    static List<Object> atOnce(java.util.function.Supplier<Object> one, java.util.function.Supplier<Object> two) {
        java.util.concurrent.CompletableFuture<Object> a = java.util.concurrent.CompletableFuture.supplyAsync(one);
        java.util.concurrent.CompletableFuture<Object> b = java.util.concurrent.CompletableFuture.supplyAsync(two);
        return java.util.stream.Stream.of(a, b).map(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                return (Object) ("failed: " + e.getMessage());
            }
        }).toList();
    }

    /** Takers who start and answer wrong: starts that keep no winner's place. */
    void failedStarts(String publicationId, int count, String name) {
        for (int i = 0; i < count; i++) {
            String taker = taker(newTaker(unique(name + "-taker-" + i), null));
            String attemptId = String.valueOf(start(taker, publicationId).get("attemptId"));
            assertThat(submit(taker, attemptId, answers(false, false))).containsEntry("passed", false);
        }
    }

    static Map<String, Object> settleRef(String publicationId, String sponsor, String period, boolean last) {
        return body("publicationId", publicationId, "sponsorId", sponsor, "period", period, "last", last);
    }

    /** The 2nd of the month after {@code month} at 00:37 (the monthly run's time). */
    static Instant runTimeAfter(String month) {
        return YearMonth.parse(month).plusMonths(1).atDay(2).atTime(0, 37).atZone(ZONE).toInstant();
    }

    static List<Map<String, Object>> distributionCharges(String sponsor) {
        return query("SELECT * FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = 'DISTRIBUTION' "
            + "ORDER BY charged_at, charge_id", sponsor);
    }

    @Test
    void theSameItemRunTwiceAtOnceWritesOneRowAndChargesOnce() {
        autoApprove(true);
        distribution(true);
        String sponsor = distributionSponsor("fm6-twice");
        cash(sponsor, 1_000);
        String publicationId = publication(sponsor, "FM6 twice", Map.of());
        starts(publicationId, 2, "fm6-twice");
        String month = period(clock.instant());
        clock.set(runTimeAfter(month));
        Map<String, Object> ref = settleRef(publicationId, sponsor, month, false);
        List<Object> outcomes = atOnce(() -> systemRunItem(DistributionSettlements.SETTLE, ref),
            () -> systemRunItem(DistributionSettlements.SETTLE, ref));
        assertThat(outcomes).as("at least one ran").anyMatch(o -> !String.valueOf(o).startsWith("failed"));
        assertThat(settlements(publicationId)).singleElement()
            .satisfies(row -> assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("10"));
        assertThat(distributionCharges(sponsor)).hasSize(1);
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void theMonthlyAndTheFinalSettlementAtOnceWriteOneFinalRowAndGiveBackOnce() {
        autoApprove(true);
        distribution(true);
        String sponsor = distributionSponsor("fm6-overlap");
        cash(sponsor, 1_000);
        String publicationId = publication(sponsor, "FM6 overlap", Map.of());
        starts(publicationId, 1, "fm6-overlap");
        String month = period(clock.instant());
        end(publicationId);
        // Both see it ended past the grace: the monthly item writes it final, so does the daily one.
        clock.set(runTimeAfter(month));
        atOnce(() -> systemRunItem(DistributionSettlements.SETTLE, settleRef(publicationId, sponsor, month, false)),
            () -> systemRunItem(DistributionSettlements.SETTLE, settleRef(publicationId, sponsor, null, true)));
        assertThat(settlements(publicationId)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("final", true);
            assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("5");
        });
        assertThat(distributionCharges(sponsor)).hasSize(1);
        Map<String, Object> deposit = distributionPrepayment(publicationId);
        assertThat(deposit).containsEntry("status", "RELEASED");
        assertThat(query("SELECT 1 FROM qb_fee_payment_version WHERE target_id = ? AND amount < 0",
            String.valueOf(deposit.get("prepayment_id")))).hasSize(1);
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void theDistributionDepositWaitsForItsFinalRowWhileTheAudienceOneIsGivenBackByItself() {
        autoApprove(true);
        distribution(true);
        String sponsor = distributionSponsor("fm6-purposes");
        grant(sponsor, 10_000, clock.instant().plus(Duration.ofDays(400)));
        String publicationId = audienceDraft(sponsor, "FM6 purposes");
        submit(sponsorToken(sponsor), publicationId);
        goLive(publicationId);
        end(publicationId);
        // The daily release job's item for the distribution deposit before its final row: nothing.
        systemRun(com.jabiz.quizbuks.fee.Prepayments.RELEASE_ITEM, body("publicationId", publicationId,
            "sponsorId", sponsor, "purpose", "DISTRIBUTION"));
        assertThat(distributionPrepayment(publicationId)).containsEntry("status", "OPEN");
        // The final settlement gives back the distribution deposit only: the audience one stays open for its job.
        finalRun(clock.instant().plus(Duration.ofHours(2)));
        assertThat(distributionPrepayment(publicationId)).containsEntry("status", "RELEASED");
        assertThat(query("SELECT status FROM qb_prepayment WHERE publication_id = ? AND purpose = 'AUDIENCE'",
            publicationId)).singleElement().satisfies(row -> assertThat(row).containsEntry("status", "OPEN"));
        systemRun(com.jabiz.quizbuks.fee.Prepayments.RELEASE_ITEM, body("publicationId", publicationId,
            "sponsorId", sponsor, "purpose", "AUDIENCE"));
        assertThat(query("SELECT status FROM qb_prepayment WHERE publication_id = ? AND purpose = 'AUDIENCE'",
            publicationId)).singleElement().satisfies(row -> assertThat(row).containsEntry("status", "RELEASED"));
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
    }

    @Test
    void beyondTheDepositTheFeeCreditThenCashPayAndTheRestIsOwedPartlyThenWhole() {
        autoApprove(true);
        distribution(true);
        String sponsor = distributionSponsor("fm6-sources");
        // Two winners at most: 10 and 1 tax set aside, of the fee credit (20), which keeps 9.
        grant(sponsor, 20, clock.instant().plus(Duration.ofDays(400)));
        cash(sponsor, 30);
        String publicationId = publication(sponsor, "FM6 sources", Map.of("maxWinners", 2, "endAt",
            clock.instant().plus(Duration.ofDays(70)).toString()));
        assertThat((BigDecimal) distributionPrepayment(publicationId).get("amount")).isEqualByComparingTo("11");
        // Ten starts (each fails: no winner's place is kept): 50 and 5 tax = 55, of the deposit (11), the fee credit
        // (9), the cash (30); 5 owed.
        failedStarts(publicationId, 10, "fm6-sources");
        String month = period(clock.instant());
        monthly();
        Map<String, Object> first = distributionCharges(sponsor).getFirst();
        List<Map<String, Object>> parts = query("SELECT sub_account, prepayment_id, amount FROM qb_fee_payment_version "
            + "WHERE target_id = ? AND amount > 0", String.valueOf(first.get("charge_id")));
        assertThat(parts.stream().filter(p -> p.get("prepayment_id") != null).map(p -> (BigDecimal) p.get("amount"))
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("11");
        assertThat(parts.stream().filter(p -> p.get("prepayment_id") == null && "FEE_CREDIT".equals(p.get("sub_account")))
            .map(p -> (BigDecimal) p.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("9");
        assertThat(parts.stream().filter(p -> "CASH".equals(p.get("sub_account")))
            .map(p -> (BigDecimal) p.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("30");
        // F-M13: the deposit's 11 (of the fee credit), the fee credit's 9 and the cash's 30; 5 are owed.
        String partialInvoice = String.valueOf(query("SELECT invoice_id FROM qb_fee_invoice WHERE charge_id = ?",
            String.valueOf(first.get("charge_id"))).getFirst().get("invoice_id"));
        assertThat(items("qb.sponsor.fee-invoice-sources", sponsorToken(sponsor), Map.of("params",
            Map.of("invoiceId", partialInvoice)))).singleElement().satisfies(sources -> assertThat(sources)
            .containsEntry("sourcePrepayment", 11).containsEntry("sourcePrepaymentGift", 11)
            .containsEntry("sourceGift", 9).containsEntry("sourceCash", 30).containsEntry("sourceUnpaid", 5));
        assertThat(query("SELECT status, total, paid_amount FROM qb_fee_invoice WHERE charge_id = ?",
            String.valueOf(first.get("charge_id")))).singleElement().satisfies(invoice -> {
                assertThat(invoice).containsEntry("status", "PARTIAL");
                assertThat((BigDecimal) invoice.get("total")).isEqualByComparingTo("55");
                assertThat((BigDecimal) invoice.get("paid_amount")).isEqualByComparingTo("50");
            });
        // The next month: a start more, nothing left to pay from: the invoice is open, nothing paid.
        failedStarts(publicationId, 1, "fm6-sources-next");
        monthlyIn(YearMonth.parse(month).plusMonths(2));
        Map<String, Object> second = distributionCharges(sponsor).getLast();
        assertThat(second.get("charge_id")).isNotEqualTo(first.get("charge_id"));
        assertThat(query("SELECT status, paid_amount FROM qb_fee_invoice WHERE charge_id = ?",
            String.valueOf(second.get("charge_id")))).singleElement().satisfies(invoice -> {
                assertThat(invoice).containsEntry("status", "OPEN");
                assertThat((BigDecimal) invoice.get("paid_amount")).isEqualByComparingTo("0");
            });
    }

    @Test
    void aPriceBelowAYenAStartIsChargedOnTheCumulativeCountMonthByMonth() {
        autoApprove(true);
        distribution(true);
        String sponsor = distributionSponsor("fm6-rounding");
        cash(sponsor, 1_000);
        // 0.4 a start (in the table: the range's least floor is 1): 1 start, 1 more, 1 more → 1, 0, 1.
        execute("UPDATE qb_distribution_price SET rate = 0.004, unit_floor = 0.1 WHERE operator_id = ?",
            operatorOf(sponsor));
        YearMonth first = YearMonth.parse(period(clock.instant()));
        String publicationId = publication(sponsor, "FM6 rounding", Map.of("endAt",
            first.plusMonths(3).atDay(20).atStartOfDay(ZONE).toInstant().toString()));
        assertThat((BigDecimal) row(publicationId).get("distribution_unit")).isEqualByComparingTo("0.4");
        for (int i = 1; i <= 3; i++) {
            starts(publicationId, 1, "fm6-rounding-" + i);
            monthlyIn(first.plusMonths(i));
        }
        assertThat(settlements(publicationId)).extracting(row -> ((BigDecimal) row.get("amount")).intValue())
            .containsExactly(1, 0, 1);
        assertThat(settlements(publicationId)).extracting(row -> row.get("charge_id") != null)
            .containsExactly(true, false, true);
        assertThat(distributionCharges(sponsor)).hasSize(2);
    }

    @Test
    void theFrozenOperatorAndPriceHoldAfterAReassignmentAPriceChangeAndAVersionSwitch() {
        autoApprove(true);
        distribution(true);
        String sponsor = distributionSponsor("fm6-frozen");
        cash(sponsor, 5_000);
        String operatorA = operatorOf(sponsor);
        String before = publication(sponsor, "FM6 frozen", Map.of());
        assertThat((BigDecimal) row(before).get("distribution_unit")).isEqualByComparingTo("5");

        // The quiz's version switched: the price stays.
        String token = sponsorToken(sponsor);
        String quizId = String.valueOf(query("SELECT quiz_id FROM qb_quiz_version_version WHERE version_id = ?::uuid",
            String.valueOf(row(before).get("version_id"))).getFirst().get("quiz_id"));
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "FM6 frozen revised", "timeLimitSec", 300));
        String second = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        run(com.jabiz.quizbuks.publishing.PublicationVersions.SWITCH, token, body("publicationId", before,
            "versionId", second));
        assertThat(String.valueOf(row(before).get("version_id"))).isEqualTo(second);
        assertThat((BigDecimal) row(before).get("distribution_unit")).isEqualByComparingTo("5");

        // A's price doubled: only what is submitted after takes it.
        run(DistributionPrices.SET, TestTokens.bearer(tokens, "fm6-hq", QbPermissions.FEE_MANAGE), body("operatorId",
            operatorA, "rate", "0.1", "unitFloor", "1", "unitCeiling", "20", "minimumDeposit", 500, "reason", "Up"));
        String afterPrice = publication(sponsor, "FM6 after price", Map.of());
        assertThat((BigDecimal) row(afterPrice).get("distribution_unit")).isEqualByComparingTo("10");
        assertThat((BigDecimal) row(before).get("distribution_unit")).isEqualByComparingTo("5");

        // Moved to operator B (another price): the fees of what was submitted stay A's at A's frozen price.
        String hq = TestTokens.bearer(tokens, "fm6-ops4", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
        String operatorB = newOperator(hq, unique("fm6 operator b"));
        run(DistributionPrices.SET, TestTokens.bearer(tokens, "fm6-hq", QbPermissions.FEE_MANAGE), body("operatorId",
            operatorB, "rate", "0.15", "unitFloor", "1", "unitCeiling", "20", "minimumDeposit", 500, "reason", "B"));
        run(com.jabiz.quizbuks.operator.OperatorProcesses.ASSIGN, hq, body("userId", sponsor, "operatorId", operatorB,
            "reason", "F-M6"));
        String afterMove = publication(sponsor, "FM6 after move", Map.of());
        assertThat((BigDecimal) row(afterMove).get("distribution_unit")).isEqualByComparingTo("15");
        starts(before, 2, "fm6-frozen");
        monthly();
        assertThat(settlements(before)).singleElement()
            .satisfies(row -> assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("10"));
        assertThat(query("SELECT c.operator_id FROM qb_fee_charge_version c JOIN qb_distribution_settlement_version s "
            + "ON s.charge_id = CAST(c.charge_id AS text) WHERE s.publication_id = ?", before)).singleElement()
            .satisfies(charge -> assertThat(charge).containsEntry("operator_id", operatorA));
        assertThat(distributionPrepayment(afterMove)).containsEntry("operator_id", operatorB);
    }

    @Test
    void theMonthEndsInJapanAndAStartBeforeTheMonthlyRunCountsInTheMonthBefore() {
        // M1 (review, accepted): the month's row takes the counter as the run on the 2nd at 00:37 reads it.
        autoApprove(true);
        distribution(true);
        String sponsor = distributionSponsor("fm6-jst");
        cash(sponsor, 1_000);
        YearMonth month = YearMonth.parse(period(clock.instant()));
        Instant evening = month.atEndOfMonth().atTime(20, 0).atZone(ZONE).toInstant();
        if (!clock.instant().isBefore(evening)) {
            month = month.plusMonths(1);
            evening = month.atEndOfMonth().atTime(20, 0).atZone(ZONE).toInstant();
        }
        clock.set(evening);
        String publicationId = publication(sponsor, "FM6 JST", Map.of());
        Instant lastMinute = month.atEndOfMonth().atTime(23, 59).atZone(ZONE).toInstant();
        Instant midnight = month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant();
        // In UTC both are the same day; in Japan they are two months.
        assertThat(lastMinute.atZone(java.time.ZoneOffset.UTC).getMonth())
            .isEqualTo(midnight.atZone(java.time.ZoneOffset.UTC).getMonth());
        assertThat(period(lastMinute)).isEqualTo(month.toString());
        assertThat(period(midnight)).isEqualTo(month.plusMonths(1).toString());
        clock.set(lastMinute);
        starts(publicationId, 1, "fm6-jst-a");
        clock.set(midnight);
        starts(publicationId, 1, "fm6-jst-b");
        monthlyIn(month.plusMonths(1));
        String settled = month.toString();
        assertThat(settlements(publicationId)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("period", settled);
            assertThat(((Number) row.get("starts")).longValue()).isEqualTo(2);
        });
    }

    @Test
    void aMonthSettledTwoMonthsLateIsInvoicedOnTheNextRunAndNeverOnTheFirst() {
        autoApprove(true);
        distribution(true);
        String sponsor = distributionSponsor("fm6-late");
        billing(sponsor, "POSTPAID", 100_000);
        String a = publication(sponsor, "FM6 late A", Map.of());
        String b = publication(sponsor, "FM6 late B", Map.of());
        String month = period(clock.instant());
        starts(a, 1, "fm6-late-a");
        starts(b, 1, "fm6-late-b");
        // Two months on, A settled and B not: the month's invoice waits.
        YearMonth late = YearMonth.parse(month).plusMonths(2);
        clock.set(late.atDay(3).atTime(9, 0).atZone(ZONE).toInstant());
        systemRun(DistributionSettlements.SETTLE, settleRef(a, sponsor, month, false));
        assertThat(invoicesDue(late.toString())).noneMatch(row -> sponsor.equals(row.get("sponsorId")));
        systemRun(DistributionSettlements.SETTLE, settleRef(b, sponsor, month, false));
        assertThat(invoicesDue(late.toString())).filteredOn(row -> sponsor.equals(row.get("sponsorId")))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("period", month));
        assertThat(systemRunItem(FeeProcesses.INVOICE_ITEM, body("sponsorId", sponsor, "period", month)))
            .isEqualTo("ISSUED");
        // The job never runs on the 1st: from the last day of a month its next run is the 2nd at 01:17.
        var cron = org.springframework.scheduling.support.CronExpression.parse(qbFeeInvoiceJob.cron());
        java.time.ZonedDateTime at = late.atEndOfMonth().atTime(12, 0).atZone(ZONE);
        assertThat(cron.next(at)).isEqualTo(late.plusMonths(1).atDay(2).atTime(1, 17).atZone(ZONE));
        for (int i = 0; i < 70; i++) {
            at = cron.next(at);
            assertThat(at.getDayOfMonth()).isNotEqualTo(1);
        }
    }

    @Test
    void aPostpaidSponsorsDepositAndItsSettledFeeTakeTheCreditLimitOnce() {
        autoApprove(true);
        distribution(true);
        String sponsor = distributionSponsor("fm6-limit");
        // 165 held for the first; after 16 is charged, 16 is owed and 149 held: 165 of 335, 170 left.
        billing(sponsor, "POSTPAID", 335);
        String first = publication(sponsor, "FM6 limit", Map.of());
        starts(first, 3, "fm6-limit");
        monthly();
        assertThat(ledger("1130", sponsor)).isEqualByComparingTo("-16");
        assertThat((BigDecimal) distributionPrepayment(first).get("remaining")).isEqualByComparingTo("149");
        String token = sponsorToken(sponsor);
        Map<String, Object> save = complete(version(token, "FM6 limit second quiz"), "FM6 limit second",
            clock.instant(), clock.instant().plus(Duration.ofDays(7)));
        save.put("maxWinners", 30);
        save.put("regions", List.of("GLOBAL"));
        String second = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        Map<String, Object> funds = check(sponsor, second);
        assertThat(dec(funds.get("available"))).isEqualByComparingTo("170");
        assertThat(dec(funds.get("total"))).isEqualByComparingTo("165");
        submit(token, second);
        assertThat(distributionPrepayment(second)).containsEntry("mode", "POSTPAID");
    }

    @Test
    void approvedInTheNextMonthItIsDueFromItsApprovalNotFromItsStart() {
        // N1 (Fable's second review): submitted on the last day of a month to start at once, approved on the 2nd of the
        // next (after that month's run): the month it was not on is never due, so neither the check nor the postpaid
        // invoice waits for a row that cannot come.
        autoApprove(false);
        distribution(true);
        String sponsor = reviewedSponsor("fm6-late-approval");
        distributionPrice(operatorOf(sponsor));
        credit(sponsor, 5_000);
        billing(sponsor, "POSTPAID", 100_000);
        YearMonth month = YearMonth.parse(period(clock.instant()));
        Instant evening = month.atEndOfMonth().atTime(20, 0).atZone(ZONE).toInstant();
        if (!clock.instant().isBefore(evening)) {
            month = month.plusMonths(1);
            evening = month.atEndOfMonth().atTime(20, 0).atZone(ZONE).toInstant();
        }
        clock.set(evening);
        String token = sponsorToken(sponsor);
        // One approved at once, live in the month, with a fee of the month (the month's invoice waits for its due).
        Map<String, Object> on = complete(version(token, "FM6 on time quiz"), "FM6 on time", clock.instant(),
            clock.instant().plus(Duration.ofDays(7)));
        on.put("maxWinners", 30);
        on.put("regions", List.of("GLOBAL"));
        String onTime = (String) run("QB_PUBLICATION_SAVE", token, on).get("publicationId");
        decide(String.valueOf(submit(token, onTime).get("approvalRequestId")), "APPROVE", null);
        starts(onTime, 1, "fm6-late-approval");
        // The other submitted now, starting now, approved only on the 2nd.
        Map<String, Object> save = complete(version(token, "FM6 late quiz"), "FM6 late", clock.instant(),
            clock.instant().plus(Duration.ofDays(7)));
        save.put("maxWinners", 30);
        save.put("regions", List.of("GLOBAL"));
        String late = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        String requestId = String.valueOf(submit(token, late).get("approvalRequestId"));
        String settled = month.toString();
        monthlyIn(month.plusMonths(1));
        assertThat(settlements(onTime)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("period", settled));
        clock.set(month.plusMonths(1).atDay(2).atTime(10, 0).atZone(ZONE).toInstant());
        decide(requestId, "APPROVE", null);
        assertThat(status(late)).isEqualTo("LIVE");

        // A day on: not due for the month before its approval, nothing reported, the month's invoice issued.
        clock.set(month.plusMonths(1).atDay(3).atTime(10, 0).atZone(ZONE).toInstant());
        String last = month.plusMonths(1).toString();
        assertThat(items(DistributionSettlements.DUE_TEMPLATE, TestTokens.bearer(tokens, "fm6-system",
            QbPermissions.FEE_SYSTEM), Map.of("params", Map.of("period", settled, "periodStart",
            month.atDay(1).atStartOfDay(ZONE).toInstant().toString(), "periodEnd",
            month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant().toString()))))
            .noneMatch(row -> late.equals(row.get("publicationId")));
        assertThat(differences(sponsor)).isEmpty();
        assertThat(invoicesDue(last)).filteredOn(row -> sponsor.equals(row.get("sponsorId"))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("period", settled));
        assertThat(settlements(late)).isEmpty();
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
    void theDirectSponsorPaysAFailedAndAnUnfinishedStartCountAndTheTakersReadTheSame() {
        autoApprove(true);
        // The operator's direct sponsor (its lead's own account) pays the fee like any other (the requester,
        // 2026-11-12).
        NewOperator operator = newOperatorWithLead(TestTokens.bearer(tokens, "fm6-ops3", QbPermissions.OPERATORS_WRITE,
            QbPermissions.OPERATORS_READ), unique("fm6 direct"), null);
        String direct = String.valueOf(query("SELECT direct_sponsor_id FROM qb_operator WHERE operator_id = ?",
            operator.operatorId()).getFirst().get("direct_sponsor_id"));
        distributionPrice(operator.operatorId());
        credit(direct, 5_000);
        cash(direct, 1_000);
        distribution(false);
        String free = publication(direct, "FM6 free", Map.of());
        distribution(true);
        String priced = publication(direct, "FM6 priced", Map.of());
        assertThat(row(free).get("distribution_unit")).isNull();
        assertThat((BigDecimal) row(priced).get("distribution_unit")).isEqualByComparingTo("5");

        // What a taker reads of the two is the same but for which publication it is: the fee is not the taker's.
        String reader = taker(newTaker(unique("fm6-reader"), null));
        for (String template : List.of("qb.app.publication", "qb.app.publication-tiers")) {
            assertThat(takerView(template, reader, priced)).as(template)
                .isEqualTo(takerView(template, reader, free));
        }

        // A taker who answers wrong and one who never finishes: both are starts.
        String failing = taker(newTaker(unique("fm6-wrong"), null));
        String attemptId = String.valueOf(start(failing, priced).get("attemptId"));
        assertThat(submit(failing, attemptId, answers(false, false))).containsEntry("passed", false);
        starts(priced, 1, "fm6-unfinished");
        assertThat(monthly().outcome()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(settlements(priced)).singleElement().satisfies(row -> {
            assertThat(((Number) row.get("starts")).longValue()).isEqualTo(2);
            assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("10");
        });
        assertThat(settlements(free)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = 'DISTRIBUTION'",
            direct)).hasSize(1);
    }

    /** A taker's rows of a publication's page, without what names the publication (its ids and names). */
    private List<Map<String, Object>> takerView(String template, String token, String publicationId) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("publicationId", publicationId);
        params.put("at", clock.instant().toString());
        params.put("country", "JP");
        Set<String> naming = Set.of("publicationId", "name", "quizId", "quizTitle", "versionId", "title", "tierId");
        return items(template, token, Map.of("params", params)).stream().map(row -> {
            Map<String, Object> kept = new java.util.TreeMap<>(row);
            kept.keySet().removeAll(naming);
            return (Map<String, Object>) kept;
        }).toList();
    }

    @Test
    void aSponsorWhoErasesTheirAccountIsStillSettledFromTheDeposit() {
        // M3 (review): the onboarding row goes with the account (SponsorErasure), the publication ends with it; the
        // deposit already holds the money, so the final settlement charges from it and gives the rest back.
        autoApprove(true);
        distribution(true);
        String sponsor = distributionSponsor("fm6-erased");
        cash(sponsor, 1_000);
        String publicationId = publication(sponsor, "FM6 erased", Map.of());
        starts(publicationId, 2, "fm6-erased");
        runAsSystem("ACCOUNT_ERASE", Map.of("userId", sponsor, "requestedBy", "it-support"));
        deliverEvents();
        assertThat(status(publicationId)).isEqualTo("ENDED");
        Instant endedAt = clock.instant();
        assertThat(finalRun(endedAt.plus(Duration.ofHours(2))).outcome()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(settlements(publicationId)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("final", true);
            assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("10");
        });
        assertThat(distributionPrepayment(publicationId)).containsEntry("status", "RELEASED");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
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

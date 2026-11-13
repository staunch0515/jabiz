package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.fee.FeeCredits;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform fee Kudos granted to sponsors (F-M2): the onboarding a lead of the sponsor's operator confirms (off by
 * default; once; never to a sponsor not approved or banned), the grant by hand with another administrator's approval
 * and within its cap, one lot per source key, the operator of the time recorded, the daily expiry voiding only what is
 * left (2190 back to 5140), the reminder once a lot and once in 24 hours, the monthly report's two kinds and the daily
 * check of the promotion expenses by operator.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FeeCreditGrantIT extends PlayItSupport {

    @Autowired
    @Qualifier("qbFeeCreditExpireJob")
    JobDefinition<FeeCredits.GrantRef> expireJob;

    @Autowired
    @Qualifier("qbFeeCreditRemindJob")
    JobDefinition<FeeCredits.SponsorRef> remindJob;

    private static boolean switched;

    void switchOn() {
        if (!switched) {
            param(QbParams.FEE_CREDIT_GRANT_ENABLED, "true");
            switched = true;
        }
    }

    void param(String key, String value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("paramKey", key);
        values.put("value", value);
        if (QbParams.FEE_CREDIT_MANUAL_MAX.equals(key)) {
            values.put("valueKind", QbParams.FEE_CREDIT_MANUAL_MAX_KIND);
        }
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", values, "reason", "F-M2 test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    String finance() {
        return tokenOf(user("fm2-finance"), QbRoles.ADMIN_FINANCE);
    }

    String content() {
        return tokenOf(user("fm2-content"), QbRoles.ADMIN_CONTENT);
    }

    String hq() {
        return TestTokens.bearer(tokens, "fm2-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    void approve(String requestId) {
        run("APPROVAL_DECIDE", content(), body("requestId", requestId, "decision", "APPROVE"));
        deliverEvents();
    }

    /** A sponsor of a new operator of its own, and the sessions of its lead and of a member who is not. */
    record Setting(String sponsor, String operatorId, String lead, String member) {}

    Setting setting(String name) {
        NewOperator operator = newOperatorWithLead(operatorsAdmin(), unique("FM2 " + name), null);
        String member = verifiedUser(unique("fm2-member"));
        run(OperatorProcesses.MEMBER_ADD, operatorsAdmin(), body("operatorId", operator.operatorId(), "userId", member,
            "lead", false));
        String sponsor = newSponsor(unique("s-fm2-" + name));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", operator.operatorId(), "reason",
            "F-M2"));
        return new Setting(sponsor, operator.operatorId(), memberSession(operator.leadId(), operator.operatorId()),
            memberSession(member, operator.operatorId()));
    }

    static List<Map<String, Object>> lots(String sponsor) {
        return query("SELECT * FROM qb_fee_credit_grant WHERE sponsor_id = ? ORDER BY granted_at, grant_id", sponsor);
    }

    BigDecimal walletBalance(String sponsor, String kind) {
        List<Map<String, Object>> rows = query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = ?",
            sponsor, kind);
        return rows.isEmpty() ? BigDecimal.ZERO : (BigDecimal) rows.getFirst().get("balance");
    }

    static List<String> lines(String grantId) {
        return query("SELECT a.account_code, e.direction, e.amount, e.dimension_1 FROM ledger_transaction_version t "
            + "JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id "
            + "JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE t.source_entity = 'QbFeeCreditGrant' AND t.source_id = ? ORDER BY t.process_seq_id, e.line_no",
            grantId).stream().map(p -> p.get("account_code") + " " + p.get("direction") + " "
            + ((BigDecimal) p.get("amount")).stripTrailingZeros().toPlainString()
            + (p.get("dimension_1") == null ? "" : " @" + p.get("dimension_1"))).toList();
    }

    /** What the daily check of the fee account finds (none: it agrees). */
    List<Map<String, Object>> differences() {
        return feeAccountItems(TestTokens.bearer(tokens, "recon-test",
            QbPermissions.WALLET_SYSTEM), Map.of());
    }

    JobRunner.JobRun runJob(JobDefinition<?> job, Instant at) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.execute(job, at);
    }

    static long mails(String sponsor) {
        return templatesTo(sponsor).stream().filter("qb.fee-credit.expiring"::equals).count();
    }

    @Test
    @Order(1)
    void theOnboardingIsOffUntilSwitchedOn() {
        Setting s = setting("off");
        assertThat(codes(refused(FeeCredits.ONBOARDING, s.lead(), body("sponsorId", s.sponsor()), 422)))
            .containsExactly(FeeCodes.FEE_CREDIT_OFF);
        assertThat(lots(s.sponsor())).isEmpty();
        assertThat(walletBalance(s.sponsor(), QbWallets.FEE_CREDIT)).isEqualByComparingTo("0");
    }

    @Test
    @Order(2)
    void aLeadOfTheSponsorsOperatorConfirmsTheOnboardingOnce() {
        switchOn();
        Setting s = setting("onboard");
        // Not a lead, and not the operator's sponsor: refused.
        assertThat(codes(refused(FeeCredits.ONBOARDING, s.member(), body("sponsorId", s.sponsor()), 422)))
            .containsExactly(FeeCodes.FEE_CREDIT_LEAD);
        Setting other = setting("onboard-other");
        refused(FeeCredits.ONBOARDING, other.lead(), body("sponsorId", s.sponsor()), 404);

        Map<String, Object> granted = run(FeeCredits.ONBOARDING, s.lead(), body("sponsorId", s.sponsor()));
        assertThat(granted).containsEntry("granted", true).containsEntry("operatorId", s.operatorId());
        Map<String, Object> lot = lots(s.sponsor()).getFirst();
        assertThat(lot).containsEntry("source", "ONBOARDING").containsEntry("source_key", "onboarding")
            .containsEntry("operator_id", s.operatorId());
        assertThat((BigDecimal) lot.get("amount")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) lot.get("remaining")).isEqualByComparingTo("1000");
        assertThat(((Timestamp) lot.get("expires_at")).toInstant())
            .isEqualTo(((Timestamp) lot.get("granted_at")).toInstant().plus(Duration.ofDays(90)));
        assertThat(walletBalance(s.sponsor(), QbWallets.FEE_CREDIT)).isEqualByComparingTo("1000");
        String grantId = String.valueOf(lot.get("grant_id"));
        assertThat(lines(grantId)).containsExactly("5140 DEBIT 1000 @" + s.sponsor(), "2190 CREDIT 1000 @" + s.sponsor());
        assertThat(ledger("2190", s.sponsor())).isEqualByComparingTo("1000");

        // Confirmed again: refused, nothing more.
        assertThat(codes(refused(FeeCredits.ONBOARDING, s.lead(), body("sponsorId", s.sponsor()), 422)))
            .containsExactly(FeeCodes.FEE_CREDIT_GIVEN);
        assertThat(lots(s.sponsor())).hasSize(1);

        // A1-4: moved to another operator afterwards, the lot keeps the operator of the time.
        run(OperatorProcesses.ASSIGN, hq(), body("userId", s.sponsor(), "operatorId", other.operatorId(), "reason",
            "Moved"));
        assertThat(lots(s.sponsor()).getFirst()).containsEntry("operator_id", s.operatorId());
        assertThat(differences()).isEmpty();
        assertBooksBalance();
    }

    @Test
    @Order(3)
    void aBannedSponsorOrOneNotApprovedIsNotGranted() {
        switchOn();
        Setting s = setting("banned");
        String boss = userWithRole(unique("fm2-boss"), QbRoles.ADMIN_SUPER);
        run("QB_USER_BAN", tokenOf(boss, QbRoles.ADMIN_SUPER), body("userId", s.sponsor(), "reason", "Fraud"));
        assertThat(codes(refused(FeeCredits.ONBOARDING, s.lead(), body("sponsorId", s.sponsor()), 422)))
            .containsExactly(FeeCodes.FEE_CREDIT_SPONSOR);
        assertThat(lots(s.sponsor())).isEmpty();
    }

    @Test
    @Order(4)
    void aGrantByHandTakesAnotherAdministratorsApprovalWithinTheCap() {
        Setting s = setting("manual");
        Map<String, Object> requested = run(FeeRequests.RECORD, finance(), body("kind", "CREDIT", "sponsorId",
            s.sponsor(), "amount", 3000, "validDays", 30, "reason", "Event partner"));
        assertThat(requested).containsEntry("status", QbFees.REVIEW);
        assertThat(lots(s.sponsor())).isEmpty();
        approve((String) requested.get("approvalRequestId"));
        Map<String, Object> request = query("SELECT * FROM qb_fee_request WHERE request_id = ?",
            requested.get("requestId")).getFirst();
        assertThat(request).containsEntry("status", QbFees.DONE);
        Map<String, Object> lot = lots(s.sponsor()).getFirst();
        assertThat(request.get("grant_id")).isEqualTo(String.valueOf(lot.get("grant_id")));
        assertThat(lot).containsEntry("source", "MANUAL").containsEntry("source_key",
            "manual:" + requested.get("requestId")).containsEntry("operator_id", s.operatorId());
        assertThat((BigDecimal) lot.get("amount")).isEqualByComparingTo("3000");
        assertThat(((Timestamp) lot.get("expires_at")).toInstant())
            .isEqualTo(((Timestamp) lot.get("granted_at")).toInstant().plus(Duration.ofDays(30)));
        assertThat(walletBalance(s.sponsor(), QbWallets.FEE_CREDIT)).isEqualByComparingTo("3000");

        // Rejected: nothing granted.
        Map<String, Object> rejected = run(FeeRequests.RECORD, finance(), body("kind", "CREDIT", "sponsorId",
            s.sponsor(), "amount", 500, "reason", "Nope"));
        run("APPROVAL_DECIDE", content(), body("requestId", rejected.get("approvalRequestId"), "decision", "REJECT",
            "reason", "Not agreed"));
        deliverEvents();
        assertThat(query("SELECT status FROM qb_fee_request WHERE request_id = ?", rejected.get("requestId"))
            .getFirst()).containsEntry("status", QbFees.REJECTED);

        // A cap set: above it refused at the request; a request within it whose cap was lowered before the approval
        // is rejected at the approval.
        Map<String, Object> within = run(FeeRequests.RECORD, finance(), body("kind", "CREDIT", "sponsorId",
            s.sponsor(), "amount", 2000, "reason", "Within"));
        param(QbParams.FEE_CREDIT_MANUAL_MAX, "1500");
        assertThat(codes(refused(FeeRequests.RECORD, finance(), body("kind", "CREDIT", "sponsorId", s.sponsor(),
            "amount", 1501, "reason", "Above"), 422))).containsExactly(FeeCodes.FEE_CREDIT_MAX);
        approve((String) within.get("approvalRequestId"));
        assertThat(query("SELECT status, grant_id FROM qb_fee_request WHERE request_id = ?", within.get("requestId"))
            .getFirst()).containsEntry("status", QbFees.REJECTED).containsEntry("grant_id", null);
        assertThat(lots(s.sponsor())).hasSize(1);
        param(QbParams.FEE_CREDIT_MANUAL_MAX, "100000");
        assertThat(differences()).isEmpty();
    }

    @Test
    @Order(5)
    void theGrantIsOncePerSourceKey() {
        Setting s = setting("idem");
        Map<String, Object> request = run(FeeRequests.RECORD, finance(), body("kind", "CREDIT", "sponsorId",
            s.sponsor(), "amount", 700, "reason", "Twice?"));
        approve((String) request.get("approvalRequestId"));
        String grantId = String.valueOf(lots(s.sponsor()).getFirst().get("grant_id"));
        // The approval's result applied again to the request as if it were still under review (a consumer retried
        // after its own failure): the grant finds the lot of the same source key and grants nothing more.
        execute("UPDATE qb_fee_request SET status = 'REVIEW', grant_id = NULL, version = version + 1 "
            + "WHERE request_id = ?", request.get("requestId"));
        Map<String, Object> approval = query("SELECT DISTINCT ON (request_id) * FROM sys_approval_request_version "
            + "WHERE request_id = ?::uuid ORDER BY request_id, version_no DESC", request.get("approvalRequestId"))
            .getFirst();
        Object again = systemRun(FeeRequests.APPROVAL_RESULT, body("subject", QbFees.APPROVAL_SUBJECT, "entityId",
            request.get("requestId"), "status", "APPROVED", "requestId", request.get("approvalRequestId"),
            "preparerId", approval.get("preparer_id"), "contentHash", String.valueOf(approval.get("content_hash")).strip()));
        assertThat(((FeeRequests.RequestOutput) again).grantId()).isEqualTo(grantId);
        assertThat(lots(s.sponsor())).hasSize(1);
        assertThat(walletBalance(s.sponsor(), QbWallets.FEE_CREDIT)).isEqualByComparingTo("700");
        assertThat(ledger("2190", s.sponsor())).isEqualByComparingTo("700");
        assertThat(lines(grantId)).hasSize(2);
    }

    @Test
    @Order(6)
    void anExpiredLotVoidsOnlyWhatIsLeftAndItsSponsorIsRemindedFirst() {
        switchOn();
        Setting s = setting("expire");
        run(FeeCredits.ONBOARDING, s.lead(), body("sponsorId", s.sponsor()));
        Map<String, Object> lot = lots(s.sponsor()).getFirst();
        String grantId = String.valueOf(lot.get("grant_id"));
        Instant expiresAt = ((Timestamp) lot.get("expires_at")).toInstant();

        // Spent in part: a fee by hand of 300 (330 with tax), prepaid, paid from the fee credit first.
        Map<String, Object> fee = run(FeeRequests.RECORD, finance(), body("kind", "MANUAL", "sponsorId", s.sponsor(),
            "amount", 300, "reason", "Report"));
        approve((String) fee.get("approvalRequestId"));
        assertThat((BigDecimal) lots(s.sponsor()).getFirst().get("remaining")).isEqualByComparingTo("670");

        // Fifteen days before: not yet within the notice (14).
        assertThat(runJob(remindJob, expiresAt.minus(Duration.ofDays(15))).items().selected()).isZero();
        // Ten days before: reminded once.
        clock.set(expiresAt.minus(Duration.ofDays(10)));
        runJob(remindJob, clock.instant());
        deliverEvents();
        assertThat(mails(s.sponsor())).isEqualTo(1);
        assertThat(lots(s.sponsor()).getFirst().get("reminded_at")).isNotNull();
        // The next day: the lot was told of already.
        clock.set(expiresAt.minus(Duration.ofDays(9)));
        runJob(remindJob, clock.instant());
        deliverEvents();
        assertThat(mails(s.sponsor())).isEqualTo(1);
        // A day after the first mail, a new lot expiring soon: told of (24 hours have passed).
        Map<String, Object> soon = run(FeeRequests.RECORD, finance(), body("kind", "CREDIT", "sponsorId", s.sponsor(),
            "amount", 200, "validDays", 3, "reason", "Short"));
        approve((String) soon.get("approvalRequestId"));
        assertThat(systemRun(FeeCredits.REMIND_ITEM, body("sponsorId", s.sponsor())))
            .isEqualTo(new FeeProcesses.ItemOutput(s.sponsor(), "REMINDED", null));
        deliverEvents();
        assertThat(mails(s.sponsor())).isEqualTo(2);
        // An hour later another: within 24 hours of the last mail, not told of yet.
        clock.set(clock.instant().plus(Duration.ofHours(1)));
        Map<String, Object> later = run(FeeRequests.RECORD, finance(), body("kind", "CREDIT", "sponsorId",
            s.sponsor(), "amount", 100, "validDays", 3, "reason", "Shorter"));
        approve((String) later.get("approvalRequestId"));
        String newId = String.valueOf(lots(s.sponsor()).get(1).get("grant_id"));
        assertThat(((FeeProcesses.ItemOutput) systemRun(FeeCredits.REMIND_ITEM, body("sponsorId", s.sponsor())))
            .outcome()).isEqualTo("NONE");
        assertThat(lots(s.sponsor()).getLast().get("reminded_at")).isNull();

        // Expired: what is left voided, 2190 back to 5140; what was spent stays spent.
        clock.set(expiresAt.plus(Duration.ofMinutes(1)));
        runJob(expireJob, clock.instant());
        Map<String, Object> voided = query("SELECT * FROM qb_fee_credit_grant WHERE grant_id = ?", grantId).getFirst();
        assertThat((BigDecimal) voided.get("remaining")).isEqualByComparingTo("0");
        assertThat((BigDecimal) voided.get("voided_amount")).isEqualByComparingTo("670");
        assertThat(voided.get("voided_at")).isNotNull();
        assertThat(lines(grantId)).containsExactly("5140 DEBIT 1000 @" + s.sponsor(), "2190 CREDIT 1000 @" + s.sponsor(),
            "2190 DEBIT 670 @" + s.sponsor(), "5140 CREDIT 670 @" + s.sponsor());
        // The second lot (3 days, expired too) voided whole.
        assertThat((BigDecimal) query("SELECT voided_amount FROM qb_fee_credit_grant WHERE grant_id = ?", newId)
            .getFirst().get("voided_amount")).isEqualByComparingTo("200");
        assertThat(walletBalance(s.sponsor(), QbWallets.FEE_CREDIT)).isEqualByComparingTo("0");
        assertThat(ledger("2190", s.sponsor())).isEqualByComparingTo("0");
        // Run again: nothing more.
        clock.set(clock.instant().plus(Duration.ofDays(1)));
        runJob(expireJob, clock.instant());
        assertThat(lines(grantId)).hasSize(4);
        assertThat(differences()).isEmpty();

        // The monthly report: granted and voided as kinds of their own.
        Instant from = expiresAt.minus(Duration.ofDays(400));
        List<Map<String, Object>> report = items("qb.admin.monthly", finance(), Map.of("params", Map.of("from",
            from.toString(), "to", clock.instant().plus(Duration.ofDays(1)).toString())));
        assertThat(report).filteredOn(row -> "FEE_CREDIT_GRANTED".equals(row.get("category"))).singleElement()
            .satisfies(row -> assertThat(new BigDecimal(String.valueOf(row.get("feeCreditPromotion"))))
                .isPositive());
        assertThat(report).filteredOn(row -> "FEE_CREDIT_VOIDED".equals(row.get("category"))).singleElement()
            .satisfies(row -> assertThat(new BigDecimal(String.valueOf(row.get("feeCreditPromotion"))))
                .isNegative());
        assertThat(report).filteredOn(row -> "OTHER".equals(row.get("category"))).singleElement()
            .satisfies(row -> assertThat(row.get("transactionCount")).isEqualTo(0));
        assertBooksBalance();
    }

    @Test
    @Order(7)
    void headquartersReadTheGrantsSummedByOperatorSponsorAndSource() {
        // Its own grants, one per source: the summary must not depend on what earlier methods granted.
        switchOn();
        Setting s = setting("summary");
        run(FeeCredits.ONBOARDING, s.lead(), body("sponsorId", s.sponsor()));
        Map<String, Object> requested = run(FeeRequests.RECORD, finance(), body("kind", "CREDIT", "sponsorId",
            s.sponsor(), "amount", 500, "reason", "Summary"));
        approve((String) requested.get("approvalRequestId"));
        assertThat(lots(s.sponsor())).hasSize(2);
        String token = finance();
        List<Map<String, Object>> byOperator = items("qb.admin.fee-credit-summary", token, Map.of("params",
            Map.of("groupBy", "OPERATOR")));
        assertThat(byOperator).isNotEmpty().allSatisfy(row -> assertThat(row).containsKeys("granted", "spent",
            "remaining", "voided", "lots"));
        List<Map<String, Object>> bySource = items("qb.admin.fee-credit-summary", token, Map.of("params",
            Map.of("groupBy", "SOURCE")));
        assertThat(bySource).extracting(row -> row.get("groupKey")).contains("ONBOARDING", "MANUAL");
        List<Map<String, Object>> all = items("qb.admin.fee-credit-grants", token, Map.of());
        assertThat(all).isNotEmpty().allSatisfy(row -> assertThat(row).containsKeys("operatorName", "spent"));
        assertThat(differences()).isEmpty();
    }
}

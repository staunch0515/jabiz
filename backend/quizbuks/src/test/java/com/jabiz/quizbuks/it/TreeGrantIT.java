package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.FeeCredits;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.fee.LightGrants;
import com.jabiz.quizbuks.fee.QbFeeAccount;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.play.SponsorPlay;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.tree.TreeProcesses;
import com.jabiz.quizbuks.tree.TreeRevocations;
import com.jabiz.quizbuks.tree.TreeRules;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform fee Kudos lighting a node grants (F-S4b, docs/quizbuks/plans/impl/F-S4.md §3 and its extra review):
 * set on a platform template Sponsors light within {@code qb.tree.grant.max-per-node} and reviewed with it; an
 * operator's copy keeps it; the lighting freezes the template's grant in effect; a Sponsor is granted a template's
 * node once in their life (the requester, 2026-11-10: not again after a revocation and a new pass, nor in another
 * operator's copy after a reassignment); not while the switch is off, nor to a banned Sponsor; a node no longer held
 * lit loses what is left of its lot (what was used is kept), also what a voided invoice gives back later; the daily
 * check of the fee account agrees throughout.
 */
class TreeGrantIT extends TreeItSupport {

    @Autowired
    @Qualifier("qbTreeGrantRetryJob")
    JobDefinition<FeeCredits.SponsorRef> retryJob;

    private static boolean limited;
    private static boolean switchedOn;

    private void param(String key, String value, Map<String, Object> kind) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("paramKey", key);
        values.put("value", value);
        if (kind != null) {
            values.put("valueKind", kind);
        }
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", values, "reason", "F-S4b test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    /** The most a node may grant: 300 (published once for the class's schema). */
    private void limit() {
        if (!limited) {
            param(QbParams.TREE_GRANT_MAX_PER_NODE, "300", QbParams.FEE_CREDIT_MANUAL_MAX_KIND);
            limited = true;
        }
    }

    /** The grants' switch on (published once for the class's schema). */
    private void switchOn() {
        if (!switchedOn) {
            param(QbParams.FEE_CREDIT_GRANT_ENABLED, "true", null);
            switchedOn = true;
        }
    }

    private static List<Object> problemCodes(Map<String, Object> saved) {
        return ((List<?>) saved.get("problems")).stream().map(p -> (Object) ((Map<?, ?>) p).get("code")).toList();
    }

    private Map<String, Object> granting(String text, Object grant) {
        Map<String, Object> node = node(null, "start", text, all(passed()));
        node.put("grantAmount", grant);
        return node;
    }

    private String challengeOn(NewOperator operator, String treeId, String nodeId) {
        return liveBound(operator.leadId(), unique("FS4b grant"), treeId, nodeId,
            Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0))));
    }

    /** The Sponsor passes the challenge; the events (the lighting's grant) are delivered when asked. */
    private void pass(String sponsor, String publicationId, boolean deliver) {
        String token = sponsorToken(sponsor);
        String attemptId = (String) run(SponsorPlay.START, token, body("publicationId", publicationId))
            .get("attemptId");
        Map<String, Object> input = submission(attemptId);
        input.put("answers", shown(attemptId, answers(true, true)));
        assertThat(run(SponsorPlay.SUBMIT, token, input)).containsEntry("passed", true);
        if (deliver) {
            deliverEvents();
        }
    }

    private List<Map<String, Object>> lots(String sponsor) {
        return query("SELECT grant_id, source_key, amount, remaining, voided_amount FROM qb_fee_credit_grant "
            + "WHERE sponsor_id = ? AND source = 'LIGHT'", sponsor);
    }

    private List<Map<String, Object>> lits(String sponsor) {
        return query("SELECT lit_id, tree_id, grant_amount FROM qb_tree_lit_version WHERE subject_kind = 'SPONSOR' "
            + "AND subject_id = ? ORDER BY process_seq_id", sponsor);
    }

    private BigDecimal creditWallet(String sponsor) {
        List<Map<String, Object>> rows = query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = ?",
            sponsor, "FEE_CREDIT");
        return rows.isEmpty() ? BigDecimal.ZERO : (BigDecimal) rows.getFirst().get("balance");
    }

    /** The codes the daily check of the fee account finds, for the Sponsor (or every Sponsor). */
    private List<Object> differences(String sponsor) {
        return items("qb.system.recon-fee-account", TestTokens.bearer(tokens, "fs4b-recon",
            QbPermissions.WALLET_SYSTEM), Map.of()).stream()
            .filter(row -> sponsor == null || sponsor.equals(row.get("sponsorId")))
            .map(row -> row.get("checkCode")).toList();
    }

    private List<Map<String, Object>> pending(String sponsor) {
        return items("qb.admin.tree-grants-pending", TestTokens.bearer(tokens, "fs4b-finance",
            QbPermissions.ADMIN_FINANCE_READ), Map.of("limit", 500)).stream()
            .filter(row -> sponsor.equals(row.get("sponsorId"))).toList();
    }

    private Map<String, Object> current(Object treeId) {
        return Map.of("treeId", treeId, "revision", ((Number) query("SELECT revision FROM qb_tree WHERE tree_id = ?",
            String.valueOf(treeId)).getFirst().get("revision")).longValue());
    }

    private String revoker() {
        return TestTokens.bearer(tokens, verifiedUser(unique("fs4b-revoker")), QbPermissions.TREE_LIT_REVOKE,
            QbPermissions.TREES_READ, "approval.decide");
    }

    /** Revokes the lit record (four eyes) and delivers the revocation's event. */
    private void revoke(Object litId) {
        Map<String, Object> asked = run(TreeRevocations.REVOKE, revoker(), body("litId", litId, "reason",
            "Answers shared"));
        decide(revoker(), asked.get("approvalRequestId"), "APPROVE", null);
        deliverEvents();
    }

    /** The daily job's next run (a day later: a run of the same time is not run twice). */
    private void runRetry() {
        advance(java.time.Duration.ofDays(1));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.execute(retryJob, clock.instant());
        deliverEvents();
    }

    @Test
    void onlyAPlatformTemplateSponsorsLightGrantsWithinTheLimitAndACopyKeepsIt() {
        String writer = mapWriter();
        // Not published (unless another test of the class ran first): none can be set.
        Map<String, Object> template = run(TreeProcesses.CREATE, writer, body("ownerKind", "PLATFORM",
            "subjectKind", "SPONSOR", "name", name("Grants")));
        List<Object> before = problemCodes(save(writer, template, List.of(granting("Start", 100))));
        if (!limited) {
            assertThat(before).containsExactly(TreeRules.GRANT_NOT_ALLOWED);
        }
        limit();
        assertThat(problemCodes(save(writer, current(template.get("treeId")), List.of(granting("Start", 301)))))
            .containsExactly(TreeRules.GRANT_TOO_LARGE);
        Map<String, Object> saved = save(writer, current(template.get("treeId")), List.of(granting("Start", 300)));
        assertThat(problemCodes(saved)).isEmpty();
        // A map Takers light grants nothing.
        Map<String, Object> takers = run(TreeProcesses.CREATE, writer, body("ownerKind", "PLATFORM",
            "subjectKind", "TAKER", "name", name("No grants")));
        assertThat(problemCodes(save(writer, takers, List.of(granting("Start", 100)))))
            .containsExactly(TreeRules.GRANT_NOT_ALLOWED);

        // Reviewed and in effect: the node row grants it.
        decide(mapWriter(), submit(writer, saved).get("approvalRequestId"), "APPROVE", null);
        assertThat(query("SELECT grant_amount FROM qb_tree_node WHERE tree_id = ?",
            String.valueOf(template.get("treeId"))).getFirst().get("grant_amount")).isEqualTo(new BigDecimal("300"));

        // An operator's copy keeps it: a save giving another is a problem, one giving none keeps it.
        NewOperator operator = officialOperator("FS4b grants");
        String lead = memberSession(operator.leadId(), operator.operatorId());
        Map<String, Object> copy = run(TreeProcesses.COPY, lead, body("templateTreeId", template.get("treeId")));
        String nodeId = String.valueOf(query("SELECT node_id FROM qb_tree_node_def WHERE version_id = ?",
            copy.get("versionId")).getFirst().get("node_id"));
        assertThat(query("SELECT grant_amount FROM qb_tree_node_def WHERE version_id = ?", copy.get("versionId"))
            .getFirst().get("grant_amount")).isEqualTo(new BigDecimal("300"));
        Map<String, Object> changed = node(nodeId, null, "Start", all(passed()));
        changed.put("grantAmount", 50);
        assertThat(problemCodes(save(lead, copy, List.of(changed)))).containsExactly(TreeRules.GRANT_COPY);
        assertThat(problemCodes(save(lead, current(copy.get("treeId")),
            List.of(node(nodeId, null, "Start", all(passed())))))).isEmpty();
        assertThat(query("SELECT grant_amount FROM qb_tree_node_def WHERE version_id = ?", copy.get("versionId"))
            .getFirst().get("grant_amount")).isEqualTo(new BigDecimal("300"));
    }

    @Test
    void aNodeIsGrantedOnceInASponsorsLifeAndItsRevocationVoidsWhatIsLeft() {
        limit();
        Copy copy = copy(new BigDecimal("200"));
        String key = LightGrants.sourceKey(copy.templateId(), copy.start());
        String sponsor = sponsorOf(copy.operator(), "s-fs4b-grant");
        String early = sponsorOf(copy.operator(), "s-fs4b-early");

        // The switch off (unless another test of the class turned it on): lit and frozen, granted later.
        boolean off = !switchedOn;
        pass(sponsor, challengeOn(copy.operator(), copy.treeId(), copy.start()), true);
        assertThat(lits(sponsor)).singleElement().satisfies(lit ->
            assertThat(lit.get("grant_amount")).isEqualTo(new BigDecimal("200")));
        if (off) {
            assertThat(lots(sponsor)).isEmpty();
            assertThat(pending(sponsor)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("sourceKey", key));
            // Lit and revoked before any grant: nothing is ever granted for it.
            pass(early, challengeOn(copy.operator(), copy.treeId(), copy.start()), true);
            revoke(lits(early).getFirst().get("lit_id"));
            assertThat(differences(sponsor)).isEmpty();
        }

        // On: the daily job grants it, once; settling again grants nothing more.
        switchOn();
        runRetry();
        assertThat(lots(sponsor)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("source_key", key)
            .containsEntry("amount", new BigDecimal("200")).containsEntry("remaining", new BigDecimal("200")));
        assertThat(creditWallet(sponsor)).isEqualByComparingTo("200");
        if (off) {
            assertThat(lots(early)).isEmpty();
            assertThat(pending(early)).isEmpty();
        }
        runAsSystem(LightGrants.SETTLE, body("sponsorId", sponsor));
        assertThat(lots(sponsor)).hasSize(1);
        assertThat(pending(sponsor)).isEmpty();
        assertThat(differences(sponsor)).isEmpty();

        // Revoked (four eyes): what is left is voided by the revocation's event.
        revoke(lits(sponsor).getFirst().get("lit_id"));
        assertThat(lots(sponsor)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("remaining", new BigDecimal("0")).containsEntry("voided_amount", new BigDecimal("200")));
        assertThat(creditWallet(sponsor)).isEqualByComparingTo("0");
        assertThat(differences(sponsor)).isEmpty();

        // Passed again after it (a new lit record of the same node): not granted again (the requester, 2026-11-10).
        advance(java.time.Duration.ofMinutes(1));
        pass(sponsor, challengeOn(copy.operator(), copy.treeId(), copy.start()), true);
        assertThat(lits(sponsor)).hasSize(2);
        runRetry();
        assertThat(lots(sponsor)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("remaining", new BigDecimal("0")));
        assertThat(creditWallet(sponsor)).isEqualByComparingTo("0");
        assertThat(pending(sponsor)).isEmpty();
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void anotherOperatorsCopyGrantsNothingMoreTheTemplatesChangeIsForLaterLightingsAndABannedSponsorNothing() {
        limit();
        switchOn();
        Copy copy = copy(new BigDecimal("200"));
        String key = LightGrants.sourceKey(copy.templateId(), copy.start());
        String sponsor = sponsorOf(copy.operator(), "s-fs4b-moved");
        pass(sponsor, challengeOn(copy.operator(), copy.treeId(), copy.start()), true);
        assertThat(lots(sponsor)).singleElement().satisfies(row -> assertThat(row).containsEntry("source_key", key));

        // Another operator copies the same template; the Sponsor is moved there and lights the node in its copy.
        NewOperator second = officialOperator("FS4b second");
        String lead = memberSession(second.leadId(), second.operatorId());
        Map<String, Object> other = run(TreeProcesses.COPY, lead, body("templateTreeId", copy.templateId()));
        String member = verifiedUser(unique("fs4b-member"));
        run(OperatorProcesses.MEMBER_ADD, operatorsAdmin(), body("operatorId", second.operatorId(), "userId", member,
            "lead", false));
        decide(memberSession(member, second.operatorId()), submit(lead, other).get("approvalRequestId"), "APPROVE",
            null);
        run(OperatorProcesses.ASSIGN, operatorsAdmin(), body("userId", sponsor, "operatorId", second.operatorId(),
            "reason", "F-S4b"));
        pass(sponsor, challengeOn(second, String.valueOf(other.get("treeId")), copy.start()), true);
        assertThat(lits(sponsor)).hasSize(2);
        runRetry();
        assertThat(lots(sponsor)).hasSize(1);
        assertThat(creditWallet(sponsor)).isEqualByComparingTo("200");

        // The template's next version grants 250; the copies have not taken it. A later lighting freezes 250, the
        // earlier ones keep 200.
        String writer = mapWriter();
        Map<String, Object> start = node(copy.start(), null, "Getting started", all(passed()));
        start.put("grantAmount", 250);
        Map<String, Object> v2 = save(writer, current(copy.templateId()), List.of(start,
            node(copy.next(), null, "Running a quiz", all(lit(copy.start(), true), passed()))));
        assertThat(problemCodes(v2)).isEmpty();
        decide(mapWriter(), submit(writer, v2).get("approvalRequestId"), "APPROVE", null);
        assertThat(query("SELECT grant_amount FROM qb_tree_node_def WHERE version_id = (SELECT current_version_id "
            + "FROM qb_tree WHERE tree_id = ?) AND node_id = ?", copy.treeId(), copy.start()).getFirst()
            .get("grant_amount")).isEqualTo(new BigDecimal("200"));
        String later = sponsorOf(copy.operator(), "s-fs4b-later");
        pass(later, challengeOn(copy.operator(), copy.treeId(), copy.start()), true);
        assertThat(lits(later).getFirst().get("grant_amount")).isEqualTo(new BigDecimal("250"));
        assertThat(lots(later)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("amount", new BigDecimal("250")));
        assertThat(lits(sponsor)).allSatisfy(lit -> assertThat(lit.get("grant_amount"))
            .isEqualTo(new BigDecimal("200")));

        // A Sponsor banned before the lighting's grant is settled: never granted.
        String banned = sponsorOf(copy.operator(), "s-fs4b-banned");
        pass(banned, challengeOn(copy.operator(), copy.treeId(), copy.start()), false);
        String boss = userWithRole(unique("fs4b-boss"), QbRoles.ADMIN_SUPER);
        run("QB_USER_BAN", tokenOf(boss, QbRoles.ADMIN_SUPER), body("userId", banned, "reason", "Fraud"));
        deliverEvents();
        assertThat(lits(banned)).hasSize(1);
        runRetry();
        assertThat(lots(banned)).isEmpty();
        assertThat(differences(null)).doesNotContain("LIGHT_GRANTS", "LIGHT_VOIDS", "FEE_CREDIT",
            "FEE_CREDIT_PROMOTION");
    }

    @Test
    void whatWasUsedIsKeptAndWhatAVoidedInvoiceGivesBackIsVoidedByTheDailyJob() {
        limit();
        switchOn();
        param(QbParams.FEE_MONTHLY, "100", null);
        Copy copy = copy(new BigDecimal("200"));
        String sponsor = sponsorOf(copy.operator(), "s-fs4b-used");
        pass(sponsor, challengeOn(copy.operator(), copy.treeId(), copy.start()), true);
        String lot = String.valueOf(lots(sponsor).getFirst().get("grant_id"));

        // 110 of it (the fee with its tax) pays the month.
        assertThat(((FeeProcesses.ItemOutput) systemRun(FeeProcesses.MONTHLY_ITEM, body("sponsorId", sponsor,
            "period", FeeRules.period(clock.instant(), ZoneId.of("Asia/Tokyo"))))).outcome()).isEqualTo("CHARGED");
        assertThat(lots(sponsor).getFirst().get("remaining")).isEqualTo(new BigDecimal("90"));

        // Revoked: the 90 left is voided (2190 / 5140), the 110 used is kept.
        revoke(lits(sponsor).getFirst().get("lit_id"));
        assertThat(lots(sponsor)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("remaining", new BigDecimal("0")).containsEntry("voided_amount", new BigDecimal("90")));
        assertThat(FeeAccountIT.lines(QbFeeAccount.GRANT, lot)).endsWith("2190 DEBIT 90", "5140 CREDIT 90");
        assertThat(creditWallet(sponsor)).isEqualByComparingTo("0");
        assertThat(differences(sponsor)).isEmpty();

        // The invoice voided: its 110 goes back to the lot, which is voided again at once (it never revives).
        String invoiceId = String.valueOf(query("SELECT invoice_id FROM qb_fee_invoice WHERE sponsor_id = ?",
            sponsor).getFirst().get("invoice_id"));
        Map<String, Object> voiding = run(FeeRequests.RECORD, tokenOf(user("fs4b-finance"), QbRoles.ADMIN_FINANCE),
            body("kind", "VOID", "invoiceId", invoiceId, "reason", "Charged by mistake"));
        run("APPROVAL_DECIDE", tokenOf(user("fs4b-content"), QbRoles.ADMIN_CONTENT), body("requestId",
            voiding.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
        assertThat(query("SELECT status FROM qb_fee_invoice WHERE invoice_id = ?", invoiceId).getFirst())
            .containsEntry("status", "VOID");
        assertThat(lots(sponsor)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("remaining", new BigDecimal("0")).containsEntry("voided_amount", new BigDecimal("200")));
        assertThat(FeeAccountIT.lines(QbFeeAccount.GRANT, lot)).endsWith("2190 DEBIT 110", "5140 CREDIT 110");
        assertThat(creditWallet(sponsor)).isEqualByComparingTo("0");
        assertThat(differences(sponsor)).isEmpty();
        assertThat(differences(null)).doesNotContain("FEE_CREDIT", "FEE_CREDIT_PROMOTION");
        runRetry();
        assertThat(lots(sponsor).getFirst().get("voided_amount")).isEqualTo(new BigDecimal("200"));
    }

    @Test
    void aVoidTheRevocationsEventMissedIsReportedAndDoneByTheDailyJob() {
        limit();
        switchOn();
        Copy copy = copy(new BigDecimal("200"));
        String sponsor = sponsorOf(copy.operator(), "s-fs4b-missed");
        pass(sponsor, challengeOn(copy.operator(), copy.treeId(), copy.start()), true);
        assertThat(lots(sponsor)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("remaining", new BigDecimal("200")));

        // Revoked, the revocation's event never reaching the grants (as if its every delivery failed).
        Object litId = lits(sponsor).getFirst().get("lit_id");
        Map<String, Object> asked = run(TreeRevocations.REVOKE, revoker(), body("litId", litId, "reason",
            "Answers shared"));
        run("APPROVAL_DECIDE", revoker(), body("requestId", asked.get("approvalRequestId"), "decision", "APPROVE"));
        do {
            execute("INSERT INTO sys_event_consumption (consumer, event_id, consumed_time) SELECT ?, e.event_id, "
                + "e.created_time FROM sys_outbox_event e WHERE e.event_type = ? AND e.payload ->> 'litId' = ? "
                + "AND NOT EXISTS (SELECT 1 FROM sys_event_consumption c WHERE c.consumer = ? "
                + "AND c.event_id = e.event_id)", LightGrants.REVOKED_CONSUMER, TreeRevocations.REVOKED_EVENT,
                String.valueOf(litId), LightGrants.REVOKED_CONSUMER);
        } while (deliverer.deliverPending().block() > 0);
        assertThat(query("SELECT 1 FROM qb_tree_lit_revocation_version WHERE lit_id = ?", String.valueOf(litId))).hasSize(1);
        assertThat(lots(sponsor).getFirst().get("remaining")).isEqualTo(new BigDecimal("200"));
        // The daily check reports it; the daily job voids it.
        assertThat(differences(sponsor)).containsExactly("LIGHT_VOIDS");
        runRetry();
        assertThat(lots(sponsor)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("remaining", new BigDecimal("0")).containsEntry("voided_amount", new BigDecimal("200")));
        assertThat(creditWallet(sponsor)).isEqualByComparingTo("0");
        assertThat(differences(sponsor)).isEmpty();
    }
}

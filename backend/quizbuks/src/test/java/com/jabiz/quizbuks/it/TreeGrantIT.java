package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.FeeCredits;
import com.jabiz.quizbuks.fee.LightGrants;
import com.jabiz.quizbuks.play.SponsorPlay;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.tree.TreeProcesses;
import com.jabiz.quizbuks.tree.TreeRevocations;
import com.jabiz.quizbuks.tree.TreeRules;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform fee Kudos lighting a node grants (F-S4b, docs/quizbuks/plans/impl/F-S4.md §3): set on a platform
 * template Sponsors light within {@code qb.tree.grant.max-per-node} and reviewed with it; an operator's copy keeps it;
 * the lighting freezes it and the lot is granted once outside the lighting (the event, or the daily job while it was
 * not); a revoked lighting voids what is left; the daily check of the fee account agrees throughout.
 */
class TreeGrantIT extends TreeItSupport {

    @Autowired
    @Qualifier("qbTreeGrantRetryJob")
    JobDefinition<FeeCredits.SponsorRef> retryJob;

    private static boolean limited;

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

    private static List<Object> problemCodes(Map<String, Object> saved) {
        return ((List<?>) saved.get("problems")).stream().map(p -> (Object) ((Map<?, ?>) p).get("code")).toList();
    }

    private Map<String, Object> granting(String text, Object grant) {
        Map<String, Object> node = node(null, "start", text, all(passed()));
        node.put("grantAmount", grant);
        return node;
    }

    private void pass(String sponsor, String publicationId) {
        String token = sponsorToken(sponsor);
        String attemptId = (String) run(SponsorPlay.START, token, body("publicationId", publicationId))
            .get("attemptId");
        Map<String, Object> input = submission(attemptId);
        input.put("answers", shown(attemptId, answers(true, true)));
        assertThat(run(SponsorPlay.SUBMIT, token, input)).containsEntry("passed", true);
        deliverEvents();
    }

    private List<Map<String, Object>> lots(String sponsor) {
        return query("SELECT source_key, amount, remaining, voided_amount FROM qb_fee_credit_grant "
            + "WHERE sponsor_id = ? AND source = 'LIGHT'", sponsor);
    }

    private BigDecimal creditWallet(String sponsor) {
        List<Map<String, Object>> rows = query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = ?",
            sponsor, "FEE_CREDIT");
        return rows.isEmpty() ? BigDecimal.ZERO : (BigDecimal) rows.getFirst().get("balance");
    }

    private List<Map<String, Object>> differences() {
        return items("qb.system.recon-fee-account", TestTokens.bearer(tokens, "fs4b-recon",
            QbPermissions.WALLET_SYSTEM), Map.of());
    }

    private List<Map<String, Object>> pending(String sponsor) {
        return items("qb.admin.tree-grants-pending", TestTokens.bearer(tokens, "fs4b-finance",
            QbPermissions.ADMIN_FINANCE_READ), Map.of("limit", 500)).stream()
            .filter(row -> sponsor.equals(row.get("sponsorId"))).toList();
    }

    private Map<String, Object> treeRow(Object treeId) {
        return query("SELECT revision FROM qb_tree WHERE tree_id = ?", String.valueOf(treeId)).getFirst();
    }

    private String revoker() {
        return TestTokens.bearer(tokens, verifiedUser(unique("fs4b-revoker")), QbPermissions.TREE_LIT_REVOKE,
            QbPermissions.TREES_READ, "approval.decide");
    }

    @Test
    void onlyAPlatformTemplateSponsorsLightGrantsWithinTheLimitAndACopyKeepsIt() {
        String writer = mapWriter();
        // Not published (unless the other test of the class ran first): none can be set.
        Map<String, Object> template = run(TreeProcesses.CREATE, writer, body("ownerKind", "PLATFORM",
            "subjectKind", "SPONSOR", "name", name("Grants")));
        List<Object> before = problemCodes(save(writer, template, List.of(granting("Start", 100))));
        if (!limited) {
            assertThat(before).containsExactly(TreeRules.GRANT_NOT_ALLOWED);
        }
        limit();
        Map<String, Object> now = Map.of("treeId", template.get("treeId"), "revision",
            ((Number) treeRow(template.get("treeId")).get("revision")).longValue());
        assertThat(problemCodes(save(writer, now, List.of(granting("Start", 301)))))
            .containsExactly(TreeRules.GRANT_TOO_LARGE);
        now = Map.of("treeId", template.get("treeId"), "revision",
            ((Number) treeRow(template.get("treeId")).get("revision")).longValue());
        Map<String, Object> saved = save(writer, now, List.of(granting("Start", 300)));
        assertThat(problemCodes(saved)).isEmpty();
        // A map Takers light grants nothing.
        Map<String, Object> takers = run(TreeProcesses.CREATE, writer, body("ownerKind", "PLATFORM",
            "subjectKind", "TAKER", "name", name("No grants")));
        assertThat(problemCodes(save(writer, takers, List.of(granting("Start", 100)))))
            .containsExactly(TreeRules.GRANT_NOT_ALLOWED);

        // Reviewed and in effect: the node row grants it.
        Map<String, Object> submitted = submit(writer, saved);
        decide(mapWriter(), submitted.get("approvalRequestId"), "APPROVE", null);
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
        Map<String, Object> copyNow = Map.of("treeId", copy.get("treeId"), "revision",
            ((Number) treeRow(copy.get("treeId")).get("revision")).longValue());
        assertThat(problemCodes(save(lead, copyNow, List.of(node(nodeId, null, "Start", all(passed()))))))
            .isEmpty();
        assertThat(query("SELECT grant_amount FROM qb_tree_node_def WHERE version_id = ?", copy.get("versionId"))
            .getFirst().get("grant_amount")).isEqualTo(new BigDecimal("300"));
    }

    @Test
    void aLightingIsGrantedOnceOutsideItAndARevocationVoidsWhatIsLeft() {
        limit();
        Copy copy = copy(new BigDecimal("200"));
        String challenge = liveBound(copy.operator().leadId(), unique("FS4b grant"), copy.treeId(), copy.start(),
            Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0))));
        String sponsor = sponsorOf(copy.operator(), "s-fs4b-grant");

        // The switch off (the default): lit and frozen, granted later; the check does not count it.
        pass(sponsor, challenge);
        Map<String, Object> lit = query("SELECT lit_id, grant_amount FROM qb_tree_lit_version WHERE subject_kind = "
            + "'SPONSOR' AND subject_id = ?", sponsor).getFirst();
        assertThat(lit.get("grant_amount")).isEqualTo(new BigDecimal("200"));
        assertThat(lots(sponsor)).isEmpty();
        assertThat(pending(sponsor)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("litId", String.valueOf(lit.get("lit_id"))));
        assertThat(differences()).isEmpty();

        // On: the daily job grants it, once; the event or the job again grants nothing more.
        param(QbParams.FEE_CREDIT_GRANT_ENABLED, "true", null);
        assertThat(differences()).extracting(row -> row.get("checkCode")).containsExactly("LIGHT_GRANTS");
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.execute(retryJob, clock.instant());
        assertThat(lots(sponsor)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("source_key", LightGrants.sourceKey(lit.get("lit_id")))
            .containsEntry("amount", new BigDecimal("200")).containsEntry("remaining", new BigDecimal("200")));
        assertThat(creditWallet(sponsor)).isEqualByComparingTo("200");
        runAsSystem(LightGrants.GRANT, body("sponsorId", sponsor));
        assertThat(lots(sponsor)).hasSize(1);
        assertThat(pending(sponsor)).isEmpty();
        assertThat(differences()).isEmpty();

        // Another Sponsor, the switch on: granted by the lighting's event.
        String other = sponsorOf(copy.operator(), "s-fs4b-grant-event");
        pass(other, challenge);
        assertThat(lots(other)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("amount", new BigDecimal("200")));

        // Revoked (four eyes): what is left is voided; the check agrees.
        Map<String, Object> asked = run(TreeRevocations.REVOKE, revoker(), body("litId", lit.get("lit_id"),
            "reason", "Answers shared"));
        decide(revoker(), asked.get("approvalRequestId"), "APPROVE", null);
        deliverEvents();
        assertThat(lots(sponsor)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("remaining", new BigDecimal("0")).containsEntry("voided_amount", new BigDecimal("200")));
        assertThat(creditWallet(sponsor)).isEqualByComparingTo("0");
        assertThat(lots(other)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("remaining", new BigDecimal("200")));
        assertThat(differences()).isEmpty();
        // Voiding again does nothing.
        runAsSystem(LightGrants.VOID, body("litId", lit.get("lit_id")));
        assertThat(creditWallet(sponsor)).isEqualByComparingTo("0");
        assertThat(differences()).isEmpty();
    }
}

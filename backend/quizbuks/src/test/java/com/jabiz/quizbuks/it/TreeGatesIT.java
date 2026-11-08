package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.benefit.BenefitCodes;
import com.jabiz.quizbuks.benefit.OfferProcesses;
import com.jabiz.quizbuks.benefit.QbBenefits;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.tree.TreeProcesses;
import com.jabiz.quizbuks.tree.TreeRevocations;
import com.jabiz.runtime.test.TestTokens;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S2c: a platform feature unlocked by lighting the node a controlled parameter names (tree.TreeGates), shown with
 * the role of providing benefits: closed while the parameter is absent (the default), open only to whoever lit the
 * node, closed again after a revocation, and closed (and reported by the daily check) when the parameter names no
 * node in effect of a platform map Takers light. The processes and the template qb.sponsor.benefit-access agree.
 */
class TreeGatesIT extends TreeItSupport {

    private static final String KEY = QbParams.TREE_GATE_BENEFIT_PROVIDER;

    private Map<String, Object> offer() {
        return body("kind", QbBenefits.GOODS, "name", "Beef noodles", "description", "One bowl of beef noodles",
            "unitValue", 30, "pickupAddress", "Ramen B, 1-2-3 Shibuya", "pickupHours", "11:00-21:00", "listed", true);
    }

    private List<Object> refusal(String sponsor) {
        return codes(refused(OfferProcesses.SAVE, sponsorToken(sponsor), offer(), 422));
    }

    private Map<String, Object> access(String sponsor) {
        return items("qb.sponsor.benefit-access", sponsorToken(sponsor), Map.of()).stream()
            .filter(row -> "BENEFIT_PROVIDER".equals(row.get("certification"))).findFirst().orElseThrow();
    }

    private List<Object> health() {
        return items("qb.admin.tree-health", TestTokens.bearer(tokens, "fs2c-health", QbPermissions.TREES_READ),
            Map.of("params", Map.of("at", clock.instant().toString()))).stream().map(row -> row.get("checkCode"))
            .toList();
    }

    /** Headquarters' content administrator, who asks for a revocation of a platform map's lighting or approves one. */
    private String revoker() {
        return TestTokens.bearer(tokens, verifiedUser(unique("fs2c-revoker")), QbPermissions.TREE_LIT_REVOKE,
            QbPermissions.TREES_READ, "approval.decide");
    }

    private void nameValue(String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", KEY, "value", value, "valueKind", QbParams.TREE_GATE_KIND),
            "reason", "F-S2c gate"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    @Test
    void aLitNodeOpensTheFeatureToWhoeverLitItWhileTheParameterNamesIt() {
        String lit = newSponsor(unique("s-fs2c-lit"));
        String other = newSponsor(unique("s-fs2c-other"));
        Gate gate = gate();
        light(gate, lit);

        // No parameter (the default): closed to everyone, the node lit or not.
        assertThat(refusal(lit)).containsExactly(BenefitCodes.NOT_CERTIFIED);
        assertThat(access(lit)).containsEntry("open", false).containsEntry("nodeId", null);
        assertThat(health()).doesNotContain("GATE_INVALID");

        // Named: open to whoever lit the node, and the page names the node to the others.
        nameGate(KEY, gate);
        assertThat(run(OfferProcesses.SAVE, sponsorToken(lit), offer())).containsKey("offerId");
        assertThat(access(lit)).containsEntry("open", true).containsEntry("nodeId", gate.nodeId());
        assertThat(refusal(other)).containsExactly(BenefitCodes.NOT_CERTIFIED);
        assertThat(access(other)).containsEntry("open", false).containsEntry("treeId", gate.treeId())
            .containsEntry("nodeName", Map.of("en", "Fair dealing"));
        assertThat(health()).doesNotContain("GATE_INVALID");

        // The lighting revoked (four eyes): closed again; a new pass lights the node anew and opens it.
        Object litId = query("SELECT lit_id FROM qb_tree_lit_version WHERE subject_id = ? AND node_id = ?", lit,
            gate.nodeId()).getFirst().get("lit_id");
        Map<String, Object> asked = run(TreeRevocations.REVOKE, revoker(), body("litId", litId, "reason",
            "Answers shared"));
        decide(revoker(), asked.get("approvalRequestId"), "APPROVE", null);
        assertThat(refusal(lit)).containsExactly(BenefitCodes.NOT_CERTIFIED);
        assertThat(access(lit)).containsEntry("open", false);
    }

    @Test
    void aParameterNamingNoNodeToLightKeepsTheFeatureClosedAndIsReported() {
        String lit = newSponsor(unique("s-fs2c-bad"));
        Gate gate = gate();
        light(gate, lit);

        // Malformed (an upper-case id): closed, reported.
        nameValue(gate.treeId().toUpperCase() + "/" + gate.nodeId());
        assertThat(refusal(lit)).containsExactly(BenefitCodes.NOT_CERTIFIED);
        assertThat(health()).contains("GATE_INVALID");

        // A node of a Sponsor's map (not the platform's): closed, reported.
        String sponsor = newSponsor(unique("s-fs2c-map"));
        Map3 own = map(sponsor);
        nameValue(own.treeId() + "/" + own.basics());
        assertThat(refusal(lit)).containsExactly(BenefitCodes.NOT_CERTIFIED);
        assertThat(health()).contains("GATE_INVALID");

        // The right node: open, nothing reported; then the map retired: closed, reported.
        nameGate(KEY, gate);
        assertThat(run(OfferProcesses.SAVE, sponsorToken(lit), offer())).containsKey("offerId");
        assertThat(health()).doesNotContain("GATE_INVALID");
        Object revision = query("SELECT revision FROM qb_tree WHERE tree_id = ?", gate.treeId()).getFirst()
            .get("revision");
        run(TreeProcesses.RETIRE, mapWriter(), body("treeId", gate.treeId(), "baseRevision", revision));
        assertThat(refusal(lit)).containsExactly(BenefitCodes.NOT_CERTIFIED);
        assertThat(access(lit)).containsEntry("open", false).containsEntry("nodeId", null);
        assertThat(health()).contains("GATE_INVALID");
    }
}

package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.tree.TreeProcesses;
import com.jabiz.quizbuks.tree.TreeRevocations;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S2c2b (D7): applying to become a Sponsor from the App (the Sponsor entry's signed-in join) is open only to whoever
 * lit the node the controlled parameter {@value QbParams#TREE_GATE_SPONSOR_APPLY} names, through the platform's
 * sign-up guard (its loads by the parameter's value, platform decision D80). Closed while the parameter is absent
 * (the default) or names no node to light; closed again after the lighting is revoked.
 */
// The Sponsor entry takes registrations (the signed-in join needs it), as in production.
@org.springframework.test.context.TestPropertySource(properties = "jabiz.security.entries.sponsor.self-registration=true")
class SponsorApplyGateIT extends TreeItSupport {

    private static final String KEY = QbParams.TREE_GATE_SPONSOR_APPLY;

    private WebTestClient.ResponseSpec join(String takerId) {
        return client.post().uri("/api/auth/account/entries/sponsor/join")
            .header(HttpHeaders.AUTHORIZATION, taker(takerId)).exchange();
    }

    private void refused(String takerId) {
        assertThat(join(takerId).expectStatus().isForbidden().expectBody(MAP).returnResult().getResponseBody()
            .toString()).contains("SIGN_UP_REFUSED");
    }

    private long roleRows(String userId) {
        return ((Number) query("SELECT count(*) AS n FROM sec_user_role_version WHERE user_id = ?::uuid", userId)
            .getFirst().get("n")).longValue();
    }

    private void joined(String takerId) {
        long before = roleRows(takerId);
        join(takerId).expectStatus().isNoContent();
        // The Sponsor entry's registration role is added to the account.
        assertThat(roleRows(takerId)).isGreaterThan(before);
    }

    /** Headquarters' content administrator, who asks for a revocation of a platform map's lighting or approves one. */
    private String revoker() {
        return TestTokens.bearer(tokens, verifiedUser(unique("fs2c2b-revoker")), QbPermissions.TREE_LIT_REVOKE,
            QbPermissions.TREES_READ, "approval.decide");
    }

    private void nameValue(String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", KEY, "value", value, "valueKind", QbParams.TREE_GATE_KIND),
            "reason", "F-S2c2b gate"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    @Test
    void theJoinIsOpenToWhoeverLitTheNamedNode() {
        String lit = verifiedUser(unique("fs2c2b-lit"));
        String other = verifiedUser(unique("fs2c2b-other"));
        String later = verifiedUser(unique("fs2c2b-later"));
        Gate gate = gate();
        light(gate, lit, later);

        // No parameter (the default): closed to everyone, the node lit or not.
        refused(lit);

        // A malformed value (an upper-case id): closed.
        nameValue(gate.treeId().toUpperCase() + "/" + gate.nodeId());
        refused(lit);

        // Named: open to whoever lit it, closed to the others.
        nameGate(KEY, gate);
        refused(other);
        joined(lit);

        // The lighting revoked (four eyes): closed again for that person.
        Object litId = query("SELECT lit_id FROM qb_tree_lit_version WHERE subject_id = ? AND node_id = ?", later,
            gate.nodeId()).getFirst().get("lit_id");
        Map<String, Object> asked = run(TreeRevocations.REVOKE, revoker(), body("litId", litId, "reason",
            "Answers shared"));
        decide(revoker(), asked.get("approvalRequestId"), "APPROVE", null);
        refused(later);
    }

    @Test
    void aRetiredMapClosesIt() {
        String lit = verifiedUser(unique("fs2c2b-retired"));
        Gate gate = gate();
        light(gate, lit);
        nameGate(KEY, gate);
        Object revision = query("SELECT revision FROM qb_tree WHERE tree_id = ?", gate.treeId()).getFirst()
            .get("revision");
        run(TreeProcesses.RETIRE, mapWriter(), body("treeId", gate.treeId(), "baseRevision", revision));
        refused(lit);
    }
}

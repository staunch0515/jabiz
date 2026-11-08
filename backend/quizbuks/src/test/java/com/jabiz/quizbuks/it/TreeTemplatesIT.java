package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.tree.TreeRevocations;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The review side's templates of knowledge maps (F-S2b3a): every map for the platform, an operator's own for its
 * members, one version node by node against the version in effect (with the running publications a removal would
 * close), and the lit records with their revocations and the requests waiting for review.
 */
class TreeTemplatesIT extends TreeItSupport {

    private String reader() {
        return TestTokens.bearer(tokens, verifiedUser(unique("fs2b3-reader")), QbPermissions.TREES_READ);
    }

    private static Map<String, Object> byKey(List<Map<String, Object>> rows, String key, Object value) {
        return rows.stream().filter(row -> String.valueOf(value).equals(String.valueOf(row.get(key)))).findFirst()
            .orElse(null);
    }

    @Test
    void theMapsAVersionAgainstTheOneInEffectAndTheLitRecords() {
        String sponsor = newSponsor(unique("s-fs2b3-maps"));
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        Map3 map = map(sponsor);
        String advanced = liveBound(sponsor, "Templates advanced", map.treeId(), map.advanced());
        String basics = liveBound(sponsor, "Templates basics", map.treeId(), map.basics());
        String userId = newTaker(unique("t-fs2b3-maps"), "JP");
        play(userId, basics, true, true);
        deliverEvents();

        // A draft keeping the basics, dropping the other two, adding one.
        Map<String, Object> tree = query("SELECT revision FROM qb_tree WHERE tree_id = ?", map.treeId()).getFirst();
        Map<String, Object> draft = save(token, Map.of("treeId", map.treeId(), "revision", tree.get("revision")),
            List.of(node(map.basics(), null, "Basics", all(passed())),
                node(null, "extra", "Extra", all(lit(map.basics(), true), passed()))));
        String reader = reader();

        Map<String, Object> row = byKey(items("qb.admin.trees", reader, Map.of()), "treeId", map.treeId());
        assertThat(row).containsEntry("ownerKind", "SPONSOR").containsEntry("ownerId", sponsor)
            .containsEntry("status", "ACTIVE").containsEntry("openStatus", "DRAFT")
            .containsEntry("openVersionId", draft.get("versionId"));
        assertThat(((Number) row.get("versionNo")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("openVersionNo")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("nodes")).intValue()).isEqualTo(3);
        assertThat(((Number) row.get("litSubjects")).intValue()).isEqualTo(1);
        assertThat(String.valueOf(row.get("name"))).contains("Coffee");

        List<Map<String, Object>> nodes = items("qb.admin.tree-version", reader, Map.of("params",
            Map.of("versionId", draft.get("versionId"))));
        assertThat(nodes).extracting(n -> n.get("change")).containsExactly("SAME", "ADDED", "REMOVED", "REMOVED");
        assertThat(byKey(nodes, "nodeId", map.basics()).get("runningBound")).satisfies(n ->
            assertThat(((Number) n).intValue()).isEqualTo(1));
        Map<String, Object> removed = byKey(nodes, "nodeId", map.advanced());
        assertThat(removed).containsEntry("change", "REMOVED");
        assertThat(((Number) removed.get("runningBound")).intValue()).isEqualTo(1);
        assertThat(((Number) byKey(nodes, "nodeId", map.expert()).get("runningBound")).intValue()).isZero();
        assertThat(String.valueOf(removed.get("name"))).contains("Advanced");
        assertThat(advanced).isNotNull();
        // The version in effect against itself: every node the same.
        String effect = (String) query("SELECT current_version_id FROM qb_tree WHERE tree_id = ?", map.treeId())
            .getFirst().get("current_version_id");
        assertThat(row).containsEntry("currentVersionId", effect);
        assertThat(items("qb.admin.tree-version", reader, Map.of("params", Map.of("versionId", effect))))
            .extracting(n -> n.get("change")).containsOnly("SAME").hasSize(3);

        // The lit record, then the request to revoke it in review, then the revocation.
        List<Map<String, Object>> lits = items("qb.admin.tree-lit", reader, Map.of("params", Map.of("treeId",
            map.treeId())));
        assertThat(lits).singleElement().satisfies(lit -> assertThat(lit).containsEntry("subjectId", userId)
            .containsEntry("nodeId", map.basics()).containsEntry("revokedAt", null).containsEntry("requestId", null));
        Object litId = lits.getFirst().get("litId");
        Map<String, Object> asked = run(TreeRevocations.REVOKE, platformOperatorMember(), body("litId", litId,
            "reason", "Shared answers"));
        assertThat(items("qb.admin.tree-lit", reader, Map.of("params", Map.of("subjectId", userId))))
            .singleElement().satisfies(lit -> assertThat(lit).containsEntry("requestStatus", "REVIEW")
                .containsEntry("approvalRequestId", asked.get("approvalRequestId")));
        decide(platformOperatorMember(), asked.get("approvalRequestId"), "APPROVE", null);
        assertThat(items("qb.admin.tree-lit", reader, Map.of("params", Map.of("nodeId", map.basics(), "treeId",
            map.treeId())))).singleElement().satisfies(lit -> assertThat(lit)
                .containsEntry("revokeReason", "Shared answers").containsEntry("requestId", null)
                .satisfies(l -> assertThat(l.get("revokedAt")).isNotNull()));
        assertThat(((Number) byKey(items("qb.admin.trees", reader, Map.of()), "treeId", map.treeId())
            .get("litSubjects")).intValue()).isZero();
    }

    @Test
    void anOperatorsMembersSeeTheirOwnOperatorsMapsOnly() {
        String sponsor = newSponsor(unique("s-fs2b3-operator"));
        Map3 map = map(sponsor);
        assertThat(items("qb.operator.trees", platformOperatorMember(), Map.of()))
            .extracting(row -> row.get("treeId")).contains(map.treeId());
        NewOperator other = officialOperator("Templates other");
        assertThat(items("qb.operator.trees", memberSession(other.leadId(), other.operatorId()), Map.of()))
            .extracting(row -> row.get("treeId")).doesNotContain(map.treeId());
        // Without a membership: nothing.
        assertThat(items("qb.operator.trees", TestTokens.bearer(tokens, verifiedUser(unique("fs2b3-nobody")),
            QbPermissions.OPERATOR_USE), Map.of())).isEmpty();
    }
}

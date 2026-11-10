package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.tree.TreeProcesses;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Knowledge maps' definitions, versions and review (F-S2a, docs/quizbuks/plans/impl/F-S2.md): a Sponsor's own map
 * reviewed by their operator, the platform's template reviewed by another administrator, an operator's copy keeping
 * the template's node ids and taking the template's changes node by node; the drafts give every problem the rules
 * find, and only the owner edits a map.
 */
class KnowledgeMapIT extends TreeItSupport {

    private Map<String, Object> treeRow(Object treeId) {
        return query("SELECT * FROM qb_tree WHERE tree_id = ?", String.valueOf(treeId)).getFirst();
    }

    private List<Map<String, Object>> nodes(Object treeId) {
        return query("SELECT node_id, active, before_answer, bindable FROM qb_tree_node WHERE tree_id = ? ORDER BY "
            + "created_in_version, node_id", String.valueOf(treeId));
    }

    @Test
    void aSponsorsMapIsReviewedByTheirOperatorAndPutsItsNodesInEffect() {
        String sponsor = newSponsor(unique("s-fs2-map"));
        String token = sponsorToken(sponsor);
        Map<String, Object> tree = run(TreeProcesses.CREATE, token, body("ownerKind", "SPONSOR", "name",
            name("Coffee")));
        assertThat(tree).containsEntry("status", "DRAFT").containsEntry("versionNo", 1);
        // One map of their own (qb.tree.sponsor.max-trees).
        assertThat(codes(refused(TreeProcesses.CREATE, token, body("ownerKind", "SPONSOR", "name", name("Tea")),
            422))).containsExactly("QB_TREE_LIMIT");

        // A draft with a cycle is saved, with its problems.
        Map<String, Object> saved = save(token, tree, List.of(
            node(null, "basics", "Basics", all(lit("advanced", false), passed())),
            node(null, "advanced", "Advanced", all(lit("basics", true), passed()))));
        assertThat(((List<?>) saved.get("problems")).stream().map(problem -> (Object) ((Map<?, ?>) problem).get("code"))
            .toList()).contains((Object) "QB_TREE_CYCLE");
        @SuppressWarnings("unchecked")
        Map<String, String> ids = (Map<String, String>) saved.get("nodeIds");
        assertThat(ids).containsOnlyKeys("basics", "advanced");
        // Submitting it is refused with the same problems.
        assertThat(codes(refused(TreeProcesses.SUBMIT, token, body("treeId", tree.get("treeId"), "baseRevision",
            saved.get("revision")), 422))).contains("QB_TREE_CYCLE");

        // Fixed: the advanced node needs the basics lit before answering.
        saved = save(token, saved, List.of(
            node(ids.get("basics"), null, "Basics", all(passed())),
            node(ids.get("advanced"), null, "Advanced", all(lit(ids.get("basics"), true), passed()))));
        assertThat((List<?>) saved.get("problems")).isEmpty();
        assertThat(saved.get("nodeIds")).isEqualTo(Map.of());
        // A stale revision is refused.
        assertThat(codes(refused(TreeProcesses.SUBMIT, token, body("treeId", tree.get("treeId"), "baseRevision",
            tree.get("revision")), 422))).containsExactly("QB_TREE_CHANGED");

        Map<String, Object> submitted = submit(token, saved);
        assertThat(submitted).containsEntry("status", "REVIEW").containsEntry("approval", "PENDING");
        assertThat(query("SELECT scope_name, scope_value FROM sys_approval_request_version WHERE request_id = "
            + "?::uuid", submitted.get("approvalRequestId")).getFirst())
            .containsEntry("scope_value", platformOperator());
        // Nothing is in effect before the review.
        assertThat(nodes(tree.get("treeId"))).isEmpty();

        decide(platformOperatorMember(), submitted.get("approvalRequestId"), "APPROVE", null);
        assertThat(treeRow(tree.get("treeId"))).containsEntry("current_version_id", submitted.get("versionId"));
        assertThat(nodes(tree.get("treeId"))).hasSize(2).allSatisfy(row -> assertThat(row)
            .containsEntry("active", true).containsEntry("bindable", true));
        assertThat(nodes(tree.get("treeId"))).filteredOn(row -> ids.get("advanced").equals(row.get("node_id")))
            .singleElement().satisfies(row -> assertThat(String.valueOf(row.get("before_answer")))
                .contains(ids.get("basics")));

        // Another Sponsor finds no map to edit.
        String other = newSponsor(unique("s-fs2-other"));
        refused(TreeProcesses.SAVE, sponsorToken(other), body("treeId", tree.get("treeId"), "name", name("Mine"),
            "nodes", List.of()), 404);
    }

    @Test
    void aVersionSentBackIsChangedAndSubmittedAgain() {
        String sponsor = newSponsor(unique("s-fs2-back"));
        String token = sponsorToken(sponsor);
        Map<String, Object> tree = run(TreeProcesses.CREATE, token, body("ownerKind", "SPONSOR", "name",
            name("Bread")));
        Map<String, Object> saved = save(token, tree, List.of(node(null, "a", "Sourdough", all(passed()))));
        Map<String, Object> submitted = submit(token, saved);
        decide(platformOperatorMember(), submitted.get("approvalRequestId"), "REJECT", "Name the starter");
        assertThat(query("SELECT status, reject_reason FROM qb_tree_version WHERE version_id = ?",
            submitted.get("versionId")).getFirst()).containsEntry("status", "REJECTED")
            .containsEntry("reject_reason", "Name the starter");

        // The version sent back is the draft: changed and submitted again, under the same number.
        Map<String, Object> current = Map.of("treeId", tree.get("treeId"), "revision",
            ((Number) treeRow(tree.get("treeId")).get("revision")).longValue());
        @SuppressWarnings("unchecked")
        String nodeId = (String) query("SELECT node_id FROM qb_tree_node_def WHERE version_id = ?",
            submitted.get("versionId")).getFirst().get("node_id");
        Map<String, Object> again = save(token, current, List.of(node(nodeId, null, "Sourdough starter",
            all(passed()))));
        assertThat(again).containsEntry("versionId", submitted.get("versionId")).containsEntry("versionNo", 1)
            .containsEntry("status", "DRAFT");
        Map<String, Object> resubmitted = submit(token, again);
        assertThat(resubmitted).containsEntry("status", "REVIEW");

        // A save of a version in review takes it back to its draft (the review is withdrawn).
        Map<String, Object> inReview = Map.of("treeId", tree.get("treeId"), "revision",
            resubmitted.get("revision"));
        save(token, inReview, List.of(node(nodeId, null, "Starter", all(passed()))));
        assertThat(query("SELECT status FROM sys_approval_request_version WHERE request_id = ?::uuid ORDER BY "
            + "version_no DESC LIMIT 1", resubmitted.get("approvalRequestId")).getFirst().get("status"))
            .isEqualTo("WITHDRAWN");
    }

    @Test
    void thePlatformsTemplateIsReviewedByAnotherAdministratorAndAnOperatorCopiesIt() {
        String writer = mapWriter();
        Map<String, Object> template = run(TreeProcesses.CREATE, writer, body("ownerKind", "PLATFORM",
            "subjectKind", "SPONSOR", "name", name("Sponsor skills")));
        Map<String, Object> saved = save(writer, template, List.of(
            node(null, "start", "Getting started", all(passed())),
            node(null, "next", "Running a quiz", all(lit("start", true), passed()))));
        @SuppressWarnings("unchecked")
        Map<String, String> ids = (Map<String, String>) saved.get("nodeIds");
        Map<String, Object> submitted = submit(writer, saved);
        // The writer does not approve their own; another administrator does.
        assertThat(codes(refused("APPROVAL_DECIDE", writer, body("requestId", submitted.get("approvalRequestId"),
            "decision", "APPROVE"), 422))).containsExactly("APPROVAL_OWN_REQUEST");
        decide(mapWriter(), submitted.get("approvalRequestId"), "APPROVE", null);
        assertThat(nodes(template.get("treeId"))).hasSize(2);

        // An operator's member copies it: the copy keeps the template's node ids, as a draft.
        NewOperator operator = newOperatorWithLead(operatorsAdmin(), unique("FS2 copy"), null);
        String lead = memberSession(operator.leadId(), operator.operatorId());
        Map<String, Object> copy = run(TreeProcesses.COPY, lead, body("templateTreeId", template.get("treeId")));
        assertThat(query("SELECT node_id FROM qb_tree_node_def WHERE version_id = ? ORDER BY seq",
            copy.get("versionId"))).extracting(row -> row.get("node_id"))
            .containsExactly(ids.get("start"), ids.get("next"));
        assertThat(codes(refused(TreeProcesses.COPY, lead, body("templateTreeId", template.get("treeId")), 422)))
            .containsExactly("QB_TREE_COPY_EXISTS");
        // A Sponsor cannot copy, nor edit the operator's copy.
        String sponsor = newSponsor(unique("s-fs2-nocopy"));
        refused(TreeProcesses.SAVE, sponsorToken(sponsor), body("treeId", copy.get("treeId"), "name", name("x"),
            "nodes", List.of()), 404);

        // The copy is reviewed by another member of the operator, not the one who submitted it.
        Map<String, Object> copySubmitted = submit(lead, copy);
        String other = verifiedUser(unique("fs2-member"));
        run(com.jabiz.quizbuks.operator.OperatorProcesses.MEMBER_ADD, operatorsAdmin(), body("operatorId",
            operator.operatorId(), "userId", other, "lead", false));
        decide(memberSession(other, operator.operatorId()), copySubmitted.get("approvalRequestId"), "APPROVE",
            null);
        assertThat(nodes(copy.get("treeId"))).extracting(row -> row.get("node_id"))
            .containsExactlyInAnyOrder(ids.get("start"), ids.get("next"));

        // The template's next version changes a node; the copy keeps its own until it takes the change.
        Map<String, Object> templateNow = Map.of("treeId", template.get("treeId"), "revision",
            ((Number) treeRow(template.get("treeId")).get("revision")).longValue());
        Map<String, Object> v2 = save(writer, templateNow, List.of(
            node(ids.get("start"), null, "Getting started well", all(passed())),
            node(ids.get("next"), null, "Running a quiz", all(lit(ids.get("start"), true), passed()))));
        decide(mapWriter(), submit(writer, v2).get("approvalRequestId"), "APPROVE", null);
        assertThat(query("SELECT d.name ->> 'en' AS name FROM qb_tree t JOIN qb_tree_node_def d ON d.version_id = "
            + "t.current_version_id WHERE t.tree_id = ? AND d.node_id = ?", String.valueOf(copy.get("treeId")),
            ids.get("start")).getFirst()).containsEntry("name", "Getting started");

        // F-S3e: the editor shows the template's changes node by node before taking them.
        assertThat(templateDiff(lead, copy.get("treeId"))).extracting(row -> row.get("nodeId") + ":" + row.get("change"))
            .containsExactly(ids.get("start") + ":CHANGED", ids.get("next") + ":SAME");
        assertThat(templateDiff(lead, copy.get("treeId")).getFirst()).containsEntry("templateVersionNo", 2)
            .containsEntry("takenVersionNo", 1);
        NewOperator stranger = newOperatorWithLead(operatorsAdmin(), unique("FS3e other"), null);
        assertThat(templateDiff(memberSession(stranger.leadId(), stranger.operatorId()), copy.get("treeId")))
            .isEmpty();

        Map<String, Object> copyNow = Map.of("treeId", copy.get("treeId"), "revision",
            ((Number) treeRow(copy.get("treeId")).get("revision")).longValue());
        Map<String, Object> adopted = run(TreeProcesses.ADOPT, lead, body("treeId", copy.get("treeId"),
            "baseRevision", copyNow.get("revision"), "nodeIds", List.of(ids.get("start"))));
        assertThat(query("SELECT name ->> 'en' AS name FROM qb_tree_node_def WHERE version_id = ? AND node_id = ?",
            adopted.get("versionId"), ids.get("start")).getFirst()).containsEntry("name", "Getting started well");
        assertThat(treeRow(copy.get("treeId"))).containsEntry("template_version_no",
            new java.math.BigDecimal(2));
        assertThat(templateDiff(lead, copy.get("treeId"))).extracting(row -> row.get("change"))
            .containsOnly("SAME");
        // The list gives the editor the name and description of the open version, else the one in effect.
        assertThat(items("qb.operator.trees", lead, Map.of("limit", 50))).filteredOn(row -> copy.get("treeId")
            .equals(row.get("treeId"))).singleElement().satisfies(row -> assertThat(String.valueOf(row.get("draftName")))
                .isEqualTo(query("SELECT name::text AS name FROM qb_tree_version WHERE version_id = ?",
                    adopted.get("versionId")).getFirst().get("name")));
    }

    private List<Map<String, Object>> templateDiff(String token, Object treeId) {
        return items("qb.operator.tree-template-diff", token, Map.of("params", Map.of("treeId", treeId),
            "limit", 500));
    }

    @Test
    void retiringAMapWithdrawsItsReviewAndRefusesFurtherEdits() {
        String sponsor = newSponsor(unique("s-fs2-retire"));
        String token = sponsorToken(sponsor);
        Map<String, Object> tree = run(TreeProcesses.CREATE, token, body("ownerKind", "SPONSOR", "name",
            name("Tea")));
        Map<String, Object> submitted = submit(token, save(token, tree, List.of(node(null, "a", "Green",
            all(passed())))));
        Map<String, Object> retired = run(TreeProcesses.RETIRE, token, body("treeId", tree.get("treeId"),
            "baseRevision", submitted.get("revision")));
        assertThat(retired).containsEntry("status", "RETIRED");
        assertThat(query("SELECT status FROM sys_approval_request_version WHERE request_id = ?::uuid ORDER BY "
            + "version_no DESC LIMIT 1", submitted.get("approvalRequestId")).getFirst().get("status"))
            .isEqualTo("WITHDRAWN");
        assertThat(codes(refused(TreeProcesses.SAVE, token, body("treeId", tree.get("treeId"), "baseRevision",
            retired.get("revision"), "name", name("Tea"), "nodes", List.of()), 422))).containsExactly("QB_TREE_STATE");
        // A retired map no longer counts against the limit.
        assertThat(run(TreeProcesses.CREATE, token, body("ownerKind", "SPONSOR", "name", name("Herbs"))))
            .containsEntry("status", "DRAFT");
    }

    @Test
    void onlyTheirWritersCreateThePlatformsTemplates() {
        String sponsor = newSponsor(unique("s-fs2-platform"));
        refused(TreeProcesses.CREATE, sponsorToken(sponsor), body("ownerKind", "PLATFORM", "subjectKind", "TAKER",
            "name", name("Mine")), 403);
    }
}

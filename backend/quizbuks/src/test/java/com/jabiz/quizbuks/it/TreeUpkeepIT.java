package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.tree.TreeCodes;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.tree.TreeLighting;
import com.jabiz.quizbuks.tree.TreeNodeCounts;
import com.jabiz.quizbuks.tree.TreeProcesses;
import com.jabiz.quizbuks.tree.TreeRevocations;
import com.jabiz.quizbuks.tree.TreeUpkeep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correcting and keeping up knowledge maps (F-S2b2, docs/quizbuks/plans/impl/F-S2.md §1): revoking a lit node with
 * another member's approval (and the person told), a revoked lighting not lit again from the passes it rested on; a
 * new version keeping the nodes running publications are bound to; retiring a map ending them; the daily check and
 * recheck finding a pass that lit nothing; the reminder of a review waiting too long.
 */
class TreeUpkeepIT extends TreeItSupport {

    @Autowired
    @Qualifier("qbTreeCheckJob")
    JobDefinition<TreeUpkeep.CheckInput> checkJob;

    @Autowired
    @Qualifier("qbTreeRecheckJob")
    JobDefinition<TreeLighting.EvaluateInput> recheckJob;

    @Autowired
    @Qualifier("qbTreeReviewRemindJob")
    JobDefinition<TreeUpkeep.RemindInput> remindJob;

    @Autowired
    @Qualifier("qbTreeNodeCountJob")
    JobDefinition<TreeNodeCounts.CountInput> countJob;

    private void runJob(JobDefinition<?> job) {
        runJob(job, clock.instant());
    }

    private void runJob(JobDefinition<?> job, java.time.Instant at) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobs.execute(job, at);
    }

    private static Map<String, Object> byKey(List<Map<String, Object>> rows, String key, Object value) {
        return rows.stream().filter(row -> value.equals(row.get(key))).findFirst().orElseThrow();
    }

    private List<Map<String, Object>> lits(String userId) {
        return query("SELECT lit_id, node_id FROM qb_tree_lit_version WHERE subject_kind = 'USER' AND subject_id = ? "
            + "ORDER BY row_id", userId);
    }

    private List<Object> messageKinds(String userId) {
        return query("SELECT kind FROM qb_message WHERE recipient_id = ? ORDER BY created_at", userId).stream()
            .map(row -> row.get("kind")).toList();
    }

    private static List<Map<String, Object>> openTasks(String type) {
        return query("SELECT title_params, source_key, scope_name, scope_value FROM (SELECT DISTINCT ON (task_id) * "
            + "FROM sys_task_version ORDER BY task_id, version_no DESC) t WHERE task_type = ? AND status = 'OPEN' "
            + "AND NOT is_deleted", type);
    }

    private Map<String, Object> revoke(String token, Object litId, String reason) {
        return run(TreeRevocations.REVOKE, token, body("litId", litId, "reason", reason));
    }

    @Test
    void aLitNodeIsRevokedWithAnotherMembersApprovalAndNotLitAgainFromTheSamePass() {
        String sponsor = newSponsor(unique("s-fs2b2-revoke"));
        credit(sponsor, 10_000);
        Map3 map = map(sponsor);
        String basics = liveBound(sponsor, "Revoke basics", map.treeId(), map.basics());
        String advanced = liveBound(sponsor, "Revoke advanced", map.treeId(), map.advanced());
        String userId = newTaker(unique("t-fs2b2-revoke"), "JP");
        play(userId, basics, true, true);
        deliverEvents();
        Object litId = lits(userId).getFirst().get("lit_id");

        // Another operator's member does not find it; a request needs a reason.
        NewOperator other = officialOperator("Revoke other");
        refused(TreeRevocations.REVOKE, memberSession(other.leadId(), other.operatorId()), body("litId", litId,
            "reason", "Wrong"), 404);
        String first = platformOperatorMember();
        refused(TreeRevocations.REVOKE, first, body("litId", litId, "reason", " "), 400);

        Map<String, Object> asked = revoke(first, litId, "Answers shared in a group chat");
        assertThat(asked).containsEntry("status", "REVIEW");
        assertThat(query("SELECT scope_value FROM sys_approval_request_version WHERE request_id = ?::uuid",
            asked.get("approvalRequestId")).getFirst()).containsEntry("scope_value", platformOperator());
        // Nothing is revoked before the approval; one request at a time.
        assertThat(query("SELECT count(*) AS n FROM qb_tree_lit_revocation_version").getFirst().get("n"))
            .isEqualTo(0L);
        assertThat(codes(refused(TreeRevocations.REVOKE, first, body("litId", litId, "reason", "Again"), 422)))
            .containsExactly(TreeCodes.REVOKE_PENDING);
        // The one who asked cannot approve it (the platform's approvals: not the preparer).
        call("APPROVAL_DECIDE", first, body("requestId", asked.get("approvalRequestId"), "decision", "APPROVE"))
            .expectStatus().is4xxClientError();

        decide(platformOperatorMember(), asked.get("approvalRequestId"), "APPROVE", null);
        assertThat(query("SELECT status FROM qb_tree_lit_revoke_request WHERE request_id = ?",
            asked.get("requestId")).getFirst()).containsEntry("status", "REVOKED");
        assertThat(query("SELECT reason, requested_by FROM qb_tree_lit_revocation_version WHERE lit_id = ?",
            String.valueOf(litId)).getFirst()).containsEntry("reason", "Answers shared in a group chat");
        assertThat(messageKinds(userId)).contains("TREE_LIT_REVOKED");
        // The advanced node is locked again for them: its publication is as if it did not exist (F-S2d).
        refused("QB_ATTEMPT_START", taker(userId), body("publicationId", advanced), 404);
        assertThat(codes(refused(TreeRevocations.REVOKE, first, body("litId", litId, "reason", "Again"), 422)))
            .containsExactly(TreeCodes.ALREADY_REVOKED);

        // The pass it rested on lights nothing again: evaluated, and in the daily recheck.
        runAsSystem(TreeLighting.EVALUATE, body("subjectKind", "USER", "subjectId", userId, "treeId", map.treeId(),
            "nodeIds", List.of(map.basics()), "dependants", false, "depth", 1));
        runJob(recheckJob);
        deliverEvents();
        assertThat(lits(userId)).hasSize(1);
        // A new pass (another publication of the node) lights it anew: a new record.
        advance(Duration.ofMinutes(1));
        String again = liveBound(sponsor, "Revoke basics again", map.treeId(), map.basics());
        play(userId, again, true, true);
        deliverEvents();
        assertThat(lits(userId)).hasSize(2);
        assertThat(start(taker(userId), advanced)).containsKey("attemptId");
    }

    @Test
    void aRejectedRequestKeepsTheLighting() {
        String sponsor = newSponsor(unique("s-fs2b2-keep"));
        credit(sponsor, 10_000);
        Map3 map = map(sponsor);
        String basics = liveBound(sponsor, "Keep basics", map.treeId(), map.basics());
        String userId = newTaker(unique("t-fs2b2-keep"), "JP");
        play(userId, basics, true, true);
        deliverEvents();
        Object litId = lits(userId).getFirst().get("lit_id");
        Map<String, Object> asked = revoke(platformOperatorMember(), litId, "Looks odd");
        decide(platformOperatorMember(), asked.get("approvalRequestId"), "REJECT", "It was fine");
        assertThat(query("SELECT status, reject_reason FROM qb_tree_lit_revoke_request WHERE request_id = ?",
            asked.get("requestId")).getFirst()).containsEntry("status", "REJECTED")
            .containsEntry("reject_reason", "It was fine");
        assertThat(query("SELECT count(*) AS n FROM qb_tree_lit_revocation_version WHERE lit_id = ?",
            String.valueOf(litId)).getFirst()).containsEntry("n", 0L);
        assertThat(messageKinds(userId)).doesNotContain("TREE_LIT_REVOKED");
    }

    @Test
    void aVersionKeepsTheNodesOfRunningPublicationsAndRetiringTheMapEndsThem() {
        String sponsor = newSponsor(unique("s-fs2b2-retire"));
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        Map3 map = map(sponsor);
        String advanced = liveBound(sponsor, "Retire advanced", map.treeId(), map.advanced());
        // A new version without the advanced node: refused while its publication runs.
        Map<String, Object> tree = query("SELECT revision FROM qb_tree WHERE tree_id = ?", map.treeId()).getFirst();
        Map<String, Object> draft = save(token, Map.of("treeId", map.treeId(), "revision", tree.get("revision")),
            List.of(node(map.basics(), null, "Basics", all(passed()))));
        Map<String, Object> refusal = refused(TreeProcesses.SUBMIT, token, body("treeId", map.treeId(),
            "baseRevision", draft.get("revision")), 422);
        assertThat(codes(refusal)).containsExactly(TreeCodes.NODE_IN_USE);
        assertThat(String.valueOf(violations(refusal).getFirst().get("message"))).contains(advanced);

        // Retired: its running publications end, telling the Sponsor.
        run(TreeProcesses.RETIRE, token, body("treeId", map.treeId(), "baseRevision", draft.get("revision")));
        assertThat(row(advanced)).containsEntry("status", "ENDED").containsEntry("end_reason", "TREE");
        assertThat(outboxEvents("qb.publication.status", "publicationId", advanced)).isEqualTo(1);
    }

    @Test
    void theDailyCheckAndRecheckFindAPassThatLitNothing() {
        String sponsor = newSponsor(unique("s-fs2b2-check"));
        credit(sponsor, 10_000);
        Map3 map = map(sponsor);
        String basics = liveBound(sponsor, "Check basics", map.treeId(), map.basics());
        String userId = newTaker(unique("t-fs2b2-check"), "JP");
        play(userId, basics, true, true);
        // The pass's event is lost: recorded as consumed by the lighting's consumer, which then never sees it.
        execute("INSERT INTO sys_event_consumption (consumer, event_id, consumed_time) SELECT ?, event_id, now() "
            + "FROM sys_outbox_event WHERE event_type = 'qb.tree.passed' AND payload ->> 'userId' = ?",
            TreeLighting.PASSED_CONSUMER, userId);
        deliverEvents();
        assertThat(lits(userId)).isEmpty();
        runJob(checkJob);
        assertThat(openTasks(TreeUpkeep.CHECK_TASK_TYPE)).isNotEmpty();
        runJob(recheckJob);
        deliverEvents();
        assertThat(lits(userId)).extracting(row -> row.get("node_id")).containsExactly(map.basics());
    }

    @Test
    void theDailyCountGivesThePeopleWhoAnsweredAndHaveNotLitFromTheMinimumGroupUp() {
        String sponsor = newSponsor(unique("s-fs3f-count"));
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        Map3 map = map(sponsor);
        String basics = liveBound(sponsor, "Count basics", map.treeId(), map.basics());
        // Three answer and fail, one passes (lit, so not counted).
        for (int i = 0; i < 3; i++) {
            assertThat(play(newTaker(unique("t-fs3f-fail"), "JP"), basics, false, false)).containsEntry("passed",
                false);
        }
        String passer = newTaker(unique("t-fs3f-pass"), "JP");
        assertThat(play(passer, basics, true, true)).containsEntry("passed", true);
        deliverEvents();
        assertThat(lits(passer)).extracting(row -> row.get("node_id")).containsExactly(map.basics());
        String versionId = String.valueOf(items("qb.sponsor.trees", token, Map.of("limit", 50)).stream()
            .filter(row -> map.treeId().equals(row.get("treeId"))).findFirst().orElseThrow().get("currentVersionId"));

        // Not counted yet: no figure, no day.
        assertThat(byKey(items("qb.sponsor.tree", token, Map.of("params", Map.of("versionId", versionId))), "nodeId",
            map.basics())).containsEntry("answeredUnlit", null).containsEntry("countedOn", null);

        // The next day's run counts the day's attempts: three, under the default minimum group (5) not shown.
        java.time.Instant tomorrow = clock.instant().plus(Duration.ofDays(1));
        runJob(countJob, tomorrow);
        String day = clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
        List<Map<String, Object>> rows = query("SELECT node_id, answered_unlit, counted_on FROM qb_tree_node_count "
            + "WHERE tree_id = ? ORDER BY node_id", map.treeId());
        assertThat(rows).hasSize(3);
        assertThat(byKey(rows, "node_id", map.basics())).satisfies(row -> {
            assertThat(((Number) row.get("answered_unlit")).intValue()).isEqualTo(3);
            assertThat(String.valueOf(row.get("counted_on"))).isEqualTo(day);
        });
        assertThat(byKey(rows, "node_id", map.advanced()).get("answered_unlit")).satisfies(n ->
            assertThat(((Number) n).intValue()).isZero());
        Map<String, Object> shown = byKey(items("qb.sponsor.tree", token, Map.of("params",
            Map.of("versionId", versionId))), "nodeId", map.basics());
        assertThat(shown.get("answeredUnlit")).isNull();
        assertThat(String.valueOf(shown.get("countedOn"))).isEqualTo(day);

        // From the minimum group up, the Sponsor's page and the review page show it; the same run again changes nothing.
        setParam(QbParams.STATS_MIN_GROUP, "3");
        try {
            assertThat(byKey(items("qb.sponsor.tree", token, Map.of("params", Map.of("versionId", versionId))),
                "nodeId", map.basics()).get("answeredUnlit")).satisfies(n -> assertThat(((Number) n).intValue())
                    .isEqualTo(3));
            assertThat(byKey(items("qb.admin.tree-version", admin(), Map.of("params", Map.of("versionId",
                versionId))), "nodeId", map.basics()).get("answeredUnlit")).satisfies(n ->
                    assertThat(((Number) n).intValue()).isEqualTo(3));
        } finally {
            setParam(QbParams.STATS_MIN_GROUP, "5");
        }
        Object before = query("SELECT version FROM qb_tree_node_count WHERE tree_id = ? AND node_id = ?",
            map.treeId(), map.basics()).getFirst().get("version");
        runJob(countJob, tomorrow.plus(Duration.ofHours(1)));
        assertThat(query("SELECT version FROM qb_tree_node_count WHERE tree_id = ? AND node_id = ?", map.treeId(),
            map.basics()).getFirst().get("version")).isEqualTo(before);
    }

    @Test
    void aReviewWaitingTooLongIsRemindedOnceAndTheReminderEndsWithTheReview() {
        String sponsor = newSponsor(unique("s-fs2b2-remind"));
        String token = sponsorToken(sponsor);
        Map<String, Object> tree = run(TreeProcesses.CREATE, token, body("ownerKind", "SPONSOR", "name",
            name("Remind")));
        Map<String, Object> saved = save(token, tree, List.of(node(null, "basics", "Basics", all(passed()))));
        Map<String, Object> submitted = submit(token, saved);
        runJob(remindJob);
        assertThat(openTasks(TreeUpkeep.REMIND_TASK_TYPE).stream().filter(task -> String.valueOf(task.get(
            "source_key")).endsWith(String.valueOf(submitted.get("versionId"))))).isEmpty();
        advance(Duration.ofDays(4));
        runJob(remindJob);
        runJob(remindJob);
        List<Map<String, Object>> reminders = openTasks(TreeUpkeep.REMIND_TASK_TYPE).stream().filter(task ->
            TreeUpkeep.reminderSource(submitted.get("versionId")).equals(task.get("source_key"))).toList();
        assertThat(reminders).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("scope_value", platformOperator()));
        decide(platformOperatorMember(), submitted.get("approvalRequestId"), "APPROVE", null);
        assertThat(openTasks(TreeUpkeep.REMIND_TASK_TYPE).stream().filter(task ->
            TreeUpkeep.reminderSource(submitted.get("versionId")).equals(task.get("source_key")))).isEmpty();
    }
}

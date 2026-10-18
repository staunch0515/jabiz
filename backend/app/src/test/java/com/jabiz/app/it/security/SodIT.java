package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.ItApprovalFixtures;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalPermissions;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Segregation of duties (docs/design/18-numbering-approvals-tasks.md section 4, decision D23): access that would give
 * one user both groups of a rule is refused where it is given; a conflict that exists anyway (a rule published after
 * the roles were given) stops the processes of either group at their entry; the report lists every conflict, and
 * every holder of {@code *}. Each test names permissions of its own.
 */
@SpringBootTest
class SodIT extends SecurityItSupport {

    @SuppressWarnings("unchecked")
    String publishRule(String left, String right) {
        Map<String, Object> input = new HashMap<>(Map.of("targetEntity", ApprovalEntities.SOD_RULE, "reason", "sod",
            "values", Map.of("ruleCode", unique("SOD"), "leftPermissions", left, "rightPermissions", right,
                "enabled", true)));
        Map<String, Object> proposed = post("/api/processes/CONTROL_CHANGE_PROPOSE/latest",
            TestTokens.bearer(tokens, "sod-proposer", ApprovalPermissions.CONTROL_PROPOSE), input)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        String changeId = (String) ((Map<String, Object>) proposed.get("output")).get("changeId");
        post("/api/processes/CONTROL_CHANGE_PUBLISH/latest",
            TestTokens.bearer(tokens, "sod-publisher", ApprovalPermissions.CONTROL_PUBLISH),
            Map.of("changeId", changeId)).expectStatus().isOk();
        return changeId;
    }

    Map<String, Object> refusedInsert(String dataset, Map<String, Object> attributes) {
        return post("/api/datasets/" + dataset + "/commit", admin(),
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", attributes))))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
    }

    @Test
    void anAssignmentThatWouldJoinBothGroupsIsRefused() {
        String prepare = unique("p.prepare");
        String review = unique("p.review");
        publishRule(prepare + ", " + unique("p.other"), review);
        String user = userWith(unique("u"), "password-123", prepare, "p.read");
        String reviewer = createRole(unique("REVIEW"), review);

        Map<String, Object> problem = refusedInsert(SecurityEntities.USER_ROLE_DATASET,
            Map.of("userId", user, "roleId", reviewer));
        assertThat(ruleCode(problem)).isEqualTo("SOD_CONFLICT");
        assertThat(violations(problem).getFirst()).containsEntry("field", "roleId");

        // Another user may hold the reviewer role.
        String other = createUser(unique("v"), "password-123");
        assign(other, reviewer, null);
        // A role without either group is fine, and so is "*": it holds everything and is only reported.
        assign(user, createRole(unique("READ"), "p.read2"), null);
        assign(user, createRole(unique("ADMIN"), "*"), null);
    }

    @Test
    void aGrantThatWouldJoinBothGroupsForAHolderIsRefused() {
        String prepare = unique("g.prepare");
        String review = unique("g.review");
        publishRule(prepare, review);
        String user = userWith(unique("u"), "password-123", prepare);
        String shared = createRole(unique("SHARED"), "g.read");
        assign(user, shared, null);

        Map<String, Object> problem = refusedInsert(SecurityEntities.ROLE_PERMISSION_DATASET,
            Map.of("roleId", shared, "permission", review));
        assertThat(ruleCode(problem)).isEqualTo("SOD_CONFLICT");
        assertThat(violations(problem).getFirst()).containsEntry("field", "permission");
        // Nobody holding the role: the grant is fine.
        insert(SecurityEntities.ROLE_PERMISSION_DATASET, Map.of("roleId", createRole(unique("EMPTY")),
            "permission", review), null);
    }

    @Test
    void aConflictThatExistsAnywayStopsTheProcessesOfEitherGroupAndIsReported() {
        String review = unique("e.review");
        String userName = unique("both");
        String user = createUser(userName, "password-123");
        String role = createRole(unique("BOTH"), ItApprovalFixtures.SOD_PREPARE, review);
        assign(user, role, null);
        String wildcardName = unique("root");
        String wildcard = createUser(wildcardName, "password-123");
        assign(wildcard, createRole(unique("ROOT"), "*"), null);

        String both = TestTokens.bearer(tokens, user, ItApprovalFixtures.SOD_PREPARE, review);
        post("/api/processes/IT_SOD_PREPARE/latest", both, Map.of()).expectStatus().isOk();
        publishRule(ItApprovalFixtures.SOD_PREPARE, review);

        Map<String, Object> problem = post("/api/processes/IT_SOD_PREPARE/latest", both, Map.of())
            .expectStatus().isForbidden().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCode(problem)).isEqualTo("SOD_CONFLICT");
        // One group alone, or "*", passes.
        post("/api/processes/IT_SOD_PREPARE/latest", TestTokens.bearer(tokens, user,
            ItApprovalFixtures.SOD_PREPARE), Map.of()).expectStatus().isOk();
        post("/api/processes/IT_SOD_PREPARE/latest", TestTokens.bearer(tokens, wildcard, "*"), Map.of())
            .expectStatus().isOk();

        List<Map<String, Object>> report = get("/api/sod/conflicts", TestTokens.bearer(tokens, "auditor",
            ApprovalPermissions.SOD_READ)).expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
        assertThat(report).filteredOn(row -> userName.equals(row.get("userName"))).singleElement()
            .satisfies(row -> {
                assertThat(row).containsEntry("userId", user).containsEntry("wildcard", false)
                    .containsEntry("left", List.of(ItApprovalFixtures.SOD_PREPARE))
                    .containsEntry("right", List.of(review));
                assertThat((List<?>) row.get("roles")).hasSize(1);
            });
        assertThat(report).filteredOn(row -> wildcardName.equals(row.get("userName")))
            .isNotEmpty().allSatisfy(row -> assertThat(row).containsEntry("wildcard", true));
        get("/api/sod/conflicts", TestTokens.bearer(tokens, "nobody")).expectStatus().isForbidden();
    }

    /**
     * 16r (decision D48, review M2): a revert that brings an assignment back, and a cancelled scheduled removal, give
     * access too and are checked like an insertion (they did not pass the check before).
     */
    @Test
    void aRevertOrACancellationThatWouldJoinBothGroupsIsRefused() {
        String prepare = unique("r.prepare");
        String review = unique("r.review");
        String user = userWith(unique("u"), "password-123", prepare);
        String reviewer = createRole(unique("REVIEW"), review);
        String removed = createRole(unique("REVIEW2"), review);
        // Both groups before the rule: one assignment removed, one scheduled to go.
        assign(user, reviewer, null);
        assign(user, removed, null);
        String revertable = assignmentOf(user, removed);
        post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", admin(), Map.of("changes",
            List.of(Map.of("action", "DELETE", "id", revertable, "version", 1)))).expectStatus().isOk();
        String scheduled = assignmentOf(user, reviewer);
        String later = clock.instant().plus(java.time.Duration.ofDays(5)).toString();
        post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", admin(), Map.of("changes",
            List.of(Map.of("action", "DELETE", "id", scheduled, "version", 1, "effectiveTime", later))))
            .expectStatus().isOk();
        publishRule(prepare, review);

        Object deletion = query("SELECT process_seq_id FROM sec_user_role_version WHERE user_role_id = ?::uuid AND"
            + " version_no = 2", revertable).getFirst().get("process_seq_id");
        // Holdings count each assignment's latest version: neither counts now, each would come back.
        Map<String, Object> cancelled = post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit",
            admin(), Map.of("changes", List.of(Map.of("action", "CANCEL_SCHEDULED", "id", scheduled, "version", 2,
                "effectiveTime", later)))).expectStatus().isEqualTo(422).expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(ruleCode(cancelled)).isEqualTo("SOD_CONFLICT");
        Map<String, Object> reverted = post("/api/processes/executions/" + deletion + "/revert", admin(),
            Map.of("reason", "undo")).expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCode(reverted)).isEqualTo("SOD_CONFLICT");
    }

    private static String assignmentOf(String user, String role) {
        return String.valueOf(query("SELECT user_role_id::text AS id FROM sec_user_role_version WHERE user_id ="
            + " ?::uuid AND role_id = ?::uuid", user, role).getFirst().get("id"));
    }
}

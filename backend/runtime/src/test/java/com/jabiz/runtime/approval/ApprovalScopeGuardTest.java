package com.jabiz.runtime.approval;

import com.jabiz.entity.Violation;
import com.jabiz.runtime.operation.Operation;
import com.jabiz.runtime.task.TaskEntities;
import com.jabiz.temporal.EntityVersion;
import com.jabiz.temporal.Timeline;
import com.jabiz.temporal.VersionAction;
import com.jabiz.temporal.VersionPlanner.PlannedVersion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The entry scope of requests and tasks changes only through APPROVAL_SCOPE_MOVE (decision D76). */
class ApprovalScopeGuardTest {

    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-02T00:00:00Z");
    private static final String ID = "019c0000-0000-7000-8000-000000000001";
    private static final long SEQ = 42;

    private final ApprovalScopeGuard guard = new ApprovalScopeGuard();

    private static Map<String, Object> request(String status, String name, String value) {
        Map<String, Object> state = new HashMap<>();
        state.put("requestId", ID);
        state.put("status", status);
        state.put("scopeName", name);
        state.put("scopeValue", value);
        return state;
    }

    private static Timeline stored(Map<String, Object> state) {
        return Timeline.of(List.of(new EntityVersion(1, T0, T0, false, state, Set.of(), VersionAction.INSERT, null,
            1)));
    }

    private static List<PlannedVersion> written(Map<String, Object> state) {
        return List.of(new PlannedVersion(2, T1, false, state, Set.of("scopeValue"), VersionAction.UPDATE, 1L));
    }

    private static Operation operation(long seq) {
        return new Operation(seq, T1, ApprovalScopeMove.MOVE, 1, "it", null, 1L, null);
    }

    private List<Violation> check(String entity, Map<String, Object> before, Map<String, Object> after,
        long seq, Object grant) {
        var def = ApprovalEntities.REQUEST.equals(entity) ? ApprovalEntities.APPROVAL_REQUEST : TaskEntities.SYS_TASK;
        return guard.check(def, stored(before), written(after), operation(seq), grant);
    }

    private static ApprovalScopeGrant grant(String entity, String name, String value) {
        return new ApprovalScopeGrant(entity, ID, name, value, SEQ);
    }

    @Test
    void theMoveWithItsGrantPasses() {
        assertThat(check(ApprovalEntities.REQUEST, request("PENDING", "op", "a"), request("PENDING", "op", "b"), SEQ,
            grant(ApprovalEntities.REQUEST, "op", "b"))).isEmpty();
        Map<String, Object> task = request("OPEN", "op", "a");
        task.put("taskId", ID);
        Map<String, Object> moved = new HashMap<>(task);
        moved.put("scopeValue", "b");
        assertThat(check(TaskEntities.TASK, task, moved, SEQ, grant(TaskEntities.TASK, "op", "b"))).isEmpty();
    }

    @Test
    void writesThatKeepTheScopeAndOtherEntitiesPass() {
        assertThat(check(ApprovalEntities.REQUEST, request("PENDING", "op", "a"), request("APPROVED", "op", "a"),
            SEQ, null)).isEmpty();
        assertThat(guard.check(ApprovalEntities.APPROVAL_RULE, stored(request("PENDING", "op", "a")),
            written(request("PENDING", "op", "b")), operation(SEQ), null)).isEmpty();
        // An insertion sets the scope as made.
        assertThat(guard.check(ApprovalEntities.APPROVAL_REQUEST, Timeline.of(List.of()),
            List.of(new PlannedVersion(1, T1, false, request("PENDING", "op", "a"), Set.of(), VersionAction.INSERT,
                null)), operation(SEQ), null)).isEmpty();
    }

    @Test
    void everyOtherChangeOfTheScopeIsRefused() {
        Map<String, Object> pending = request("PENDING", "op", "a");
        // No grant, another operation, another instance, another value, another entity.
        assertThat(check(ApprovalEntities.REQUEST, pending, request("PENDING", "op", "b"), SEQ, null))
            .extracting(Violation::ruleCode).containsExactly("IMMUTABLE_FIELD");
        assertThat(check(ApprovalEntities.REQUEST, pending, request("PENDING", "op", "b"), SEQ + 1,
            grant(ApprovalEntities.REQUEST, "op", "b"))).hasSize(1);
        assertThat(check(ApprovalEntities.REQUEST, pending, request("PENDING", "op", "b"), SEQ,
            new ApprovalScopeGrant(ApprovalEntities.REQUEST, "other", "op", "b", SEQ))).hasSize(1);
        assertThat(check(ApprovalEntities.REQUEST, pending, request("PENDING", "op", "c"), SEQ,
            grant(ApprovalEntities.REQUEST, "op", "b"))).hasSize(1);
        assertThat(check(ApprovalEntities.REQUEST, pending, request("PENDING", "op", "b"), SEQ,
            grant(TaskEntities.TASK, "op", "b"))).hasSize(1);
        // The name never changes, nor does a scope appear on a request of none.
        assertThat(check(ApprovalEntities.REQUEST, pending, request("PENDING", "region", "b"), SEQ,
            grant(ApprovalEntities.REQUEST, "region", "b"))).extracting(Violation::field).containsExactly("scopeName");
        assertThat(check(ApprovalEntities.REQUEST, request("PENDING", null, null), request("PENDING", "op", "b"), SEQ,
            grant(ApprovalEntities.REQUEST, "op", "b"))).hasSize(1);
        // Decided requests stay where they were decided, and the move does not decide.
        assertThat(check(ApprovalEntities.REQUEST, request("APPROVED", "op", "a"), request("APPROVED", "op", "b"), SEQ,
            grant(ApprovalEntities.REQUEST, "op", "b"))).hasSize(1);
        assertThat(check(ApprovalEntities.REQUEST, pending, request("APPROVED", "op", "b"), SEQ,
            grant(ApprovalEntities.REQUEST, "op", "b"))).hasSize(1);
    }
}

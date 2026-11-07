package com.jabiz.runtime.approval;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.operation.Operation;
import com.jabiz.runtime.task.TaskEntities;
import com.jabiz.runtime.temporal.TemporalWriteGuard;
import com.jabiz.temporal.EntityVersion;
import com.jabiz.temporal.Timeline;
import com.jabiz.temporal.VersionPlanner.PlannedVersion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps the entry scope of approval requests and tasks (decision D64) as it was made, but for the move of
 * {@code APPROVAL_SCOPE_MOVE} (decision D76): a write that changes {@code scopeName} or {@code scopeValue} of a stored
 * {@code SysApprovalRequest} or {@code SysTask} passes only with an {@link ApprovalScopeGrant} for that instance, the
 * unchanged name, the new value and the writing operation, and only while the request stays pending or the task open.
 * The dataset API, the generic entity processes, other writes of any process, cancellations and reverts all end here
 * (422 {@code IMMUTABLE_FIELD}). An insertion sets the scope as made.
 */
@Component
class ApprovalScopeGuard implements TemporalWriteGuard {

    private static final String NAME = "scopeName";
    private static final String VALUE = "scopeValue";
    private static final String STATUS = "status";

    @Override
    public List<Violation> check(EntityDefinition def, Timeline timeline, List<PlannedVersion> versions,
        Operation operation, Object grant) {
        String open;
        if (ApprovalEntities.REQUEST.equals(def.name)) {
            open = ApprovalEntities.PENDING;
        } else if (TaskEntities.TASK.equals(def.name)) {
            open = TaskEntities.OPEN;
        } else {
            return List.of();
        }
        List<Violation> refused = new ArrayList<>();
        for (PlannedVersion version : versions) {
            Optional<EntityVersion> before = base(timeline, version);
            if (before.isEmpty()) {
                continue;
            }
            Map<String, Object> was = before.get().state();
            Map<String, Object> now = version.state();
            if (Objects.equals(was.get(NAME), now.get(NAME)) && Objects.equals(was.get(VALUE), now.get(VALUE))) {
                continue;
            }
            boolean allowed = !version.deleted() && grant instanceof ApprovalScopeGrant granted
                && granted.allows(def.name, was.get(def.primaryKey), now.get(NAME), now.get(VALUE),
                    operation.processSeqId())
                && Objects.equals(was.get(NAME), now.get(NAME))
                && open.equals(was.get(STATUS)) && open.equals(now.get(STATUS));
            if (!allowed) {
                String field = Objects.equals(was.get(NAME), now.get(NAME)) ? VALUE : NAME;
                refused.add(new Violation(field, PlatformErrorCodes.IMMUTABLE_FIELD, "The entry scope of "
                    + def.name + " changes only through APPROVAL_SCOPE_MOVE, while it is " + open,
                    Map.of("field", field)));
            }
        }
        return refused;
    }

    /** The stored version a planned one is based on: the one it names, else the one in effect when it starts. */
    private static Optional<EntityVersion> base(Timeline timeline, PlannedVersion version) {
        if (timeline.versions().isEmpty()) {
            return Optional.empty();
        }
        if (version.baseVersionNo() != null) {
            Optional<EntityVersion> named = timeline.version(version.baseVersionNo());
            if (named.isPresent()) {
                return named;
            }
        }
        return timeline.at(version.effectiveFrom());
    }
}

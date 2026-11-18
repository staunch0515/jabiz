package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.operation.Operation;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.temporal.TemporalWriteCheck;
import com.jabiz.temporal.Timeline;
import com.jabiz.temporal.VersionPlanner.PlannedVersion;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the waiting role ({@link WaitingRole#CODE}) the first launch's alone, on every path a temporal write takes
 * (decision D82): 422 {@code FIRST_LAUNCH_ROLE_RESERVED} for
 * <ul>
 *   <li>a version of the role that is enabled or does not require a second factor, or an assignment of it coming
 *       alive, unless written by the system in one of {@link #PROCESSES} with a {@link FirstLaunchGrant} for that
 *       operation - so nobody enables or gives it again once the run has ended;</li>
 *   <li>any permission of the role coming alive, whoever writes it.</li>
 * </ul>
 * Disabling the role, or withdrawing it from a user, is anybody's who may write roles.
 */
@Component
class WaitingRoleWriteCheck implements TemporalWriteCheck {

    /** The processes that write the waiting role (each checks the run's state itself). */
    static final Set<String> PROCESSES = Set.of(FirstLaunchAccountProcesses.CREATE, FirstLaunchAccountProcesses.ADVANCE);

    private static final String ROLE = SqlIdentifiers.require(SecurityEntities.SEC_ROLE.physicalTable);
    private static final String ROLE_ID = SqlIdentifiers.require(SecurityEntities.SEC_ROLE.fields.get("roleId")
        .physicalColumn());
    private static final String ROLE_CODE = SqlIdentifiers.require(SecurityEntities.SEC_ROLE.fields.get("roleCode")
        .physicalColumn());

    @Override
    public Mono<List<Violation>> check(StorageEngine engine, EntityDefinition def, UUID id, Timeline timeline,
        List<PlannedVersion> versions, Operation operation, Object grant) {
        return switch (def.name) {
            case SecurityEntities.ROLE -> Mono.just(role(def, id, versions, operation, grant));
            case SecurityEntities.USER_ROLE -> TemporalWriteCheck.comesAlive(timeline, versions)
                .map(state -> waiting(engine, state.get("roleId")).map(waiting -> waiting && !granted(operation, grant)
                    ? refused(def, id, "is given only by the first launch") : List.<Violation>of()))
                .orElse(Mono.just(List.of()));
            case SecurityEntities.ROLE_PERMISSION -> TemporalWriteCheck.comesAlive(timeline, versions)
                .map(state -> waiting(engine, state.get("roleId")).map(waiting -> waiting
                    ? refused(def, id, "never holds a permission") : List.<Violation>of()))
                .orElse(Mono.just(List.of()));
            default -> Mono.just(List.of());
        };
    }

    private static List<Violation> role(EntityDefinition def, UUID id, List<PlannedVersion> versions,
        Operation operation, Object grant) {
        for (PlannedVersion version : versions) {
            Map<String, Object> state = version.state();
            if (version.deleted() || !WaitingRole.CODE.equals(state.get("roleCode"))) {
                continue;
            }
            boolean opens = Boolean.TRUE.equals(state.get("enabled")) || !Boolean.TRUE.equals(state.get("requireMfa"));
            if (opens && !granted(operation, grant)) {
                return refused(def, id, "is enabled, and requires a second factor, only by the first launch");
            }
        }
        return List.of();
    }

    private static boolean granted(Operation operation, Object grant) {
        return grant instanceof FirstLaunchGrant granted && granted.allows(operation.processSeqId())
            && PROCESSES.contains(operation.processName())
            && RequestContext.SYSTEM_ACTOR.equals(operation.actorId());
    }

    /** Whether {@code roleId} is the waiting role's. */
    private static Mono<Boolean> waiting(StorageEngine engine, Object roleId) {
        UUID role = Rows.uuid(roleId);
        if (role == null) {
            return Mono.just(false);
        }
        return engine.select("SELECT " + ROLE_CODE + " AS code FROM " + ROLE + " WHERE " + ROLE_ID
                + " = :id ORDER BY version_no DESC LIMIT 1", Map.of("id", BoundValue.of(role)))
            .next().map(row -> Objects.equals(WaitingRole.CODE, Rows.string(row.get("code")))).defaultIfEmpty(false);
    }

    private static List<Violation> refused(EntityDefinition def, UUID id, String why) {
        return List.of(new Violation(null, PlatformErrorCodes.FIRST_LAUNCH_ROLE_RESERVED, def.name + " [ID: " + id
            + "]: the first launch's waiting role " + WaitingRole.CODE + " " + why, Map.of("entity", def.name)));
    }
}

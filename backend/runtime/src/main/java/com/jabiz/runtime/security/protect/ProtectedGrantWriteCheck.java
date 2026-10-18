package com.jabiz.runtime.security.protect;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.operation.Operation;
import com.jabiz.runtime.security.BootstrapAdmin;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.temporal.TemporalWriteCheck;
import com.jabiz.temporal.Timeline;
import com.jabiz.temporal.VersionPlanner.PlannedVersion;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The four eyes on protected grants at the place every temporal write passes (decision D48 item 1): a write that makes
 * a role assignment ({@code SecUserRole}), a role's permission ({@code SecRolePermission}) or another user's link to an
 * identity provider ({@code SecUserIdentity}) come alive - an insertion, a revert of its deletion, a cancelled deletion
 * - so that a user gains a protected permission (or someone else gets a way into the account of a holder of one) is
 * refused with 422 {@code PROTECTED_CHANGE_REQUIRES_APPROVAL}, unless it is the write of an approved change
 * ({@link ProtectedChangeGrant}), the platform's own first administrator on an empty database
 * ({@link BootstrapGrant}), or the setup window is still open. The first administrator is refused (403) once another
 * administrator exists. Such writes first take the lock segregation of duties takes ({@code jabiz.sod}), so that two
 * concurrent changes of access - on any path, reverts and cancellations included - cannot each pass alone (review M2).
 * Every write of the security entities records the end of the setup window and the first administrator's retirement
 * when they are due, so that taking administrators away cannot undo them.
 */
@Component
class ProtectedGrantWriteCheck implements TemporalWriteCheck {

    private static final Set<String> RECORDING = Set.of(SecurityEntities.USER, SecurityEntities.ROLE,
        SecurityEntities.ROLE_PERMISSION, SecurityEntities.USER_ROLE, SecurityEntities.USER_IDENTITY);

    private final ProtectedGrants grants;

    ProtectedGrantWriteCheck(ProtectedGrants grants) {
        this.grants = grants;
    }

    @Override
    public Mono<List<Violation>> check(StorageEngine engine, EntityDefinition def, UUID id, Timeline timeline,
        List<PlannedVersion> versions, Operation operation, Object grant) {
        if (!grants.enabled() || !RECORDING.contains(def.name)) {
            return Mono.just(List.of());
        }
        Optional<Map<String, Object>> alive = TemporalWriteCheck.comesAlive(timeline, versions);
        ProtectedGrants.Draft draft = alive.map(state -> draft(def.name, state)).orElse(null);
        if (draft == null) {
            // Not a grant: only whether the setup window ends, or the first administrator retires, now.
            return grants.record(operation.processSeqId(), operation.opTime()).thenReturn(List.of());
        }
        if (grant instanceof ProtectedChangeGrant granted && granted.allows(def.name, operation.processSeqId())) {
            return Mono.just(List.of());
        }
        return grants.lock().then(bootstrapping(engine, grant, operation)).flatMap(bootstrapping -> bootstrapping
            ? Mono.just(List.<Violation>of())
            : grants.assess(draft, operation.actorId(), operation.processSeqId(), operation.opTime())
            .flatMap(assessment -> {
                if (!assessment.protectedChange()) {
                    return grants.record(operation.processSeqId(), operation.opTime())
                        .thenReturn(List.<Violation>of());
                }
                if (assessment.retiredBootstrap()) {
                    return Mono.error(new BootstrapAdminRetiredException("Other administrators exist: the first"
                        + " administrator may no longer give protected permissions (" + assessment.gained() + ")"));
                }
                if (!assessment.needsApproval()) {
                    return Mono.just(List.<Violation>of());
                }
                String process = ProtectedChangeProcesses.requestProcess(draft.kind());
                return Mono.just(List.of(new Violation(null, PlatformErrorCodes.PROTECTED_CHANGE_REQUIRES_APPROVAL,
                    def.name + " [ID: " + id + "] gives protected permissions " + assessment.gained()
                        + " and needs another administrator's approval: request it with " + process,
                    Map.of("entity", def.name, "process", process))));
            }));
    }

    /**
     * Whether this is the first administrator being made (review M1): the platform's {@code SEC_BOOTSTRAP_ADMIN}, run
     * by the system with its {@link BootstrapGrant}, on a database where no user exists but those of this operation.
     * With {@code .always} there is no setup window, and nobody could approve the first administrator.
     */
    private static Mono<Boolean> bootstrapping(StorageEngine engine, Object grant, Operation operation) {
        if (!(grant instanceof BootstrapGrant granted) || !granted.allows(operation.processSeqId())
            || !BootstrapAdmin.PROCESS.name().equals(operation.processName())
            || !RequestContext.SYSTEM_ACTOR.equals(operation.actorId())) {
            return Mono.just(false);
        }
        return engine.select("SELECT 1 AS other FROM sec_user_version WHERE process_seq_id <> :seq LIMIT 1",
            Map.of("seq", BoundValue.of(operation.processSeqId()))).hasElements().map(other -> !other);
    }

    private static ProtectedGrants.Draft draft(String entity, Map<String, Object> state) {
        return switch (entity) {
            case SecurityEntities.USER_ROLE -> new ProtectedGrants.Draft(ProtectedChangeEntities.USER_ROLE,
                Rows.uuid(state.get("userId")), Rows.uuid(state.get("roleId")), null);
            case SecurityEntities.ROLE_PERMISSION -> new ProtectedGrants.Draft(ProtectedChangeEntities.ROLE_PERMISSION,
                null, Rows.uuid(state.get("roleId")), (String) state.get("permission"));
            case SecurityEntities.USER_IDENTITY -> new ProtectedGrants.Draft(ProtectedChangeEntities.IDENTITY_LINK,
                Rows.uuid(state.get("userId")), null, null);
            default -> null;
        };
    }
}

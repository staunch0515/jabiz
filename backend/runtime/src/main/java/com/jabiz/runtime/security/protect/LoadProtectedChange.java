package com.jabiz.runtime.security.protect;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Loads what applying an approved protected change needs ({@code SEC_PROTECTED_CHANGE_APPLY}): the change (only for
 * events of {@value ProtectedChangeEntities#SUBJECT}), the names for its case, and - for a pending change being
 * approved - the approver as the decision records it, the user, the second factor to remove, and whether it can still
 * be made as approved ({@link Checked}).
 */
@Component
class LoadProtectedChange implements StepHandler<LoadProtectedChange.Metadata, ProcessContext> {

    static final String CHANGE = "protected_change_loaded";
    static final String NAMES = "protected_change_names";
    static final String USER = "protected_change_user";
    static final String MFA = "protected_change_mfa";
    static final String CHECKED = "protected_change_checked";
    /** Who approved, as the decision records it (not as the event says). */
    static final String DECIDER = "protected_change_decider";
    /** The users the change is for, judged now. */
    static final String AFFECTED = "protected_change_affected";

    record Metadata(String inputKey, String changeKey) {}

    /** @param failure why it cannot be made as approved (a code), null when it can */
    record Checked(String failure) {}

    static StepSpec<Metadata, ProcessContext> of(String inputKey, String changeKey) {
        return StepSpec.of(LoadProtectedChange.class, new Metadata(inputKey, changeKey));
    }

    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry definitions;
    private final ProtectedGrants grants;
    private final StorageAdapterRegistry storages;
    private final String poolRef;

    LoadProtectedChange(DatasetEntityManager entities, DatasetRegistry datasets, EntityDefinitionRegistry definitions,
        ProtectedGrants grants, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.entities = entities;
        this.datasets = datasets;
        this.definitions = definitions;
        this.grants = grants;
        this.storages = storages;
        this.poolRef = poolRef;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            ProtectedChangeProcesses.ApplyInput input = ctx.get(metadata.inputKey(),
                ProtectedChangeProcesses.ApplyInput.class);
            if (!ProtectedChangeEntities.SUBJECT.equals(input.subject()) || input.entityId() == null) {
                return Mono.empty();
            }
            Optional<UUID> changeId = uuid(input.entityId());
            if (changeId.isEmpty()) {
                return Mono.empty();
            }
            return find(ProtectedChangeEntities.CHANGE_DATASET, ProtectedChangeEntities.CHANGE, changeId.get())
                .flatMap(change -> {
                    ctx.put(metadata.changeKey(), change);
                    UUID user = Rows.uuid(change.get("userId"));
                    UUID role = Rows.uuid(change.get("roleId"));
                    return grants.names(user, role).flatMap(names -> {
                        ctx.put(NAMES, List.copyOf(names));
                        if (!ProtectedChangeEntities.PENDING.equals(change.get("status"))
                            || !ApprovalEntities.APPROVED.equals(input.status())) {
                            return Mono.<Void>empty();
                        }
                        return check(change, input.requestId(), ctx);
                    });
                });
        });
    }

    /**
     * Whether the approved change can be made as approved, judged now (review H1, H2, M3, L2), in this order:
     * {@code CHANGE_GREW} (it would give more protected permissions, or to more users, than were approved);
     * {@code APPROVER_NOT_ALLOWED} (the approver, read from the recorded decision, is the requester, a user it is for,
     * the retired first administrator, or does not hold an administrator's permission and everything the change gives);
     * {@code TARGET_CREDENTIALS_NOT_OWN} (a user gaining a protected permission has a password, second factor or
     * provider link someone else set); then the target already exists or is gone.
     */
    private Mono<Void> check(EntityInstance change, String requestId, ProcessContext ctx) {
        String kind = change.get("kind");
        UUID user = Rows.uuid(change.get("userId"));
        UUID role = Rows.uuid(change.get("roleId"));
        String requester = change.get("requestedBy");
        Set<String> approvedGains = ProtectedChangeProcesses.split(change.get("gained"));
        Set<String> approvedUsers = ProtectedChangeProcesses.split(change.get("affectedUsers"));
        Mono<String> decider = uuid(requestId).map(request -> engine().select("SELECT approver_id FROM"
                + " sys_approval_decision WHERE request_id = :request AND decision = 'APPROVE'"
                + " ORDER BY level_no DESC LIMIT 1", Map.of("request", BoundValue.of(request)))
            .next().map(row -> Rows.string(row.get("approver_id"))).defaultIfEmpty("")).orElse(Mono.just(""));
        Mono<ProtectedGrants.Assessment> now = grants.lock().then(grants.assess(
            ProtectedChangeProcesses.draftOf(change), requester, ctx.processSeqId(), ctx.opTime(),
            String.valueOf(change.id())));
        return Mono.zip(decider, now).flatMap(found -> {
            String approver = found.getT1();
            ProtectedGrants.Assessment assessment = found.getT2();
            ctx.put(DECIDER, approver);
            ctx.put(AFFECTED, assessment.affected());
            Set<String> users = new TreeSet<>();
            assessment.affected().forEach(id -> users.add(id.toString()));
            if (!approvedGains.containsAll(assessment.gained()) || !approvedUsers.containsAll(users)) {
                return failed(ctx, "CHANGE_GREW");
            }
            Mono<Boolean> allowed = approver.isEmpty() || approver.equals(requester) || users.contains(approver)
                ? Mono.just(false)
                : grants.retiredBootstrap(approver, ctx.processSeqId(), ctx.opTime()).flatMap(retired -> retired
                    ? Mono.just(false)
                    : uuid(approver).map(id -> grants.effectiveHoldings(id, ctx.opTime()).map(held ->
                        ProtectedChangeDecisionCheck.covers(held, approvedGains))).orElse(Mono.just(false)));
            return allowed.flatMap(ok -> {
                if (!ok) {
                    return failed(ctx, "APPROVER_NOT_ALLOWED");
                }
                boolean grant = kind.equals(ProtectedChangeEntities.USER_ROLE)
                    || kind.equals(ProtectedChangeEntities.ROLE_PERMISSION);
                Mono<Boolean> own = !grant ? Mono.just(true)
                    : Flux.fromIterable(assessment.affected()).concatMap(grants::credentials)
                        .all(ProtectedGrants.Credentials::own);
                return own.flatMap(owned -> owned ? target(change, kind, user, role, ctx)
                    : failed(ctx, "TARGET_CREDENTIALS_NOT_OWN"));
            });
        });
    }

    private static Mono<Void> failed(ProcessContext ctx, String code) {
        ctx.put(CHECKED, new Checked(code));
        return Mono.empty();
    }

    /** The checks of the target itself: it must not exist already, or must still be there. */
    private Mono<Void> target(EntityInstance change, String kind, UUID user, UUID role, ProcessContext ctx) {
        Mono<String> failure = switch (kind) {
            case ProtectedChangeEntities.USER_ROLE -> exists(SecurityEntities.USER_ROLE_DATASET,
                SecurityEntities.USER_ROLE, List.of(new QueryPredicate.Eq("userId", user),
                    new QueryPredicate.Eq("roleId", role)), "ALREADY_ASSIGNED");
            case ProtectedChangeEntities.ROLE_PERMISSION -> exists(SecurityEntities.ROLE_PERMISSION_DATASET,
                SecurityEntities.ROLE_PERMISSION, List.of(new QueryPredicate.Eq("roleId", role),
                    new QueryPredicate.Eq("permission", change.get("permission"))), "ALREADY_GRANTED");
            case ProtectedChangeEntities.IDENTITY_LINK -> exists(SecurityEntities.USER_IDENTITY_DATASET,
                SecurityEntities.USER_IDENTITY, List.of(new QueryPredicate.Eq("provider", change.get("provider")),
                    new QueryPredicate.Eq("subject", change.get("subject"))), "IDENTITY_IN_USE");
            case ProtectedChangeEntities.PASSWORD -> find(SecurityEntities.USER_DATASET, SecurityEntities.USER, user)
                .doOnNext(found -> ctx.put(USER, found)).map(found -> "").defaultIfEmpty("USER_NOT_FOUND");
            // Only the enrolment the reset was approved for (review L2): one made since is the user's own.
            case ProtectedChangeEntities.MFA_RESET -> first(SecurityEntities.USER_MFA_DATASET,
                SecurityEntities.USER_MFA, List.of(new QueryPredicate.Eq("userId", user)))
                .map(found -> {
                    if (!String.valueOf(found.id()).equals(change.get("mfaId"))) {
                        return "MFA_CHANGED";
                    }
                    ctx.put(MFA, found);
                    return "";
                }).defaultIfEmpty("MFA_NOT_ENROLLED");
            default -> Mono.just("UNKNOWN_KIND");
        };
        return failure.doOnNext(code -> ctx.put(CHECKED, new Checked(code.isEmpty() ? null : code))).then();
    }

    /** {@code code} when an instance matching all of {@code where} exists; "" otherwise. */
    private Mono<String> exists(String dataset, String entity, List<QueryPredicate> where, String code) {
        return first(dataset, entity, where).map(found -> code).defaultIfEmpty("");
    }

    private Mono<EntityInstance> first(String dataset, String entity, List<QueryPredicate> where) {
        DatasetDefinition definition = datasets.findById(dataset).orElseThrow();
        EntityDefinition def = definitions.getOrThrow(entity);
        return entities.query(definition, def, EntityQuery.builder().where(new QueryPredicate.And(where)).limit(1)
            .build()).next();
    }

    private Mono<EntityInstance> find(String dataset, String entity, UUID id) {
        if (id == null) {
            return Mono.empty();
        }
        return Mono.defer(() -> entities.findById(datasets.findById(dataset).orElseThrow(),
                definitions.getOrThrow(entity), id))
            .onErrorResume(EntityNotFoundException.class, e -> Mono.empty());
    }

    private static Optional<UUID> uuid(String text) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private StorageEngine engine() {
        return storages.getEngine(poolRef);
    }
}

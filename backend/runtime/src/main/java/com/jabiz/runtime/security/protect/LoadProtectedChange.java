package com.jabiz.runtime.security.protect;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
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
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Loads what applying an approved protected change needs ({@code SEC_PROTECTED_CHANGE_APPLY}): the change (only for
 * events of {@value ProtectedChangeEntities#SUBJECT}), the names for its case, and - for a pending change being
 * approved - the user, the second factor to remove, and whether it can still be made as approved ({@link Checked}):
 * the approver must still not be its requester, a user it is for, or the retired first administrator, and the
 * target must not exist already.
 */
@Component
class LoadProtectedChange implements StepHandler<LoadProtectedChange.Metadata, ProcessContext> {

    static final String CHANGE = "protected_change_loaded";
    static final String NAMES = "protected_change_names";
    static final String USER = "protected_change_user";
    static final String MFA = "protected_change_mfa";
    static final String CHECKED = "protected_change_checked";

    record Metadata(String inputKey, String changeKey) {}

    /** @param failure why it cannot be made as approved (a code), null when it can */
    record Checked(boolean approverNotAllowed, String failure) {}

    static StepSpec<Metadata, ProcessContext> of(String inputKey, String changeKey) {
        return StepSpec.of(LoadProtectedChange.class, new Metadata(inputKey, changeKey));
    }

    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry definitions;
    private final ProtectedGrants grants;

    LoadProtectedChange(DatasetEntityManager entities, DatasetRegistry datasets, EntityDefinitionRegistry definitions,
        ProtectedGrants grants) {
        this.entities = entities;
        this.datasets = datasets;
        this.definitions = definitions;
        this.grants = grants;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            ProtectedChangeProcesses.ApplyInput input = ctx.get(metadata.inputKey(),
                ProtectedChangeProcesses.ApplyInput.class);
            if (!ProtectedChangeEntities.SUBJECT.equals(input.subject()) || input.entityId() == null) {
                return Mono.empty();
            }
            UUID changeId;
            try {
                changeId = UUID.fromString(input.entityId());
            } catch (IllegalArgumentException e) {
                return Mono.empty();
            }
            return find(ProtectedChangeEntities.CHANGE_DATASET, ProtectedChangeEntities.CHANGE, changeId)
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
                        return check(change, input.approverId(), ctx);
                    });
                });
        });
    }

    private Mono<Void> check(EntityInstance change, String approver, ProcessContext ctx) {
        String kind = change.get("kind");
        UUID user = Rows.uuid(change.get("userId"));
        UUID role = Rows.uuid(change.get("roleId"));
        Mono<Boolean> approverNotAllowed = grants.assess(ProtectedChangeProcesses.draftOf(change),
                change.get("requestedBy"), ctx.processSeqId(), ctx.opTime())
            .map(assessment -> approver == null || approver.equals(change.get("requestedBy"))
                || assessment.affected().stream().anyMatch(id -> id.toString().equals(approver)))
            .zipWith(grants.retiredBootstrap(approver), (target, retired) -> target || retired);
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
            case ProtectedChangeEntities.MFA_RESET -> first(SecurityEntities.USER_MFA_DATASET,
                SecurityEntities.USER_MFA, List.of(new QueryPredicate.Eq("userId", user)))
                .doOnNext(found -> ctx.put(MFA, found)).map(found -> "").defaultIfEmpty("MFA_NOT_ENROLLED");
            default -> Mono.just("UNKNOWN_KIND");
        };
        return approverNotAllowed.zipWith(failure).doOnNext(found -> ctx.put(CHECKED,
            new Checked(found.getT1(), found.getT2().isEmpty() ? null : found.getT2()))).then();
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
}

package com.jabiz.runtime.security;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.security.LoginAttemptPolicy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The actor a user is now, for refreshing a session (docs/design/10-security.md section 2): the user must still exist,
 * be enabled, not be locked and hold a role in effect; roles and permissions are read afresh, so changes reach the
 * user at the next refresh at the latest.
 */
@Component
public class RbacService {

    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final LoginAttemptPolicy policy;
    private final Clock clock;

    public RbacService(DatasetEntityManager entities, DatasetRegistry datasets, LoginAttemptPolicy policy,
        Clock clock) {
        this.entities = entities;
        this.datasets = datasets;
        this.policy = policy;
        this.clock = clock;
    }

    /** The actor, or empty when the user may not act (any more). */
    public Mono<Optional<Actor>> currentActor(UUID userId) {
        return entities.findById(dataset(SecurityEntities.USER_DATASET), SecurityEntities.SEC_USER, userId)
            .filter(Rbac::enabled)
            .flatMap(user -> query(SecurityEntities.LOGIN_RECORD_DATASET, SecurityEntities.SEC_LOGIN_RECORD,
                    Rbac.latestLoginRecordOf(user.id()))
                .flatMap(latest -> {
                    LoginAttemptPolicy.State state = Rbac.state(latest.isEmpty() ? null : latest.getFirst());
                    if (policy.isLocked(state, clock.instant())) {
                        return Mono.empty();
                    }
                    return access(user.id()).filter(access -> !access.roles().isEmpty())
                        .map(access -> new Actor(String.valueOf(user.id()), user.get("tenantId"), access.roles(),
                            access.permissions()));
                }))
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty());
    }

    private Mono<Rbac.Access> access(Object userId) {
        return query(SecurityEntities.USER_ROLE_DATASET, SecurityEntities.SEC_USER_ROLE, Rbac.assignmentsOf(userId))
            .flatMap(assignments -> query(SecurityEntities.ROLE_DATASET, SecurityEntities.SEC_ROLE,
                Rbac.rolesOf(assignments)))
            .flatMap(roles -> query(SecurityEntities.ROLE_PERMISSION_DATASET, SecurityEntities.SEC_ROLE_PERMISSION,
                Rbac.permissionsOf(roles)).map(grants -> Rbac.access(roles, grants)));
    }

    private Mono<List<EntityInstance>> query(String datasetId, EntityDefinition def, EntityQuery query) {
        return entities.query(dataset(datasetId), def, query).collectList();
    }

    private DatasetDefinition dataset(String id) {
        return datasets.findById(id).orElseThrow();
    }
}

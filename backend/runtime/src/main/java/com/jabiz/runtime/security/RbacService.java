package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.security.LoginAttemptPolicy;
import com.jabiz.security.SignInAttempt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The actor a user is now, for refreshing a session (docs/design/10-security.md section 2): the user must still exist,
 * be enabled, not be locked and hold a role in effect that the session's entry accepts; roles and permissions are read
 * afresh, so changes reach the user at the next refresh at the latest. The entry's verified-address requirement and
 * the applications' sign-in guards are asked again too (section 15; decision D36).
 */
@Component
public class RbacService {

    /**
     * The outcome of renewing a session.
     *
     * @param actor   the actor of the renewed session, or null when the user may not act (any more)
     * @param refused whether a sign-in guard refused: the session then ends (its family is revoked)
     */
    public record Renewal(Actor actor, boolean refused) {

        static final Renewal DENIED = new Renewal(null, false);
        static final Renewal REFUSED = new Renewal(null, true);

        public Optional<Actor> granted() {
            return Optional.ofNullable(actor);
        }
    }

    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final LoginAttemptPolicy policy;
    private final Clock clock;
    private final SignInEntries entries;
    private final SignInGuards guards;

    public RbacService(DatasetEntityManager entities, DatasetRegistry datasets, LoginAttemptPolicy policy,
        Clock clock, SignInEntries entries, SignInGuards guards) {
        this.entities = entities;
        this.datasets = datasets;
        this.policy = policy;
        this.clock = clock;
        this.entries = entries;
        this.guards = guards;
    }

    /** The actor, or empty when the user may not act (any more). */
    public Mono<Optional<Actor>> currentActor(UUID userId) {
        return currentActor(userId, null);
    }

    /**
     * The actor of a session whose sign-in passed a second factor at {@code mfaAt} (null if it did not), or empty
     * when the user may not act (any more): also when a role now requires a second factor the session lacks
     * (docs/design/10-security.md section 9).
     */
    public Mono<Optional<Actor>> currentActor(UUID userId, Instant mfaAt) {
        return currentActor(userId, mfaAt, null);
    }

    /**
     * As above, for a session that came through the provider account {@code identityId} (null for none): its link to
     * the user must still exist, so that unlinking an account ends its sessions at the next refresh
     * (docs/design/10-security.md section 12).
     */
    public Mono<Optional<Actor>> currentActor(UUID userId, Instant mfaAt, UUID identityId) {
        return renew(userId, mfaAt, identityId, RequestContext.DEFAULT_ENTRY).map(Renewal::granted);
    }

    /** As above, for a session of the sign-in entry {@code entry}. */
    public Mono<Renewal> renew(UUID userId, Instant mfaAt, UUID identityId, String entry) {
        if (identityId != null) {
            return entities.findById(dataset(SecurityEntities.USER_IDENTITY_DATASET), SecurityEntities.SEC_USER_IDENTITY,
                    identityId)
                .filter(identity -> userId.toString().equals(String.valueOf(identity.<Object>get("userId"))))
                .flatMap(identity -> renew(userId, mfaAt, null, entry))
                .defaultIfEmpty(Renewal.DENIED);
        }
        SignInEntries.Entry resolved = entries.resolve(entry);
        Instant now = clock.instant();
        return entities.findById(dataset(SecurityEntities.USER_DATASET), SecurityEntities.SEC_USER, userId)
            .filter(Rbac::enabled)
            .flatMap(user -> query(SecurityEntities.LOGIN_RECORD_DATASET, SecurityEntities.SEC_LOGIN_RECORD,
                    Rbac.latestLoginRecordOf(user.id()))
                .flatMap(latest -> {
                    LoginAttemptPolicy.State state = Rbac.state(latest.isEmpty() ? null : latest.getFirst());
                    boolean verified = SecurityEntities.emailVerified(user);
                    if (policy.isLocked(state, now) || (resolved.requireVerifiedEmail() && !verified)) {
                        return Mono.just(Renewal.DENIED);
                    }
                    return access(user.id(), resolved)
                        .flatMap(access -> {
                            if (access.roles().isEmpty() || (mfaAt == null && access.mfaRequired())) {
                                return Mono.just(Renewal.DENIED);
                            }
                            Actor actor = new Actor(String.valueOf(user.id()), user.get("tenantId"), access.roles(),
                                access.permissions(), mfaAt, access.dataPeriod(), resolved.name(), verified);
                            return guards.check(actor.actorId(), user.get("userName"), resolved.name(),
                                    access.roles(), verified, SignInAttempt.Factor.REFRESH, now)
                                .map(decision -> decision.allowed() ? new Renewal(actor, false) : Renewal.REFUSED);
                        });
                }))
            .defaultIfEmpty(Renewal.DENIED);
    }

    /** The access of the user's roles in effect that the entry accepts. */
    private Mono<Rbac.Access> access(Object userId, SignInEntries.Entry entry) {
        return query(SecurityEntities.USER_ROLE_DATASET, SecurityEntities.SEC_USER_ROLE, Rbac.assignmentsOf(userId))
            .flatMap(assignments -> query(SecurityEntities.ROLE_DATASET, SecurityEntities.SEC_ROLE,
                Rbac.rolesOf(assignments))
                .map(roles -> Rbac.acceptedBy(roles, entry))
                .flatMap(roles -> query(SecurityEntities.ROLE_PERMISSION_DATASET,
                    SecurityEntities.SEC_ROLE_PERMISSION, Rbac.permissionsOf(roles))
                    .map(grants -> Rbac.access(assignments, roles, grants))));
    }

    private Mono<List<EntityInstance>> query(String datasetId, EntityDefinition def, EntityQuery query) {
        return entities.query(dataset(datasetId), def, query).collectList();
    }

    private DatasetDefinition dataset(String id) {
        return datasets.findById(id).orElseThrow();
    }
}

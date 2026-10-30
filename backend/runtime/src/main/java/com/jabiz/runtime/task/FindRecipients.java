package com.jabiz.runtime.task;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.security.SignInEntries;
import com.jabiz.runtime.security.SignInGuards;
import com.jabiz.security.SignInAttempt;
import com.jabiz.runtime.sod.SodService;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Who hears of a task: its assignee, or every enabled user granted its permission through an enabled role (literally:
 * holders of {@code *} are not written to about every task). Only users with an e-mail address; into the context as a
 * list of {@link Recipient}s.
 *
 * <p>A task given to a permission in an entry scope (decision D64) is written about to those who would see it: holders
 * whose role granting the permission some entry without that scope accepts, and holders who reach it through an entry
 * of that scope only and whose session there has the task's value (the entry's resolver, as at a refresh).
 */
@Component
class FindRecipients<C extends ProcessContext> implements StepHandler<FindRecipients.Metadata, C> {

    record Recipient(String userId, String address) {}

    record Metadata(String taskKey, String targetKey) {}

    static <C extends ProcessContext> StepSpec<Metadata, C> of(String taskKey, String targetKey) {
        return StepSpec.of(FindRecipients.class, new Metadata(taskKey, targetKey));
    }

    private static final String USERS = SodService.latest(SecurityEntities.SEC_USER,
        "user_name, email, verified_email, enabled");

    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final SignInEntries entries;
    private final SignInGuards guards;

    FindRecipients(StorageAdapterRegistry storages, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        SignInEntries entries, SignInGuards guards) {
        this.storages = storages;
        this.poolRef = poolRef;
        this.entries = entries;
        this.guards = guards;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, C ctx) {
        return Mono.defer(() -> {
            EntityInstance task = ctx.get(metadata.taskKey(), EntityInstance.class);
            String user = task.get("assigneeUserId");
            String sql;
            Map<String, BoundValue> params;
            if (user != null) {
                sql = "SELECT u.user_id, u.email FROM " + USERS + " u WHERE u.user_id::text = :user AND u.enabled AND NOT u.is_deleted";
                params = Map.of("user", BoundValue.of(user));
            } else {
                sql = "SELECT DISTINCT u.user_id, u.user_name, u.email, u.verified_email, r.role_code FROM " + USERS + " u"
                    + " JOIN " + SodService.latest(SecurityEntities.SEC_USER_ROLE, "user_id, role_id")
                    + " ur ON ur.user_id = u.user_id AND NOT ur.is_deleted"
                    + " JOIN " + SodService.latest(SecurityEntities.SEC_ROLE, "role_code, enabled")
                    + " r ON r.role_id = ur.role_id AND r.enabled AND NOT r.is_deleted"
                    + " JOIN " + SodService.latest(SecurityEntities.SEC_ROLE_PERMISSION, "role_id, permission")
                    + " rp ON rp.role_id = r.role_id AND NOT rp.is_deleted AND rp.permission = :permission"
                    + " WHERE u.enabled AND NOT u.is_deleted";
                params = Map.of("permission", BoundValue.of(task.<String>get("assigneePermission")));
            }
            String scope = user == null ? task.get("scopeName") : null;
            String value = task.get("scopeValue");
            return storages.getEngine(poolRef)
                .select("SELECT * FROM (" + sql + ") x WHERE x.email IS NOT NULL ORDER BY user_id", params)
                .collectList()
                .flatMapMany(rows -> scope == null || value == null ? Flux.fromIterable(rows)
                    : Flux.fromIterable(rows).groupBy(row -> String.valueOf(row.get("user_id")))
                        .concatMap(Flux::collectList)
                        .concatMap(holder -> sees(holder, scope, value, ctx.opTime())
                            .flatMapMany(yes -> yes ? Flux.just(holder.getFirst()) : Flux.empty())))
                .map(row -> new Recipient(String.valueOf(row.get("user_id")), (String) row.get("email")))
                .distinct()
                .collectList()
                .doOnNext(found -> ctx.put(metadata.targetKey(), List.copyOf(found)))
                .then();
        });
    }

    /**
     * Whether a holder of the permission (one row per role granting it) sees a task of the entry scope {@code scope}'s
     * value {@code value}: through an entry without that scope, or through one of it whose resolver gives them the value.
     */
    private Mono<Boolean> sees(List<Map<String, Object>> holder, String scope, String value, java.time.Instant now) {
        String userId = String.valueOf(holder.getFirst().get("user_id"));
        String userName = (String) holder.getFirst().get("user_name");
        boolean verified = com.jabiz.security.EmailVerification.verified((String) holder.getFirst().get("email"),
            (String) holder.getFirst().get("verified_email"));
        List<SignInEntries.Entry> reaching = entries.all().stream()
            .filter(entry -> holder.stream().anyMatch(row -> entry.accepts((String) row.get("role_code"))))
            .toList();
        // An entry without a scope shows every task; one of another scope shows none of this one's.
        if (reaching.stream().anyMatch(entry -> guards.scopeOf(entry.name()).isEmpty())) {
            return Mono.just(true);
        }
        // One holder after another, each through the entries of the scope: the few holders of a permission.
        return Flux.fromIterable(reaching)
            .filter(entry -> guards.scopeOf(entry.name()).map(scope::equals).orElse(false))
            .concatMap(entry -> guards.check(userId, userName, entry.name(), holder.stream()
                    .map(row -> (String) row.get("role_code")).filter(entry::accepts)
                    .collect(java.util.stream.Collectors.toSet()), verified, SignInAttempt.Factor.REFRESH, now))
            .any(decision -> decision.allowed() && decision.scope() != null && value.equals(decision.scope().value()));
    }
}

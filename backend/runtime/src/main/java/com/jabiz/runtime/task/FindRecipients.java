package com.jabiz.runtime.task;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.sod.SodService;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Who hears of a task: its assignee, or every enabled user granted its permission through an enabled role (literally:
 * holders of {@code *} are not written to about every task). Only users with an e-mail address; into the context as a
 * list of {@link Recipient}s.
 */
@Component
class FindRecipients<C extends ProcessContext> implements StepHandler<FindRecipients.Metadata, C> {

    record Recipient(String userId, String address) {}

    record Metadata(String taskKey, String targetKey) {}

    static <C extends ProcessContext> StepSpec<Metadata, C> of(String taskKey, String targetKey) {
        return StepSpec.of(FindRecipients.class, new Metadata(taskKey, targetKey));
    }

    private static final String USERS = SodService.latest(SecurityEntities.SEC_USER, "email, enabled");

    private final StorageAdapterRegistry storages;
    private final String poolRef;

    FindRecipients(StorageAdapterRegistry storages, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.poolRef = poolRef;
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
                sql = "SELECT DISTINCT u.user_id, u.email FROM " + USERS + " u"
                    + " JOIN " + SodService.latest(SecurityEntities.SEC_USER_ROLE, "user_id, role_id")
                    + " ur ON ur.user_id = u.user_id AND NOT ur.is_deleted"
                    + " JOIN " + SodService.latest(SecurityEntities.SEC_ROLE, "enabled")
                    + " r ON r.role_id = ur.role_id AND r.enabled AND NOT r.is_deleted"
                    + " JOIN " + SodService.latest(SecurityEntities.SEC_ROLE_PERMISSION, "role_id, permission")
                    + " rp ON rp.role_id = r.role_id AND NOT rp.is_deleted AND rp.permission = :permission"
                    + " WHERE u.enabled AND NOT u.is_deleted";
                params = Map.of("permission", BoundValue.of(task.<String>get("assigneePermission")));
            }
            return storages.getEngine(poolRef)
                .select("SELECT user_id, email FROM (" + sql + ") x WHERE x.email IS NOT NULL ORDER BY user_id",
                    params)
                .map(row -> new Recipient(String.valueOf(row.get("user_id")), (String) row.get("email")))
                .collectList()
                .doOnNext(found -> ctx.put(metadata.targetKey(), List.copyOf(found)))
                .then();
        });
    }
}

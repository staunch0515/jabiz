package com.jabiz.runtime.security;

import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The names people are shown by (docs/design/10-security.md section 14): data keeps the user id of whoever prepared,
 * approved or changed it, and pages show the user's display name. Another user's login name is never given out (it
 * is half of what signs them in); a user without a display name is shown by id.
 */
@Component
public class UserNames {

    /** Most ids one call resolves: a page of a register. */
    public static final int MAX_IDS = 200;

    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;

    public UserNames(DatasetEntityManager entities, DatasetRegistry datasets) {
        this.entities = entities;
        this.datasets = datasets;
    }

    /**
     * The display names of the users among {@code ids} that a caller of {@code tenantId} may see, by id: users of
     * that tenant and users of none (null: every user, a deployment without tenants). An id that is no such user's,
     * a user without a display name, and ids beyond the first {@link #MAX_IDS} have no entry.
     */
    public Mono<Map<String, String>> of(Collection<String> ids, String tenantId) {
        return users(ids, tenantId).map(users -> {
            Map<String, String> names = new LinkedHashMap<>();
            for (EntityInstance user : users) {
                String display = user.get("displayName");
                if (display != null && !display.isBlank()) {
                    names.put(String.valueOf(user.id()), display);
                }
            }
            return names;
        });
    }

    /** The name the signed-in user is shown by to themselves: their display name, else their user name. */
    public Mono<String> own(String actorId) {
        return users(List.of(actorId), null).map(users -> users.stream().findFirst().map(user -> {
            String display = user.get("displayName");
            return display == null || display.isBlank() ? user.<String>get("userName") : display;
        }).orElse(""));
    }

    /** How the signed-in user is shown to themselves, and their own e-mail address (null if none). */
    public record Self(String name, String email) {}

    /** {@link #own} with the user's own e-mail address (docs/design/10-security.md section 15); empty for non-users. */
    public Mono<Self> self(String actorId) {
        return users(List.of(actorId), null).map(users -> users.stream().findFirst().map(user -> {
            String display = user.get("displayName");
            return new Self(display == null || display.isBlank() ? user.<String>get("userName") : display,
                user.<String>get("email"));
        }).orElse(new Self("", null)));
    }

    private Mono<List<EntityInstance>> users(Collection<String> ids, String tenantId) {
        List<Object> wanted = ids.stream().filter(Objects::nonNull).distinct().limit(MAX_IDS).map(UserNames::uuid)
            .filter(Objects::nonNull).map(Object.class::cast).toList();
        if (wanted.isEmpty()) {
            return Mono.just(List.of());
        }
        List<QueryPredicate> where = new ArrayList<>(List.of(new QueryPredicate.In("userId", wanted)));
        if (tenantId != null) {
            where.add(new QueryPredicate.Or(List.of(new QueryPredicate.Eq("tenantId", tenantId),
                new QueryPredicate.IsNull("tenantId"))));
        }
        EntityQuery query = EntityQuery.builder().where(new QueryPredicate.And(where)).limit(MAX_IDS).build();
        return entities.query(datasets.findById(SecurityEntities.USER_DATASET).orElseThrow(), SecurityEntities.SEC_USER,
            query).collectList();
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

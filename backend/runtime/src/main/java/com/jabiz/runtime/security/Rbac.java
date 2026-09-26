package com.jabiz.runtime.security;

import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.security.LoginAttemptPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a user may do, derived from the security entities (docs/design/10-security.md section 3): the roles assigned to
 * the user that are in effect and enabled, and the permissions granted to those roles. Shared by the sign-in process
 * and the token refresh, so both apply the same rules.
 */
public final class Rbac {

    /** Role codes and permission codes of a user. */
    public record Access(Set<String> roles, Set<String> permissions) {
        public Access {
            roles = Set.copyOf(roles);
            permissions = Set.copyOf(permissions);
        }
    }

    private Rbac() {}

    /** The user's role assignments in effect. */
    public static EntityQuery assignmentsOf(Object userId) {
        return EntityQuery.builder().where(userId == null
            ? new QueryPredicate.In("userId", List.of())
            : new QueryPredicate.Eq("userId", userId)).build();
    }

    /** The roles of the assignments. */
    public static EntityQuery rolesOf(Collection<EntityInstance> assignments) {
        return EntityQuery.builder().where(new QueryPredicate.In("roleId", ids(assignments, "roleId"))).build();
    }

    /** The permissions granted to the enabled roles among {@code roles}. */
    public static EntityQuery permissionsOf(Collection<EntityInstance> roles) {
        List<Object> enabled = roles.stream().filter(Rbac::enabled).map(EntityInstance::id).toList();
        return EntityQuery.builder().where(new QueryPredicate.In("roleId", enabled)).build();
    }

    /** The latest login record of a user: it holds the failure counter and lock in force. */
    public static EntityQuery latestLoginRecordOf(Object userId) {
        return EntityQuery.builder()
            .where(userId == null ? new QueryPredicate.In("userId", List.of()) : new QueryPredicate.Eq("userId", userId))
            .orderBy("attemptNo", false)
            .limit(1)
            .build();
    }

    /** Codes of the enabled roles and the permissions they grant. */
    public static Access access(Collection<EntityInstance> roles, Collection<EntityInstance> rolePermissions) {
        Set<Object> enabledIds = new LinkedHashSet<>();
        Set<String> codes = new TreeSet<>();
        for (EntityInstance role : roles) {
            if (enabled(role)) {
                enabledIds.add(String.valueOf(role.id()));
                codes.add(role.get("roleCode"));
            }
        }
        Set<String> permissions = new TreeSet<>();
        for (EntityInstance grant : rolePermissions) {
            if (enabledIds.contains(String.valueOf(grant.<Object>get("roleId")))) {
                permissions.add(grant.get("permission"));
            }
        }
        return new Access(codes, permissions);
    }

    /** The counters carried by a login record; {@link LoginAttemptPolicy.State#INITIAL} for none. */
    public static LoginAttemptPolicy.State state(EntityInstance record) {
        if (record == null) {
            return LoginAttemptPolicy.State.INITIAL;
        }
        return new LoginAttemptPolicy.State(number(record.get("attemptNo")), (int) number(record.get("failureCount")),
            record.<Instant>get("lockedUntil"));
    }

    public static boolean enabled(EntityInstance instance) {
        return Boolean.TRUE.equals(instance.get("enabled"));
    }

    private static long number(Object value) {
        return value instanceof BigDecimal decimal ? decimal.longValueExact() : ((Number) value).longValue();
    }

    private static List<Object> ids(Collection<EntityInstance> instances, String field) {
        List<Object> ids = new ArrayList<>();
        instances.forEach(instance -> ids.add(instance.get(field)));
        return ids;
    }
}

package com.jabiz.runtime.security;

import com.jabiz.context.DataPeriod;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.security.LoginAttemptPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
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

    /**
     * Role codes and permission codes of a user, and whether one of the roles requires a second factor
     * (docs/design/10-security.md section 9).
     */
    /**
     * @param dataPeriod the span of business time the actor may see in datasets declaring {@code withinDataPeriod},
     *                   or null when not limited (docs/design/10-security.md section 13.2)
     */
    public record Access(Set<String> roles, Set<String> permissions, boolean mfaRequired, DataPeriod dataPeriod) {
        public Access {
            roles = Set.copyOf(roles);
            permissions = Set.copyOf(permissions);
        }

        public Access(Set<String> roles, Set<String> permissions, boolean mfaRequired) {
            this(roles, permissions, mfaRequired, null);
        }

        public Access(Set<String> roles, Set<String> permissions) {
            this(roles, permissions, false);
        }
    }

    /**
     * Most rows one security query reads (also the query limit of the security datasets). Access is never computed
     * from a truncated list: reaching the limit is an error ({@link #complete}).
     */
    public static final int MAX_ROWS = 5000;

    private Rbac() {}

    /** The user's role assignments in effect. */
    public static EntityQuery assignmentsOf(Object userId) {
        return all(userId == null
            ? new QueryPredicate.In("userId", List.of())
            : new QueryPredicate.Eq("userId", userId), "roleId");
    }

    /** The roles of the assignments. */
    public static EntityQuery rolesOf(Collection<EntityInstance> assignments) {
        return all(new QueryPredicate.In("roleId", ids(complete(assignments), "roleId")), "roleCode");
    }

    /** The permissions granted to the enabled roles among {@code roles}. */
    public static EntityQuery permissionsOf(Collection<EntityInstance> roles) {
        List<Object> enabled = complete(roles).stream().filter(Rbac::enabled).map(EntityInstance::id).toList();
        return all(new QueryPredicate.In("roleId", enabled), "permission");
    }

    /** Every match, in a stable order; see {@link #complete}. */
    public static EntityQuery all(QueryPredicate where, String orderBy) {
        return EntityQuery.builder().where(where).orderBy(orderBy, true).limit(MAX_ROWS).build();
    }

    /** The rows of a query built by {@link #all}; fails when they may have been cut off at {@link #MAX_ROWS}. */
    public static <T extends Collection<EntityInstance>> T complete(T rows) {
        if (rows.size() >= MAX_ROWS) {
            throw new IllegalStateException("A security query reached " + MAX_ROWS + " rows; access is not computed "
                + "from a truncated list");
        }
        return rows;
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
    /**
     * As {@link #access(Collection, Collection)}, with the data period of the assignments of enabled roles: none if
     * one of them is not limited, else the smallest period covering them all ({@link DataPeriod#hull}).
     */
    public static Access access(Collection<EntityInstance> assignments, Collection<EntityInstance> roles,
        Collection<EntityInstance> rolePermissions) {
        Access access = access(roles, rolePermissions);
        complete(assignments);
        Set<String> enabledIds = new HashSet<>();
        roles.stream().filter(Rbac::enabled).forEach(role -> enabledIds.add(String.valueOf(role.id())));
        List<DataPeriod> periods = new ArrayList<>();
        for (EntityInstance assignment : assignments) {
            if (enabledIds.contains(String.valueOf(assignment.<Object>get("roleId")))) {
                periods.add(DataPeriod.of(instant(assignment.get("dataFrom")), instant(assignment.get("dataTo"))));
            }
        }
        return new Access(access.roles(), access.permissions(), access.mfaRequired(), DataPeriod.hull(periods));
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> instant;
            case java.time.OffsetDateTime time -> time.toInstant();
            default -> Instant.parse(String.valueOf(value));
        };
    }

    public static Access access(Collection<EntityInstance> roles, Collection<EntityInstance> rolePermissions) {
        complete(roles);
        complete(rolePermissions);
        Set<Object> enabledIds = new LinkedHashSet<>();
        Set<String> codes = new TreeSet<>();
        boolean mfaRequired = false;
        for (EntityInstance role : roles) {
            if (enabled(role)) {
                enabledIds.add(String.valueOf(role.id()));
                codes.add(role.get("roleCode"));
                mfaRequired |= Boolean.TRUE.equals(role.get("requireMfa"));
            }
        }
        Set<String> permissions = new TreeSet<>();
        for (EntityInstance grant : rolePermissions) {
            if (enabledIds.contains(String.valueOf(grant.<Object>get("roleId")))) {
                permissions.add(grant.get("permission"));
            }
        }
        return new Access(codes, permissions, mfaRequired);
    }

    /** The last TOTP step accepted before, as carried by a login record; -1 for none. */
    public static long mfaStep(EntityInstance record) {
        Object step = record == null ? null : record.get("mfaStep");
        return step == null ? -1 : number(step);
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

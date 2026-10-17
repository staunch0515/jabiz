package com.jabiz.runtime.security.protect;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.query.BoundValue;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.security.BootstrapAdmin;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.security.ProtectedPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * What the four eyes on protected grants need to know (decision D48 item 1; docs/design/10-security.md section 16).
 *
 * <ul>
 *   <li><b>Holdings</b> count what a user could hold: every assignment, role and grant whose latest version is not
 *       deleted, enabled or not, scheduled or not (as segregation of duties does, 18 section 4.2). Enabling a role or a
 *       user therefore never gives anything a check did not see.</li>
 *   <li><b>Administrators</b> are enabled users holding {@code *} through an enabled role. The <b>first
 *       administrator</b> is the user {@code SEC_BOOTSTRAP_ADMIN} created (with the role {@code ADMIN}).</li>
 *   <li>The <b>setup window</b> ends ("armed") the first time two administrators besides the first one exist, and
 *       never opens again ({@code sec_protected_grants_armed}); with {@code .always} there is none.</li>
 * </ul>
 * Reads go through the engine of the writing transaction, so they see its own writes.
 */
@Component
public class ProtectedGrants {

    /** A protected change as asked for, before anything is written. */
    public record Draft(String kind, UUID userId, UUID roleId, String permission) {
        public Draft {
            Objects.requireNonNull(kind, "kind must not be null");
        }
    }

    /**
     * What a change would do.
     *
     * @param gained           the protected permissions it gives (a role, a role's permission), or those its user
     *                         holds (a credential reset); empty: nothing protected
     * @param affected         the users it is for, who may not approve it
     * @param armed            whether the setup window has ended (computed only for protected changes)
     * @param retiredBootstrap whether the acting user is the first administrator and another one exists (computed
     *                         only for protected changes)
     */
    public record Assessment(String kind, Set<String> gained, List<UUID> affected, boolean armed,
        boolean retiredBootstrap) {

        public Assessment {
            gained = Set.copyOf(gained);
            affected = List.copyOf(affected);
        }

        public boolean protectedChange() {
            return !gained.isEmpty();
        }

        public boolean needsApproval() {
            return protectedChange() && armed;
        }
    }

    private final ProtectedGrantSettings settings;
    private final StorageAdapterRegistry storages;
    private final String poolRef;

    public ProtectedGrants(ProtectedGrantSettings settings, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.settings = settings;
        this.storages = storages;
        this.poolRef = poolRef;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public ProtectedPermissions permissions() {
        return settings.permissions();
    }

    /** What {@code draft}, made by {@code actorId} in operation {@code processSeqId}, would do. */
    public Mono<Assessment> assess(Draft draft, String actorId, long processSeqId, Instant now) {
        if (!settings.enabled()) {
            return Mono.just(new Assessment(draft.kind(), Set.of(), List.of(), false, false));
        }
        return gained(draft, actorId).flatMap(found -> {
            Set<String> gained = found.gained();
            if (gained.isEmpty()) {
                return Mono.just(new Assessment(draft.kind(), gained, found.affected(), false, false));
            }
            return armed(processSeqId, now).zipWith(retiredBootstrap(actorId))
                .map(state -> new Assessment(draft.kind(), gained, found.affected(), state.getT1(), state.getT2()));
        });
    }

    private record Gain(Set<String> gained, List<UUID> affected) {}

    private Mono<Gain> gained(Draft draft, String actorId) {
        ProtectedPermissions set = settings.permissions();
        return switch (draft.kind()) {
            case ProtectedChangeEntities.USER_ROLE -> grantsOf(draft.roleId())
                .zipWith(holdings(List.of(draft.userId())))
                .map(found -> new Gain(set.gained(found.getT1(), held(found.getT2(), draft.userId())),
                    List.of(draft.userId())));
            case ProtectedChangeEntities.ROLE_PERMISSION -> !set.isProtected(draft.permission())
                ? Mono.just(new Gain(Set.of(), List.of()))
                : usersOf(draft.roleId()).flatMap(users -> users.isEmpty()
                    ? Mono.just(new Gain(Set.of(), List.of()))
                    : holdings(users).map(holdings -> {
                        List<UUID> affected = users.stream().filter(user -> !set.gained(
                            List.of(draft.permission()), held(holdings, user)).isEmpty()).toList();
                        return new Gain(affected.isEmpty() ? Set.of() : Set.of(draft.permission()), affected);
                    }));
            case ProtectedChangeEntities.IDENTITY_LINK -> draft.userId().toString().equals(actorId)
                // Linking one's own account: the user proved who they are (D39), nobody else gains a way in.
                ? Mono.just(new Gain(Set.of(), List.of()))
                : heldBy(draft.userId(), set);
            case ProtectedChangeEntities.PASSWORD, ProtectedChangeEntities.MFA_RESET -> heldBy(draft.userId(), set);
            default -> Mono.error(new IllegalArgumentException("Unknown protected change kind " + draft.kind()));
        };
    }

    private Mono<Gain> heldBy(UUID user, ProtectedPermissions set) {
        return holdings(List.of(user)).map(holdings -> new Gain(set.among(held(holdings, user)), List.of(user)));
    }

    private static Set<String> held(Map<UUID, Set<String>> holdings, UUID user) {
        return holdings.getOrDefault(user, Set.of());
    }

    /**
     * Whether the setup window has ended: always with {@code .always}; once recorded, for good; otherwise when two
     * administrators besides the first one exist now, which this records in operation {@code processSeqId}.
     */
    public Mono<Boolean> armed(long processSeqId, Instant now) {
        if (settings.always()) {
            return Mono.just(true);
        }
        return engine().select("SELECT 1 AS armed FROM " + ARMED + " WHERE armed_id = 1", Map.of()).hasElements()
            .flatMap(armed -> armed ? Mono.just(true) : admins().zipWith(bootstrapUser().defaultIfEmpty(""))
                .flatMap(found -> {
                    Set<String> others = new LinkedHashSet<>(found.getT1());
                    others.remove(found.getT2());
                    if (others.size() < 2) {
                        return Mono.just(false);
                    }
                    return engine().select("INSERT INTO " + ARMED + " (armed_id, armed_time, process_seq_id)"
                            + " VALUES (1, :now, :seq) ON CONFLICT (armed_id) DO NOTHING RETURNING armed_id",
                            Map.of("now", BoundValue.of(now), "seq", BoundValue.of(processSeqId)))
                        .then(Mono.just(true));
                }));
    }

    /** Whether {@code actorId} is the first administrator while another administrator exists. */
    public Mono<Boolean> retiredBootstrap(String actorId) {
        if (actorId == null || RequestContext.SYSTEM_ACTOR.equals(actorId)) {
            return Mono.just(false);
        }
        return bootstrapUser().filter(actorId::equals)
            .flatMap(bootstrap -> admins().map(admins -> admins.stream().anyMatch(admin -> !admin.equals(bootstrap))))
            .defaultIfEmpty(false);
    }

    /** The administrators: enabled users holding {@code *} through an enabled role (ids as text). */
    public Mono<Set<String>> admins() {
        String sql = "SELECT DISTINCT ur.uid::text AS user_id"
            + " FROM (SELECT DISTINCT ON (" + RP_ID + ") " + RP_ROLE + " AS rid, is_deleted FROM " + RP
            + " WHERE " + RP_PERMISSION + " = :all ORDER BY " + RP_ID + ", version_no DESC) rp"
            + " JOIN (SELECT DISTINCT ON (" + ROLE_ID + ") " + ROLE_ID + " AS rid, " + ROLE_ENABLED
            + " AS enabled, is_deleted FROM " + ROLE + " WHERE " + ROLE_ID + " IN (SELECT " + RP_ROLE + " FROM " + RP
            + " WHERE " + RP_PERMISSION + " = :all) ORDER BY " + ROLE_ID + ", version_no DESC) r ON r.rid = rp.rid"
            + " JOIN (SELECT DISTINCT ON (" + UR_ID + ") " + UR_USER + " AS uid, " + UR_ROLE + " AS rid, is_deleted"
            + " FROM " + UR + " WHERE " + UR_ROLE + " IN (SELECT " + RP_ROLE + " FROM " + RP + " WHERE "
            + RP_PERMISSION + " = :all) ORDER BY " + UR_ID + ", version_no DESC) ur ON ur.rid = rp.rid"
            + " JOIN (SELECT DISTINCT ON (" + USER_ID + ") " + USER_ID + " AS uid, " + USER_ENABLED
            + " AS enabled, is_deleted FROM " + USER + " WHERE " + USER_ID + " IN (SELECT " + UR_USER + " FROM " + UR
            + " WHERE " + UR_ROLE + " IN (SELECT " + RP_ROLE + " FROM " + RP + " WHERE " + RP_PERMISSION
            + " = :all)) ORDER BY " + USER_ID + ", version_no DESC) u ON u.uid = ur.uid"
            + " WHERE NOT rp.is_deleted AND NOT r.is_deleted AND r.enabled AND NOT ur.is_deleted"
            + " AND NOT u.is_deleted AND u.enabled";
        return engine().select(sql, Map.of("all", BoundValue.of(RequestContext.ALL_PERMISSIONS)))
            .map(row -> Rows.string(row.get("user_id"))).collect(TreeSet<String>::new, Set::add)
            .map(Set::copyOf);
    }

    /**
     * The first administrator: the user created in the same operation as the role {@code ADMIN} by
     * {@code SEC_BOOTSTRAP_ADMIN} (docs/design/10-security.md section 7); empty when the platform made none.
     */
    public Mono<String> bootstrapUser() {
        String sql = "SELECT u." + USER_ID + "::text AS user_id FROM " + ROLE + " r"
            + " JOIN op_process p ON p.process_seq_id = r.process_seq_id AND p.process_name = :process"
            + " JOIN " + USER + " u ON u.process_seq_id = r.process_seq_id AND u.version_no = 1"
            + " WHERE r." + ROLE_CODE + " = :role AND r.version_no = 1 LIMIT 1";
        return engine().select(sql, Map.of("process", BoundValue.of(BootstrapAdmin.PROCESS.name()),
            "role", BoundValue.of(BootstrapAdmin.ADMIN_ROLE))).next().map(row -> Rows.string(row.get("user_id")));
    }

    /** The permissions a role grants (latest versions not deleted). */
    public Mono<Set<String>> grantsOf(UUID roleId) {
        String sql = "SELECT rp.permission FROM (SELECT DISTINCT ON (" + RP_ID + ") " + RP_PERMISSION
            + " AS permission, is_deleted FROM " + RP + " WHERE " + RP_ROLE + " = :role ORDER BY " + RP_ID
            + ", version_no DESC) rp WHERE NOT rp.is_deleted";
        return engine().select(sql, Map.of("role", BoundValue.of(roleId)))
            .map(row -> Rows.string(row.get("permission"))).collect(TreeSet<String>::new, Set::add)
            .map(Set::copyOf);
    }

    /** The users assigned a role (latest versions not deleted). */
    public Mono<List<UUID>> usersOf(UUID roleId) {
        String sql = "SELECT DISTINCT ur.uid FROM (SELECT DISTINCT ON (" + UR_ID + ") " + UR_USER
            + " AS uid, is_deleted FROM " + UR + " WHERE " + UR_ROLE + " = :role ORDER BY " + UR_ID
            + ", version_no DESC) ur WHERE NOT ur.is_deleted ORDER BY ur.uid";
        return engine().select(sql, Map.of("role", BoundValue.of(roleId))).map(row -> Rows.uuid(row.get("uid")))
            .collectList();
    }

    /** What each of {@code users} could hold (see the class comment), by user. */
    public Mono<Map<UUID, Set<String>>> holdings(Collection<UUID> users) {
        if (users.isEmpty()) {
            return Mono.just(Map.of());
        }
        String sql = "SELECT ur.uid, rp.permission"
            + " FROM (SELECT DISTINCT ON (" + UR_ID + ") " + UR_USER + " AS uid, " + UR_ROLE + " AS rid, is_deleted"
            + " FROM " + UR + " WHERE " + UR_USER + " = ANY(:users) ORDER BY " + UR_ID + ", version_no DESC) ur"
            + " JOIN (SELECT DISTINCT ON (" + RP_ID + ") " + RP_ROLE + " AS rid, " + RP_PERMISSION
            + " AS permission, is_deleted FROM " + RP + " WHERE " + RP_ROLE + " IN (SELECT " + UR_ROLE + " FROM " + UR
            + " WHERE " + UR_USER + " = ANY(:users)) ORDER BY " + RP_ID + ", version_no DESC) rp ON rp.rid = ur.rid"
            + " WHERE NOT ur.is_deleted AND NOT rp.is_deleted";
        return engine().select(sql, Map.of("users", BoundValue.of(users.toArray(UUID[]::new)))).collectList()
            .map(rows -> {
                Map<UUID, Set<String>> holdings = new LinkedHashMap<>();
                for (Map<String, Object> row : rows) {
                    holdings.computeIfAbsent(Rows.uuid(row.get("uid")), id -> new TreeSet<>())
                        .add(Rows.string(row.get("permission")));
                }
                return holdings;
            });
    }

    /** The names people know a user and a role by, for the approvers' task (null when not found). */
    public Mono<List<String>> names(UUID userId, UUID roleId) {
        List<Mono<String>> lookups = new ArrayList<>();
        lookups.add(userId == null ? Mono.just("") : engine().select("SELECT " + USER_NAME + " AS name FROM " + USER
            + " WHERE " + USER_ID + " = :id ORDER BY version_no DESC LIMIT 1", Map.of("id", BoundValue.of(userId)))
            .next().map(row -> Rows.string(row.get("name"))).defaultIfEmpty(""));
        lookups.add(roleId == null ? Mono.just("") : engine().select("SELECT " + ROLE_CODE + " AS name FROM " + ROLE
            + " WHERE " + ROLE_ID + " = :id ORDER BY version_no DESC LIMIT 1", Map.of("id", BoundValue.of(roleId)))
            .next().map(row -> Rows.string(row.get("name"))).defaultIfEmpty(""));
        return Mono.zip(lookups, values -> List.of((String) values[0], (String) values[1]));
    }

    private StorageEngine engine() {
        return storages.getEngine(poolRef);
    }

    private static final String ARMED = "sec_protected_grants_armed";
    private static final String USER = table(SecurityEntities.SEC_USER);
    private static final String USER_ID = column(SecurityEntities.SEC_USER, "userId");
    private static final String USER_NAME = column(SecurityEntities.SEC_USER, "userName");
    private static final String USER_ENABLED = column(SecurityEntities.SEC_USER, "enabled");
    private static final String ROLE = table(SecurityEntities.SEC_ROLE);
    private static final String ROLE_ID = column(SecurityEntities.SEC_ROLE, "roleId");
    private static final String ROLE_CODE = column(SecurityEntities.SEC_ROLE, "roleCode");
    private static final String ROLE_ENABLED = column(SecurityEntities.SEC_ROLE, "enabled");
    private static final String RP = table(SecurityEntities.SEC_ROLE_PERMISSION);
    private static final String RP_ID = column(SecurityEntities.SEC_ROLE_PERMISSION, "rolePermissionId");
    private static final String RP_ROLE = column(SecurityEntities.SEC_ROLE_PERMISSION, "roleId");
    private static final String RP_PERMISSION = column(SecurityEntities.SEC_ROLE_PERMISSION, "permission");
    private static final String UR = table(SecurityEntities.SEC_USER_ROLE);
    private static final String UR_ID = column(SecurityEntities.SEC_USER_ROLE, "userRoleId");
    private static final String UR_USER = column(SecurityEntities.SEC_USER_ROLE, "userId");
    private static final String UR_ROLE = column(SecurityEntities.SEC_USER_ROLE, "roleId");

    private static String table(EntityDefinition def) {
        return SqlIdentifiers.require(def.physicalTable);
    }

    private static String column(EntityDefinition def, String field) {
        return SqlIdentifiers.require(def.fields.get(field).physicalColumn());
    }
}

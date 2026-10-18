package com.jabiz.runtime.security.protect;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.query.BoundValue;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.security.BootstrapAdmin;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.sod.SodService;
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
 *       deleted, enabled or not, scheduled or not (as segregation of duties does, 18 section 4.2), and every assignment
 *       and grant still waiting for approval (a pending {@code SecProtectedChange}, review H1): a grant made while
 *       another is pending is judged as if that one were in effect, so that neither can grow the other unseen.</li>
 *   <li><b>Administrators</b> are enabled users holding {@code *} through an enabled role, now or at any later time a
 *       version is scheduled for (review L1). The <b>first administrator</b> is the user {@code SEC_BOOTSTRAP_ADMIN}
 *       created (with the role {@code ADMIN}).</li>
 *   <li>The <b>setup window</b> ends ("armed") the first time two administrators besides the first one exist; the
 *       first administrator <b>retires</b> the first time another one exists. Both are recorded and never undone
 *       ({@code sec_protected_grants_armed}, {@code sec_bootstrap_retired}); with {@code .always} there is no
 *       window.</li>
 * </ul>
 * Reads go through the engine of the writing transaction, so they see its own writes. Callers that judge a grant take
 * {@link #lock()} first, as segregation of duties does: changes of access are serialized.
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
            gained = Set.copyOf(new TreeSet<>(gained));
            affected = List.copyOf(affected);
        }

        public boolean protectedChange() {
            return !gained.isEmpty();
        }

        public boolean needsApproval() {
            return protectedChange() && armed;
        }
    }

    /**
     * Who set a user's credentials (review M3): the password (the last version that changed it), the second factor
     * and the links to identity providers.
     *
     * @param own     whether the user set all of them themselves (or proved the address, as in self-service)
     * @param setBy   who last set a credential someone else set (null when own)
     * @param setAt   when
     * @param summary for the approvers: who last set the password, and when
     */
    public record Credentials(boolean own, String setBy, Instant setAt, String summary) {}

    /** Processes in which a user sets their own password (or proves the address the account belongs to). */
    static final Set<String> SELF_PASSWORD = Set.of("SEC_ACCOUNT_PASSWORD_CHANGE", "SPONSOR_SIGN_UP",
        "SPONSOR_SIGN_UP_JOIN", "SPONSOR_EMAIL_VERIFY", "SPONSOR_PASSWORD_RESET_CONFIRM");
    /** Processes in which a user links their own account at a provider. */
    static final Set<String> SELF_IDENTITY = Set.of("SEC_IDENTITY_LINK_SELF", "SPONSOR_OIDC_SIGN_UP");
    /** Processes in which a user enrols their own second factor. */
    static final Set<String> SELF_MFA = Set.of("SEC_MFA_ENROLL_BEGIN", "SEC_MFA_ENROLL_CONFIRM");

    private final ProtectedGrantSettings settings;
    private final StorageAdapterRegistry storages;
    private final SodService sod;
    private final String poolRef;

    public ProtectedGrants(ProtectedGrantSettings settings, StorageAdapterRegistry storages, SodService sod,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.settings = settings;
        this.storages = storages;
        this.sod = sod;
        this.poolRef = poolRef;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public ProtectedPermissions permissions() {
        return settings.permissions();
    }

    /** Serializes changes of access for the rest of the transaction (the lock segregation of duties takes too). */
    public Mono<Void> lock() {
        return sod.lock();
    }

    /** What {@code draft}, made by {@code actorId} in operation {@code processSeqId}, would do. */
    public Mono<Assessment> assess(Draft draft, String actorId, long processSeqId, Instant now) {
        return assess(draft, actorId, processSeqId, now, null);
    }

    /** As {@link #assess(Draft, String, long, Instant)}, not counting the pending change {@code excludedChange}. */
    public Mono<Assessment> assess(Draft draft, String actorId, long processSeqId, Instant now,
        String excludedChange) {
        if (!settings.enabled()) {
            return Mono.just(new Assessment(draft.kind(), Set.of(), List.of(), false, false));
        }
        return pending(excludedChange).flatMap(pending -> gained(draft, actorId, pending)).flatMap(found -> {
            if (found.gained().isEmpty()) {
                return Mono.just(new Assessment(draft.kind(), found.gained(), found.affected(), false, false));
            }
            return armed(processSeqId, now).zipWith(retiredBootstrap(actorId, processSeqId, now))
                .map(state -> new Assessment(draft.kind(), found.gained(), found.affected(), state.getT1(),
                    state.getT2()));
        });
    }

    private record Gain(Set<String> gained, List<UUID> affected) {}

    /** The pending assignments and grants, as {@code (user, role)} and {@code (role, permission)}. */
    private record Pending(List<UUID[]> assignments, List<Object[]> grants) {

        static final Pending NONE = new Pending(List.of(), List.of());

        Set<String> grantsOf(UUID role) {
            Set<String> found = new TreeSet<>();
            grants.stream().filter(g -> role.equals(g[0])).forEach(g -> found.add((String) g[1]));
            return found;
        }

        Set<UUID> usersOf(UUID role) {
            Set<UUID> found = new LinkedHashSet<>();
            assignments.stream().filter(a -> role.equals(a[1])).forEach(a -> found.add(a[0]));
            return found;
        }

        Set<UUID> rolesOf(UUID user) {
            Set<UUID> found = new LinkedHashSet<>();
            assignments.stream().filter(a -> user.equals(a[0])).forEach(a -> found.add(a[1]));
            return found;
        }
    }

    private Mono<Pending> pending(String excludedChange) {
        String sql = "SELECT c.kind, c.user_id, c.role_id, c.permission FROM (SELECT DISTINCT ON (change_id)"
            + " change_id::text AS cid, kind, user_id, role_id, permission, status, is_deleted FROM " + CHANGES
            + " WHERE kind IN ('USER_ROLE', 'ROLE_PERMISSION') ORDER BY change_id, version_no DESC) c"
            + " WHERE c.status = 'PENDING' AND NOT c.is_deleted AND c.cid <> :excluded";
        return engine().select(sql, Map.of("excluded", BoundValue.of(excludedChange == null ? "" : excludedChange)))
            .collectList().map(rows -> {
                List<UUID[]> assignments = new ArrayList<>();
                List<Object[]> grants = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    UUID role = Rows.uuid(row.get("role_id"));
                    if (ProtectedChangeEntities.USER_ROLE.equals(row.get("kind"))) {
                        assignments.add(new UUID[] {Rows.uuid(row.get("user_id")), role});
                    } else {
                        grants.add(new Object[] {role, Rows.string(row.get("permission"))});
                    }
                }
                return new Pending(assignments, grants);
            });
    }

    private Mono<Gain> gained(Draft draft, String actorId, Pending pending) {
        ProtectedPermissions set = settings.permissions();
        return switch (draft.kind()) {
            // What a grant gives is judged against what is held in fact: a pending change never makes a grant look
            // like nothing new (it only makes more look given, review H1).
            case ProtectedChangeEntities.USER_ROLE -> grantsOf(draft.roleId(), pending)
                .zipWith(holdings(List.of(draft.userId()), Pending.NONE))
                .map(found -> new Gain(set.gained(found.getT1(), held(found.getT2(), draft.userId())),
                    List.of(draft.userId())));
            case ProtectedChangeEntities.ROLE_PERMISSION -> !set.isProtected(draft.permission())
                ? Mono.just(new Gain(Set.of(), List.of()))
                : usersOf(draft.roleId(), pending).flatMap(users -> users.isEmpty()
                    ? Mono.just(new Gain(Set.of(), List.of()))
                    : holdings(users, Pending.NONE).map(holdings -> {
                        List<UUID> affected = users.stream().filter(user -> !set.gained(
                            List.of(draft.permission()), held(holdings, user)).isEmpty()).toList();
                        return new Gain(affected.isEmpty() ? Set.of() : Set.of(draft.permission()), affected);
                    }));
            case ProtectedChangeEntities.IDENTITY_LINK -> draft.userId().toString().equals(actorId)
                // Linking one's own account: the user proved who they are (D39), nobody else gains a way in.
                ? Mono.just(new Gain(Set.of(), List.of()))
                : heldBy(draft.userId(), set, pending);
            case ProtectedChangeEntities.PASSWORD, ProtectedChangeEntities.MFA_RESET ->
                heldBy(draft.userId(), set, pending);
            default -> Mono.error(new IllegalArgumentException("Unknown protected change kind " + draft.kind()));
        };
    }

    private Mono<Gain> heldBy(UUID user, ProtectedPermissions set, Pending pending) {
        return holdings(List.of(user), pending).map(holdings -> new Gain(set.among(held(holdings, user)),
            List.of(user)));
    }

    private static Set<String> held(Map<UUID, Set<String>> holdings, UUID user) {
        return holdings.getOrDefault(user, Set.of());
    }

    /**
     * Records what has happened for good, in operation {@code processSeqId}: the end of the setup window, the first
     * administrator's retirement. Asked on every write of the security entities, so that taking administrators away
     * cannot undo either.
     */
    public Mono<Void> record(long processSeqId, Instant now) {
        return armed(processSeqId, now).then(bootstrapUser().flatMap(bootstrap ->
            retiredBootstrap(bootstrap, processSeqId, now)).then());
    }

    /**
     * Whether the setup window has ended: always with {@code .always}; once recorded, for good; otherwise when two
     * administrators besides the first one exist, which this records in operation {@code processSeqId}.
     */
    public Mono<Boolean> armed(long processSeqId, Instant now) {
        if (settings.always()) {
            return Mono.just(true);
        }
        return engine().select("SELECT 1 AS armed FROM " + ARMED + " WHERE armed_id = 1", Map.of()).hasElements()
            .flatMap(armed -> armed ? Mono.just(true) : admins(now).zipWith(bootstrapUser().defaultIfEmpty(""))
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

    /** Whether the setup window has been recorded as ended (or there is none). */
    public Mono<Boolean> armedOnRecord() {
        return engine().select("SELECT 1 AS armed FROM " + ARMED + " WHERE armed_id = 1", Map.of()).hasElements();
    }

    /**
     * Whether {@code actorId} is the first administrator and retired: recorded already, or another administrator
     * exists now (which this records in operation {@code processSeqId}).
     */
    public Mono<Boolean> retiredBootstrap(String actorId, long processSeqId, Instant now) {
        if (actorId == null || RequestContext.SYSTEM_ACTOR.equals(actorId)) {
            return Mono.just(false);
        }
        return bootstrapUser().filter(actorId::equals).flatMap(bootstrap -> engine().select(
                "SELECT 1 AS retired FROM " + RETIRED + " WHERE retired_id = 1", Map.of()).hasElements()
            .flatMap(retired -> retired ? Mono.just(true) : admins(now).flatMap(admins -> {
                if (admins.stream().noneMatch(admin -> !admin.equals(bootstrap))) {
                    return Mono.just(false);
                }
                return engine().select("INSERT INTO " + RETIRED + " (retired_id, user_id, retired_time,"
                        + " process_seq_id) VALUES (1, :user, :now, :seq) ON CONFLICT (retired_id) DO NOTHING"
                        + " RETURNING retired_id", Map.of("user", BoundValue.of(UUID.fromString(bootstrap)),
                        "now", BoundValue.of(now), "seq", BoundValue.of(processSeqId)))
                    .then(Mono.just(true));
            })))
            .defaultIfEmpty(false);
    }

    /**
     * The administrators: users holding {@code *} through a role, where the user (enabled), the assignment, the role
     * (enabled) and the grant each live now or at a later time a version is scheduled for (ids as text). Counting a
     * later time keeps a deletion scheduled far ahead from dropping an administrator early (review L1).
     */
    public Mono<Set<String>> admins(Instant now) {
        String starRoles = "(SELECT " + RP_ROLE + " FROM " + RP + " WHERE " + RP_PERMISSION + " = :all)";
        String sql = "SELECT DISTINCT ur.k::text AS user_id"
            + " FROM " + alive(RP, RP_ID, RP_ROLE + " AS role_id", RP_PERMISSION + " = :all", null) + " rp"
            + " JOIN " + alive(ROLE, ROLE_ID, ROLE_ID + " AS role_id", ROLE_ID + " IN " + starRoles, ROLE_ENABLED)
            + " r ON r.role_id = rp.role_id"
            + " JOIN " + alive(UR, UR_ID, UR_ROLE + " AS role_id, " + UR_USER + " AS uid", UR_ROLE + " IN "
                + starRoles, null) + " urr ON urr.role_id = rp.role_id"
            + " JOIN " + alive(USER, USER_ID, USER_ID + " AS uid", USER_ID + " IN (SELECT " + UR_USER + " FROM " + UR
                + " WHERE " + UR_ROLE + " IN " + starRoles + ")", USER_ENABLED) + " ur ON ur.uid = urr.uid";
        return engine().select(sql, Map.of("all", BoundValue.of(RequestContext.ALL_PERMISSIONS),
                "now", BoundValue.of(now)))
            .map(row -> Rows.string(row.get("user_id"))).collect(TreeSet<String>::new, Set::add)
            .map(Set::copyOf);
    }

    /**
     * {@code (SELECT DISTINCT k, columns …)}: the instances of a temporal table (filtered by {@code where} on immutable
     * columns) that live - not deleted, and {@code flag} true when given - in the version in effect now or in a version
     * scheduled after now. The version taking effect at a time is the highest numbered with that effective time.
     */
    private static String alive(String table, String key, String columns, String where, String flag) {
        return "(SELECT DISTINCT v.k, " + strip(columns) + " FROM (SELECT DISTINCT ON (" + key + ", effect_start_time) "
            + key + " AS k, effect_start_time AS eff, is_deleted" + (flag == null ? "" : ", " + flag + " AS flag")
            + ", " + columns + " FROM " + table + " WHERE " + where + " ORDER BY " + key + ", effect_start_time,"
            + " version_no DESC) v WHERE NOT v.is_deleted" + (flag == null ? "" : " AND v.flag")
            + " AND (v.eff > :now OR v.eff = (SELECT max(t.effect_start_time) FROM " + table + " t WHERE t." + key
            + " = v.k AND t.effect_start_time <= :now)))";
    }

    /** {@code a AS x, b AS y} → {@code v.x, v.y}. */
    private static String strip(String columns) {
        List<String> names = new ArrayList<>();
        for (String column : columns.split(",")) {
            String[] parts = column.trim().split("\\s+AS\\s+");
            names.add("v." + parts[parts.length - 1].trim());
        }
        return String.join(", ", names);
    }

    /**
     * The first administrator: the user created in the same operation as the role {@code ADMIN} by
     * {@code SEC_BOOTSTRAP_ADMIN} (docs/design/10-security.md section 7); empty when the platform made none.
     */
    public Mono<String> bootstrapUser() {
        String sql = "SELECT u." + USER_ID + "::text AS user_id FROM " + ROLE + " r"
            + " JOIN op_process p ON p.process_seq_id = r.process_seq_id AND p.process_name = :process"
            + " JOIN " + USER + " u ON u.process_seq_id = r.process_seq_id AND u.version_no = 1"
            + " WHERE r." + ROLE_CODE + " = :role AND r.version_no = 1 ORDER BY u.row_id LIMIT 1";
        return engine().select(sql, Map.of("process", BoundValue.of(BootstrapAdmin.PROCESS.name()),
            "role", BoundValue.of(BootstrapAdmin.ADMIN_ROLE))).next().map(row -> Rows.string(row.get("user_id")));
    }

    /** The permissions a role grants (latest versions not deleted) and those waiting for approval. */
    private Mono<Set<String>> grantsOf(UUID roleId, Pending pending) {
        return grantsOf(List.of(roleId)).map(grants -> {
            Set<String> all = new TreeSet<>(grants.getOrDefault(roleId, Set.of()));
            all.addAll(pending.grantsOf(roleId));
            return all;
        });
    }

    /** The permissions of each of {@code roles} (latest versions not deleted). */
    private Mono<Map<UUID, Set<String>>> grantsOf(Collection<UUID> roles) {
        if (roles.isEmpty()) {
            return Mono.just(Map.of());
        }
        String sql = "SELECT rp.rid, rp.permission FROM (SELECT DISTINCT ON (" + RP_ID + ") " + RP_ROLE + " AS rid, "
            + RP_PERMISSION + " AS permission, is_deleted FROM " + RP + " WHERE " + RP_ROLE + " = ANY(:roles)"
            + " ORDER BY " + RP_ID + ", version_no DESC) rp WHERE NOT rp.is_deleted";
        return engine().select(sql, Map.of("roles", BoundValue.of(roles.toArray(UUID[]::new)))).collectList()
            .map(rows -> {
                Map<UUID, Set<String>> grants = new LinkedHashMap<>();
                rows.forEach(row -> grants.computeIfAbsent(Rows.uuid(row.get("rid")), id -> new TreeSet<>())
                    .add(Rows.string(row.get("permission"))));
                return grants;
            });
    }

    /** The users assigned a role (latest versions not deleted) and those waiting for approval to be. */
    private Mono<List<UUID>> usersOf(UUID roleId, Pending pending) {
        String sql = "SELECT DISTINCT ur.uid FROM (SELECT DISTINCT ON (" + UR_ID + ") " + UR_USER
            + " AS uid, is_deleted FROM " + UR + " WHERE " + UR_ROLE + " = :role ORDER BY " + UR_ID
            + ", version_no DESC) ur WHERE NOT ur.is_deleted ORDER BY ur.uid";
        return engine().select(sql, Map.of("role", BoundValue.of(roleId))).map(row -> Rows.uuid(row.get("uid")))
            .collectList().map(users -> {
                Set<UUID> all = new LinkedHashSet<>(users);
                all.addAll(pending.usersOf(roleId));
                return List.copyOf(all);
            });
    }

    /** What each of {@code users} could hold (see the class comment), pending changes included, by user. */
    private Mono<Map<UUID, Set<String>>> holdings(Collection<UUID> users, Pending pending) {
        if (users.isEmpty()) {
            return Mono.just(Map.of());
        }
        String sql = "SELECT ur.uid, ur.rid FROM (SELECT DISTINCT ON (" + UR_ID + ") " + UR_USER + " AS uid, "
            + UR_ROLE + " AS rid, is_deleted FROM " + UR + " WHERE " + UR_USER + " = ANY(:users) ORDER BY " + UR_ID
            + ", version_no DESC) ur WHERE NOT ur.is_deleted";
        return engine().select(sql, Map.of("users", BoundValue.of(users.toArray(UUID[]::new)))).collectList()
            .flatMap(rows -> {
                Map<UUID, Set<UUID>> roles = new LinkedHashMap<>();
                for (UUID user : users) {
                    roles.put(user, new LinkedHashSet<>(pending.rolesOf(user)));
                }
                rows.forEach(row -> roles.computeIfAbsent(Rows.uuid(row.get("uid")), id -> new LinkedHashSet<>())
                    .add(Rows.uuid(row.get("rid"))));
                Set<UUID> allRoles = new LinkedHashSet<>();
                roles.values().forEach(allRoles::addAll);
                return grantsOf(allRoles).map(grants -> {
                    Map<UUID, Set<String>> holdings = new LinkedHashMap<>();
                    roles.forEach((user, held) -> {
                        Set<String> permissions = new TreeSet<>();
                        for (UUID role : held) {
                            permissions.addAll(grants.getOrDefault(role, Set.of()));
                            permissions.addAll(pending.grantsOf(role));
                        }
                        holdings.put(user, permissions);
                    });
                    return holdings;
                });
            });
    }

    /** What a user holds in effect now: enabled user, enabled roles, versions in effect at {@code now}. */
    public Mono<Set<String>> effectiveHoldings(UUID user, Instant now) {
        String sql = "SELECT DISTINCT rp.permission FROM"
            + " " + current(UR, UR_ID, UR_ROLE + " AS rid", UR_USER + " = :user") + " ur"
            + " JOIN " + current(ROLE, ROLE_ID, ROLE_ID + " AS rid, " + ROLE_ENABLED + " AS enabled", "TRUE")
            + " r ON r.rid = ur.rid"
            + " JOIN " + current(RP, RP_ID, RP_ROLE + " AS rid, " + RP_PERMISSION + " AS permission", "TRUE")
            + " rp ON rp.rid = ur.rid"
            + " JOIN " + current(USER, USER_ID, USER_ENABLED + " AS enabled", USER_ID + " = :user") + " u ON true"
            + " WHERE NOT ur.is_deleted AND NOT r.is_deleted AND r.enabled AND NOT rp.is_deleted AND NOT u.is_deleted"
            + " AND u.enabled";
        return engine().select(sql, Map.of("user", BoundValue.of(user), "now", BoundValue.of(now)))
            .map(row -> Rows.string(row.get("permission"))).collect(TreeSet<String>::new, Set::add)
            .map(Set::copyOf);
    }

    /** The version of each instance (filtered by {@code where}) in effect at {@code :now}. */
    private static String current(String table, String key, String columns, String where) {
        return "(SELECT DISTINCT ON (" + key + ") " + columns + ", is_deleted FROM " + table + " WHERE " + where
            + " AND effect_start_time <= :now ORDER BY " + key + ", effect_start_time DESC, version_no DESC)";
    }

    /** Who set {@code user}'s credentials (review M3). */
    public Mono<Credentials> credentials(UUID user) {
        String passwords = "SELECT v.version_no, v.password_hash IS NOT NULL AS has_password, v.password_hash,"
            + " p.actor_id, p.process_name, p.op_time FROM " + USER + " v JOIN op_process p"
            + " ON p.process_seq_id = v.process_seq_id WHERE v." + USER_ID + " = :user ORDER BY v.version_no";
        String links = "SELECT f.actor_id, f.process_name, f.op_time FROM (SELECT DISTINCT ON (user_identity_id)"
            + " user_identity_id, is_deleted FROM sec_user_identity_version WHERE user_id = :user"
            + " ORDER BY user_identity_id, version_no DESC) i"
            + " JOIN (SELECT DISTINCT ON (v.user_identity_id) v.user_identity_id, p.actor_id, p.process_name,"
            + " p.op_time FROM sec_user_identity_version v JOIN op_process p ON p.process_seq_id = v.process_seq_id"
            + " WHERE v.user_id = :user ORDER BY v.user_identity_id, v.version_no) f"
            + " ON f.user_identity_id = i.user_identity_id WHERE NOT i.is_deleted";
        String factors = "SELECT p.actor_id, p.process_name, p.op_time FROM (SELECT DISTINCT ON (user_mfa_id)"
            + " user_mfa_id, is_deleted, process_seq_id FROM sec_user_mfa_version WHERE user_id = :user"
            + " ORDER BY user_mfa_id, version_no DESC) m JOIN op_process p ON p.process_seq_id = m.process_seq_id"
            + " WHERE NOT m.is_deleted";
        Map<String, BoundValue> params = Map.of("user", BoundValue.of(user));
        String self = user.toString();
        return Mono.zip(engine().select(passwords, params).collectList(), engine().select(links, params).collectList(),
            engine().select(factors, params).collectList()).map(found -> {
                // The version that last changed the password, when the user has one now.
                Map<String, Object> setter = null;
                Object previous = null;
                boolean first = true;
                for (Map<String, Object> version : found.getT1()) {
                    Object hash = version.get("password_hash");
                    if (hash != null && (first || !hash.equals(previous))) {
                        setter = version;
                    } else if (hash == null) {
                        setter = null;
                    }
                    previous = hash;
                    first = false;
                }
                String summary = setter == null ? "no password"
                    : "password set by " + setter.get("actor_id") + " at " + Rows.instant(setter.get("op_time"));
                List<Map<String, Object>> foreign = new ArrayList<>();
                if (setter != null && !own(setter, self, SELF_PASSWORD)) {
                    foreign.add(setter);
                }
                found.getT2().stream().filter(link -> !own(link, self, SELF_IDENTITY)).forEach(foreign::add);
                found.getT3().stream().filter(factor -> !own(factor, self, SELF_MFA)).forEach(foreign::add);
                if (foreign.isEmpty()) {
                    return new Credentials(true, null, null, summary);
                }
                Map<String, Object> last = foreign.getLast();
                return new Credentials(false, Rows.string(last.get("actor_id")), Rows.instant(last.get("op_time")),
                    summary);
            });
    }

    private static boolean own(Map<String, Object> operation, String user, Set<String> selfProcesses) {
        return user.equals(Rows.string(operation.get("actor_id")))
            || selfProcesses.contains(Rows.string(operation.get("process_name")));
    }

    /** The names people know a user and a role by, for the approvers' task (empty when not found). */
    public Mono<List<String>> names(UUID userId, UUID roleId) {
        List<Mono<String>> lookups = new ArrayList<>();
        lookups.add(userId == null ? Mono.just("") : userName(userId));
        lookups.add(roleId == null ? Mono.just("") : engine().select("SELECT " + ROLE_CODE + " AS name FROM " + ROLE
            + " WHERE " + ROLE_ID + " = :id ORDER BY version_no DESC LIMIT 1", Map.of("id", BoundValue.of(roleId)))
            .next().map(row -> Rows.string(row.get("name"))).defaultIfEmpty(""));
        return Mono.zip(lookups, values -> List.of((String) values[0], (String) values[1]));
    }

    /** A user's name ("" when not found). */
    public Mono<String> userName(UUID userId) {
        return engine().select("SELECT " + USER_NAME + " AS name FROM " + USER + " WHERE " + USER_ID
                + " = :id ORDER BY version_no DESC LIMIT 1", Map.of("id", BoundValue.of(userId)))
            .next().map(row -> Rows.string(row.get("name"))).defaultIfEmpty("");
    }

    private StorageEngine engine() {
        return storages.getEngine(poolRef);
    }

    private static final String ARMED = "sec_protected_grants_armed";
    private static final String RETIRED = "sec_bootstrap_retired";
    private static final String CHANGES = SqlIdentifiers.require(ProtectedChangeEntities.SEC_PROTECTED_CHANGE
        .physicalTable);
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

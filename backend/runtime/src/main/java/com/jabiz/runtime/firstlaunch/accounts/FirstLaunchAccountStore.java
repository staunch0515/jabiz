package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.query.BoundValue;
import com.jabiz.runtime.security.protect.ProtectedChangeEntities;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The first launch's accounts ({@code sys_first_launch_account}, V68) and what the waiting phase reads about their
 * users, through the engine of the running transaction (docs/design/23-first-launch.md section 8). Platform code.
 */
@Component
public class FirstLaunchAccountStore {

    static final String TABLE = "sys_first_launch_account";
    /** The template of the invitation's mail (decision D63). */
    static final String INVITATION_TEMPLATE = "jabiz.auth.invitation";

    /**
     * An account of the run as recorded when it was created: its user and its place in the configuration's list, never
     * its key (which is the user's name, personal data erased with the user).
     */
    public record Recorded(UUID runId, UUID userId, int position, List<String> roles, boolean report) {
        public Recorded {
            roles = List.copyOf(roles);
        }

        /** How the log, the report and the run's events name the account: its place in the configuration, from 1. */
        public String label() {
            return "#" + (position + 1);
        }
    }

    /**
     * What the waiting phase needs to know about a user now.
     *
     * @param email          the current address
     * @param displayName    the name, for the log and the report
     * @param locale         the language of the user's mail (null: the platform's)
     * @param acceptedAt     when the address was proven (accepting the invitation proves it); null: not yet
     * @param mfaConfirmedAt when the current second factor was confirmed; null: none
     * @param roles          the codes of the roles the user holds now
     * @param pendingRoles   the role ids a protected change waiting for approval would give the user
     * @param decidedRoles   the role ids the user was ever assigned, or a protected change for which was decided -
     *                       whatever the administrators did with them since is theirs
     * @param firstInvited   when the first invitation to the current address was recorded; null: never
     * @param invitations    how many invitations to the current address were recorded
     * @param enabled        whether the user is enabled
     */
    public record Facts(UUID userId, String email, String displayName, String locale, Instant acceptedAt,
        Instant mfaConfirmedAt, Set<String> roles, Set<UUID> pendingRoles, Set<UUID> decidedRoles,
        Instant firstInvited, int invitations, boolean enabled) {
        public Facts {
            roles = Set.copyOf(roles);
            pendingRoles = Set.copyOf(pendingRoles);
            decidedRoles = Set.copyOf(decidedRoles);
        }
    }

    /** A role as it is now. */
    public record Role(UUID roleId, String code, boolean enabled, long version) {}

    private final StorageAdapterRegistry storages;
    private final String poolRef;

    public FirstLaunchAccountStore(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.poolRef = poolRef;
    }

    private StorageEngine engine() {
        return storages.getEngine(poolRef);
    }

    /** The run in its content phase (a run without an event), if any. */
    public Mono<Optional<UUID>> runningRun() {
        return engine().select("SELECT r.run_id FROM sys_first_launch_run r WHERE NOT EXISTS (SELECT 1 FROM"
                + " sys_first_launch_event e WHERE e.run_id = r.run_id)", Map.of())
            .next().map(row -> Optional.of(Rows.uuid(row.get("run_id")))).defaultIfEmpty(Optional.empty());
    }

    /**
     * Whether {@code userId} is an account of a run that is waiting: its content phase done ({@code CONTENT_DONE}) and
     * not ended - judged by the run's record and {@code sys_first_launch_account}, never by request ids.
     */
    public Mono<Boolean> waitingAccount(UUID userId) {
        return engine().select("SELECT 1 AS found FROM " + TABLE + " a WHERE a.user_id = :user"
                + " AND EXISTS (SELECT 1 FROM sys_first_launch_event e WHERE e.run_id = a.run_id"
                + " AND e.kind = 'CONTENT_DONE')"
                + " AND NOT EXISTS (SELECT 1 FROM sys_first_launch_event e WHERE e.run_id = a.run_id"
                + " AND e.kind IN ('DONE', 'EXPIRED', 'FAILED'))", Map.of("user", BoundValue.of(userId)))
            .hasElements();
    }

    Mono<Void> record(Recorded account, long processSeqId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", account.runId());
        row.put("user_id", account.userId());
        row.put("position", account.position());
        row.put("roles", String.join(",", account.roles()));
        row.put("report", account.report());
        row.put("process_seq_id", processSeqId);
        return engine().insert(TABLE, row);
    }

    /** The keys of the application's steps that ran for the user once it was ready (section 9). */
    public Mono<Set<String>> readyDone(UUID runId, UUID userId) {
        return engine().select("SELECT step_key FROM sys_first_launch_ready WHERE run_id = :run AND user_id = :user",
                Map.of("run", BoundValue.of(runId), "user", BoundValue.of(userId)))
            .map(row -> Rows.string(row.get("step_key"))).collectList().map(Set::copyOf);
    }

    /** The keys of the application's steps that ran, by user, for every account of the run. */
    public Mono<Map<UUID, Set<String>>> readyDone(UUID runId) {
        return engine().select("SELECT user_id, step_key FROM sys_first_launch_ready WHERE run_id = :run",
                Map.of("run", BoundValue.of(runId)))
            .collectMultimap(row -> Rows.uuid(row.get("user_id")), row -> Rows.string(row.get("step_key")))
            .map(found -> {
                Map<UUID, Set<String>> done = new HashMap<>();
                found.forEach((user, keys) -> done.put(user, Set.copyOf(keys)));
                return Map.copyOf(done);
            });
    }

    /** Records that a step ran, in the step's transaction. */
    Mono<Void> recordReady(UUID runId, UUID userId, String stepKey, long processSeqId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", runId);
        row.put("user_id", userId);
        row.put("step_key", stepKey);
        row.put("process_seq_id", processSeqId);
        return engine().insert("sys_first_launch_ready", row);
    }

    /**
     * The ready items ({@code r} and the user id's hex digits) of the job whose latest outcome failed: their steps are
     * listed in the report until they succeed.
     */
    public Mono<Set<String>> failedItems(String jobName) {
        return engine().select("SELECT f.item_key FROM (SELECT DISTINCT ON (item_key) item_key, kind FROM"
                + " sys_job_item_failure WHERE job_name = :job AND item_key LIKE 'r%' ORDER BY item_key, entry_no DESC) f"
                + " WHERE f.kind = 'FAILED'", Map.of("job", BoundValue.of(jobName)))
            .map(row -> Rows.string(row.get("item_key"))).collectList().map(Set::copyOf);
    }

    /** The run's accounts, in the configuration's order. */
    public Mono<List<Recorded>> accounts(UUID runId) {
        return engine().select("SELECT run_id, user_id, position, roles, report FROM " + TABLE
                + " WHERE run_id = :run ORDER BY position", Map.of("run", BoundValue.of(runId)))
            .map(row -> new Recorded(Rows.uuid(row.get("run_id")), Rows.uuid(row.get("user_id")), Rows.intValue(row.get("position")),
                Arrays.stream(Rows.string(row.get("roles")).split(",")).filter(code -> !code.isBlank()).toList(),
                Boolean.TRUE.equals(row.get("report"))))
            .collectList();
    }

    /** The roles with these codes as they are now (absent: no such role). */
    public Mono<Map<String, Role>> roles(Collection<String> codes, Instant now) {
        return engine().select("SELECT r.role_id, r.role_code, r.enabled, r.version_no FROM (SELECT DISTINCT ON"
                + " (role_id) role_id, role_code, enabled, version_no, is_deleted FROM sec_role_version WHERE"
                + " role_code = ANY(:codes) AND effect_start_time <= :now ORDER BY role_id, effect_start_time DESC,"
                + " version_no DESC) r WHERE NOT r.is_deleted",
                Map.of("codes", BoundValue.of(codes.toArray(String[]::new)), "now", BoundValue.of(now)))
            .collectMap(row -> Rows.string(row.get("role_code")), row -> new Role(Rows.uuid(row.get("role_id")),
                Rows.string(row.get("role_code")), Boolean.TRUE.equals(row.get("enabled")),
                Rows.longValue(row.get("version_no"))))
            .map(Map::copyOf);
    }

    /**
     * The codes among these roles that hold one of {@code permissions} (an administrator's, decision D51) now or in a
     * version scheduled later - as {@code ProtectedGrants.admins} counts administrators (review L1 of D48).
     */
    public Mono<Set<String>> rolesHolding(Collection<UUID> roleIds, Set<String> permissions, Instant now) {
        if (roleIds.isEmpty()) {
            return Mono.just(Set.of());
        }
        return engine().select("SELECT DISTINCT ro.role_code FROM (SELECT DISTINCT ON (role_permission_id)"
                + " role_permission_id, role_id, permission, is_deleted FROM sec_role_permission_version WHERE role_id"
                + " = ANY(:roles) AND effect_start_time <= :now ORDER BY role_permission_id, effect_start_time DESC,"
                + " version_no DESC) rp JOIN (SELECT DISTINCT ON (role_id) role_id, role_code FROM sec_role_version"
                + " WHERE role_id = ANY(:roles) ORDER BY role_id, version_no DESC) ro ON ro.role_id = rp.role_id"
                + " WHERE rp.permission = ANY(:permissions) AND (NOT rp.is_deleted OR EXISTS (SELECT 1 FROM"
                + " sec_role_permission_version later WHERE later.role_permission_id = rp.role_permission_id AND"
                + " later.effect_start_time > :now AND NOT later.is_deleted))"
                + " UNION SELECT r.role_code FROM sec_role_permission_version v JOIN sec_role_version r ON r.role_id ="
                + " v.role_id WHERE v.role_id = ANY(:roles) AND v.effect_start_time > :now AND NOT v.is_deleted AND"
                + " v.permission = ANY(:permissions)",
                Map.of("roles", BoundValue.of(roleIds.toArray(UUID[]::new)),
                    "permissions", BoundValue.of(permissions.toArray(String[]::new)), "now", BoundValue.of(now)))
            .map(row -> Rows.string(row.get("role_code"))).collectList().map(Set::copyOf);
    }

    /** What the waiting phase needs to know about these users now. */
    public Mono<Map<UUID, Facts>> facts(Collection<UUID> userIds, Instant now) {
        if (userIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        Map<String, BoundValue> params = Map.of("users", BoundValue.of(userIds.toArray(UUID[]::new)),
            "now", BoundValue.of(now));
        Mono<List<Map<String, Object>>> users = engine().select("SELECT user_id, email, display_name, locale,"
            + " email_verified_at, verified_email, enabled FROM (SELECT DISTINCT ON (user_id) * FROM sec_user_version WHERE"
            + " user_id = ANY(:users) AND effect_start_time <= :now ORDER BY user_id, effect_start_time DESC,"
            + " version_no DESC) u WHERE NOT u.is_deleted", params).collectList();
        Mono<List<Map<String, Object>>> factors = engine().select("SELECT user_id, max(confirmed_time) AS confirmed"
            + " FROM (SELECT DISTINCT ON (user_mfa_id) user_id, confirmed, confirmed_time, is_deleted FROM"
            + " sec_user_mfa_version WHERE user_id = ANY(:users) AND effect_start_time <= :now ORDER BY user_mfa_id,"
            + " effect_start_time DESC, version_no DESC) m WHERE NOT m.is_deleted AND m.confirmed GROUP BY user_id",
            params).collectList();
        Mono<List<Map<String, Object>>> roles = engine().select("SELECT a.user_id, r.role_code FROM (SELECT DISTINCT"
            + " ON (user_role_id) user_id, role_id, is_deleted FROM sec_user_role_version WHERE user_id = ANY(:users)"
            + " AND effect_start_time <= :now ORDER BY user_role_id, effect_start_time DESC, version_no DESC) a"
            + " JOIN (SELECT DISTINCT ON (role_id) role_id, role_code FROM sec_role_version ORDER BY role_id,"
            + " version_no DESC) r ON r.role_id = a.role_id WHERE NOT a.is_deleted", params).collectList();
        Mono<List<Map<String, Object>>> changes = engine().select("SELECT c.user_id, c.role_id, c.status FROM (SELECT"
            + " DISTINCT ON (change_id) user_id, role_id, kind, status, is_deleted FROM sec_protected_change_version"
            + " WHERE user_id = ANY(:texts) ORDER BY change_id, version_no DESC) c WHERE NOT c.is_deleted"
            + " AND c.kind = :kind", Map.of("texts", BoundValue.of(userIds.stream().map(UUID::toString)
                .toArray(String[]::new)), "kind", BoundValue.of(ProtectedChangeEntities.USER_ROLE))).collectList();
        // To the current address only: a corrected address is invited anew, and reminded on its own time.
        Mono<List<Map<String, Object>>> invitations = engine().select("SELECT m.user_id, min(m.created_time) AS first,"
            + " count(*) AS invitations FROM sys_mail_message m JOIN (SELECT DISTINCT ON (user_id) user_id, email FROM"
            + " sec_user_version WHERE user_id = ANY(:users) ORDER BY user_id, version_no DESC) u ON u.user_id ="
            + " m.user_id AND lower(u.email) = lower(m.address) WHERE m.template = :template AND m.user_id ="
            + " ANY(:users) GROUP BY m.user_id", Map.of("users", params.get("users"), "template",
                BoundValue.of(INVITATION_TEMPLATE))).collectList();
        Mono<List<Map<String, Object>>> ever = engine().select("SELECT DISTINCT user_id, role_id FROM"
            + " sec_user_role_version WHERE user_id = ANY(:users)", Map.of("users", params.get("users"))).collectList();
        return Mono.zip(users, factors, roles, changes, invitations, ever).map(found -> {
            Map<UUID, Instant> mfa = new HashMap<>();
            found.getT2().forEach(row -> mfa.put(Rows.uuid(row.get("user_id")), Rows.instant(row.get("confirmed"))));
            Map<UUID, List<String>> held = new HashMap<>();
            found.getT3().forEach(row -> held.computeIfAbsent(Rows.uuid(row.get("user_id")), id -> new ArrayList<>())
                .add(Rows.string(row.get("role_code"))));
            Map<UUID, List<UUID>> waiting = new HashMap<>();
            Map<UUID, List<UUID>> decided = new HashMap<>();
            found.getT4().forEach(row -> ("PENDING".equals(Rows.string(row.get("status"))) ? waiting : decided)
                .computeIfAbsent(Rows.uuid(row.get("user_id")), id -> new ArrayList<>())
                .add(Rows.uuid(row.get("role_id"))));
            found.getT6().forEach(row -> decided.computeIfAbsent(Rows.uuid(row.get("user_id")),
                id -> new ArrayList<>()).add(Rows.uuid(row.get("role_id"))));
            Map<UUID, Map<String, Object>> invited = new HashMap<>();
            found.getT5().forEach(row -> invited.put(Rows.uuid(row.get("user_id")), row));
            Map<UUID, Facts> facts = new LinkedHashMap<>();
            for (Map<String, Object> row : found.getT1()) {
                UUID user = Rows.uuid(row.get("user_id"));
                String email = Rows.string(row.get("email"));
                String verified = Rows.string(row.get("verified_email"));
                boolean accepted = email != null && email.equalsIgnoreCase(verified);
                Map<String, Object> invitation = invited.get(user);
                facts.put(user, new Facts(user, email, Rows.string(row.get("display_name")),
                    Rows.string(row.get("locale")), accepted ? Rows.instant(row.get("email_verified_at")) : null,
                    mfa.get(user), Set.copyOf(held.getOrDefault(user, List.of())),
                    Set.copyOf(waiting.getOrDefault(user, List.of())),
                    Set.copyOf(decided.getOrDefault(user, List.of())),
                    invitation == null ? null : Rows.instant(invitation.get("first")),
                    invitation == null ? 0 : Rows.longValue(invitation.get("invitations")).intValue(),
                    Boolean.TRUE.equals(row.get("enabled"))));
            }
            return Map.copyOf(facts);
        });
    }
}

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

    /** An account of the run as recorded when it was created. */
    public record Recorded(UUID runId, String key, UUID userId, int position, List<String> roles, boolean report) {
        public Recorded {
            roles = List.copyOf(roles);
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
     * @param firstInvited   when the first invitation was recorded; null: never
     * @param invitations    how many invitations were recorded
     */
    public record Facts(UUID userId, String email, String displayName, String locale, Instant acceptedAt,
        Instant mfaConfirmedAt, Set<String> roles, Set<UUID> pendingRoles, Instant firstInvited, int invitations) {
        public Facts {
            roles = Set.copyOf(roles);
            pendingRoles = Set.copyOf(pendingRoles);
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
        row.put("account_key", account.key());
        row.put("user_id", account.userId());
        row.put("position", account.position());
        row.put("roles", String.join(",", account.roles()));
        row.put("report", account.report());
        row.put("process_seq_id", processSeqId);
        return engine().insert(TABLE, row);
    }

    /** The run's accounts, in the configuration's order. */
    public Mono<List<Recorded>> accounts(UUID runId) {
        return engine().select("SELECT run_id, account_key, user_id, position, roles, report FROM " + TABLE
                + " WHERE run_id = :run ORDER BY position", Map.of("run", BoundValue.of(runId)))
            .map(row -> new Recorded(Rows.uuid(row.get("run_id")), Rows.string(row.get("account_key")),
                Rows.uuid(row.get("user_id")), Rows.intValue(row.get("position")),
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

    /** The codes among these roles that hold one of {@code permissions} now (an administrator's, decision D51). */
    public Mono<Set<String>> rolesHolding(Collection<UUID> roleIds, Set<String> permissions, Instant now) {
        if (roleIds.isEmpty()) {
            return Mono.just(Set.of());
        }
        return engine().select("SELECT DISTINCT ro.role_code FROM (SELECT DISTINCT ON (role_permission_id) role_id,"
                + " permission, is_deleted FROM sec_role_permission_version WHERE role_id = ANY(:roles) AND"
                + " effect_start_time <= :now ORDER BY role_permission_id, effect_start_time DESC, version_no DESC) rp"
                + " JOIN (SELECT DISTINCT ON (role_id) role_id, role_code FROM sec_role_version WHERE role_id ="
                + " ANY(:roles) ORDER BY role_id, version_no DESC) ro ON ro.role_id = rp.role_id"
                + " WHERE NOT rp.is_deleted AND rp.permission = ANY(:permissions)",
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
            + " email_verified_at, verified_email FROM (SELECT DISTINCT ON (user_id) * FROM sec_user_version WHERE"
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
        Mono<List<Map<String, Object>>> pending = engine().select("SELECT c.user_id, c.role_id FROM (SELECT DISTINCT"
            + " ON (change_id) user_id, role_id, kind, status, is_deleted FROM sec_protected_change_version WHERE"
            + " user_id = ANY(:texts) ORDER BY change_id, version_no DESC) c WHERE NOT c.is_deleted AND c.kind = :kind"
            + " AND c.status = 'PENDING'", Map.of("texts", BoundValue.of(userIds.stream().map(UUID::toString)
                .toArray(String[]::new)), "kind", BoundValue.of(ProtectedChangeEntities.USER_ROLE))).collectList();
        Mono<List<Map<String, Object>>> invitations = engine().select("SELECT user_id, min(created_time) AS first,"
            + " count(*) AS invitations FROM sys_mail_message WHERE template = :template AND user_id = ANY(:users)"
            + " GROUP BY user_id", Map.of("users", params.get("users"), "template",
                BoundValue.of(INVITATION_TEMPLATE))).collectList();
        return Mono.zip(users, factors, roles, pending, invitations).map(found -> {
            Map<UUID, Instant> mfa = new HashMap<>();
            found.getT2().forEach(row -> mfa.put(Rows.uuid(row.get("user_id")), Rows.instant(row.get("confirmed"))));
            Map<UUID, List<String>> held = new HashMap<>();
            found.getT3().forEach(row -> held.computeIfAbsent(Rows.uuid(row.get("user_id")), id -> new ArrayList<>())
                .add(Rows.string(row.get("role_code"))));
            Map<UUID, List<UUID>> waiting = new HashMap<>();
            found.getT4().forEach(row -> waiting.computeIfAbsent(Rows.uuid(row.get("user_id")),
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
                    invitation == null ? null : Rows.instant(invitation.get("first")),
                    invitation == null ? 0 : Rows.longValue(invitation.get("invitations")).intValue()));
            }
            return Map.copyOf(facts);
        });
    }
}

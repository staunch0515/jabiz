package com.jabiz.runtime.sod;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.query.BoundValue;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.security.SodRule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * What segregation of duties needs to know (docs/design/18-numbering-approvals-tasks.md section 4): the SoD rules in
 * effect, and which permissions users hold through which roles. Holdings count every assignment, role and grant
 * whose latest version is not deleted (a scheduled assignment counts already) and roles that are enabled.
 */
@Component
public class SodService {

    /** Most rules read at once; more is refused rather than checked partially. */
    static final int MAX_RULES = 1000;

    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final StorageAdapterRegistry storages;
    private final String poolRef;

    public SodService(DatasetEntityManager entities, DatasetRegistry datasets, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.entities = entities;
        this.datasets = datasets;
        this.storages = storages;
        this.poolRef = poolRef;
    }

    /** One user's permissions and, per permission, the roles that grant it. */
    public record Holding(UUID userId, String userName, Map<String, Set<String>> rolesByPermission) {

        public Set<String> permissions() {
            return rolesByPermission.keySet();
        }
    }

    /** The enabled SoD rules in effect now. */
    /**
     * One user's conflict with one rule (18 section 4.4).
     *
     * @param left  the permissions of the rule's left group the user holds ({@code *} for a wildcard holder)
     * @param roles the roles granting them
     */
    public record Conflict(String userId, String userName, String ruleCode, List<String> left, List<String> right,
        List<String> roles, boolean wildcard) {}

    /**
     * The conflict report: every user who holds a permission of each group of an SoD rule, with the permissions and
     * the roles they come from. Holders of {@code *} are always listed ({@code wildcard}), whatever else they hold.
     * Ordered by user name and rule, so that the same holdings give the same report.
     */
    public Mono<List<Conflict>> conflicts() {
        return rules().zipWith(holdings(null)).map(found -> {
            List<Conflict> conflicts = new java.util.ArrayList<>();
            for (Holding holding : found.getT2().values()) {
                for (SodRule rule : found.getT1()) {
                    conflict(rule, holding).ifPresent(conflicts::add);
                }
            }
            conflicts.sort(java.util.Comparator.comparing(Conflict::userName,
                    java.util.Comparator.nullsFirst(java.util.Comparator.<String>naturalOrder()))
                .thenComparing(Conflict::userId).thenComparing(Conflict::ruleCode));
            return List.copyOf(conflicts);
        });
    }

    private static java.util.Optional<Conflict> conflict(SodRule rule, Holding holding) {
        Map<String, Set<String>> held = holding.rolesByPermission();
        boolean wildcard = SodRule.coveredByAll(held.keySet());
        if (!wildcard && !rule.violatedBy(held.keySet())) {
            return java.util.Optional.empty();
        }
        List<String> left = held.keySet().stream().filter(rule.left()::contains).sorted().toList();
        List<String> right = held.keySet().stream().filter(rule.right()::contains).sorted().toList();
        Set<String> roles = new java.util.TreeSet<>();
        held.forEach((permission, grantedBy) -> {
            if (rule.involves(permission) || (wildcard && permission.equals("*"))) {
                roles.addAll(grantedBy);
            }
        });
        return java.util.Optional.of(new Conflict(String.valueOf(holding.userId()), holding.userName(),
            rule.ruleCode(), wildcard && left.isEmpty() ? List.of("*") : left,
            wildcard && right.isEmpty() ? List.of("*") : right, List.copyOf(roles), wildcard));
    }

    public Mono<List<SodRule>> rules() {
        DatasetDefinition dataset = datasets.findById(ApprovalEntities.SOD_RULE_DATASET).orElseThrow();
        EntityQuery query = EntityQuery.builder().where(new QueryPredicate.Eq("enabled", true)).limit(MAX_RULES)
            .build();
        return entities.query(dataset, ApprovalEntities.SOD, query).collectList().map(rows -> {
            if (rows.size() >= MAX_RULES) {
                throw new IllegalStateException("More than " + MAX_RULES + " SoD rules");
            }
            return rows.stream().map(row -> SodRule.of(row.get("ruleCode"), row.get("leftPermissions"),
                row.get("rightPermissions"))).toList();
        });
    }

    /**
     * Serializes the changes of access in this transaction (until it ends), so that two concurrent assignments
     * cannot each pass the check alone.
     */
    Mono<Void> lock() {
        return engine().select("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtextextended("
            + ":key, 0))) l", Map.of("key", BoundValue.of("jabiz.sod"))).then();
    }

    /** The holdings of {@code users} (every user when null), by user id. */
    public Mono<Map<UUID, Holding>> holdings(Collection<UUID> users) {
        String sql = "SELECT u.user_id, u.user_name, r.role_code, rp.permission"
            + " FROM " + latest(SecurityEntities.SEC_USER_ROLE, "user_id, role_id") + " ur"
            + " JOIN " + latest(SecurityEntities.SEC_ROLE, "role_code, enabled") + " r ON r.role_id = ur.role_id"
            + " JOIN " + latest(SecurityEntities.SEC_ROLE_PERMISSION, "role_id, permission") + " rp"
            + " ON rp.role_id = r.role_id"
            + " JOIN " + latest(SecurityEntities.SEC_USER, "user_name") + " u ON u.user_id = ur.user_id"
            + " WHERE NOT ur.is_deleted AND NOT r.is_deleted AND r.enabled AND NOT rp.is_deleted AND NOT u.is_deleted"
            + (users == null ? "" : " AND ur.user_id = ANY(:users)")
            + " ORDER BY u.user_name, rp.permission, r.role_code";
        Map<String, BoundValue> params = users == null ? Map.of()
            : Map.of("users", BoundValue.of(users.toArray(UUID[]::new)));
        return engine().select(sql, params).collectList().map(rows -> {
            Map<UUID, Holding> holdings = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                UUID user = (UUID) row.get("user_id");
                holdings.computeIfAbsent(user, id -> new Holding(id, (String) row.get("user_name"),
                        new LinkedHashMap<>()))
                    .rolesByPermission().computeIfAbsent((String) row.get("permission"), p -> new TreeSet<>())
                    .add((String) row.get("role_code"));
            }
            return holdings;
        });
    }

    /** The permissions a role grants (none when it is disabled) and its code, as a one-role holding. */
    Mono<Map<String, Set<String>>> grantsOf(UUID roleId) {
        String sql = "SELECT r.role_code, rp.permission"
            + " FROM " + latest(SecurityEntities.SEC_ROLE, "role_code, enabled") + " r"
            + " JOIN " + latest(SecurityEntities.SEC_ROLE_PERMISSION, "role_id, permission") + " rp"
            + " ON rp.role_id = r.role_id"
            + " WHERE r.role_id = :role AND NOT r.is_deleted AND r.enabled AND NOT rp.is_deleted";
        return engine().select(sql, Map.of("role", BoundValue.of(roleId))).collectList().map(rows -> {
            Map<String, Set<String>> grants = new LinkedHashMap<>();
            rows.forEach(row -> grants.computeIfAbsent((String) row.get("permission"), p -> new TreeSet<>())
                .add((String) row.get("role_code")));
            return grants;
        });
    }

    /** The users assigned {@code roleId}. */
    Mono<List<UUID>> usersOf(UUID roleId) {
        String sql = "SELECT DISTINCT ur.user_id FROM " + latest(SecurityEntities.SEC_USER_ROLE, "user_id, role_id")
            + " ur WHERE ur.role_id = :role AND NOT ur.is_deleted";
        return engine().select(sql, Map.of("role", BoundValue.of(roleId)))
            .map(row -> (UUID) row.get("user_id")).collectList();
    }

    /**
     * {@code (SELECT DISTINCT ON (key) key, columns, is_deleted FROM table ORDER BY key, version_no DESC)}: the latest
     * version of every instance of a temporal entity, for reads of the security tables.
     */
    public static String latest(EntityDefinition def, String columns) {
        String key = SqlIdentifiers.require(def.primaryKeyColumn());
        for (String column : columns.split(",\\s*")) {
            SqlIdentifiers.require(column);
        }
        return "(SELECT DISTINCT ON (" + key + ") " + key + ", " + columns + ", is_deleted FROM "
            + SqlIdentifiers.require(def.physicalTable) + " ORDER BY " + key + ", version_no DESC)";
    }

    private StorageEngine engine() {
        return storages.getEngine(poolRef);
    }
}

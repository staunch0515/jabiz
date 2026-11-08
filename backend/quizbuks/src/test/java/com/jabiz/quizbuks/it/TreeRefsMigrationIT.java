package com.jabiz.quizbuks.it;

import com.jabiz.runtime.test.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migration that names each referenced node by its graph (V95, docs/quizbuks/plans/impl/F-S2d.md §2): the
 * references and the node rows' before_answer written before it are resolved as tree.TreeRules#resolve does (the
 * graph's own node first, else the one other graph in effect it may name that has it); a reference no single graph
 * has stays bare. Its own schemas, migrated by Flyway as the platform does, without the application.
 */
class TreeRefsMigrationIT {

    private static final PostgresTestDatabase DB = PostgresTestDatabase.get();

    private final List<String> schemas = new ArrayList<>();

    @AfterEach
    void dropSchemas() {
        schemas.forEach(DB::dropSchema);
    }

    @Test
    void theReferencesInEffectAreResolvedOwnGraphFirstElseTheOneOtherItMayName() throws Exception {
        String schema = schemaBefore();
        String platform = id();
        String twinA = id();
        String twinB = id();
        String sponsor = id();
        String copyA = id();
        String copyB = id();
        String template = id();
        // The node ids: "p1" in the platform's map; "own" in the Sponsor's own; "shared" in both copies of a template
        // (a copy keeps its template's ids) and in the template; "twin" in two platform maps (no single one).
        try (Connection c = DB.connect(schema)) {
            tree(c, platform, "PLATFORM", null, "TAKER", null);
            tree(c, twinA, "PLATFORM", null, "TAKER", null);
            tree(c, twinB, "PLATFORM", null, "TAKER", null);
            tree(c, sponsor, "SPONSOR", "sponsor-1", "TAKER", null);
            tree(c, template, "PLATFORM", null, "SPONSOR", null);
            tree(c, copyA, "OPERATOR", "op-a", "SPONSOR", template);
            tree(c, copyB, "OPERATOR", "op-b", "SPONSOR", template);
            node(c, platform, "p1", null);
            node(c, twinA, "twin", null);
            node(c, twinB, "twin", null);
            node(c, template, "shared", null);
            node(c, copyA, "shared", null);
            node(c, copyB, "shared", null);
            node(c, sponsor, "own", null);
            node(c, sponsor, "x", "[\"own\",\"p1\",\"twin\"]");
            ref(c, sponsor, "x", "own");
            ref(c, sponsor, "x", "p1");
            ref(c, sponsor, "x", "twin");
            // A node of operator A's copy naming the template's id it keeps: its own copy's node, not B's or the
            // template's.
            node(c, copyA, "y", "[\"shared\"]");
            ref(c, copyA, "y", "shared");
        }

        migrate(schema, null);

        try (Connection c = DB.connect(schema)) {
            assertThat(refTree(c, sponsor, "x", "own")).isEqualTo(sponsor);
            assertThat(refTree(c, sponsor, "x", "p1")).isEqualTo(platform);
            assertThat(refTree(c, sponsor, "x", "twin")).isNull();
            assertThat(refTree(c, copyA, "y", "shared")).isEqualTo(copyA);
            assertThat(beforeAnswer(c, sponsor, "x")).isEqualTo("[\"" + sponsor + "/own\", \"" + platform
                + "/p1\", \"twin\"]");
            assertThat(beforeAnswer(c, copyA, "y")).isEqualTo("[\"" + copyA + "/shared\"]");
            assertThat(beforeAnswer(c, platform, "p1")).isNull();
        }
    }

    // ---- schemas -----------------------------------------------------------------------------------------------------

    private String schemaBefore() {
        String schema = "it_refs_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        schemas.add(schema);
        Flyway.configure().dataSource(DB.jdbcUrl(), DB.username(), DB.password()).schemas(schema)
            .defaultSchema(schema).createSchemas(true).locations("classpath:db/jabiz").table("jabiz_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        migrate(schema, before(schema));
        return schema;
    }

    /** The latest migration before V95 that this branch has (V93 and V94, F-S6a's, may or may not be merged yet). */
    private static String before(String schema) {
        return java.util.Arrays.stream(Flyway.configure().dataSource(DB.jdbcUrl(), DB.username(), DB.password())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().info().all())
            .map(org.flywaydb.core.api.MigrationInfo::getVersion)
            .filter(version -> version.isNewerThan("0") && !version.isAtLeast("95"))
            .max(org.flywaydb.core.api.MigrationVersion::compareTo).orElseThrow().getVersion();
    }

    private static void migrate(String schema, String target) {
        var config = Flyway.configure().dataSource(DB.jdbcUrl(), DB.username(), DB.password()).schemas(schema)
            .defaultSchema(schema).locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0");
        if (target != null) {
            config.target(target);
        }
        config.load().migrate();
    }

    // ---- rows --------------------------------------------------------------------------------------------------------

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static void tree(Connection c, String treeId, String ownerKind, String ownerId, String subjectKind,
        String templateTreeId) throws SQLException {
        execute(c, "INSERT INTO qb_tree (tree_id, owner_kind, owner_id, subject_kind, template_tree_id, status, "
            + "created_at, created_by, revision, version) VALUES (?, ?, ?, ?, ?, 'ACTIVE', now(), 'it', 1, 1)",
            treeId, ownerKind, ownerId, subjectKind, templateTreeId);
    }

    private static void node(Connection c, String treeId, String nodeId, String beforeAnswer) throws SQLException {
        execute(c, "INSERT INTO qb_tree_node (row_id, tree_id, node_id, created_in_version, active, before_answer, "
            + "bindable, version) VALUES (?, ?, ?, 1, true, ?, true, 1)", id(), treeId, nodeId, beforeAnswer);
    }

    private static void ref(Connection c, String treeId, String nodeId, String refNodeId) throws SQLException {
        execute(c, "INSERT INTO qb_tree_node_ref (row_id, tree_id, node_id, ref_node_id, version) "
            + "VALUES (?, ?, ?, ?, 1)", id(), treeId, nodeId, refNodeId);
    }

    private static void execute(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            statement.execute();
        }
    }

    private static Object one(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                Map<String, Object> found = new LinkedHashMap<>();
                assertThat(rows.next()).isTrue();
                found.put("v", rows.getObject(1));
                return found.get("v");
            }
        }
    }

    private static Object refTree(Connection c, String treeId, String nodeId, String refNodeId) throws SQLException {
        return one(c, "SELECT ref_tree_id FROM qb_tree_node_ref WHERE tree_id = ? AND node_id = ? AND ref_node_id = ?",
            treeId, nodeId, refNodeId);
    }

    private static Object beforeAnswer(Connection c, String treeId, String nodeId) throws SQLException {
        return one(c, "SELECT before_answer FROM qb_tree_node WHERE tree_id = ? AND node_id = ?", treeId, nodeId);
    }
}

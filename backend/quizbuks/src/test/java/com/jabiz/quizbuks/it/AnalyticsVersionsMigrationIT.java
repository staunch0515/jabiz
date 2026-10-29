package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.stats.QuestionTotals;
import com.jabiz.runtime.test.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The migration of the question statistics to versions (V42, Q51b): a publication that never switched keeps its
 * totals and shown questions as its card's version's; one that switched under V41 is read again from its first
 * attempt (its totals counted two versions under the same numbers). Its own schemas, migrated by Flyway as the platform
 * does (its scripts, then the application's), without the application.
 */
class AnalyticsVersionsMigrationIT {

    private static final PostgresTestDatabase DB = PostgresTestDatabase.get();

    private final List<String> schemas = new ArrayList<>();

    @AfterEach
    void dropSchemas() {
        schemas.forEach(DB::dropSchema);
    }

    @Test
    void theTotalsBecomeTheCardsVersionsAndASwitchedPublicationIsReadAgain() throws Exception {
        String schema = schemaAt("41");
        UUID kept = UUID.randomUUID();
        UUID keptVersion = UUID.randomUUID();
        UUID switched = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        try (Connection connection = DB.connect(schema)) {
            for (UUID publication : List.of(kept, switched, empty)) {
                registry(connection, publication, "QbPublication");
            }
            card(connection, kept, keptVersion);
            card(connection, switched, UUID.randomUUID());
            card(connection, empty, UUID.randomUUID());
            cursor(connection, kept, 4, "[[1,4,3],[2,4,1]]", false);
            cursor(connection, switched, 6, "[[1,6,2],[2,6,5]]", true);
            cursor(connection, empty, 0, "[]", false);
            stat(connection, kept, 1, 4, 3);
            stat(connection, kept, 2, 4, 1);
            stat(connection, switched, 1, 6, 2);
            execute(connection, "INSERT INTO qb_publication_past_version (past_id, publication_id, quiz_id, version_id, "
                + "replaced_at, version) VALUES (?, ?, ?, ?, now(), 1)", UUID.randomUUID().toString(), switched,
                UUID.randomUUID(), UUID.randomUUID());
        }

        migrate(schema, null);

        try (Connection connection = DB.connect(schema)) {
            Map<String, Object> keptCursor = select(connection, "SELECT * FROM qb_analytics_cursor "
                + "WHERE publication_id = ?", kept);
            assertThat(QuestionTotals.parse(String.valueOf(keptCursor.get("questions"))))
                .isEqualTo(new TreeMap<>(Map.of(keptVersion.toString(), new QuestionTotals.Version(4, new TreeMap<>(
                    Map.of(1, new QuestionTotals.Count(4, 3), 2, new QuestionTotals.Count(4, 1)))))));
            assertThat(keptCursor.get("watermark_id")).isEqualTo("a-last");
            assertThat(rows(connection, "SELECT version_id, question_no, based_on FROM qb_question_stat "
                + "WHERE publication_id = ? ORDER BY question_no", kept))
                .extracting(row -> List.of(row.get("version_id"), ((Number) row.get("question_no")).intValue(),
                    ((Number) row.get("based_on")).intValue()))
                .containsExactly(List.of(keptVersion, 1, 4), List.of(keptVersion, 2, 4));

            // The switched one: read again from the first attempt, its shown questions gone, not finalized.
            Map<String, Object> again = select(connection, "SELECT * FROM qb_analytics_cursor WHERE publication_id = ?",
                switched);
            assertThat(again).containsEntry("questions", "{}").containsEntry("watermark_at", null)
                .containsEntry("watermark_id", null).containsEntry("finalized_at", null);
            assertThat(((Number) again.get("analysed")).intValue()).isZero();
            // What the sponsor was shown stays until the new totals pass it by the minimum group.
            assertThat(((Number) again.get("shown_analysed")).intValue()).isEqualTo(6);
            assertThat(rows(connection, "SELECT 1 FROM qb_question_stat WHERE publication_id = ?", switched)).isEmpty();

            assertThat(select(connection, "SELECT questions FROM qb_analytics_cursor WHERE publication_id = ?", empty))
                .containsEntry("questions", "{}");

            // One row per version and question from now on.
            stat(connection, kept, UUID.randomUUID(), 1, 1, 1);
            assertThatThrownBy(() -> stat(connection, kept, keptVersion, 1, 1, 1))
                .hasMessageContaining("qb_question_stat_once");
        }
    }

    // ---- schemas -----------------------------------------------------------------------------------------------------

    private String schemaAt(String target) {
        String schema = "it_q51b_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        schemas.add(schema);
        Flyway.configure().dataSource(DB.jdbcUrl(), DB.username(), DB.password()).schemas(schema)
            .defaultSchema(schema).createSchemas(true).locations("classpath:db/jabiz").table("jabiz_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        migrate(schema, target);
        return schema;
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

    private static void registry(Connection connection, UUID id, String type) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO op_process (process_seq_id, process_name, process_version, actor_id, "
                + "op_time) VALUES (nextval('op_process_seq'), 'IT', 1, 'it', now())");
        }
        execute(connection, "INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) "
            + "VALUES (?, ?, currval('op_process_seq'))", id, type);
    }

    private static void card(Connection connection, UUID publication, UUID version) {
        execute(connection, "INSERT INTO qb_publication_card (publication_id, publisher_kind, publisher_id, status, regions, "
            + "start_at, end_at, name, quiz_title, version_id, max_reward, full_score, pass_score, time_limit_sec, "
            + "question_count, tiers, went_on_at, version, audience, kind, reward_method) VALUES (?, 'SPONSOR', 'sponsor-a', "
            + "'LIVE', 'GLOBAL', now(), now() + interval '7 days', 'Old', 'Old', ?, 100, 5, 3, 300, 2, '[]', now(), "
            + "1, 'PUBLIC', 'QUIZ', 'PLATFORM')", publication, version);
    }

    private static void cursor(Connection connection, UUID publication, int analysed, String questions,
        boolean finalized) {
        execute(connection, "INSERT INTO qb_analytics_cursor (publication_id, publisher_kind, publisher_id, views, starts, completions, "
            + "passes, winners, spent, watermark_at, watermark_id, analysed, submitted, expired, duration_sec, "
            + "questions, shown_analysed, shown_submitted, shown_expired, shown_duration_sec, shown_at, finalized_at, "
            + "version) VALUES (?, 'SPONSOR', 'sponsor-a', ?, ?, ?, 0, 0, 0, now(), 'a-last', ?, ?, 0, 60, ?, ?, ?, 0, 60, now(), "
            + (finalized ? "now()" : "NULL") + ", 1)", publication, analysed, analysed, analysed, analysed, analysed,
            questions, analysed, analysed);
    }

    private static void stat(Connection connection, UUID publication, int no, int completions, int correct) {
        execute(connection, "INSERT INTO qb_question_stat (stat_id, publication_id, publisher_kind, publisher_id, question_no, "
            + "completions, correct, shown_at, version) VALUES (?, ?, 'SPONSOR', 'sponsor-a', ?, ?, ?, now(), 1)",
            UUID.randomUUID().toString(), publication, no, completions, correct);
    }

    private static void stat(Connection connection, UUID publication, UUID version, int no, int completions,
        int correct) {
        execute(connection, "INSERT INTO qb_question_stat (stat_id, publication_id, publisher_kind, publisher_id, version_id, "
            + "question_no, completions, correct, based_on, shown_at, version) VALUES (?, ?, 'SPONSOR', 'sponsor-a', ?, ?, ?, ?, "
            + "?, now(), 1)", UUID.randomUUID().toString(), publication, version, no, completions, correct,
            completions);
    }

    private static void execute(Connection connection, String sql, Object... parameters) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.execute();
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static List<Map<String, Object>> rows(Connection connection, String sql, Object parameter)
        throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static Map<String, Object> select(Connection connection, String sql, Object parameter)
        throws SQLException {
        List<Map<String, Object>> rows = rows(connection, sql, parameter);
        assertThat(rows).as(sql).hasSize(1);
        return rows.getFirst();
    }
}

package com.jabiz.quizbuks.it;

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
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The migration of the engine's publisher (V29, docs/quizbuks/shopify/S0-publisher.md D-S0-5): the rows written
 * before it keep their sponsor as the publisher of kind SPONSOR; afterwards every write must name the kind, the
 * dictionary's kinds only, and the append-only tables stay append-only. Those keep the id's column sponsor_id (V30:
 * their columns are sealed, {@link SealedColumnsMigrationIT}); the ordinary tables have it as publisher_id. Its own schemas, migrated by Flyway as the
 * platform does (its scripts, then the application's), without the application.
 */
class PublisherMigrationIT {

    private static final PostgresTestDatabase DB = PostgresTestDatabase.get();

    /** The append-only tables D-S0-2 gives the pair, whose id stays in its sealed column sponsor_id (V30). */
    private static final List<String> SEALED = List.of("qb_publication_version", "qb_reward_tier_version",
        "qb_cash_reward_version", "qb_redemption_version", "qb_digital_prize_version");
    /** The ordinary tables D-S0-2 renames (the merchant account's own keep sponsor_id). */
    private static final List<String> RENAMED = List.of("qb_publication_card", "qb_attempt", "qb_analytics_hour",
        "qb_analytics_cursor", "qb_question_stat", "qb_digital_code", "qb_invitation");
    private static final List<String> WITH_PUBLISHER = Stream.concat(SEALED.stream(), RENAMED.stream()).toList();
    private static final List<String> KEPT = List.of("qb_topup", "qb_topup_receipt_version", "qb_sponsor_mail_server",
        "qb_invitation_quota", "qb_redeem_token", "qb_ai_job", "qb_ai_usage");

    private final List<String> schemas = new ArrayList<>();

    @AfterEach
    void dropSchemas() {
        schemas.forEach(DB::dropSchema);
    }

    @Test
    void theRowsOfV28AreASponsorsAndTheColumnsFollowTheirNewName() throws Exception {
        String schema = schemaAtV28();
        UUID publication = UUID.randomUUID();
        UUID tier = UUID.randomUUID();
        String attempt = UUID.randomUUID().toString();
        try (Connection connection = DB.connect(schema)) {
            registry(connection, publication, "QbPublication");
            registry(connection, tier, "QbRewardTier");
            execute(connection, "INSERT INTO qb_publication_card (publication_id, sponsor_id, status, regions, "
                + "start_at, end_at, name, quiz_title, version_id, max_reward, full_score, pass_score, time_limit_sec, "
                + "question_count, tiers, went_on_at, version, audience, kind, reward_method) VALUES (?, 'sponsor-a', "
                + "'LIVE', 'GLOBAL', now(), now() + interval '7 days', 'Old', 'Old', ?, 100, 5, 3, 300, 2, '[]', now(), "
                + "1, 'PUBLIC', 'QUIZ', 'PLATFORM')", publication, UUID.randomUUID());
            execute(connection, "INSERT INTO qb_reward_tier_version (tier_id, version_no, effect_start_time, "
                + "created_time, process_seq_id, sponsor_id, publication_id, seq, from_score, to_score, reward) "
                + "VALUES (?, 1, now(), now(), currval('op_process_seq'), 'sponsor-a', ?, 1, 0, 5, 100)", tier,
                publication);
            execute(connection, "INSERT INTO qb_attempt (attempt_id, user_id, publication_id, sponsor_id, version_id, "
                + "started_at, deadline, status, current_index, option_order, answers, version) VALUES (?, 'taker-1', "
                + "?, 'sponsor-a', ?, now(), now() + interval '5 minutes', 'IN_PROGRESS', 0, '[]', '[]', 1)", attempt,
                publication, UUID.randomUUID());
        }

        migrate(schema, null);

        try (Connection connection = DB.connect(schema)) {
            for (String table : SEALED) {
                assertThat(columns(connection, table)).as(table).contains("sponsor_id", "publisher_kind")
                    .doesNotContain("publisher_id");
            }
            for (String table : RENAMED) {
                assertThat(columns(connection, table)).as(table).contains("publisher_id", "publisher_kind")
                    .doesNotContain("sponsor_id");
            }
            for (String table : WITH_PUBLISHER) {
                // Without a default from now on: every write names the kind.
                assertThat(select(connection, "SELECT column_default FROM information_schema.columns WHERE "
                    + "table_schema = current_schema() AND table_name = ? AND column_name = 'publisher_kind'", table))
                    .as(table).containsEntry("column_default", null);
            }
            for (String table : KEPT) {
                assertThat(columns(connection, table)).as(table).contains("sponsor_id").doesNotContain("publisher_id");
            }
            assertThat(select(connection, "SELECT publisher_kind, publisher_id FROM qb_publication_card WHERE "
                + "publication_id = ?", publication)).containsEntry("publisher_kind", "SPONSOR")
                .containsEntry("publisher_id", "sponsor-a");
            assertThat(select(connection, "SELECT publisher_kind, sponsor_id FROM qb_reward_tier_version WHERE "
                + "tier_id = ?", tier)).containsEntry("publisher_kind", "SPONSOR")
                .containsEntry("sponsor_id", "sponsor-a");
            assertThat(select(connection, "SELECT publisher_kind, publisher_id FROM qb_attempt WHERE attempt_id = ?",
                attempt)).containsEntry("publisher_kind", "SPONSOR").containsEntry("publisher_id", "sponsor-a");
            // The view over the cards carries the kind.
            assertThat(columns(connection, "qb_publication_owner")).containsExactlyInAnyOrder("publication_id",
                "publisher_kind", "publisher_id");
            assertThat(select(connection, "SELECT publisher_kind FROM qb_publication_owner WHERE publication_id = ?",
                publication)).containsEntry("publisher_kind", "SPONSOR");
            // The indexes follow the column: publisher on the ordinary tables, sponsor again on the sealed ones (V30).
            assertThat(select(connection, "SELECT count(*) AS n FROM pg_indexes WHERE schemaname = current_schema() "
                + "AND indexname LIKE '%sponsor%' AND tablename = ANY(?)", connection.createArrayOf("text",
                RENAMED.toArray()))).containsEntry("n", 0L);
            assertThat(select(connection, "SELECT count(*) AS n FROM pg_indexes WHERE schemaname = current_schema() "
                + "AND indexname LIKE '%publisher%' AND tablename = ANY(?)", connection.createArrayOf("text",
                SEALED.toArray()))).containsEntry("n", 0L);
            assertThat(select(connection, "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND "
                + "indexname = 'qb_attempt_pending_idx'", null).get("indexdef").toString()).contains("publisher_id");
        }
    }

    @Test
    void afterwardsEveryWriteNamesAKindOfTheDictionaryAndTheAppendOnlyTablesStaySo() throws Exception {
        String schema = schemaAtV28();
        migrate(schema, null);
        UUID publication = UUID.randomUUID();
        try (Connection connection = DB.connect(schema)) {
            registry(connection, publication, "QbPublication");
            String attempt = "INSERT INTO qb_attempt (attempt_id, user_id, publication_id, %s version_id, started_at, "
                + "deadline, status, current_index, option_order, answers, version) VALUES (?, 'taker-1', ?, %s ?, "
                + "now(), now() + interval '5 minutes', 'IN_PROGRESS', 0, '[]', '[]', 1)";
            assertThatThrownBy(() -> execute(connection, String.format(attempt, "publisher_id,", "'sponsor-a',"),
                UUID.randomUUID().toString(), publication, UUID.randomUUID()))
                .hasMessageContaining("publisher_kind");
            assertThatThrownBy(() -> execute(connection, String.format(attempt, "publisher_kind, publisher_id,",
                "'OTHER', 'x',"), UUID.randomUUID().toString(), publication, UUID.randomUUID()))
                .hasMessageContaining("qb_attempt_publisher_kind_check");
            execute(connection, String.format(attempt, "publisher_kind, publisher_id,", "'SHOPIFY_SHOP', 'shop-1',"),
                UUID.randomUUID().toString(), publication, UUID.randomUUID());

            UUID tier = UUID.randomUUID();
            registry(connection, tier, "QbRewardTier");
            execute(connection, "INSERT INTO qb_reward_tier_version (tier_id, version_no, effect_start_time, "
                + "created_time, process_seq_id, publisher_kind, sponsor_id, publication_id, seq, from_score, "
                + "to_score, reward) VALUES (?, 1, now(), now(), currval('op_process_seq'), 'SPONSOR', 'sponsor-a', "
                + "?, 1, 0, 5, 100)", tier, publication);
            // Renaming a column leaves the append-only triggers in place (platform decision D5).
            assertThatThrownBy(() -> execute(connection, "UPDATE qb_reward_tier_version SET sponsor_id = 'b' WHERE "
                + "tier_id = ?", tier)).hasMessageContaining("append-only");
        }
    }

    // ---- schemas -----------------------------------------------------------------------------------------------------

    /** A new schema with the platform's scripts and the application's up to V28. */
    private String schemaAtV28() {
        String schema = "it_publisher_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        schemas.add(schema);
        Flyway.configure().dataSource(DB.jdbcUrl(), DB.username(), DB.password()).schemas(schema)
            .defaultSchema(schema).createSchemas(true).locations("classpath:db/jabiz").table("jabiz_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        migrate(schema, "28");
        return schema;
    }

    /** The application's scripts up to {@code target} (all when null), as the platform runs them. */
    private static void migrate(String schema, String target) {
        var config = Flyway.configure().dataSource(DB.jdbcUrl(), DB.username(), DB.password()).schemas(schema)
            .defaultSchema(schema).locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0");
        if (target != null) {
            config.target(target);
        }
        config.load().migrate();
    }

    // ---- rows --------------------------------------------------------------------------------------------------------

    /** An entity's row in the platform's registry (the temporal rows and the cards refer to it). */
    private static void registry(Connection connection, UUID id, String type) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO op_process (process_seq_id, process_name, process_version, actor_id, "
                + "op_time) VALUES (nextval('op_process_seq'), 'IT', 1, 'it', now())");
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO entity_registry (entity_id, "
            + "entity_type, created_seq_id) VALUES (?, ?, currval('op_process_seq'))")) {
            statement.setObject(1, id);
            statement.setString(2, type);
            statement.execute();
        }
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

    private static List<String> columns(Connection connection, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT column_name FROM "
            + "information_schema.columns WHERE table_schema = current_schema() AND table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
        }
        return columns;
    }

    private static Map<String, Object> select(Connection connection, String sql, Object parameter)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (parameter != null) {
                statement.setObject(1, parameter);
            }
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as(sql).isTrue();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                return row;
            }
        }
    }
}

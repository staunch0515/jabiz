package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.publishing.IntroLeads;
import com.jabiz.runtime.test.PostgresTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migration that fills the cards' lead of the introduction (V134, Q113b-1) uses the same rules as
 * {@link IntroLeads}, which writes it from then on: its function and its update are run here, as written, on two
 * stand-ins for the card and the version (only the columns they read), with the texts the Java rules are tested on.
 */
class IntroLeadMigrationIT {

    private static final PostgresTestDatabase DB = PostgresTestDatabase.get();
    private static final String MIGRATION = "db/migration/V134__quizbuks_card_intro_lead.sql";

    /** Introductions of every rule: marks, images, links, lists, lines, lengths, and two-unit characters. */
    private static final List<String> INTRODUCTIONS = List.of(
        "# Tea\n\nLearn **green** and _black_ tea. See [the guide](https://example.com).\n![a cup](https://x/cup.png)",
        "> Quote with `code` and ~~strike~~",
        "Three steps:\n- look\n+ smell\n 2. taste well-brewed tea",
        "## **",
        " \n\t ",
        "a".repeat(IntroLeads.LENGTH),
        "b".repeat(IntroLeads.LENGTH + 1),
        "🍵".repeat(IntroLeads.LENGTH + 5),
        "古树茶和台地茶的区别很直接。\n看完短片，答 5 道题。",
        "| table | row |\n|---|---|\n| one | two |");

    private String schema;

    @AfterEach
    void dropSchema() {
        if (schema != null) {
            DB.dropSchema(schema);
        }
    }

    @Test
    void theMigrationFillsTheCardsAsIntroLeadsWouldInEveryLanguageOffered() throws Exception {
        schema = "it_lead_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DB.connect("public")) {
            execute(c, "CREATE SCHEMA " + schema);
        }
        try (Connection c = DB.connect(schema)) {
            execute(c, "CREATE TABLE qb_quiz_version_version (version_id uuid PRIMARY KEY, content text NOT NULL)");
            execute(c, "CREATE TABLE qb_publication_card (publication_id uuid PRIMARY KEY, version_id uuid NOT NULL, "
                + "translations varchar(20000), intro_lead varchar(160))");
            for (String introduction : INTRODUCTIONS) {
                // The base's introduction, and the same text again as the Chinese translation's.
                String version = UUID.randomUUID().toString();
                String content = "{\"intro\": " + json(introduction) + ", \"translations\": {\"zh\": {\"texts\": "
                    + "{\"intro\": " + json(introduction) + "}}, \"ja\": {\"texts\": {}}}}";
                insert(c, "INSERT INTO qb_quiz_version_version VALUES (?::uuid, ?)", version, content);
                insert(c, "INSERT INTO qb_publication_card (publication_id, version_id, translations) "
                    + "VALUES (?::uuid, ?::uuid, ?)", UUID.randomUUID().toString(), version,
                    "{\"ja\":{\"name\":\"名前\"},\"zh\":{\"name\":\"名字\",\"quizTitle\":\"茶\"}}");
            }
            // One card offering its base only: no texts, none written.
            String alone = UUID.randomUUID().toString();
            insert(c, "INSERT INTO qb_quiz_version_version VALUES (?::uuid, ?)", alone, "{\"intro\": \"Alone.\"}");
            insert(c, "INSERT INTO qb_publication_card (publication_id, version_id) VALUES (?::uuid, ?::uuid)",
                UUID.randomUUID().toString(), alone);

            // The migration as written, but for the column added (the stand-in has it).
            String sql = migration();
            execute(c, sql.substring(sql.indexOf("CREATE FUNCTION")));

            for (String introduction : INTRODUCTIONS) {
                String expected = IntroLeads.of(introduction);
                try (PreparedStatement select = c.prepareStatement("SELECT c.intro_lead, "
                    + "CAST(c.translations AS jsonb) -> 'zh' ->> 'introLead' AS zh, "
                    + "CAST(c.translations AS jsonb) -> 'zh' ->> 'name' AS zh_name, "
                    + "CAST(c.translations AS jsonb) -> 'ja' AS ja "
                    + "FROM qb_publication_card c JOIN qb_quiz_version_version v ON v.version_id = c.version_id "
                    + "WHERE CAST(v.content AS jsonb) ->> 'intro' = ?")) {
                    select.setString(1, introduction);
                    try (ResultSet row = select.executeQuery()) {
                        assertThat(row.next()).as(introduction).isTrue();
                        assertThat(row.getString("intro_lead")).as(introduction).isEqualTo(expected);
                        assertThat(row.getString("zh")).as(introduction).isEqualTo(expected);
                        // What the texts had stays; a language with no introduction gets none.
                        assertThat(row.getString("zh_name")).isEqualTo("名字");
                        assertThat(row.getString("ja")).isEqualTo("{\"name\": \"名前\"}");
                    }
                }
            }
            try (Statement select = c.createStatement(); ResultSet row = select.executeQuery(
                "SELECT intro_lead, translations FROM qb_publication_card WHERE version_id = '" + alone + "'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("intro_lead")).isEqualTo("Alone.");
                assertThat(row.getString("translations")).isNull();
            }
        }
    }

    private static String migration() throws IOException {
        try (InputStream in = IntroLeadMigrationIT.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as(MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String json(String text) {
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(text);
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement statement = c.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void insert(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            statement.execute();
        }
    }
}

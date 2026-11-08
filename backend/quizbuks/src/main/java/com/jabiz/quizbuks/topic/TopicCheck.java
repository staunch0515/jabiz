package com.jabiz.quizbuks.topic;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F-S9: every word of the word list of topics has its name in every language of the platform (zh, ja, en), deactivated
 * and merged words too (they are still shown where they were chosen). The processes require all three; a word written
 * otherwise (a migration, a hand-made fix) without one is an error of the startup check and {@code platformCheck}
 * (category {@code QUIZBUKS}), one problem a word naming the languages it lacks.
 *
 * <p>The word list is data, so the check reads the database itself (JDBC with Flyway's settings, as
 * {@code LiveModeDataCheck} and {@code CertificationLegalCheck}), at startup and in {@code platformCheck}, never on the
 * request path. When it cannot read it says so as a warning. The table and columns come from the entity's metadata.
 */
@Component
public class TopicCheck implements PlatformCheck {

    static final String LOCATION = "topic";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final Environment environment;
    private final EntityDefinitionRegistry entities;

    public TopicCheck(Environment environment, EntityDefinitionRegistry entities) {
        this.environment = environment;
        this.entities = entities;
    }

    @Override
    public List<CheckProblem> check() {
        String url = environment.getProperty("spring.flyway.url");
        if (url == null || url.isBlank()) {
            return List.of();
        }
        try (Connection connection = DriverManager.getConnection(url, environment.getProperty("spring.flyway.user"),
            environment.getProperty("spring.flyway.password"))) {
            String schema = environment.getProperty("spring.flyway.schemas");
            if (schema != null && !schema.isBlank()) {
                connection.setSchema(schema.split(",")[0].strip());
            }
            connection.setReadOnly(true);
            return problems(read(connection));
        } catch (SQLException | RuntimeException e) {
            return List.of(CheckProblem.warning(CountryCatalog.CATEGORY, LOCATION, "The word list of topics cannot be "
                + "checked for names in every language: " + e.getMessage()));
        }
    }

    /** Each word's names by language, by code (a name that cannot be read: none). */
    private Map<String, Map<String, Object>> read(Connection connection) throws SQLException {
        EntityDefinition topic = entities.getOrThrow(QbTopics.TOPIC);
        String sql = "SELECT " + column(topic, "topicId") + ", CAST(" + column(topic, "name") + " AS text) FROM "
            + SqlIdentifiers.require(topic.physicalTable) + " ORDER BY 1";
        Map<String, Map<String, Object>> names = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                names.put(rows.getString(1), parse(rows.getString(2)));
            }
        }
        return names;
    }

    private static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> map = JSON.readValue(json, MAP);
            return map == null ? Map.of() : map;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** The errors: one a word without its name in some language of the platform. */
    public static List<CheckProblem> problems(Map<String, Map<String, Object>> names) {
        List<CheckProblem> problems = new ArrayList<>();
        names.forEach((code, byLanguage) -> {
            List<String> missing = QbContent.LANGUAGES.stream().filter(language -> {
                Object name = byLanguage.get(language);
                return name == null || name.toString().isBlank();
            }).toList();
            if (!missing.isEmpty()) {
                problems.add(CheckProblem.error(CountryCatalog.CATEGORY, LOCATION + ":" + code, "Topic " + code
                    + " of the word list has no name in " + String.join(", ", missing) + ": rename it "
                    + "(QB_TOPIC_RENAME) with a name in every language (docs/quizbuks/operations.md §7.26)"));
            }
        });
        return List.copyOf(problems);
    }

    private static String column(EntityDefinition entity, String field) {
        return SqlIdentifiers.require(entity.physicalColumn(field));
    }
}

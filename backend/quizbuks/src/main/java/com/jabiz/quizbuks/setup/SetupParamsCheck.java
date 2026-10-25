package com.jabiz.quizbuks.setup;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.param.ParamEntities;
import com.jabiz.query.SqlIdentifiers;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every business parameter QuizBuks defines ({@link QbParams#ALL}) has a value in effect (docs/quizbuks/plans/
 * Q26-param-check.md): a deployment of a version that added parameters needs {@code QB_SETUP} run once afterwards
 * (operations.md, "after every deployment"), and a controlled one its proposal published. Forgotten, the processes
 * that read the parameter fail on a user's screen ({@code PARAM_NOT_FOUND}); this check says so in the startup and
 * {@code platformCheck} output instead. Warnings only (category {@value CountryCatalog#CATEGORY}): the application
 * must start for an administrator to run the setup. A database where none of the parameters exists is reported once
 * ("QB_SETUP has not run yet"), not once per parameter.
 *
 * <p>Reads the database itself (JDBC with Flyway's connection settings, as {@code LiveModeDataCheck} does): the
 * parameters' service is reactive, which the application's code does not use. It runs at startup and in
 * {@code platformCheck}, never on the request path; without a database address it is skipped. The table and the key
 * column come from the platform's parameter entity; the version columns are the platform's temporal ones.
 */
@Component
public class SetupParamsCheck implements PlatformCheck {

    static final String LOCATION = "QB_SETUP";

    private final Environment environment;
    private final EntityDefinitionRegistry entities;
    private final Clock clock;

    public SetupParamsCheck(Environment environment, EntityDefinitionRegistry entities, Clock clock) {
        this.environment = environment;
        this.entities = entities;
        this.clock = clock;
    }

    @Override
    public List<CheckProblem> check() {
        String url = environment.getProperty("spring.flyway.url");
        if (url == null || url.isBlank()) {
            return List.of();
        }
        Set<String> present;
        try (Connection connection = DriverManager.getConnection(url, environment.getProperty("spring.flyway.user"),
            environment.getProperty("spring.flyway.password"))) {
            String schema = schema();
            if (schema != null) {
                connection.setSchema(schema);
            }
            connection.setReadOnly(true);
            present = inEffect(connection);
        } catch (SQLException | RuntimeException e) {
            return List.of(CheckProblem.warning(CountryCatalog.CATEGORY, LOCATION,
                "the business parameters could not be read: " + e.getMessage()));
        }
        return problems(QbParams.ALL.stream().map(QbParams.Param::key).toList(), present);
    }

    /** The warnings for the {@code keys} not among those {@code present}: one each, or one for all when none is. */
    static List<CheckProblem> problems(List<String> keys, Set<String> present) {
        List<String> missing = keys.stream().filter(key -> !present.contains(key)).toList();
        if (missing.isEmpty()) {
            return List.of();
        }
        if (missing.size() == keys.size()) {
            return List.of(CheckProblem.warning(CountryCatalog.CATEGORY, LOCATION, "QB_SETUP has not run yet: none "
                + "of the " + keys.size() + " business parameters has a value (operations.md: run it, then publish "
                + "its proposals)"));
        }
        List<CheckProblem> problems = new ArrayList<>();
        for (String key : missing) {
            boolean controlled = QbParams.CONTROLLED.contains(key);
            problems.add(CheckProblem.warning(CountryCatalog.CATEGORY, "parameter " + key, "no value in effect: "
                + (controlled
                    ? "run QB_SETUP after the deployment and have another administrator publish its proposal "
                        + "(a controlled parameter)"
                    : "run QB_SETUP after the deployment (it creates the missing parameters and changes nothing else)")
                + "; the processes reading it fail until then"));
        }
        return problems;
    }

    /** The keys of the parameters whose version in effect now (by the application's clock) is not a deletion. */
    private Set<String> inEffect(Connection connection) throws SQLException {
        EntityDefinition param = entities.getOrThrow(ParamEntities.ENTITY);
        String key = SqlIdentifiers.require(param.physicalColumn(ParamEntities.KEY));
        String sql = "SELECT " + key + " FROM (SELECT DISTINCT ON (param_id) " + key + ", is_deleted FROM "
            + SqlIdentifiers.require(param.physicalTable) + " WHERE effect_start_time <= ? "
            + "ORDER BY param_id, effect_start_time DESC, version_no DESC) v WHERE NOT v.is_deleted";
        Set<String> keys = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    keys.add(rows.getString(1));
                }
            }
        }
        return keys;
    }

    private String schema() {
        String schema = environment.getProperty("spring.flyway.default-schema");
        if (schema == null || schema.isBlank()) {
            String schemas = environment.getProperty("spring.flyway.schemas");
            schema = schemas == null ? null : schemas.split(",")[0].strip();
        }
        return schema == null || schema.isBlank() ? null : schema;
    }
}

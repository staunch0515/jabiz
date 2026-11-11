package com.jabiz.quizbuks.setup;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.quizbuks.wallet.StripeSettings;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.param.ParamEntities;
import com.jabiz.runtime.web.JabizWebProperties;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The money switches of the first launch (F-F10a, docs/quizbuks/plans/impl/F-F10.md section 1.5), at startup and in
 * {@code platformCheck} (category {@value CountryCatalog#CATEGORY}). On the real system ({@code jabiz.web.environment}
 * {@code production}) it warns for each of the switches that must be off at the first launch while it is on, and while
 * the Kudos reward switch has no value at all (publishing is then not blocked); a warning only, since the application
 * has to start for the finance administrators to publish the proposals. It does not warn for a value that is not
 * published yet ({@link SetupParamsCheck} says so). It also warns when Stripe is in live mode on a system that does not
 * say it is production: that system proposes the declared values, not the first launch's.
 *
 * <p>Reads the database itself, as {@link SetupParamsCheck} does (JDBC with Flyway's connection settings, never on the
 * request path); skipped without a database address and outside production.
 */
@Component
public class FirstLaunchSwitchesCheck implements PlatformCheck {

    static final String LOCATION = "first launch";

    /** The switches that are off at the first launch, as the registry lists them: on is a warning. */
    static final List<String> MUST_BE_OFF = List.of(QbParams.KUDOS_BUY_ENABLED, QbParams.KUDOS_SELL_ENABLED,
        QbParams.TRANSFER_ENABLED, QbParams.WITHDRAWAL_BANK_ENABLED, QbParams.REWARD_KUDOS_ENABLED);

    private final JabizWebProperties web;
    private final StripeSettings stripe;
    private final Environment environment;
    private final EntityDefinitionRegistry entities;
    private final Clock clock;

    public FirstLaunchSwitchesCheck(JabizWebProperties web, StripeSettings stripe, Environment environment,
        EntityDefinitionRegistry entities, Clock clock) {
        this.web = web;
        this.stripe = stripe;
        this.environment = environment;
        this.entities = entities;
        this.clock = clock;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>(liveModeProblems(web.environment(), stripe.livemode()));
        if (!JabizWebProperties.PRODUCTION.equals(web.environment())) {
            return List.copyOf(problems);
        }
        String url = environment.getProperty("spring.flyway.url");
        if (url == null || url.isBlank()) {
            return List.copyOf(problems);
        }
        try (Connection connection = DriverManager.getConnection(url, environment.getProperty("spring.flyway.user"),
            environment.getProperty("spring.flyway.password"))) {
            String schema = schema();
            if (schema != null) {
                connection.setSchema(schema);
            }
            connection.setReadOnly(true);
            problems.addAll(problems(values(connection)));
        } catch (SQLException | RuntimeException e) {
            problems.add(CheckProblem.warning(CountryCatalog.CATEGORY, LOCATION,
                "the first launch's switches could not be read: " + e.getMessage()));
        }
        return List.copyOf(problems);
    }

    /** Stripe's live mode on a system that is not production: it proposes the declared values, not the first launch's. */
    static List<CheckProblem> liveModeProblems(String environment, boolean livemode) {
        if (!livemode || JabizWebProperties.PRODUCTION.equals(environment)) {
            return List.of();
        }
        return List.of(CheckProblem.warning(CountryCatalog.CATEGORY, "jabiz.web.environment",
            "Stripe is in live mode (quizbuks.stripe.livemode=true) but jabiz.web.environment is '" + environment
                + "', not production: QB_SETUP proposes the declared values of the money switches (cash into Kudos on, "
                + "Kudos rewards on), not the first launch's. Set JABIZ_WEB_ENVIRONMENT=production (operations.md)"));
    }

    /**
     * The warnings for the production system, from the values in effect ({@code values}: key to text; a key that has no
     * value is not in the map): one for each switch that must be off and is on, and one when the Kudos reward switch
     * is not there.
     */
    static List<CheckProblem> problems(Map<String, String> values) {
        List<CheckProblem> problems = new ArrayList<>();
        for (String key : MUST_BE_OFF) {
            String value = values.get(key);
            if (value == null) {
                if (QbParams.REWARD_KUDOS_ENABLED.equals(key)) {
                    problems.add(CheckProblem.warning(CountryCatalog.CATEGORY, "parameter " + key, "has no value: "
                        + "a publication may still offer a Kudos reward until it is off at the first launch "
                        + "(operations.md section 4: run QB_SETUP and have another administrator publish it)"));
                }
            } else if ("true".equalsIgnoreCase(value.trim())) {
                problems.add(CheckProblem.warning(CountryCatalog.CATEGORY, "parameter " + key, "is on, but the first "
                    + "launch has it off (operations.md section 4): propose false as a controlled change"));
            }
        }
        return List.copyOf(problems);
    }

    /** The values in effect now (by the application's clock) of the switches, by key, deleted ones left out. */
    private Map<String, String> values(Connection connection) throws SQLException {
        EntityDefinition param = entities.getOrThrow(ParamEntities.ENTITY);
        String key = SqlIdentifiers.require(param.physicalColumn(ParamEntities.KEY));
        String value = SqlIdentifiers.require(param.physicalColumn(ParamEntities.VALUE));
        String sql = "SELECT " + key + ", " + value + " FROM (SELECT DISTINCT ON (param_id) " + key + ", " + value
            + ", is_deleted FROM " + SqlIdentifiers.require(param.physicalTable) + " WHERE " + key + " = ANY(?) AND "
            + "effect_start_time <= ? ORDER BY param_id, effect_start_time DESC, version_no DESC) v "
            + "WHERE NOT v.is_deleted";
        Map<String, String> values = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, connection.createArrayOf("text", MUST_BE_OFF.toArray()));
            statement.setObject(2, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.put(rows.getString(1), rows.getString(2));
                }
            }
        }
        return values;
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

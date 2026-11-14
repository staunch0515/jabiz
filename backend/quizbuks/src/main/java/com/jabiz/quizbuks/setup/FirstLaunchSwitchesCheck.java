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
 * the Kudos reward switch has no value at all (a Kudos reward is then refused: not there counts as off); a warning only, since the application
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

    /**
     * The switches that are off at the first launch: the registry's active rows whose first launch value is false, and
     * its waiting rows whose key already exists ({@link FirstLaunchSwitches#KEY_BEFORE_MEANING}: declared off, and not to
     * be turned on before their feature is there); the registry is the one list, so a switch added there is checked
     * without a second edit. On is a warning.
     */
    static final List<String> MUST_BE_OFF = FirstLaunchSwitches.ALL.stream()
        .filter(row -> (row.status() == FirstLaunchSwitches.Status.ACTIVE && Boolean.FALSE.equals(row.value()))
            || (row.status() == FirstLaunchSwitches.Status.WAITING
                && FirstLaunchSwitches.KEY_BEFORE_MEANING.contains(row.key())))
        .map(FirstLaunchSwitches.Row::key).toList();

    /**
     * F-M9a-2b: the AI fee's switches and the website draft's reservation limits, which {@link #aiFeeProblems} reads
     * (#692 re-review L-b, plan question 6).
     */
    static final List<String> AI_FEE_KEYS = List.of(QbParams.AI_FEE_ENABLED, QbParams.SITE_PROFILE_ENABLED,
        QbParams.AI_FEE_SITE_CALLS, QbParams.AI_FEE_SITE_PAGES, QbParams.AI_FEE_SITE_OUTPUT);

    /** The most calls an attempt of the website draft makes (ClaudeWebResearch.MAX_CALLS): the calls limit's top. */
    static final int SITE_CALLS_MAX = com.jabiz.quizbuks.content.ai.ClaudeWebResearch.MAX_CALLS;

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
        List<CheckProblem> problems = new ArrayList<>(liveModeProblems(web.environment(), web.production(), stripe.livemode()));
        if (!web.production()) {
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
            Map<String, String> values = values(connection);
            problems.addAll(problems(values));
            problems.addAll(aiFeeProblems(values));
        } catch (SQLException | RuntimeException e) {
            problems.add(CheckProblem.warning(CountryCatalog.CATEGORY, LOCATION,
                "the first launch's switches could not be read: " + e.getMessage()));
        }
        return List.copyOf(problems);
    }

    /**
     * Stripe's live mode on a system that is not production. A staging system proposes the first launch's values like
     * production does, so the warning there is that live money runs on a system that is not the real one; any other
     * system proposes the declared values (cash into Kudos on, Kudos rewards on), which live money must not meet.
     */
    static List<CheckProblem> liveModeProblems(String environment, boolean production, boolean livemode) {
        if (!livemode || production) {
            return List.of();
        }
        String effect = FirstLaunchSwitches.appliesTo(environment)
            ? "QB_SETUP proposes the first launch's values here as in production, but this is not the real system: "
                + "live keys belong to production only"
            : "QB_SETUP proposes the declared values of the money switches (cash into Kudos on, Kudos rewards on), "
                + "not the first launch's. Set JABIZ_WEB_ENVIRONMENT=production (operations.md)";
        return List.of(CheckProblem.warning(CountryCatalog.CATEGORY, "jabiz.web.environment",
            "Stripe is in live mode (quizbuks.stripe.livemode=true) but jabiz.web.environment is '" + environment
                + "', not production: " + effect));
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
                        + "no publication can offer a Kudos reward here until it is published (off at the first "
                        + "launch; not there counts as off in production). operations.md section 4: run QB_SETUP and "
                        + "have another administrator publish it"));
                }
            } else if ("true".equalsIgnoreCase(value.trim())) {
                problems.add(CheckProblem.warning(CountryCatalog.CATEGORY, "parameter " + key, "is on, but the first "
                    + "launch has it off (operations.md section 4): propose false as a controlled change"));
            }
        }
        return List.copyOf(problems);
    }

    /**
     * F-M9a-2b, the warnings of the AI fee (values in effect, by key): the website draft on while the AI fee is off (a
     * draft is then refused, plan question 6); and with both on, each of the website draft's reservation limits not
     * set or not usable (a whole number from 1, the calls at most {@value #SITE_CALLS_MAX}): the reservation is then the
     * job's worst case, and requests are refused more often than they need be (#692 re-review L-b; set them from the
     * P95 of real jobs, operations.md 7.56).
     */
    static List<CheckProblem> aiFeeProblems(Map<String, String> values) {
        List<CheckProblem> problems = new ArrayList<>();
        boolean fee = "true".equalsIgnoreCase(String.valueOf(values.get(QbParams.AI_FEE_ENABLED)).trim());
        boolean site = "true".equalsIgnoreCase(String.valueOf(values.get(QbParams.SITE_PROFILE_ENABLED)).trim());
        if (site && !fee) {
            problems.add(CheckProblem.warning(CountryCatalog.CATEGORY, "parameter " + QbParams.SITE_PROFILE_ENABLED,
                "is on while " + QbParams.AI_FEE_ENABLED + " is off: drafting from a website is charged to the AI "
                    + "credit, so every request is refused (QB_AI_UNAVAILABLE). Turn the AI fee on first, or the "
                    + "website draft off (operations.md 7.56)"));
        }
        if (site && fee) {
            for (String key : List.of(QbParams.AI_FEE_SITE_CALLS, QbParams.AI_FEE_SITE_PAGES,
                QbParams.AI_FEE_SITE_OUTPUT)) {
                String value = values.get(key);
                int max = QbParams.AI_FEE_SITE_CALLS.equals(key) ? SITE_CALLS_MAX : Integer.MAX_VALUE;
                if (value == null) {
                    problems.add(CheckProblem.warning(CountryCatalog.CATEGORY, "parameter " + key, "is not set: the "
                        + "website draft reserves the job's worst case, so requests are refused more often than they "
                        + "need be. Set it from the P95 of real jobs (operations.md 7.56)"));
                } else if (!usable(value, max)) {
                    problems.add(CheckProblem.warning(CountryCatalog.CATEGORY, "parameter " + key, "is '" + value
                        + "', not a whole number from 1" + (max == Integer.MAX_VALUE ? "" : " to " + max)
                        + ": it is ignored and the website draft reserves the job's worst case (operations.md 7.56)"));
                }
            }
        }
        return List.copyOf(problems);
    }

    private static boolean usable(String value, int max) {
        String text = value.strip();
        if (!text.matches("[0-9]{1,9}")) {
            return false;
        }
        long number = Long.parseLong(text);
        return number >= 1 && number <= max;
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
            List<String> keys = new ArrayList<>(MUST_BE_OFF);
            keys.addAll(AI_FEE_KEYS);
            statement.setArray(1, connection.createArrayOf("text", keys.toArray()));
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

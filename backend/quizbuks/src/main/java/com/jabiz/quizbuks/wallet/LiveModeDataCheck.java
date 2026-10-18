package com.jabiz.quizbuks.wallet;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.quizbuks.messaging.QbLegal;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.ledger.LedgerEntities;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
 * Going live on a database that still holds test mode's money (docs/quizbuks/plans/Q6-payments.md, review addition
 * R5, deploy/quizbuks/README.md "Going live"): with {@code quizbuks.stripe.livemode=true}, startup and
 * {@code platformCheck} fail (category {@code QUIZBUKS}) while any top-up was made in test mode or account 2110
 * (the sponsors' wallets) is not 0, since test Kudos would then pay real rewards. Clearing them is a deliberate
 * step: a fresh database, or {@code quizbuks.stripe.allow-live-with-test-data=true} set on purpose (only a warning
 * then).
 *
 * <p>Live mode also warns while no version of the takers' terms, the sponsors' terms or the privacy policy is in effect
 * (Q7b, D-Q7-4): a warning only, since the application must start for an administrator to publish them.
 *
 * <p>The check reads the database itself (JDBC, as the platform's SQL template precompile check does, with Flyway's
 * connection settings): it runs at startup and in {@code platformCheck}, never on the request path. Test mode skips
 * it without connecting. Table and column names come from the entities' metadata.
 */
@Component
public class LiveModeDataCheck implements PlatformCheck {

    public static final String ALLOW = "quizbuks.stripe.allow-live-with-test-data";
    private static final String TEST_SESSION = "cs_test_";

    private final StripeSettings settings;
    private final boolean allow;
    private final Environment environment;
    private final EntityDefinitionRegistry entities;
    private final Clock clock;

    public LiveModeDataCheck(StripeSettings settings, @Value("${" + ALLOW + ":false}") boolean allow,
        Environment environment, EntityDefinitionRegistry entities, Clock clock) {
        this.settings = settings;
        this.allow = allow;
        this.environment = environment;
        this.entities = entities;
        this.clock = clock;
    }

    /** What the database holds of test mode: top-ups made in test mode, and the sum of account 2110. */
    public record TestData(long testTopUps, BigDecimal sponsorWallets) {}

    @Override
    public List<CheckProblem> check() {
        if (!settings.livemode()) {
            return List.of();
        }
        String url = environment.getProperty("spring.flyway.url");
        if (url == null || url.isBlank()) {
            return List.of(CheckProblem.error(CountryCatalog.CATEGORY, ALLOW, "Live mode cannot check the database "
                + "for test mode's top-ups: spring.flyway.url is not set"));
        }
        try (Connection connection = DriverManager.getConnection(url, environment.getProperty("spring.flyway.user"),
            environment.getProperty("spring.flyway.password"))) {
            String schema = schema();
            if (schema != null) {
                connection.setSchema(schema);
            }
            connection.setReadOnly(true);
            List<CheckProblem> problems = new ArrayList<>();
            try {
                problems.addAll(problems(true, allow, read(connection)));
            } catch (SQLException | RuntimeException e) {
                problems.add(CheckProblem.error(CountryCatalog.CATEGORY, ALLOW, "Live mode cannot check the "
                    + "database for test mode's top-ups: " + e.getMessage()));
            }
            problems.addAll(legalCheck(() -> legalInEffect(connection)));
            return List.copyOf(problems);
        } catch (SQLException | RuntimeException e) {
            return List.of(CheckProblem.error(CountryCatalog.CATEGORY, ALLOW, "Live mode cannot check the database "
                + "for test mode's top-ups: " + e.getMessage()));
        }
    }

    /** Reads the kinds of legal text in effect. */
    @FunctionalInterface
    public interface LegalRead {
        Set<String> read() throws SQLException;
    }

    /**
     * The warnings of the legal texts in live mode, or, when they cannot be read, a warning saying so (review L4:
     * not the top-ups' message).
     */
    public static List<CheckProblem> legalCheck(LegalRead read) {
        try {
            return legalProblems(true, read.read());
        } catch (SQLException | RuntimeException e) {
            return List.of(CheckProblem.warning(CountryCatalog.CATEGORY, "legal", "Live mode cannot check which "
                + "legal texts are in effect: " + e.getMessage()));
        }
    }

    public static List<CheckProblem> problems(boolean livemode, boolean allow, TestData data) {
        if (!livemode || (data.testTopUps() == 0 && data.sponsorWallets().signum() == 0)) {
            return List.of();
        }
        String message = "Live mode (quizbuks.stripe.livemode=true) on a database with test mode's money: "
            + data.testTopUps() + " test-mode top-up(s), account 2110 (sponsor wallets) at "
            + data.sponsorWallets().toPlainString() + ". Start live on a fresh database";
        List<CheckProblem> problems = new ArrayList<>();
        problems.add(allow ? CheckProblem.warning(CountryCatalog.CATEGORY, ALLOW, message + " (allowed by " + ALLOW
            + "=true)") : CheckProblem.error(CountryCatalog.CATEGORY, ALLOW, message + ", or set " + ALLOW
            + "=true on purpose (deploy/quizbuks/README.md)"));
        return List.copyOf(problems);
    }

    /** The warnings of live mode without the terms or the privacy policy in effect (Q7b). */
    public static List<CheckProblem> legalProblems(boolean livemode, Set<String> inEffect) {
        if (!livemode) {
            return List.of();
        }
        return QbLegal.REQUIRED_FOR_LIVE.stream().filter(kind -> !inEffect.contains(kind))
            .map(kind -> CheckProblem.warning(CountryCatalog.CATEGORY, "legal:" + kind, "Live mode without a version "
                + "of " + kind + " in effect: publish it (QB_LEGAL_PUBLISH) before taking users "
                + "(docs/quizbuks/launch-checklist.md)"))
            .toList();
    }

    /** The kinds of legal text with a version in effect now (by the application's clock), withdrawn ones left out. */
    private Set<String> legalInEffect(Connection connection) throws SQLException {
        EntityDefinition version = entities.getOrThrow(QbLegal.VERSION);
        EntityDefinition withdrawal = entities.getOrThrow(QbLegal.WITHDRAWAL);
        String sql = "SELECT DISTINCT v." + column(version, "kind") + " FROM " + table(version) + " v WHERE v."
            + column(version, "effectiveAt") + " <= ? AND NOT EXISTS (SELECT 1 FROM " + table(withdrawal)
            + " w WHERE w." + column(withdrawal, "legalVersionId") + " = v." + column(version, "legalVersionId") + ")";
        Set<String> kinds = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    kinds.add(rows.getString(1));
                }
            }
        }
        return kinds;
    }

    private TestData read(Connection connection) throws SQLException {
        EntityDefinition topUp = entities.getOrThrow(QbTopUps.TOPUP);
        EntityDefinition entry = entities.getOrThrow(LedgerEntities.ENTRY);
        EntityDefinition account = entities.getOrThrow(LedgerEntities.ACCOUNT);
        // A top-up is test mode's when Stripe said so, or when its session is a test one (opened, never paid).
        String topUps = "SELECT count(*) FROM " + table(topUp) + " WHERE " + column(topUp, "livemode") + " = false OR "
            + "left(" + column(topUp, "checkoutSessionId") + ", " + TEST_SESSION.length() + ") = ?";
        String wallets = "SELECT coalesce(sum(CASE WHEN e." + column(entry, "direction") + " = 'CREDIT' THEN e."
            + column(entry, "amount") + " ELSE -e." + column(entry, "amount") + " END), 0) FROM " + table(entry)
            + " e WHERE e." + column(entry, "accountId") + " IN (SELECT a." + column(account, "accountId") + " FROM "
            + table(account) + " a WHERE a." + column(account, "accountCode") + " = ?)";
        long count;
        try (PreparedStatement statement = connection.prepareStatement(topUps)) {
            statement.setString(1, TEST_SESSION);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                count = rows.getLong(1);
            }
        }
        BigDecimal sum;
        try (PreparedStatement statement = connection.prepareStatement(wallets)) {
            statement.setString(1, QbLedger.SPONSOR_PREPAYMENTS);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                sum = rows.getBigDecimal(1);
            }
        }
        return new TestData(count, sum == null ? BigDecimal.ZERO : sum);
    }

    private static String table(EntityDefinition entity) {
        return SqlIdentifiers.require(entity.physicalTable);
    }

    private static String column(EntityDefinition entity, String field) {
        return SqlIdentifiers.require(entity.physicalColumn(field));
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

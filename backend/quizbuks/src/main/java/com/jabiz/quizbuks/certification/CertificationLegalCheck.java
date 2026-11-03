package com.jabiz.quizbuks.certification;

import com.jabiz.quizbuks.certification.Certifications.Certification;
import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Q86 (D-Q86-1): an understanding certification stands for a legal text's version that requires consent; when a
 * newer such version is in effect than the catalog's version of the certification, the holders passed a quiz about
 * an older text. A warning, not an error: publishing the text first and the quiz after is the normal order. Reads
 * the database itself (JDBC with Flyway's settings, as {@code LiveModeDataCheck}), at startup and in
 * {@code platformCheck}, never on the request path.
 */
@Component
public class CertificationLegalCheck implements PlatformCheck {

    private final Environment environment;
    private final Clock clock;

    public CertificationLegalCheck(Environment environment, Clock clock) {
        this.environment = environment;
        this.clock = clock;
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
                connection.setSchema(schema.split(",")[0].trim());
            }
            connection.setReadOnly(true);
            return problems(requiredVersions(connection));
        } catch (Exception e) {
            return List.of(CheckProblem.warning(CountryCatalog.CATEGORY, "certification:legal",
                "The understanding certifications cannot be checked against the legal texts: " + e.getMessage()));
        }
    }

    /** The highest version requiring consent in effect now, by kind of legal text. */
    private Map<String, Integer> requiredVersions(Connection connection) throws java.sql.SQLException {
        Map<String, Integer> versions = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT kind, max(legal_version_no) AS v"
            + " FROM qb_legal_version_version WHERE requires_consent AND effective_at <= ? GROUP BY kind")) {
            statement.setTimestamp(1, Timestamp.from(clock.instant()));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    versions.put(rows.getString("kind"), rows.getInt("v"));
                }
            }
        }
        return versions;
    }

    /** The warnings: each understanding certification whose version lags the text's version requiring consent. */
    public static List<CheckProblem> problems(Map<String, Integer> requiredVersions) {
        List<CheckProblem> problems = new ArrayList<>();
        for (Certification certification : Certifications.UNDERSTANDING) {
            for (String kind : Certifications.legalKinds(certification)) {
                Integer required = requiredVersions.get(kind);
                if (required != null && required > certification.version()) {
                    problems.add(CheckProblem.warning(CountryCatalog.CATEGORY, "certification:" + certification.key(),
                        "Certification " + certification.key() + " is at version " + certification.version()
                            + " while version " + required + " of " + kind + " requires consent again: raise the"
                            + " catalog's version and publish a new round"));
                }
            }
        }
        return List.copyOf(problems);
    }
}

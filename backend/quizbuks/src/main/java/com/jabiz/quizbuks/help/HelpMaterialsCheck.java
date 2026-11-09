package com.jabiz.quizbuks.help;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.quizbuks.setup.QbParams;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
 * The AI help's materials fit {@code qb.help.manual-max-bytes} (F-C10, startup check {@value #LOCATION}, an error): the
 * published FAQ, terms and manual of one entry and language go into every request of it, so materials above the limit
 * would make every question cost more than planned, or not fit at all. The materials are read by the
 * {@link HelpMaterials} beans (part b registers the manuals' and the snapshot's); without any, nothing is read and
 * nothing is reported. The limit is the parameter in effect, its declared value before the setup.
 *
 * <p>Reads the database itself (JDBC with Flyway's connection settings, as {@code SetupParamsCheck} does), never on
 * the request path; without a database address it is skipped.
 */
@Component
public class HelpMaterialsCheck implements PlatformCheck {

    static final String LOCATION = "QB_HELP_MATERIALS";

    /** One entry's and language's materials, as one request takes them. */
    public record Materials(String entry, String language, long bytes) {}

    /** Reads the help's published materials (part b): their size by entry and language. */
    public interface HelpMaterials {
        List<Materials> read(Connection connection) throws SQLException;
    }

    private final Environment environment;
    private final ObjectProvider<HelpMaterials> readers;

    public HelpMaterialsCheck(Environment environment, ObjectProvider<HelpMaterials> readers) {
        this.environment = environment;
        this.readers = readers;
    }

    @Override
    public List<CheckProblem> check() {
        List<HelpMaterials> all = readers.orderedStream().toList();
        String url = environment.getProperty("spring.flyway.url");
        if (all.isEmpty() || url == null || url.isBlank()) {
            return List.of();
        }
        long limit;
        List<Materials> materials = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, environment.getProperty("spring.flyway.user"),
            environment.getProperty("spring.flyway.password"))) {
            String schema = schema();
            if (schema != null) {
                connection.setSchema(schema);
            }
            connection.setReadOnly(true);
            limit = limit(connection);
            for (HelpMaterials reader : all) {
                materials.addAll(reader.read(connection));
            }
        } catch (SQLException | RuntimeException e) {
            return List.of(CheckProblem.warning(CountryCatalog.CATEGORY, LOCATION,
                "the AI help's materials could not be read: " + e.getMessage()));
        }
        return problems(materials, limit);
    }

    /** An error for each entry and language whose materials together are above {@code limit} bytes. */
    static List<CheckProblem> problems(List<Materials> materials, long limit) {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (Materials item : materials) {
            totals.merge(item.entry() + "/" + item.language(), item.bytes(), Long::sum);
        }
        List<CheckProblem> problems = new ArrayList<>();
        totals.forEach((key, bytes) -> {
            if (bytes > limit) {
                problems.add(CheckProblem.error(CountryCatalog.CATEGORY, LOCATION + " " + key, "the AI help's "
                    + "materials of " + key + " are " + bytes + " bytes, more than " + QbParams.HELP_MANUAL_MAX_BYTES
                    + " (" + limit + "): shorten the manual or the FAQ, or raise the parameter"));
            }
        });
        return List.copyOf(problems);
    }

    /** The parameter in effect (its latest version not deleted), else its declared value. */
    private static long limit(Connection connection) throws SQLException {
        String sql = "SELECT param_value, is_deleted FROM sys_param_version WHERE param_key = ? "
            + "ORDER BY effect_start_time DESC, version_no DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, QbParams.HELP_MANUAL_MAX_BYTES);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next() && !rows.getBoolean(2) && rows.getString(1) != null) {
                    try {
                        return new BigDecimal(rows.getString(1).strip()).longValue();
                    } catch (NumberFormatException e) {
                        // A value that is not a number: the declared one.
                    }
                }
            }
        }
        return Long.parseLong(QbParams.defaultOf(QbParams.HELP_MANUAL_MAX_BYTES));
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

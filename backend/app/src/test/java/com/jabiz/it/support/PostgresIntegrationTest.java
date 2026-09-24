package com.jabiz.it.support;

import com.jabiz.app.App;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Base class of integration tests against a real PostgreSQL.
 *
 * <p>Every test class gets a fresh application context bound to its own schema: Flyway migrates the
 * production scripts plus the test fixtures ({@code db/testmigration}) into it, and the schema is
 * dropped after the class. Tests inspect raw table contents through {@link #query} so that assertions
 * do not depend on the code under test.
 */
@SpringBootTest(classes = App.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PostgresIntegrationTest {

    public static final Instant START = Instant.parse("2026-01-31T09:00:00Z");

    protected static final PostgresTestDatabase DB = PostgresTestDatabase.get();

    /** Schema of the test class currently running; test classes run one after another. */
    private static String schema;

    @Autowired
    protected MutableClock clock;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String current = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        schema = current;
        String separator = DB.r2dbcUrl().contains("?") ? "&" : "?";
        registry.add("spring.r2dbc.url", () -> DB.r2dbcUrl() + separator + "schema=" + current);
        registry.add("spring.r2dbc.username", DB::username);
        registry.add("spring.r2dbc.password", DB::password);
        registry.add("spring.flyway.url", DB::jdbcUrl);
        registry.add("spring.flyway.user", DB::username);
        registry.add("spring.flyway.password", DB::password);
        registry.add("spring.flyway.schemas", () -> current);
        registry.add("spring.flyway.default-schema", () -> current);
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/testmigration");
    }

    @AfterAll
    static void dropSchema() {
        if (schema != null) {
            DB.dropSchema(schema);
            schema = null;
        }
    }

    @BeforeEach
    void resetClock() {
        clock.set(START);
    }

    /** Runs a query with positional parameters and returns rows keyed by lower-case column label. */
    protected static List<Map<String, Object>> query(String sql, Object... params) {
        try (Connection connection = DB.connect(schema);
             PreparedStatement statement = prepare(connection, sql, params);
             ResultSet rs = statement.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), rs.getObject(i));
                }
                rows.add(row);
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static int execute(String sql, Object... params) {
        try (Connection connection = DB.connect(schema);
             PreparedStatement statement = prepare(connection, sql, params)) {
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... params) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
        return statement;
    }
}

package com.jabiz.it.support;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The PostgreSQL server used by integration tests (docs/design/07-quality.md §7).
 *
 * <p>When {@code JABIZ_TEST_DB_URL} is set (a JDBC URL, with {@code JABIZ_TEST_DB_USER} and
 * {@code JABIZ_TEST_DB_PASSWORD}), tests connect to that server, which is how environments without
 * Docker run them. Otherwise one {@code postgres:16} container is started for the whole test JVM.
 * Either way each test class works in its own schema, so a shared local database is never polluted.
 */
public final class PostgresTestDatabase {

    private static PostgresTestDatabase instance;

    private final String jdbcUrl;
    private final String username;
    private final String password;

    private PostgresTestDatabase(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    public static synchronized PostgresTestDatabase get() {
        if (instance == null) {
            instance = create();
        }
        return instance;
    }

    private static PostgresTestDatabase create() {
        String url = System.getenv("JABIZ_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            return new PostgresTestDatabase(url,
                System.getenv().getOrDefault("JABIZ_TEST_DB_USER", "postgres"),
                System.getenv().getOrDefault("JABIZ_TEST_DB_PASSWORD", ""));
        }
        // Never stopped explicitly: Testcontainers' reaper removes it when the JVM exits.
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:16");
        container.start();
        return new PostgresTestDatabase(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    /** The same server and database as {@link #jdbcUrl()}, addressed through R2DBC. */
    public String r2dbcUrl() {
        return "r2dbc:" + jdbcUrl.substring("jdbc:".length());
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    /** Opens a JDBC connection whose search path is the given schema. */
    public Connection connect(String schema) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
        connection.setSchema(schema);
        return connection;
    }

    public void dropSchema(String schema) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not drop test schema " + schema, e);
        }
    }
}

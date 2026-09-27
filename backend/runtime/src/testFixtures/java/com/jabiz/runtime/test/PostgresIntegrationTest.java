package com.jabiz.runtime.test;

import com.jabiz.context.RequestContext;
import com.jabiz.runtime.context.RequestContexts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Base class of integration tests against a real PostgreSQL.
 *
 * <p>Every test class gets a fresh application context bound to its own schema: Flyway migrates the
 * platform scripts ({@code db/jabiz}), the application scripts ({@code db/migration}) and the test
 * fixtures ({@code db/testmigration}) into it, and the schema is dropped after the class. The application
 * class is found the Spring Boot way, by searching upwards from the test's package. Tests inspect raw table contents through {@link #query} so that assertions
 * do not depend on the code under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PostgresIntegrationTest {

    public static final Instant START = Instant.parse("2026-01-31T09:00:00Z");

    protected static final PostgresTestDatabase DB = PostgresTestDatabase.get();

    /** Request context of calls made directly by tests; platform writes refuse to run without one. */
    protected static final RequestContext TEST_REQUEST = new RequestContext(
        "it-user", null, Locale.ENGLISH, "it-request", Set.of(), Set.of());

    /** Schema of the test class currently running; test classes run one after another. */
    private static String schema;

    /** File storage directory of the test class currently running (docs/design/14-files.md section 6). */
    private static Path files;

    @Autowired
    protected MutableClock clock;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String current = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        schema = current;
        registry.add("spring.r2dbc.url", () -> DB.r2dbcUrl() + "?schema=" + current);
        registry.add("spring.r2dbc.username", DB::username);
        registry.add("spring.r2dbc.password", DB::password);
        registry.add("spring.flyway.url", DB::jdbcUrl);
        registry.add("spring.flyway.user", DB::username);
        registry.add("spring.flyway.password", DB::password);
        registry.add("spring.flyway.schemas", () -> current);
        registry.add("spring.flyway.default-schema", () -> current);
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/testmigration");
        try {
            files = Files.createTempDirectory("jabiz-files-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path root = files;
        registry.add("jabiz.files.local.root", root::toString);
    }

    /** The file storage directory of the running test class. */
    protected static Path filesRoot() {
        return files;
    }

    /** Schema of the running test class, for tests that open their own connections. */
    protected static String schema() {
        return schema;
    }

    @AfterAll
    static void dropSchema() {
        if (schema != null) {
            DB.dropSchema(schema);
            schema = null;
        }
        if (files != null) {
            deleteRecursively(files);
            files = null;
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void resetClock() {
        clock.set(START);
    }

    /** Runs a pipeline the way a request would, with {@link #TEST_REQUEST} in its Reactor context. */
    protected static <T> Mono<T> asTestRequest(Mono<T> pipeline) {
        return asRequest(TEST_REQUEST, pipeline);
    }

    /** Runs a pipeline the way a request would, with {@link #TEST_REQUEST} in its Reactor context. */
    protected static <T> Flux<T> asTestRequest(Flux<T> pipeline) {
        return pipeline.contextWrite(view -> RequestContexts.put(view, TEST_REQUEST));
    }

    /** Runs a pipeline the way a request of the given context would. */
    protected static <T> Mono<T> asRequest(RequestContext request, Mono<T> pipeline) {
        return pipeline.contextWrite(view -> RequestContexts.put(view, request));
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

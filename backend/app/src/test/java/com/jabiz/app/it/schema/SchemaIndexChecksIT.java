package com.jabiz.app.it.schema;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.schema.SchemaIndexChecks;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The startup check of INVALID indexes (ROADMAP 17zz18), and the application's own Flyway lock. In a class of its own: the invalid index stays in the test's
 * schema, and a healthy schema is what every other test of the platform asserts by starting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class SchemaIndexChecksIT extends PostgresIntegrationTest {

    @Autowired
    SchemaIndexChecks checks;

    @Autowired
    MeterRegistry meters;

    @Autowired
    Flyway flyway;

    double gauge() {
        return meters.get(PlatformObservations.SCHEMA_INVALID_INDEXES).gauge().value();
    }

    /**
     * The session-level lock is for the platform's run only (PlatformSchemaMigration copies the configuration): the
     * application's own Flyway, Spring's for {@code db/migration}, keeps Flyway's default transactional lock.
     */
    @Test
    void theApplicationsOwnFlywayKeepsTheTransactionalLock() throws ReflectiveOperationException {
        Class<?> extension = Class.forName("org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object settings = flyway.getConfiguration().getPluginRegister().getPlugin((Class) extension);
        assertThat(flyway.getConfiguration().getLocations()).extracting(Object::toString)
            .contains("classpath:db/migration");
        assertThat(extension.getMethod("isTransactionalLock").invoke(settings)).isEqualTo(true);
    }

    @Test
    void aFailedConcurrentIndexBuildIsAWarningCountedByTheGauge() throws SQLException {
        assertThat(checks.check()).isEmpty();
        assertThat(gauge()).isZero();

        try (Connection connection = DB.connect(schema()); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE it_si_dup (id integer, tag text)");
            statement.execute("INSERT INTO it_si_dup VALUES (1, 'a'), (2, 'a')");
            // Duplicate rows make the unique build fail after the index entry exists: PostgreSQL keeps it, INVALID.
            assertThatThrownBy(() -> statement.execute(
                "CREATE UNIQUE INDEX CONCURRENTLY it_si_dup_tag_uk ON it_si_dup (tag)"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23505"));
            statement.execute("CREATE INDEX CONCURRENTLY it_si_dup_id_idx ON it_si_dup (id)");
        }
        assertThat(checks.check()).singleElement().satisfies(problem -> {
            assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.WARNING);
            assertThat(problem.category()).isEqualTo("SCHEMA");
            assertThat(problem.location()).isEqualTo("Index it_si_dup_tag_uk");
            assertThat(problem.message()).contains("on table it_si_dup", "failed half way",
                "DROP INDEX CONCURRENTLY it_si_dup_tag_uk");
        });
        assertThat(gauge()).isEqualTo(1);

        // A session holding the lock a concurrent build holds on the table: probably being built.
        try (Connection holder = DB.connect(schema()); Statement hold = holder.createStatement()) {
            holder.setAutoCommit(false);
            hold.execute("LOCK TABLE it_si_dup IN SHARE UPDATE EXCLUSIVE MODE");
            assertThat(checks.check()).singleElement()
                .satisfies(problem -> assertThat(problem.message()).contains("probably being built"));
            // Not counted: a build running at the start is no failure (yet).
            assertThat(gauge()).isZero();
            holder.rollback();
        }
        assertThat(checks.check()).hasSize(1);
        assertThat(gauge()).isEqualTo(1);

        try (Connection connection = DB.connect(schema()); Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX CONCURRENTLY it_si_dup_tag_uk");
        }
        assertThat(checks.check()).isEmpty();
        assertThat(gauge()).isZero();
    }
}

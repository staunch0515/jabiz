package com.jabiz.app.it.schema;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.schema.SchemaIndexChecks;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The startup check of INVALID indexes (ROADMAP 17zz18). In a class of its own: the invalid index stays in the test's
 * schema, and a healthy schema is what every other test of the platform asserts by starting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class SchemaIndexChecksIT extends PostgresIntegrationTest {

    @Autowired
    SchemaIndexChecks checks;

    @Test
    void aFailedConcurrentIndexBuildIsAnErrorAndAHealthySchemaIsNot() throws SQLException {
        assertThat(checks.check()).isEmpty();

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
            assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.ERROR);
            assertThat(problem.category()).isEqualTo("SCHEMA");
            assertThat(problem.location()).isEqualTo("Index it_si_dup_tag_uk");
            assertThat(problem.message()).contains("on table it_si_dup", "DROP INDEX CONCURRENTLY it_si_dup_tag_uk");
        });

        try (Connection connection = DB.connect(schema()); Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX CONCURRENTLY it_si_dup_tag_uk");
        }
        assertThat(checks.check()).isEmpty();
    }
}

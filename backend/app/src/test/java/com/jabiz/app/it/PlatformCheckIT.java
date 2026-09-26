package com.jabiz.app.it;

import com.jabiz.app.App;
import com.jabiz.runtime.check.PlatformCheckFailedException;
import com.jabiz.runtime.test.PlatformCheckLauncher;
import com.jabiz.runtime.test.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ROADMAP phase 5, acceptance 1: {@code platformCheck} names the file and the problem of a misspelled field, an
 * incompatible result column and an undeclared parameter, and exits non-zero. The broken templates live in
 * {@code src/test/resources/broken-queries}, which only this test scans.
 */
class PlatformCheckIT {

    /** The integration test fixtures bring their own tables. */
    private static final String FIXTURE_MIGRATIONS =
        "--spring.flyway.locations=classpath:db/migration,classpath:db/testmigration";

    private record Outcome(int exitCode, List<String> lines) {}

    private static Outcome check(String... args) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        List<String> all = new ArrayList<>(List.of(FIXTURE_MIGRATIONS));
        all.addAll(List.of(args));
        int code = PlatformCheckLauncher.run(App.class, all,
            new PrintStream(buffer, true, StandardCharsets.UTF_8));
        String output = buffer.toString(StandardCharsets.UTF_8);
        System.out.print(output); // kept in the test report
        return new Outcome(code, output.lines().toList());
    }

    @Test
    void theApplicationPasses() {
        Outcome outcome = check();

        assertThat(outcome.lines()).last().isEqualTo("platformCheck: 0 error(s), 0 warning(s)");
        assertThat(outcome.exitCode()).isZero();
    }

    @Test
    void brokenTemplatesAreReportedWithFileAndProblem() {
        Outcome outcome = check("--jabiz.sql-templates.locations=classpath*:broken-queries/**/*.sql");

        assertThat(outcome.exitCode()).isNotZero();
        assertThat(outcome.lines())
            .contains("SQL_TEMPLATE | broken-queries/wrong_field.sql:10 | unknown field WaybillTracking.freigtCharge")
            .anySatisfy(line -> assertThat(line).startsWith("SQL_TEMPLATE | broken-queries/wrong_type.sql | ")
                .contains("result shippedTime").contains("numeric").contains("Instant"))
            .contains("SQL_TEMPLATE | broken-queries/undeclared_param.sql:13 | parameter :maxFreight is not declared")
            .anySatisfy(line -> assertThat(line).startsWith("SQL_TEMPLATE | broken-queries/forbidden_forms.sql:11 | ")
                .contains("IN (:statuses) is not allowed"))
            .anySatisfy(line -> assertThat(line).startsWith("SQL_TEMPLATE | broken-queries/forbidden_forms.sql:12 | ")
                .contains("LIMIT"))
            .anySatisfy(line -> assertThat(line).startsWith("SQL_TEMPLATE | broken-queries/forbidden_forms.sql | ")
                .contains("declares no permissions"))
            .anySatisfy(line -> assertThat(line).startsWith("SQL_TEMPLATE | broken-queries/bad_header.sql:1 | ")
                .contains("colour"))
            // A database error is located on the template line it refers to.
            .anySatisfy(line -> assertThat(line).startsWith("SQL_TEMPLATE | broken-queries/syntax_error.sql:13 | ")
                .contains("no_such_column"));
    }

    /** ROADMAP phase 5, requirement 7: application startup runs the same checks and refuses to start. */
    @Test
    void startupRunsTheSameChecks() {
        String schema = PlatformCheckLauncher.newSchema();
        List<String> args = new ArrayList<>(PlatformCheckLauncher.databaseArgs(schema));
        args.add(FIXTURE_MIGRATIONS);
        args.add("--jabiz.sql-templates.locations=classpath*:broken-queries/**/*.sql");
        try {
            assertThatThrownBy(() -> new SpringApplicationBuilder(App.class).web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new)).close())
                .isInstanceOf(PlatformCheckFailedException.class)
                .hasMessageContaining("SQL_TEMPLATE | broken-queries/wrong_field.sql:10 | unknown field "
                    + "WaybillTracking.freigtCharge")
                .hasMessageContaining("SQL_TEMPLATE | broken-queries/undeclared_param.sql:13 | parameter :maxFreight "
                    + "is not declared")
                .hasMessageContaining("broken-queries/wrong_type.sql | result shippedTime");
        } finally {
            PostgresTestDatabase.get().dropSchema(schema);
        }
    }
}

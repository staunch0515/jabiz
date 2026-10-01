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

    /** ROADMAP phase 13c: every problem of public datasets and public templates, in one run. */
    @Test
    void brokenPublicDatasetsAndTemplatesAreAllReported() {
        Outcome outcome = check("--jabiz.sql-templates.locations=classpath*:broken-public/**/*.sql",
            "--spring.profiles.active=broken-public");

        assertThat(outcome.exitCode()).isNotZero();
        assertThat(outcome.lines())
            .contains("PUBLIC | Dataset urn:jabiz:dataset:it:broken:context | scope field title is taken from the"
                + " request context, which anonymous visitors do not have; public datasets allow fixed scope values"
                + " only")
            .anySatisfy(line -> assertThat(line).startsWith("PUBLIC | Dataset urn:jabiz:dataset:it:broken:unscoped"
                + " | has no scope"))
            .contains("PUBLIC | Dataset urn:jabiz:dataset:it:broken:unscoped | public field secretStuff does not"
                + " exist on ItAttachment")
            .contains("PUBLIC | Dataset urn:jabiz:dataset:it:broken:large | maxQueryBatchSize 500 exceeds"
                + " jabiz.public.max-limit 100")
            .contains("PUBLIC | broken-public/default_dataset.sql | query it.broken.default_dataset: entity"
                + " ItAttachment must be read through a public dataset named in datasets; default datasets are never"
                + " public")
            .contains("PUBLIC | broken-public/outside_whitelist.sql:9 | query it.broken.outside_whitelist: field"
                + " ItAttachment.title is not in the whitelist of its public dataset")
            .contains("PUBLIC | broken-public/outside_whitelist.sql | query it.broken.outside_whitelist: result title"
                + " comes from ItAttachment.title, which is not in the whitelist of its public dataset")
            .contains("PUBLIC | broken-public/public_and_permissions.sql | query it.broken.public_and_permissions: a"
                + " public template (access: public) declares no permissions")
            .contains("PUBLIC | broken-public/public_and_permissions.sql | query it.broken.public_and_permissions:"
                + " timeoutMs (3000) exceeds jabiz.public.max-timeout (2000 ms)")
            .contains("PUBLIC | broken-public/private_cache.sql | query it.broken.private_cache: cacheSeconds applies"
                + " to public templates (access: public) only");
    }

    /** ROADMAP phase 14j-1: every problem of document layouts, in one run. */
    @Test
    void brokenDocumentLayoutsAreAllReported() {
        Outcome outcome = check("--spring.profiles.active=broken-documents", "--jabiz.documents.page-size=B5");

        assertThat(outcome.exitCode()).isNotZero();
        assertThat(outcome.lines())
            .contains("DOCUMENTS | it.broken_document | declared twice")
            .contains("DOCUMENTS | jabiz.documents.page-size | must be A4 or LETTER, was B5")
            .contains("DOCUMENTS | it.broken_document | commerce.order_document_header | is read both for one row and"
                + " as a table")
            .contains("DOCUMENTS | it.broken_document | commerce.order_document_header | has no result column colour")
            .contains("DOCUMENTS | it.broken_document | it.no_such_template | no such template")
            .contains("DOCUMENTS | it.broken_document | unknown subject entity Nowhere")
            .contains("MESSAGES | document.it.broken_document [en] | no text for document layout it.broken_document")
            .contains("MESSAGES | document.it.broken_document.missing [zh] | no text for document layout"
                + " it.broken_document");
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

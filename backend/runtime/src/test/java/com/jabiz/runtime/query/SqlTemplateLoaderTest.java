package com.jabiz.runtime.query;

import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.UrlResource;
import org.springframework.mock.env.MockEnvironment;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTemplateLoaderTest {

    private final SqlTemplateLoader loader = new SqlTemplateLoader(new MockEnvironment());

    private record Result(List<AdvancedQueryDefinition> queries, List<String> problems) {}

    private Result load(String content) {
        List<AdvancedQueryDefinition> queries = new ArrayList<>();
        List<CheckProblem> problems = new ArrayList<>();
        loader.load("queries/x.sql", content, queries, problems);
        return new Result(queries, problems.stream().map(CheckProblem::format).toList());
    }

    @Test
    void aValidFileCompiles() {
        Result result = load("""
            /*---
            id: x.valid
            entities: [Order]
            params:
              min: { kind: { type: monetary, currency: JPY, scale: 0 }, required: true }
            results:
              amount: { from: Order.amount }
            permissions: [x.read]
            ---*/
            SELECT o.{{Order.amount}} AS amount FROM {{Order}} o WHERE o.{{Order.amount}} > :min
            """);

        assertThat(result.problems()).isEmpty();
        AdvancedQueryDefinition query = result.queries().getFirst();
        assertThat(query.queryId()).isEqualTo("x.valid");
        assertThat(query.source().path()).isEqualTo("queries/x.sql");
        assertThat(query.source().firstLine()).isEqualTo(9);
        assertThat(query.parameters().getFirst().required()).isTrue();
    }

    @Test
    void aMissingHeaderIsReported() {
        assertThat(load("SELECT 1").problems())
            .containsExactly("SQL_TEMPLATE | queries/x.sql:1 | file must start with a header comment /*--- ... ---*/");
    }

    @Test
    void invalidYamlIsReportedAtItsLine() {
        assertThat(load("/*---\nid: x\nentities: [Order\n---*/\nSELECT 1").problems()).singleElement()
            .asString().startsWith("SQL_TEMPLATE | queries/x.sql:").contains("header is not valid YAML");
        assertThat(load("/*---\n- a\n---*/\nSELECT 1").problems())
            .containsExactly("SQL_TEMPLATE | queries/x.sql:1 | header must be a YAML mapping");
    }

    @Test
    void theJsonSchemaIsEnforced() {
        List<String> problems = load("""
            /*---
            id: "bad id"
            entities: []
            params:
              p: { list: true }
            results:
              r: { from: notARef }
            timeoutMs: 0
            extra: 1
            ---*/
            SELECT 1
            """).problems();

        assertThat(problems).isNotEmpty().allSatisfy(p -> assertThat(p).startsWith("SQL_TEMPLATE | queries/x.sql:1 | header "));
        assertThat(String.join("\n", problems)).contains("/id", "/entities", "/params/p", "/results/r", "/timeoutMs",
            "extra");
    }

    @Test
    void headerProblemsTheSchemaCannotSeeAreReported() {
        assertThat(load("""
            /*---
            id: x.kind
            entities: [Order]
            results:
              r: { kind: { type: monetary } }
            ---*/
            SELECT 1 AS r
            """).problems()).containsExactly("SQL_TEMPLATE | queries/x.sql:1 | result r: kind needs 'currency'");
    }

    @Test
    void pathsStartAtThePatternRoot() throws MalformedURLException {
        assertThat(SqlTemplateLoader.relativePath("classpath*:queries/**/*.sql",
            new UrlResource("file:/app/build/resources/main/queries/sales/orders.sql")))
            .isEqualTo("queries/sales/orders.sql");
        assertThat(SqlTemplateLoader.relativePath("classpath*:broken-queries/**/*.sql",
            new UrlResource("jar:file:/app.jar!/broken-queries/a.sql"))).isEqualTo("broken-queries/a.sql");
        assertThat(SqlTemplateLoader.relativePath("classpath:*.sql",
            new UrlResource("file:/x/top.sql"))).isEqualTo("top.sql");
        assertThat(SqlTemplateLoader.relativePath("classpath*:queries/**/*.sql", new ByteArrayResource(new byte[0])))
            .isNotBlank();
    }

    @Test
    void theDefaultLocationIsScanned() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(SqlTemplateLoader.LOCATIONS_PROPERTY, "classpath*:no-such-dir/**/*.sql, ");

        SqlTemplateLoader.Loaded loaded = new SqlTemplateLoader(environment).load();

        assertThat(loaded.queries()).isEmpty();
        assertThat(loaded.problems()).isEmpty();
    }
}

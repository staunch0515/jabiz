package com.jabiz.query.template;

import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.TemporalRole;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.QueryParameter;
import com.jabiz.query.custom.TemplateSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlTemplateFileTest {

    private static final String BODY = "\nSELECT o.{{Order.amount}} AS amount\nFROM {{Order}} o\n"
        + "WHERE o.{{Order.note}} = ANY(:notes) AND o.{{Order.amount}} >= :min\n";
    private static final String FILE = "﻿\n/*---\nid: sales.orders\n...\n---*/" + BODY;

    @Test
    void splitSeparatesHeaderAndBodyAndKeepsLines() {
        SqlTemplateFile.Parts parts = SqlTemplateFile.split("queries/a.sql", FILE);

        assertThat(parts.header()).isEqualTo("\nid: sales.orders\n...\n");
        assertThat(parts.headerLine()).isEqualTo(2);
        assertThat(parts.body()).isEqualTo(BODY);
        assertThat(parts.bodyLine()).isEqualTo(5); // the line of "---*/", where the SQL text begins
    }

    @Test
    void aFileWithoutAClosedHeaderIsRejected() {
        assertThatThrownBy(() -> SqlTemplateFile.split("a.sql", "SELECT 1"))
            .isInstanceOf(SqlTemplateException.class).hasMessageContaining("must start with a header");
        assertThatThrownBy(() -> SqlTemplateFile.split("a.sql", "/*--- id: x"))
            .isInstanceOf(SqlTemplateException.class).hasMessageContaining("not closed");
    }

    /** ROADMAP phase 5, requirement 1: the file and the Java DSL compile to the same definition. */
    @Test
    void theHeaderCompilesToWhatTheDslBuilds() {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("id", "sales.orders");
        header.put("description", "orders");
        header.put("entities", List.of("Order"));
        header.put("datasets", Map.of("Order", "urn:test:dataset:Order"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("notes", Map.of("like", "Order.note", "list", true, "required", true));
        params.put("min", Map.of("kind", Map.of("type", "monetary", "currency", "JPY", "scale", 0),
            "default", 0, "description", "lower bound"));
        header.put("params", params);
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("amount", Map.of("from", "Order.amount"));
        results.put("seen", Map.of("kind", Map.of("type", "temporal", "role", "EVENT_TIME")));
        results.put("typed", Map.of("from", "Order.note", "kind", Map.of("type", "text", "maxLength", 5)));
        header.put("results", results);
        header.put("list", Map.of("filters", List.of("amount"), "sorts", List.of("amount"), "key", List.of("amount"),
            "defaultSort", Map.of("field", "amount", "asc", false)));
        header.put("permissions", List.of("sales.read"));
        header.put("timeoutMs", 1500);

        AdvancedQueryDefinition fromFile = SqlTemplateFile.compile("queries/sales/orders.sql", header,
            new SqlTemplateFile.Parts("", 1, BODY, 12));

        AdvancedQueryDefinition fromDsl = AdvancedQueryDefinition.define("sales.orders", q -> q
            .description("orders")
            .fromEntities("Order")
            .dataset("Order", "urn:test:dataset:Order")
            .parameterLike("notes", "Order", "note", true, true)
            .parameter(new QueryParameter("min", new SemanticKind.Monetary("JPY", 0), false, 0, "lower bound", false,
                null, null))
            .returnsFrom("amount", "Order", "amount")
            .returns("seen", new SemanticKind.Temporal(TemporalRole.EVENT_TIME))
            .returns("typed", new SemanticKind.Text(5, false), "Order", "note")
            .list(l -> l.filters("amount").sorts("amount").key("amount").defaultSort("amount", false))
            .permissions("sales.read")
            .timeout(Duration.ofMillis(1500))
            .source(new TemplateSource("queries/sales/orders.sql", 12))
            .sqlTemplate(BODY));

        assertThat(fromFile).isEqualTo(fromDsl);
    }

    @Test
    void headerProblemsAreAllReported() {
        Map<String, Object> header = Map.of(
            "id", "bad",
            "entities", List.of("Order"),
            "params", Map.of("p", Map.of("like", "nodot"), "k", Map.of("kind", Map.of("type", "weird"))),
            "results", Map.of("r", Map.of("kind", Map.of("type", "monetary"))));

        assertThatThrownBy(() -> SqlTemplateFile.compile("a.sql", header, new SqlTemplateFile.Parts("", 1, BODY, 3)))
            .isInstanceOfSatisfying(SqlTemplateException.class, e -> assertThat(e.problems())
                .extracting(TemplateProblem::message).containsExactlyInAnyOrder(
                    "parameter p: 'nodot' is not of the form Entity.field",
                    "parameter k: unknown kind type 'weird'",
                    "result r: kind needs 'currency'"));
        assertThatThrownBy(() -> SqlTemplateFile.compile("a.sql", Map.of("entities", List.of()),
            new SqlTemplateFile.Parts("", 1, BODY, 3))).hasMessageContaining("needs an id");
        assertThatThrownBy(() -> SqlTemplateFile.compile("a.sql", Map.of("id", "x"),
            new SqlTemplateFile.Parts("", 1, BODY, 3))).hasMessageContaining("declares no result fields");
    }

    @Test
    void locationsCountLinesFromTheStartOfTheSql() {
        TemplateSource source = new TemplateSource("queries/a.sql", 6);

        assertThat(source.locate(BODY, 0)).isEqualTo("queries/a.sql:6");
        assertThat(source.locate(BODY, BODY.indexOf("WHERE"))).isEqualTo("queries/a.sql:9");
        assertThatThrownBy(() -> new TemplateSource(" ", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemplateSource("a", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}

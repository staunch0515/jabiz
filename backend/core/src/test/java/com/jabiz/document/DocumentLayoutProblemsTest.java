package com.jabiz.document;

import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.TemporalRole;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.QueryParameter;
import com.jabiz.query.template.TemplateSchemas;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentLayoutProblemsTest {

    private static AdvancedQueryDefinition template(String id, SemanticKind param, String... columns) {
        return AdvancedQueryDefinition.define(id, q -> {
            q.fromEntities("Order").parameter(QueryParameter.of("orderId", param, true)).sqlTemplate("SELECT 1")
                .permissions("p");
            for (String column : columns) {
                q.returns(column, new SemanticKind.Text(50, false));
            }
        });
    }

    private static final DocumentLayout LAYOUT = DocumentLayout.define("shop.order", d -> d
        .permissions("shop.read")
        .subject("Order", "orderId")
        .number("shop.header", "orderNo")
        .facts("shop.header", "orderNo", "customer")
        .table("shop.lines", "sku", "name"));

    @Test
    void aLayoutMatchingItsTemplatesHasNoProblems() {
        Map<String, AdvancedQueryDefinition> templates = Map.of(
            "shop.header", template("shop.header", new SemanticKind.SemanticIdentity("urn:x"), "orderNo", "customer"),
            // A reference to the order and the order's identity take the same values.
            "shop.lines", template("shop.lines", new SemanticKind.Reference("Order"), "sku", "name"));
        assertThat(DocumentLayoutProblems.of(LAYOUT, id -> Optional.ofNullable(templates.get(id)))).isEmpty();
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        DocumentLayout layout = DocumentLayout.define("shop.order", d -> d
            .subject("Order", "missing")
            .facts("shop.header", "orderNo", "nope")
            .table("shop.header", "orderNo")
            .table("shop.lines", "sku")
            .totals("shop.public", "x")
            .text("terms", "shop.sliced", "x")
            .note("none"));
        AdvancedQueryDefinition publicTemplate = AdvancedQueryDefinition.define("shop.public", q -> q
            .fromEntities("Order").returns("x", new SemanticKind.Bool()).sqlTemplate("SELECT 1").publicAccess());
        AdvancedQueryDefinition sliced = AdvancedQueryDefinition.define("shop.sliced", q -> q.fromEntities("Order")
            .parameter(QueryParameter.of("at", new SemanticKind.Temporal(TemporalRole.EVENT_TIME), false))
            .returns("x", new SemanticKind.Bool()).sqlTemplate("SELECT 1").permissions("p").timeSlice("at", null));
        Map<String, AdvancedQueryDefinition> templates = Map.of(
            "shop.header", template("shop.header", new SemanticKind.Text(10, false), "orderNo"),
            "shop.public", publicTemplate, "shop.sliced", sliced);
        List<DocumentLayoutProblems.Problem> problems = DocumentLayoutProblems.of(layout,
            id -> Optional.ofNullable(templates.get(id)));
        assertThat(problems).extracting(p -> p.location() + ": " + p.message()).containsExactlyInAnyOrder(
            "shop.order: declares no permissions",
            "shop.order | shop.header: is read both for one row and as a table",
            "shop.order | shop.header: has no result column nope",
            "shop.order | shop.lines: no such template",
            "shop.order | shop.public: is a public template; documents read with the issuer's permissions",
            "shop.order | shop.sliced: declares timeSlice; a document is read at the point in time it is issued at,"
                + " the same for all its templates",
            "shop.order: no template has the subject parameter missing");
    }

    @Test
    void theRecipientsColumnMustBeText() {
        DocumentLayout layout = DocumentLayout.define("shop.order", d -> d.permissions("shop.read")
            .recipients("shop.header", "customer").facts("shop.header", "orderNo"));
        AdvancedQueryDefinition header = AdvancedQueryDefinition.define("shop.header", q -> q.fromEntities("Order")
            .returns("orderNo", new SemanticKind.Text(10, false)).returns("customer", new SemanticKind.Bool())
            .sqlTemplate("SELECT 1").permissions("p"));
        assertThat(DocumentLayoutProblems.of(layout, id -> Optional.of(header)))
            .extracting(p -> p.location() + ": " + p.message())
            .containsExactly("shop.order | shop.header: recipients column customer is not text");
        AdvancedQueryDefinition text = AdvancedQueryDefinition.define("shop.header", q -> q.fromEntities("Order")
            .returns("orderNo", new SemanticKind.Text(10, false)).returns("customer", new SemanticKind.Text(320, false))
            .sqlTemplate("SELECT 1").permissions("p"));
        assertThat(DocumentLayoutProblems.of(layout, id -> Optional.of(text))).isEmpty();
    }

    @Test
    void aParameterTheTemplatesTakeDifferentlyIsReported() {
        Map<String, AdvancedQueryDefinition> templates = Map.of(
            "shop.header", template("shop.header", new SemanticKind.Text(10, false), "orderNo", "customer"),
            "shop.lines", template("shop.lines", new SemanticKind.Numeric(9, 0), "sku", "name"));
        assertThat(DocumentLayoutProblems.of(LAYOUT, id -> Optional.ofNullable(templates.get(id))))
            .extracting(DocumentLayoutProblems.Problem::message)
            .containsExactly("parameter orderId differs from the one of shop.header");
    }

    @Test
    void theParametersOfSeveralTemplatesFormOneSchema() {
        AdvancedQueryDefinition a = AdvancedQueryDefinition.define("a.a", q -> q.fromEntities("Order")
            .parameter(QueryParameter.of("orderId", new SemanticKind.Text(10, false), true))
            .returns("x", new SemanticKind.Bool()).sqlTemplate("SELECT 1"));
        AdvancedQueryDefinition b = AdvancedQueryDefinition.define("a.b", q -> q.fromEntities("Order")
            .parameter(QueryParameter.of("orderId", new SemanticKind.Text(10, false), false))
            .parameter(QueryParameter.listOf("skus", new SemanticKind.Text(10, false), false))
            .returns("x", new SemanticKind.Bool()).sqlTemplate("SELECT 1"));
        Map<String, Object> schema = TemplateSchemas.params(List.of(b, a));
        assertThat(schema).containsEntry("required", List.of("orderId")).containsEntry("additionalProperties", false);
        assertThat(schema.get("properties").toString()).startsWith("{orderId=").contains("skus=");
        assertThat(TemplateSchemas.values(QueryParameter.listOf("skus", new SemanticKind.Text(10, false), false)))
            .containsEntry("type", "array");
    }

    @Test
    void everyKindHasItsSchemaForm() {
        assertThat(TemplateSchemas.values(QueryParameter.of("a", new SemanticKind.Monetary("USD", 2), false)))
            .containsEntry("format", "decimal");
        assertThat(TemplateSchemas.values(QueryParameter.of("a", new SemanticKind.Temporal(TemporalRole.EVENT_TIME),
            false))).containsEntry("format", "date-time");
        assertThat(TemplateSchemas.values(QueryParameter.of("a", new SemanticKind.Date(), false)))
            .containsEntry("format", "date");
        assertThat(TemplateSchemas.values(QueryParameter.of("a", new SemanticKind.Code("urn:d", List.of("A", "B")),
            false))).containsEntry("enum", List.of("A", "B"));
        assertThat(TemplateSchemas.values(QueryParameter.of("a", new SemanticKind.Code("urn:d", List.of()), false)))
            .doesNotContainKey("enum");
        assertThat(TemplateSchemas.values(QueryParameter.of("a", new SemanticKind.Text(null, false), false)))
            .doesNotContainKey("maxLength");
        assertThat(TemplateSchemas.values(QueryParameter.of("a", new SemanticKind.Bool(), false)))
            .containsEntry("type", "boolean");
        assertThat(TemplateSchemas.values(QueryParameter.of("a", new SemanticKind.Version(), false)))
            .containsEntry("type", "integer");
        assertThat(TemplateSchemas.values(QueryParameter.of("a", new SemanticKind.Custom("geo.h3", Map.of()), false)))
            .isEmpty();
        assertThat(TemplateSchemas.values(QueryParameter.of("a", new SemanticKind.None(), false))).isEmpty();
        assertThat(TemplateSchemas.values(QueryParameter.of("a", null, false))).isEmpty();
        assertThat(TemplateSchemas.params(AdvancedQueryDefinition.define("a.c", q -> q.fromEntities("Order")
            .parameter(QueryParameter.withDefault("n", new SemanticKind.Numeric(9, 0), 5))
            .returns("x", new SemanticKind.Bool()).sqlTemplate("SELECT 1"))).get("properties").toString())
            .contains("default=5");
    }
}

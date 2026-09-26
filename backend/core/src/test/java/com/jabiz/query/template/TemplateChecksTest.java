package com.jabiz.query.template;

import com.jabiz.entity.SemanticKind;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.QueryParameter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static com.jabiz.query.template.TemplateFixtures.ENTITIES;
import static org.assertj.core.api.Assertions.assertThat;

class TemplateChecksTest {

    private static AdvancedQueryDefinition query(Consumer<AdvancedQueryDefinition.Builder> body) {
        return AdvancedQueryDefinition.define("q", q -> {
            q.fromEntities("Order", "Price").permissions("p");
            body.accept(q);
        });
    }

    private static List<String> problems(AdvancedQueryDefinition query) {
        TemplateChecks.Resolved resolved = TemplateChecks.resolve(query, ENTITIES);
        List<TemplateProblem> all = new java.util.ArrayList<>(resolved.problems());
        all.addAll(TemplateChecks.check(resolved.query(), ENTITIES));
        return all.stream().map(p -> (p.warning() ? "W " : "") + p.message()).toList();
    }

    @Test
    void kindsAreInheritedFromTheSourceFields() {
        AdvancedQueryDefinition query = query(q -> q
            .parameterLike("ids", "Price", "priceId", false, true)
            .returnsFrom("amount", "Order", "amount")
            .returns("note", new SemanticKind.Text(10, false), "Order", "note")
            .sqlTemplate("SELECT o.{{Order.amount}} AS amount, o.{{Order.note}} AS note FROM {{Order}} o, {{Price}} p"
                + " WHERE p.{{Price.priceId}} = ANY(:ids)"));

        TemplateChecks.Resolved resolved = TemplateChecks.resolve(query, ENTITIES);

        assertThat(resolved.problems()).isEmpty();
        assertThat(resolved.query().parameters().getFirst().kind())
            .isEqualTo(new SemanticKind.SemanticIdentity("urn:test:price"));
        assertThat(resolved.query().result("AMOUNT").orElseThrow().kind()).isEqualTo(new SemanticKind.Monetary("JPY", 0));
        // A declared kind wins over the source's.
        assertThat(resolved.query().result("note").orElseThrow().kind()).isEqualTo(new SemanticKind.Text(10, false));
        assertThat(TemplateChecks.check(resolved.query(), ENTITIES)).isEmpty();
    }

    @Test
    void unresolvableSourcesAreReported() {
        assertThat(problems(query(q -> q
            .parameterLike("a", "Ghost", "x", false, false)
            .parameterLike("b", "Order", "nope", false, false)
            .parameter(QueryParameter.like("c", "Other", "x", false, false))
            .returnsFrom("r", "Order", "missing")
            .sqlTemplate("SELECT 1 AS r FROM {{Order}} o WHERE :a = :b AND :c IS NULL"))))
            .contains("parameter a refers to Ghost.x, but Ghost is not in entities",
                "parameter b refers to unknown field Order.nope",
                "result r refers to unknown field Order.missing");
    }

    @Test
    void placeholdersEntitiesAndDatasetsAreChecked() {
        assertThat(problems(AdvancedQueryDefinition.define("q", q -> q
            .fromEntities("Order", "Ghost")
            .dataset("Price", "urn:x")
            .permissions("p")
            .returns("x", new SemanticKind.Bool())
            .sqlTemplate("SELECT {{Order.nope}}, {{Price.amount}} AS x FROM {{Order}} o"))))
            .contains("unknown entity Ghost in entities",
                "datasets names Price, which is not in entities",
                "unknown field Order.nope",
                "entity Price is not declared in entities");
    }

    @Test
    void parametersMustBeDeclaredUsedAndNotReserved() {
        assertThat(problems(query(q -> q
            .parameter("__x", new SemanticKind.Bool(), false)
            .parameter("unused", new SemanticKind.Bool(), false)
            .parameter("dup", new SemanticKind.Bool(), false)
            .parameter("dup", new SemanticKind.Bool(), false)
            .parameter(new QueryParameter("nokind", null, false, null, "", false, null, null))
            .returns("x", new SemanticKind.Bool())
            .sqlTemplate("SELECT :undeclared AS x, :__x, :dup, :nokind FROM {{Order}} o"))))
            .contains("parameter :undeclared is not declared",
                "parameter __x uses a reserved prefix (scope_, __)",
                "parameter unused is declared but not used",
                "parameter dup is declared twice",
                "parameter nokind has no kind");
    }

    /** Decision D7. */
    @Test
    void listParametersGoThroughAnyOrAll() {
        AdvancedQueryDefinition good = query(q -> q
            .listParameter("xs", new SemanticKind.Text(null, false), false)
            .listParameter("ys", new SemanticKind.Text(null, false), false)
            .returns("x", new SemanticKind.Bool())
            .sqlTemplate("SELECT true AS x FROM {{Order}} o WHERE (CAST(:xs AS text[]) IS NULL OR o.{{Order.note}}"
                + " = ANY (:xs)) AND o.{{Order.note}} <> ALL(:ys)"));
        assertThat(problems(good)).isEmpty();

        assertThat(problems(query(q -> q
            .listParameter("xs", new SemanticKind.Text(null, false), false)
            .returns("x", new SemanticKind.Bool())
            .sqlTemplate("SELECT true AS x FROM {{Order}} o WHERE o.{{Order.note}} IN (:xs)"))))
            .containsExactlyInAnyOrder("list parameter :xs must be used as = ANY(:xs) or <> ALL(:xs)",
                "IN (:xs) is not allowed; write = ANY(:xs) with a list parameter (decision D7)");
    }

    @Test
    void theOuterResultIsNotLimited() {
        assertThat(problems(query(q -> q.returns("x", new SemanticKind.Bool())
            .sqlTemplate("SELECT true AS x FROM (SELECT 1 FROM {{Order}} o LIMIT 1) s OFFSET 3 FETCH FIRST 1 ROWS ONLY"))))
            .filteredOn(p -> p.contains("must not limit")).hasSize(2);
    }

    @Test
    void resultsAndTheListWhitelistAreChecked() {
        assertThat(problems(query(q -> q
            .returns("a b", new SemanticKind.Bool())
            .returns("dup", new SemanticKind.Bool())
            .returns("DUP", new SemanticKind.Bool())
            .returns(new com.jabiz.query.custom.ProjectedField("nokind", null, null, null))
            .list(l -> l.filters("ghost").sorts("dup").key("gone").defaultSort("other", true))
            .sqlTemplate("SELECT 1 FROM {{Order}} o"))))
            .contains("result a b is not a plain SQL name",
                "result DUP is declared twice (result names are case-insensitive)",
                "result nokind has no kind",
                "list.defaultSort other is not in list.sorts",
                "list refers to ghost, which is not a result column",
                "list refers to gone, which is not a result column",
                "list refers to other, which is not a result column");
    }

    @Test
    void barePhysicalNamesAreWarnedAbout() {
        assertThat(problems(query(q -> q
            .parameter("note_text", new SemanticKind.Text(null, false), false)
            .returns("amount_jpy", new SemanticKind.Monetary("JPY", 0))
            .sqlTemplate("SELECT o.amount_jpy AS amount_jpy FROM t_order o WHERE o.{{Order.note}} = :note_text"
                + " AND o.region_code = 'region_code'"))))
            .containsExactly("W bare physical name t_order (table of Order); use a placeholder so the template follows"
                    + " the metamodel",
                "W bare physical name region_code (column of Order.region); use a placeholder so the template follows"
                    + " the metamodel");
    }

    @Test
    void entitiesKindsAndOrderAreChecked() {
        assertThat(problems(AdvancedQueryDefinition.define("q", q -> q.permissions("p")
            .parameter("k", new SemanticKind.Custom("no.such.kind", java.util.Map.of()), false)
            .returns("x", new SemanticKind.Custom("no.such.kind", java.util.Map.of()))
            .sqlTemplate("SELECT :k AS x, (SELECT 1 ORDER BY 1) AS \"order\" FROM (SELECT 1) AS order_x ORDER  BY 1"))))
            .containsExactly("entities must name at least one entity",
                "parameter k uses custom kind no.such.kind, which has no registered CustomKindSupport",
                "W a top-level ORDER BY has no effect: the platform sorts the result (list.defaultSort, then the"
                    + " stable key)",
                "result x uses custom kind no.such.kind, which has no registered CustomKindSupport");
    }
}

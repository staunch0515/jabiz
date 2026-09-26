package com.jabiz.query.template;

import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.query.QueryPredicate;
import com.jabiz.query.SortOrder;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OuterQueryCompilerTest {

    private static final String INNER = "SELECT 1";

    private static final AdvancedQueryDefinition QUERY = AdvancedQueryDefinition.define("q", q -> q
        .returns("orderNo", new SemanticKind.SemanticIdentity("urn:test:order"))
        .returns("amount", new SemanticKind.Monetary("JPY", 0))
        .returns("note", new SemanticKind.Text(null, false))
        .returns("labels", new SemanticKind.Custom("test.map", Map.of()))
        .list(l -> l.filters("orderNo", "amount", "note").sorts("amount", "note").defaultSort("amount", false))
        .sqlTemplate(INNER));

    static {
        if (com.jabiz.entity.CustomKinds.find("test.map").isEmpty()) {
            com.jabiz.entity.CustomKinds.register(new MapKind());
        }
    }

    private static OuterQueryCompiler.OuterQuery compile(QueryPredicate filter, List<SortOrder> sorts) {
        return OuterQueryCompiler.compile(QUERY, INNER, filter, sorts, 20, 10);
    }

    @Test
    void wrapsPagesAndCounts() {
        OuterQueryCompiler.OuterQuery outer = compile(null, List.of());

        assertThat(outer.listSql()).isEqualTo("SELECT * FROM (\nSELECT 1\n) q"
            + " ORDER BY q.amount DESC, CAST(q.orderno AS text) ASC, q.note ASC LIMIT :__limit OFFSET :__offset");
        assertThat(outer.countSql()).isEqualTo("SELECT count(*) AS total FROM (SELECT * FROM (\nSELECT 1\n) q) c");
        assertThat(outer.listParams().get("__limit").value()).isEqualTo(10);
        assertThat(outer.listParams().get("__offset").value()).isEqualTo(20L);
        assertThat(outer.countParams()).isEmpty();
    }

    @Test
    void filtersAreBoundAndSharedByListAndCount() {
        OuterQueryCompiler.OuterQuery outer = compile(new QueryPredicate.And(List.of(
            new QueryPredicate.Gte("amount", "100"),
            new QueryPredicate.In("orderNo", List.of("A", 7)),
            new QueryPredicate.Or(List.of(new QueryPredicate.Like("note", "x%"), new QueryPredicate.IsNull("note"))),
            new QueryPredicate.Between("amount", 1, 2),
            new QueryPredicate.Eq("note", null),
            new QueryPredicate.Ne("note", null),
            new QueryPredicate.Ne("note", "z"),
            new QueryPredicate.Lt("amount", 5),
            new QueryPredicate.Lte("amount", 5),
            new QueryPredicate.Gt("amount", 5),
            new QueryPredicate.IsNotNull("amount"))), List.of(new SortOrder("note", true)));

        assertThat(outer.listSql()).contains(" WHERE (q.amount >= :__f0 AND CAST(q.orderno AS text) = ANY(:__f1)"
            + " AND (q.note LIKE :__f2 OR q.note IS NULL) AND q.amount BETWEEN :__f3 AND :__f4 AND q.note IS NULL"
            + " AND q.note IS NOT NULL AND q.note <> :__f5 AND q.amount < :__f6 AND q.amount <= :__f7"
            + " AND q.amount > :__f8 AND q.amount IS NOT NULL)")
            .contains("ORDER BY q.note ASC, CAST(q.orderno AS text) ASC, q.amount ASC");
        assertThat(outer.countParams().get("__f0").value()).isEqualTo(new BigDecimal("100"));
        assertThat((String[]) outer.countParams().get("__f1").value()).containsExactly("A", "7");
        assertThat(outer.listParams()).containsAllEntriesOf(outer.countParams());
    }

    @Test
    void emptyAndAlwaysTrueDisjunctions() {
        assertThat(compile(new QueryPredicate.Or(List.of()), List.of()).listSql()).contains("WHERE 1 = 0");
        assertThat(compile(new QueryPredicate.Or(List.of(new QueryPredicate.And(List.of()),
            new QueryPredicate.IsNull("note"))), List.of()).listSql()).doesNotContain("WHERE");
    }

    @Test
    void anExplicitKeyIsTheStableOrder() {
        AdvancedQueryDefinition keyed = AdvancedQueryDefinition.define("k", q -> q
            .returns("a", new SemanticKind.Bool()).returns("b", new SemanticKind.Bool())
            .list(l -> l.key("b")).sqlTemplate(INNER));

        assertThat(OuterQueryCompiler.compile(keyed, INNER, null, List.of(), 0, 1).listSql())
            .contains("ORDER BY q.b ASC LIMIT");
    }

    @Test
    void onlyWhitelistedColumnsAndAllowedOperators() {
        expect(() -> compile(new QueryPredicate.Eq("labels", "x"), List.of()), "labels", "FILTER_NOT_ALLOWED");
        expect(() -> compile(null, List.of(new SortOrder("orderNo", true))), "orderNo", "SORT_NOT_ALLOWED");
        expect(() -> compile(new QueryPredicate.Like("amount", "1%"), List.of()), "amount", "OPERATOR_NOT_ALLOWED");
        expect(() -> compile(new QueryPredicate.Gt("orderNo", "A"), List.of()), "orderNo", "OPERATOR_NOT_ALLOWED");
        expect(() -> compile(new QueryPredicate.Eq("ghost", 1), List.of()), "ghost", "UNKNOWN_FIELD");
    }

    @Test
    void malformedValuesAreRejected() {
        expect(() -> compile(new QueryPredicate.Gt("amount", "lots"), List.of()), "amount", "INVALID_VALUE");
        expect(() -> compile(new QueryPredicate.Gt("amount", null), List.of()), "amount", "INVALID_VALUE");
        expect(() -> compile(new QueryPredicate.Like("note", null), List.of()), "note", "INVALID_VALUE");
        expect(() -> compile(new QueryPredicate.In("orderNo", null), List.of()), "orderNo", "INVALID_VALUE");
        expect(() -> compile(new QueryPredicate.In("orderNo", java.util.Arrays.asList("a", null)), List.of()),
            "orderNo", "INVALID_VALUE");
        expect(() -> compile(new QueryPredicate.Between("amount", 1, null), List.of()), "amount", "INVALID_VALUE");
    }

    @Test
    void identifiersOfTemporalEntitiesAreComparedInCanonicalForm() {
        AdvancedQueryDefinition prices = AdvancedQueryDefinition.define("p", q -> q
            .returns("priceId", new SemanticKind.SemanticIdentity("urn:test:price"), "Price", "priceId")
            .returns("orderRef", new SemanticKind.Reference("Order"), "Price", "orderRef")
            .list(l -> l.filters("priceId", "orderRef"))
            .sqlTemplate(INNER));
        java.util.UUID id = java.util.UUID.randomUUID();

        OuterQueryCompiler.OuterQuery outer = OuterQueryCompiler.compile(prices, INNER, new QueryPredicate.And(List.of(
                new QueryPredicate.Eq("priceId", id.toString().toUpperCase()),
                new QueryPredicate.Eq("orderRef", "Ab-1"))), List.of(), 0, 5, TemplateFixtures.ENTITIES);

        assertThat(outer.countParams().get("__f0").value()).isEqualTo(id.toString());
        // Order is not temporal: its identifiers are compared as given.
        assertThat(outer.countParams().get("__f1").value()).isEqualTo("Ab-1");
        expect(() -> OuterQueryCompiler.compile(prices, INNER, new QueryPredicate.Eq("priceId", "nope"), List.of(), 0,
            5, TemplateFixtures.ENTITIES), "priceId", "INVALID_VALUE");
    }

    private static void expect(Runnable call, String field, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ValidationException.class,
            e -> assertThat(e.violations()).extracting(Violation::field, Violation::ruleCode)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(field, code)));
    }

    /** A custom kind stored as JSON, to show such columns are left out of the stable order. */
    static final class MapKind implements com.jabiz.entity.CustomKindSupport {
        @Override public String kindId() { return "test.map"; }
        @Override public Object coerce(Map<String, Object> params, Object raw, boolean forInput) { return raw; }
        @Override public Class<?> javaType(Map<String, Object> params) { return Map.class; }
        @Override public java.util.Set<com.jabiz.query.QueryOperator> allowedOperators(Map<String, Object> params) {
            return java.util.Set.of();
        }
        @Override public Map<String, Object> export(Map<String, Object> params) { return Map.of(); }
    }
}

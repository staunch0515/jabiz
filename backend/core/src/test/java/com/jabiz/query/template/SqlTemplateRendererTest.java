package com.jabiz.query.template;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.query.QueryCompiler;
import com.jabiz.query.TimeSlice;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static com.jabiz.query.template.TemplateFixtures.JP_ORDERS;
import static com.jabiz.query.template.TemplateFixtures.JP_PRICES;
import static com.jabiz.query.template.TemplateFixtures.ORDER;
import static com.jabiz.query.template.TemplateFixtures.PRICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlTemplateRendererTest {

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final DatasetDefinition ALL_ORDERS = DatasetDefinition.define("urn:test:all", d -> d
        .targetEntityType("Order").storage(s -> s.connectionPoolRef("default")));

    private final SqlTemplateRenderer renderer = new SqlTemplateRenderer(new QueryCompiler());

    private static AdvancedQueryDefinition query(String sql, String... entities) {
        return AdvancedQueryDefinition.define("q", q -> q.fromEntities(entities)
            .returns("x", new SemanticKind.Text(null, false)).sqlTemplate(sql));
    }

    @Test
    void anUnrestrictedEntityIsItsTableAndFieldsAreColumns() {
        QueryCompiler.Binder binder = new QueryCompiler.Binder("scope_");
        String sql = renderer.render(query("SELECT o.{{Order.amount}} FROM {{Order}} o WHERE o.{{Order.note}} = :n",
                "Order"), Map.of("Order", new SqlTemplateRenderer.EntityBinding(ORDER, ALL_ORDERS, Map.of())),
            TimeSlice.asOf(NOW), binder).sql();

        assertThat(sql).isEqualTo("SELECT o.amount_jpy FROM t_order o WHERE o.note_text = :n");
        assertThat(binder.params()).isEmpty();
    }

    @Test
    void aScopedDatasetIsASubSelectWithScopeAndSoftDelete() {
        QueryCompiler.Binder binder = new QueryCompiler.Binder("scope_");
        String sql = renderer.render(query("SELECT 1 FROM {{Order}} o", "Order"),
            Map.of("Order", new SqlTemplateRenderer.EntityBinding(ORDER, JP_ORDERS, Map.of("region", "JP"))),
            TimeSlice.asOf(NOW), binder).sql();

        assertThat(sql).isEqualTo("SELECT 1 FROM (SELECT * FROM t_order WHERE region_code = :scope_0"
            + " AND is_removed IS NOT TRUE) o");
        assertThat(binder.params().get("scope_0").value()).isEqualTo("JP");
    }

    /** Decision D3: the versions in effect first, then tombstones and the scope outside. */
    @Test
    void aTemporalEntityIsItsVersionsInEffectFilteredOutside() {
        QueryCompiler.Binder binder = new QueryCompiler.Binder("scope_");
        String sql = renderer.render(query("SELECT 1 FROM {{Price}} p", "Price"),
            Map.of("Price", new SqlTemplateRenderer.EntityBinding(PRICE, JP_PRICES, Map.of("region", "JP"))),
            TimeSlice.asOf(NOW), binder).sql();

        assertThat(sql).isEqualTo("SELECT 1 FROM (SELECT * FROM (SELECT DISTINCT ON (price_id) * FROM t_price"
            + " WHERE effect_start_time <= :__asOf ORDER BY price_id, effect_start_time DESC, version_no DESC) v"
            + " WHERE NOT is_deleted AND region = :scope_0) p");
        assertThat(binder.params().get("__asOf").value()).isEqualTo(NOW);
    }

    @Test
    void placeholdersInsideLiteralsAndCommentsAreLeftAlone() {
        String sql = renderer.render(query("SELECT '{{Order}}' -- {{Order.nope}}\nFROM {{Order}} o", "Order"),
            Map.of("Order", new SqlTemplateRenderer.EntityBinding(ORDER, ALL_ORDERS, Map.of())),
            TimeSlice.asOf(NOW), new QueryCompiler.Binder("scope_")).sql();

        assertThat(sql).isEqualTo("SELECT '{{Order}}' -- {{Order.nope}}\nFROM t_order o");
    }

    @Test
    void everyBadPlaceholderIsReported() {
        assertThatThrownBy(() -> renderer.render(
            query("SELECT {{Order.nope}}, {{Price.amount}}, {{Ghost}} FROM {{Order}} o", "Order", "Ghost"),
            Map.of("Order", new SqlTemplateRenderer.EntityBinding(ORDER, ALL_ORDERS, Map.of())),
            TimeSlice.asOf(NOW), new QueryCompiler.Binder("scope_")))
            .isInstanceOfSatisfying(SqlTemplateException.class, e -> {
                assertThat(e.queryId()).isEqualTo("q");
                assertThat(e.problems()).extracting(TemplateProblem::message).containsExactly(
                    "unknown field Order.nope", "entity Price is not declared in entities", "unknown entity Ghost");
                assertThat(e.problems()).extracting(TemplateProblem::offset).containsExactly(7, 23, 41);
            });
    }

    @Test
    void renderedOffsetsMapBackToTheTemplate() {
        String template = "SELECT o.{{Order.amount}}, bad FROM {{Order}} o";
        SqlTemplateRenderer.Rendered rendered = renderer.render(query(template, "Order"),
            Map.of("Order", new SqlTemplateRenderer.EntityBinding(ORDER, JP_ORDERS, Map.of("region", "JP"))),
            TimeSlice.asOf(NOW), new QueryCompiler.Binder("scope_"));

        assertThat(rendered.templateOffset(rendered.sql().indexOf("bad"))).isEqualTo(template.indexOf("bad"));
        assertThat(rendered.templateOffset(rendered.sql().indexOf("amount_jpy") + 3))
            .isEqualTo(template.indexOf("{{Order.amount}}"));
        assertThat(rendered.templateOffset(rendered.sql().length() - 1)).isEqualTo(template.length() - 1);
        assertThat(rendered.templateOffset(0)).isZero();
    }
}

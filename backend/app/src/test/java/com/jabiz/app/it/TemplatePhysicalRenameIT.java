package com.jabiz.app.it;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.query.BoundValue;
import com.jabiz.query.QueryCompiler;
import com.jabiz.query.RawQueryPlan;
import com.jabiz.query.TimeSlice;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.template.OuterQueryCompiler;
import com.jabiz.query.template.SqlTemplateRenderer;
import com.jabiz.query.template.TemplateChecks;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ROADMAP phase 5, acceptance 2: after the physical columns (and table) of an entity are renamed, only the metamodel
 * changes; the same template text keeps working, because tables and columns come from placeholders.
 */
class TemplatePhysicalRenameIT extends PostgresIntegrationTest {

    /** The template, unchanged between the two mappings. */
    private static final String TEMPLATE = """
        SELECT a.{{Article.code}} AS code, a.{{Article.price}} AS price
        FROM {{Article}} a
        WHERE a.{{Article.price}} >= :minPrice
        """;

    @Autowired
    StorageAdapterRegistry storage;

    private static EntityDefinition article(String table, String codeColumn, String priceColumn) {
        return EntityDefinition.define("Article", eb -> {
            eb.physicalTable(table);
            eb.primaryKey("code");
            eb.field("code", f -> f.physicalColumn(codeColumn).required(true).immutable(true)
                .asSemanticIdentity("urn:it:article"));
            eb.field("price", f -> f.physicalColumn(priceColumn).asMonetary("JPY", 0));
        });
    }

    private static final AdvancedQueryDefinition QUERY = AdvancedQueryDefinition.define("it.articles", q -> q
        .fromEntities("Article")
        .parameter("minPrice", new SemanticKind.Monetary("JPY", 0), true)
        .returns("code", new SemanticKind.SemanticIdentity("urn:it:article"), "Article", "code")
        .returns("price", new SemanticKind.Monetary("JPY", 0), "Article", "price")
        .list(l -> l.defaultSort("code", true).sorts("code"))
        .sqlTemplate(TEMPLATE));

    @Test
    void theSameTemplateRunsBeforeAndAfterARename() {
        execute("CREATE TABLE it_article (f_code text PRIMARY KEY, f_price numeric)");
        execute("INSERT INTO it_article VALUES ('A', 100), ('B', 300), ('C', 500)");
        EntityDefinition before = article("it_article", "f_code", "f_price");
        assertThat(prices(run(before))).containsExactly("B=300", "C=500");

        // The legacy table is renamed physically; the metamodel follows, the template does not change.
        execute("ALTER TABLE it_article RENAME TO it_article_v2");
        execute("ALTER TABLE it_article_v2 RENAME COLUMN f_code TO article_code");
        execute("ALTER TABLE it_article_v2 RENAME COLUMN f_price TO unit_price");
        EntityDefinition after = article("it_article_v2", "article_code", "unit_price");

        assertThat(TemplateChecks.check(QUERY, name -> Optional.of(after))).isEmpty();
        assertThat(prices(run(after))).containsExactly("B=300", "C=500");
        assertThat(render(before)).contains("f_code", "f_price", "it_article ").doesNotContain("unit_price");
        assertThat(render(after)).contains("article_code", "unit_price", "it_article_v2")
            .doesNotContain("f_code", "f_price");
    }

    private static List<String> prices(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> row.get("code") + "=" + ((BigDecimal) row.get("price")).intValue()).toList();
    }

    private static String render(EntityDefinition def) {
        return new SqlTemplateRenderer(new QueryCompiler()).render(QUERY, bindings(def),
            TimeSlice.asOf(START), new QueryCompiler.Binder("scope_")).sql();
    }

    private List<Map<String, Object>> run(EntityDefinition def) {
        QueryCompiler.Binder platform = new QueryCompiler.Binder("scope_");
        String sql = new SqlTemplateRenderer(new QueryCompiler()).render(QUERY, bindings(def), TimeSlice.asOf(START),
            platform).sql();
        OuterQueryCompiler.OuterQuery outer = OuterQueryCompiler.compile(QUERY, sql, null, List.of(), 0, 10);
        Map<String, BoundValue> params = new LinkedHashMap<>(platform.params());
        params.put("minPrice", BoundValue.of(new BigDecimal("200")));
        params.putAll(outer.listParams());
        return asTestRequest(storage.getEngine("default")
            .executeRawQuery(new RawQueryPlan(outer.listSql(), params, Duration.ofSeconds(5)))
            .collectList()).block();
    }

    private static Map<String, SqlTemplateRenderer.EntityBinding> bindings(EntityDefinition def) {
        DatasetDefinition dataset = DatasetDefinition.define("urn:it:dataset:Article", d -> d
            .targetEntityType("Article").asDefault().permissions("it.read", "it.write")
            .storage(s -> s.connectionPoolRef("default")));
        return Map.of("Article", new SqlTemplateRenderer.EntityBinding(def, dataset, Map.of()));
    }
}

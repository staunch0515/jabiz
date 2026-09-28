package com.jabiz.query.template;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.PublicRead;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.query.QueryCompiler;
import com.jabiz.query.TimeSlice;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.ProjectedField;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.jabiz.query.template.TemplateFixtures.ORDER;
import static com.jabiz.query.template.TemplateFixtures.PRICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Public datasets and public templates in the core (docs/design/15-public-access.md; decision D17). */
class PublicReadTest {

    private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
    private static final PublicReadChecks.Limits LIMITS = new PublicReadChecks.Limits(Duration.ofSeconds(2), 100);

    /** Orders of region JP, public: only the id and the amount leave it. */
    private static final DatasetDefinition PUBLIC_ORDERS = DatasetDefinition.define("urn:test:public:Order", d -> d
        .targetEntityType("Order")
        .scope(s -> s.fixed("region", "JP"))
        .policy(p -> p.softDelete("removed").maxQueryBatchSize(50))
        .publicRead(p -> p.fields("orderId", "amount"))
        .storage(s -> s.connectionPoolRef("default")));

    private static final DatasetDefinition PUBLIC_PRICES = DatasetDefinition.define("urn:test:public:Price", d -> d
        .targetEntityType("Price")
        .scope(s -> s.fixed("region", "JP"))
        .policy(p -> p.maxQueryBatchSize(100))
        .publicRead(p -> p.fields("priceId", "amount", "effectStartTime"))
        .storage(s -> s.connectionPoolRef("default")));

    private static final DatasetDefinition ALL_ORDERS = DatasetDefinition.define("urn:test:public:AllOrders", d -> d
        .targetEntityType("Order")
        .policy(p -> p.maxQueryBatchSize(100))
        .publicRead(p -> p.allRows().fields("orderId"))
        .storage(s -> s.connectionPoolRef("default")));

    private static final Function<String, Optional<DatasetDefinition>> DATASETS = id -> Optional.ofNullable(Map.of(
        PUBLIC_ORDERS.resourceId(), PUBLIC_ORDERS, PUBLIC_PRICES.resourceId(), PUBLIC_PRICES,
        TemplateFixtures.JP_ORDERS.resourceId(), TemplateFixtures.JP_ORDERS).get(id));

    private final SqlTemplateRenderer renderer = new SqlTemplateRenderer(new QueryCompiler());

    private static AdvancedQueryDefinition.Builder publicQuery(String sql) {
        return new AdvancedQueryDefinition.Builder("pub").publicAccess().fromEntities("Order")
            .dataset("Order", PUBLIC_ORDERS.resourceId())
            .returns(ProjectedField.inherit("amount", "Order", "amount")).sqlTemplate(sql);
    }

    private String render(AdvancedQueryDefinition query, EntityDefinition entity, DatasetDefinition dataset) {
        return renderer.render(query, Map.of(entity.name, new SqlTemplateRenderer.EntityBinding(entity, dataset,
            dataset.scope().resolve(RequestContext.anonymous(Locale.ROOT, "t")))), TimeSlice.asOf(NOW),
            new QueryCompiler.Binder("scope_")).sql();
    }

    @Test
    void aPublicDatasetIsReadOnlyWithoutTimeTravel() {
        DatasetDefinition dataset = DatasetDefinition.define("urn:test:public:x", d -> d
            .targetEntityType("Order")
            .scope(s -> s.fixed("region", "JP"))
            .policy(p -> p.processOnlyWrites().allowTimeTravel(true))
            .publicRead(p -> p.fields("orderId"))
            .storage(s -> s.connectionPoolRef("default")));

        assertThat(dataset.isPublic()).isTrue();
        assertThat(dataset.policy().readOnly()).isTrue();
        assertThat(dataset.policy().allowTimeTravel()).isFalse();
        assertThat(dataset.policy().processOnlyWrites()).isFalse();
        assertThat(TemplateFixtures.JP_ORDERS.isPublic()).isFalse();
    }

    @Test
    void theWhitelistIsNotEmptyAndHasNoDuplicates() {
        assertThatThrownBy(() -> new PublicRead(false, List.of())).hasMessageContaining("at least one field");
        assertThatThrownBy(() -> new PublicRead(false, List.of("a", "a"))).hasMessageContaining("twice");
        assertThat(new PublicRead(true, List.of("a")).allows("a")).isTrue();
        assertThat(new PublicRead(true, List.of("a")).allows("b")).isFalse();
    }

    @Test
    void aPublicDatasetIsProjectedToItsWhitelist() {
        String sql = render(publicQuery("SELECT o.{{Order.amount}} AS amount FROM {{Order}} o").build(), ORDER,
            PUBLIC_ORDERS);

        assertThat(sql).isEqualTo("SELECT o.amount_jpy AS amount FROM (SELECT order_no, amount_jpy FROM t_order"
            + " WHERE region_code = :scope_0 AND is_removed IS NOT TRUE) o");
    }

    @Test
    void aTemporalPublicDatasetIsProjectedOutsideTheVersionsInEffect() {
        AdvancedQueryDefinition query = new AdvancedQueryDefinition.Builder("pub").publicAccess()
            .fromEntities("Price").dataset("Price", PUBLIC_PRICES.resourceId())
            .returns("x", new SemanticKind.Text(null, false)).sqlTemplate("SELECT 1 FROM {{Price}} p").build();

        assertThat(render(query, PRICE, PUBLIC_PRICES)).isEqualTo("SELECT 1 FROM (SELECT price_id, amount,"
            + " effect_start_time FROM (SELECT DISTINCT ON (price_id) * FROM t_price WHERE effect_start_time <= :__asOf"
            + " ORDER BY price_id, effect_start_time DESC, version_no DESC) v WHERE NOT is_deleted"
            + " AND region = :scope_0) p");
    }

    @Test
    void anUnscopedPublicDatasetIsStillProjected() {
        AdvancedQueryDefinition query = new AdvancedQueryDefinition.Builder("pub").publicAccess()
            .fromEntities("Order").dataset("Order", ALL_ORDERS.resourceId())
            .returns("x", new SemanticKind.Text(null, false)).sqlTemplate("SELECT 1 FROM {{Order}} o").build();

        assertThat(render(query, ORDER, ALL_ORDERS)).isEqualTo("SELECT 1 FROM (SELECT order_no FROM t_order) o");
    }

    @Test
    void aFieldOutsideTheWhitelistIsNotRendered() {
        AdvancedQueryDefinition query = publicQuery("SELECT o.{{Order.note}} AS amount FROM {{Order}} o").build();

        assertThatThrownBy(() -> render(query, ORDER, PUBLIC_ORDERS)).isInstanceOf(SqlTemplateException.class)
            .hasMessageContaining("Order.note is not in the whitelist of public dataset urn:test:public:Order");
    }

    @Test
    void everyProblemOfAPublicDatasetIsReported() {
        EntityDefinition secretive = EntityDefinition.define("Account", eb -> {
            eb.physicalTable("t_account");
            eb.primaryKey("accountId");
            eb.field("accountId", f -> f.physicalColumn("account_id").asSemanticIdentity("urn:test:account"));
            eb.field("owner", f -> f.physicalColumn("owner").asText(20));
            eb.field("pin", f -> f.physicalColumn("pin").asText(20).sensitive());
        });
        DatasetDefinition broken = DatasetDefinition.define("urn:test:public:Account", d -> d
            .targetEntityType("Account")
            .asDefault()
            .scope(s -> s.fromContext("owner", RequestContext::actorId))
            .policy(p -> p.maxQueryBatchSize(500))
            .publicRead(p -> p.allRows().fields("accountId", "pin", "balance"))
            .storage(s -> s.connectionPoolRef("default")));

        assertThat(PublicReadChecks.checkDataset(broken, secretive, LIMITS)).containsExactlyInAnyOrder(
            "a default dataset serves the back office and cannot be public; declare a separate one",
            "scope field owner is taken from the request context, which anonymous visitors do not have; public"
                + " datasets allow fixed scope values only",
            "declares allRows() but also a scope",
            "public field pin is sensitive and can never be public",
            "public field balance does not exist on Account",
            "maxQueryBatchSize 500 exceeds jabiz.public.max-limit 100");
    }

    @Test
    void aPublicDatasetWithoutScopeMustSayAllRows() {
        DatasetDefinition unscoped = DatasetDefinition.define("urn:test:public:o", d -> d
            .targetEntityType("Order").policy(p -> p.maxQueryBatchSize(10)).publicRead(p -> p.fields("orderId"))
            .storage(s -> s.connectionPoolRef("default")));

        assertThat(PublicReadChecks.checkDataset(unscoped, ORDER, LIMITS)).singleElement().asString()
            .startsWith("has no scope");
        assertThat(PublicReadChecks.checkDataset(PUBLIC_ORDERS, ORDER, LIMITS)).isEmpty();
        assertThat(PublicReadChecks.checkDataset(ALL_ORDERS, ORDER, LIMITS)).isEmpty();
        assertThat(PublicReadChecks.checkDataset(TemplateFixtures.JP_ORDERS, ORDER, LIMITS)).isEmpty();
    }

    @Test
    void aValidPublicTemplateHasNoProblems() {
        AdvancedQueryDefinition query = publicQuery("SELECT o.{{Order.amount}} AS amount FROM {{Order}} o")
            .cacheSeconds(30).timeout(Duration.ofMillis(1500)).build();

        assertThat(PublicReadChecks.checkTemplate(query, DATASETS, TemplateFixtures.ENTITIES, LIMITS)).isEmpty();
    }

    @Test
    void everyProblemOfAPublicTemplateIsReported() {
        AdvancedQueryDefinition query = new AdvancedQueryDefinition.Builder("pub").publicAccess()
            .permissions("x.read")
            .cacheSeconds(7200)
            .timeout(Duration.ofSeconds(3))
            .fromEntities("Order", "Price")
            .dataset("Order", PUBLIC_ORDERS.resourceId())
            .returns(ProjectedField.inherit("note", "Order", "note"))
            .sqlTemplate("SELECT o.{{Order.note}} AS note FROM {{Order}} o JOIN {{Price}} p ON 1 = 1")
            .build();

        List<TemplateProblem> problems = PublicReadChecks.checkTemplate(query, DATASETS, TemplateFixtures.ENTITIES,
            LIMITS);

        assertThat(problems).extracting(TemplateProblem::message).containsExactlyInAnyOrder(
            "a public template (access: public) declares no permissions",
            "cacheSeconds must be between 0 and 3600",
            "timeoutMs (3000) exceeds jabiz.public.max-timeout (2000 ms)",
            "entity Price must be read through a public dataset named in datasets; default datasets are never public",
            "field Order.note is not in the whitelist of its public dataset",
            "result note comes from Order.note, which is not in the whitelist of its public dataset");
        assertThat(problems).filteredOn(p -> p.message().startsWith("field Order.note")).singleElement()
            .extracting(TemplateProblem::offset).isEqualTo(9);
    }

    @Test
    void aPublicTemplateCannotReadADatasetThatIsNotPublic() {
        AdvancedQueryDefinition query = new AdvancedQueryDefinition.Builder("pub").publicAccess()
            .fromEntities("Order").dataset("Order", TemplateFixtures.JP_ORDERS.resourceId())
            .returns("x", new SemanticKind.Text(null, false)).sqlTemplate("SELECT 1 FROM {{Order}} o").build();

        assertThat(PublicReadChecks.checkTemplate(query, DATASETS, TemplateFixtures.ENTITIES, LIMITS))
            .extracting(TemplateProblem::message)
            .containsExactly("dataset urn:test:dataset:Order of entity Order is not public");
    }

    @Test
    void onlyPublicTemplatesSetCacheSeconds() {
        AdvancedQueryDefinition priv = new AdvancedQueryDefinition.Builder("priv").permissions("x").cacheSeconds(5)
            .fromEntities("Order").returns("x", new SemanticKind.Text(null, false)).sqlTemplate("SELECT 1").build();
        AdvancedQueryDefinition plain = new AdvancedQueryDefinition.Builder("plain").permissions("x")
            .fromEntities("Order").returns("x", new SemanticKind.Text(null, false)).sqlTemplate("SELECT 1").build();

        assertThat(PublicReadChecks.checkTemplate(priv, DATASETS, TemplateFixtures.ENTITIES, LIMITS))
            .extracting(TemplateProblem::message)
            .containsExactly("cacheSeconds applies to public templates (access: public) only");
        assertThat(PublicReadChecks.checkTemplate(plain, DATASETS, TemplateFixtures.ENTITIES, LIMITS)).isEmpty();
    }

    @Test
    void limitsMustBePositive() {
        assertThatThrownBy(() -> new PublicReadChecks.Limits(Duration.ZERO, 1)).hasMessageContaining("maxTimeout");
        assertThatThrownBy(() -> new PublicReadChecks.Limits(Duration.ofSeconds(1), 0))
            .hasMessageContaining("maxLimit");
    }

    @Test
    void theHeaderDeclaresPublicAccessAndCacheSeconds() {
        SqlTemplateFile.Parts parts = new SqlTemplateFile.Parts("", 1, "SELECT 1", 2);
        AdvancedQueryDefinition query = SqlTemplateFile.compile("p.sql", Map.of("id", "pub", "access", "public",
            "cacheSeconds", 120, "entities", List.of("Order"),
            "results", Map.of("x", Map.of("kind", Map.of("type", "text")))), parts);

        assertThat(query.publicAccess()).isTrue();
        assertThat(query.cacheSeconds()).isEqualTo(120);
        assertThat(query.withDataset("Order", "d").publicAccess()).isTrue();
        assertThatThrownBy(() -> SqlTemplateFile.compile("p.sql", Map.of("id", "pub", "access", "everyone",
            "entities", List.of("Order"), "results", Map.of("x", Map.of("kind", Map.of("type", "text")))), parts))
            .isInstanceOf(SqlTemplateException.class).hasMessageContaining("access must be public");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCatalogDescribesPublicTemplatesOnly() {
        AdvancedQueryDefinition pub = new AdvancedQueryDefinition.Builder("shop.catalog").publicAccess()
            .description("Catalog")
            .fromEntities("Order")
            .parameter("maxAmount", new SemanticKind.Monetary("JPY", 0), false)
            .listParameter("regions", new SemanticKind.Text(8, false), true)
            .returns("amount", new SemanticKind.Monetary("JPY", 0))
            .list(l -> l.filters("amount").sorts("amount").defaultSort("amount", false))
            .sqlTemplate("SELECT 1").build();
        AdvancedQueryDefinition other = new AdvancedQueryDefinition.Builder("a.private").permissions("x")
            .fromEntities("Order").returns("x", new SemanticKind.Text(null, false)).sqlTemplate("SELECT 1").build();
        AdvancedQueryDefinition cached = new AdvancedQueryDefinition.Builder("a.public").publicAccess().cacheSeconds(5)
            .fromEntities("Order").returns("x", new SemanticKind.Text(null, false)).sqlTemplate("SELECT 1").build();

        Map<String, Object> catalog = PublicQueryCatalog.export(List.of(pub, other, cached), 60);
        List<Map<String, Object>> queries = (List<Map<String, Object>>) catalog.get("queries");

        assertThat(queries).extracting(q -> q.get("id")).containsExactly("a.public", "shop.catalog");
        assertThat(queries.get(0)).containsEntry("cacheSeconds", 5).doesNotContainKey("description");
        Map<String, Object> shop = queries.get(1);
        assertThat(shop).containsEntry("description", "Catalog").containsEntry("cacheSeconds", 60);
        assertThat((Map<String, Object>) shop.get("params")).containsOnlyKeys("maxAmount", "regions");
        assertThat((Map<String, Object>) ((Map<String, Object>) shop.get("params")).get("regions"))
            .containsEntry("list", true).containsEntry("required", true);
        assertThat((Map<String, Object>) ((Map<String, Object>) shop.get("results")).get("amount"))
            .containsEntry("type", "monetary");
        assertThat((Map<String, Object>) shop.get("list")).containsEntry("filters", List.of("amount"))
            .containsEntry("defaultSort", Map.of("field", "amount", "asc", false));
    }

    @Test
    void theAnonymousContextHasNoIdentity() {
        RequestContext anonymous = RequestContext.anonymous(Locale.JAPANESE, "r-1");

        assertThat(anonymous.actorId()).isEqualTo(RequestContext.ANONYMOUS_ACTOR);
        assertThat(anonymous.tenantId()).isNull();
        assertThat(anonymous.permissions()).isEmpty();
        assertThat(anonymous.locale()).isEqualTo(Locale.JAPANESE);
        assertThat(anonymous.hasPermission("x")).isFalse();
    }
}

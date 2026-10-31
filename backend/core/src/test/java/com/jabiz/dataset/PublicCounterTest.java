package com.jabiz.dataset;

import com.jabiz.entity.EntityDefinition;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Public counters in the core (docs/design/15-public-access.md section 10; decision D65). */
class PublicCounterTest {

    private static final EntityDefinition PRODUCT = EntityDefinition.define("Product", eb -> {
        eb.physicalTable("t_product");
        eb.primaryKey("productId");
        eb.field("productId", f -> f.physicalColumn("product_id").asSemanticIdentity("urn:test:product"));
        eb.field("sku", f -> f.physicalColumn("sku").asText(40));
        eb.field("region", f -> f.physicalColumn("region").asCode("urn:test:region", "JP", "US"));
        eb.field("price", f -> f.physicalColumn("price").asMonetary("JPY", 0));
        eb.field("secret", f -> f.physicalColumn("secret").asText(40).sensitive());
        eb.field("hidden", f -> f.physicalColumn("hidden").asText(40));
    });

    private static final DatasetDefinition PUBLIC = DatasetDefinition.define("urn:test:public:Product", d -> d
        .targetEntityType("Product")
        .scope(s -> s.fixed("region", "JP"))
        .publicRead(p -> p.fields("productId", "sku", "region", "price"))
        .storage(s -> s.connectionPoolRef("default")));

    private static final DatasetDefinition PRIVATE = DatasetDefinition.define("urn:test:default:Product", d -> d
        .targetEntityType("Product")
        .asDefault()
        .storage(s -> s.connectionPoolRef("default")));

    private static PublicCounter counter(String field) {
        return PublicCounter.define("shop.product-view", c -> c.keys(PUBLIC.resourceId(), field));
    }

    @Test
    void isDeclaredWithANameKeysAndAZone() {
        PublicCounter tokyo = PublicCounter.define("shop.product-view", c -> c.keys(PUBLIC.resourceId(), "productId")
            .zone("Asia/Tokyo"));
        assertThat(tokyo.zone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
        assertThat(counter("productId").zone()).isEqualTo(ZoneOffset.UTC);

        assertThatThrownBy(() -> PublicCounter.define("Shop views", c -> c.keys(PUBLIC.resourceId(), "sku")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must match");
        assertThatThrownBy(() -> PublicCounter.define("a".repeat(61), c -> c.keys(PUBLIC.resourceId(), "sku")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PublicCounter.define("shop.views", c -> { }))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("public dataset");
        assertThatThrownBy(() -> PublicCounter.define("shop.views", c -> c.keys(PUBLIC.resourceId(), "sku")
            .zone("Mars/Olympus"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a zone");
    }

    @Test
    void takesKeysFromAWhitelistedTextCodeOrIdFieldOfAPublicDataset() {
        assertThat(counter("productId").problems(PUBLIC, PRODUCT)).isEmpty();
        assertThat(counter("sku").problems(PUBLIC, PRODUCT)).isEmpty();
        assertThat(counter("region").problems(PUBLIC, PRODUCT)).isEmpty();

        assertThat(counter("price").problems(PUBLIC, PRODUCT))
            .containsExactly("field price is not a text, code, identity or reference field");
        assertThat(counter("hidden").problems(PUBLIC, PRODUCT))
            .containsExactly("field hidden is not whitelisted in publicRead of urn:test:public:Product");
        assertThat(counter("secret").problems(PUBLIC, PRODUCT)).contains(
            "field secret is not a text, code, identity or reference field");
        assertThat(counter("missing").problems(PUBLIC, PRODUCT)).contains(
            "field missing is not a field of Product");
        assertThat(counter("sku").problems(PRIVATE, PRODUCT))
            .containsExactly("dataset urn:test:public:Product is not public (publicRead)");
        assertThat(counter("sku").problems(null, null))
            .containsExactly("dataset urn:test:public:Product is not declared");
    }

    @Test
    void mayNameTheKeysOwnerByAWhitelistedField() {
        PublicCounter bySku = PublicCounter.define("shop.product-view", c -> c.keys(PUBLIC.resourceId(), "productId")
            .owner("sku"));
        assertThat(bySku.owner()).isEqualTo("sku");
        assertThat(bySku.problems(PUBLIC, PRODUCT)).isEmpty();
        assertThat(counter("productId").owner()).isNull();

        assertThat(PublicCounter.define("shop.product-view", c -> c.keys(PUBLIC.resourceId(), "productId")
            .owner("hidden")).problems(PUBLIC, PRODUCT))
            .containsExactly("owner field hidden is not whitelisted in publicRead of urn:test:public:Product");
        assertThat(PublicCounter.define("shop.product-view", c -> c.keys(PUBLIC.resourceId(), "productId")
            .owner("price")).problems(PUBLIC, PRODUCT))
            .containsExactly("field price is not a text, code, identity or reference field");
    }

    @Test
    void normalizesKeys() {
        String id = "0192f0a0-0000-7000-8000-00000000c001";
        assertThat(PublicCounter.normalizeKey(id.toUpperCase(), true)).isEqualTo(id);
        assertThat(PublicCounter.normalizeKey("1-2-3-4-5", true)).isNull();
        assertThat(PublicCounter.normalizeKey("not-an-id", true)).isNull();
        assertThat(PublicCounter.normalizeKey("SKU-1", false)).isEqualTo("SKU-1");
        assertThat(PublicCounter.normalizeKey(" ", false)).isNull();
        assertThat(PublicCounter.normalizeKey(null, false)).isNull();
        assertThat(PublicCounter.normalizeKey("x".repeat(PublicCounter.MAX_KEY_LENGTH + 1), false)).isNull();
        assertThat(PublicCounter.normalizeKey("x".repeat(PublicCounter.MAX_KEY_LENGTH), false)).hasSize(100);
    }
}

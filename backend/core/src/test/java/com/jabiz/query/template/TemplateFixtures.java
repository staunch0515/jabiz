package com.jabiz.query.template;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Entities and datasets shared by the template tests. */
final class TemplateFixtures {

    static final EntityDefinition ORDER = EntityDefinition.define("Order", eb -> {
        eb.physicalTable("t_order");
        eb.primaryKey("orderId");
        eb.field("orderId", f -> f.physicalColumn("order_no").asSemanticIdentity("urn:test:order"));
        eb.field("region", f -> f.physicalColumn("region_code").asCode("urn:test:region", "JP", "US"));
        eb.field("amount", f -> f.physicalColumn("amount_jpy").asMonetary("JPY", 0));
        eb.field("note", f -> f.physicalColumn("note_text").asText(200));
        eb.field("removed", f -> f.physicalColumn("is_removed").asBool());
    });

    static final EntityDefinition PRICE = EntityDefinition.define("Price", eb -> {
        eb.physicalTable("t_price");
        eb.primaryKey("priceId");
        eb.field("priceId", f -> f.physicalColumn("price_id").asSemanticIdentity("urn:test:price"));
        eb.field("region", f -> f.physicalColumn("region").asText(8));
        eb.field("amount", f -> f.physicalColumn("amount").asMonetary("JPY", 0));
        eb.field("orderRef", f -> f.physicalColumn("order_ref").asReference("Order"));
        eb.temporal();
    });

    /** Orders of region JP, soft-deleted through {@code removed}. */
    static final DatasetDefinition JP_ORDERS = DatasetDefinition.define("urn:test:dataset:Order", d -> d
        .targetEntityType("Order")
        .asDefault()
        .scope(s -> s.fixed("region", "JP"))
        .policy(p -> p.softDelete("removed"))
        .storage(s -> s.connectionPoolRef("default")));

    static final DatasetDefinition JP_PRICES = DatasetDefinition.define("urn:test:dataset:Price", d -> d
        .targetEntityType("Price")
        .asDefault()
        .scope(s -> s.fixed("region", "JP"))
        .storage(s -> s.connectionPoolRef("default")));

    static final Function<String, Optional<EntityDefinition>> ENTITIES =
        name -> Optional.ofNullable(Map.of("Order", ORDER, "Price", PRICE).get(name));

    private TemplateFixtures() {}
}

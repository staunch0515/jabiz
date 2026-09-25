package com.jabiz.app;

import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;

/**
 * Sample temporal entity (docs/design/04-temporal-append-only.md): the price of a product, whose changes can be
 * scheduled ahead and corrected afterwards without losing any earlier price.
 */
public final class PriceEntityDefinitions extends BaseEntityDefinitions {

    public static final EntityDefinition PRICE = EntityDefinition.define("Price", eb -> {
        eb.physicalTable("t_price");
        eb.primaryKey("priceId");

        eb.field("priceId", f -> f.physicalColumn("f_price_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:pricing:price"));
        eb.field("sku", f -> f.physicalColumn("f_sku").immutable(true).required(true).asText(64));
        eb.field("amount", nonNegativeMonetary("f_amount", "NON_NEGATIVE_AMOUNT", "JPY", 0)
            .andThen(f -> f.required(true)));
        eb.unique("uk_price_sku", "sku");
        eb.listView("default", lv -> lv
            .columns("sku", "amount", "effectStartTime")
            .filters("sku", "amount")
            .sorts("sku", "amount", "effectStartTime")
            .defaultSort("sku", true));
        eb.temporal(t -> t.allowScheduled(true));
    });

    private PriceEntityDefinitions() {}
}

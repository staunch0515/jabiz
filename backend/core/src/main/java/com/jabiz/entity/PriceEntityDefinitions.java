package com.jabiz.entity;

public final class PriceEntityDefinitions extends BaseEntityDefinitions {

    public static final EntityDefinition PRICE = EntityDefinition.define("PriceVersion", eb -> {
        eb.physicalTable("t_price_version");
        eb.primaryKey("priceId");

        eb.field("priceId", semanticIdentity("f_price_id", "urn:ubos:entity:pricing:price-version"));
        eb.field("amount", nonNegativeMonetary("f_amount", "NON_NEGATIVE_AMOUNT", "JPY", 0));
        eb.field("effectiveTime", temporalCausality("f_effective_at", "PRICE_CAUSALITY", 0));
        eb.field("recordedTime", systemRecordedTime("f_created_at"));
        eb.field("rowVersion", rowVersion("f_version"));
    });

    private PriceEntityDefinitions() {}
}

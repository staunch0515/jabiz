package com.jabiz.app.it.fixture;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.Violation;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;

/**
 * Temporal entities of the integration tests (docs/design/04-temporal-append-only.md). Tables are created by
 * {@code db/testmigration/V1001__it_temporal.sql}.
 */
public final class ItTemporalFixtures extends BaseEntityDefinitions {

    public static final String PRICE_DATASET = "urn:jabiz:dataset:it:ItPrice";
    /** Region JP only. */
    public static final String PRICE_JP_DATASET = "urn:jabiz:dataset:it:ItPriceJP";
    /** No time travel. */
    public static final String PRICE_CURRENT_DATASET = "urn:jabiz:dataset:it:ItPriceCurrent";
    public static final String NOTE_DATASET = "urn:jabiz:dataset:it:ItNote";

    /** Rule code of ItPrice's entity check: the note "cheap" does not go with an amount above 1000. */
    public static final String CHEAP_NOTE = "IT_CHEAP_NOTE";

    /** Schedulable prices; SKUs are unique; lifecycle DRAFT -> ACTIVE -> RETIRED; may replace another price. */
    public static final EntityDefinition PRICE = EntityDefinition.define("ItPrice", eb -> {
        eb.physicalTable("it_price");
        eb.primaryKey("priceId");
        eb.field("priceId", f -> f.physicalColumn("price_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:it:price"));
        eb.field("sku", f -> f.physicalColumn("sku").required(true).asText(32));
        eb.field("region", f -> f.physicalColumn("region").required(true).asText(8));
        eb.field("amount", nonNegativeMonetary("amount", "NON_NEGATIVE_AMOUNT", "JPY", 0));
        eb.field("note", f -> f.physicalColumn("note").asText(200));
        eb.field("status", f -> f.physicalColumn("status")
            .asCode("urn:jabiz:dict:it_price_status", "DRAFT", "ACTIVE", "RETIRED"));
        eb.field("replacesRef", f -> f.physicalColumn("replaces_ref").asReference("ItPrice"));
        eb.stateTransitions("status", st -> {
            st.from("DRAFT").to("ACTIVE");
            st.from("ACTIVE").to("RETIRED");
        });
        eb.unique("uk_it_price_sku", "sku");
        // Over two fields, so that a rebased copy can break it (docs/design/02-metamodel.md section 4.1).
        eb.check(CHEAP_NOTE, (state, ctx) -> "cheap".equals(state.get("note"))
            && state.get("amount") instanceof BigDecimal amount && amount.compareTo(BigDecimal.valueOf(1000)) > 0
            ? List.of(new Violation("note", CHEAP_NOTE, "An amount above 1000 is not cheap"))
            : List.of());
        eb.listView("default", lv -> lv
            .columns("sku", "region", "amount", "status")
            .filters("sku", "region", "amount")
            .sorts("sku", "amount")
            .defaultSort("sku", true));
        eb.temporal(t -> t.allowScheduled(true));
    });

    /** Not schedulable; refers to a price. */
    public static final EntityDefinition NOTE = EntityDefinition.define("ItNote", eb -> {
        eb.physicalTable("it_note");
        eb.primaryKey("noteId");
        eb.field("noteId", f -> f.physicalColumn("note_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:it:note"));
        eb.field("priceRef", f -> f.physicalColumn("price_ref").asReference("ItPrice"));
        eb.field("body", f -> f.physicalColumn("body").asText(null, true));
        eb.temporal();
    });

    /** SKUs of the prices a dataset shows, through a hand-written template (sorted by the platform, by sku). */
    public static final AdvancedQueryDefinition SKUS = AdvancedQueryDefinition.define("it.price_skus", q -> q
        .fromEntities("ItPrice")
        .returns("sku", new SemanticKind.Text(32, false))
        .sqlTemplate("SELECT p.{{ItPrice.sku}} AS sku FROM {{ItPrice}} p"));

    private ItTemporalFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        EntityDefinition itPriceEntity() {
            return PRICE;
        }

        @Bean
        EntityDefinition itNoteEntity() {
            return NOTE;
        }

        @Bean
        DatasetDefinition itPriceDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(PRICE_DATASET, d -> d
                .targetEntityType("ItPrice")
                .asDefault()
                .permissions("it.read", "it.write")
                .storage(s -> s.connectionPoolRef(pool)));
        }

        @Bean
        DatasetDefinition itPriceJpDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(PRICE_JP_DATASET, d -> d
                .targetEntityType("ItPrice")
                .permissions("it.read", "it.write")
                .scope(s -> s.fixed("region", "JP"))
                .storage(s -> s.connectionPoolRef(pool)));
        }

        @Bean
        DatasetDefinition itPriceCurrentDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(PRICE_CURRENT_DATASET, d -> d
                .targetEntityType("ItPrice")
                .permissions("it.read", "it.write")
                .policy(p -> p.allowTimeTravel(false))
                .storage(s -> s.connectionPoolRef(pool)));
        }

        @Bean
        DatasetDefinition itNoteDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(NOTE_DATASET, d -> d
                .targetEntityType("ItNote")
                .asDefault()
                .permissions("it.read", "it.write")
                .storage(s -> s.connectionPoolRef(pool)));
        }
    }
}

package com.jabiz.app.it.fixture;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Referrers of {@code ItPrice} for the tests of deletions read ahead together (ROADMAP 17zz21): a temporal one whose
 * versions may be scheduled and which publishes its changes ({@code ItTag}), and a plain one ({@code ItPriceMark}).
 */
public final class ItDeleteFixtures extends BaseEntityDefinitions {

    public static final String TAG_DATASET = "urn:jabiz:dataset:it:ItTag";
    public static final String MARK_DATASET = "urn:jabiz:dataset:it:ItPriceMark";

    public static final EntityDefinition TAG = EntityDefinition.define("ItTag", eb -> {
        eb.physicalTable("it_tag");
        eb.primaryKey("tagId");
        eb.field("tagId", f -> f.physicalColumn("tag_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:it:tag"));
        eb.field("priceRef", f -> f.physicalColumn("price_ref").asReference("ItPrice"));
        eb.field("label", f -> f.physicalColumn("label").asText(200));
        eb.publishChanges();
        eb.temporal(t -> t.allowScheduled(true));
    });

    public static final EntityDefinition MARK = EntityDefinition.define("ItPriceMark", eb -> {
        eb.physicalTable("it_price_mark");
        eb.primaryKey("markId");
        eb.field("markId", semanticIdentity("f_id", "urn:jabiz:entity:it:price-mark"));
        eb.field("priceRef", f -> f.physicalColumn("f_price_ref").asReference("ItPrice"));
        eb.field("rowVersion", rowVersion("f_version"));
    });

    private ItDeleteFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        EntityDefinition itTagEntity() {
            return TAG;
        }

        @Bean
        EntityDefinition itPriceMarkEntity() {
            return MARK;
        }

        @Bean
        DatasetDefinition itTagDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(TAG_DATASET, d -> d
                .targetEntityType("ItTag")
                .asDefault()
                .permissions("it.read", "it.write")
                .storage(s -> s.connectionPoolRef(pool)));
        }

        @Bean
        DatasetDefinition itPriceMarkDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(MARK_DATASET, d -> d
                .targetEntityType("ItPriceMark")
                .asDefault()
                .permissions("it.read", "it.write")
                .storage(s -> s.connectionPoolRef(pool)));
        }
    }
}

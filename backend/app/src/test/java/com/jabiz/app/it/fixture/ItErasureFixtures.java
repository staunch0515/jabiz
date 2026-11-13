package com.jabiz.app.it.fixture;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.MaskStyle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Many rows per user for the loads of the notices before an account is deleted (ROADMAP 17zz22): a status, an amount, a
 * region the second dataset's scope fixes, and a sensitive and a masked field the loads must not filter on nor add up.
 */
public final class ItErasureFixtures extends BaseEntityDefinitions {

    public static final String POINT = "ItErasurePoint";
    public static final String DATASET = "urn:jabiz:dataset:it:ItErasurePoint";
    /** The same entity, within the region EAST only. */
    public static final String EAST_DATASET = "urn:jabiz:dataset:it:ItErasurePointEast";

    public static final EntityDefinition ENTITY = EntityDefinition.define(POINT, eb -> {
        eb.physicalTable("it_erasure_point");
        eb.primaryKey("pointId");
        eb.field("pointId", semanticIdentity("f_id", "urn:jabiz:entity:it:erasure-point"));
        eb.field("userId", f -> f.physicalColumn("f_user").asText(36));
        eb.field("status", f -> f.physicalColumn("f_status").asCode("urn:jabiz:dict:it_erasure_status", "OPEN", "USED",
            "VOID"));
        eb.field("region", f -> f.physicalColumn("f_region").asText(10));
        eb.field("amount", f -> f.physicalColumn("f_amount").asMonetary("JPY", 0));
        eb.field("note", f -> f.physicalColumn("f_note").asText(100));
        eb.field("secret", f -> f.physicalColumn("f_secret").asText(100).sensitive());
        eb.field("card", f -> f.physicalColumn("f_card").asText(30).masked("it.card.reveal", MaskStyle.LAST4));
        eb.field("rowVersion", rowVersion("f_version"));
    });

    private ItErasureFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        EntityDefinition itErasurePointEntity() {
            return ENTITY;
        }

        @Bean
        DatasetDefinition itErasurePointDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(DATASET, d -> d
                .targetEntityType(POINT)
                .asDefault()
                .permissions("it.read", "it.write")
                .storage(s -> s.connectionPoolRef(pool)));
        }

        @Bean
        DatasetDefinition itErasurePointEastDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(EAST_DATASET, d -> d
                .targetEntityType(POINT)
                .permissions("it.read", "it.write")
                .scope(s -> s.fixed("region", "EAST"))
                .storage(s -> s.connectionPoolRef(pool)));
        }
    }
}

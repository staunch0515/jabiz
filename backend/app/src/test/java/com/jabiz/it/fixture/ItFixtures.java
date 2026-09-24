package com.jabiz.it.fixture;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Entities and datasets used by the integration tests, one per dataset policy under test.
 * Tables are created by {@code db/testmigration/V1000__it_fixtures.sql}; nothing here is on the
 * production classpath.
 */
public final class ItFixtures extends BaseEntityDefinitions {

    public static final String TICKET_DATASET = "urn:jabiz:dataset:it:ItTicket";
    public static final String SOFT_DATASET = "urn:jabiz:dataset:it:ItSoft";
    public static final String REGIONAL_DATASET = "urn:jabiz:dataset:it:ItRegional";
    public static final String READONLY_DATASET = "urn:jabiz:dataset:it:ItReadonly";

    public static final int TICKET_MAX_WRITE_BATCH = 3;
    public static final int TICKET_MAX_QUERY_BATCH = 5;

    /** Lifecycle OPEN -> IN_PROGRESS -> DONE; OPEN is the only initial state. */
    public static final EntityDefinition TICKET = EntityDefinition.define("ItTicket", eb -> {
        eb.physicalTable("it_ticket");
        eb.primaryKey("ticketId");
        eb.field("ticketId", semanticIdentity("f_id", "urn:jabiz:entity:it:ticket"));
        eb.field("title", f -> f.physicalColumn("f_title").required(true));
        eb.field("amount", nonNegativeMonetary("f_amount", "NON_NEGATIVE_AMOUNT", "JPY", 0));
        eb.field("status", f -> f.physicalColumn("f_status")
            .asCode("urn:jabiz:dict:it_ticket_status", "OPEN", "IN_PROGRESS", "DONE"));
        eb.field("owner", f -> f.physicalColumn("f_owner").immutable(true));
        eb.field("recordedTime", systemRecordedTime("f_created_at"));
        eb.field("rowVersion", rowVersion("f_version"));
        eb.stateTransitions("status", st -> {
            st.from("OPEN").to("IN_PROGRESS");
            st.from("IN_PROGRESS").to("DONE");
        });
    });

    public static final EntityDefinition SOFT = EntityDefinition.define("ItSoft", eb -> {
        eb.physicalTable("it_soft");
        eb.primaryKey("softId");
        eb.field("softId", semanticIdentity("f_id", "urn:jabiz:entity:it:soft"));
        eb.field("name", f -> f.physicalColumn("f_name"));
        eb.field("rowVersion", rowVersion("f_version"));
    });

    public static final EntityDefinition REGIONAL = EntityDefinition.define("ItRegional", eb -> {
        eb.physicalTable("it_regional");
        eb.primaryKey("regionalId");
        eb.field("regionalId", semanticIdentity("f_id", "urn:jabiz:entity:it:regional"));
        eb.field("region", f -> f.physicalColumn("f_region").asCode("urn:jabiz:dict:it_region", "JP", "US"));
        eb.field("name", f -> f.physicalColumn("f_name"));
        eb.field("rowVersion", rowVersion("f_version"));
    });

    public static final EntityDefinition READONLY = EntityDefinition.define("ItReadonly", eb -> {
        eb.physicalTable("it_readonly");
        eb.primaryKey("readonlyId");
        eb.field("readonlyId", semanticIdentity("f_id", "urn:jabiz:entity:it:readonly"));
        eb.field("name", f -> f.physicalColumn("f_name"));
        eb.field("rowVersion", rowVersion("f_version"));
    });

    private ItFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        EntityDefinition itTicketEntity() {
            return TICKET;
        }

        @Bean
        EntityDefinition itSoftEntity() {
            return SOFT;
        }

        @Bean
        EntityDefinition itRegionalEntity() {
            return REGIONAL;
        }

        @Bean
        EntityDefinition itReadonlyEntity() {
            return READONLY;
        }

        @Bean
        DatasetDefinition itTicketDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(TICKET_DATASET, d -> d
                .targetEntityType("ItTicket")
                .storage(s -> s.connectionPoolRef(pool))
                .policy(p -> p.maxWriteBatchSize(TICKET_MAX_WRITE_BATCH).maxQueryBatchSize(TICKET_MAX_QUERY_BATCH)));
        }

        @Bean
        DatasetDefinition itSoftDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(SOFT_DATASET, d -> d
                .targetEntityType("ItSoft")
                .storage(s -> s.connectionPoolRef(pool))
                .policy(p -> p.softDelete(true, "is_deleted").softDeleteTimeColumn("deleted_at")));
        }

        @Bean
        DatasetDefinition itRegionalDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(REGIONAL_DATASET, d -> d
                .targetEntityType("ItRegional")
                .storage(s -> s.connectionPoolRef(pool))
                .defaultPartitionFilter(Map.of("region", "JP")));
        }

        @Bean
        DatasetDefinition itReadonlyDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(READONLY_DATASET, d -> d
                .targetEntityType("ItReadonly")
                .storage(s -> s.connectionPoolRef(pool))
                .policy(p -> p.readOnly(true)));
        }
    }
}

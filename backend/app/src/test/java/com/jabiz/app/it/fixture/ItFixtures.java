package com.jabiz.app.it.fixture;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicReference;

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
    public static final String TENANT_DATASET = "urn:jabiz:dataset:it:ItTenant";
    public static final String UNIQUE_DATASET = "urn:jabiz:dataset:it:ItUnique";

    public static final int TICKET_MAX_WRITE_BATCH = 3;
    public static final int TICKET_MAX_QUERY_BATCH = 5;

    /** Actor seen by the last evaluation of ItTicket's title rule; shows what rules receive. */
    public static final AtomicReference<String> LAST_TITLE_ACTOR = new AtomicReference<>();

    /** Lifecycle OPEN -> IN_PROGRESS -> DONE; OPEN is the only initial state. */
    public static final EntityDefinition TICKET = EntityDefinition.define("ItTicket", eb -> {
        eb.physicalTable("it_ticket");
        eb.primaryKey("ticketId");
        eb.field("ticketId", semanticIdentity("f_id", "urn:jabiz:entity:it:ticket"));
        eb.field("title", f -> f.physicalColumn("f_title").required(true)
            .rule("IT_ACTOR_PROBE", (v, ctx) -> {
                LAST_TITLE_ACTOR.set(ctx.request().actorId());
                return true;
            }));
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
        eb.field("deleted", f -> f.physicalColumn("is_deleted").asBool());
        eb.field("deletedAt", systemRecordedTime("deleted_at"));
    });

    /** Scoped by the tenant of the request: without a tenant the dataset cannot be used at all. */
    public static final EntityDefinition TENANT = EntityDefinition.define("ItTenant", eb -> {
        eb.physicalTable("it_tenant");
        eb.primaryKey("tenantRowId");
        eb.field("tenantRowId", semanticIdentity("f_id", "urn:jabiz:entity:it:tenant"));
        eb.field("tenantId", f -> f.physicalColumn("f_tenant").asText(64));
        eb.field("name", f -> f.physicalColumn("f_name").asText(200));
        eb.field("rowVersion", rowVersion("f_version"));
    });

    /** Codes are unique (index uk_it_unique_code). */
    public static final EntityDefinition UNIQUE = EntityDefinition.define("ItUnique", eb -> {
        eb.physicalTable("it_unique");
        eb.primaryKey("uniqueId");
        eb.field("uniqueId", semanticIdentity("f_id", "urn:jabiz:entity:it:unique"));
        eb.field("code", f -> f.physicalColumn("f_code").required(true).asText(32));
        eb.field("rowVersion", rowVersion("f_version"));
        eb.unique("uk_it_unique_code", "code");
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
        EntityDefinition itTenantEntity() {
            return TENANT;
        }

        @Bean
        EntityDefinition itUniqueEntity() {
            return UNIQUE;
        }

        @Bean
        DatasetDefinition itTicketDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(TICKET_DATASET, d -> d
                .targetEntityType("ItTicket")
                .asDefault()
                .permissions("it.read", "it.write")
                .storage(s -> s.connectionPoolRef(pool))
                .policy(p -> p.maxWriteBatchSize(TICKET_MAX_WRITE_BATCH).maxQueryBatchSize(TICKET_MAX_QUERY_BATCH)));
        }

        @Bean
        DatasetDefinition itSoftDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(SOFT_DATASET, d -> d
                .targetEntityType("ItSoft")
                .asDefault()
                .permissions("it.read", "it.write")
                .storage(s -> s.connectionPoolRef(pool))
                .policy(p -> p.softDelete("deleted").softDeleteTimeField("deletedAt")));
        }

        @Bean
        DatasetDefinition itRegionalDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(REGIONAL_DATASET, d -> d
                .targetEntityType("ItRegional")
                .asDefault()
                .permissions("it.read", "it.write")
                .storage(s -> s.connectionPoolRef(pool))
                .scope(s -> s.fixed("region", "JP")));
        }

        @Bean
        DatasetDefinition itReadonlyDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(READONLY_DATASET, d -> d
                .targetEntityType("ItReadonly")
                .asDefault()
                .permissions("it.read", "it.write")
                .storage(s -> s.connectionPoolRef(pool))
                .policy(p -> p.readOnly(true)));
        }

        @Bean
        DatasetDefinition itTenantDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(TENANT_DATASET, d -> d
                .targetEntityType("ItTenant")
                .asDefault()
                .permissions("it.read", "it.write")
                .storage(s -> s.connectionPoolRef(pool))
                .scope(s -> s.fromContext("tenantId", RequestContext::tenantId)));
        }

        @Bean
        DatasetDefinition itUniqueDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(UNIQUE_DATASET, d -> d
                .targetEntityType("ItUnique")
                .asDefault()
                .permissions("it.read", "it.write")
                .storage(s -> s.connectionPoolRef(pool)));
        }
    }
}

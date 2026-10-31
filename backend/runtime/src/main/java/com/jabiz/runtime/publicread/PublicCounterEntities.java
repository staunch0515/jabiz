package com.jabiz.runtime.publicread;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The counts of the public counters as a read-only platform entity (docs/design/15-public-access.md section 10;
 * decision D65): one row per counter, key and day, with the key's owner for counters that declare one. The platform's
 * dataset needs {@value #READ}; an application gives its readers their share through datasets of its own (read-only,
 * scoped to the reader as {@code ownerKey}, e.g. {@code fromContext("ownerKey", RequestContext::actorId)}) and
 * templates.
 * Only {@link PublicCounters} writes the table.
 */
@Configuration
public class PublicCounterEntities {

    public static final String ENTITY = "PublicCounterDay";
    public static final String DATASET = "urn:jabiz:dataset:platform:PublicCounterDay";
    public static final String READ = "public-counter.read";
    /** The declared write permission of the read-only dataset; nothing writes through it. */
    public static final String WRITE = "public-counter.write";

    static final int MAX_ROWS = 1000;

    public static final EntityDefinition DAY_ENTITY = EntityDefinition.define(ENTITY, eb -> {
        eb.physicalTable("sys_public_counter_day");
        eb.primaryKey("rowId");
        eb.field("rowId", f -> f.physicalColumn("row_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:public-counter-day"));
        eb.field("counterName", f -> f.physicalColumn("counter_name").immutable(true).required(true).asText(60));
        eb.field("counterKey", f -> f.physicalColumn("counter_key").immutable(true).required(true).asText(100));
        eb.field("day", f -> f.physicalColumn("day").immutable(true).required(true).asDate());
        eb.field("hits", f -> f.physicalColumn("hits").required(true).asNumeric(18, 0));
        // Whom the key belongs to (the counter's owner field, decision D65 item 7); empty without one.
        eb.field("ownerKey", f -> f.physicalColumn("owner_key").immutable(true).asText(128));
        eb.listView("default", lv -> lv
            .columns("counterName", "counterKey", "ownerKey", "day", "hits")
            .filters("counterName", "counterKey", "ownerKey", "day")
            .sorts("day", "hits")
            .defaultSort("day", false));
    });

    @Bean
    EntityDefinition publicCounterDayEntity() {
        return DAY_ENTITY;
    }

    @Bean
    DatasetDefinition publicCounterDayDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(DATASET, d -> d
            .targetEntityType(ENTITY)
            .asDefault()
            .permissions(READ, WRITE)
            .policy(p -> p.readOnly(true).maxQueryBatchSize(MAX_ROWS))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}

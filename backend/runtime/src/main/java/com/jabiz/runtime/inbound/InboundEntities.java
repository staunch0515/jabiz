package com.jabiz.runtime.inbound;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Received inbound webhook events as a platform entity (docs/design/11-ledger-events-jobs.md section 2.5; decision
 * D37): {@code InboundEvent}, one row of the append-only {@code sys_inbound_event}, which the platform's receiver
 * inserts itself (like {@code MailAttempt}: a log of what arrived, not entity data, so no audit record). Its dataset
 * is read-only and needs {@code inbound.read}. The body is not a field, nor a column of the table: it is kept in
 * {@code sys_inbound_event_body}, so that lists, exports and reports carry its SHA-256 only, and the body itself, up
 * to 1 MB of what may be personal data, is read one event at a time through
 * {@code GET /api/inbound-events/{id}/body}. Applications may declare a retention policy counted from
 * {@code receivedTime}.
 */
@Configuration
public class InboundEntities {

    public static final String EVENT = "InboundEvent";
    public static final String EVENT_DATASET = "urn:jabiz:dataset:platform:InboundEvent";
    static final String TABLE = "sys_inbound_event";
    /** The bodies, one row per event, apart so that nothing reading the events carries them. */
    static final String BODY_TABLE = "sys_inbound_event_body";
    /** The unique key of (provider, external event id): a second arrival of an event fails on it. */
    public static final String EXTERNAL_KEY = "sys_inbound_event_external_key";

    static final int MAX_ROWS = 1000;

    public static final EntityDefinition EVENT_ENTITY = EntityDefinition.define(EVENT, eb -> {
        eb.physicalTable(TABLE);
        eb.primaryKey("inboundEventId");
        eb.field("inboundEventId", f -> f.physicalColumn("inbound_event_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:platform:inbound-event"));
        eb.field("provider", f -> f.physicalColumn("provider").immutable(true).required(true).asText(40));
        eb.field("externalEventId", f -> f.physicalColumn("external_event_id").immutable(true).required(true)
            .asText(255));
        eb.field("eventType", f -> f.physicalColumn("event_type").immutable(true).required(true).asText(100));
        eb.field("livemode", f -> f.physicalColumn("livemode").immutable(true).asBool());
        eb.field("signedTime", f -> f.physicalColumn("signed_time").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("receivedTime", f -> f.physicalColumn("received_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("contentType", f -> f.physicalColumn("content_type").immutable(true).asText(100));
        eb.field("bodySha256", f -> f.physicalColumn("body_sha256").immutable(true).required(true).asText(64));
        eb.field("processSeqId", f -> f.physicalColumn("process_seq_id").immutable(true).required(true)
            .asNumeric(19, 0));
        eb.listView("default", lv -> lv
            .columns("provider", "eventType", "externalEventId", "livemode", "receivedTime")
            .filters("provider", "eventType", "externalEventId", "receivedTime")
            .sorts("receivedTime")
            .defaultSort("receivedTime", false));
    });

    @Bean
    EntityDefinition inboundEventEntity() {
        return EVENT_ENTITY;
    }

    @Bean
    DatasetDefinition inboundEventDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        // Read-only: the receiver writes the table itself, outside the entity write path and its auditing
        // (docs/design/21-audit-retention.md section 1), like the mail attempts.
        return DatasetDefinition.define(EVENT_DATASET, d -> d
            .targetEntityType(EVENT)
            .asDefault()
            .permissions(InboundPermissions.READ, InboundPermissions.WRITE)
            .policy(p -> p.readOnly(true).maxQueryBatchSize(MAX_ROWS))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}

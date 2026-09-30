package com.jabiz.app.it.fixture;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.retention.RetentionPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Period;

/**
 * Entities with retention (docs/design/21-audit-retention.md section 3). Tables are created by
 * {@code db/testmigration/V1006__it_retention.sql}.
 */
public final class ItRetentionFixtures extends BaseEntityDefinitions {

    public static final String RECORD_DATASET = "urn:jabiz:dataset:it:ItRecord";
    public static final String DOCUMENT_DATASET = "urn:jabiz:dataset:it:ItDocument";

    /** Plain; kept seven years from the end of the fiscal year it was booked in. */
    public static final EntityDefinition RECORD = EntityDefinition.define("ItRecord", eb -> {
        eb.physicalTable("it_record");
        eb.primaryKey("recordId");
        eb.field("recordId", semanticIdentity("f_id", "urn:jabiz:entity:it:record"));
        eb.field("title", f -> f.physicalColumn("f_title").required(true).asText(200));
        eb.field("vendor", f -> f.physicalColumn("f_vendor").asText(20));
        eb.field("bookedTime", f -> f.physicalColumn("f_booked_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("rowVersion", rowVersion("f_version"));
    });

    /** Temporal; kept a year from its issue. */
    public static final EntityDefinition DOCUMENT = EntityDefinition.define("ItDocument", eb -> {
        eb.physicalTable("it_document");
        eb.primaryKey("documentId");
        eb.field("documentId", f -> f.physicalColumn("document_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:it:document"));
        eb.field("title", f -> f.physicalColumn("title").required(true).asText(200));
        eb.field("vendor", f -> f.physicalColumn("vendor").asText(20));
        eb.field("issuedTime", f -> f.physicalColumn("issued_time").asTemporal(TemporalRole.EVENT_TIME));
        eb.temporal();
    });

    private ItRetentionFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        EntityDefinition itRecordEntity() {
            return RECORD;
        }

        @Bean
        EntityDefinition itDocumentEntity() {
            return DOCUMENT;
        }

        @Bean
        DatasetDefinition itRecordDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(RECORD_DATASET, d -> d
                .targetEntityType("ItRecord")
                .asDefault()
                .permissions("it.read", "it.write")
                .storage(s -> s.connectionPoolRef(pool)));
        }

        @Bean
        DatasetDefinition itDocumentDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(DOCUMENT_DATASET, d -> d
                .targetEntityType("ItDocument")
                .asDefault()
                .permissions("it.read", "it.write")
                .storage(s -> s.connectionPoolRef(pool)));
        }

        @Bean
        RetentionPolicy itRecordRetention() {
            return RetentionPolicy.of("ItRecord").keep(Period.ofYears(7)).from("bookedTime").afterFiscalYearEnd();
        }

        @Bean
        RetentionPolicy itDocumentRetention() {
            return RetentionPolicy.of("ItDocument").keep(Period.ofYears(1)).from("issuedTime");
        }
    }
}

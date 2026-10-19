package com.jabiz.runtime.retention;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The append-only archives of the platform as read-only platform entities (ROADMAP 16v-1 item 8, the implementation
 * notes of decision D52; docs/design/21-audit-retention.md section 3.1), so that an application can declare how long
 * they are kept
 * ({@code RetentionPolicy.of("GeneratedFile")}, {@code RetentionPolicy.of("DocumentRun")}), see what is past its
 * retention in the report and place legal holds on them by id or by subject:
 * <ul>
 *   <li>{@value #GENERATED_FILE}: the files the server made and handed out ({@code sys_generated_file}, decision D31),
 *       counted from {@code createdTime};</li>
 *   <li>{@value #DOCUMENT_RUN}: the issued documents ({@code sys_document_run}, decision D30), counted from
 *       {@code issuedTime}.</li>
 * </ul>
 * Their fields say what was kept, of what and by whom, never what it says: no content, PDF, file name, title,
 * parameters or scope (those are read through the download and reprint APIs, which check the archive's own
 * permissions and record every read). Their datasets are read-only and need {@code audit.read}: who issued or archived
 * what, and when, is what the audit shows. The tables are append-only already, so nothing deletes their rows; the
 * report only says what is past its retention (decision D27 item 5).
 */
@Configuration
public class ArchiveEntities {

    public static final String GENERATED_FILE = "GeneratedFile";
    public static final String DOCUMENT_RUN = "DocumentRun";
    public static final String GENERATED_FILE_DATASET = "urn:jabiz:dataset:platform:GeneratedFile";
    public static final String DOCUMENT_RUN_DATASET = "urn:jabiz:dataset:platform:DocumentRun";

    /** Reading the archives' records: the audit's permission. */
    public static final String READ = "audit.read";
    /** The declared write permission of the read-only datasets; nothing writes through them. */
    public static final String WRITE = "audit.archive.write";

    static final int MAX_ROWS = 1000;

    public static final EntityDefinition GENERATED_FILE_ENTITY = EntityDefinition.define(GENERATED_FILE, eb -> {
        eb.physicalTable("sys_generated_file");
        eb.primaryKey("fileId");
        eb.field("fileId", f -> f.physicalColumn("file_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:generated-file"));
        eb.field("mediaType", f -> f.physicalColumn("media_type").immutable(true).required(true).asText(100));
        eb.field("size", f -> f.physicalColumn("size").immutable(true).required(true).asNumeric(9, 0));
        eb.field("sha256", f -> f.physicalColumn("sha256").immutable(true).required(true).asText(64));
        eb.field("subjectEntity", f -> f.physicalColumn("subject_entity").immutable(true).asText(100));
        eb.field("subjectId", f -> f.physicalColumn("subject_id").immutable(true).asText(100));
        eb.field("createdBy", f -> f.physicalColumn("created_by").immutable(true).required(true).asText(100));
        eb.field("createdTime", f -> f.physicalColumn("created_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("processSeqId", f -> f.physicalColumn("process_seq_id").immutable(true).required(true)
            .asNumeric(19, 0));
        eb.listView("default", lv -> lv
            .columns("mediaType", "size", "subjectEntity", "subjectId", "createdBy", "createdTime")
            .filters("mediaType", "subjectEntity", "subjectId", "createdBy", "createdTime")
            .sorts("createdTime", "size")
            .defaultSort("createdTime", false));
    });

    public static final EntityDefinition DOCUMENT_RUN_ENTITY = EntityDefinition.define(DOCUMENT_RUN, eb -> {
        eb.physicalTable("sys_document_run");
        eb.primaryKey("runId");
        eb.field("runId", f -> f.physicalColumn("run_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:document-run"));
        eb.field("layoutId", f -> f.physicalColumn("layout_id").immutable(true).required(true).asText(200));
        eb.field("pdfSize", f -> f.physicalColumn("pdf_size").immutable(true).required(true).asNumeric(9, 0));
        eb.field("pdfHash", f -> f.physicalColumn("pdf_hash").immutable(true).required(true).asText(64));
        eb.field("subjectEntity", f -> f.physicalColumn("subject_entity").immutable(true).asText(200));
        eb.field("subjectId", f -> f.physicalColumn("subject_id").immutable(true).asText(200));
        eb.field("issuedBy", f -> f.physicalColumn("issued_by").immutable(true).required(true).asText(100));
        eb.field("issuedTime", f -> f.physicalColumn("issued_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("processSeqId", f -> f.physicalColumn("process_seq_id").immutable(true).required(true)
            .asNumeric(19, 0));
        eb.listView("default", lv -> lv
            .columns("layoutId", "subjectEntity", "subjectId", "pdfSize", "issuedBy", "issuedTime")
            .filters("layoutId", "subjectEntity", "subjectId", "issuedBy", "issuedTime")
            .sorts("issuedTime", "pdfSize")
            .defaultSort("issuedTime", false));
    });

    @Bean
    EntityDefinition generatedFileEntity() {
        return GENERATED_FILE_ENTITY;
    }

    @Bean
    EntityDefinition documentRunEntity() {
        return DOCUMENT_RUN_ENTITY;
    }

    @Bean
    DatasetDefinition generatedFileDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(GENERATED_FILE_DATASET, GENERATED_FILE, poolRef);
    }

    @Bean
    DatasetDefinition documentRunDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(DOCUMENT_RUN_DATASET, DOCUMENT_RUN, poolRef);
    }

    private static DatasetDefinition dataset(String id, String entity, String poolRef) {
        // Read-only: FILE_ARCHIVE and DOCUMENT_ISSUE write these tables themselves, append-only.
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(READ, WRITE)
            .policy(p -> p.readOnly(true).maxQueryBatchSize(MAX_ROWS))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}

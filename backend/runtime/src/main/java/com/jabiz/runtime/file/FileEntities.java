package com.jabiz.runtime.file;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Uploaded files as the platform entity {@code SysFile} (docs/design/14-files.md section 2; decision D18): an ordinary
 * entity, not a temporal one, because a file must be really deleted when its owner asks. Every write is a process
 * (upload, delete, purge) with an operation record, so the audit trail stays complete. The storage key is derived
 * from {@code fileId} ({@link FileKeys}) and is not a field.
 */
@Configuration
public class FileEntities {

    public static final String ENTITY = "SysFile";
    public static final String DATASET = "urn:jabiz:dataset:platform:SysFile";

    public static final String FILE_ID = "fileId";
    public static final String POLICY = "policy";
    public static final String CONTENT_TYPE = "contentType";
    public static final String SIZE_BYTES = "sizeBytes";
    public static final String SHA256 = "sha256";
    public static final String WIDTH = "width";
    public static final String HEIGHT = "height";
    public static final String VARIANTS = "variants";
    public static final String ORIGINAL_NAME = "originalName";
    public static final String UPLOADED_BY = "uploadedBy";
    public static final String UPLOADED_TIME = "uploadedTime";

    public static final EntityDefinition SYS_FILE = EntityDefinition.define(ENTITY, eb -> {
        eb.physicalTable("sys_file");
        eb.primaryKey(FILE_ID);
        eb.field(FILE_ID, f -> f.physicalColumn("file_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:file"));
        eb.field(POLICY, f -> f.physicalColumn("policy").immutable(true).required(true).asText(100));
        eb.field(CONTENT_TYPE, f -> f.physicalColumn("content_type").immutable(true).required(true).asText(100));
        eb.field(SIZE_BYTES, f -> f.physicalColumn("size_bytes").immutable(true).required(true).asNumeric(19, 0));
        eb.field(SHA256, f -> f.physicalColumn("sha256").immutable(true).required(true).asText(64));
        eb.field(WIDTH, f -> f.physicalColumn("width").immutable(true).asNumeric(9, 0));
        eb.field(HEIGHT, f -> f.physicalColumn("height").immutable(true).asNumeric(9, 0));
        eb.field(VARIANTS, f -> f.physicalColumn("variants").immutable(true).asText(200));
        eb.field(ORIGINAL_NAME, f -> f.physicalColumn("original_name").immutable(true).required(true).asText(255));
        eb.field(UPLOADED_BY, f -> f.physicalColumn("uploaded_by").immutable(true).required(true).asText(64));
        eb.field(UPLOADED_TIME, f -> f.physicalColumn("uploaded_time").immutable(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("version", f -> f.physicalColumn("version").immutable(true).asVersion());
        eb.listView("default", lv -> lv
            .columns(ORIGINAL_NAME, POLICY, CONTENT_TYPE, SIZE_BYTES, UPLOADED_BY, UPLOADED_TIME)
            .filters(POLICY, CONTENT_TYPE, UPLOADED_BY)
            .sorts(UPLOADED_TIME, SIZE_BYTES)
            .defaultSort(UPLOADED_TIME, false));
    });

    @Bean
    EntityDefinition sysFileEntity() {
        return SYS_FILE;
    }

    /** Read by administrators; written only by the file service and the file processes. */
    @Bean
    DatasetDefinition sysFileDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(DATASET, d -> d
            .targetEntityType(ENTITY)
            .asDefault()
            .permissions(FilePermissions.READ, FilePermissions.WRITE)
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}

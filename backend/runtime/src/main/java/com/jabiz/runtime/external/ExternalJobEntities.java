package com.jabiz.runtime.external;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Asynchronous external jobs as platform entities (docs/design/11-ledger-events-jobs.md section 4a; decision D38):
 * {@code ExternalJob} (one row of {@code sys_external_job}, without its input), {@code ExternalJobAttempt},
 * {@code ExternalJobUsage} and {@code ExternalJobFile} (the files a job read and produced, decision D50). The platform inserts these rows itself, as a log of what the executor did (like the mail
 * attempts: not entity data, so no audit record); their datasets are read-only and need {@code external-job.read}.
 * The input and the result are not fields: they may be personal and are read by the job's processes only. A job's
 * status, which several tables make up, is read through {@code GET /api/external-jobs/{id}}.
 */
@Configuration
public class ExternalJobEntities {

    public static final String JOB = "ExternalJob";
    public static final String ATTEMPT = "ExternalJobAttempt";
    public static final String USAGE = "ExternalJobUsage";
    public static final String JOB_DATASET = "urn:jabiz:dataset:platform:ExternalJob";
    public static final String ATTEMPT_DATASET = "urn:jabiz:dataset:platform:ExternalJobAttempt";
    public static final String USAGE_DATASET = "urn:jabiz:dataset:platform:ExternalJobUsage";

    static final String JOB_TABLE = "sys_external_job";
    static final String CLAIM_TABLE = "sys_external_job_claim";
    static final String ATTEMPT_TABLE = "sys_external_job_attempt";
    static final String USAGE_TABLE = "sys_external_job_usage";
    static final String CANCEL_TABLE = "sys_external_job_cancel";
    static final String OUTCOME_TABLE = "sys_external_job_outcome";
    static final String FILE_TABLE = "sys_external_job_file";

    /** The files a job read and produced (decision D50): which job produced a file stays known after it is gone. */
    public static final String FILE = "ExternalJobFile";
    public static final String FILE_DATASET = "urn:jabiz:dataset:platform:ExternalJobFile";

    static final String ATTEMPT_OUTCOMES = "urn:jabiz:dict:external-job:attempt-outcome";
    static final String FILE_DIRECTIONS = "urn:jabiz:dict:external-job:file-direction";

    static final int MAX_ROWS = 1000;

    public static final EntityDefinition JOB_ENTITY = EntityDefinition.define(JOB, eb -> {
        eb.physicalTable(JOB_TABLE);
        eb.primaryKey("jobId");
        eb.field("jobId", f -> f.physicalColumn("job_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:external-job"));
        eb.field("jobSeq", f -> f.physicalColumn("job_seq").immutable(true).asNumeric(19, 0));
        eb.field("jobType", f -> f.physicalColumn("job_type").immutable(true).required(true).asText(100));
        eb.field("reference", f -> f.physicalColumn("reference").immutable(true).asText(200));
        eb.field("requestedBy", f -> f.physicalColumn("requested_by").immutable(true).required(true).asText(100));
        eb.field("processSeqId", f -> f.physicalColumn("process_seq_id").immutable(true).required(true)
            .asNumeric(19, 0));
        eb.field("createdTime", f -> f.physicalColumn("created_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.listView("default", lv -> lv
            .columns("jobType", "reference", "requestedBy", "createdTime")
            .filters("jobType", "reference", "requestedBy", "createdTime")
            .sorts("createdTime", "jobSeq")
            .defaultSort("jobSeq", false));
    });

    public static final EntityDefinition ATTEMPT_ENTITY = EntityDefinition.define(ATTEMPT, eb -> {
        eb.physicalTable(ATTEMPT_TABLE);
        eb.primaryKey("attemptId");
        eb.field("attemptId", f -> f.physicalColumn("attempt_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:external-job-attempt"));
        eb.field("jobId", f -> f.physicalColumn("job_id").immutable(true).required(true).asReference(JOB));
        eb.field("attemptNo", f -> f.physicalColumn("attempt_no").immutable(true).required(true).asNumeric(9, 0));
        eb.field("outcome", f -> f.physicalColumn("outcome").immutable(true).required(true)
            .asCode(ATTEMPT_OUTCOMES, "SUCCEEDED", "FAILED", "TIMED_OUT", "LEASE_EXPIRED"));
        eb.field("retryable", f -> f.physicalColumn("retryable").immutable(true).required(true).asBool());
        eb.field("errorCode", f -> f.physicalColumn("error_code").immutable(true).asText(100));
        eb.field("errorDetail", f -> f.physicalColumn("error_detail").immutable(true).asText(2000, true));
        eb.field("durationMs", f -> f.physicalColumn("duration_ms").immutable(true).asNumeric(19, 0));
        eb.field("finishedTime", f -> f.physicalColumn("finished_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.listView("default", lv -> lv
            .columns("jobId", "attemptNo", "outcome", "retryable", "errorCode", "durationMs", "finishedTime")
            .filters("jobId", "outcome", "errorCode", "finishedTime")
            .sorts("finishedTime", "attemptNo")
            .defaultSort("finishedTime", false));
    });

    public static final EntityDefinition USAGE_ENTITY = EntityDefinition.define(USAGE, eb -> {
        eb.physicalTable(USAGE_TABLE);
        eb.primaryKey("usageId");
        eb.field("usageId", f -> f.physicalColumn("usage_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:external-job-usage"));
        eb.field("jobId", f -> f.physicalColumn("job_id").immutable(true).required(true).asReference(JOB));
        eb.field("attemptNo", f -> f.physicalColumn("attempt_no").immutable(true).required(true).asNumeric(9, 0));
        eb.field("metric", f -> f.physicalColumn("metric").immutable(true).required(true).asText(64));
        eb.field("quantity", f -> f.physicalColumn("quantity").immutable(true).required(true).asNumeric(19, 0));
        eb.listView("default", lv -> lv
            .columns("jobId", "attemptNo", "metric", "quantity")
            .filters("jobId", "metric")
            .sorts("attemptNo", "metric")
            .defaultSort("attemptNo", false));
    });

    public static final EntityDefinition FILE_ENTITY = EntityDefinition.define(FILE, eb -> {
        eb.physicalTable(FILE_TABLE);
        eb.primaryKey("jobFileId");
        eb.field("jobFileId", f -> f.physicalColumn("job_file_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:external-job-file"));
        eb.field("jobId", f -> f.physicalColumn("job_id").immutable(true).required(true).asReference(JOB));
        eb.field("direction", f -> f.physicalColumn("direction").immutable(true).required(true)
            .asCode(FILE_DIRECTIONS, "INPUT", "OUTPUT"));
        eb.field("position", f -> f.physicalColumn("position").immutable(true).required(true).asNumeric(9, 0));
        eb.field("attemptNo", f -> f.physicalColumn("attempt_no").immutable(true).asNumeric(9, 0));
        eb.field("outputKey", f -> f.physicalColumn("output_key").immutable(true).asText(64));
        eb.field("fileId", f -> f.physicalColumn("file_id").immutable(true).required(true).asText(36));
        eb.field("policy", f -> f.physicalColumn("policy").immutable(true).required(true).asText(100));
        eb.field("sizeBytes", f -> f.physicalColumn("size_bytes").immutable(true).required(true).asNumeric(19, 0));
        eb.field("sha256", f -> f.physicalColumn("sha256").immutable(true).required(true).asText(64));
        eb.field("recordedTime", f -> f.physicalColumn("recorded_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.listView("default", lv -> lv
            .columns("jobId", "direction", "attemptNo", "outputKey", "fileId", "policy", "sizeBytes", "recordedTime")
            .filters("jobId", "direction", "fileId", "policy", "recordedTime")
            .sorts("recordedTime", "position")
            .defaultSort("recordedTime", false));
    });

    @Bean
    EntityDefinition externalJobFileEntity() {
        return FILE_ENTITY;
    }

    @Bean
    DatasetDefinition externalJobFileDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(FILE_DATASET, FILE, poolRef);
    }

    @Bean
    EntityDefinition externalJobEntity() {
        return JOB_ENTITY;
    }

    @Bean
    EntityDefinition externalJobAttemptEntity() {
        return ATTEMPT_ENTITY;
    }

    @Bean
    EntityDefinition externalJobUsageEntity() {
        return USAGE_ENTITY;
    }

    @Bean
    DatasetDefinition externalJobDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(JOB_DATASET, JOB, poolRef);
    }

    @Bean
    DatasetDefinition externalJobAttemptDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(ATTEMPT_DATASET, ATTEMPT, poolRef);
    }

    @Bean
    DatasetDefinition externalJobUsageDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(USAGE_DATASET, USAGE, poolRef);
    }

    private static DatasetDefinition dataset(String id, String entity, String poolRef) {
        // Read-only: the executor writes these tables itself, outside the entity write path and its auditing
        // (docs/design/21-audit-retention.md section 1), like the mail attempts.
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(ExternalJobPermissions.READ, ExternalJobPermissions.WRITE)
            .policy(p -> p.readOnly(true).maxQueryBatchSize(MAX_ROWS))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}

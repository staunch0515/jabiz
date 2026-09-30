package com.jabiz.runtime.report;

import com.jabiz.query.BoundValue;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The store of issued reports (docs/design/19-reports.md section 5): {@code sys_report_run} and
 * {@code sys_report_run_supersede}, both append-only (decision D5). Written by the {@code REPORT_ISSUE} process only;
 * read by the archive API, which checks permissions.
 */
@Component
public class ReportRuns {

    static final String RUNS = "sys_report_run";
    static final String SUPERSEDES = "sys_report_run_supersede";

    private static final String SUMMARY_COLUMNS = "r.run_id, r.template_id, r.template_version, r.permissions, r.scope,"
        + " r.title, r.company, r.period, r.language, r.params, r.parameters, r.as_of, r.read_at, r.known_at, r.landscape,"
        + " r.columns, r.row_count, r.content_hash, r.recomputable, r.issued_by, r.issued_time, r.process_seq_id,"
        + " s.superseded_by";
    private static final String FROM = " FROM sys_report_run r"
        + " LEFT JOIN sys_report_run_supersede s ON s.run_id = r.run_id";

    private final StorageAdapterRegistry storages;
    private final String poolRef;

    public ReportRuns(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.poolRef = poolRef;
    }

    private StorageEngine engine() {
        return storages.getEngine(poolRef);
    }

    Mono<Void> insert(ReportRun run) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", run.runId());
        row.put("template_id", run.templateId());
        row.put("template_version", run.templateVersion());
        row.put("template_source", run.templateSource());
        row.put("permissions", String.join(",", run.permissions()));
        row.put("scope", ArchivedValues.scopeJson(run.scope()));
        row.put("title", run.title());
        row.put("company", run.company());
        row.put("period", run.period());
        row.put("language", run.language());
        row.put("params", ArchivedValues.paramsJson(run.params()));
        row.put("parameters", ArchivedValues.parametersJson(run.parameters()));
        row.put("as_of", run.asOf());
        row.put("read_at", run.readAt());
        row.put("known_at", run.knownAt());
        row.put("landscape", run.landscape());
        row.put("columns", ArchivedValues.columnsJson(run.columns()));
        row.put("rows", ArchivedValues.rowsJson(run.columns(), run.rows()));
        row.put("row_count", run.rowCount());
        row.put("content_hash", run.contentHash());
        row.put("recomputable", run.recomputable());
        row.put("issued_by", run.issuedBy());
        row.put("issued_time", run.issuedTime());
        row.put("process_seq_id", run.processSeqId());
        row.put("version", 1L);
        return engine().insert(RUNS, row);
    }

    Mono<Void> supersede(UUID runId, UUID supersededBy, Instant time, long processSeqId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", runId);
        row.put("superseded_by", supersededBy);
        row.put("superseded_time", time);
        row.put("process_seq_id", processSeqId);
        return engine().insert(SUPERSEDES, row);
    }

    /** The run with its rows. */
    public Mono<ReportRun> find(UUID runId) {
        return engine().select("SELECT " + SUMMARY_COLUMNS + ", r.template_source, r.rows" + FROM
                + " WHERE r.run_id = :id", Map.of("id", BoundValue.of(runId)))
            .next()
            .map(row -> run(row, true));
    }

    /**
     * The runs, newest first, without their rows or template text; read as far as the subscriber takes them.
     *
     * @param templateId only runs of this template, or null for all
     */
    public Flux<ReportRun> latest(String templateId) {
        return engine().select("SELECT " + SUMMARY_COLUMNS + FROM
                + " WHERE (CAST(:template AS varchar) IS NULL OR r.template_id = :template)"
                + " ORDER BY r.issued_time DESC, r.run_id DESC",
                Map.of("template", templateId == null ? BoundValue.nullOf(String.class) : BoundValue.of(templateId)))
            .map(row -> run(row, false));
    }

    /** The runs issued in {@code [from, to)} (either open when null), oldest first, without their rows. */
    public Flux<ReportRun> issuedBetween(Instant from, Instant to) {
        return engine().select("SELECT " + SUMMARY_COLUMNS + FROM
                + " WHERE (CAST(:from AS timestamptz) IS NULL OR r.issued_time >= :from)"
                + " AND (CAST(:to AS timestamptz) IS NULL OR r.issued_time < :to)"
                + " ORDER BY r.issued_time, r.run_id",
                Map.of("from", from == null ? BoundValue.nullOf(Instant.class) : BoundValue.of(from),
                    "to", to == null ? BoundValue.nullOf(Instant.class) : BoundValue.of(to)))
            .map(row -> run(row, false));
    }

    private static ReportRun run(Map<String, Object> row, boolean full) {
        var columns = ArchivedValues.columns((String) row.get("columns"));
        String permissions = (String) row.get("permissions");
        return new ReportRun((UUID) row.get("run_id"), (String) row.get("template_id"),
            ((String) row.get("template_version")).trim(), full ? (String) row.get("template_source") : null,
            permissions == null || permissions.isEmpty() ? List.of() : Arrays.asList(permissions.split(",")),
            ArchivedValues.scope((String) row.get("scope")), (String) row.get("title"), (String) row.get("company"), (String) row.get("period"),
            (String) row.get("language"), ArchivedValues.params((String) row.get("params")),
            ArchivedValues.parameters((String) row.get("parameters")), instant(row.get("as_of")),
            instant(row.get("read_at")), instant(row.get("known_at")), Boolean.TRUE.equals(row.get("landscape")),
            columns,
            full ? ArchivedValues.rows(columns, (String) row.get("rows")) : List.of(),
            ((Number) row.get("row_count")).intValue(), ((String) row.get("content_hash")).trim(),
            Boolean.TRUE.equals(row.get("recomputable")), (String) row.get("issued_by"),
            instant(row.get("issued_time")), ((Number) row.get("process_seq_id")).longValue(),
            (UUID) row.get("superseded_by"));
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant i -> i;
            case OffsetDateTime t -> t.toInstant();
            default -> throw new IllegalStateException("Unexpected time " + value.getClass());
        };
    }
}

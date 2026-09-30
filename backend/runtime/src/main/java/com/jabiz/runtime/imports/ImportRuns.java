package com.jabiz.runtime.imports;

import com.jabiz.imports.ImportIssue;
import com.jabiz.imports.ImportMapping;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The store of imports (docs/design/20-imports.md section 5): {@code sys_import_run} and {@code sys_import_ref},
 * both append-only (decision D5). Written by {@code IMPORT_RUN} only, in its transaction; read by the import API,
 * which checks permissions.
 */
@Component
public class ImportRuns {

    static final String RUNS = "sys_import_run";
    static final String REFS = "sys_import_ref";
    /** The unique indexes a concurrent commit of the same file or reference runs into. */
    static final Set<String> DUPLICATE_CONSTRAINTS = Set.of("sys_import_run_file_uk", "sys_import_ref_pkey");

    private static final String COLUMNS = "run_id, import_id, import_version, outcome, file_id, file_sha256, mapping,"
        + " params, record_count, row_count, unit_count, processed_count, duplicate_count, issue_count, columns, totals,"
        + " notes, imported_by, imported_time, process_seq_id";

    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final JsonMapper json;

    ImportRuns(StorageAdapterRegistry storages, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        JsonMapper json) {
        this.storages = storages;
        this.poolRef = poolRef;
        this.json = json;
    }

    private StorageEngine engine() {
        return storages.getEngine(poolRef);
    }

    Mono<Void> insert(ImportRun run) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", run.runId());
        row.put("import_id", run.importId());
        row.put("import_version", run.importVersion());
        row.put("outcome", run.outcome());
        row.put("file_id", run.fileId());
        row.put("file_sha256", run.sha256());
        row.put("mapping", json.writeValueAsString(run.mapping()));
        row.put("params", json.writeValueAsString(run.params()));
        row.put("record_count", run.recordCount());
        row.put("row_count", run.rowCount());
        row.put("unit_count", run.unitCount());
        row.put("processed_count", run.processedCount());
        row.put("duplicate_count", run.duplicateCount());
        row.put("issue_count", run.issueCount());
        row.put("columns", json.writeValueAsString(run.columns()));
        // Totals as text, so that their scale survives.
        Map<String, String> totals = new LinkedHashMap<>();
        run.totals().forEach((field, total) -> totals.put(field, total.toPlainString()));
        row.put("totals", json.writeValueAsString(totals));
        row.put("issues", json.writeValueAsString(run.issues()));
        row.put("notes", run.notes());
        row.put("imported_by", run.importedBy());
        row.put("imported_time", run.importedTime());
        row.put("process_seq_id", run.processSeqId());
        return engine().insert(RUNS, row);
    }

    Mono<Void> insertRef(String importId, String ref, UUID runId, int rowNumber, long processSeqId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("import_id", importId);
        row.put("ref", ref);
        row.put("run_id", runId);
        row.put("row_number", rowNumber);
        row.put("process_seq_id", processSeqId);
        return engine().insert(REFS, row);
    }

    /** Which of {@code refs} the import has imported before. */
    Mono<Set<String>> knownRefs(String importId, Set<String> refs) {
        if (refs.isEmpty()) {
            return Mono.just(Set.of());
        }
        return engine().select("SELECT ref FROM " + REFS + " WHERE import_id = :import AND ref = ANY(:refs)",
                Map.of("import", BoundValue.of(importId), "refs", BoundValue.of(refs.toArray(String[]::new))))
            .map(row -> (String) row.get("ref"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Holds, until the transaction ends, the lock of committing this file to this import: a second commit of the same
     * file waits and then finds the first one's record.
     */
    Mono<Void> lockFile(String importId, String sha256) {
        return engine().select("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0)) AS locked",
                Map.of("key", BoundValue.of("jabiz.import:" + importId + ":" + sha256)))
            .then();
    }

    /** The committed run of this file, if the import has one. */
    Mono<Optional<UUID>> committedRun(String importId, String sha256) {
        return engine().select("SELECT run_id FROM " + RUNS + " WHERE import_id = :import AND file_sha256 = :sha"
                    + " AND outcome = 'committed'",
                Map.of("import", BoundValue.of(importId), "sha", BoundValue.of(sha256)))
            .next()
            .map(row -> Optional.of((UUID) row.get("run_id")))
            .defaultIfEmpty(Optional.empty());
    }

    /** The run with its problems. */
    public Mono<ImportRun> find(UUID runId) {
        return engine().select("SELECT " + COLUMNS + ", issues FROM " + RUNS + " WHERE run_id = :id",
                Map.of("id", BoundValue.of(runId)))
            .next()
            .map(row -> run(row, true));
    }

    /**
     * The runs, newest first, without their problems; read as far as the subscriber takes them.
     *
     * @param importId only runs of this import, or null for all
     */
    public Flux<ImportRun> latest(String importId) {
        return engine().select("SELECT " + COLUMNS + " FROM " + RUNS
                    + " WHERE (CAST(:import AS varchar) IS NULL OR import_id = :import)"
                    + " ORDER BY imported_time DESC, run_id DESC",
                Map.of("import", importId == null ? BoundValue.nullOf(String.class) : BoundValue.of(importId)))
            .map(row -> run(row, false));
    }

    private ImportRun run(Map<String, Object> row, boolean full) {
        Map<String, String> totalsText = json.readValue((String) row.get("totals"), new TypeReference<>() {});
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totalsText.forEach((field, total) -> totals.put(field, new BigDecimal(total)));
        List<ImportIssue> issues = full ? json.readValue((String) row.get("issues"), new TypeReference<>() {})
            : List.of();
        return new ImportRun((UUID) row.get("run_id"), (String) row.get("import_id"),
            ((Number) row.get("import_version")).intValue(), (String) row.get("outcome"), (UUID) row.get("file_id"),
            ((String) row.get("file_sha256")).trim(), json.readValue((String) row.get("mapping"), ImportMapping.class),
            json.readValue((String) row.get("params"), new TypeReference<Map<String, Object>>() {}),
            number(row, "record_count"), number(row, "row_count"), number(row, "unit_count"),
            number(row, "processed_count"), number(row, "duplicate_count"), number(row, "issue_count"),
            json.readValue((String) row.get("columns"), new TypeReference<Map<String, String>>() {}), totals, issues,
            (String) row.get("notes"), (String) row.get("imported_by"), instant(row.get("imported_time")),
            ((Number) row.get("process_seq_id")).longValue());
    }

    private static int number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
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

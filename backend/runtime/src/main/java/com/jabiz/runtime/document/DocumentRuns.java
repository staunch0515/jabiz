package com.jabiz.runtime.document;

import com.jabiz.document.DocumentContent;
import com.jabiz.document.DocumentLayout;
import com.jabiz.query.BoundValue;
import com.jabiz.report.ReportColumn;
import com.jabiz.runtime.report.ArchivedValues;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The store of issued documents (docs/design/22-documents.md section 4): {@code sys_document_run}, append-only
 * (decision D5). Written by {@code DOCUMENT_ISSUE} only; read by the document API, which checks permissions.
 */
@Component
public class DocumentRuns {

    static final String RUNS = "sys_document_run";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SUMMARY_COLUMNS = "run_id, layout_id, layout_version, template_versions, permissions,"
        + " scope, subject_entity, subject_id, document_no, title, language, page_size, params, as_of, read_at,"
        + " known_at, recipients, content_hash, recomputable, pdf_hash, pdf_size, page_count, issued_by, issued_time,"
        + " process_seq_id";

    private final StorageAdapterRegistry storages;
    private final String poolRef;

    public DocumentRuns(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.poolRef = poolRef;
    }

    private StorageEngine engine() {
        return storages.getEngine(poolRef);
    }

    Mono<Void> insert(DocumentRun run) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", run.runId());
        row.put("layout_id", run.layoutId());
        row.put("layout_version", run.layoutVersion());
        row.put("layout_source", run.layoutSource());
        row.put("template_versions", JSON.writeValueAsString(new TreeMap<>(run.templateVersions())));
        row.put("permissions", String.join(",", run.permissions()));
        row.put("scope", ArchivedValues.scopeJson(run.scope()));
        row.put("subject_entity", run.subjectEntity());
        row.put("subject_id", run.subjectId());
        row.put("document_no", run.documentNo());
        row.put("title", run.title());
        row.put("language", run.language());
        row.put("page_size", run.pageSize());
        row.put("params", ArchivedValues.paramsJson(run.params()));
        row.put("as_of", run.asOf());
        row.put("read_at", run.readAt());
        row.put("known_at", run.knownAt());
        row.put("recipients", JSON.writeValueAsString(run.recipients()));
        row.put("content", contentJson(run.content()));
        row.put("content_hash", run.contentHash());
        row.put("recomputable", run.recomputable());
        row.put("pdf", run.pdf());
        row.put("pdf_hash", run.pdfHash());
        row.put("pdf_size", run.pdfSize());
        row.put("page_count", run.pageCount());
        row.put("issued_by", run.issuedBy());
        row.put("issued_time", run.issuedTime());
        row.put("process_seq_id", run.processSeqId());
        row.put("version", 1L);
        return engine().insert(RUNS, row);
    }

    /** The document with its content and PDF. */
    public Mono<DocumentRun> find(UUID runId) {
        return engine().select("SELECT " + SUMMARY_COLUMNS + ", layout_source, content, pdf FROM " + RUNS
                + " WHERE run_id = :id", Map.of("id", BoundValue.of(runId)))
            .next()
            .map(row -> run(row, true));
    }

    /**
     * Documents newest first, without content or PDF; read as far as the subscriber takes them.
     *
     * @param layoutId only of this layout, or null
     * @param subject  only about this subject id, or null
     */
    public Flux<DocumentRun> latest(String layoutId, String subject) {
        return engine().select("SELECT " + SUMMARY_COLUMNS + " FROM " + RUNS
                + " WHERE (CAST(:layout AS varchar) IS NULL OR layout_id = :layout)"
                + " AND (CAST(:subject AS varchar) IS NULL OR subject_id = :subject)"
                + " ORDER BY issued_time DESC, run_id DESC",
                Map.of("layout", text(layoutId), "subject", text(subject)))
            .map(row -> run(row, false));
    }

    private static BoundValue text(String value) {
        return value == null ? BoundValue.nullOf(String.class) : BoundValue.of(value);
    }

    private static DocumentRun run(Map<String, Object> row, boolean full) {
        String permissions = (String) row.get("permissions");
        Map<String, String> versions = JSON.readValue((String) row.get("template_versions"),
            new TypeReference<TreeMap<String, String>>() {});
        return new DocumentRun((UUID) row.get("run_id"), (String) row.get("layout_id"),
            ((String) row.get("layout_version")).trim(), full ? (String) row.get("layout_source") : null, versions,
            permissions == null || permissions.isEmpty() ? List.of() : Arrays.asList(permissions.split(",")),
            ArchivedValues.scope((String) row.get("scope")), (String) row.get("subject_entity"),
            (String) row.get("subject_id"), (String) row.get("document_no"), (String) row.get("title"),
            (String) row.get("language"), (String) row.get("page_size"),
            ArchivedValues.params((String) row.get("params")), instant(row.get("as_of")),
            instant(row.get("read_at")), instant(row.get("known_at")), recipients((String) row.get("recipients")),
            full ? content((String) row.get("content")) : null, ((String) row.get("content_hash")).trim(),
            Boolean.TRUE.equals(row.get("recomputable")), full ? bytes(row.get("pdf")) : null,
            ((String) row.get("pdf_hash")).trim(), ((Number) row.get("pdf_size")).intValue(),
            ((Number) row.get("page_count")).intValue(), (String) row.get("issued_by"),
            instant(row.get("issued_time")), ((Number) row.get("process_seq_id")).longValue());
    }

    /** The layout's canonical description as JSON text, archived with every document it lays out. */
    static String layoutSource(DocumentLayout layout) {
        return JSON.writeValueAsString(layout.describe());
    }

    /** What a document shows as stored: values normalized, decimals and times as strings. */
    static String contentJson(DocumentContent content) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("layoutId", content.layoutId());
        stored.put("layoutVersion", content.layoutVersion());
        stored.put("title", content.title());
        stored.put("number", content.number());
        stored.put("company", content.company());
        stored.put("language", content.language());
        stored.put("issuedTime", content.issuedTime().toString());
        stored.put("labels", new TreeMap<>(content.labels()));
        List<Map<String, Object>> sources = new ArrayList<>();
        for (DocumentContent.Source source : content.sources().values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("templateId", source.templateId());
            entry.put("templateVersion", source.templateVersion());
            entry.put("columns", ArchivedValues.columnsData(source.columns()));
            entry.put("rows", ArchivedValues.rowsData(source.columns(), source.rows()));
            sources.add(entry);
        }
        stored.put("sources", sources);
        return JSON.writeValueAsString(stored);
    }

    @SuppressWarnings("unchecked")
    static DocumentContent content(String json) {
        Map<String, Object> stored = JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        Map<String, DocumentContent.Source> sources = new LinkedHashMap<>();
        for (Map<String, Object> entry : (List<Map<String, Object>>) stored.get("sources")) {
            List<ReportColumn> columns = ArchivedValues.columnsFrom((List<Map<String, Object>>) entry.get("columns"));
            List<List<Object>> rows = ArchivedValues.normalize(columns, (List<List<Object>>) entry.get("rows"));
            String id = (String) entry.get("templateId");
            sources.put(id, new DocumentContent.Source(id, (String) entry.get("templateVersion"), columns, rows));
        }
        return new DocumentContent((String) stored.get("layoutId"), (String) stored.get("layoutVersion"),
            (String) stored.get("title"), (String) stored.get("number"), (String) stored.get("company"),
            (String) stored.get("language"), Instant.parse((String) stored.get("issuedTime")), false,
            (Map<String, String>) stored.get("labels"), sources);
    }

    /** Documents issued before recipients were kept have none. */
    private static List<String> recipients(String json) {
        return json == null ? List.of() : JSON.readValue(json, new TypeReference<List<String>>() {});
    }

    private static byte[] bytes(Object value) {
        return switch (value) {
            case null -> null;
            case byte[] b -> b;
            case ByteBuffer buffer -> {
                byte[] copy = new byte[buffer.remaining()];
                buffer.duplicate().get(copy);
                yield copy;
            }
            default -> throw new IllegalStateException("Unexpected bytes " + value.getClass());
        };
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

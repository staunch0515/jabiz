package com.jabiz.runtime.export;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.MetaModelExporter;
import com.jabiz.export.OpenCsv;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.integrity.IntegrityStore;
import com.jabiz.runtime.operation.OperationRecorder;
import com.jabiz.runtime.operation.OperationRequest;
import com.jabiz.runtime.report.ReportExporter;
import com.jabiz.runtime.report.ReportRun;
import com.jabiz.runtime.report.ReportRuns;
import com.jabiz.runtime.security.MaskedFields;
import com.jabiz.runtime.security.RevealRecorder;
import com.jabiz.runtime.security.SensitiveDataMasker;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes the open-format export (docs/design/21-audit-retention.md section 4): a ZIP with one CSV per dataset (its
 * entries as the caller may read them, sensitive fields left out, in primary key order), the archived PDFs of the
 * reports issued in a period, {@code schema.json} describing every column, and {@code manifest.json} with each file's
 * SHA-256, the rows, the parameters, the platform version and the head of the seal chain. The data is read page by
 * page through the datasets, so their permissions, scopes and time travel apply; the ZIP is written to a temporary
 * file on {@code boundedElastic}, whatever its size.
 */
@Component
public class DataExporter {

    public static final String PROCESS_NAME = "DATA_EXPORT";
    static final String FORMAT = "jabiz-open-export/1";

    /**
     * What to export; the caller checked it (see {@code ExportController}).
     *
     * @param readAt the moment the export reads temporal datasets at, unless {@code asOf} / {@code knownAt} say
     *               otherwise: one snapshot for all their pages
     */
    public record Plan(List<DatasetDefinition> datasets, Instant asOf, Instant knownAt, Instant readAt,
        boolean reports, Instant reportsFrom, Instant reportsTo, Predicate<ReportRun> readableRun) {}

    private final DatasetEntityManager entities;
    private final EntityDefinitionRegistry definitions;
    private final SensitiveDataMasker masker;
    private final MessageCatalog messages;
    private final ReportRuns runs;
    private final ReportExporter reports;
    private final IntegrityStore integrity;
    private final OperationRecorder operations;
    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final JsonMapper json;
    private final Clock clock;
    private final RevealRecorder reveals;

    public DataExporter(DatasetEntityManager entities, EntityDefinitionRegistry definitions,
        SensitiveDataMasker masker, MessageCatalog messages, ReportRuns runs, ReportExporter reports,
        IntegrityStore integrity, OperationRecorder operations, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef, JsonMapper json, Clock clock,
        RevealRecorder reveals) {
        this.reveals = reveals;
        this.entities = entities;
        this.definitions = definitions;
        this.masker = masker;
        this.messages = messages;
        this.runs = runs;
        this.reports = reports;
        this.integrity = integrity;
        this.operations = operations;
        this.storages = storages;
        this.poolRef = poolRef;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Records the export as an operation (who exported what), then writes the ZIP; the caller streams and deletes
     * the file.
     */
    public Mono<Path> export(Plan plan, RequestContext request) {
        StorageEngine engine = storages.getEngine(poolRef);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("datasets", plan.datasets().stream().map(DatasetDefinition::resourceId).toList());
        summary.put("asOf", plan.asOf());
        summary.put("knownAt", plan.knownAt());
        summary.put("reports", plan.reports());
        OperationRequest operation = OperationRequest.named(PROCESS_NAME, 1)
            .withInputSummary(json.writeValueAsString(summary));
        return engine.inTransaction(operations.beginOrJoin(engine, operation, request))
            .flatMap(started -> Mono.fromCallable(() -> new Archive(Files.createTempFile("jabiz-export-", ".zip")))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(archive -> write(plan, request, archive, started.processSeqId(), engine)
                    .then(blocking(archive::close))
                    .thenReturn(archive.path)
                    .onErrorResume(error -> blocking(archive::discard).then(Mono.error(error)))
                    // A client gone before the ZIP is complete leaves no file behind either.
                    .doOnCancel(() -> Schedulers.boundedElastic().schedule(archive::discardQuietly))));
    }

    private Mono<Void> write(Plan plan, RequestContext request, Archive archive, long processSeqId,
        StorageEngine engine) {
        List<Map<String, Object>> schema = new ArrayList<>();
        return Flux.fromIterable(plan.datasets())
            .concatMap(dataset -> dataset(plan, request, dataset, archive, schema))
            .then(plan.reports() ? reports(plan, request, archive) : Mono.empty())
            .then(blocking(() -> archive.json("schema.json", Map.of("format", FORMAT, "entities", schema))))
            .then(integrity.head(engine).map(head -> {
                Map<String, Object> seal = new LinkedHashMap<>();
                seal.put("sealNo", head.block().sealNo());
                seal.put("sealHash", head.storedHash());
                seal.put("keyId", head.block().keyId());
                return seal;
            }).defaultIfEmpty(Map.of()))
            .flatMap(head -> blocking(() -> archive.json("manifest.json", manifest(plan, request, processSeqId,
                head, archive.files))));
    }

    /**
     * One dataset as CSV, page by page in primary key order, and its description for {@code schema.json}. Masked
     * fields are exported masked, except to holders of their permissions, whose export is recorded
     * (docs/design/10-security.md section 13.1).
     */
    private Mono<Void> dataset(Plan plan, RequestContext request, DatasetDefinition dataset, Archive archive,
        List<Map<String, Object>> schema) {
        EntityDefinition def = definitions.getOrThrow(dataset.targetEntityType());
        Set<String> plain = MaskedFields.plainFor(request, def);
        long[] exported = {0};
        List<FieldDefinition> fields = def.fields.values().stream().filter(field -> !field.sensitive()).toList();
        String file = "data/" + dataset.resourceId().replaceAll("[^A-Za-z0-9._-]", "_") + ".csv";
        int batch = dataset.policy().maxQueryBatchSize();
        // Temporal datasets are read at one moment throughout; others as they are (they have no other time), and so
        // are the temporal datasets showing the current state only (the caller cannot ask them another time).
        boolean pinned = def.temporal && dataset.policy().allowTimeTravel();
        Instant asOf = pinned ? firstNonNull(plan.asOf(), plan.readAt()) : null;
        Instant knownAt = pinned ? firstNonNull(plan.knownAt(), plan.readAt()) : null;
        schema.add(describe(def, dataset, file, fields));
        return blocking(() -> archive.begin(file, OpenCsv.line(fields.stream().map(FieldDefinition::name).toList())))
            // Pages follow the key, not an offset: rows deleted or added meanwhile shift nothing.
            .thenMany(page(dataset, def, asOf, knownAt, null, batch)
                .expand(rows -> rows.size() < batch ? Mono.empty()
                    : page(dataset, def, asOf, knownAt, rows.getLast().id(), batch)))
            .concatMap(rows -> blocking(() -> {
                exported[0] += rows.size();
                for (EntityInstance stored : rows) {
                    EntityInstance row = masker.hide(stored, plain);
                    List<String> cells = new ArrayList<>(fields.size());
                    for (FieldDefinition field : fields) {
                        cells.add(OpenCsv.value(row.attributes().get(field.name()), json::writeValueAsString));
                    }
                    archive.row(OpenCsv.line(cells));
                }
            }))
            .then(blocking(archive::end))
            .then(Mono.defer(() -> plain.isEmpty() ? Mono.empty()
                : reveals.record(request, RevealRecorder.Kind.EXPORT, PROCESS_NAME, def.name, null, plain,
                    exported[0])));
    }

    /** The entries after {@code after} (the start when null), at most {@code batch}, in key order. */
    private Mono<List<EntityInstance>> page(DatasetDefinition dataset, EntityDefinition def, Instant asOf,
        Instant knownAt, Object after, int batch) {
        EntityQuery.Builder query = EntityQuery.builder().orderBy(def.primaryKey, true).limit(batch);
        if (after != null) {
            query.where(new QueryPredicate.KeyAfter(after));
        }
        return entities.query(dataset, def, query.build(), asOf, knownAt).collectList();
    }

    private static Instant firstNonNull(Instant first, Instant second) {
        return first != null ? first : second;
    }

    /** The archived PDFs of the runs issued in the period that the caller may read, as issued. */
    private Mono<Void> reports(Plan plan, RequestContext request, Archive archive) {
        return runs.issuedBetween(plan.reportsFrom(), plan.reportsTo())
            .filter(plan.readableRun())
            .concatMap(summary -> runs.find(summary.runId()))
            .filter(ReportRun::intact)
            .concatMap(run -> reports.write(run.document(), ReportExporter.Format.PDF,
                    Locale.forLanguageTag(run.language()))
                .flatMap(bytes -> blocking(() -> archive.file("reports/" + run.runId() + "-"
                    + reports.fileName(run.templateId(), run.issuedTime(), ReportExporter.Format.PDF), bytes))))
            .then();
    }

    private Map<String, Object> describe(EntityDefinition def, DatasetDefinition dataset, String file,
        List<FieldDefinition> fields) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("entity", def.name);
        entity.put("dataset", dataset.resourceId());
        entity.put("file", file);
        entity.put("label", labels(MetaModelExporter.labelKey(def.name, null), def.name));
        entity.put("primaryKey", def.primaryKey);
        entity.put("temporal", def.temporal);
        List<Map<String, Object>> columns = new ArrayList<>();
        for (FieldDefinition field : fields) {
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("name", field.name());
            column.put("label", labels(MetaModelExporter.labelKey(def.name, field.name()), field.name()));
            column.put("required", field.required());
            column.put("systemManaged", def.isSystemManaged(field));
            column.putAll(MetaModelExporter.kindToJson(field.kind()));
            columns.add(column);
        }
        entity.put("columns", columns);
        entity.put("references", def.references.stream()
            .map(r -> Map.of("field", r.sourceField(), "targetEntity", r.targetEntity())).toList());
        return entity;
    }

    private Map<String, String> labels(String key, String fallback) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (Locale locale : List.of(Locale.ENGLISH, Locale.CHINESE)) {
            labels.put(locale.getLanguage(), messages.find(key, locale).orElse(fallback));
        }
        return labels;
    }

    private Map<String, Object> manifest(Plan plan, RequestContext request, long processSeqId,
        Map<String, Object> head, List<Map<String, Object>> files) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", FORMAT);
        manifest.put("exportedTime", clock.instant().toString());
        manifest.put("exportedBy", request.actorId());
        manifest.put("processSeqId", processSeqId);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("datasets", plan.datasets().stream().map(DatasetDefinition::resourceId).toList());
        parameters.put("asOf", plan.asOf() == null ? null : plan.asOf().toString());
        parameters.put("knownAt", plan.knownAt() == null ? null : plan.knownAt().toString());
        parameters.put("readAt", plan.readAt().toString());
        parameters.put("reports", plan.reports());
        parameters.put("reportsFrom", plan.reportsFrom() == null ? null : plan.reportsFrom().toString());
        parameters.put("reportsTo", plan.reportsTo() == null ? null : plan.reportsTo().toString());
        manifest.put("parameters", parameters);
        String version = DataExporter.class.getPackage().getImplementationVersion();
        manifest.put("platformVersion", version == null ? "development" : version);
        manifest.put("integrityHead", head.isEmpty() ? null : head);
        manifest.put("files", files);
        return manifest;
    }

    private static Mono<Void> blocking(IoAction action) {
        return Mono.<Void>fromRunnable(() -> {
            try {
                action.run();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    /** The ZIP being written: one entry at a time, each hashed and its rows counted as it is written. */
    private final class Archive {
        final Path path;
        final List<Map<String, Object>> files = new ArrayList<>();
        private final ZipOutputStream zip;
        private String entry;
        private MessageDigest digest;
        private long rows;
        private long bytes;

        Archive(Path path) throws IOException {
            this.path = path;
            this.zip = new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.UTF_8);
        }

        void begin(String name, String header) throws IOException {
            zip.putNextEntry(new ZipEntry(name));
            entry = name;
            digest = sha256();
            rows = 0;
            bytes = 0;
            write(header.getBytes(StandardCharsets.UTF_8));
        }

        void row(String line) throws IOException {
            write(line.getBytes(StandardCharsets.UTF_8));
            rows++;
        }

        void end() throws IOException {
            zip.closeEntry();
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("path", entry);
            file.put("sha256", HexFormat.of().formatHex(digest.digest()));
            file.put("bytes", bytes);
            file.put("rows", rows);
            files.add(file);
            entry = null;
        }

        void file(String name, byte[] content) throws IOException {
            zip.putNextEntry(new ZipEntry(name));
            entry = name;
            digest = sha256();
            bytes = 0;
            write(content);
            zip.closeEntry();
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("path", name);
            file.put("sha256", HexFormat.of().formatHex(digest.digest()));
            file.put("bytes", bytes);
            files.add(file);
        }

        void json(String name, Object value) throws IOException {
            byte[] content = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
            if ("manifest.json".equals(name)) {
                // The manifest lists every other file; it cannot list itself.
                zip.putNextEntry(new ZipEntry(name));
                zip.write(content);
                zip.closeEntry();
            } else {
                file(name, content);
            }
        }

        private void write(byte[] content) throws IOException {
            OutputStream out = zip;
            out.write(content);
            digest.update(content);
            bytes += content.length;
        }

        void close() throws IOException {
            zip.close();
        }

        void discardQuietly() {
            try {
                discard();
            } catch (IOException ignored) {
                // A temporary file; the system cleans its directory.
            }
        }

        void discard() throws IOException {
            try {
                zip.close();
            } finally {
                Files.deleteIfExists(path);
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}

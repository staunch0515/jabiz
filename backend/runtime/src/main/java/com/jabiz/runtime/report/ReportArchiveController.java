package com.jabiz.runtime.report;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.ProjectedField;
import com.jabiz.query.custom.SemanticRow;
import com.jabiz.report.ReportColumn;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import com.jabiz.runtime.security.Permissions;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Issued reports (docs/design/19-reports.md section 5): the list, one run, the run reproduced as a file from what was
 * archived (the same bytes every time for PDF), and verification against the data. Everything needs
 * {@code report.archive.read} and the permissions the template had when the run was issued; a run the caller may
 * not read is reported as not found.
 */
@RestController
@RequestMapping("/api/reports/runs")
class ReportArchiveController {

    /** A run without its rows. {@code supersededBy} is the run that replaced it, if any. */
    record RunSummary(String runId, String templateId, String templateVersion, String title, String period,
        Instant asOf, Instant knownAt, int rowCount, String contentHash, boolean recomputable, String issuedBy,
        Instant issuedTime, String supersededBy) {}

    /** A run with what its page header showed and its columns. */
    record RunDetail(RunSummary run, String company, String language, Map<String, Object> params,
        List<ParameterEntry> parameters, List<ColumnEntry> columns, boolean landscape) {}

    record ParameterEntry(String label, String value) {}

    record ColumnEntry(String name, String label) {}

    /**
     * The outcome of running the report again at the archived point in time: {@code identical} (same hash),
     * {@code differs} (the data read at that point changed - only possible when the template reads non-temporal
     * entities, or the data was changed outside the platform), or {@code template_changed} (the template is not the
     * version the run was issued with, so its rows were not recomputed).
     */
    record Verification(String verdict, boolean recomputable, String contentHash, String currentHash,
        String currentVersion) {}

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    static final String CONTENT_HASH_HEADER = "X-Jabiz-Content-Hash";

    private final ReportRuns runs;
    private final SqlTemplateRegistry templates;
    private final AdvancedQueryExecutor executor;
    private final ReportExporter exporter;
    private final ReportSettings settings;
    private final boolean development;

    ReportArchiveController(ReportRuns runs, SqlTemplateRegistry templates, AdvancedQueryExecutor executor,
        ReportExporter exporter, ReportSettings settings, Environment environment) {
        this.runs = runs;
        this.templates = templates;
        this.executor = executor;
        this.exporter = exporter;
        this.settings = settings;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
    }

    /** The latest runs the caller may read, newest first, optionally of one template. */
    @GetMapping
    Mono<List<RunSummary>> list(@RequestParam(required = false) String template,
        @RequestParam(required = false) Integer limit) {
        int size = Math.min(limit == null || limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT);
        return RequestContexts.current().flatMap(context -> {
            Permissions.requireAll(context, List.of(ReportPermissions.ARCHIVE_READ), development,
                "Reading issued reports");
            // Filtered before counting: runs the caller may not read never take the place of those they may.
            return runs.latest(blankToNull(template))
                .filter(run -> Permissions.allowsAll(context, run.permissions(), development))
                .take(size)
                .map(ReportArchiveController::summary)
                .collectList();
        });
    }

    @GetMapping("/{runId}")
    Mono<RunDetail> get(@PathVariable String runId) {
        return readable(runId).map(run -> new RunDetail(summary(run), run.company(), run.language(), run.params(),
            run.parameters().stream().map(p -> new ParameterEntry(p.label(), p.value())).toList(),
            run.columns().stream().map(c -> new ColumnEntry(c.name(), c.label())).toList(), run.landscape()));
    }

    /**
     * The run as a file, made from what was archived - never from the current data - with its page header as issued,
     * in the language it was issued in. The archived rows are checked against the archived hash first.
     */
    @GetMapping(value = "/{runId}/export", produces = {"text/csv", "application/pdf",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/json"})
    Mono<ResponseEntity<byte[]>> export(@PathVariable String runId, @RequestParam String format) {
        ReportExporter.Format chosen = format(format);
        return readable(runId).flatMap(run -> {
            if (!ArchivedValues.hash(run.columns(), run.rows()).equals(run.contentHash())) {
                return Mono.error(new IllegalStateException("The archived rows of report run " + runId
                    + " do not match their content hash"));
            }
            return exporter.write(run.document(), chosen, Locale.forLanguageTag(run.language()))
                .map(bytes -> ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(chosen.mediaType()))
                    .header(CONTENT_HASH_HEADER, run.contentHash())
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(exporter.fileName(run.templateId(), run.issuedTime(), chosen)).build().toString())
                    .body(bytes));
        });
    }

    /** Runs the report again at the archived point in time and compares the content hash. */
    @PostMapping("/{runId}/verify")
    Mono<Verification> verify(@PathVariable String runId) {
        return readable(runId).flatMap(run -> {
            Optional<AdvancedQueryDefinition> current = templates.find(run.templateId());
            if (current.isEmpty() || !current.get().version().equals(run.templateVersion())) {
                return Mono.just(new Verification("template_changed", run.recomputable(), run.contentHash(), null,
                    current.map(AdvancedQueryDefinition::version).orElse(null)));
            }
            AdvancedQueryDefinition query = templates.prepare(current.get());
            // Exactly the point the run was read at: its parameters, and the archived times for the rest.
            AdvancedQueryExecutor.At at = AdvancedQueryExecutor.At.pinned(run.readAt(), run.knownAt());
            return executor.all(query, run.params(), at, null, List.of(), settings.maxRows() + 1)
                .map(page -> {
                    List<ReportColumn> columns = run.columns();
                    List<List<Object>> rows = new ArrayList<>(page.items().size());
                    for (SemanticRow row : page.items()) {
                        List<Object> values = new ArrayList<>(columns.size());
                        for (ProjectedField field : query.resultFields()) {
                            values.add(row.getRaw(field.name()));
                        }
                        rows.add(values);
                    }
                    String hash = ArchivedValues.hash(columns, rows);
                    return new Verification(hash.equals(run.contentHash()) ? "identical" : "differs",
                        run.recomputable(), run.contentHash(), hash, query.version());
                });
        });
    }

    /** The run, if the caller may read it: the archive permission and the template's permissions at issue. */
    private Mono<ReportRun> readable(String runId) {
        return RequestContexts.current().flatMap(context -> {
            Permissions.requireAll(context, List.of(ReportPermissions.ARCHIVE_READ), development,
                "Reading issued reports");
            UUID id = uuid(runId);
            return runs.find(id)
                .filter(run -> Permissions.allowsAll(context, run.permissions(), development))
                .switchIfEmpty(Mono.error(new EntityNotFoundException("Unknown report run: " + runId)));
        });
    }

    private static UUID uuid(String runId) {
        try {
            return UUID.fromString(runId);
        } catch (IllegalArgumentException e) {
            throw new EntityNotFoundException("Unknown report run: " + runId);
        }
    }

    private static ReportExporter.Format format(String format) {
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "csv" -> ReportExporter.Format.CSV;
            case "xlsx" -> ReportExporter.Format.XLSX;
            case "pdf" -> ReportExporter.Format.PDF;
            default -> throw new ValidationException(List.of(new Violation("format", PlatformErrorCodes.INVALID_VALUE,
                "format must be csv, xlsx or pdf")));
        };
    }

    private static RunSummary summary(ReportRun run) {
        return new RunSummary(run.runId().toString(), run.templateId(), run.templateVersion(), run.title(),
            run.period(), run.asOf(), run.knownAt(), run.rowCount(), run.contentHash(), run.recomputable(),
            run.issuedBy(), run.issuedTime(), run.supersededBy() == null ? null : run.supersededBy().toString());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

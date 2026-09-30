package com.jabiz.runtime.imports;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.MetaModelExporter;
import com.jabiz.entity.Violation;
import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.ValidationException;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.imports.ImportCodes;
import com.jabiz.report.ReportColumn;
import com.jabiz.report.ReportDocument;
import com.jabiz.runtime.context.RequestContextWebFilter;
import com.jabiz.runtime.report.ReportExporter;
import com.jabiz.runtime.report.ReportSettings;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ServerWebExchange;
import java.util.ArrayList;
import com.jabiz.file.MediaTypes;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.imports.ImportDefinition;
import com.jabiz.imports.ImportFormat;
import com.jabiz.imports.ImportIssue;
import com.jabiz.imports.ImportMapping;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.file.FilePolicyRegistry;
import com.jabiz.runtime.process.ProcessInputSchemas;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Imports over HTTP (docs/design/20-imports.md section 6): the catalog of imports the caller may run, inspecting an
 * uploaded file for the mapping step, and previews. Texts come in the caller's language.
 */
@RestController
class ImportController {

    /**
     * @param params      JSON Schema of the import's parameters; null when it takes none
     * @param accept      content types an upload for the import may have
     * @param extensions  file name extensions to offer in the upload control (advisory; the content decides)
     * @param mappings    whether the caller may save mappings of this import
     */
    record ImportEntry(String id, int version, String title, String filePolicy, List<String> accept,
        List<String> extensions, ImportFormatEntry format, List<ImportFieldEntry> fields, Map<String, Object> params,
        List<String> totals, boolean externalRef, String onDuplicate, boolean mappings) {}

    /**
     * @param kind       {@code csv}, {@code fixedWidth}, {@code xlsx}, {@code xml} or {@code custom}
     * @param adjustable whether a mapping may change the layout settings below
     */
    record ImportFormatEntry(String kind, boolean adjustable, String delimiter, Boolean header, Integer skipLines,
        String charset, String sheet, Integer headerRow, List<String> charsets) {}

    record ImportFieldEntry(String name, String label, Map<String, Object> kind, boolean required, List<String> columns) {}

    record ImportIssueEntry(int row, String location, String field, String column, String code, String message) {}

    record ImportInspectRequest(String fileId, ImportFormat.Options options) {}

    record ImportSampleEntry(int number, String location, Map<String, String> cells, String problem) {}

    record ImportInspectResponse(List<String> columns, Map<String, String> header, List<ImportSampleEntry> sample, int records,
        Map<String, String> suggested, List<ImportIssueEntry> issues) {}

    record ImportPreviewRequest(String fileId, ImportMapping mapping, Map<String, Object> params) {}

    record ImportRowEntry(int number, String location, String status, Map<String, Object> values) {}

    /** An import's report, with its problems in the caller's language. */
    record ImportReportResponse(String runId, String importId, int importVersion, String fileId, String sha256, boolean committed,
        boolean accepted, int records, int rows, int processed, int units, int duplicates, Map<String, String> columns,
        Map<String, String> constants, Map<String, BigDecimal> totals, List<ImportRowEntry> results,
        List<ImportIssueEntry> issues) {}

    private final ImportRegistry imports;
    private final ImportService service;
    private final FilePolicyRegistry policies;
    private final MessageCatalog messages;
    private final ReportExporter exporter;
    private final ReportSettings settings;

    ImportController(ImportRegistry imports, ImportService service, FilePolicyRegistry policies,
        MessageCatalog messages, ReportExporter exporter, ReportSettings settings) {
        this.exporter = exporter;
        this.settings = settings;
        this.imports = imports;
        this.service = service;
        this.policies = policies;
        this.messages = messages;
    }

    /** The imports the caller may run, ordered by id. */
    @GetMapping("/api/meta/imports")
    Mono<List<ImportEntry>> catalog() {
        return RequestContexts.current().map(context -> imports.all().stream()
            .filter(definition -> service.allowed(definition, context))
            .sorted(Comparator.comparing(ImportDefinition::id))
            .map(definition -> entry(definition, context))
            .toList());
    }

    @PostMapping("/api/imports/{importId}/inspect")
    Mono<ImportInspectResponse> inspect(@PathVariable String importId, @RequestBody ImportInspectRequest request) {
        return RequestContexts.current().flatMap(context -> service.inspect(importId, request.fileId(),
                request.options())
            .map(inspection -> new ImportInspectResponse(inspection.columns(), inspection.header(),
                inspection.sample().stream().map(r -> new ImportSampleEntry(r.number(), r.location(), r.cells(),
                    r.problem())).toList(),
                inspection.records(), inspection.suggested(),
                issues(importId, inspection.issues(), context.locale()))));
    }

    @PostMapping("/api/imports/{importId}/preview")
    Mono<ImportReportResponse> preview(@PathVariable String importId, @RequestBody ImportPreviewRequest request) {
        return RequestContexts.current().flatMap(context -> service.preview(importId, request.fileId(),
                request.mapping(), request.params())
            .map(report -> response(report, context.locale())));
    }

    record ImportMappingRequest(ImportMapping mapping) {}

    /** @param notes what the person importing says about the data (decisions on data quality); kept with the run */
    record ImportCommitRequest(String fileId, ImportMapping mapping, Map<String, Object> params, String notes) {}

    /** A commit that found problems: nothing was imported; the attempt is recorded as {@code report.runId}. */
    static final class RejectedException extends RuntimeException {
        private final transient ImportReportResponse report;

        RejectedException(ImportReportResponse report) {
            super("The import was rejected", null, false, false);
            this.report = report;
        }
    }

    record ImportRunSummary(String runId, String importId, int importVersion, String title, String outcome, String fileId,
        String sha256, int records, int rows, int units, int processed, int duplicates, int issueCount,
        Map<String, BigDecimal> totals, String notes, String importedBy, java.time.Instant importedTime) {}

    record ImportRunDetail(ImportRunSummary run, ImportMapping mapping, Map<String, Object> params, Map<String, String> columns,
        List<ImportIssueEntry> issues) {}

    /** 200 with the report when imported; 422 {@code IMPORT_REJECTED} with the report when not. */
    @PostMapping("/api/imports/{importId}/commit")
    Mono<ImportReportResponse> commit(@PathVariable String importId, @RequestBody ImportCommitRequest request) {
        return RequestContexts.current().flatMap(context -> service.commit(importId, request.fileId(),
                request.mapping(), request.params(), request.notes())
            .map(report -> {
                ImportReportResponse response = response(report, context.locale());
                if (!report.committed()) {
                    throw new RejectedException(response);
                }
                return response;
            }));
    }

    @ExceptionHandler(RejectedException.class)
    ResponseEntity<ProblemDetail> rejected(RejectedException rejected, ServerWebExchange exchange) {
        RequestContext context = RequestContextWebFilter.of(exchange);
        Locale locale = context == null ? messages.defaultLocale() : context.locale();
        int count = rejected.report.issues().size();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(422),
            rejected.getMessage());
        problem.setProperty("violations", List.of(Map.of("ruleCode", ImportCodes.REJECTED, "message",
            messages.message(new Violation(null, ImportCodes.REJECTED, "rejected", Map.of("count", count)), locale))));
        problem.setProperty("report", rejected.report);
        return ResponseEntity.status(HttpStatus.valueOf(422)).body(problem);
    }

    @GetMapping("/api/imports/runs")
    Mono<List<ImportRunSummary>> runs(@RequestParam(name = "import", required = false) String importId,
        @RequestParam(required = false) Integer limit) {
        int size = Math.min(limit == null || limit <= 0 ? 50 : limit, 200);
        return RequestContexts.current().flatMap(context -> service.runs(blankToNull(importId), size)
            .map(list -> list.stream().map(run -> summary(run, context.locale())).toList()));
    }

    @GetMapping("/api/imports/runs/{runId}")
    Mono<ImportRunDetail> run(@PathVariable String runId) {
        return RequestContexts.current().flatMap(context -> service.run(runId).map(run -> new ImportRunDetail(
            summary(run, context.locale()), run.mapping(), run.params(), run.columns(),
            issues(run.importId(), run.issues(), context.locale()))));
    }

    /** The run's report as a file: its figures in the header and its problems as rows. */
    @GetMapping(value = "/api/imports/runs/{runId}/export", produces = {"text/csv", "application/pdf",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/json"})
    Mono<ResponseEntity<byte[]>> export(@PathVariable String runId, @RequestParam String format) {
        ReportExporter.Format chosen = switch (format.toLowerCase(Locale.ROOT)) {
            case "csv" -> ReportExporter.Format.CSV;
            case "xlsx" -> ReportExporter.Format.XLSX;
            case "pdf" -> ReportExporter.Format.PDF;
            default -> throw new ValidationException(List.of(new Violation("format", PlatformErrorCodes.INVALID_VALUE,
                "format must be csv, xlsx or pdf")));
        };
        return RequestContexts.current().flatMap(context -> service.run(runId).flatMap(run -> {
            Locale locale = context.locale();
            ReportDocument document = document(run, locale);
            return exporter.write(document, chosen, locale).map(bytes -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(chosen.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(exporter.fileName("import-" + run.importId(), run.importedTime(), chosen)).build()
                    .toString())
                .body(bytes));
        }));
    }

    private ReportDocument document(ImportRun run, Locale locale) {
        List<ReportDocument.Parameter> header = new ArrayList<>();
        header.add(new ReportDocument.Parameter(text("import.report.outcome", locale),
            text("import.report.outcome." + run.outcome(), locale)));
        header.add(new ReportDocument.Parameter(text("import.report.file", locale), run.sha256()));
        header.add(new ReportDocument.Parameter(text("import.report.records", locale),
            run.recordCount() + " / " + run.rowCount() + " / " + run.duplicateCount()));
        header.add(new ReportDocument.Parameter(text("import.report.processed", locale),
            run.processedCount() + " / " + run.unitCount()));
        run.totals().forEach((field, total) -> header.add(new ReportDocument.Parameter(text("import.report.total",
            locale) + " " + label(run.importId(), field, locale), total.toPlainString())));
        header.add(new ReportDocument.Parameter(text("import.report.importedBy", locale), run.importedBy()));
        if (run.notes() != null && !run.notes().isBlank()) {
            header.add(new ReportDocument.Parameter(text("import.report.notes", locale), run.notes()));
        }
        List<ReportColumn> columns = List.of(
            new ReportColumn("row", text("import.report.row", locale), new SemanticKind.Version()),
            new ReportColumn("location", text("import.report.location", locale), null),
            new ReportColumn("field", text("import.report.field", locale), null),
            new ReportColumn("code", text("import.report.code", locale), null),
            new ReportColumn("message", text("import.report.message", locale), null));
        List<List<Object>> rows = new ArrayList<>();
        for (ImportIssueEntry issue : issues(run.importId(), run.issues(), locale)) {
            List<Object> row = new ArrayList<>();
            row.add(issue.row() == 0 ? null : (long) issue.row());
            row.add(issue.location());
            row.add(issue.field() == null ? null : label(run.importId(), issue.field(), locale));
            row.add(issue.code());
            row.add(issue.message());
            rows.add(row);
        }
        return new ReportDocument("import-" + run.importId(), String.valueOf(run.importVersion()),
            text("import.report.title", locale) + ": " + title(run.importId(), locale), settings.company(), null,
            header, run.importedTime(), null, null, true, columns, rows);
    }

    private ImportRunSummary summary(ImportRun run, Locale locale) {
        return new ImportRunSummary(run.runId().toString(), run.importId(), run.importVersion(), title(run.importId(),
            locale), run.outcome(), run.fileId().toString(), run.sha256(), run.recordCount(), run.rowCount(),
            run.unitCount(), run.processedCount(), run.duplicateCount(), run.issueCount(), run.totals(), run.notes(),
            run.importedBy(), run.importedTime());
    }

    private String title(String importId, Locale locale) {
        return messages.find("import." + importId, locale).orElse(importId);
    }

    private String label(String importId, String field, Locale locale) {
        return messages.find("import." + importId + "." + field, locale).orElse(field);
    }

    private String text(String key, Locale locale) {
        return messages.find(key, locale).orElse(key);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @GetMapping("/api/imports/{importId}/mappings")
    Mono<List<ImportService.SavedMapping>> mappings(@PathVariable String importId) {
        return service.mappings(importId);
    }

    @PutMapping("/api/imports/{importId}/mappings/{name}")
    Mono<ImportMappings.MappingOutput> saveMapping(@PathVariable String importId, @PathVariable String name,
        @RequestBody ImportMappingRequest request) {
        return service.saveMapping(importId, name, request.mapping());
    }

    @DeleteMapping("/api/imports/{importId}/mappings/{name}")
    Mono<ImportMappings.MappingOutput> removeMapping(@PathVariable String importId, @PathVariable String name) {
        return service.removeMapping(importId, name);
    }

    ImportReportResponse response(ImportReport report, Locale locale) {
        return new ImportReportResponse(report.runId(), report.importId(), report.importVersion(), report.fileId(), report.sha256(),
            report.committed(), report.accepted(), report.records(), report.rows(), report.processed(), report.units(),
            report.duplicates(), report.columns(), report.constants(), report.totals(),
            report.results().stream().map(r -> new ImportRowEntry(r.number(), r.location(), r.status(), r.values()))
                .toList(),
            issues(report.importId(), report.issues(), locale));
    }

    private List<ImportIssueEntry> issues(String importId, List<ImportIssue> issues, Locale locale) {
        return issues.stream().map(issue -> {
            // The field placeholder shows the field's label where the import has one.
            String label = issue.field() == null ? null
                : messages.find("import." + importId + "." + issue.field(), locale).orElse(issue.field());
            Map<String, Object> params = new java.util.HashMap<>(issue.params());
            if (label != null) {
                params.put("field", label);
            }
            String message = messages.message(new Violation(issue.field(), issue.code(), issue.message(), params),
                locale);
            return new ImportIssueEntry(issue.row(), issue.location(), issue.field(), issue.column(), issue.code(),
                message);
        }).toList();
    }

    private ImportEntry entry(ImportDefinition<?> definition, RequestContext context) {
        Locale locale = context.locale();
        String prefix = "import." + definition.id();
        List<ImportFieldEntry> fields = definition.fields().stream()
            .map(field -> new ImportFieldEntry(field.name(), messages.find(prefix + "." + field.name(), locale)
                .orElse(field.name()), MetaModelExporter.kindToJson(field.kind()), field.required(), field.columns()))
            .toList();
        List<MediaTypes> types = policies.find(definition.filePolicy()).map(policy -> List.copyOf(policy.allowed()))
            .orElse(List.of());
        return new ImportEntry(definition.id(), definition.version(), messages.find(prefix, locale)
            .orElse(definition.id()), definition.filePolicy(),
            types.stream().map(MediaTypes::contentType).toList(),
            types.stream().flatMap(type -> type.extensions().stream()).toList(),
            format(definition.format()), fields,
            definition.hasParams() ? ProcessInputSchemas.of(definition.paramsType()).schema() : null,
            definition.totals(), definition.externalRef() != null,
            definition.externalRef() == null ? null : definition.onDuplicate().name(),
            context.hasPermission(definition.mappingPermission()));
    }

    private static ImportFormatEntry format(ImportFormat format) {
        return switch (format) {
            case ImportFormat.Csv csv -> new ImportFormatEntry("csv", true, String.valueOf(csv.delimiter()), csv.header(),
                csv.skipLines(), csv.charset().name(), null, null, ImportFormat.CHARSETS);
            case ImportFormat.Xlsx xlsx -> new ImportFormatEntry("xlsx", true, null, xlsx.header(), null, null,
                xlsx.sheet(), xlsx.headerRow(), null);
            case ImportFormat.FixedWidth fixed -> new ImportFormatEntry("fixedWidth", false, null, null, fixed.skipLines(),
                fixed.charset().name(), null, null, null);
            case ImportFormat.Xml xml -> new ImportFormatEntry("xml", false, null, null, null, null, null, null, null);
            case ImportFormat.Custom custom -> new ImportFormatEntry("custom", false, null, null, null, null, null, null,
                null);
        };
    }

}

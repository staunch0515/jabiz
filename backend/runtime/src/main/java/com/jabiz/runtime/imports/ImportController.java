package com.jabiz.runtime.imports;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.MetaModelExporter;
import com.jabiz.entity.Violation;
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
        List<String> extensions, FormatEntry format, List<FieldEntry> fields, Map<String, Object> params,
        List<String> totals, boolean externalRef, String onDuplicate, boolean mappings) {}

    /**
     * @param kind       {@code csv}, {@code fixedWidth}, {@code xlsx}, {@code xml} or {@code custom}
     * @param adjustable whether a mapping may change the layout settings below
     */
    record FormatEntry(String kind, boolean adjustable, String delimiter, Boolean header, Integer skipLines,
        String charset, String sheet, Integer headerRow, List<String> charsets) {}

    record FieldEntry(String name, String label, Map<String, Object> kind, boolean required, List<String> columns) {}

    record IssueEntry(int row, String location, String field, String column, String code, String message) {}

    record InspectRequest(String fileId, ImportFormat.Options options) {}

    record SampleEntry(int number, String location, Map<String, String> cells, String problem) {}

    record InspectResponse(List<String> columns, Map<String, String> header, List<SampleEntry> sample, int records,
        Map<String, String> suggested, List<IssueEntry> issues) {}

    record PreviewRequest(String fileId, ImportMapping mapping, Map<String, Object> params) {}

    record RowEntry(int number, String location, String status, Map<String, Object> values) {}

    /** An import's report, with its problems in the caller's language. */
    record ReportResponse(String importId, int importVersion, String fileId, String sha256, boolean committed,
        boolean accepted, int records, int rows, int processed, int units, int duplicates, Map<String, String> columns,
        Map<String, String> constants, Map<String, BigDecimal> totals, List<RowEntry> results,
        List<IssueEntry> issues) {}

    private final ImportRegistry imports;
    private final ImportService service;
    private final FilePolicyRegistry policies;
    private final MessageCatalog messages;

    ImportController(ImportRegistry imports, ImportService service, FilePolicyRegistry policies,
        MessageCatalog messages) {
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
    Mono<InspectResponse> inspect(@PathVariable String importId, @RequestBody InspectRequest request) {
        return RequestContexts.current().flatMap(context -> service.inspect(importId, request.fileId(),
                request.options())
            .map(inspection -> new InspectResponse(inspection.columns(), inspection.header(),
                inspection.sample().stream().map(r -> new SampleEntry(r.number(), r.location(), r.cells(),
                    r.problem())).toList(),
                inspection.records(), inspection.suggested(),
                issues(importId, inspection.issues(), context.locale()))));
    }

    @PostMapping("/api/imports/{importId}/preview")
    Mono<ReportResponse> preview(@PathVariable String importId, @RequestBody PreviewRequest request) {
        return RequestContexts.current().flatMap(context -> service.preview(importId, request.fileId(),
                request.mapping(), request.params())
            .map(report -> response(report, context.locale())));
    }

    record MappingRequest(ImportMapping mapping) {}

    @GetMapping("/api/imports/{importId}/mappings")
    Mono<List<ImportService.SavedMapping>> mappings(@PathVariable String importId) {
        return service.mappings(importId);
    }

    @PutMapping("/api/imports/{importId}/mappings/{name}")
    Mono<ImportMappings.MappingOutput> saveMapping(@PathVariable String importId, @PathVariable String name,
        @RequestBody MappingRequest request) {
        return service.saveMapping(importId, name, request.mapping());
    }

    @DeleteMapping("/api/imports/{importId}/mappings/{name}")
    Mono<ImportMappings.MappingOutput> removeMapping(@PathVariable String importId, @PathVariable String name) {
        return service.removeMapping(importId, name);
    }

    ReportResponse response(ImportReport report, Locale locale) {
        return new ReportResponse(report.importId(), report.importVersion(), report.fileId(), report.sha256(),
            report.committed(), report.accepted(), report.records(), report.rows(), report.processed(), report.units(),
            report.duplicates(), report.columns(), report.constants(), report.totals(),
            report.results().stream().map(r -> new RowEntry(r.number(), r.location(), r.status(), r.values()))
                .toList(),
            issues(report.importId(), report.issues(), locale));
    }

    private List<IssueEntry> issues(String importId, List<ImportIssue> issues, Locale locale) {
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
            return new IssueEntry(issue.row(), issue.location(), issue.field(), issue.column(), issue.code(),
                message);
        }).toList();
    }

    private ImportEntry entry(ImportDefinition<?> definition, RequestContext context) {
        Locale locale = context.locale();
        String prefix = "import." + definition.id();
        List<FieldEntry> fields = definition.fields().stream()
            .map(field -> new FieldEntry(field.name(), messages.find(prefix + "." + field.name(), locale)
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

    private static FormatEntry format(ImportFormat format) {
        return switch (format) {
            case ImportFormat.Csv csv -> new FormatEntry("csv", true, String.valueOf(csv.delimiter()), csv.header(),
                csv.skipLines(), csv.charset().name(), null, null, ImportFormat.CHARSETS);
            case ImportFormat.Xlsx xlsx -> new FormatEntry("xlsx", true, null, xlsx.header(), null, null,
                xlsx.sheet(), xlsx.headerRow(), null);
            case ImportFormat.FixedWidth fixed -> new FormatEntry("fixedWidth", false, null, null, fixed.skipLines(),
                fixed.charset().name(), null, null, null);
            case ImportFormat.Xml xml -> new FormatEntry("xml", false, null, null, null, null, null, null, null);
            case ImportFormat.Custom custom -> new FormatEntry("custom", false, null, null, null, null, null, null,
                null);
        };
    }

}

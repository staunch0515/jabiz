package com.jabiz.runtime.report;

import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.QueryPredicate;
import com.jabiz.query.SortOrder;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.ProjectedField;
import com.jabiz.query.custom.QueryParameter;
import com.jabiz.query.custom.ReportSpec;
import com.jabiz.query.custom.SemanticRow;
import com.jabiz.report.CsvReportWriter;
import com.jabiz.report.ReportColumn;
import com.jabiz.report.ReportDocument;
import com.jabiz.report.ReportFormat;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import com.jabiz.runtime.query.QueryTexts;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import io.micrometer.common.KeyValues;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Exports the result of a SQL template as CSV, XLSX or PDF (docs/design/19-reports.md section 4). The whole result is
 * read (up to {@code jabiz.reports.export.max-rows}; a larger one is refused, never cut), turned into a
 * {@link ReportDocument} in the caller's language, and written off the event loop. Permissions are the caller's
 * business: the same as running the template.
 */
@Component
public class ReportExporter {

    /** An export format and the media type and file extension of its files. */
    public enum Format {
        CSV("text/csv;charset=UTF-8", "csv"),
        XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
        PDF("application/pdf", "pdf");

        private final String mediaType;
        private final String extension;

        Format(String mediaType, String extension) {
            this.mediaType = mediaType;
            this.extension = extension;
        }

        public String mediaType() {
            return mediaType;
        }

        public String extension() {
            return extension;
        }
    }

    /** A written export. */
    public record Export(byte[] content, Format format, String fileName, int rows) {}

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AdvancedQueryExecutor executor;
    private final SqlTemplateRegistry templates;
    private final QueryTexts texts;
    private final ReportSettings settings;
    private final Clock clock;
    private final PlatformObservations observations;

    public ReportExporter(AdvancedQueryExecutor executor, SqlTemplateRegistry templates, QueryTexts texts,
        ReportSettings settings, Clock clock, PlatformObservations observations) {
        this.executor = executor;
        this.templates = templates;
        this.texts = texts;
        this.settings = settings;
        this.clock = clock;
        this.observations = observations;
    }

    /**
     * @throws BusinessRuleViolationException (as the error of the returned Mono) {@code REPORT_TOO_LARGE} when the
     *                                        result has more rows than an export may have
     */
    public Mono<Export> export(AdvancedQueryDefinition query, Map<String, Object> params, AdvancedQueryExecutor.At at,
        QueryPredicate filter, List<SortOrder> sorts, Format format) {
        int maxRows = settings.maxRows();
        Mono<Export> export = RequestContexts.current().flatMap(context -> executor
            .all(query, params, at, filter, sorts, maxRows + 1)
            .flatMap(page -> {
                if (page.items().size() > maxRows) {
                    return Mono.error(new BusinessRuleViolationException(new Violation(null,
                        PlatformErrorCodes.REPORT_TOO_LARGE, "Query " + query.queryId() + " has more than " + maxRows
                        + " rows", Map.of("limit", maxRows))));
                }
                Instant runTime = clock.instant();
                ReportDocument document = document(templates.prepare(query), params, asOfAsked(query, params, at),
                    page, runTime, context.locale());
                return write(document, format, context.locale())
                    .map(bytes -> new Export(bytes, format, fileName(query, runTime, format), page.items().size()));
            }));
        return observations.mono(PlatformObservations.EXPORT, "export " + query.queryId(),
            KeyValues.of("template", query.queryId(), "format", format.extension()), export);
    }

    /** The document as a file, written off the event loop: it is CPU work, and the writers block on their streams. */
    public Mono<byte[]> write(ReportDocument document, Format format, Locale locale) {
        ReportLabels labels = labels(locale);
        return Mono.fromCallable(() -> write(document, format, labels)).subscribeOn(Schedulers.boundedElastic());
    }

    private byte[] write(ReportDocument document, Format format, ReportLabels labels) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        switch (format) {
            case CSV -> CsvReportWriter.write(document, out);
            case XLSX -> XlsxReportWriter.write(document, settings.format(), labels, out);
            case PDF -> PdfReportWriter.write(document, settings.format(), labels, settings.fonts(), out);
        }
        return out.toByteArray();
    }

    /**
     * Whether the run was asked for another effective time than now - in the request or through the template's
     * {@code timeSlice} - so that the page header says so.
     */
    static boolean asOfAsked(AdvancedQueryDefinition query, Map<String, Object> params, AdvancedQueryExecutor.At at) {
        if (query.timeSlice() != null) {
            return query.timeSlice().asOf() != null && params != null && params.get(query.timeSlice().asOf()) != null;
        }
        return at != null && at.asOf() != null;
    }

    ReportDocument document(AdvancedQueryDefinition query, Map<String, Object> params, boolean asOfAsked,
        AdvancedQueryExecutor.Page page, Instant runTime, Locale locale) {
        ReportFormat format = settings.format();
        List<ReportColumn> columns = query.resultFields().stream()
            .map(field -> new ReportColumn(field.name(), texts.column(query, field, locale), field.kind()))
            .toList();
        List<List<Object>> rows = new ArrayList<>(page.items().size());
        for (SemanticRow row : page.items()) {
            List<Object> values = new ArrayList<>(columns.size());
            for (ProjectedField field : query.resultFields()) {
                values.add(row.getRaw(field.name()));
            }
            rows.add(values);
        }
        Map<String, Object> given = params == null ? Map.of() : params;
        List<ReportDocument.Parameter> shown = new ArrayList<>();
        for (QueryParameter parameter : query.parameters()) {
            Object value = given.get(parameter.name());
            if (value != null) {
                shown.add(new ReportDocument.Parameter(parameter.name(), text(parameter.kind(), value, format)));
            }
        }
        ReportSpec report = query.report();
        return new ReportDocument(query.queryId(), query.version(), texts.title(query, locale), settings.company(),
            report == null ? null : period(query, report, given, format), shown, runTime,
            asOfAsked && page.slice() != null ? page.slice().asOf() : null,
            page.slice() == null ? null : page.slice().knownAt(), report != null && report.landscape(), columns,
            rows);
    }

    /** "from – to" from the parameters the report names; null when neither was given. */
    private static String period(AdvancedQueryDefinition query, ReportSpec report, Map<String, Object> given,
        ReportFormat format) {
        String from = periodEnd(query, report.periodFrom(), given, format);
        String to = periodEnd(query, report.periodTo(), given, format);
        if (from == null && to == null) {
            return null;
        }
        return (from == null ? "" : from) + " – " + (to == null ? "" : to);
    }

    private static String periodEnd(AdvancedQueryDefinition query, String parameter, Map<String, Object> given,
        ReportFormat format) {
        if (parameter == null || given.get(parameter) == null) {
            return null;
        }
        SemanticKind kind = query.parameters().stream().filter(p -> p.name().equals(parameter)).findFirst()
            .map(QueryParameter::kind).orElse(null);
        return text(kind, given.get(parameter), format);
    }

    /** A parameter's value as the header shows it: times in the report's form, lists joined. */
    static String text(SemanticKind kind, Object value, ReportFormat format) {
        if (value instanceof Collection<?> values) {
            return values.stream().map(v -> text(kind, v, format)).collect(Collectors.joining(", "));
        }
        if (kind instanceof SemanticKind.Temporal) {
            try {
                return format.dateTime(OffsetDateTime.parse(String.valueOf(value)).toInstant());
            } catch (DateTimeParseException e) {
                return String.valueOf(value);
            }
        }
        if (kind instanceof SemanticKind.Date) {
            try {
                return format.date(LocalDate.parse(String.valueOf(value)));
            } catch (DateTimeParseException e) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    private ReportLabels labels(Locale locale) {
        ReportLabels english = ReportLabels.ENGLISH;
        return new ReportLabels(texts.text("report.period", locale, english.period()),
            texts.text("report.runAt", locale, english.runAt()),
            texts.text("report.asOf", locale, english.asOf()),
            texts.text("report.knownAt", locale, english.knownAt()),
            texts.text("report.version", locale, english.version()),
            texts.text("report.page", locale, "Page {page} of {pages}"));
    }

    /** {@code <template>-<run time>.<ext>}, with anything but letters, digits, dots, dashes and underscores replaced. */
    String fileName(AdvancedQueryDefinition query, Instant runTime, Format format) {
        return fileName(query.queryId(), runTime, format);
    }

    public String fileName(String templateId, Instant runTime, Format format) {
        String id = templateId.replaceAll("[^A-Za-z0-9._-]", "_");
        return id + "-" + FILE_TIME.format(runTime.atZone(settings.format().zone())) + "." + format.extension();
    }
}

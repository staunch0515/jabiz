package com.jabiz.runtime.web;

import com.jabiz.context.RequestContext;
import com.jabiz.query.QueryPredicate;
import com.jabiz.query.SortOrder;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.SemanticRow;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import com.jabiz.runtime.report.ReportExporter;
import com.jabiz.runtime.security.Permissions;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SQL template API (docs/design/05-sql-template.md): {@code POST /api/queries/{id}} runs a registered template with
 * the given parameters, filtered, sorted and paged within the template's whitelist. The caller needs every
 * permission the template declares (default deny: 403 {@code PERMISSION_DENIED}); a public template
 * ({@code access: public}) needs none.
 */
@RestController
@RequestMapping("/api/queries")
class QueryController {

    /**
     * @param asOf    effective time the template's temporal entities are read at; default now
     *                (docs/design/19-reports.md section 2.1)
     * @param knownAt recorded time they are read as of; default everything recorded so far
     * @param count   whether to return {@code total}; default true
     */
    record RunRequest(Map<String, Object> params, Instant asOf, Instant knownAt, List<ListRequests.Filter> filters,
        List<ListRequests.Sort> sorts, Integer offset, Integer limit, Boolean count) {}

    /** {@code total} is absent when counting was turned off; {@code limit} is the page size in effect. */
    record RunResponse(List<Map<String, Object>> items, Long total, int offset, int limit) {}

    private static final int DEFAULT_LIMIT = 50;

    private final SqlTemplateRegistry templates;
    private final AdvancedQueryExecutor executor;
    private final ReportExporter exporter;
    private final boolean development;

    QueryController(SqlTemplateRegistry templates, AdvancedQueryExecutor executor, ReportExporter exporter,
        Environment environment) {
        this.templates = templates;
        this.executor = executor;
        this.exporter = exporter;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
    }

    @PostMapping("/{queryId}")
    Mono<RunResponse> run(@PathVariable String queryId, @RequestBody(required = false) RunRequest request) {
        return RequestContexts.current().flatMap(context -> {
            AdvancedQueryDefinition query = templates.find(queryId)
                .orElseThrow(() -> new EntityNotFoundException("Unknown query: " + queryId));
            requirePermissions(query, context, development);
            RunRequest body = request == null ? new RunRequest(null, null, null, null, null, null, null, null) : request;
            int offset = body.offset() == null ? 0 : body.offset();
            int limit = body.limit() == null ? DEFAULT_LIMIT : body.limit();
            if (offset < 0) {
                throw ListRequests.invalid("offset", "offset must not be negative");
            }
            if (limit <= 0) {
                throw ListRequests.invalid("limit", "limit must be positive");
            }
            return executor.page(query, body.params(), new AdvancedQueryExecutor.At(body.asOf(), body.knownAt()),
                    filter(body.filters()), sorts(body.sorts()), offset, limit, body.count() == null || body.count(),
                    null)
                .map(page -> new RunResponse(page.items().stream().map(QueryController::values).toList(),
                    page.total(), page.offset(), page.limit()));
        });
    }

    /**
     * The whole result as a file (docs/design/19-reports.md section 4): {@code format} is {@code csv}, {@code xlsx} or
     * {@code pdf}; the body is the same as a run's, its paging ignored. Permissions are those of a run. A result larger
     * than {@code jabiz.reports.export.max-rows} is refused with 422 {@code REPORT_TOO_LARGE}.
     */
    @PostMapping(value = "/{queryId}/export", produces = {"text/csv", "application/pdf",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/json"})
    Mono<ResponseEntity<byte[]>> export(@PathVariable String queryId, @RequestParam String format,
        @RequestBody(required = false) RunRequest request) {
        return RequestContexts.current().flatMap(context -> {
            AdvancedQueryDefinition query = templates.find(queryId)
                .orElseThrow(() -> new EntityNotFoundException("Unknown query: " + queryId));
            requirePermissions(query, context, development);
            ReportExporter.Format chosen = switch (format.toLowerCase(Locale.ROOT)) {
                case "csv" -> ReportExporter.Format.CSV;
                case "xlsx" -> ReportExporter.Format.XLSX;
                case "pdf" -> ReportExporter.Format.PDF;
                default -> throw ListRequests.invalid("format", "format must be csv, xlsx or pdf");
            };
            RunRequest body = request == null ? new RunRequest(null, null, null, null, null, null, null, null) : request;
            return exporter.export(query, body.params(), new AdvancedQueryExecutor.At(body.asOf(), body.knownAt()),
                    filter(body.filters()), sorts(body.sorts()), chosen)
                .map(export -> ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(export.format().mediaType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.fileName()).build().toString())
                    .body(export.content()));
        });
    }

    /**
     * The caller needs every permission the template declares. Default deny: a template without permissions runs only
     * in the dev profile (registration reports it as an error elsewhere, but the startup check can be turned off).
     */
    static void requirePermissions(AdvancedQueryDefinition query, RequestContext context, boolean development) {
        if (query.publicAccess()) {
            // Anyone may read it anonymously; signed-in users (the back office previewing it) may as well.
            return;
        }
        Permissions.requireAll(context, query.permissions(), development, "Running query " + query.queryId());
    }

    private static QueryPredicate filter(List<ListRequests.Filter> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        List<QueryPredicate> parts = new ArrayList<>(filters.size());
        for (ListRequests.Filter filter : filters) {
            if (filter == null || filter.field() == null || filter.op() == null) {
                throw ListRequests.invalid("filters", "every filter needs a field and an op");
            }
            parts.add(ListRequests.toPredicate(filter));
        }
        return parts.size() == 1 ? parts.getFirst() : new QueryPredicate.And(parts);
    }

    private static List<SortOrder> sorts(List<ListRequests.Sort> sorts) {
        if (sorts == null) {
            return List.of();
        }
        List<SortOrder> result = new ArrayList<>(sorts.size());
        for (ListRequests.Sort sort : sorts) {
            if (sort == null || sort.field() == null) {
                throw ListRequests.invalid("sorts", "every sort needs a field");
            }
            result.add(new SortOrder(sort.field(), sort.asc() == null || sort.asc()));
        }
        return result;
    }

    private static Map<String, Object> values(SemanticRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        row.getAllColumns().forEach((name, value) -> values.put(name, value.value()));
        return values;
    }
}

package com.jabiz.runtime.document;

import com.jabiz.context.RequestContext;
import com.jabiz.document.DocumentContent;
import com.jabiz.document.DocumentLayout;
import com.jabiz.query.template.TemplateSchemas;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import com.jabiz.runtime.report.ReportScopes;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Business documents (docs/design/22-documents.md section 4): the layouts the caller may issue, a preview (laid out,
 * never kept), and issued documents - the list, one document, its PDF exactly as issued, and verification. Issuing is
 * the process {@code DOCUMENT_ISSUE}. Reading an issued document needs {@code document.archive.read}, the permissions
 * it was issued with and the issuer's data scope; one the caller may not read is reported as not found.
 */
@RestController
class DocumentController {

    /**
     * @param params the parameters of the layout's templates, as one JSON Schema
     */
    record DocumentLayoutEntry(String id, String title, String subjectEntity, String subjectParam, String version,
        Map<String, Object> params) {}

    /** What to preview: the parameters, the point in time and the language, as for {@code DOCUMENT_ISSUE}. */
    record DocumentPreviewRequest(Map<String, Object> params, Instant asOf, Instant knownAt, String language) {}

    /** An issued document without its content. */
    record DocumentSummary(String runId, String layoutId, String layoutVersion, String title, String documentNo,
        String subjectEntity, String subjectId, String language, String pageSize, Instant asOf, Instant readAt,
        Instant knownAt, String contentHash, String pdfHash, int pdfSize, int pages, boolean recomputable,
        String issuedBy, Instant issuedTime) {}

    /** An issued document with its parameters and what each template returned. */
    record DocumentDetail(DocumentSummary run, Map<String, Object> params, Map<String, String> templateVersions,
        List<DocumentSourceEntry> sources) {}

    record DocumentSourceEntry(String templateId, List<DocumentColumnEntry> columns, List<List<Object>> rows) {}

    record DocumentColumnEntry(String name, String label) {}

    /**
     * {@code copyIntact}: the kept PDF still has the hash it was issued with. {@code verdict}: reading the templates
     * again at the archived point in time gives the same content ({@code identical}) or not ({@code differs}); not
     * read again when the layout ({@code layout_changed}) or a template ({@code template_changed}) is not the version
     * the document was issued with.
     */
    record DocumentVerification(String verdict, boolean copyIntact, boolean recomputable, String contentHash,
        String currentHash, String layoutVersion, String currentLayoutVersion) {}

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    static final String CONTENT_HASH_HEADER = "X-Jabiz-Content-Hash";
    static final String PDF_HASH_HEADER = "X-Jabiz-Pdf-Hash";

    private final Documents documents;
    private final DocumentLayoutRegistry layouts;
    private final DocumentRuns runs;
    private final SqlTemplateRegistry templates;
    private final ReportScopes scopes;
    private final Clock clock;
    private final boolean development;

    DocumentController(Documents documents, DocumentLayoutRegistry layouts, DocumentRuns runs,
        SqlTemplateRegistry templates, ReportScopes scopes, Clock clock, Environment environment) {
        this.documents = documents;
        this.layouts = layouts;
        this.runs = runs;
        this.templates = templates;
        this.scopes = scopes;
        this.clock = clock;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
    }

    /** The layouts the caller may issue, ordered by id. */
    @GetMapping("/api/meta/documents")
    Mono<List<DocumentLayoutEntry>> documentLayouts() {
        return RequestContexts.current().map(context -> {
            if (!Permissions.allowsDeclared(context, DocumentPermissions.ISSUE, development)) {
                return List.of();
            }
            List<DocumentLayoutEntry> entries = new ArrayList<>();
            for (DocumentLayout layout : layouts.all()) {
                if (Permissions.allowsAll(context, documents.permissions(layout), development)) {
                    entries.add(new DocumentLayoutEntry(layout.id(), documents.title(layout, context.locale()),
                        layout.subjectEntity(), layout.subjectParam(), layout.version(),
                        TemplateSchemas.params(layouts.templatesOf(layout))));
                }
            }
            return entries;
        });
    }

    /** The document laid out as it would be issued now, marked as a preview on every page; nothing is kept. */
    @PostMapping(value = "/api/documents/{layoutId}/preview", produces = {"application/pdf", "application/json"})
    Mono<ResponseEntity<byte[]>> previewDocument(@PathVariable String layoutId, @RequestBody DocumentPreviewRequest request) {
        return RequestContexts.current().flatMap(context -> {
            Permissions.requireDeclared(context, DocumentPermissions.ISSUE, development, "Previewing documents");
            DocumentLayout layout = documents.layout(layoutId);
            documents.requirePermissions(context, layout, "Previewing document " + layoutId);
            Instant now = clock.instant();
            return documents.read(layout, request.params(), new AdvancedQueryExecutor.At(request.asOf(),
                    request.knownAt()), now, documents.language(request.language(), context), true, context)
                .flatMap(read -> documents.render(layout, read.content()))
                .map(rendered -> pdf(rendered.pdf(), layoutId + "-preview.pdf", null, rendered.pdfHash()));
        });
    }

    /** The latest documents the caller may read, newest first, optionally of one layout or about one subject. */
    @GetMapping("/api/documents/runs")
    Mono<List<DocumentSummary>> listDocuments(@RequestParam(required = false) String layout,
        @RequestParam(required = false) String subject, @RequestParam(required = false) Integer limit) {
        int size = Math.min(limit == null || limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT);
        return RequestContexts.current().flatMap(context -> {
            Permissions.requireDeclared(context, DocumentPermissions.ARCHIVE_READ, development,
                "Reading issued documents");
            // Filtered before counting: documents the caller may not read never take the place of those they may.
            return runs.latest(blankToNull(layout), blankToNull(subject))
                .filter(run -> readable(run, context))
                .take(size)
                .map(DocumentController::summary)
                .collectList();
        });
    }

    @GetMapping("/api/documents/runs/{runId}")
    Mono<DocumentDetail> getDocument(@PathVariable String runId) {
        return readable(runId).map(run -> {
            List<DocumentSourceEntry> sources = new ArrayList<>();
            for (DocumentContent.Source source : run.content().sources().values()) {
                sources.add(new DocumentSourceEntry(source.templateId(),
                    source.columns().stream().map(c -> new DocumentColumnEntry(c.name(), c.label())).toList(),
                    source.rows()));
            }
            return new DocumentDetail(summary(run), run.params(), run.templateVersions(), sources);
        });
    }

    /**
     * The PDF exactly as it was issued: the kept bytes, never laid out again. They are checked against the kept hash
     * first.
     */
    @GetMapping(value = "/api/documents/runs/{runId}/pdf", produces = {"application/pdf", "application/json"})
    Mono<ResponseEntity<byte[]>> documentPdf(@PathVariable String runId) {
        return readable(runId).flatMap(run -> {
            if (!Documents.sha256(run.pdf()).equals(run.pdfHash())) {
                return Mono.error(new IllegalStateException("The kept PDF of document " + runId
                    + " does not match its hash"));
            }
            return Mono.just(pdf(run.pdf(), fileName(run), run.contentHash(), run.pdfHash()));
        });
    }

    /** Checks the kept PDF and reads the templates again at the archived point in time. */
    @PostMapping("/api/documents/runs/{runId}/verify")
    Mono<DocumentVerification> verifyDocument(@PathVariable String runId) {
        return readable(runId).flatMap(run -> RequestContexts.current().flatMap(context -> {
            boolean intact = Documents.sha256(run.pdf()).equals(run.pdfHash());
            Optional<DocumentLayout> current = layouts.find(run.layoutId());
            String currentVersion = current.map(DocumentLayout::version).orElse(null);
            if (current.isEmpty() || !currentVersion.equals(run.layoutVersion())) {
                return Mono.just(new DocumentVerification("layout_changed", intact, run.recomputable(), run.contentHash(),
                    null, run.layoutVersion(), currentVersion));
            }
            for (Map.Entry<String, String> entry : run.templateVersions().entrySet()) {
                String now = templates.find(entry.getKey()).map(q -> q.version()).orElse(null);
                if (!entry.getValue().equals(now)) {
                    return Mono.just(new DocumentVerification("template_changed", intact, run.recomputable(),
                        run.contentHash(), null, run.layoutVersion(), currentVersion));
                }
            }
            AdvancedQueryExecutor.At at = AdvancedQueryExecutor.At.pinned(run.readAt(), run.knownAt());
            return documents.read(current.get(), run.params(), at, run.issuedTime(),
                    java.util.Locale.forLanguageTag(run.language()), false, context)
                .map(read -> {
                    String hash = Documents.contentHash(read.content());
                    return new DocumentVerification(hash.equals(run.contentHash()) ? "identical" : "differs", intact,
                        run.recomputable(), run.contentHash(), hash, run.layoutVersion(), currentVersion);
                })
                // What issuing refuses - a one-row template now without its row, more rows than allowed - means the
                // data is not what was issued: an answer, not an error.
                .onErrorResume(BusinessRuleViolationException.class, refused -> Mono.just(new DocumentVerification(
                    "differs", intact, run.recomputable(), run.contentHash(), null, run.layoutVersion(),
                    currentVersion)));
        }));
    }

    /** The document, if the caller may read it: the archive permission, its permissions and the issuer's scope. */
    private Mono<DocumentRun> readable(String runId) {
        return RequestContexts.current().flatMap(context -> {
            Permissions.requireDeclared(context, DocumentPermissions.ARCHIVE_READ, development,
                "Reading issued documents");
            return runs.find(uuid(runId))
                .filter(run -> readable(run, context))
                .switchIfEmpty(Mono.error(new EntityNotFoundException("Unknown document: " + runId)));
        });
    }

    private boolean readable(DocumentRun run, RequestContext context) {
        if (!Permissions.allowsAll(context, run.permissions(), development)) {
            return false;
        }
        // Every template's datasets as they are now: a scope that has come to depend on the caller limits readers too.
        for (String template : run.templateVersions().keySet()) {
            if (!scopes.matches(template, run.scope(), context)) {
                return false;
            }
        }
        return scopes.matches(run.scope(), context);
    }

    private static ResponseEntity<byte[]> pdf(byte[] bytes, String fileName, String contentHash, String pdfHash) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(PDF_HASH_HEADER, pdfHash)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build()
                .toString());
        if (contentHash != null) {
            response.header(CONTENT_HASH_HEADER, contentHash);
        }
        return response.body(bytes);
    }

    /** {@code <number>.pdf}, or {@code <layout>-<run>.pdf} without a number; letters, digits, {@code . - _} only. */
    static String fileName(DocumentRun run) {
        String base = run.documentNo() != null && !run.documentNo().isBlank() ? run.documentNo()
            : run.layoutId() + "-" + run.runId();
        return base.replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf";
    }

    private static UUID uuid(String runId) {
        try {
            return UUID.fromString(runId);
        } catch (IllegalArgumentException e) {
            throw new EntityNotFoundException("Unknown document: " + runId);
        }
    }

    private static DocumentSummary summary(DocumentRun run) {
        return new DocumentSummary(run.runId().toString(), run.layoutId(), run.layoutVersion(), run.title(),
            run.documentNo(), run.subjectEntity(), run.subjectId(), run.language(), run.pageSize(), run.asOf(),
            run.readAt(), run.knownAt(), run.contentHash(), run.pdfHash(), run.pdfSize(), run.pageCount(),
            run.recomputable(), run.issuedBy(), run.issuedTime());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

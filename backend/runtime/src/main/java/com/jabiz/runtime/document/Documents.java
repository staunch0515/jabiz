package com.jabiz.runtime.document;

import com.jabiz.approval.ContentHash;
import com.jabiz.context.RequestContext;
import com.jabiz.document.DocumentContent;
import com.jabiz.document.DocumentLayout;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.ProjectedField;
import com.jabiz.query.custom.SemanticRow;
import com.jabiz.report.ReportColumn;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import com.jabiz.runtime.query.QueryTexts;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import com.jabiz.runtime.report.ArchivedValues;
import com.jabiz.runtime.report.ReportScopes;
import com.jabiz.runtime.report.ReportSettings;
import com.jabiz.runtime.security.Permissions;
import io.micrometer.common.KeyValues;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads and lays out documents (docs/design/22-documents.md section 3): every template of the layout is run at the
 * same point in time with the parameters it declares, masked fields masked whoever asks (an issued document is read
 * by others later, 10 section 13.1), its rows checked against the layout (one row where one is shown) and kept with
 * their column labels; the PDF is written off the event loop. The callers - issue, preview, verify - decide what is
 * kept.
 */
@Component
public class Documents {

    /**
     * What was read.
     *
     * @param content      everything the document shows
     * @param readAt       the effective time the templates were read at
     * @param knownAt      the recorded time they were read as of (null for everything recorded)
     * @param permissions  the layout's and its templates' permissions: reading the document needs them all
     * @param scope        the caller-dependent scope values of the templates' datasets, by dataset id
     * @param recomputable whether the templates read temporal entities only, so that reading again at the same point
     *                     must give the same rows
     */
    public record Read(DocumentContent content, Instant readAt, Instant knownAt, List<String> permissions,
        Map<String, Map<String, String>> scope, boolean recomputable) {}

    /** A laid-out document: its bytes, their SHA-256 and the number of pages. */
    public record Rendered(byte[] pdf, String pdfHash, int pages) {}

    private final DocumentLayoutRegistry layouts;
    private final SqlTemplateRegistry templates;
    private final AdvancedQueryExecutor executor;
    private final EntityDefinitionRegistry entities;
    private final QueryTexts texts;
    private final MessageCatalog messages;
    private final ReportSettings reports;
    private final ReportScopes scopes;
    private final DocumentSettings settings;
    private final PlatformObservations observations;
    private final boolean development;

    public Documents(DocumentLayoutRegistry layouts, SqlTemplateRegistry templates, AdvancedQueryExecutor executor,
        EntityDefinitionRegistry entities, QueryTexts texts, MessageCatalog messages, ReportSettings reports,
        ReportScopes scopes, DocumentSettings settings, PlatformObservations observations, Environment environment) {
        this.layouts = layouts;
        this.templates = templates;
        this.executor = executor;
        this.entities = entities;
        this.texts = texts;
        this.messages = messages;
        this.reports = reports;
        this.scopes = scopes;
        this.settings = settings;
        this.observations = observations;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
    }

    /** The layout of that id. @throws EntityNotFoundException when there is none */
    public DocumentLayout layout(String id) {
        return layouts.find(id).orElseThrow(() -> new EntityNotFoundException("Unknown document layout: " + id));
    }

    /** The layout's title in the language, its id when there is none. */
    public String title(DocumentLayout layout, Locale locale) {
        return messages.find(layout.titleKey(), locale).orElse(layout.id());
    }

    /** The layout's permissions and those of all its templates. */
    public List<String> permissions(DocumentLayout layout) {
        Set<String> all = new LinkedHashSet<>(layout.permissions());
        for (AdvancedQueryDefinition query : layouts.templatesOf(layout)) {
            all.addAll(query.permissions());
        }
        return List.copyOf(all);
    }

    /** @throws com.jabiz.runtime.security.PermissionDeniedException unless the caller has them all */
    public void requirePermissions(RequestContext context, DocumentLayout layout, String what) {
        Permissions.requireAll(context, permissions(layout), development, what);
    }

    /** The language a document is written in: the asked one, which must be one of the application's, or the caller's. */
    public Locale language(String asked, RequestContext context) {
        if (asked == null || asked.isBlank()) {
            return messages.supported(context.locale());
        }
        Locale locale = Locale.forLanguageTag(asked.trim());
        boolean known = messages.supportedLocales().stream()
            .anyMatch(supported -> supported.getLanguage().equals(locale.getLanguage()));
        if (!known) {
            throw new ValidationException(List.of(new Violation("language", PlatformErrorCodes.INVALID_VALUE,
                "language must be one of " + messages.supportedLocales())));
        }
        return Locale.forLanguageTag(locale.getLanguage());
    }

    /**
     * Runs the layout's templates at {@code at} (the caller's permissions are checked by the caller).
     *
     * @param time    the issue (or preview) time the document states
     * @param preview whether the document is a preview
     * @throws ValidationException             (as the error) for a parameter no template has, or a template's own
     *                                         parameter problems
     * @throws BusinessRuleViolationException  (as the error) {@code DOCUMENT_NOT_SINGLE} or {@code DOCUMENT_TOO_LARGE}
     */
    public Mono<Read> read(DocumentLayout layout, Map<String, Object> params, AdvancedQueryExecutor.At at,
        Instant time, Locale locale, boolean preview, RequestContext request) {
        Map<String, Object> given = params == null ? Map.of() : params;
        List<AdvancedQueryDefinition> queries = layouts.templatesOf(layout);
        List<Violation> unknown = new ArrayList<>();
        for (String name : given.keySet()) {
            if (queries.stream().noneMatch(q -> q.parameters().stream().anyMatch(p -> p.name().equals(name)))) {
                unknown.add(new Violation(name, PlatformErrorCodes.UNKNOWN_FIELD,
                    "Document layout " + layout.id() + " has no parameter [" + name + "]"));
            }
        }
        if (!unknown.isEmpty()) {
            return Mono.error(new ValidationException(unknown));
        }
        Map<String, Map<String, String>> scope = new TreeMap<>();
        for (AdvancedQueryDefinition query : queries) {
            scope.putAll(scopes.of(query, request));
        }
        boolean recomputable = queries.stream().allMatch(query -> query.participatingEntities().stream()
            .allMatch(entity -> entities.find(entity).map(def -> def.temporal).orElse(false)));
        List<Instant> slice = new ArrayList<>(2);
        return Flux.fromIterable(queries)
            .concatMap(query -> source(layout, query, given, at, locale, slice))
            .collectList()
            .map(sources -> {
                Map<String, DocumentContent.Source> byTemplate = new LinkedHashMap<>();
                sources.forEach(source -> byTemplate.put(source.templateId(), source));
                Map<String, String> labels = new LinkedHashMap<>();
                for (String key : layout.requiredMessages()) {
                    if (!key.equals(layout.titleKey())) {
                        labels.put(key, messages.find(key, locale).orElse(key));
                    }
                }
                String number = null;
                if (layout.number() != null) {
                    Object value = byTemplate.get(layout.number().template()).first(layout.number().column());
                    number = value == null ? null : String.valueOf(value);
                }
                DocumentContent content = new DocumentContent(layout.id(), layout.version(),
                    title(layout, locale), number, reports.company(),
                    locale.toLanguageTag(), time, preview, labels, byTemplate);
                return new Read(content, slice.isEmpty() ? time : slice.get(0), slice.size() < 2 ? null : slice.get(1),
                    permissions(layout), scope, recomputable);
            });
    }

    private Mono<DocumentContent.Source> source(DocumentLayout layout, AdvancedQueryDefinition query,
        Map<String, Object> given, AdvancedQueryExecutor.At at, Locale locale, List<Instant> slice) {
        Map<String, Object> own = new LinkedHashMap<>();
        query.parameters().forEach(p -> {
            if (given.containsKey(p.name())) {
                own.put(p.name(), given.get(p.name()));
            }
        });
        int maxRows = settings.maxRows();
        return executor.all(query, own, at, null, List.of(), maxRows + 1, true).flatMap(page -> {
            int rows = page.items().size();
            if (layout.singleRow(query.queryId()) && rows != 1) {
                return Mono.error(new BusinessRuleViolationException(new Violation(null,
                    PlatformErrorCodes.DOCUMENT_NOT_SINGLE, "Document " + layout.id() + " reads one row of "
                    + query.queryId() + ", found " + rows, Map.of("template", query.queryId(), "rows", rows))));
            }
            if (rows > maxRows) {
                return Mono.error(new BusinessRuleViolationException(new Violation(null,
                    PlatformErrorCodes.DOCUMENT_TOO_LARGE, "Document " + layout.id() + " has more than " + maxRows
                    + " rows of " + query.queryId(), Map.of("limit", maxRows + " rows"))));
            }
            if (slice.isEmpty() && page.slice() != null) {
                slice.add(page.slice().asOf());
                slice.add(page.slice().knownAt());
            }
            List<ReportColumn> columns = query.resultFields().stream()
                .map(field -> new ReportColumn(field.name(), label(layout, query, field, locale), field.kind()))
                .toList();
            List<List<Object>> values = new ArrayList<>(rows);
            for (SemanticRow row : page.items()) {
                List<Object> value = new ArrayList<>(columns.size());
                for (ProjectedField field : query.resultFields()) {
                    value.add(row.getRaw(field.name()));
                }
                values.add(value);
            }
            return Mono.just(new DocumentContent.Source(query.queryId(), query.version(), columns,
                ArchivedValues.normalize(columns, values)));
        });
    }

    /** {@code document.<layout>.<column>}, else the template's own text for the column. */
    private String label(DocumentLayout layout, AdvancedQueryDefinition query, ProjectedField field, Locale locale) {
        return messages.find(layout.labelKey(field.name()), locale)
            .orElseGet(() -> texts.column(query, field, locale));
    }

    /**
     * The content hash of a document: its layout and, by template, the column names and rows. Labels and layout
     * details are left out: they are kept with the document, but verifying it compares the data.
     */
    public static String contentHash(DocumentContent content) {
        Map<String, Object> sources = new TreeMap<>();
        content.sources().forEach((id, source) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("columns", source.columns().stream().map(ReportColumn::name).toList());
            entry.put("rows", ArchivedValues.normalize(source.columns(), source.rows()));
            sources.put(id, entry);
        });
        Map<String, Object> hashed = new LinkedHashMap<>();
        hashed.put("layout", content.layoutId());
        hashed.put("sources", sources);
        return ContentHash.of(hashed);
    }

    /**
     * The document as PDF, written off the event loop.
     *
     * @throws BusinessRuleViolationException (as the error) {@code DOCUMENT_TOO_LARGE} beyond
     *                                        {@code jabiz.documents.max-bytes}
     */
    public Mono<Rendered> render(DocumentLayout layout, DocumentContent content) {
        Locale locale = Locale.forLanguageTag(content.language());
        PdfDocumentWriter.Labels labels = new PdfDocumentWriter.Labels(
            texts.text("document.page", locale, PdfDocumentWriter.Labels.ENGLISH.page()),
            texts.text("document.preview", locale, PdfDocumentWriter.Labels.ENGLISH.preview()));
        Mono<Rendered> rendered = Mono.fromCallable(() -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int pages = PdfDocumentWriter.write(layout, content, reports.format(), labels, reports.fonts(),
                    settings.pageSize(), out);
                byte[] pdf = out.toByteArray();
                return new Rendered(pdf, sha256(pdf), pages);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(done -> done.pdf().length > settings.maxBytes()
                ? Mono.error(new BusinessRuleViolationException(new Violation(null,
                    PlatformErrorCodes.DOCUMENT_TOO_LARGE, "Document " + layout.id() + " is larger than "
                    + settings.maxBytes() + " bytes", Map.of("limit", settings.maxBytes() + " bytes"))))
                : Mono.just(done));
        return observations.mono(PlatformObservations.DOCUMENT_RENDER, "render " + layout.id(),
            KeyValues.of("layout", layout.id()), rendered);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}

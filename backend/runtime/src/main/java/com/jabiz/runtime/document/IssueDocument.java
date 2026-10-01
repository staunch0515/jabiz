package com.jabiz.runtime.document;

import com.jabiz.document.DocumentLayout;
import com.jabiz.document.DocumentRecipients;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The step of {@code DOCUMENT_ISSUE} (docs/design/22-documents.md section 4). The caller needs the layout's and its
 * templates' permissions besides the process's. The parts of the point in time not asked for are pinned to the issue
 * time, so that reading again at the archived point gives the same rows.
 */
@Component
class IssueDocument implements StepHandler<NoMetadata, ProcessContext> {

    private final Documents documents;
    private final DocumentRuns runs;
    private final DocumentSettings settings;
    private final EntityIdGenerator ids;

    IssueDocument(Documents documents, DocumentRuns runs, DocumentSettings settings, EntityIdGenerator ids) {
        this.documents = documents;
        this.runs = runs;
        this.settings = settings;
        this.ids = ids;
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            DocumentProcesses.IssueInput input = ctx.get(DocumentProcesses.INPUT, DocumentProcesses.IssueInput.class);
            DocumentLayout layout = documents.layout(input.layoutId());
            documents.requirePermissions(ctx.request(), layout, "Issuing document " + layout.id());
            Locale language = documents.language(input.language(), ctx.request());
            Instant issued = ctx.opTime();
            Map<String, Object> params = new LinkedHashMap<>(input.params() == null ? Map.of() : input.params());
            AdvancedQueryExecutor.At at = new AdvancedQueryExecutor.At(input.asOf(), input.knownAt())
                .pin(issued, issued);
            return documents.read(layout, params, at, issued, language, false, ctx.request())
                .flatMap(read -> documents.render(layout, read.content()).flatMap(rendered -> {
                    // A run is no entity of the metamodel: the generator is asked for a plain UUIDv7.
                    UUID runId = UUID.fromString(String.valueOf(ids.next(null)));
                    Map<String, String> versions = new TreeMap<>();
                    read.content().sources().forEach((id, source) -> versions.put(id, source.templateVersion()));
                    Object subject = layout.subjectParam() == null ? null : params.get(layout.subjectParam());
                    String contentHash = Documents.contentHash(read.content());
                    DocumentRun run = new DocumentRun(runId, layout.id(), layout.version(),
                        DocumentRuns.layoutSource(layout), versions, read.permissions(),
                        read.scope(), layout.subjectEntity(), subject == null ? null : String.valueOf(subject),
                        read.content().number(), read.content().title(), read.content().language(),
                        settings.pageSize().name(), params, input.asOf(), read.readAt(),
                        read.knownAt() != null ? read.knownAt() : issued, recipients(layout, read), read.content(),
                        contentHash,
                        read.recomputable(), rendered.pdf(), rendered.pdfHash(), rendered.pdf().length,
                        rendered.pages(), ctx.request().actorId(), issued, ctx.processSeqId());
                    return runs.insert(run).then(Mono.fromRunnable(() -> ctx.put(DocumentProcesses.OUTPUT,
                        new DocumentProcesses.IssueOutput(runId.toString(), run.documentNo(), contentHash,
                            rendered.pdfHash(), rendered.pages(), read.recomputable()))));
                }));
        });
    }

    /** The addresses the layout's recipients column holds; none without such a column. */
    static List<String> recipients(DocumentLayout layout, Documents.Read read) {
        if (layout.recipients() == null) {
            return List.of();
        }
        return DocumentRecipients.split(read.content().source(layout.recipients().template())
            .first(layout.recipients().column()));
    }
}

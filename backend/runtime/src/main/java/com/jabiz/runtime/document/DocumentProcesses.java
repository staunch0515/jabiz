package com.jabiz.runtime.document;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.process.steps.PublishEvent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code DOCUMENT_ISSUE} (docs/design/22-documents.md section 4): reads a document's templates at one point in time,
 * lays it out as PDF and keeps the bytes - every later print is these bytes - with what it shows and its content hash,
 * so that it can be verified against the data. Business processes issue documents by calling it as a sub-process
 * ({@code CallProcess.of("DOCUMENT_ISSUE", 1, ...)}). Announces {@code jabiz.document.issued}.
 */
@Configuration
public class DocumentProcesses {

    public static final String ISSUE = "DOCUMENT_ISSUE";
    public static final String ISSUED_EVENT = "jabiz.document.issued";

    static final String INPUT = "input";
    static final String OUTPUT = "output";

    /**
     * @param layoutId the document layout
     * @param params   the parameters of its templates
     * @param asOf     the effective time to read at, such as the end of the document's date; default the issue time
     * @param knownAt  the recorded time to read as of; default the issue time
     * @param language the document's language, one of the application's; default the caller's
     */
    public record IssueInput(@NotBlank String layoutId, Map<String, Object> params, Instant asOf, Instant knownAt,
        @Size(max = 10) String language) {}

    /**
     * @param recomputable whether the templates read temporal entities only, so that {@code verify} can compare the
     *                     content with the data
     */
    public record IssueOutput(String runId, String documentNo, String contentHash, String pdfHash, int pages,
        boolean recomputable) {}

    @Bean
    ProcessDefinition<IssueInput, IssueOutput, ProcessContext> documentIssueProcess() {
        return ProcessDefinition.define(ISSUE, 1, IssueInput.class, IssueOutput.class, ProcessContext.class, pb -> pb
            .description("Issues a business document: lays it out as PDF and keeps it exactly as issued.")
            .permissions(DocumentPermissions.ISSUE)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, IssueOutput.class))
            .step("Read, lay out and keep the document", IssueDocument.class, NoMetadata.INSTANCE)
            .step("Announce it", PublishEvent.of(ISSUED_EVENT, DocumentProcesses::issued)));
    }

    private static Map<String, Object> issued(ProcessContext ctx) {
        IssueInput input = ctx.get(INPUT, IssueInput.class);
        IssueOutput output = ctx.get(OUTPUT, IssueOutput.class);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", output.runId());
        payload.put("layoutId", input.layoutId());
        payload.put("documentNo", output.documentNo());
        payload.put("contentHash", output.contentHash());
        payload.put("pdfHash", output.pdfHash());
        return payload;
    }
}

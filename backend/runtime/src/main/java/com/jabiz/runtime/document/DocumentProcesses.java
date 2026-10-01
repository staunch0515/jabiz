package com.jabiz.runtime.document;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.RetryPolicy;
import com.jabiz.runtime.process.steps.PublishEvent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
    public static final String SEND = "DOCUMENT_SEND";
    public static final String ISSUED_EVENT = "jabiz.document.issued";

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String DELIVERIES = "deliveries";

    /** Five attempts, the first retry after a second, then doubling: as task notifications. */
    static final RetryPolicy RETRY = new RetryPolicy(5, Duration.ofSeconds(1));

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

    /**
     * @param runId the issued document
     * @param to    the addresses; empty for those the document's data names (the layout's recipients column)
     */
    public record SendInput(@NotBlank String runId, @Size(max = 50) List<@Size(max = 400) String> to) {}

    /** @param addresses the addresses a delivery was recorded for, in the order of the deliveries */
    public record SendOutput(List<String> deliveryIds, List<String> addresses) {}

    /**
     * {@code DOCUMENT_SEND} (22 section 5): sends an issued document by e-mail, its kept PDF attached, one message per
     * address. The deliveries are recorded in the transaction and sent after the commit, retried, with every attempt
     * recorded.
     */
    @Bean
    ProcessDefinition<SendInput, SendOutput, ProcessContext> documentSendProcess() {
        return ProcessDefinition.define(SEND, 1, SendInput.class, SendOutput.class, ProcessContext.class, pb -> pb
            .description("Sends an issued document by e-mail with its kept PDF attached.")
            .permissions(DocumentPermissions.SEND)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, SendOutput.class))
            .step("Record the deliveries", RecordDeliveries.class, NoMetadata.INSTANCE)
            .afterCommit("Send them", SendDocuments.class, NoMetadata.INSTANCE, RETRY));
    }

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

package com.jabiz.app.commerce;

import com.jabiz.document.DocumentLayout;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.document.DocumentProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

import static com.jabiz.app.commerce.CommerceEntities.ORDER;
import static com.jabiz.app.commerce.CommerceEntities.ORDER_DATASET;

/**
 * The order confirmation, the platform's example of a business document (docs/design/22-documents.md): the customer,
 * the order's number, time and warehouse, its lines and total, read from two SQL templates. {@code
 * ORDER_CONFIRMATION_ISSUE} issues it for an order as at the time it was placed - a product or warehouse renamed
 * later still shows its name of that time - and keeps the PDF exactly as issued.
 */
public final class OrderConfirmations {

    public static final String LAYOUT_ID = "commerce.order_confirmation";
    public static final String HEADER = "commerce.order_document_header";
    public static final String LINES = "commerce.order_document_lines";
    public static final String ISSUE = "ORDER_CONFIRMATION_ISSUE";

    public static final DocumentLayout LAYOUT = DocumentLayout.define(LAYOUT_ID, d -> d
        .permissions("commerce.order.read")
        .subject(ORDER, "orderId")
        .number(HEADER, "orderNo")
        .party("customer", HEADER, "customerCode")
        .facts(HEADER, "orderNo", "orderedTime", "warehouseName")
        .table(LINES, "lineNo", "sku", "productName", "quantity", "unitPrice", "lineAmount")
        .totals(HEADER, "totalAmount")
        .note("thanks"));

    public record IssueInput(@NotBlank String orderId) {}

    /** The issued document, as {@code DOCUMENT_ISSUE} gave it. */
    public record IssueOutput(String runId, String documentNo, String pdfHash) {}

    private static final String ORDER_ID = "orderId";
    private static final String ORDER_KEY = "order";
    private static final String ISSUED = "issued";

    public static final ProcessDefinition<IssueInput, IssueOutput, ProcessContext> ISSUE_PROCESS =
        ProcessDefinition.define(ISSUE, 1, IssueInput.class, IssueOutput.class, ProcessContext.class, pb -> pb
            .description("Issues the confirmation of a sales order as a PDF kept exactly as issued.")
            .permissions("commerce.order.confirm")
            .actsOn(ORDER, ORDER_ID)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(ORDER_ID, input.orderId());
                return ctx;
            })
            .outputMapper(ctx -> {
                DocumentProcesses.IssueOutput issued = ctx.get(ISSUED, DocumentProcesses.IssueOutput.class);
                return new IssueOutput(issued.runId(), issued.documentNo(), issued.pdfHash());
            })
            .step("Load the order", LoadEntity.by(ORDER_DATASET, ORDER_ID, ORDER_KEY))
            // Read as at the order's time: names changed since then do not change what the customer was sent.
            .step("Issue the confirmation", CallProcess.<ProcessContext>of(DocumentProcesses.ISSUE, 1,
                ctx -> new DocumentProcesses.IssueInput(LAYOUT_ID, Map.of(ORDER_ID, ctx.get(ORDER_ID)),
                    orderedTime(ctx.get(ORDER_KEY, EntityInstance.class)), null, null), ISSUED)));

    private OrderConfirmations() {}

    private static Instant orderedTime(EntityInstance order) {
        Object time = order.get("orderedTime");
        return time instanceof OffsetDateTime t ? t.toInstant() : (Instant) time;
    }
}

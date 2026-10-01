package com.jabiz.finance.ar;

import com.jabiz.document.DocumentLayout;
import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.BookingTime;
import com.jabiz.finance.company.CompanyEntities;
import com.jabiz.finance.company.CompanyProcesses;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.document.DocumentProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The documents of invoices and credit memos (FIN-AR-005; docs/finance/00-design.md section 8): the company, the
 * customer with its addresses, the lines, the tax by jurisdiction, the totals, the terms and due date and, on an
 * invoice, the remittance instructions. {@code FIN_INVOICE_ISSUE} issues one for a posted document through the
 * platform's {@code DOCUMENT_ISSUE}, which keeps the PDF exactly as issued: reprints are that copy
 * (platform decision D30). {@code FIN_INVOICE_SEND} issues it and e-mails it to the customer's contact.
 *
 * <p>The data is read as at the end of the document's date, so the customer's address is the one valid on that day
 * (FIN-AR-001 acceptance 2); a document posted after its date is read as at its posting, the first time it existed.
 */
public final class InvoiceDocuments {

    public static final String INVOICE_LAYOUT = "finance.ar.invoice";
    public static final String CREDIT_MEMO_LAYOUT = "finance.ar.credit_memo";

    public static final String HEADER = "finance.ar.invoice_document_header";
    public static final String LINES = "finance.ar.invoice_document_lines";
    public static final String TAXES = "finance.ar.invoice_document_taxes";
    public static final String COMPANY = "finance.company.profile";

    public static final String ISSUE = "FIN_INVOICE_ISSUE";
    public static final String SEND = "FIN_INVOICE_SEND";

    public static final String NOT_POSTED = "FIN_INVOICE_NOT_POSTED";
    public static final String NOT_ISSUABLE = "FIN_INVOICE_NOT_ISSUABLE";
    public static final String NO_COMPANY = "FIN_COMPANY_PROFILE_MISSING";

    public static final DocumentLayout INVOICE = layout(INVOICE_LAYOUT, true);
    public static final DocumentLayout CREDIT_MEMO = layout(CREDIT_MEMO_LAYOUT, false);

    // An invoice asks to be paid; a credit memo names what it credits and is not paid.
    private static DocumentLayout layout(String id, boolean invoice) {
        return DocumentLayout.define(id, d -> {
            d.permissions(FinancePermissions.AR_READ)
                .subject(InvoiceEntities.INVOICE, "invoiceId")
                .number(HEADER, "invoiceNo")
                .recipients(HEADER, "contactEmail")
                .party("from", COMPANY, "legalName", "street", "cityLine", "country", "phone", "email")
                .party("billTo", HEADER, "customerName", "contactName", "billingStreet", "billingCityLine",
                    "billingCountry")
                .party("shipTo", HEADER, "customerName", "shippingStreet", "shippingCityLine", "shippingCountry");
            if (invoice) {
                d.facts(HEADER, "invoiceNo", "invoiceDate", "terms", "dueDate", "customerCode", "reference",
                    "currency");
            } else {
                d.facts(HEADER, "invoiceNo", "invoiceDate", "originalInvoiceNo", "customerCode", "reference",
                    "currency");
            }
            d.table(LINES, "lineNo", "description", "quantity", "unitPrice", "amount", "taxCode")
                .table(TAXES, "jurisdiction", "base", "rate", "tax")
                .totals(HEADER, "subtotal", "taxTotal", "total");
            if (invoice) {
                d.text("remittance", COMPANY, "remittance").note("thanks");
            } else {
                d.note("credit");
            }
        });
    }

    public record InvoiceId(@NotNull UUID invoiceId) {}

    /** The issued document, as {@code DOCUMENT_ISSUE} gave it. */
    public record IssueOutput(String runId, String documentNo, String pdfHash) {}

    /** What was issued and the addresses it goes to. */
    public record SendOutput(String runId, String documentNo, List<String> addresses) {}

    static final String INVOICE_ID = "invoiceId";
    static final String INVOICE_KEY = "invoice";
    static final String PROFILES = "profiles";
    static final String ISSUE_INPUT = "issueInput";
    static final String ISSUED = "issued";
    static final String SENT = "sent";

    public static ProcessDefinition<InvoiceId, IssueOutput, ProcessContext> issueProcess(BookingTime booking) {
        return ProcessDefinition.define(ISSUE, 1, InvoiceId.class, IssueOutput.class, ProcessContext.class, pb -> pb
            .description("Issues the document of a posted invoice or credit memo as a PDF kept exactly as issued.")
            .permissions(FinancePermissions.INVOICE_ISSUE)
            .actsOn(InvoiceEntities.INVOICE, INVOICE_ID, a -> a.whenField("status", InvoiceEntities.POSTED))
            .contextFactory(InvoiceDocuments::withId)
            .outputMapper(ctx -> {
                DocumentProcesses.IssueOutput issued = ctx.get(ISSUED, DocumentProcesses.IssueOutput.class);
                return new IssueOutput(issued.runId(), issued.documentNo(), issued.pdfHash());
            })
            .step("Load the document", LoadEntity.by(InvoiceEntities.INVOICE_DATASET, INVOICE_ID, INVOICE_KEY))
            .step("Load the company", QueryEntities.of(CompanyEntities.PROFILE_DATASET,
                ctx -> CompanyProcesses.current(), PROFILES))
            .compute("Check it can be issued", (metadata, ctx) -> prepare(ctx, booking))
            .step("Issue it", issue()));
    }

    /**
     * Issues the document (as {@link #issueProcess}) and sends it to the customer's contact in the same transaction;
     * the e-mail leaves after the commit and is recorded with the document.
     */
    public static ProcessDefinition<InvoiceId, SendOutput, ProcessContext> sendProcess(BookingTime booking) {
        return ProcessDefinition.define(SEND, 1, InvoiceId.class, SendOutput.class, ProcessContext.class, pb -> pb
            .description("Issues the document of a posted invoice or credit memo and e-mails it to the customer.")
            .permissions(FinancePermissions.INVOICE_ISSUE)
            .actsOn(InvoiceEntities.INVOICE, INVOICE_ID, a -> a.whenField("status", InvoiceEntities.POSTED))
            .contextFactory(InvoiceDocuments::withId)
            .outputMapper(ctx -> {
                DocumentProcesses.IssueOutput issued = ctx.get(ISSUED, DocumentProcesses.IssueOutput.class);
                DocumentProcesses.SendOutput sent = ctx.get(SENT, DocumentProcesses.SendOutput.class);
                return new SendOutput(issued.runId(), issued.documentNo(), sent.addresses());
            })
            .step("Load the document", LoadEntity.by(InvoiceEntities.INVOICE_DATASET, INVOICE_ID, INVOICE_KEY))
            .step("Load the company", QueryEntities.of(CompanyEntities.PROFILE_DATASET,
                ctx -> CompanyProcesses.current(), PROFILES))
            .compute("Check it can be issued", (metadata, ctx) -> prepare(ctx, booking))
            .step("Issue it", issue())
            .step("Send it to the customer", CallProcess.<ProcessContext>when(ctx -> ctx.contains(ISSUED),
                DocumentProcesses.SEND, 1, ctx -> new DocumentProcesses.SendInput(
                    ctx.get(ISSUED, DocumentProcesses.IssueOutput.class).runId(), List.of()), SENT)));
    }

    private static com.jabiz.process.StepSpec<CallProcess.Metadata<ProcessContext>, ProcessContext> issue() {
        return CallProcess.<ProcessContext>when(ctx -> ctx.contains(ISSUE_INPUT), DocumentProcesses.ISSUE, 1,
            ctx -> ctx.get(ISSUE_INPUT, DocumentProcesses.IssueInput.class), ISSUED);
    }

    static void prepare(ProcessContext ctx, BookingTime booking) {
        EntityInstance invoice = ctx.get(INVOICE_KEY, EntityInstance.class);
        String number = invoice.get("invoiceNo");
        String status = invoice.get("status");
        if (!InvoiceEntities.POSTED.equals(status)) {
            ctx.reject(new Violation("invoiceId", NOT_POSTED, "Only a posted document is issued; "
                + (number == null ? "a draft" : number) + " is " + status, Map.of("status", status)));
            return;
        }
        Instant posted = instant(invoice.get("postedTime"));
        if (InvoiceEntities.OPENING.equals(invoice.get("source")) || posted == null) {
            // An open item of the legacy system: its document was issued there.
            ctx.reject(new Violation("invoiceId", NOT_ISSUABLE, number + " was not posted here: its document is the "
                + "one the earlier system issued", Map.of("invoiceNo", String.valueOf(number))));
            return;
        }
        @SuppressWarnings("unchecked")
        List<EntityInstance> profiles = (List<EntityInstance>) ctx.get(PROFILES);
        if (profiles == null || profiles.isEmpty()) {
            ctx.reject(new Violation("invoiceId", NO_COMPANY, "The company's profile is not set: documents show "
                + "its name, address and remittance instructions", Map.of()));
            return;
        }
        boolean creditMemo = InvoiceEntities.CREDIT_MEMO.equals(invoice.get("kind"));
        ctx.put(ISSUE_INPUT, new DocumentProcesses.IssueInput(creditMemo ? CREDIT_MEMO_LAYOUT : INVOICE_LAYOUT,
            Map.of(INVOICE_ID, invoice.id().toString()), readAt(booking, invoice.get("invoiceDate"), posted), null,
            null));
    }

    /**
     * The last moment of the document's date, or its posting when that came later: versions in effect from the next
     * day, such as a customer's new address, are not read.
     */
    static Instant readAt(BookingTime booking, Object invoiceDate, Instant posted) {
        LocalDate date = invoiceDate instanceof LocalDate d ? d : LocalDate.parse(String.valueOf(invoiceDate));
        Instant endOfDay = booking.endOf(date).minus(1, ChronoUnit.MICROS);
        return posted.isAfter(endOfDay) ? posted : endOfDay;
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof OffsetDateTime t) {
            return t.toInstant();
        }
        return value == null ? null : Instant.parse(String.valueOf(value));
    }

    private static ProcessContext withId(com.jabiz.process.ProcessStart start, InvoiceId input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INVOICE_ID, input.invoiceId());
        return ctx;
    }

    private InvoiceDocuments() {}
}

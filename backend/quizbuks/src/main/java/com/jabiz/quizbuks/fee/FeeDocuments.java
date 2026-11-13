package com.jabiz.quizbuks.fee;

import com.jabiz.document.DocumentLayout;
import com.jabiz.quizbuks.QbPermissions;

/**
 * The invoice as a platform document (Q87, D-Q87-5): the issuer ({@code qb.issuer.*}, the receipts' template), the
 * sponsor, the invoice's facts, its charges as lines and the totals; kept exactly as issued. The sponsor issues it
 * from their wallet page ({@code QB_FEE_INVOICE_DOCUMENT}); its templates read the sponsor's own datasets, so another
 * sponsor's invoice yields nothing (CLAUDE.md: a layout a sponsor can read is limited to their own subjects).
 */
public final class FeeDocuments {

    public static final DocumentLayout LAYOUT = DocumentLayout.define(FeeProcesses.LAYOUT_ID, d -> d
        .permissions(QbPermissions.TOPUP)
        .subject(QbFees.INVOICE, "invoiceId")
        .number(FeeProcesses.DOCUMENT_TEMPLATE, "invoiceNo")
        .party("issuer", FeeProcesses.ISSUER_TEMPLATE, "name", "address", "registrationNo")
        .party("sponsor", FeeProcesses.DOCUMENT_TEMPLATE, "company", "contactName")
        .facts(FeeProcesses.DOCUMENT_TEMPLATE, "period", "billingMode", "issuedAt", "dueOn", "status", "creditApplied")
        .table(FeeProcesses.LINES_TEMPLATE, "kind", "period", "description", "amount", "tax", "total")
        .totals(FeeProcesses.DOCUMENT_TEMPLATE, "amount", "tax", "total")
        .note("note"));

    /**
     * F-M7: a credit note as a platform document: the issuer, the sponsor, the invoice it credits, why, the tax rate,
     * what it gave back; the invoices its credit paid as lines (as at its issue); and its totals. The sponsor issues it
     * from their fee account ({@code QB_FEE_CREDIT_NOTE_DOCUMENT}); its templates read the sponsor's own datasets.
     */
    public static final DocumentLayout CREDIT_NOTE_LAYOUT = DocumentLayout.define(QbCreditNotes.LAYOUT_ID, d -> d
        .permissions(QbPermissions.TOPUP)
        .subject(QbCreditNotes.CREDIT_NOTE, "creditNoteId")
        .number(CreditNotes.DOCUMENT_TEMPLATE, "creditNoteNo")
        .party("issuer", FeeProcesses.ISSUER_TEMPLATE, "name", "address", "registrationNo")
        .party("sponsor", CreditNotes.DOCUMENT_TEMPLATE, "company", "contactName")
        .facts(CreditNotes.DOCUMENT_TEMPLATE, "invoiceNo", "reason", "endReason", "usedDays", "explanation", "taxRate",
            "issuedAt", "refundedCash", "refundedCredit", "voidedCredit")
        .table(CreditNotes.LINES_TEMPLATE, "invoiceNo", "usedAt", "amount")
        .totals(CreditNotes.DOCUMENT_TEMPLATE, "amount", "tax", "total")
        .note("note"));

    private FeeDocuments() {}
}

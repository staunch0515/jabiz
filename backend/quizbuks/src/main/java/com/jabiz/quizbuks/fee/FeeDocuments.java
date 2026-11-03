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
        .facts(FeeProcesses.DOCUMENT_TEMPLATE, "period", "billingMode", "issuedAt", "dueOn", "status")
        .table(FeeProcesses.LINES_TEMPLATE, "kind", "period", "description", "amount", "tax", "total")
        .totals(FeeProcesses.DOCUMENT_TEMPLATE, "amount", "tax", "total")
        .note("note"));

    private FeeDocuments() {}
}

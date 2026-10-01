package com.jabiz.finance.ar;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import com.jabiz.runtime.ledger.LedgerEntities;

import java.math.BigDecimal;
import java.util.List;

/**
 * The receivables' cash side and what follows from it (FIN-AR-007, 008, 012, 014; docs/finance/00-design.md section 8):
 * <ul>
 *   <li>{@code FinReceipt}: money received from a customer into a bank account, posted when recorded; what is not
 *       applied to invoices waits as unapplied cash. Written only by {@link ReceiptProcesses}.</li>
 *   <li>{@code FinWriteOff}: an invoice's open amount written off against the allowance once approved, and what was
 *       recovered of it later. Written only by {@link WriteOffProcesses}.</li>
 *   <li>{@code FinRecurringInvoice} with its lines: a template the monthly run makes an invoice from, once per
 *       period; kept by the receivables clerk through the data views.</li>
 * </ul>
 */
public final class ReceiptEntities {

    public static final String RECEIPT = "FinReceipt";
    public static final String WRITE_OFF = "FinWriteOff";
    public static final String RECURRING = "FinRecurringInvoice";
    public static final String RECURRING_LINE = "FinRecurringInvoiceLine";

    public static final String RECEIPT_DATASET = "urn:jabiz:dataset:default:FinReceipt";
    public static final String WRITE_OFF_DATASET = "urn:jabiz:dataset:default:FinWriteOff";
    public static final String RECURRING_DATASET = "urn:jabiz:dataset:default:FinRecurringInvoice";
    public static final String RECURRING_LINE_DATASET = "urn:jabiz:dataset:default:FinRecurringInvoiceLine";

    public static final String METHODS = "urn:jabiz:dict:finance:receipt-method";
    public static final String RECEIPT_STATUSES = "urn:jabiz:dict:finance:receipt-status";
    public static final String WRITE_OFF_STATUSES = "urn:jabiz:dict:finance:write-off-status";

    public static final List<String> METHOD_VALUES = List.of("CHECK", "ACH", "WIRE", "CARD");

    public static final String POSTED = "POSTED";
    public static final String VOID = "VOID";
    public static final List<String> RECEIPT_STATUS_VALUES = List.of(POSTED, VOID);

    /** Waiting for approval; posted; rejected by an approver; or approved but no longer possible. */
    public static final String PENDING = "PENDING";
    public static final String REJECTED = "REJECTED";
    public static final String REFUSED = "REFUSED";
    public static final List<String> WRITE_OFF_STATUS_VALUES = List.of(PENDING, POSTED, REJECTED, REFUSED);

    /** The entity the general ledger's lines of a receipt refer to (FIN-GL-021). */
    public static final String SOURCE_ENTITY = RECEIPT;

    public static final EntityDefinition RECEIPT_ENTITY = EntityDefinition.define(RECEIPT, eb -> {
        eb.physicalTable("fi_receipt_version");
        eb.primaryKey("receiptId");
        eb.field("receiptId", f -> f.physicalColumn("receipt_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:receipt"));
        eb.field("receiptNo", f -> f.physicalColumn("receipt_no").processOnly().asText(40));
        // The payer; moved to another customer only while nothing of it is applied (FIN-AR-008).
        eb.field("customerCode", f -> f.physicalColumn("customer_code").required(true).processOnly().asText(20));
        eb.field("receiptDate", f -> f.physicalColumn("receipt_date").immutable(true).required(true).asDate());
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asNumeric(15, 2));
        eb.field("currency", f -> f.physicalColumn("currency").immutable(true).required(true).asText(3));
        eb.field("method", f -> f.physicalColumn("method").immutable(true).required(true)
            .asCode(METHODS, values(METHOD_VALUES)));
        eb.field("reference", f -> f.physicalColumn("reference").immutable(true).asText(100));
        // The bank account's general ledger account it was deposited to (a BANK control account).
        eb.field("bankAccount", f -> f.physicalColumn("bank_account").immutable(true).required(true).asText(20));
        eb.field("description", f -> f.physicalColumn("description").immutable(true).asText(500));
        eb.field("unappliedAmount", f -> f.physicalColumn("unapplied_amount").required(true).processOnly()
            .asNumeric(15, 2));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(RECEIPT_STATUSES, values(RECEIPT_STATUS_VALUES)));
        eb.field("preparedBy", f -> f.physicalColumn("prepared_by").processOnly().asText(100));
        eb.field("voidDate", f -> f.physicalColumn("void_date").processOnly().asDate());
        eb.field("voidReason", f -> f.physicalColumn("void_reason").processOnly().asText(500));
        eb.field("voidGlNo", f -> f.physicalColumn("void_gl_no").processOnly().asText(40));
        eb.unique("uk_fi_receipt_no", "receiptNo");
        eb.display("receiptNo");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("receiptNo", "customerCode", "receiptDate", "amount", "method", "reference", "bankAccount",
                "unappliedAmount", "status")
            .filters("receiptNo", "customerCode", "receiptDate", "status", "reference", "bankAccount")
            .sorts("receiptNo", "receiptDate", "amount", "unappliedAmount")
            .defaultSort("receiptDate", false));
    });

    public static final EntityDefinition WRITE_OFF_ENTITY = EntityDefinition.define(WRITE_OFF, eb -> {
        eb.physicalTable("fi_write_off_version");
        eb.primaryKey("writeOffId");
        eb.field("writeOffId", f -> f.physicalColumn("write_off_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:write-off"));
        eb.field("invoiceId", f -> f.physicalColumn("invoice_id").immutable(true).required(true)
            .asReference(InvoiceEntities.INVOICE));
        eb.field("invoiceNo", f -> f.physicalColumn("invoice_no").immutable(true).required(true).asText(40));
        eb.field("customerCode", f -> f.physicalColumn("customer_code").immutable(true).required(true).asText(20));
        eb.field("writeOffDate", f -> f.physicalColumn("write_off_date").immutable(true).required(true).asDate());
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asNumeric(15, 2));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).required(true).asText(500));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(WRITE_OFF_STATUSES, values(WRITE_OFF_STATUS_VALUES)));
        eb.field("requestedBy", f -> f.physicalColumn("requested_by").processOnly().asText(100));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").processOnly().asText(40));
        eb.field("contentHash", f -> f.physicalColumn("content_hash").processOnly().asText(64));
        // Why an approved write-off was not posted: the invoice was paid meanwhile, the period closed.
        eb.field("refusal", f -> f.physicalColumn("refusal").processOnly().asText(500));
        eb.field("glNo", f -> f.physicalColumn("gl_no").processOnly().asText(40));
        eb.field("transactionId", f -> f.physicalColumn("transaction_id").processOnly()
            .asReference(LedgerEntities.TRANSACTION));
        eb.field("applicationId", f -> f.physicalColumn("application_id").processOnly()
            .asReference(InvoiceEntities.APPLICATION));
        eb.field("recoveredAmount", f -> f.physicalColumn("recovered_amount").processOnly().asNumeric(15, 2));
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("invoiceNo", "customerCode", "writeOffDate", "amount", "status", "requestedBy",
                "recoveredAmount", "reason")
            .filters("invoiceId", "invoiceNo", "customerCode", "status", "writeOffDate")
            .sorts("writeOffDate", "amount")
            .defaultSort("writeOffDate", false));
    });

    public static final EntityDefinition RECURRING_ENTITY = EntityDefinition.define(RECURRING, eb -> {
        eb.physicalTable("fi_recurring_invoice_version");
        eb.primaryKey("templateId");
        eb.field("templateId", f -> f.physicalColumn("template_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:recurring-invoice"));
        eb.field("templateCode", f -> f.physicalColumn("template_code").immutable(true).required(true).asText(40)
            .apply(Rules.pattern("FIN_RECURRING_CODE_FORMAT", "[0-9A-Z][0-9A-Z_-]{0,39}")));
        eb.field("customerCode", f -> f.physicalColumn("customer_code").required(true).asText(20));
        eb.field("description", f -> f.physicalColumn("description").required(true).asText(500)
            .apply(Rules.notBlank("FIN_RECURRING_INVOICE_DESCRIPTION_BLANK")));
        // The day of the month the invoice is dated; a short month dates it on its last day.
        eb.field("invoiceDay", f -> f.physicalColumn("invoice_day").required(true).asNumeric(2, 0)
            .apply(Rules.range("FIN_RECURRING_INVOICE_DAY_RANGE", BigDecimal.ONE, new BigDecimal("31"))));
        eb.field("startDate", f -> f.physicalColumn("start_date").required(true).asDate());
        eb.field("endDate", f -> f.physicalColumn("end_date").asDate());
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        eb.unique("uk_fi_recurring_invoice_code", "templateCode");
        eb.display("templateCode");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("templateCode", "customerCode", "description", "invoiceDay", "startDate", "endDate", "active")
            .filters("templateCode", "customerCode", "active")
            .sorts("templateCode")
            .defaultSort("templateCode", true));
    });

    public static final EntityDefinition RECURRING_LINE_ENTITY = EntityDefinition.define(RECURRING_LINE, eb -> {
        eb.physicalTable("fi_recurring_invoice_line_version");
        eb.primaryKey("templateLineId");
        eb.field("templateLineId", f -> f.physicalColumn("template_line_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:finance:recurring-invoice-line"));
        eb.field("templateId", f -> f.physicalColumn("template_id").immutable(true).required(true)
            .asReference(RECURRING));
        eb.field("lineNo", f -> f.physicalColumn("line_no").required(true).asNumeric(4, 0));
        eb.field("description", f -> f.physicalColumn("description").required(true).asText(500)
            .apply(Rules.notBlank("FIN_INVOICE_LINE_DESCRIPTION_BLANK")));
        eb.field("quantity", f -> f.physicalColumn("quantity").required(true).asNumeric(15, 4)
            .apply(Rules.range("FIN_INVOICE_QUANTITY_RANGE", new BigDecimal("0.0001"), null)));
        eb.field("unitPrice", f -> f.physicalColumn("unit_price").required(true).asNumeric(17, 4)
            .apply(Rules.range("FIN_INVOICE_PRICE_RANGE", BigDecimal.ZERO, null)));
        eb.field("revenueAccount", f -> f.physicalColumn("revenue_account").required(true).asText(20));
        eb.field("taxCode", f -> f.physicalColumn("tax_code").asText(20));
        eb.field("department", f -> f.physicalColumn("department").asText(20));
        eb.field("location", f -> f.physicalColumn("location").asText(20));
        eb.unique("uk_fi_recurring_invoice_line", "templateId", "lineNo");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("templateId", "lineNo", "description", "quantity", "unitPrice", "revenueAccount", "taxCode")
            .filters("templateId")
            .sorts("lineNo")
            .defaultSort("lineNo", true));
    });

    static String[] values(List<String> values) {
        return values.toArray(String[]::new);
    }

    private ReceiptEntities() {}
}

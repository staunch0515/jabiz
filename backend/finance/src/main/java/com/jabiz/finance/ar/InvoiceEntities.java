package com.jabiz.finance.ar;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import com.jabiz.runtime.ledger.LedgerEntities;

import java.math.BigDecimal;
import java.util.List;

/**
 * The receivables' documents (FIN-AR-003, 004, 006; docs/finance/00-design.md section 8). An invoice and a credit
 * memo are one entity, {@code FinInvoice}, told apart by {@code kind}: they share lines, tax and posting, and differ
 * in direction and numbering ({@code INV-}, {@code CM-}). A credit memo may refer to the invoice it credits, whose
 * date then sets its tax rates (FIN-TX-005). Drafts change; a posted document never does: it is corrected by a credit
 * memo or voided with a reversing entry (FIN-AR-004). {@code FinInvoiceTax} keeps how each jurisdiction's tax was
 * computed (FIN-UI-007), {@code FinApplication} each credit or receipt applied to an invoice; both are written once.
 * Everything is written only by {@link InvoiceProcesses}.
 */
public final class InvoiceEntities {

    public static final String INVOICE = "FinInvoice";
    public static final String LINE = "FinInvoiceLine";
    public static final String TAX = "FinInvoiceTax";
    public static final String APPLICATION = "FinApplication";

    public static final String INVOICE_DATASET = "urn:jabiz:dataset:default:FinInvoice";
    public static final String LINE_DATASET = "urn:jabiz:dataset:default:FinInvoiceLine";
    public static final String TAX_DATASET = "urn:jabiz:dataset:default:FinInvoiceTax";
    public static final String APPLICATION_DATASET = "urn:jabiz:dataset:default:FinApplication";

    public static final String KINDS = "urn:jabiz:dict:finance:ar-document-kind";
    public static final String STATUSES = "urn:jabiz:dict:finance:ar-document-status";
    public static final String SOURCES = "urn:jabiz:dict:finance:ar-document-source";

    public static final String INVOICE_KIND = "INVOICE";
    public static final String CREDIT_MEMO = "CREDIT_MEMO";
    public static final List<String> KIND_VALUES = List.of(INVOICE_KIND, CREDIT_MEMO);

    public static final String DRAFT = "DRAFT";
    public static final String POSTED = "POSTED";
    public static final String VOID = "VOID";
    public static final List<String> STATUS_VALUES = List.of(DRAFT, POSTED, VOID);

    /** Entered here, or an open item of the legacy system brought over by the migration (not posted again). */
    public static final String MANUAL = "MANUAL";
    public static final String OPENING = "OPENING";
    public static final List<String> SOURCE_VALUES = List.of(MANUAL, OPENING);

    /** The entity the general ledger's lines of a posted document refer to (FIN-GL-021). */
    public static final String SOURCE_ENTITY = INVOICE;

    public static final EntityDefinition INVOICE_ENTITY = EntityDefinition.define(INVOICE, eb -> {
        eb.physicalTable("fi_invoice_version");
        eb.primaryKey("invoiceId");
        eb.field("invoiceId", f -> f.physicalColumn("invoice_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:invoice"));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(KINDS, values(KIND_VALUES)));
        // Given when posted (FIN-AR-004); an opening item keeps the legacy number.
        eb.field("invoiceNo", f -> f.physicalColumn("invoice_no").processOnly().asText(40));
        eb.field("customerCode", f -> f.physicalColumn("customer_code").immutable(true).required(true).asText(20));
        eb.field("invoiceDate", f -> f.physicalColumn("invoice_date").required(true).asDate());
        eb.field("dueDate", f -> f.physicalColumn("due_date").processOnly().asDate());
        eb.field("currency", f -> f.physicalColumn("currency").required(true).asText(3));
        // US dollars per unit of the currency on the invoice date (1 for US dollars); fixed when posted.
        eb.field("exchangeRate", f -> f.physicalColumn("exchange_rate").processOnly().asNumeric(19, 10));
        eb.field("termsCode", f -> f.physicalColumn("terms_code").required(true).asText(20));
        // The tax code of the ship-to jurisdiction, from the customer unless given (FIN-TX-002).
        eb.field("taxCode", f -> f.physicalColumn("tax_code").required(true).asText(20));
        eb.field("description", f -> f.physicalColumn("description").asText(500));
        eb.field("reference", f -> f.physicalColumn("reference").asText(100));
        eb.field("originalInvoiceId", f -> f.physicalColumn("original_invoice_id").immutable(true)
            .asReference(INVOICE));
        eb.field("source", f -> f.physicalColumn("source").required(true).processOnly()
            .asCode(SOURCES, values(SOURCE_VALUES)));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUSES, values(STATUS_VALUES)));
        eb.field("subtotal", f -> f.physicalColumn("subtotal").processOnly().asNumeric(15, 2));
        eb.field("taxTotal", f -> f.physicalColumn("tax_total").processOnly().asNumeric(15, 2));
        eb.field("total", f -> f.physicalColumn("total").processOnly().asNumeric(15, 2));
        eb.field("totalUsd", f -> f.physicalColumn("total_usd").processOnly().asNumeric(15, 2));
        // What is still to be paid or applied, in the document's currency and in US dollars at its own rate.
        eb.field("openAmount", f -> f.physicalColumn("open_amount").processOnly().asNumeric(15, 2));
        eb.field("openAmountUsd", f -> f.physicalColumn("open_amount_usd").processOnly().asNumeric(15, 2));
        eb.field("glNo", f -> f.physicalColumn("gl_no").processOnly().asText(40));
        eb.field("transactionId", f -> f.physicalColumn("transaction_id").processOnly()
            .asReference(LedgerEntities.TRANSACTION));
        eb.field("voidDate", f -> f.physicalColumn("void_date").processOnly().asDate());
        eb.field("voidReason", f -> f.physicalColumn("void_reason").processOnly().asText(500));
        eb.field("voidGlNo", f -> f.physicalColumn("void_gl_no").processOnly().asText(40));
        eb.unique("uk_fi_invoice_no", "invoiceNo");
        eb.display("invoiceNo");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("invoiceNo", "kind", "customerCode", "invoiceDate", "dueDate", "currency", "total", "openAmount",
                "status")
            .filters("invoiceNo", "kind", "customerCode", "invoiceDate", "dueDate", "status", "source",
                "originalInvoiceId", "currency")
            .sorts("invoiceNo", "invoiceDate", "dueDate", "total", "openAmount")
            .defaultSort("invoiceDate", false));
    });

    public static final EntityDefinition LINE_ENTITY = EntityDefinition.define(LINE, eb -> {
        eb.physicalTable("fi_invoice_line_version");
        eb.primaryKey("lineId");
        eb.field("lineId", f -> f.physicalColumn("line_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:invoice-line"));
        eb.field("invoiceId", f -> f.physicalColumn("invoice_id").immutable(true).required(true)
            .asReference(INVOICE));
        eb.field("lineNo", f -> f.physicalColumn("line_no").immutable(true).required(true).asNumeric(4, 0));
        eb.field("description", f -> f.physicalColumn("description").required(true).asText(500)
            .apply(Rules.notBlank("FIN_INVOICE_LINE_DESCRIPTION_BLANK")));
        eb.field("quantity", f -> f.physicalColumn("quantity").required(true).asNumeric(15, 4)
            .apply(Rules.range("FIN_INVOICE_QUANTITY_RANGE", new BigDecimal("0.0001"), null)));
        eb.field("unitPrice", f -> f.physicalColumn("unit_price").required(true).asNumeric(17, 4)
            .apply(Rules.range("FIN_INVOICE_PRICE_RANGE", BigDecimal.ZERO, null)));
        eb.field("amount", f -> f.physicalColumn("amount").processOnly().asNumeric(15, 2));
        eb.field("revenueAccount", f -> f.physicalColumn("revenue_account").required(true).asText(20));
        // Its own tax code (NT for a service), else the invoice's.
        eb.field("taxCode", f -> f.physicalColumn("tax_code").asText(20));
        eb.field("department", f -> f.physicalColumn("department").asText(20));
        eb.field("location", f -> f.physicalColumn("location").asText(20));
        eb.unique("uk_fi_invoice_line", "invoiceId", "lineNo");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("lineNo", "description", "quantity", "unitPrice", "amount", "revenueAccount", "taxCode")
            .filters("invoiceId")
            .sorts("lineNo")
            .defaultSort("lineNo", true));
    });

    /**
     * How a posted document's tax came about, per jurisdiction (FIN-TX-003, FIN-UI-007), and how each line was taxed:
     * one row per jurisdiction ({@code lineNo} empty) and one per line (its code, kind, reason, certificate, tax).
     */
    public static final EntityDefinition TAX_ENTITY = EntityDefinition.define(TAX, eb -> {
        eb.physicalTable("fi_invoice_tax_version");
        eb.primaryKey("taxId");
        eb.field("taxId", f -> f.physicalColumn("tax_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:invoice-tax"));
        eb.field("invoiceId", f -> f.physicalColumn("invoice_id").immutable(true).required(true)
            .asReference(INVOICE));
        eb.field("lineNo", f -> f.physicalColumn("line_no").immutable(true).asNumeric(4, 0));
        eb.field("jurisdiction", f -> f.physicalColumn("jurisdiction").immutable(true).asText(20));
        eb.field("taxCode", f -> f.physicalColumn("tax_code").immutable(true).asText(20));
        eb.field("taxKind", f -> f.physicalColumn("tax_kind").immutable(true).asText(15));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).asText(30));
        eb.field("certificateNo", f -> f.physicalColumn("certificate_no").immutable(true).asText(40));
        eb.field("base", f -> f.physicalColumn("base").immutable(true).required(true).asNumeric(15, 2));
        eb.field("ratePercent", f -> f.physicalColumn("rate_percent").immutable(true).asNumeric(7, 4));
        eb.field("rateFrom", f -> f.physicalColumn("rate_from").immutable(true).asDate());
        eb.field("tax", f -> f.physicalColumn("tax").immutable(true).required(true).asNumeric(15, 2));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("lineNo", "jurisdiction", "taxCode", "taxKind", "reason", "certificateNo", "base", "ratePercent",
                "rateFrom", "tax")
            .filters("invoiceId", "jurisdiction", "taxCode")
            .sorts("lineNo", "jurisdiction")
            .defaultSort("jurisdiction", true));
    });

    /**
     * A credit memo (or, from F3c, a receipt) applied to an invoice on a day: written once; taking it back is a new
     * application of the opposite amount, so the history and any earlier day's open items stay as they were
     * (FIN-AR-008).
     */
    public static final EntityDefinition APPLICATION_ENTITY = EntityDefinition.define(APPLICATION, eb -> {
        eb.physicalTable("fi_application_version");
        eb.primaryKey("applicationId");
        eb.field("applicationId", f -> f.physicalColumn("application_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:finance:application"));
        eb.field("sourceKind", f -> f.physicalColumn("source_kind").immutable(true).required(true).asText(20));
        // The credit memo or receipt applied: two entities, so its identity as text.
        eb.field("sourceId", f -> f.physicalColumn("source_id").immutable(true).required(true).asText(36));
        eb.field("invoiceId", f -> f.physicalColumn("invoice_id").immutable(true).required(true)
            .asReference(INVOICE));
        eb.field("customerCode", f -> f.physicalColumn("customer_code").immutable(true).required(true).asText(20));
        eb.field("applicationDate", f -> f.physicalColumn("application_date").immutable(true).required(true)
            .asDate());
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asNumeric(15, 2));
        eb.field("amountUsd", f -> f.physicalColumn("amount_usd").immutable(true).required(true)
            .asNumeric(15, 2));
        eb.field("reversesApplicationId", f -> f.physicalColumn("reverses_application_id").immutable(true)
            .asReference(APPLICATION));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("applicationDate", "sourceKind", "sourceId", "invoiceId", "customerCode", "amount", "amountUsd")
            .filters("invoiceId", "sourceId", "customerCode", "applicationDate")
            .sorts("applicationDate")
            .defaultSort("applicationDate", true));
    });

    static String[] values(List<String> values) {
        return values.toArray(String[]::new);
    }

    private InvoiceEntities() {}
}

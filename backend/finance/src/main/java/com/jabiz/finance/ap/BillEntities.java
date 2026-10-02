package com.jabiz.finance.ap;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import com.jabiz.entity.TemporalRole;
import com.jabiz.file.FileKind;
import com.jabiz.runtime.ledger.LedgerEntities;

import java.math.BigDecimal;
import java.util.List;

/**
 * The payables' documents (FIN-AP-004…008, FIN-TX-007; docs/finance/00-design.md section 9). A vendor bill and a
 * vendor credit are one entity, {@code FinBill}, told apart by {@code kind}, numbered without gaps when posted
 * ({@code BILL-}, {@code VC-}); the vendor's own number is {@code vendorInvoiceNo}. Unlike an invoice, a bill posts at
 * once and its approval is a state of its own: a bill an approval rule stops is in the books but cannot be paid until
 * approved (FIN-AP-006). Drafts change; a posted document never does: it is corrected by a vendor credit or voided
 * with a reversing entry. {@code FinBillTax} keeps how each line's use tax was computed (FIN-TX-007),
 * {@code FinApApplication} each credit applied to a bill; both are written once. Bills are in US dollars until
 * foreign currency settlement (F7). Everything is written only by {@link BillProcesses}.
 */
public final class BillEntities {

    public static final String BILL = "FinBill";
    public static final String LINE = "FinBillLine";
    public static final String TAX = "FinBillTax";
    public static final String APPLICATION = "FinApApplication";

    public static final String BILL_DATASET = "urn:jabiz:dataset:default:FinBill";
    public static final String LINE_DATASET = "urn:jabiz:dataset:default:FinBillLine";
    public static final String TAX_DATASET = "urn:jabiz:dataset:default:FinBillTax";
    public static final String APPLICATION_DATASET = "urn:jabiz:dataset:default:FinApApplication";

    public static final String KINDS = "urn:jabiz:dict:finance:ap-document-kind";
    public static final String STATUSES = "urn:jabiz:dict:finance:ap-document-status";
    public static final String SOURCES = "urn:jabiz:dict:finance:ap-document-source";
    public static final String APPROVALS = "urn:jabiz:dict:finance:ap-approval";

    public static final String BILL_KIND = "BILL";
    public static final String CREDIT = "CREDIT";
    public static final List<String> KIND_VALUES = List.of(BILL_KIND, CREDIT);

    public static final String DRAFT = "DRAFT";
    public static final String POSTED = "POSTED";
    public static final String VOID = "VOID";
    public static final List<String> STATUS_VALUES = List.of(DRAFT, POSTED, VOID);

    /** Entered here, or an open item of the legacy system brought over by the migration (not posted again). */
    public static final String MANUAL = "MANUAL";
    public static final String OPENING = "OPENING";
    public static final List<String> SOURCE_VALUES = List.of(MANUAL, OPENING);

    /** No rule stopped it; or it waits for, was given or was refused approval. Only the first and third are paid. */
    public static final String NOT_REQUIRED = "NOT_REQUIRED";
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final List<String> APPROVAL_VALUES = List.of(NOT_REQUIRED, PENDING, APPROVED, REJECTED);

    /** What an application applies to a bill (FIN-AP-008); payments add theirs in F4c. */
    public static final String CREDIT_SOURCE = "CREDIT";

    /** The bills' supporting documents: the vendor's invoice as received (FIN-AP-004). */
    public static final String BILL_FILES = "fin.bill";

    public static final EntityDefinition BILL_ENTITY = EntityDefinition.define(BILL, eb -> {
        eb.physicalTable("fi_bill_version");
        eb.primaryKey("billId");
        eb.field("billId", f -> f.physicalColumn("bill_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:bill"));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(KINDS, values(KIND_VALUES)));
        // Given when posted; an opening item keeps the legacy document number.
        eb.field("billNo", f -> f.physicalColumn("bill_no").processOnly().asText(40));
        eb.field("vendorCode", f -> f.physicalColumn("vendor_code").immutable(true).required(true).asText(20));
        eb.field("vendorInvoiceNo", f -> f.physicalColumn("vendor_invoice_no").required(true).asText(40)
            .apply(Rules.notBlank("FIN_BILL_VENDOR_INVOICE_BLANK")));
        // The vendor's number as the duplicate check compares it: its letters and digits in capitals (FIN-AP-005).
        eb.field("vendorInvoiceKey", f -> f.physicalColumn("vendor_invoice_key").processOnly().asText(40));
        eb.field("invoiceDate", f -> f.physicalColumn("invoice_date").required(true).asDate());
        eb.field("receivedDate", f -> f.physicalColumn("received_date").asDate());
        eb.field("dueDate", f -> f.physicalColumn("due_date").processOnly().asDate());
        eb.field("currency", f -> f.physicalColumn("currency").required(true).asText(3));
        eb.field("termsCode", f -> f.physicalColumn("terms_code").required(true).asText(20));
        eb.field("description", f -> f.physicalColumn("description").asText(500));
        // A vendor credit's bill, if it credits one.
        eb.field("originalBillId", f -> f.physicalColumn("original_bill_id").immutable(true).asReference(BILL));
        // The default 1099 form and box of the lines, from the vendor (FIN-AP-020).
        eb.field("form1099", f -> f.physicalColumn("form_1099").asText(10));
        eb.field("box1099", f -> f.physicalColumn("box_1099").asText(2));
        eb.field("attachmentFileId", f -> f.physicalColumn("attachment_file_id").kind(FileKind.of(BILL_FILES))
            .auditMasked());
        // Why a bill like another (same vendor, amount and date) was entered all the same (FIN-AP-005).
        eb.field("duplicateReason", f -> f.physicalColumn("duplicate_reason").asText(500));
        eb.field("source", f -> f.physicalColumn("source").required(true).processOnly()
            .asCode(SOURCES, values(SOURCE_VALUES)));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUSES, values(STATUS_VALUES)));
        eb.field("approval", f -> f.physicalColumn("approval").processOnly()
            .asCode(APPROVALS, values(APPROVAL_VALUES)));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").processOnly().asText(40));
        // Who entered the draft: the platform's approval never lets the preparer approve (FIN-CT-001).
        eb.field("preparedBy", f -> f.physicalColumn("prepared_by").processOnly().asText(100));
        eb.field("subtotal", f -> f.physicalColumn("subtotal").processOnly().asNumeric(15, 2));
        eb.field("useTaxTotal", f -> f.physicalColumn("use_tax_total").processOnly().asNumeric(15, 2));
        // What is owed the vendor: the lines; use tax is owed the state, not the vendor.
        eb.field("total", f -> f.physicalColumn("total").processOnly().asNumeric(15, 2));
        eb.field("openAmount", f -> f.physicalColumn("open_amount").processOnly().asNumeric(15, 2));
        eb.field("glNo", f -> f.physicalColumn("gl_no").processOnly().asText(40));
        eb.field("postedTime", f -> f.physicalColumn("posted_time").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("transactionId", f -> f.physicalColumn("transaction_id").processOnly()
            .asReference(LedgerEntities.TRANSACTION));
        eb.field("voidDate", f -> f.physicalColumn("void_date").processOnly().asDate());
        eb.field("voidReason", f -> f.physicalColumn("void_reason").processOnly().asText(500));
        eb.field("voidGlNo", f -> f.physicalColumn("void_gl_no").processOnly().asText(40));
        eb.unique("uk_fi_bill_no", "billNo");
        eb.display("billNo");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("billNo", "kind", "vendorCode", "vendorInvoiceNo", "invoiceDate", "dueDate", "total",
                "openAmount", "status", "approval")
            .filters("billNo", "kind", "vendorCode", "vendorInvoiceNo", "vendorInvoiceKey", "invoiceDate", "dueDate",
                "status", "approval", "source", "originalBillId", "openAmount")
            .sorts("billNo", "invoiceDate", "dueDate", "total", "openAmount")
            .defaultSort("invoiceDate", false));
    });

    public static final EntityDefinition LINE_ENTITY = EntityDefinition.define(LINE, eb -> {
        eb.physicalTable("fi_bill_line_version");
        eb.primaryKey("lineId");
        eb.field("lineId", f -> f.physicalColumn("line_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:bill-line"));
        eb.field("billId", f -> f.physicalColumn("bill_id").immutable(true).required(true).asReference(BILL));
        eb.field("lineNo", f -> f.physicalColumn("line_no").immutable(true).required(true).asNumeric(4, 0));
        eb.field("description", f -> f.physicalColumn("description").required(true).asText(500)
            .apply(Rules.notBlank("FIN_BILL_LINE_DESCRIPTION_BLANK")));
        eb.field("amount", f -> f.physicalColumn("amount").required(true).asNumeric(15, 2)
            .apply(Rules.range("FIN_BILL_AMOUNT_RANGE", new BigDecimal("0.01"), null)));
        // An expense or an asset cost account; a fixed-asset cost account makes an asset (FIN-AP-007).
        eb.field("account", f -> f.physicalColumn("account").required(true).asText(20));
        // A taxable purchase the vendor charged no tax on: use tax accrues at this code's rates (FIN-TX-007).
        eb.field("useTaxCode", f -> f.physicalColumn("use_tax_code").asText(20));
        eb.field("department", f -> f.physicalColumn("department").asText(20));
        eb.field("location", f -> f.physicalColumn("location").asText(20));
        // The line's 1099 form and box: the bill's unless overridden; none when not reportable (FIN-AP-020).
        eb.field("form1099", f -> f.physicalColumn("form_1099").asText(10));
        eb.field("box1099", f -> f.physicalColumn("box_1099").asText(2));
        eb.unique("uk_fi_bill_line", "billId", "lineNo");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("lineNo", "description", "amount", "account", "useTaxCode", "department", "form1099", "box1099")
            .filters("billId", "account")
            .sorts("lineNo")
            .defaultSort("lineNo", true));
    });

    /**
     * How a posted bill's use tax came about (FIN-TX-007): one row per jurisdiction ({@code lineNo} empty) and one per
     * line that accrued it.
     */
    public static final EntityDefinition TAX_ENTITY = EntityDefinition.define(TAX, eb -> {
        eb.physicalTable("fi_bill_tax_version");
        eb.primaryKey("taxId");
        eb.field("taxId", f -> f.physicalColumn("tax_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:bill-tax"));
        eb.field("billId", f -> f.physicalColumn("bill_id").immutable(true).required(true).asReference(BILL));
        eb.field("lineNo", f -> f.physicalColumn("line_no").immutable(true).asNumeric(4, 0));
        eb.field("jurisdiction", f -> f.physicalColumn("jurisdiction").immutable(true).asText(20));
        eb.field("taxCode", f -> f.physicalColumn("tax_code").immutable(true).asText(20));
        eb.field("base", f -> f.physicalColumn("base").immutable(true).required(true).asNumeric(15, 2));
        eb.field("ratePercent", f -> f.physicalColumn("rate_percent").immutable(true).asNumeric(7, 4));
        eb.field("rateFrom", f -> f.physicalColumn("rate_from").immutable(true).asDate());
        eb.field("tax", f -> f.physicalColumn("tax").immutable(true).required(true).asNumeric(15, 2));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("lineNo", "jurisdiction", "taxCode", "base", "ratePercent", "rateFrom", "tax")
            .filters("billId", "jurisdiction")
            .sorts("lineNo", "jurisdiction")
            .defaultSort("jurisdiction", true));
    });

    /**
     * A vendor credit applied to a bill on a day: written once; taking it back is a new application of the opposite
     * amount that names it (FIN-AP-008). Payments apply theirs from F4c.
     */
    public static final EntityDefinition APPLICATION_ENTITY = EntityDefinition.define(APPLICATION, eb -> {
        eb.physicalTable("fi_ap_application_version");
        eb.primaryKey("applicationId");
        eb.field("applicationId", f -> f.physicalColumn("application_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:finance:ap-application"));
        eb.field("sourceKind", f -> f.physicalColumn("source_kind").immutable(true).required(true).asText(20));
        eb.field("sourceId", f -> f.physicalColumn("source_id").immutable(true).required(true).asText(36));
        eb.field("sourceNo", f -> f.physicalColumn("source_no").immutable(true).asText(40));
        eb.field("billId", f -> f.physicalColumn("bill_id").immutable(true).required(true).asReference(BILL));
        eb.field("vendorCode", f -> f.physicalColumn("vendor_code").immutable(true).required(true).asText(20));
        eb.field("applicationDate", f -> f.physicalColumn("application_date").immutable(true).required(true)
            .asDate());
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asNumeric(15, 2));
        eb.field("discount", f -> f.physicalColumn("discount").immutable(true).asNumeric(15, 2));
        eb.field("reversesApplicationId", f -> f.physicalColumn("reverses_application_id").immutable(true)
            .asReference(APPLICATION));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).asText(500));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("applicationDate", "sourceKind", "sourceNo", "billId", "vendorCode", "amount", "discount",
                "reversesApplicationId", "reason")
            .filters("billId", "sourceId", "vendorCode", "applicationDate", "sourceKind")
            .sorts("applicationDate")
            .defaultSort("applicationDate", true));
    });

    static String[] values(List<String> values) {
        return values.toArray(String[]::new);
    }

    private BillEntities() {}
}

package com.jabiz.finance.ap;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

import java.util.List;

/**
 * Payments (FIN-AP-010…015, FIN-BK-011; docs/finance/00-design.md section 9). A payment run ({@code FinPaymentRun},
 * {@code PAY-RUN-01}) gathers what is to be paid from one bank account on one day by one method: bills of vendors, a
 * payment that is no bill's (a tax to a state authority, FIN-AP-015) or a vendor's prepayment (FIN-AP-008), each a
 * {@code FinPaymentLine}. It is prepared, submitted, approved by another person and released by the treasury; only a
 * released run posts, as one {@code FinPayment} per vendor (and one per other line). A payment pays bills through
 * applications ({@code FinApApplication}, kind {@code PAYMENT}); a voided payment reverses its entry and takes its
 * applications back, the bills open again. The files given to the bank ({@code FinPaymentFile}) are kept by the
 * platform's generated file archive; a run has one active file of a kind at a time (FIN-BK-011). All written only
 * by {@link PaymentProcesses} and {@link PaymentFiles}.
 */
public final class PaymentEntities {

    public static final String RUN = "FinPaymentRun";
    public static final String LINE = "FinPaymentLine";
    public static final String PAYMENT = "FinPayment";
    public static final String FILE = "FinPaymentFile";

    public static final String RUN_DATASET = "urn:jabiz:dataset:default:FinPaymentRun";
    public static final String LINE_DATASET = "urn:jabiz:dataset:default:FinPaymentLine";
    public static final String PAYMENT_DATASET = "urn:jabiz:dataset:default:FinPayment";
    public static final String FILE_DATASET = "urn:jabiz:dataset:default:FinPaymentFile";

    public static final String RUN_STATUSES = "urn:jabiz:dict:finance:payment-run-status";
    public static final String METHODS = "urn:jabiz:dict:finance:run-method";
    public static final String LINE_KINDS = "urn:jabiz:dict:finance:payment-line-kind";
    public static final String PAYMENT_STATUSES = "urn:jabiz:dict:finance:payment-status";
    public static final String FILE_KINDS = "urn:jabiz:dict:finance:payment-file-kind";
    public static final String FILE_STATUSES = "urn:jabiz:dict:finance:payment-file-status";

    /** Prepared and changed; waiting for approval; approved and locked; paid; given up. */
    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String APPROVED = "APPROVED";
    public static final String RELEASED = "RELEASED";
    public static final String CANCELLED = "CANCELLED";
    public static final List<String> RUN_STATUS_VALUES = List.of(DRAFT, SUBMITTED, APPROVED, RELEASED, CANCELLED);

    /** {@code MANUAL}: paid outside the bank files, such as a tax paid on the authority's portal (FIN-AP-015). */
    public static final String MANUAL = "MANUAL";
    /** {@code CARD}: paid by company card; not on the vendor's Form 1099 (the card processor files 1099-K). */
    public static final String CARD = "CARD";
    public static final List<String> METHOD_VALUES = List.of("ACH", "CHECK", "WIRE", MANUAL, CARD);

    /** A vendor's bill paid; another payment (a tax, FIN-AP-015) to an account; a vendor's prepayment. */
    public static final String BILL_LINE = "BILL";
    public static final String OTHER_LINE = "OTHER";
    public static final String PREPAYMENT_LINE = "PREPAYMENT";
    public static final List<String> LINE_KIND_VALUES = List.of(BILL_LINE, OTHER_LINE, PREPAYMENT_LINE);

    public static final String POSTED = "POSTED";
    public static final String VOID = "VOID";
    public static final List<String> PAYMENT_STATUS_VALUES = List.of(POSTED, VOID);

    public static final String NACHA = "NACHA";
    public static final String CHECK_FILE = "CHECKS";
    public static final String POSITIVE_PAY = "POSITIVE_PAY";
    public static final String WIRE = "WIRE";
    public static final List<String> FILE_KIND_VALUES = List.of(NACHA, CHECK_FILE, POSITIVE_PAY, WIRE);

    public static final String ACTIVE = "ACTIVE";
    public static final List<String> FILE_STATUS_VALUES = List.of(ACTIVE, CANCELLED);

    public static final EntityDefinition RUN_ENTITY = EntityDefinition.define(RUN, eb -> {
        eb.physicalTable("fi_payment_run_version");
        eb.primaryKey("runId");
        eb.field("runId", f -> f.physicalColumn("run_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:payment-run"));
        eb.field("runNo", f -> f.physicalColumn("run_no").immutable(true).required(true).asText(20));
        eb.field("bankCode", f -> f.physicalColumn("bank_code").immutable(true).required(true).asText(20));
        eb.field("paymentDate", f -> f.physicalColumn("payment_date").required(true).asDate());
        eb.field("method", f -> f.physicalColumn("method").immutable(true).required(true)
            .asCode(METHODS, values(METHOD_VALUES)));
        eb.field("description", f -> f.physicalColumn("description").asText(500));
        // F7 (D4): the currency its bills are paid in and the rate the bank pays it at; empty for runs in US dollars
        // before F7.
        eb.field("currency", f -> f.physicalColumn("currency").processOnly().asText(3));
        eb.field("exchangeRate", f -> f.physicalColumn("exchange_rate").processOnly().asNumeric(19, 10));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(RUN_STATUSES, values(RUN_STATUS_VALUES)));
        eb.field("total", f -> f.physicalColumn("total").processOnly().asNumeric(15, 2));
        eb.field("lineCount", f -> f.physicalColumn("line_count").processOnly().asNumeric(6, 0));
        // Who prepared it last: never its approver (FIN-AP-011, FIN-CT-001).
        eb.field("preparedBy", f -> f.physicalColumn("prepared_by").processOnly().asText(100));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").processOnly().asText(40));
        // The content approved: the run is locked to it (FIN-CT-003).
        eb.field("contentHash", f -> f.physicalColumn("content_hash").processOnly().asText(64));
        eb.field("approvedBy", f -> f.physicalColumn("approved_by").processOnly().asText(100));
        eb.field("releasedBy", f -> f.physicalColumn("released_by").processOnly().asText(100));
        eb.field("releasedTime", f -> f.physicalColumn("released_time").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("cancelReason", f -> f.physicalColumn("cancel_reason").processOnly().asText(500));
        eb.unique("uk_fi_payment_run_no", "runNo");
        eb.display("runNo");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("runNo", "paymentDate", "bankCode", "method", "currency", "total", "lineCount", "status",
                "preparedBy", "approvedBy", "releasedBy")
            .filters("runNo", "paymentDate", "bankCode", "method", "status")
            .sorts("runNo", "paymentDate", "total")
            .defaultSort("paymentDate", false));
    });

    public static final EntityDefinition LINE_ENTITY = EntityDefinition.define(LINE, eb -> {
        eb.physicalTable("fi_payment_line_version");
        eb.primaryKey("lineId");
        eb.field("lineId", f -> f.physicalColumn("line_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:payment-line"));
        eb.field("runId", f -> f.physicalColumn("run_id").immutable(true).required(true).asReference(RUN));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(LINE_KINDS, values(LINE_KIND_VALUES)));
        // A bill's line: the bill and its vendor.
        eb.field("billId", f -> f.physicalColumn("bill_id").immutable(true).asReference(BillEntities.BILL));
        eb.field("billNo", f -> f.physicalColumn("bill_no").immutable(true).asText(40));
        eb.field("vendorCode", f -> f.physicalColumn("vendor_code").immutable(true).asText(20));
        // Another payment's payee and account; a prepayment's account is the settings'.
        eb.field("payee", f -> f.physicalColumn("payee").immutable(true).asText(200));
        eb.field("account", f -> f.physicalColumn("account").immutable(true).asText(20));
        eb.field("description", f -> f.physicalColumn("description").immutable(true).asText(500));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asNumeric(15, 2));
        // The early-payment discount taken on the bill (FIN-AP-012): paid is amount less discount.
        eb.field("discount", f -> f.physicalColumn("discount").immutable(true).asNumeric(15, 2));
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("kind", "billNo", "vendorCode", "payee", "account", "amount", "discount", "description")
            .filters("runId", "billId", "vendorCode", "kind")
            .sorts("vendorCode", "billNo")
            .defaultSort("vendorCode", true));
    });

    public static final EntityDefinition PAYMENT_ENTITY = EntityDefinition.define(PAYMENT, eb -> {
        eb.physicalTable("fi_payment_version");
        eb.primaryKey("paymentId");
        eb.field("paymentId", f -> f.physicalColumn("payment_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:payment"));
        eb.field("paymentNo", f -> f.physicalColumn("payment_no").immutable(true).required(true).asText(20));
        eb.field("runId", f -> f.physicalColumn("run_id").immutable(true).required(true).asReference(RUN));
        eb.field("runNo", f -> f.physicalColumn("run_no").immutable(true).required(true).asText(20));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(LINE_KINDS, values(LINE_KIND_VALUES)));
        eb.field("vendorCode", f -> f.physicalColumn("vendor_code").immutable(true).asText(20));
        eb.field("payee", f -> f.physicalColumn("payee").immutable(true).required(true).asText(200));
        eb.field("bankCode", f -> f.physicalColumn("bank_code").immutable(true).required(true).asText(20));
        eb.field("method", f -> f.physicalColumn("method").immutable(true).required(true)
            .asCode(METHODS, values(METHOD_VALUES)));
        eb.field("paymentDate", f -> f.physicalColumn("payment_date").immutable(true).required(true).asDate());
        // Paid out of the bank: what the bills came to less the discounts.
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asNumeric(15, 2));
        eb.field("discount", f -> f.physicalColumn("discount").immutable(true).asNumeric(15, 2));
        // F7: in the run's currency; the bank pays the US dollars. Empty for payments in US dollars before F7.
        eb.field("currency", f -> f.physicalColumn("currency").immutable(true).asText(3));
        eb.field("exchangeRate", f -> f.physicalColumn("exchange_rate").immutable(true).asNumeric(19, 10));
        eb.field("amountUsd", f -> f.physicalColumn("amount_usd").immutable(true).asNumeric(15, 2));
        eb.field("checkNo", f -> f.physicalColumn("check_no").immutable(true).asText(20));
        // The vendor's bank account paid to, for an ACH payment: the one approved when the run was released.
        eb.field("vendorBankAccountId", f -> f.physicalColumn("vendor_bank_account_id").immutable(true)
            .asReference(ApEntities.VENDOR_BANK));
        // What of a prepayment is not yet applied to bills (FIN-AP-008).
        eb.field("openAmount", f -> f.physicalColumn("open_amount").processOnly().asNumeric(15, 2));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(PAYMENT_STATUSES, values(PAYMENT_STATUS_VALUES)));
        // Its entry is found by its number in FinPosting, as a receipt's is.
        eb.field("voidDate", f -> f.physicalColumn("void_date").processOnly().asDate());
        eb.field("voidReason", f -> f.physicalColumn("void_reason").processOnly().asText(500));
        eb.field("voidGlNo", f -> f.physicalColumn("void_gl_no").processOnly().asText(40));
        eb.unique("uk_fi_payment_no", "paymentNo");
        eb.display("paymentNo");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("paymentNo", "runNo", "paymentDate", "kind", "vendorCode", "payee", "method", "checkNo",
                "currency", "amount", "amountUsd", "discount", "openAmount", "status")
            .filters("paymentNo", "runId", "runNo", "paymentDate", "kind", "vendorCode", "method", "checkNo",
                "status")
            .sorts("paymentNo", "paymentDate", "amount")
            .defaultSort("paymentDate", false));
    });

    public static final EntityDefinition FILE_ENTITY = EntityDefinition.define(FILE, eb -> {
        eb.physicalTable("fi_payment_file_version");
        eb.primaryKey("paymentFileId");
        eb.field("paymentFileId", f -> f.physicalColumn("payment_file_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:finance:payment-file"));
        eb.field("runId", f -> f.physicalColumn("run_id").immutable(true).required(true).asReference(RUN));
        eb.field("runNo", f -> f.physicalColumn("run_no").immutable(true).required(true).asText(20));
        eb.field("fileKind", f -> f.physicalColumn("file_kind").immutable(true).required(true)
            .asCode(FILE_KINDS, values(FILE_KIND_VALUES)));
        eb.field("bankCode", f -> f.physicalColumn("bank_code").immutable(true).required(true).asText(20));
        // The company's day it was made: NACHA files of one bank and day are told apart by their modifier.
        eb.field("generatedDate", f -> f.physicalColumn("generated_date").immutable(true).required(true).asDate());
        // The platform's generated file: the bytes as given to the bank, with their hash.
        eb.field("generatedFileId", f -> f.physicalColumn("generated_file_id").immutable(true).required(true)
            .asText(36));
        eb.field("fileName", f -> f.physicalColumn("file_name").immutable(true).required(true).asText(200));
        eb.field("sha256", f -> f.physicalColumn("sha256").immutable(true).required(true).asText(64));
        eb.field("entryCount", f -> f.physicalColumn("entry_count").immutable(true).asNumeric(6, 0));
        eb.field("total", f -> f.physicalColumn("total").immutable(true).asNumeric(15, 2));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(FILE_STATUSES, values(FILE_STATUS_VALUES)));
        eb.field("generatedBy", f -> f.physicalColumn("generated_by").immutable(true).required(true).asText(100));
        eb.field("cancelledBy", f -> f.physicalColumn("cancelled_by").processOnly().asText(100));
        eb.field("cancelReason", f -> f.physicalColumn("cancel_reason").processOnly().asText(500));
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("runNo", "fileKind", "fileName", "entryCount", "total", "sha256", "status", "generatedBy",
                "cancelReason")
            .filters("runId", "runNo", "fileKind", "status", "bankCode", "generatedDate")
            .sorts("runNo", "fileKind")
            .defaultSort("runNo", false));
    });

    static String[] values(List<String> values) {
        return values.toArray(String[]::new);
    }

    private PaymentEntities() {}
}

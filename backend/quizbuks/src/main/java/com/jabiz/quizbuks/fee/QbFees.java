package com.jabiz.quizbuks.fee;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.quizbuks.identity.QbAccounts;

import java.util.List;

/**
 * The platform's fees (docs/quizbuks/plans/Q87-fee-account.md, D-Q87-4 – D-Q87-7).
 *
 * <ul>
 *   <li>{@value #CHARGE}: one fee charged to a sponsor, written once ({@code QB_FEE_CHARGE} only; append-only): its
 *       kind, the month it belongs to, the amount, the tax and the total, the sponsor's operator and billing mode
 *       when charged (frozen, as a card freezes its operator), what it stands for, and its posting (debit 1130 for
 *       the sponsor / credit 4120 and 2150; the charge is the posting's source). A prepaid charge gets an invoice
 *       of its own at once, paid from the cash account when it covers it (the payment's source is the charge too,
 *       the invoice naming the transaction); a postpaid charge is on its month's invoice.</li>
 *   <li>{@value #INVOICE}: what the sponsor pays, from their cash account only ({@code QB_FEE_INVOICE_PAY}; debit
 *       2160 / credit 1130): a prepaid charge's own, or a postpaid month's (the job {@code qb.fee-invoice}, all the
 *       month's postpaid charges). Numbered ({@code QBF-<year>-00001}), due in {@code qb.fee.due-days}, overdue by
 *       the daily job, voided by headquarters with another administrator's approval (the charges reversed).</li>
 * </ul>
 * {@value #REQUEST}: what headquarters asks another administrator to approve (four eyes): a fee charged by hand
 * ({@code MANUAL}) or an invoice voided ({@code VOID}); the approval's result does it.
 * The sponsor's billing mode and credit limit live on {@code QbSponsor} ({@code QB_OPERATOR_BILLING_SET}).
 */
public final class QbFees {

    public static final String CHARGE = "QbFeeCharge";
    public static final String INVOICE = "QbFeeInvoice";
    public static final String REQUEST = "QbFeeRequest";
    public static final String REQUEST_KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:fee-request-kind";
    public static final String REQUEST_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:fee-request-status";
    /** A request's kinds: a fee charged by hand, an invoice voided. */
    public static final String REQUEST_MANUAL = "MANUAL";
    public static final String REQUEST_VOID = "VOID";
    /** F-M2: platform fee Kudos granted to a sponsor by hand (four eyes, within {@code qb.fee-credit.manual.max}). */
    public static final String REQUEST_CREDIT = "CREDIT";
    /** A request's statuses: waiting for the approval, done, rejected (or approved by the sponsor themselves). */
    public static final String REVIEW = "REVIEW";
    public static final String DONE = "DONE";
    public static final String REJECTED = "REJECTED";
    /** The approval subject of the requests, and its rule (proposed by {@code QB_SETUP}). */
    public static final String APPROVAL_SUBJECT = "qb.fee-request";
    public static final String REVIEW_RULE = "qb.fee-request.review";
    /** The task type of an overdue invoice (closed when it is paid or voided). */
    public static final String OVERDUE_TASK = "qb.fee.overdue";
    public static final String OVERDUE_TASK_TITLE = "task.qb.fee.overdue";
    /** The lock of one invoice's document (two requests at once issue one). */
    public static String documentLockName(Object invoiceId) {
        return "qb.fee-invoice:" + invoiceId;
    }

    public static String overdueTaskSource(Object invoiceId) {
        return "qb.fee-overdue:" + invoiceId;
    }
    public static final String SPONSOR_CHARGES = "urn:jabiz:dataset:sponsor:" + CHARGE;
    public static final String SPONSOR_INVOICES = "urn:jabiz:dataset:sponsor:" + INVOICE;
    public static final String KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:fee-kind";
    public static final String BILLING_MODE_DICTIONARY = "urn:jabiz:dict:quizbuks:billing-mode";
    public static final String INVOICE_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:fee-invoice-status";
    public static final String INVOICE_SEQUENCE = "qb.fee-invoice";

    public static final String OPEN = "OPEN";
    /** Paid in part (F-M1): {@code paidAmount} of {@code total}, not past its due day. */
    public static final String PARTIAL = "PARTIAL";
    public static final String OVERDUE = "OVERDUE";
    public static final String PAID = "PAID";
    public static final String VOID = "VOID";
    public static final List<String> INVOICE_STATUSES = List.of(OPEN, PARTIAL, OVERDUE, PAID, VOID);
    /** The statuses an invoice is owed in (what is owed: {@code total - paidAmount}). */
    public static final List<String> UNPAID = List.of(OPEN, PARTIAL, OVERDUE);

    /** Event: an invoice was issued ({@code {invoiceId, sponsorId}}), for the mail. */
    public static final String INVOICE_ISSUED_EVENT = "qb.fee.invoice-issued";
    /** Event: an invoice is overdue, a reminder due ({@code {invoiceId, sponsorId, reminder}}), for the mail. */
    public static final String INVOICE_OVERDUE_EVENT = "qb.fee.invoice-overdue";

    public static final int REASON_LENGTH = 500;
    public static final int SOURCE_LENGTH = 100;
    /** The most reminders of an overdue invoice, a week apart (D-Q87-7). */
    public static final int MAX_REMINDERS = 3;
    public static final int REMINDER_DAYS = 7;

    private QbFees() {}

    public static String defaultDataset(String entity) {
        return QbAccounts.defaultDataset(entity);
    }

    public static final EntityDefinition CHARGE_ENTITY = EntityDefinition.define(CHARGE, eb -> {
        eb.physicalTable("qb_fee_charge_version");
        eb.primaryKey("chargeId");
        eb.field("chargeId", f -> f.physicalColumn("charge_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:fee-charge"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        // The sponsor's operator when charged; none: headquarters'. The settlement sums by it (D-Q87-8).
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).asText(36));
        eb.field("billingMode", f -> f.physicalColumn("billing_mode").immutable(true).required(true)
            .asCode(BILLING_MODE_DICTIONARY, FeeRules.BILLING_MODES.toArray(String[]::new)));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(KIND_DICTIONARY, FeeRules.KINDS.toArray(String[]::new)));
        eb.field("period", f -> f.physicalColumn("period").immutable(true).required(true).asText(7));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("taxRate", f -> f.physicalColumn("tax_rate").immutable(true).required(true).asNumeric(5, 4));
        eb.field("tax", f -> f.physicalColumn("tax").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("total", f -> f.physicalColumn("total").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).asText(REASON_LENGTH, true));
        // What it stands for: a monthly fee's month, a manual fee's record.
        eb.field("sourceKey", f -> f.physicalColumn("source_key").immutable(true).asText(SOURCE_LENGTH));
        eb.field("chargedAt", f -> f.physicalColumn("charged_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("chargedBy", f -> f.physicalColumn("charged_by").immutable(true).asText(128));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("sponsorId", "operatorId", "billingMode", "kind", "period", "amount", "tax", "total", "chargedAt")
            .filters("sponsorId", "operatorId", "kind", "period", "chargedAt")
            .sorts("chargedAt", "total")
            .defaultSort("chargedAt", false));
    });

    public static final EntityDefinition INVOICE_ENTITY = EntityDefinition.define(INVOICE, eb -> {
        eb.physicalTable("qb_fee_invoice");
        eb.primaryKey("invoiceId");
        eb.field("invoiceId", f -> f.physicalColumn("invoice_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:fee-invoice"));
        eb.field("invoiceNo", f -> f.physicalColumn("invoice_no").immutable(true).required(true).processOnly()
            .asText(30));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).processOnly().asText(36));
        eb.field("billingMode", f -> f.physicalColumn("billing_mode").immutable(true).required(true).processOnly()
            .asCode(BILLING_MODE_DICTIONARY, FeeRules.BILLING_MODES.toArray(String[]::new)));
        eb.field("period", f -> f.physicalColumn("period").immutable(true).required(true).processOnly().asText(7));
        // A prepaid charge's own invoice names it; a postpaid month's names none (its charges are the month's).
        eb.field("chargeId", f -> f.physicalColumn("charge_id").immutable(true).processOnly().asText(36));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("tax", f -> f.physicalColumn("tax").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("total", f -> f.physicalColumn("total").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(INVOICE_STATUS_DICTIONARY, INVOICE_STATUSES.toArray(String[]::new)));
        eb.field("issuedAt", f -> f.physicalColumn("issued_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("dueOn", f -> f.physicalColumn("due_on").immutable(true).required(true).processOnly().asDate());
        // What has been paid of the total (F-M1: an invoice may be paid in part); the total once PAID.
        eb.field("paidAmount", f -> f.physicalColumn("paid_amount").required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("paidAt", f -> f.physicalColumn("paid_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("paymentTransactionId", f -> f.physicalColumn("payment_transaction_id").processOnly().asText(64));
        eb.field("voidedAt", f -> f.physicalColumn("voided_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("voidedBy", f -> f.physicalColumn("voided_by").processOnly().asText(128));
        eb.field("voidReason", f -> f.physicalColumn("void_reason").processOnly().asText(REASON_LENGTH, true));
        eb.field("voidRequestedBy", f -> f.physicalColumn("void_requested_by").processOnly().asText(128));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").processOnly().asText(36));
        eb.field("remindersSent", f -> f.physicalColumn("reminders_sent").required(true).processOnly()
            .asNumeric(3, 0));
        eb.field("lastReminderAt", f -> f.physicalColumn("last_reminder_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("documentNo", f -> f.physicalColumn("document_no").processOnly().asText(100));
        eb.field("documentRunId", f -> f.physicalColumn("document_run_id").processOnly().asText(36));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_fee_invoice_no", "invoiceNo");
        eb.stateTransitions("status", st -> st
            .initial(OPEN, PARTIAL, PAID)
            .from(OPEN).to(PARTIAL, OVERDUE, PAID, VOID)
            .from(PARTIAL).to(OVERDUE, PAID, VOID)
            .from(OVERDUE).to(PAID, VOID)
            .from(PAID).to(VOID));
        eb.listView("default", lv -> lv
            .columns("invoiceNo", "sponsorId", "operatorId", "billingMode", "period", "total", "paidAmount", "status",
                "issuedAt", "dueOn", "paidAt")
            .filters("sponsorId", "operatorId", "status", "period", "issuedAt", "dueOn", "chargeId", "invoiceNo")
            .sorts("issuedAt", "dueOn", "total")
            .defaultSort("issuedAt", false));
    });

    public static final EntityDefinition REQUEST_ENTITY = EntityDefinition.define(REQUEST, eb -> {
        eb.physicalTable("qb_fee_request");
        eb.primaryKey("requestId");
        eb.field("requestId", f -> f.physicalColumn("request_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:fee-request"));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true).processOnly()
            .asCode(REQUEST_KIND_DICTIONARY, REQUEST_MANUAL, REQUEST_VOID, REQUEST_CREDIT));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(128));
        // A manual fee: what and why; a void: which invoice and why.
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).processOnly().asMonetary("JPY", 0));
        eb.field("invoiceId", f -> f.physicalColumn("invoice_id").immutable(true).processOnly().asText(36));
        // A grant by hand (F-M2): for how many days it is valid (none: qb.fee-credit.valid-days at the approval).
        eb.field("validDays", f -> f.physicalColumn("valid_days").immutable(true).processOnly().asNumeric(3, 0));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).required(true).processOnly()
            .asText(REASON_LENGTH, true));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(REQUEST_STATUS_DICTIONARY, REVIEW, DONE, REJECTED));
        eb.field("requestedBy", f -> f.physicalColumn("requested_by").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("requestedAt", f -> f.physicalColumn("requested_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").processOnly().asText(36));
        eb.field("decidedAt", f -> f.physicalColumn("decided_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        // What the approval made: the charge, or the invoice voided.
        eb.field("chargeId", f -> f.physicalColumn("charge_id").processOnly().asText(36));
        eb.field("grantId", f -> f.physicalColumn("grant_id").processOnly().asText(36));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.stateTransitions("status", st -> st
            .initial(REVIEW)
            .from(REVIEW).to(DONE, REJECTED));
        eb.listView("default", lv -> lv
            .columns("kind", "sponsorId", "amount", "invoiceId", "status", "requestedBy", "requestedAt")
            .filters("kind", "sponsorId", "status", "requestedAt", "invoiceId")
            .sorts("requestedAt")
            .defaultSort("requestedAt", false));
    });
}

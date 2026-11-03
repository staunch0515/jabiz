package com.jabiz.quizbuks.fee;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

import java.util.List;

/**
 * The platform fees' records (Q87b, docs/quizbuks/plans/Q87-fee-account.md §11):
 * <ul>
 *   <li>{@value #CHARGE}: one charge, written once (a time-sliced entity, {@code writeOnce}): the sponsor, its kind and
 *       month, the amount before tax, the rate and the provisional tax, whether it was paid from the cash account at
 *       once or is owed, and the operator the sponsor belonged to then (the settlement's). Its posting's source;</li>
 *   <li>{@value #BILLING}: a sponsor's billing mode and credit limit (an ordinary table; none: the platform's default);</li>
 *   <li>{@value #INVOICE}: a sponsor's invoice of a month (ordinary: its state changes as it is paid, overdue, voided);
 *       the charges are the sponsor's of the month, not linked one by one;</li>
 *   <li>{@value #PAYMENT}: a payment from the cash account of an invoice, or of a month's charges before its invoice
 *       (a prepaid sponsor whose cash fell short pays then, not a month later); written once, its posting's source;</li>
 *   <li>{@value #REQUEST}: a charge by hand or an invoice's voiding asked by finance, waiting for another
 *       administrator's approval (D-Q87-4, D-Q87-6).</li>
 * </ul>
 * Only the {@code QB_FEE_*} processes write them.
 */
public final class QbFees {

    public static final String CHARGE = "QbFeeCharge";
    public static final String BILLING = "QbSponsorBilling";
    public static final String INVOICE = "QbFeeInvoice";
    public static final String REQUEST = "QbFeeRequest";
    public static final String PAYMENT = "QbFeePayment";

    public static final String KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:fee-kind";
    public static final String MODE_DICTIONARY = "urn:jabiz:dict:quizbuks:billing-mode";
    public static final String INVOICE_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:fee-invoice-status";

    /** The kinds of charge; later ones (Q47c-2's audience rules) only add a kind. */
    public static final String MONTHLY = "MONTHLY";
    public static final String MANUAL = "MANUAL";
    public static final List<String> KINDS = List.of(MONTHLY, MANUAL);

    /** How a charge was settled when it was made. */
    public static final String CASH = "CASH";
    public static final String RECEIVABLE = "RECEIVABLE";

    /** An invoice's states: paid when issued (nothing due), issued (something due), past its due date, voided. */
    public static final String PAID = "PAID";
    public static final String ISSUED = "ISSUED";
    public static final String OVERDUE = "OVERDUE";
    public static final String VOID = "VOID";
    public static final List<String> INVOICE_STATUSES = List.of(PAID, ISSUED, OVERDUE, VOID);
    /** The states of an invoice something is still due on. */
    public static final List<String> UNPAID_STATUSES = List.of(ISSUED, OVERDUE);

    /** A request's kinds and states. */
    public static final String REQUEST_CHARGE = "CHARGE";
    public static final String REQUEST_VOID = "VOID";
    public static final String REVIEW = "REVIEW";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    public static final int REASON_LENGTH = 500;
    public static final long MAX_AMOUNT = 10_000_000L;
    /** Reminders of an overdue invoice: one a week, three at most (D-Q87-7). */
    public static final int MAX_REMINDERS = 3;
    public static final int REMINDER_DAYS = 7;

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** The lock of a sponsor's fees and invoices: the sponsor's wallet lock (the cash account is under it). */
    public static String lockName(String sponsorId) {
        return com.jabiz.quizbuks.wallet.QbWallets.lockName(com.jabiz.quizbuks.wallet.QbWallets.SPONSOR, sponsorId);
    }

    public static final EntityDefinition CHARGE_ENTITY = EntityDefinition.define(CHARGE, eb -> {
        eb.physicalTable("qb_fee_charge_version");
        eb.primaryKey("chargeId");
        eb.field("chargeId", f -> f.physicalColumn("charge_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:fee-charge"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true).asText(20));
        eb.field("period", f -> f.physicalColumn("period").immutable(true).required(true).asText(7));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("taxRate", f -> f.physicalColumn("tax_rate").immutable(true).required(true).asNumeric(5, 4));
        eb.field("tax", f -> f.physicalColumn("tax").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("settlement", f -> f.physicalColumn("settlement").immutable(true).required(true).asText(12));
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).asText(64));
        eb.field("sourceEntity", f -> f.physicalColumn("source_entity").immutable(true).asText(64));
        eb.field("sourceId", f -> f.physicalColumn("source_id").immutable(true).asText(64));
        eb.field("note", f -> f.physicalColumn("note").immutable(true).asText(REASON_LENGTH));
        eb.field("chargedAt", f -> f.physicalColumn("charged_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("sponsorId", "kind", "period", "amount", "tax", "settlement", "operatorId", "chargedAt")
            .filters("sponsorId", "kind", "period", "operatorId")
            .sorts("chargedAt", "amount")
            .defaultSort("chargedAt", false));
    });

    public static final EntityDefinition BILLING_ENTITY = EntityDefinition.define(BILLING, eb -> {
        eb.physicalTable("qb_sponsor_billing");
        eb.primaryKey("billingId");
        eb.field("billingId", f -> f.physicalColumn("billing_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:sponsor-billing"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("billingMode", f -> f.physicalColumn("billing_mode").required(true).processOnly()
            .asCode(MODE_DICTIONARY, FeeRules.PREPAID, FeeRules.POSTPAID));
        eb.field("creditLimit", f -> f.physicalColumn("credit_limit").required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("reason", f -> f.physicalColumn("reason").required(true).processOnly().asText(REASON_LENGTH));
        eb.field("updatedAt", f -> f.physicalColumn("updated_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("updatedBy", f -> f.physicalColumn("updated_by").required(true).processOnly().asText(128));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_sponsor_billing_sponsor", "sponsorId");
        eb.listView("default", lv -> lv
            .columns("sponsorId", "billingMode", "creditLimit", "reason", "updatedAt", "updatedBy")
            .filters("sponsorId", "billingMode")
            .sorts("updatedAt")
            .defaultSort("updatedAt", false));
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
        eb.field("period", f -> f.physicalColumn("period").immutable(true).required(true).processOnly().asText(7));
        eb.field("billingMode", f -> f.physicalColumn("billing_mode").immutable(true).required(true).processOnly()
            .asText(10));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("tax", f -> f.physicalColumn("tax").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("total", f -> f.physicalColumn("total").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("paidAtCharge", f -> f.physicalColumn("paid_at_charge").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("due", f -> f.physicalColumn("due").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("adjustment", f -> f.physicalColumn("adjustment").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(INVOICE_STATUS_DICTIONARY, INVOICE_STATUSES.toArray(String[]::new)));
        eb.field("issuedAt", f -> f.physicalColumn("issued_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("dueOn", f -> f.physicalColumn("due_on").immutable(true).processOnly().asDate());
        eb.field("paidAt", f -> f.physicalColumn("paid_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("documentRunId", f -> f.physicalColumn("document_run_id").processOnly().asText(36));
        eb.field("reminders", f -> f.physicalColumn("reminders").required(true).processOnly().asNumeric(3, 0));
        eb.field("remindedAt", f -> f.physicalColumn("reminded_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("voidedAt", f -> f.physicalColumn("voided_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_fee_invoice_no", "invoiceNo");
        eb.unique("uk_qb_fee_invoice_period", "sponsorId", "period");
        eb.stateTransitions("status", st -> st
            .initial(PAID, ISSUED)
            .from(ISSUED).to(PAID, OVERDUE, VOID)
            .from(OVERDUE).to(PAID, VOID)
            .from(PAID).to(VOID));
        eb.listView("default", lv -> lv
            .columns("invoiceNo", "sponsorId", "period", "total", "due", "status", "issuedAt", "dueOn", "paidAt")
            .filters("sponsorId", "period", "status")
            .sorts("issuedAt", "dueOn", "total")
            .defaultSort("issuedAt", false));
    });

    public static final EntityDefinition REQUEST_ENTITY = EntityDefinition.define(REQUEST, eb -> {
        eb.physicalTable("qb_fee_request");
        eb.primaryKey("requestId");
        eb.field("requestId", f -> f.physicalColumn("request_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:fee-request"));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true).processOnly().asText(10));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("invoiceId", f -> f.physicalColumn("invoice_id").immutable(true).processOnly().asText(36));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).processOnly().asMonetary("JPY", 0));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).required(true).processOnly()
            .asText(REASON_LENGTH));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly().asText(10));
        eb.field("requestedBy", f -> f.physicalColumn("requested_by").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("requestedAt", f -> f.physicalColumn("requested_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").processOnly().asText(64));
        eb.field("decidedAt", f -> f.physicalColumn("decided_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.stateTransitions("status", st -> st
            .initial(REVIEW)
            .from(REVIEW).to(APPROVED, REJECTED));
        eb.listView("default", lv -> lv
            .columns("kind", "sponsorId", "invoiceId", "amount", "reason", "status", "requestedBy", "requestedAt")
            .filters("kind", "sponsorId", "status")
            .sorts("requestedAt")
            .defaultSort("requestedAt", false));
    });

    public static final EntityDefinition PAYMENT_ENTITY = EntityDefinition.define(PAYMENT, eb -> {
        eb.physicalTable("qb_fee_payment");
        eb.primaryKey("paymentId");
        eb.field("paymentId", f -> f.physicalColumn("payment_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:fee-payment"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("period", f -> f.physicalColumn("period").immutable(true).required(true).processOnly().asText(7));
        eb.field("invoiceId", f -> f.physicalColumn("invoice_id").immutable(true).processOnly().asText(36));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("paidAt", f -> f.physicalColumn("paid_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("sponsorId", "period", "invoiceId", "amount", "paidAt")
            .filters("sponsorId", "period", "invoiceId")
            .sorts("paidAt")
            .defaultSort("paidAt", false));
    });

    private QbFees() {}
}

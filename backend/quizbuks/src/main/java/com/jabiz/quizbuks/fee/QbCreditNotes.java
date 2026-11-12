package com.jabiz.quizbuks.fee;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

import java.util.List;

/**
 * F-M7 (docs/quizbuks/plans/impl/F-M.md §10.3-2, -4): credit notes. A note is written once and never changed: it
 * lowers what the sponsor owes for a fee (debit 4120 and 2150 / credit 1130) without touching the fee or its invoice.
 * What it paid, gave back and gave back but voided is on it; what its credit paid of invoices since is in
 * {@code QbFeeCreditNoteUse}, so that what is left of it is worked out, never kept.
 */
public final class QbCreditNotes {

    public static final String CREDIT_NOTE = "QbFeeCreditNote";
    public static final String USE = "QbFeeCreditNoteUse";
    public static final String SPONSOR_CREDIT_NOTES = "urn:jabiz:dataset:sponsor:" + CREDIT_NOTE;
    public static final String REASON_DICTIONARY = "urn:jabiz:dict:quizbuks:credit-note-reason";
    public static final String SEQUENCE = "qb.fee-credit-note";

    /** The unused days of an audience fee, given back by the refund job. */
    public static final String AUDIENCE_UNUSED = "AUDIENCE_UNUSED";
    /** A note by hand (four eyes): finance's reason is on it. */
    public static final String MANUAL = "MANUAL";
    public static final List<String> REASONS = List.of(AUDIENCE_UNUSED, MANUAL);

    /** The source keys: one automatic note a fee, one note a request. */
    public static String audienceSource(String chargeId) {
        return "audience-unused:" + chargeId;
    }

    public static String manualSource(String requestId) {
        return "manual:" + requestId;
    }

    private QbCreditNotes() {}

    public static final EntityDefinition CREDIT_NOTE_ENTITY = EntityDefinition.define(CREDIT_NOTE, eb -> {
        eb.physicalTable("qb_fee_credit_note_version");
        eb.primaryKey("creditNoteId");
        eb.field("creditNoteId", f -> f.physicalColumn("credit_note_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:fee-credit-note"));
        eb.field("creditNoteNo", f -> f.physicalColumn("credit_note_no").immutable(true).required(true).asText(30));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        // The fee's operator: the settlement deducts its share by it (C3).
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).asText(36));
        eb.field("chargeId", f -> f.physicalColumn("charge_id").immutable(true).required(true).asText(36));
        eb.field("invoiceId", f -> f.physicalColumn("invoice_id").immutable(true).asText(36));
        eb.field("invoiceNo", f -> f.physicalColumn("invoice_no").immutable(true).asText(30));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).asText(36));
        eb.field("billingMode", f -> f.physicalColumn("billing_mode").immutable(true).required(true)
            .asCode(QbFees.BILLING_MODE_DICTIONARY, FeeRules.BILLING_MODES.toArray(String[]::new)));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).required(true)
            .asCode(REASON_DICTIONARY, REASONS.toArray(String[]::new)));
        // An automatic note's: why the publication ended and how many days it was on.
        eb.field("endReason", f -> f.physicalColumn("end_reason").immutable(true).asText(20));
        eb.field("usedDays", f -> f.physicalColumn("used_days").immutable(true).asNumeric(5, 0));
        // A note by hand's: finance's reason, printed on it.
        eb.field("note", f -> f.physicalColumn("note").immutable(true).asText(QbFees.REASON_LENGTH, true));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("taxRate", f -> f.physicalColumn("tax_rate").immutable(true).required(true).asNumeric(5, 4));
        eb.field("tax", f -> f.physicalColumn("tax").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("total", f -> f.physicalColumn("total").immutable(true).required(true).asMonetary("JPY", 0));
        // Where the total went when issued (§10.3-4): the fee's own invoice, back as cash, back as fee credit (of
        // which voided at once, being past its date); the rest is credit.
        eb.field("appliedOwn", f -> f.physicalColumn("applied_own").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("refundedCash", f -> f.physicalColumn("refunded_cash").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("refundedCredit", f -> f.physicalColumn("refunded_credit").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("voidedCredit", f -> f.physicalColumn("voided_credit").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("sourceKey", f -> f.physicalColumn("source_key").immutable(true).required(true)
            .asText(QbFees.SOURCE_LENGTH));
        eb.field("issuedAt", f -> f.physicalColumn("issued_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("issuedBy", f -> f.physicalColumn("issued_by").immutable(true).asText(128));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.unique("uk_qb_fee_credit_note_no", "creditNoteNo");
        eb.listView("default", lv -> lv
            .columns("creditNoteNo", "sponsorId", "operatorId", "invoiceNo", "reason", "amount", "tax", "total",
                "issuedAt")
            .filters("sponsorId", "operatorId", "chargeId", "invoiceId", "publicationId", "reason", "issuedAt")
            .sorts("issuedAt", "total")
            .defaultSort("issuedAt", false));
    });

    public static final EntityDefinition USE_ENTITY = EntityDefinition.define(USE, eb -> {
        eb.physicalTable("qb_fee_credit_note_use_version");
        eb.primaryKey("useId");
        eb.field("useId", f -> f.physicalColumn("use_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:fee-credit-note-use"));
        eb.field("creditNoteId", f -> f.physicalColumn("credit_note_id").immutable(true).required(true).asText(36));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        eb.field("invoiceId", f -> f.physicalColumn("invoice_id").immutable(true).required(true).asText(36));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("usedAt", f -> f.physicalColumn("used_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("creditNoteId", "sponsorId", "invoiceId", "amount", "usedAt")
            .filters("creditNoteId", "sponsorId", "invoiceId", "usedAt")
            .sorts("usedAt")
            .defaultSort("usedAt", false));
    });
}

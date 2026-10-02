package com.jabiz.finance.bank;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

/**
 * A bank account's reconciliation for a statement's closing day (FIN-BK-007, 008): the statement balance, the book
 * items not on the statement by then (deposits in transit, outstanding payments), the adjusted bank balance, the book
 * balance, the statement lines not in the books and the difference. Prepared, completed when the difference is
 * zero, signed off by a reviewer other than the preparer (the platform's approval), and then issued as the report
 * {@code finance.bank.reconciliation}, whose archive reprints it exactly.
 */
public final class ReconciliationEntities {

    public static final String RECONCILIATION = "FinBankReconciliation";
    public static final String RECONCILIATION_DATASET = "urn:jabiz:dataset:default:FinBankReconciliation";
    public static final String STATUSES = "urn:jabiz:dict:finance:bank-reconciliation-status";

    /** Its figures worked out, open to working out again; waiting for the reviewer; signed off and issued. */
    public static final String PREPARED = "PREPARED";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String SIGNED_OFF = "SIGNED_OFF";

    public static final EntityDefinition RECONCILIATION_ENTITY = EntityDefinition.define(RECONCILIATION, eb -> {
        eb.physicalTable("fi_bank_reconciliation_version");
        eb.primaryKey("reconciliationId");
        eb.field("reconciliationId", f -> f.physicalColumn("reconciliation_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:finance:bank-reconciliation"));
        eb.field("bankCode", f -> f.physicalColumn("bank_code").immutable(true).required(true).asText(20));
        eb.field("statementDate", f -> f.physicalColumn("statement_date").immutable(true).required(true).asDate());
        eb.field("statementBalance", f -> f.physicalColumn("statement_balance").processOnly().required(true)
            .asNumeric(15, 2));
        eb.field("depositsInTransit", f -> f.physicalColumn("deposits_in_transit").processOnly().required(true)
            .asNumeric(15, 2));
        eb.field("outstandingPayments", f -> f.physicalColumn("outstanding_payments").processOnly().required(true)
            .asNumeric(15, 2));
        eb.field("adjustedBalance", f -> f.physicalColumn("adjusted_balance").processOnly().required(true)
            .asNumeric(15, 2));
        eb.field("bookBalance", f -> f.physicalColumn("book_balance").processOnly().required(true).asNumeric(15, 2));
        eb.field("notInBooks", f -> f.physicalColumn("not_in_books").processOnly().required(true).asNumeric(15, 2));
        eb.field("difference", f -> f.physicalColumn("difference").processOnly().required(true).asNumeric(15, 2));
        eb.field("status", f -> f.physicalColumn("status").processOnly().required(true)
            .asCode(STATUSES, PREPARED, SUBMITTED, SIGNED_OFF));
        eb.field("preparedBy", f -> f.physicalColumn("prepared_by").processOnly().asText(100));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").processOnly().asText(40));
        eb.field("contentHash", f -> f.physicalColumn("content_hash").processOnly().asText(64));
        eb.field("reviewedBy", f -> f.physicalColumn("reviewed_by").processOnly().asText(100));
        eb.field("signedOffTime", f -> f.physicalColumn("signed_off_time").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        // The issued report: its archive reprints the reconciliation exactly (FIN-BK-008).
        eb.field("reportRunId", f -> f.physicalColumn("report_run_id").processOnly().asText(40));
        eb.field("reportHash", f -> f.physicalColumn("report_hash").processOnly().asText(64));
        eb.unique("uk_fi_bank_reconciliation_day", "bankCode", "statementDate");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("bankCode", "statementDate", "statementBalance", "adjustedBalance", "bookBalance",
                "difference", "status", "preparedBy", "reviewedBy", "signedOffTime", "reportRunId")
            .filters("bankCode", "statementDate", "status")
            .sorts("statementDate", "bankCode")
            .defaultSort("statementDate", false));
    });

    private ReconciliationEntities() {}
}

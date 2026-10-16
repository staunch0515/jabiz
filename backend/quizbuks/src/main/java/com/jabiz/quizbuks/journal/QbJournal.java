package com.jabiz.quizbuks.journal;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

/**
 * The journal export's records (docs/quizbuks/plans/Q9d-journal-receipts.md, D-Q9d-2): an export is one month's
 * summary voucher, written once with its archived files (the detail in parts); its confirmation, written once too, is the
 * voucher number the accounting system gave it. All only ever inserted (migration V9).
 */
public final class QbJournal {

    public static final String EXPORT = "QbJournalExport";
    public static final String CONFIRMATION = "QbJournalExportConfirmation";
    public static final String PART = "QbJournalExportPart";

    /** The voucher numbers: {@code QBJ-<year>-00001}, counted per year of the period (JST), without gaps. */
    public static final String VOUCHER_SEQUENCE = "qb.journal";

    /** Exports take this lock: one at a time, each after the one before. */
    public static final String LOCK = "qb.journal";

    public static final String GRANULARITY_MONTH = "MONTH";

    public static final String FIRST_TEMPLATE = "qb.system.journal-first";
    public static final String SUMMARY_TEMPLATE = "qb.system.journal-summary";
    public static final String PARTS_TEMPLATE = "qb.system.journal-parts";
    public static final String DETAIL_TEMPLATE = "qb.system.journal-detail";
    public static final String OPEN_TRANSACTIONS_TEMPLATE = "qb.system.journal-open-transactions";
    public static final String EXPORTS_TEMPLATE = "qb.admin.journal-exports";
    public static final String MAPPING_TEMPLATE = "qb.admin.journal-mapping";

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    public static final EntityDefinition EXPORT_ENTITY = EntityDefinition.define(EXPORT, eb -> {
        eb.physicalTable("qb_journal_export_version");
        eb.primaryKey("exportId");
        eb.field("exportId", f -> f.physicalColumn("export_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:journal-export"));
        eb.field("periodFrom", f -> f.physicalColumn("period_from").immutable(true).required(true).asDate());
        eb.field("periodTo", f -> f.physicalColumn("period_to").immutable(true).required(true).asDate());
        eb.field("periodEnd", f -> f.physicalColumn("period_end").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("granularity", f -> f.physicalColumn("granularity").immutable(true).required(true).asText(10));
        eb.field("voucherNo", f -> f.physicalColumn("voucher_no").immutable(true).required(true).asText(30));
        eb.field("knownAt", f -> f.physicalColumn("known_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("previousExportId", f -> f.physicalColumn("previous_export_id").immutable(true)
            .asReference(EXPORT));
        eb.field("lineCount", f -> f.physicalColumn("line_count").immutable(true).required(true).asNumeric(9, 0));
        eb.field("transactionCount", f -> f.physicalColumn("transaction_count").immutable(true).required(true)
            .asNumeric(9, 0));
        eb.field("debitTotal", f -> f.physicalColumn("debit_total").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("creditTotal", f -> f.physicalColumn("credit_total").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("fileId", f -> f.physicalColumn("file_id").immutable(true).required(true).asText(36));
        eb.field("fileSha256", f -> f.physicalColumn("file_sha256").immutable(true).required(true).asText(64));
        eb.field("detailPartCount", f -> f.physicalColumn("detail_part_count").immutable(true).required(true)
            .asNumeric(9, 0));
        eb.field("exportedBy", f -> f.physicalColumn("exported_by").immutable(true).required(true).asText(128));
        eb.unique("uk_qb_journal_export_period", "periodFrom", "granularity");
        eb.unique("uk_qb_journal_export_voucher", "voucherNo");
        eb.display("voucherNo");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("periodFrom", "periodTo", "voucherNo", "lineCount", "debitTotal", "knownAt", "exportedBy")
            .filters("periodFrom", "voucherNo")
            .sorts("periodFrom")
            .defaultSort("periodFrom", false));
    });

    /** A part of an export's detail: one archived CSV file, within the platform's limits (review H1). */
    public static final EntityDefinition PART_ENTITY = EntityDefinition.define(PART, eb -> {
        eb.physicalTable("qb_journal_export_part_version");
        eb.primaryKey("partId");
        eb.field("partId", f -> f.physicalColumn("part_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:journal-export-part"));
        eb.field("exportId", f -> f.physicalColumn("export_id").immutable(true).required(true).asReference(EXPORT));
        eb.field("partNo", f -> f.physicalColumn("part_no").immutable(true).required(true).asNumeric(9, 0));
        eb.field("fileId", f -> f.physicalColumn("file_id").immutable(true).required(true).asText(36));
        eb.field("sha256", f -> f.physicalColumn("sha256").immutable(true).required(true).asText(64));
        eb.field("rowCount", f -> f.physicalColumn("row_count").immutable(true).required(true).asNumeric(9, 0));
        eb.unique("uk_qb_journal_export_part_no", "exportId", "partNo");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("exportId", "partNo", "rowCount", "fileId")
            .filters("exportId")
            .sorts("partNo")
            .defaultSort("partNo", true));
    });

    public static final EntityDefinition CONFIRMATION_ENTITY = EntityDefinition.define(CONFIRMATION, eb -> {
        eb.physicalTable("qb_journal_export_confirmation_version");
        eb.primaryKey("confirmationId");
        eb.field("confirmationId", f -> f.physicalColumn("confirmation_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:journal-confirmation"));
        eb.field("exportId", f -> f.physicalColumn("export_id").immutable(true).required(true).asReference(EXPORT));
        eb.field("externalVoucherNo", f -> f.physicalColumn("external_voucher_no").immutable(true).required(true)
            .asText(JournalLines.EXTERNAL_VOUCHER_LENGTH));
        eb.field("confirmedBy", f -> f.physicalColumn("confirmed_by").immutable(true).required(true).asText(128));
        eb.unique("uk_qb_journal_confirmation_export", "exportId");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("exportId", "externalVoucherNo", "confirmedBy")
            .filters("exportId", "externalVoucherNo")
            .sorts("externalVoucherNo")
            .defaultSort("externalVoucherNo", true));
    });

    private QbJournal() {}
}

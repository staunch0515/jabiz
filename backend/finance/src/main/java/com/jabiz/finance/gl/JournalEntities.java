package com.jabiz.finance.gl;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import com.jabiz.entity.TemporalRole;
import com.jabiz.file.FileKind;
import com.jabiz.runtime.ledger.LedgerEntities;

import java.util.List;

/**
 * Journal entries and what belongs to them (FIN-GL-010 … 018; docs/finance/00-design.md section 6.3). All temporal:
 * an entry's history shows every save, submission, approval and its posting. Everything here changes through the
 * journal processes only ({@link JournalProcesses}); the datasets are {@code processOnlyWrites}, and the state and
 * the numbers are {@code processOnly} on top.
 */
public final class JournalEntities {

    public static final String JOURNAL = "FinJournal";
    public static final String LINE = "FinJournalLine";
    public static final String ATTACHMENT = "FinJournalAttachment";
    public static final String POSTING = "FinPosting";
    public static final String RECURRING = "FinRecurringTemplate";
    public static final String RECURRING_LINE = "FinRecurringLine";

    public static final String JOURNAL_DATASET = "urn:jabiz:dataset:default:FinJournal";
    public static final String LINE_DATASET = "urn:jabiz:dataset:default:FinJournalLine";
    public static final String ATTACHMENT_DATASET = "urn:jabiz:dataset:default:FinJournalAttachment";
    public static final String POSTING_DATASET = "urn:jabiz:dataset:default:FinPosting";
    public static final String RECURRING_DATASET = "urn:jabiz:dataset:default:FinRecurringTemplate";
    public static final String RECURRING_LINE_DATASET = "urn:jabiz:dataset:default:FinRecurringLine";

    public static final String STATUSES = "urn:jabiz:dict:finance:journal-status";
    public static final String SOURCES = "urn:jabiz:dict:finance:journal-source";
    public static final String POSTING_SOURCES = "urn:jabiz:dict:finance:posting-source";

    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String APPROVED = "APPROVED";
    public static final String POSTED = "POSTED";
    public static final String REJECTED = "REJECTED";
    public static final List<String> STATUS_VALUES = List.of(DRAFT, SUBMITTED, APPROVED, POSTED, REJECTED);

    /** Where an entry comes from (FIN-GL-010); all but the automatic reversal follow the approval rules. */
    public static final String MANUAL = "MANUAL";
    public static final String RECURRING_SOURCE = "RECURRING";
    public static final String REVERSING = "REVERSING";
    public static final String AUTO_REVERSING = "AUTO_REVERSING";
    public static final String IMPORT = "IMPORT";
    public static final List<String> SOURCE_VALUES = List.of(MANUAL, RECURRING_SOURCE, REVERSING, AUTO_REVERSING,
        IMPORT);

    /**
     * Sources of general ledger numbers (design section 4.5), counted apart per fiscal year: manual journals (MAN)
     * and, from later phases, the subledgers.
     */
    public static final List<String> POSTING_SOURCE_VALUES =
        List.of("MAN", "AR", "AP", "BK", "FA", "FX", "IMP", "CLS");

    /** The ledger's currency and scale (FIN-FX-001). */
    static final String USD = "USD";
    static final int CENTS = 2;

    /** The file policies of supporting documents (FIN-GL-016): documents and images, and spreadsheets. */
    public static final String SUPPORT_FILES = "fin.journal.support";
    public static final String SHEET_FILES = "fin.journal.sheets";
    public static final String ATTACHMENT_ONE_FILE = "FIN_ATTACHMENT_ONE_FILE";

    private static String[] values(List<String> values) {
        return values.toArray(String[]::new);
    }

    public static final EntityDefinition JOURNAL_ENTITY = EntityDefinition.define(JOURNAL, eb -> {
        eb.physicalTable("fi_journal_version");
        eb.primaryKey("journalId");
        eb.field("journalId", f -> f.physicalColumn("journal_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:journal"));
        // JE-0001: drawn when the entry is first submitted, per fiscal year; a draft has none.
        eb.field("journalNo", f -> f.physicalColumn("journal_no").processOnly().asText(30));
        eb.field("postingDate", f -> f.physicalColumn("posting_date").required(true).asDate());
        eb.field("documentDate", f -> f.physicalColumn("document_date").required(true).asDate());
        eb.field("description", f -> f.physicalColumn("description").required(true).asText(500)
            .apply(Rules.notBlank("FIN_JOURNAL_DESCRIPTION_BLANK")));
        eb.field("source", f -> f.physicalColumn("source").immutable(true).required(true)
            .asCode(SOURCES, values(SOURCE_VALUES)));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUSES, values(STATUS_VALUES)));
        eb.field("preparer", f -> f.physicalColumn("preparer").immutable(true).required(true).asText(100));
        // An adjusting entry may go into a soft-closed period (FIN-PC-003) and, when asked, into period 13.
        eb.field("adjusting", f -> f.physicalColumn("adjusting").required(true).asBool());
        eb.field("adjustmentPeriod", f -> f.physicalColumn("adjustment_period").required(true).asBool());
        eb.field("autoReverseDate", f -> f.physicalColumn("auto_reverse_date").asDate());
        eb.field("reversesJournalId", f -> f.physicalColumn("reverses_journal_id").immutable(true)
            .asReference(JOURNAL));
        // Set on the original once a reversal of it is made: the automatic reversal looks only at the others.
        eb.field("reversedById", f -> f.physicalColumn("reversed_by_id").processOnly().asReference(JOURNAL));
        // A recurring template's entry of one period, "PREPAID-INS/2026-01": made once (FIN-GL-017).
        eb.field("recurringKey", f -> f.physicalColumn("recurring_key").immutable(true).asText(150));
        eb.field("totalDebit", f -> f.physicalColumn("total_debit").required(true).processOnly()
            .asMonetary(USD, CENTS));
        eb.field("totalCredit", f -> f.physicalColumn("total_credit").required(true).processOnly()
            .asMonetary(USD, CENTS));
        eb.field("periodKey", f -> f.physicalColumn("period_key").processOnly().asText(7));
        // Entry numbers count per fiscal year: JE-0001 comes again each year.
        eb.field("fiscalYear", f -> f.physicalColumn("fiscal_year").processOnly().asNumeric(4, 0));
        // The content the approval and the control-account exception were given for (FIN-CT-003).
        eb.field("contentHash", f -> f.physicalColumn("content_hash").processOnly().asText(64));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").processOnly().asText(36));
        eb.field("exceptionBy", f -> f.physicalColumn("exception_by").processOnly().asText(100));
        eb.field("exceptionTime", f -> f.physicalColumn("exception_time").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("exceptionReason", f -> f.physicalColumn("exception_reason").processOnly().asText(500));
        eb.field("exceptionHash", f -> f.physicalColumn("exception_hash").processOnly().asText(64));
        eb.field("glNo", f -> f.physicalColumn("gl_no").processOnly().asText(30));
        eb.field("transactionId", f -> f.physicalColumn("transaction_id").processOnly()
            .asReference(LedgerEntities.TRANSACTION));
        eb.unique("uk_fi_journal_no", "fiscalYear", "journalNo");
        eb.unique("uk_fi_journal_gl_no", "glNo");
        eb.unique("uk_fi_journal_reverses", "reversesJournalId");
        eb.unique("uk_fi_journal_recurring", "recurringKey");
        eb.display("description");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("journalNo", "postingDate", "description", "source", "status", "totalDebit", "glNo", "preparer")
            .filters("journalNo", "postingDate", "source", "status", "preparer", "fiscalYear", "periodKey", "glNo")
            .sorts("journalNo", "postingDate", "totalDebit")
            .defaultSort("postingDate", false));
    });

    public static final EntityDefinition LINE_ENTITY = EntityDefinition.define(LINE, eb -> {
        eb.physicalTable("fi_journal_line_version");
        eb.primaryKey("lineId");
        eb.field("lineId", f -> f.physicalColumn("line_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:journal-line"));
        eb.field("journalId", f -> f.physicalColumn("journal_id").immutable(true).required(true)
            .asReference(JOURNAL));
        eb.field("lineNo", f -> f.physicalColumn("line_no").immutable(true).required(true).asNumeric(4, 0));
        eb.field("accountCode", f -> f.physicalColumn("account_code").immutable(true).required(true).asText(20));
        eb.field("debit", f -> f.physicalColumn("debit").immutable(true).asMonetary(USD, CENTS));
        eb.field("credit", f -> f.physicalColumn("credit").immutable(true).asMonetary(USD, CENTS));
        eb.field("memo", f -> f.physicalColumn("memo").immutable(true).asText(200));
        eb.field("department", f -> f.physicalColumn("department").immutable(true).asText(20));
        eb.field("location", f -> f.physicalColumn("location").immutable(true).asText(20));
        eb.unique("uk_fi_journal_line", "journalId", "lineNo");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("journalId", "lineNo", "accountCode", "debit", "credit", "memo", "department", "location")
            .filters("journalId", "accountCode", "department", "location")
            .sorts("lineNo")
            .defaultSort("lineNo", true));
    });

    /** A supporting document; its content hash is taken when attached and shown with the entry (FIN-GL-016). */
    public static final EntityDefinition ATTACHMENT_ENTITY = EntityDefinition.define(ATTACHMENT, eb -> {
        eb.physicalTable("fi_journal_attachment_version");
        eb.primaryKey("attachmentId");
        eb.field("attachmentId", f -> f.physicalColumn("attachment_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:finance:journal-attachment"));
        eb.field("journalId", f -> f.physicalColumn("journal_id").immutable(true).required(true)
            .asReference(JOURNAL));
        // Spreadsheets have a policy of their own: the platform keeps them apart from other file types (D26).
        eb.field("fileId", f -> f.physicalColumn("file_id").immutable(true).kind(FileKind.of(SUPPORT_FILES)));
        eb.field("sheetFileId", f -> f.physicalColumn("sheet_file_id").immutable(true)
            .kind(FileKind.of(SHEET_FILES)));
        eb.field("sha256", f -> f.physicalColumn("sha256").immutable(true).required(true).asText(64));
        eb.field("description", f -> f.physicalColumn("description").immutable(true).asText(200));
        eb.check(ATTACHMENT_ONE_FILE, (state, ctx) ->
            (state.get("fileId") == null) == (state.get("sheetFileId") == null)
            ? List.of(new com.jabiz.entity.Violation("fileId", ATTACHMENT_ONE_FILE,
                "An attachment is one document or one spreadsheet")) : List.of());
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("journalId", "fileId", "sheetFileId", "description", "sha256")
            .filters("journalId")
            .sorts("journalId")
            .defaultSort("journalId", true));
    });

    /**
     * One posting of finance to the general ledger: the ledger transaction with its fiscal period (13 included), its
     * source and general ledger number, and the document posted from. The ledger knows times only; periods, period 13
     * and the per-source numbering live here (design section 6.3; FIN-PC-001, FIN-GL-013).
     */
    public static final EntityDefinition POSTING_ENTITY = EntityDefinition.define(POSTING, eb -> {
        eb.physicalTable("fi_posting_version");
        eb.primaryKey("postingId");
        eb.field("postingId", f -> f.physicalColumn("posting_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:posting"));
        eb.field("transactionId", f -> f.physicalColumn("transaction_id").immutable(true).required(true)
            .asReference(LedgerEntities.TRANSACTION));
        eb.field("postingDate", f -> f.physicalColumn("posting_date").immutable(true).required(true).asDate());
        eb.field("fiscalYear", f -> f.physicalColumn("fiscal_year").immutable(true).required(true)
            .asNumeric(4, 0));
        eb.field("periodNo", f -> f.physicalColumn("period_no").immutable(true).required(true).asNumeric(2, 0));
        eb.field("periodKey", f -> f.physicalColumn("period_key").immutable(true).required(true).asText(7));
        eb.field("source", f -> f.physicalColumn("source").immutable(true).required(true)
            .asCode(POSTING_SOURCES, values(POSTING_SOURCE_VALUES)));
        eb.field("glNo", f -> f.physicalColumn("gl_no").immutable(true).required(true).asText(30));
        eb.field("documentNo", f -> f.physicalColumn("document_no").immutable(true).asText(40));
        eb.field("sourceEntity", f -> f.physicalColumn("source_entity").immutable(true).required(true)
            .asText(100));
        eb.field("sourceId", f -> f.physicalColumn("source_id").immutable(true).required(true).asText(100));
        eb.unique("uk_fi_posting_transaction", "transactionId");
        eb.unique("uk_fi_posting_gl_no", "glNo");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("glNo", "postingDate", "periodKey", "source", "documentNo", "sourceEntity")
            .filters("glNo", "postingDate", "periodKey", "source", "documentNo")
            .sorts("glNo", "postingDate")
            .defaultSort("glNo", true));
    });

    /** A recurring entry (FIN-GL-017): its lines repeat each period from start to end, dated the period's end. */
    public static final EntityDefinition RECURRING_ENTITY = EntityDefinition.define(RECURRING, eb -> {
        eb.physicalTable("fi_recurring_template_version");
        eb.primaryKey("templateId");
        eb.field("templateId", f -> f.physicalColumn("template_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:recurring-template"));
        eb.field("templateCode", f -> f.physicalColumn("template_code").immutable(true).required(true).asText(40)
            .apply(Rules.pattern("FIN_RECURRING_CODE_FORMAT", "[0-9A-Z][0-9A-Z_-]{0,39}")));
        eb.field("description", f -> f.physicalColumn("description").required(true).asText(500)
            .apply(Rules.notBlank("FIN_JOURNAL_DESCRIPTION_BLANK")));
        eb.field("startDate", f -> f.physicalColumn("start_date").required(true).asDate());
        eb.field("endDate", f -> f.physicalColumn("end_date").asDate());
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        eb.unique("uk_fi_recurring_code", "templateCode");
        eb.display("templateCode");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("templateCode", "description", "startDate", "endDate", "active")
            .filters("templateCode", "active")
            .sorts("templateCode")
            .defaultSort("templateCode", true));
    });

    public static final EntityDefinition RECURRING_LINE_ENTITY = EntityDefinition.define(RECURRING_LINE, eb -> {
        eb.physicalTable("fi_recurring_line_version");
        eb.primaryKey("recurringLineId");
        eb.field("recurringLineId", f -> f.physicalColumn("recurring_line_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:finance:recurring-line"));
        eb.field("templateId", f -> f.physicalColumn("template_id").immutable(true).required(true)
            .asReference(RECURRING));
        eb.field("lineNo", f -> f.physicalColumn("line_no").required(true).asNumeric(4, 0));
        eb.field("accountCode", f -> f.physicalColumn("account_code").required(true).asText(20));
        eb.field("debit", f -> f.physicalColumn("debit").asMonetary(USD, CENTS));
        eb.field("credit", f -> f.physicalColumn("credit").asMonetary(USD, CENTS));
        eb.field("memo", f -> f.physicalColumn("memo").asText(200));
        eb.field("department", f -> f.physicalColumn("department").asText(20));
        eb.field("location", f -> f.physicalColumn("location").asText(20));
        eb.unique("uk_fi_recurring_line", "templateId", "lineNo");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("templateId", "lineNo", "accountCode", "debit", "credit", "memo")
            .filters("templateId", "accountCode")
            .sorts("lineNo")
            .defaultSort("lineNo", true));
    });

    private JournalEntities() {}
}

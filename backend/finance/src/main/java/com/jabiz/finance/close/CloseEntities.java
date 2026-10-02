package com.jabiz.finance.close;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.file.FileKind;

import java.util.List;

/**
 * The period close (docs/finance/00-design.md section 7.3; ROADMAP F8): the checklist's template, each period's
 * tasks made from it, and the artifact a close leaves. Written by {@link CloseProcesses} only.
 */
public final class CloseEntities {

    public static final String TEMPLATE = "FinCloseTemplate";
    public static final String TEMPLATE_DATASET = "urn:jabiz:dataset:default:FinCloseTemplate";
    public static final String TASK = "FinCloseTask";
    public static final String TASK_DATASET = "urn:jabiz:dataset:default:FinCloseTask";
    public static final String ARTIFACT = "FinCloseArtifact";
    public static final String ARTIFACT_DATASET = "urn:jabiz:dataset:default:FinCloseArtifact";
    public static final String ARTIFACT_LINE = "FinCloseArtifactLine";
    public static final String ARTIFACT_LINE_DATASET = "urn:jabiz:dataset:default:FinCloseArtifactLine";

    public static final String REOPEN = "FinPeriodReopen";
    public static final String REOPEN_DATASET = "urn:jabiz:dataset:default:FinPeriodReopen";
    public static final String REOPEN_STATUSES = "urn:jabiz:dict:finance:period-reopen-status";

    /**
     * A reopening asked for and waiting for its approver; approved, the period open again; rejected; withdrawn by its
     * requester; lapsed, approved after a later period closed, so not done.
     */
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String WITHDRAWN = "WITHDRAWN";
    public static final String LAPSED = "LAPSED";

    public static final String KINDS = "urn:jabiz:dict:finance:close-task-kind";
    public static final String STATUSES = "urn:jabiz:dict:finance:close-task-status";

    /** A task a person does and marks done; a check the books answer themselves. */
    public static final String MANUAL = "MANUAL";
    public static final String AUTO = "AUTO";
    public static final List<String> KIND_VALUES = List.of(MANUAL, AUTO);

    /** Not done (a manual task) or not checked yet; done; a check passed or failed when last run. */
    public static final String OPEN = "OPEN";
    public static final String DONE = "DONE";
    public static final String PASSED = "PASSED";
    public static final String FAILED = "FAILED";
    public static final List<String> STATUS_VALUES = List.of(OPEN, DONE, PASSED, FAILED);

    /** Evidence files of manual tasks: reviews, schedules, sign-offs. */
    public static final String EVIDENCE_FILES = "fin.close.evidence";

    /** The sections of an artifact's lines. */
    public static final String TRIAL_BALANCE = "TRIAL_BALANCE";
    public static final String SUBLEDGER = "SUBLEDGER";
    public static final String CHECKLIST = "CHECKLIST";

    public static final EntityDefinition TEMPLATE_ENTITY = EntityDefinition.define(TEMPLATE, eb -> {
        eb.physicalTable("fi_close_template_version");
        eb.primaryKey("templateId");
        eb.field("templateId", f -> f.physicalColumn("template_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:close-template"));
        eb.field("taskCode", f -> f.physicalColumn("task_code").immutable(true).required(true).asText(30));
        eb.field("name", f -> f.physicalColumn("name").processOnly().required(true).asText(200));
        eb.field("kind", f -> f.physicalColumn("kind").processOnly().required(true).asCode(KINDS, MANUAL, AUTO));
        // What an automatic item checks (CloseChecks); none for a manual one.
        eb.field("checkCode", f -> f.physicalColumn("check_code").processOnly().asText(30));
        // Who does a manual item: the holders of this permission see its task and may complete it.
        eb.field("ownerPermission", f -> f.physicalColumn("owner_permission").processOnly().asText(100));
        // The task is due this many days after the period's last day.
        eb.field("dueDays", f -> f.physicalColumn("due_days").processOnly().required(true).asNumeric(3, 0));
        eb.field("required", f -> f.physicalColumn("required").processOnly().required(true).asBool());
        eb.field("sortOrder", f -> f.physicalColumn("sort_order").processOnly().required(true).asNumeric(4, 0));
        eb.field("active", f -> f.physicalColumn("active").processOnly().required(true).asBool());
        eb.unique("uk_fi_close_template_code", "taskCode");
        eb.display("name");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("sortOrder", "taskCode", "name", "kind", "checkCode", "ownerPermission", "dueDays", "required",
                "active")
            .filters("taskCode", "kind", "active")
            .sorts("sortOrder", "taskCode")
            .defaultSort("sortOrder", true));
    });

    /** A period's item of the checklist, copied from the template when the close starts (FIN-PC-004). */
    public static final EntityDefinition TASK_ENTITY = EntityDefinition.define(TASK, eb -> {
        eb.physicalTable("fi_close_task_version");
        eb.primaryKey("taskId");
        eb.field("taskId", f -> f.physicalColumn("task_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:close-task"));
        eb.field("periodKey", f -> f.physicalColumn("period_key").immutable(true).required(true).asText(7));
        eb.field("taskCode", f -> f.physicalColumn("task_code").immutable(true).required(true).asText(30));
        eb.field("name", f -> f.physicalColumn("name").immutable(true).required(true).asText(200));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(KINDS, MANUAL, AUTO));
        eb.field("checkCode", f -> f.physicalColumn("check_code").immutable(true).asText(30));
        eb.field("ownerPermission", f -> f.physicalColumn("owner_permission").immutable(true).asText(100));
        eb.field("dueDate", f -> f.physicalColumn("due_date").immutable(true).required(true).asDate());
        eb.field("required", f -> f.physicalColumn("required").immutable(true).required(true).asBool());
        eb.field("sortOrder", f -> f.physicalColumn("sort_order").immutable(true).required(true).asNumeric(4, 0));
        eb.field("status", f -> f.physicalColumn("status").processOnly().required(true)
            .asCode(STATUSES, OPEN, DONE, PASSED, FAILED));
        // What the last check found: the exceptions, or what was compared.
        eb.field("result", f -> f.physicalColumn("result").processOnly().asText(2000));
        // Where the check's evidence is: reports and their parameters ("template?name=value&…", "; " between).
        eb.field("evidence", f -> f.physicalColumn("evidence").processOnly().asText(500));
        eb.field("checkedAt", f -> f.physicalColumn("checked_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("completedBy", f -> f.physicalColumn("completed_by").processOnly().asText(100));
        eb.field("completedAt", f -> f.physicalColumn("completed_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("note", f -> f.physicalColumn("note").processOnly().asText(1000));
        eb.field("evidenceFileId", f -> f.physicalColumn("evidence_file_id").processOnly()
            .kind(FileKind.of(EVIDENCE_FILES)).auditMasked());
        eb.unique("uk_fi_close_task_period_code", "periodKey", "taskCode");
        eb.display("name");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("periodKey", "sortOrder", "name", "kind", "ownerPermission", "dueDate", "required", "status",
                "result", "checkedAt", "completedBy", "completedAt")
            .filters("periodKey", "taskCode", "kind", "status")
            .sorts("periodKey", "sortOrder", "dueDate")
            .defaultSort("sortOrder", true));
    });

    /**
     * What a close left (FIN-PC-005): written once, never changed. A later close of the same period (after a reopen,
     * F8b) is a new artifact naming the one it supersedes. The trial balance is as known at {@code knownAt}, the
     * close time: run then again, it gives the same rows and {@code trialBalanceHash}.
     */
    public static final EntityDefinition ARTIFACT_ENTITY = EntityDefinition.define(ARTIFACT, eb -> {
        eb.physicalTable("fi_close_artifact_version");
        eb.primaryKey("artifactId");
        eb.field("artifactId", f -> f.physicalColumn("artifact_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:close-artifact"));
        eb.field("periodKey", f -> f.physicalColumn("period_key").immutable(true).required(true).asText(7));
        eb.field("seq", f -> f.physicalColumn("seq").immutable(true).required(true).asNumeric(3, 0));
        eb.field("periodEnd", f -> f.physicalColumn("period_end").immutable(true).required(true).asDate());
        eb.field("closedBy", f -> f.physicalColumn("closed_by").immutable(true).required(true).asText(100));
        eb.field("closedAt", f -> f.physicalColumn("closed_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("knownAt", f -> f.physicalColumn("known_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("totalDebit", f -> f.physicalColumn("total_debit").immutable(true).required(true)
            .asNumeric(17, 2));
        eb.field("totalCredit", f -> f.physicalColumn("total_credit").immutable(true).required(true)
            .asNumeric(17, 2));
        eb.field("trialBalanceHash", f -> f.physicalColumn("trial_balance_hash").immutable(true).required(true)
            .asText(64));
        eb.field("contentHash", f -> f.physicalColumn("content_hash").immutable(true).required(true).asText(64));
        eb.field("reportRunId", f -> f.physicalColumn("report_run_id").immutable(true).asText(40));
        eb.field("supersedesId", f -> f.physicalColumn("supersedes_id").immutable(true).asReference(ARTIFACT));
        eb.unique("uk_fi_close_artifact_seq", "periodKey", "seq");
        eb.display("periodKey");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("periodKey", "seq", "closedBy", "closedAt", "totalDebit", "totalCredit", "contentHash",
                "reportRunId", "supersedesId")
            .filters("periodKey")
            .sorts("periodKey", "closedAt")
            .defaultSort("closedAt", false));
    });

    /**
     * An artifact's line: an account of the trial balance (debit, credit), a subledger beside its control accounts
     * (amount, ledgerAmount) or an item of the checklist (status, result).
     */
    public static final EntityDefinition ARTIFACT_LINE_ENTITY = EntityDefinition.define(ARTIFACT_LINE, eb -> {
        eb.physicalTable("fi_close_artifact_line_version");
        eb.primaryKey("lineId");
        eb.field("lineId", f -> f.physicalColumn("line_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:close-artifact-line"));
        eb.field("artifactId", f -> f.physicalColumn("artifact_id").immutable(true).required(true)
            .asReference(ARTIFACT));
        eb.field("section", f -> f.physicalColumn("section").immutable(true).required(true).asText(20));
        eb.field("seq", f -> f.physicalColumn("seq").immutable(true).required(true).asNumeric(6, 0));
        eb.field("code", f -> f.physicalColumn("code").immutable(true).required(true).asText(30));
        eb.field("name", f -> f.physicalColumn("name").immutable(true).asText(200));
        eb.field("debit", f -> f.physicalColumn("debit").immutable(true).asNumeric(17, 2));
        eb.field("credit", f -> f.physicalColumn("credit").immutable(true).asNumeric(17, 2));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).asNumeric(17, 2));
        eb.field("ledgerAmount", f -> f.physicalColumn("ledger_amount").immutable(true).asNumeric(17, 2));
        eb.field("status", f -> f.physicalColumn("status").immutable(true).asText(10));
        eb.field("result", f -> f.physicalColumn("result").immutable(true).asText(2000));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("section", "seq", "code", "name", "debit", "credit", "amount", "ledgerAmount", "status",
                "result")
            .filters("artifactId", "section", "code")
            .sorts("section", "seq")
            .defaultSort("seq", true));
    });

    /**
     * A request to open a closed period again (FIN-PC-006): its reason, who asked, and the decision of a controller
     * other than the requester (the platform's approval). Approved, the period and its subledgers are open; closed again,
     * the new artifact supersedes the one the request names.
     */
    public static final EntityDefinition REOPEN_ENTITY = EntityDefinition.define(REOPEN, eb -> {
        eb.physicalTable("fi_period_reopen_version");
        eb.primaryKey("reopenId");
        eb.field("reopenId", f -> f.physicalColumn("reopen_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:period-reopen"));
        eb.field("periodKey", f -> f.physicalColumn("period_key").immutable(true).required(true).asText(7));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).required(true).asText(1000));
        // The artifact of the close the request undoes.
        eb.field("artifactId", f -> f.physicalColumn("artifact_id").immutable(true).asReference(ARTIFACT));
        eb.field("requestedBy", f -> f.physicalColumn("requested_by").immutable(true).required(true).asText(100));
        eb.field("requestedAt", f -> f.physicalColumn("requested_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("status", f -> f.physicalColumn("status").processOnly().required(true)
            .asCode(REOPEN_STATUSES, PENDING, APPROVED, REJECTED, WITHDRAWN, LAPSED));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").processOnly().asText(40));
        eb.field("contentHash", f -> f.physicalColumn("content_hash").processOnly().asText(64));
        eb.field("decidedBy", f -> f.physicalColumn("decided_by").processOnly().asText(100));
        eb.field("decidedAt", f -> f.physicalColumn("decided_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.display("periodKey");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("periodKey", "reason", "requestedBy", "requestedAt", "status", "decidedBy", "decidedAt")
            .filters("periodKey", "status", "requestedBy")
            .sorts("requestedAt", "periodKey")
            .defaultSort("requestedAt", false));
    });

    private CloseEntities() {}
}

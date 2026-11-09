package com.jabiz.quizbuks.help;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.quizbuks.identity.AccountLimits;

import java.util.List;

/**
 * The AI help's records (F-C10, docs/quizbuks/plans/impl/F-C10.md), ordinary tables (not append-only: an erased
 * account's conversations are deleted), written by {@link HelpProcesses}, {@link HelpOutcomes} and {@link HelpUpkeep}
 * only:
 * <ul>
 *   <li>{@value #THREAD}: a conversation of a Taker ({@code app}) or a Sponsor ({@code sponsor}) with the AI, and with
 *       a person once handed over (to the Sponsor's operator; a direct Sponsor's and a Taker's to headquarters);</li>
 *   <li>{@value #MESSAGE}: its messages, the questions to the AI with their job's state, the AI's answers (same-site
 *       links only), what the user wrote to the person and the person's replies, each answer's rating;</li>
 *   <li>{@value #USAGE}: what the help's jobs used, per user and month and for everybody ({@value #EVERYBODY}); its own
 *       usage, never a Sponsor's AI usage, fees or limits (A1-9).</li>
 * </ul>
 */
public final class QbHelp {

    public static final String THREAD = "QbHelpThread";
    public static final String MESSAGE = "QbHelpMessage";
    public static final String USAGE = "QbHelpUsage";

    /** The usage row of everybody together. */
    public static final String EVERYBODY = "*";

    /** The two entries the help is asked from. */
    public static final String APP = "app";
    public static final String SPONSOR = "sponsor";
    public static final List<String> ENTRIES = List.of(APP, SPONSOR);
    public static final List<String> LANGUAGES = List.of("zh", "ja", "en");

    /** A conversation's status. */
    public static final String OPEN = "OPEN";
    public static final String ESCALATED = "ESCALATED";
    public static final String CLOSED = "CLOSED";
    public static final List<String> STATUSES = List.of(OPEN, ESCALATED, CLOSED);

    /** Whom a conversation handed to a person waits for. */
    public static final String WAIT_STAFF = "STAFF";
    public static final String WAIT_USER = "USER";
    public static final List<String> WAITS = List.of(WAIT_STAFF, WAIT_USER);

    /** Why a conversation closed. */
    public static final String CLOSED_BY_USER = "USER";
    public static final String CLOSED_BY_STAFF = "STAFF";
    public static final String CLOSED_RESOLVED = "RESOLVED";
    public static final String CLOSED_AUTO = "AUTO";
    public static final List<String> CLOSE_REASONS = List.of(CLOSED_BY_USER, CLOSED_BY_STAFF, CLOSED_RESOLVED,
        CLOSED_AUTO);

    /** A message's kind: a question to the AI, its answer, the user's words to a person, a person's reply. */
    public static final String QUESTION = "QUESTION";
    public static final String ANSWER = "ANSWER";
    public static final String USER = "USER";
    public static final String STAFF = "STAFF";
    public static final List<String> KINDS = List.of(QUESTION, ANSWER, USER, STAFF);

    /** A question's job. */
    public static final String QUEUED = "QUEUED";
    public static final String ANSWERED = "ANSWERED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final List<String> JOB_STATUSES = List.of(QUEUED, ANSWERED, FAILED, CANCELLED);

    /** An answer's or a reply's rating. */
    public static final String SOLVED = "SOLVED";
    public static final String UNSOLVED = "UNSOLVED";
    public static final List<String> RATINGS = List.of(SOLVED, UNSOLVED);

    public static final int ID_LENGTH = 36;
    /** The longest question or message a user writes, and a person's reply. */
    public static final int TEXT_LENGTH = 2_000;
    /** The longest answer kept (an answer cut at it ends with an ellipsis). */
    public static final int ANSWER_LENGTH = 8_000;
    public static final int LINKS_LENGTH = 2_000;
    public static final int PAGE_LENGTH = 40;
    public static final int CODE_LENGTH = 100;

    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:help-status";
    public static final String KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:help-kind";
    public static final String ENTRY_DICTIONARY = "urn:jabiz:dict:quizbuks:help-entry";
    public static final String JOB_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:help-job-status";
    public static final String RATING_DICTIONARY = "urn:jabiz:dict:quizbuks:help-rating";
    public static final String WAIT_DICTIONARY = "urn:jabiz:dict:quizbuks:help-wait";
    public static final String CLOSE_DICTIONARY = "urn:jabiz:dict:quizbuks:help-close";

    /** The templates (queries/qb/me, queries/qb/admin, queries/qb/system). */
    public static final String MY_THREADS = "qb.me.help-threads";
    public static final String MY_MESSAGES = "qb.me.help-messages";
    public static final String MY_SETTINGS = "qb.me.help-settings";
    public static final String ADMIN_THREADS = "qb.admin.help-threads";
    public static final String ADMIN_MESSAGES = "qb.admin.help-messages";
    public static final String GAPS = "qb.admin.help-gaps";
    public static final String USAGE_TEMPLATE = "qb.admin.help-usage";
    public static final String RETENTION_DUE = "qb.system.help-retention-due";
    public static final String AUTO_CLOSE_DUE = "qb.system.help-auto-close-due";
    public static final String REMIND_DUE = "qb.system.help-remind-due";

    /** The event of a person's reply (the user's message, QB_MESSAGE_FROM_EVENT). */
    public static final String REPLIED_EVENT = "qb.help.replied";

    /** The tasks: a conversation handed to a person, and its reminder. */
    public static final String TASK = "qb.help.escalated";
    public static final String REMIND_TASK = "qb.help.remind";

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** The caller's own rows, for the user's templates only. */
    public static String ownDataset(String entity) {
        return "urn:jabiz:dataset:me:" + entity;
    }

    /** The conversations handed to a person, for the staff's templates and dataset reads. */
    public static final String STAFF_THREADS = "urn:jabiz:dataset:staff:" + THREAD;

    /** One person's questions take turns: the day's count is read under it. Never everybody's. */
    public static String userLock(Object userId) {
        return "qb.help:" + userId;
    }

    /** A conversation: every process that changes it takes it. */
    public static String threadLock(Object threadId) {
        return threadId == null ? null : "qb.help-thread:" + threadId;
    }

    /** A usage row (a user's, or everybody's): the job outcomes take it, never a question. */
    public static String usageLock(Object userId) {
        return "qb.help-usage:" + userId;
    }

    /** The source of a conversation's task to a person. */
    public static String taskSource(Object threadId) {
        return "qb.help:" + threadId;
    }

    /** The source of the reminder of a conversation waiting since {@code waitingSinceEpoch} (seconds). */
    public static String remindSource(Object threadId, long waitingSinceEpoch) {
        return "qb.help-remind:" + threadId + ":" + waitingSinceEpoch;
    }

    /** A job's reference: its conversation and its question. */
    public static String reference(Object threadId, Object messageId) {
        return "help:" + threadId + ":" + messageId;
    }

    /** The conversation and the question of a job's reference; null when it is not one. */
    public static String[] ofReference(String reference) {
        if (reference == null || !reference.startsWith("help:")) {
            return null;
        }
        String[] parts = reference.substring("help:".length()).split(":", -1);
        return parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank() ? parts : null;
    }

    public static final EntityDefinition THREAD_ENTITY = EntityDefinition.define(THREAD, eb -> {
        eb.physicalTable("qb_help_thread");
        eb.primaryKey("threadId");
        eb.field("threadId", f -> f.physicalColumn("thread_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:help-thread"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).processOnly()
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("entry", f -> f.physicalColumn("entry").immutable(true).required(true).processOnly()
            .asCode(ENTRY_DICTIONARY, ENTRIES.toArray(String[]::new)));
        eb.field("language", f -> f.physicalColumn("language").required(true).processOnly().asText(5));
        // A name of the pages' list (HelpPages), never a path or an id.
        eb.field("startPage", f -> f.physicalColumn("start_page").immutable(true).processOnly().asText(PAGE_LENGTH));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        eb.field("escalated", f -> f.physicalColumn("escalated").required(true).processOnly().asBool());
        eb.field("escalatedAt", f -> f.physicalColumn("escalated_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        // The operator it was handed to; none (and escalated): headquarters.
        eb.field("operatorId", f -> f.physicalColumn("operator_id").processOnly().asText(ID_LENGTH));
        eb.field("waitingFor", f -> f.physicalColumn("waiting_for").processOnly()
            .asCode(WAIT_DICTIONARY, WAITS.toArray(String[]::new)));
        eb.field("waitingSince", f -> f.physicalColumn("waiting_since").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("resolved", f -> f.physicalColumn("resolved").processOnly().asBool());
        eb.field("closedAt", f -> f.physicalColumn("closed_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("closeReason", f -> f.physicalColumn("close_reason").processOnly()
            .asCode(CLOSE_DICTIONARY, CLOSE_REASONS.toArray(String[]::new)));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("lastActivityAt", f -> f.physicalColumn("last_activity_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("entry", "language", "status", "escalated", "operatorId", "waitingFor", "lastActivityAt")
            .filters("userId", "entry", "status", "escalated", "operatorId", "lastActivityAt")
            .sorts("lastActivityAt", "createdAt")
            .defaultSort("lastActivityAt", false));
    });

    public static final EntityDefinition MESSAGE_ENTITY = EntityDefinition.define(MESSAGE, eb -> {
        eb.physicalTable("qb_help_message");
        eb.primaryKey("messageId");
        eb.field("messageId", f -> f.physicalColumn("message_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:help-message"));
        eb.field("threadId", f -> f.physicalColumn("thread_id").immutable(true).required(true).processOnly()
            .asText(ID_LENGTH));
        // The conversation's user, for the operators' scope and the erasure.
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).processOnly()
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true).processOnly()
            .asCode(KIND_DICTIONARY, KINDS.toArray(String[]::new)));
        eb.field("body", f -> f.physicalColumn("body").immutable(true).required(true).processOnly()
            .asText(ANSWER_LENGTH, true));
        // The answer's same-site paths, one a line.
        eb.field("links", f -> f.physicalColumn("links").immutable(true).processOnly().asText(LINKS_LENGTH, true));
        eb.field("page", f -> f.physicalColumn("page").immutable(true).processOnly().asText(PAGE_LENGTH));
        eb.field("jobStatus", f -> f.physicalColumn("job_status").processOnly()
            .asCode(JOB_STATUS_DICTIONARY, JOB_STATUSES.toArray(String[]::new)));
        eb.field("failureCode", f -> f.physicalColumn("failure_code").processOnly().asText(CODE_LENGTH));
        // The model a question's job asked (its usage is priced at it).
        eb.field("model", f -> f.physicalColumn("model").immutable(true).processOnly().asText(60));
        // What the question's job reserved for an attempt's input: an attempt that recorded nothing is charged on it.
        eb.field("reservedInputTokens", f -> f.physicalColumn("reserved_input_tokens").immutable(true).processOnly()
            .asNumeric(12, 0));
        eb.field("rating", f -> f.physicalColumn("rating").processOnly()
            .asCode(RATING_DICTIONARY, RATINGS.toArray(String[]::new)));
        eb.field("ratedAt", f -> f.physicalColumn("rated_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("threadId", "kind", "jobStatus", "rating", "createdAt")
            .filters("threadId", "userId", "kind", "jobStatus", "rating", "createdAt")
            .sorts("createdAt")
            .defaultSort("createdAt", true));
    });

    public static final EntityDefinition USAGE_ENTITY = EntityDefinition.define(USAGE, eb -> {
        eb.physicalTable("qb_help_usage");
        eb.primaryKey("usageId");
        eb.field("usageId", f -> f.physicalColumn("usage_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:help-usage"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).processOnly()
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("month", f -> f.physicalColumn("month").immutable(true).required(true).processOnly().asText(7));
        eb.field("questions", f -> f.physicalColumn("questions").required(true).processOnly().asNumeric(9, 0));
        eb.field("failedJobs", f -> f.physicalColumn("failed_jobs").required(true).processOnly().asNumeric(9, 0));
        eb.field("cancelledJobs", f -> f.physicalColumn("cancelled_jobs").required(true).processOnly()
            .asNumeric(9, 0));
        eb.field("inputTokens", f -> f.physicalColumn("input_tokens").required(true).processOnly().asNumeric(15, 0));
        eb.field("outputTokens", f -> f.physicalColumn("output_tokens").required(true).processOnly()
            .asNumeric(15, 0));
        // Dollars at the models' list prices (AiCost), for reference only.
        eb.field("costUsd", f -> f.physicalColumn("cost_usd").processOnly().asNumeric(16, 6));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_help_usage_month", "userId", "month");
        eb.listView("default", lv -> lv
            .columns("userId", "month", "questions", "failedJobs", "cancelledJobs", "inputTokens", "outputTokens")
            .filters("userId", "month")
            .sorts("month", "inputTokens", "outputTokens")
            .defaultSort("month", false));
    });

    private QbHelp() {}
}

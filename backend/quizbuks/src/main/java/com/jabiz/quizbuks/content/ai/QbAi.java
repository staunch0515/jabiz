package com.jabiz.quizbuks.content.ai;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.file.FileKind;
import com.jabiz.quizbuks.content.QbContent;

import java.util.List;

/**
 * The two tables of AI generation (docs/quizbuks/plans/Q8-ai.md, D-Q8-3), both ordinary tables written only by the
 * processes of {@link AiProcesses} ({@code processOnlyWrites}):
 * <ul>
 *   <li>{@code QbAiJob}: one request for candidates. Its state changes within minutes and it is found by the
 *       platform's job id, as a top-up is by Stripe's session (D-Q8-3: not temporal); the platform's audit keeps its
 *       history. The candidates are kept only while they wait for the sponsor's review ({@code READY}) and are never
 *       written into the quiz without it (A-6);</li>
 *   <li>{@code QbAiUsage}: the tokens and jobs of a sponsor in a month of the deployment's zone, and one row per month
 *       for everybody ({@code sponsorId = *}). Added to only under the locks {@code qb.ai:<sponsor>} and
 *       {@code qb.ai:*}, by the completion and failure processes (A-7: the limits are counted from it).</li>
 * </ul>
 */
public final class QbAi {

    public static final String JOB = "QbAiJob";
    public static final String USAGE = "QbAiUsage";

    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:ai-job-status";
    public static final String KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:ai-job-kind";

    /** Waiting for or running at the provider. */
    public static final String QUEUED = "QUEUED";
    /** Candidates waiting for the sponsor's review. */
    public static final String READY = "READY";
    public static final String FAILED = "FAILED";
    /** Cancelled by the sponsor; the job still finishes at the provider, and its usage is still counted. */
    public static final String CANCELLED = "CANCELLED";
    /** Some candidates were reviewed and added to the quiz. */
    public static final String ACCEPTED = "ACCEPTED";
    public static final String DISCARDED = "DISCARDED";
    /** Not reviewed in time ({@code qb.ai.candidates.expire-days}). */
    public static final String EXPIRED = "EXPIRED";
    public static final List<String> STATUSES = List.of(QUEUED, READY, FAILED, CANCELLED, ACCEPTED, DISCARDED,
        EXPIRED);

    public static final String KIND_QUESTIONS = "QUESTIONS";
    /** Candidate covers (Q8b, M-25; platform 16t): pictures drawn from Claude's designs. */
    public static final String KIND_COVER = "COVER";
    /** Q73d-1: candidate translations of a quiz's text into one of its other languages. */
    public static final String KIND_TRANSLATION = "TRANSLATION";
    /** Q73d-2: candidate translations of a publication's own texts, and a reviewer's check of its translations. */
    public static final String KIND_PUBLICATION_TEXTS = "PUBLICATION_TEXTS";
    public static final String KIND_TRANSLATION_CHECK = "TRANSLATION_CHECK";

    /**
     * The fields holding a cover job's candidate pictures while they wait for the sponsor's choice: files of
     * {@code qb.content.image}, so that the platform's sweep keeps them until the job is closed (then they are referred
     * to by nothing, and the ones not chosen go).
     */
    public static final java.util.List<String> COVER_FIELDS = List.of("cover1", "cover2", "cover3");

    /** The row of {@code QbAiUsage} that counts every sponsor. */
    public static final String EVERYBODY = "*";

    public static final int FAILURE_MESSAGE_LENGTH = 500;

    /** The default (administrators', {@code qb.admin.ai.read}) dataset of an entity. */
    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** A sponsor's own rows, read only. */
    public static String sponsorDataset(String entity) {
        return "urn:jabiz:dataset:sponsor:" + entity;
    }

    /**
     * The lock of one sponsor's AI requests ({@code HoldLock}): the limits are checked and the usage added under it.
     * Order (D-Q8-4): {@code qb.quiz:<quiz>} → {@code qb.ai:<sponsor>} → {@code qb.ai:*}.
     */
    public static String lockName(String sponsorId) {
        return "qb.ai:" + sponsorId;
    }

    /** The lock of the usage row of everybody, after a sponsor's. */
    public static final String GLOBAL_LOCK = lockName(EVERYBODY);

    public static final EntityDefinition JOB_ENTITY = EntityDefinition.define(JOB, eb -> {
        eb.physicalTable("qb_ai_job");
        eb.primaryKey("aiJobId");
        eb.field("aiJobId", f -> f.physicalColumn("ai_job_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:ai-job"));
        eb.field("externalJobId", f -> f.physicalColumn("external_job_id").immutable(true).required(true)
            .processOnly().asText(36));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("quizId", f -> f.physicalColumn("quiz_id").immutable(true).required(true).processOnly()
            .asReference(QbContent.QUIZ));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true).processOnly()
            .asCode(KIND_DICTIONARY, KIND_QUESTIONS, KIND_COVER, KIND_TRANSLATION,
                KIND_PUBLICATION_TEXTS, KIND_TRANSLATION_CHECK));
        // What was asked, as canonical JSON: the count, difficulty, language and materials (not their text).
        eb.field("params", f -> f.physicalColumn("params").immutable(true).required(true).processOnly()
            .asText(2_000));
        eb.field("model", f -> f.physicalColumn("model").immutable(true).required(true).processOnly().asText(100));
        // The model that answered (another one when the provider fell back after a refusal, A-10): administrators only.
        eb.field("servedModel", f -> f.physicalColumn("served_model").processOnly().asText(100));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        // The candidates as canonical JSON, while READY only. Not repeated in the audit (the provider's output; the
        // platform keeps the job's result as it came, and the audit records that the column changed).
        eb.field("candidates", f -> f.physicalColumn("candidates").processOnly().auditMasked().asText(null, true));
        for (int i = 0; i < COVER_FIELDS.size(); i++) {
            String column = "cover_" + (i + 1);
            eb.field(COVER_FIELDS.get(i), f -> f.physicalColumn(column).processOnly()
                .kind(FileKind.of(QbContent.IMAGE_POLICY)));
        }
        eb.field("candidateCount", f -> f.physicalColumn("candidate_count").processOnly().asNumeric(4, 0));
        eb.field("invalidCount", f -> f.physicalColumn("invalid_count").processOnly().asNumeric(4, 0));
        eb.field("acceptedCount", f -> f.physicalColumn("accepted_count").processOnly().asNumeric(4, 0));
        eb.field("inputBytes", f -> f.physicalColumn("input_bytes").immutable(true).required(true).processOnly()
            .asNumeric(9, 0));
        eb.field("estimatedTokens", f -> f.physicalColumn("estimated_tokens").immutable(true).required(true)
            .processOnly().asNumeric(12, 0));
        eb.field("inputTokens", f -> f.physicalColumn("input_tokens").required(true).processOnly().asNumeric(12, 0));
        eb.field("outputTokens", f -> f.physicalColumn("output_tokens").required(true).processOnly()
            .asNumeric(12, 0));
        // The part of the tokens the provider's fallback model used (A-10), priced at that model.
        eb.field("fallbackInputTokens", f -> f.physicalColumn("fallback_input_tokens").processOnly()
            .asNumeric(12, 0));
        eb.field("fallbackOutputTokens", f -> f.physicalColumn("fallback_output_tokens").processOnly()
            .asNumeric(12, 0));
        // Dollars at the models' prices (AiCost; an estimate, A-8), once the job finished.
        eb.field("costUsd", f -> f.physicalColumn("cost_usd").processOnly().asNumeric(14, 6));
        // The deployment's zone when the job was asked for (jabiz.fiscal-year-zone): its day and month, which the
        // sponsor's usage card reads as the limits count them.
        eb.field("zone", f -> f.physicalColumn("zone").immutable(true).required(true).processOnly().asText(64));
        eb.field("attempts", f -> f.physicalColumn("attempts").processOnly().asNumeric(4, 0));
        eb.field("failureCode", f -> f.physicalColumn("failure_code").processOnly().asText(100));
        // The system's explanation only, never the provider's answer.
        eb.field("failureMessage", f -> f.physicalColumn("failure_message").processOnly()
            .asText(FAILURE_MESSAGE_LENGTH));
        eb.field("requestedAt", f -> f.physicalColumn("requested_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("finishedAt", f -> f.physicalColumn("finished_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("expiresAt", f -> f.physicalColumn("expires_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("closedAt", f -> f.physicalColumn("closed_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_ai_job_external", "externalJobId");
        eb.stateTransitions("status", st -> st
            .initial(QUEUED)
            .from(QUEUED).to(READY, FAILED, CANCELLED)
            .from(READY).to(ACCEPTED, DISCARDED, EXPIRED));
        eb.listView("default", lv -> lv
            .columns("sponsorId", "quizId", "kind", "model", "servedModel", "status", "candidateCount",
                "acceptedCount", "inputTokens", "outputTokens", "failureCode", "requestedAt")
            .filters("sponsorId", "quizId", "status", "failureCode", "requestedAt")
            .sorts("requestedAt", "inputTokens", "outputTokens")
            .defaultSort("requestedAt", false));
    });

    public static final EntityDefinition USAGE_ENTITY = EntityDefinition.define(USAGE, eb -> {
        eb.physicalTable("qb_ai_usage");
        eb.primaryKey("usageId");
        eb.field("usageId", f -> f.physicalColumn("usage_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:ai-usage"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("month", f -> f.physicalColumn("month").immutable(true).required(true).processOnly().asText(7));
        eb.field("jobs", f -> f.physicalColumn("jobs").required(true).processOnly().asNumeric(9, 0));
        eb.field("failedJobs", f -> f.physicalColumn("failed_jobs").required(true).processOnly().asNumeric(9, 0));
        eb.field("inputTokens", f -> f.physicalColumn("input_tokens").required(true).processOnly()
            .asNumeric(15, 0));
        eb.field("outputTokens", f -> f.physicalColumn("output_tokens").required(true).processOnly()
            .asNumeric(15, 0));
        // Dollars at the models' prices (AiCost; null while a job's could not be priced).
        eb.field("costUsd", f -> f.physicalColumn("cost_usd").processOnly().asNumeric(16, 6));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_ai_usage_month", "sponsorId", "month");
        eb.listView("default", lv -> lv
            .columns("sponsorId", "month", "jobs", "failedJobs", "inputTokens", "outputTokens")
            .filters("sponsorId", "month")
            .sorts("month", "inputTokens", "outputTokens")
            .defaultSort("month", false));
    });

    private QbAi() {}
}

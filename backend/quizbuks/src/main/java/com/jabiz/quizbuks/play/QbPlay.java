package com.jabiz.quizbuks.play;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.publishing.QbPublications;

/**
 * The entities of answering (docs/quizbuks/plans/Q5-answering.md, 实体与迁移), all ordinary tables: an attempt
 * (changed while in progress, refused every change by the processes once finished; the audit keeps its history), the
 * first view of a publication by a taker, and the completion counts of the last hour, day, week and month of a
 * publication, refreshed every ten minutes. Only the processes write them.
 */
public final class QbPlay {

    public static final String ATTEMPT = "QbAttempt";
    public static final String VIEW = "QbPublicationView";
    public static final String STATS = "QbPublicationStats";
    public static final String STATS_RUN = "QbStatsRun";

    public static final String ATTEMPT_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:attempt-status";
    public static final String REWARD_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:reward-status";

    /** The taker's own attempts, read only. */
    public static final String TAKER_ATTEMPTS = "urn:jabiz:dataset:taker:" + ATTEMPT;

    /**
     * Every attempt with its reward and commission, read only, for finance (Q9c, C-2). A dataset cannot leave fields
     * out, so the amounts are read here and the default dataset is read by nobody through the generic API.
     */
    public static final String FINANCE_ATTEMPTS = "urn:jabiz:dataset:finance:" + ATTEMPT;

    /** The default (administrators' read-only) dataset of an entity. */
    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /**
     * An attempt: who answers which publication (once: the pair is unique), until when, the order its options are
     * shown in and the answers so far; once finished, the result (with the correct answers, written only then), the
     * reward and how it was settled. Only {@code currentIndex} and {@code answers} change while it is in progress.
     * Answers and the result name options by their position in the attempt (Q10b), which only the option order maps
     * back to the version's numbers; the order is sensitive (no read interface returns it).
     */
    public static final EntityDefinition ATTEMPT_ENTITY = EntityDefinition.define(ATTEMPT, eb -> {
        eb.physicalTable("qb_attempt");
        eb.primaryKey("attemptId");
        eb.field("attemptId", f -> f.physicalColumn("attempt_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:attempt"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(128));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(QbPublications.PUBLICATION));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("versionId", f -> f.physicalColumn("version_id").immutable(true).required(true).processOnly()
            .asReference(QbContent.VERSION));
        eb.field("startedAt", f -> f.physicalColumn("started_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("deadline", f -> f.physicalColumn("deadline").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(ATTEMPT_STATUS_DICTIONARY, AttemptStatus.ALL.toArray(String[]::new)));
        eb.field("currentIndex", f -> f.physicalColumn("current_index").required(true).asNumeric(4, 0));
        // Sensitive (Q10b, M1): it maps the positions a taker answers by back to the author's order, so no read
        // interface returns it, not even the taker's own dataset; only the processes read it.
        eb.field("optionOrder", f -> f.physicalColumn("option_order").immutable(true).required(true)
            .sensitive().asText(PlayLimits.ANSWERS_LENGTH));
        eb.field("answers", f -> f.physicalColumn("answers").required(true).asText(PlayLimits.ANSWERS_LENGTH));
        eb.field("result", f -> f.physicalColumn("result").processOnly().asText(PlayLimits.RESULT_LENGTH));
        eb.field("submittedAt", f -> f.physicalColumn("submitted_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("score", f -> f.physicalColumn("score").processOnly().asNumeric(7, 0));
        eb.field("fullScore", f -> f.physicalColumn("full_score").processOnly().asNumeric(7, 0));
        eb.field("passed", f -> f.physicalColumn("passed").processOnly().asBool());
        eb.field("tierId", f -> f.physicalColumn("tier_id").processOnly().asText(36));
        eb.field("reward", f -> f.physicalColumn("reward").processOnly().asMonetary("JPY", 0));
        eb.field("fee", f -> f.physicalColumn("fee").processOnly().asMonetary("JPY", 0));
        eb.field("rewardStatus", f -> f.physicalColumn("reward_status").processOnly()
            .asCode(REWARD_STATUS_DICTIONARY, RewardStatus.ALL.toArray(String[]::new)));
        eb.field("rewardPaidAt", f -> f.physicalColumn("reward_paid_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("ledgerTransactionId", f -> f.physicalColumn("ledger_transaction_id").processOnly().asText(64));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        // Finished is final: the platform refuses any other move as well.
        eb.stateTransitions("status", st -> st
            .initial(AttemptStatus.IN_PROGRESS)
            .from(AttemptStatus.IN_PROGRESS).to(AttemptStatus.SUBMITTED, AttemptStatus.EXPIRED));
        eb.listView("default", lv -> lv
            .columns("userId", "publicationId", "status", "startedAt", "deadline", "submittedAt", "score", "reward",
                "rewardStatus")
            .filters("userId", "publicationId", "sponsorId", "status", "rewardStatus", "startedAt", "submittedAt")
            .sorts("startedAt", "submittedAt", "score", "reward", "attemptId")
            .defaultSort("startedAt", false));
    });

    /** The first time a taker opened a publication's page (Q9's funnel); later views are not recorded. */
    public static final EntityDefinition VIEW_ENTITY = EntityDefinition.define(VIEW, eb -> {
        eb.physicalTable("qb_publication_view");
        eb.primaryKey("viewId");
        eb.field("viewId", f -> f.physicalColumn("view_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:publication-view"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(128));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(QbPublications.PUBLICATION));
        eb.field("firstViewedAt", f -> f.physicalColumn("first_viewed_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("userId", "publicationId", "firstViewedAt")
            .filters("userId", "publicationId", "firstViewedAt")
            .sorts("firstViewedAt")
            .defaultSort("firstViewedAt", false));
    });

    /**
     * A publication's completions in the last hour, day, week and month before {@code refreshedAt}, refreshed by the
     * job {@code qb.publication-stats} (U-22: every ten minutes is enough); the total is the counter's, live.
     */
    public static final EntityDefinition STATS_ENTITY = EntityDefinition.define(STATS, eb -> {
        eb.physicalTable("qb_publication_stats");
        eb.primaryKey("publicationId");
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(QbPublications.PUBLICATION));
        eb.field("last1h", f -> f.physicalColumn("last_1h").required(true).asNumeric(9, 0));
        eb.field("last24h", f -> f.physicalColumn("last_24h").required(true).asNumeric(9, 0));
        eb.field("last7d", f -> f.physicalColumn("last_7d").required(true).asNumeric(9, 0));
        eb.field("last30d", f -> f.physicalColumn("last_30d").required(true).asNumeric(9, 0));
        eb.field("refreshedAt", f -> f.physicalColumn("refreshed_at").required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("publicationId", "last1h", "last24h", "last7d", "last30d", "refreshedAt")
            .filters("publicationId", "refreshedAt")
            .sorts("last24h", "last30d", "refreshedAt")
            .defaultSort("last24h", false));
    });

    /**
     * The last run of a statistics job: when its counts were taken. One row per job; the detail page shows it as the
     * time of the completion counts, which change only when the counts do. The analytics job (Q9e) also records the
     * zone the sponsors' days are counted in.
     */
    public static final EntityDefinition STATS_RUN_ENTITY = EntityDefinition.define(STATS_RUN, eb -> {
        eb.physicalTable("qb_stats_run");
        eb.primaryKey("job");
        eb.field("job", f -> f.physicalColumn("job").immutable(true).required(true).asText(64));
        eb.field("refreshedAt", f -> f.physicalColumn("refreshed_at").required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        // The analytics job's (Q9e, D-Q9e-3): the zone it grouped days by, jabiz.fiscal-year-zone.
        eb.field("zone", f -> f.physicalColumn("day_zone").asText(64));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("job", "refreshedAt", "zone")
            .filters("job")
            .sorts("job", "refreshedAt")
            .defaultSort("job", true));
    });

    private QbPlay() {}
}

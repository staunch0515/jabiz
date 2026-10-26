package com.jabiz.quizbuks.stats;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.publishing.PublisherKind;

/**
 * The entities of the sponsors' analytics (docs/quizbuks/plans/Q9e-analytics.md, Q9e1), all ordinary tables written
 * only by the job {@code qb.analytics}: the hourly buckets of a publication's increments, the job's cursor per
 * publication and the question statistics a sponsor is shown. Answering writes none of them (D-Q9e-2).
 */
public final class QbAnalytics {

    public static final String HOUR = "QbAnalyticsHour";
    public static final String CURSOR = "QbAnalyticsCursor";
    public static final String QUESTION_STAT = "QbQuestionStat";

    /** The default (administrators' read-only, the job's) dataset of an entity. */
    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /**
     * The job's own read-only datasets of the tables it reads in bulk (attempts, cards, first views): their query time
     * limit is the job's (a finished publication is read whole once), not the pages' five seconds.
     */
    public static String jobDataset(String entity) {
        return "urn:jabiz:dataset:stats:" + entity;
    }

    private static void count(com.jabiz.entity.EntityBuilder eb, String field, String column) {
        eb.field(field, f -> f.physicalColumn(column).required(true).asNumeric(9, 0));
    }

    /**
     * A publication's increments within one hour of UTC ({@code hourStart}, a whole hour): first views, starts,
     * completions, passes, winners and the rewards spent. An hour without increments has no row. {@code gap}: the row
     * also holds the increments of a time the job did not run, or the history before its first run (D-Q9e-2).
     */
    public static final EntityDefinition HOUR_ENTITY = EntityDefinition.define(HOUR, eb -> {
        eb.physicalTable("qb_analytics_hour");
        eb.primaryKey("hourId");
        eb.field("hourId", f -> f.physicalColumn("hour_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:analytics-hour"));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(QbPublications.PUBLICATION));
        PublisherKind.declare(eb, false);
        eb.field("hourStart", f -> f.physicalColumn("hour_start").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        count(eb, "views", "views");
        count(eb, "starts", "starts");
        count(eb, "completions", "completions");
        count(eb, "passes", "passes");
        count(eb, "winners", "winners");
        eb.field("spent", f -> f.physicalColumn("spent").required(true).asMonetary("JPY", 0));
        eb.field("gap", f -> f.physicalColumn("gap").required(true).asBool());
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("publicationId", "hourStart", "views", "starts", "completions", "passes", "winners", "spent",
                "gap")
            .filters("publicationId", "publisherKind", "publisherId", "hourStart")
            .sorts("hourStart")
            .defaultSort("hourStart", false));
    });

    /**
     * Where the job stands with a publication: the counters' values and the first views counted at its last change
     * ({@code views} … {@code spent}), how far the first views ({@code viewsUntil}) and the finished attempts
     * ({@code watermarkAt}, {@code watermarkId}) were read, the totals of the attempts read ({@code analysed},
     * {@code submitted}, {@code expired}, {@code durationSec} of the submitted ones, {@code questions} per question)
     * and the ones the sponsor is shown ({@code shown*}), which change only after at least the minimum group of new
     * completions (E-4). {@code finalizedAt}: the publication was recounted whole after it ended and is not read again.
     * The current totals are the job's and never shown: read only by administrators and the job.
     */
    public static final EntityDefinition CURSOR_ENTITY = EntityDefinition.define(CURSOR, eb -> {
        eb.physicalTable("qb_analytics_cursor");
        eb.primaryKey("publicationId");
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(QbPublications.PUBLICATION));
        PublisherKind.declare(eb, false);
        count(eb, "views", "views");
        count(eb, "starts", "starts");
        count(eb, "completions", "completions");
        count(eb, "passes", "passes");
        count(eb, "winners", "winners");
        eb.field("spent", f -> f.physicalColumn("spent").required(true).asMonetary("JPY", 0));
        eb.field("viewsUntil", f -> f.physicalColumn("views_until").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("watermarkAt", f -> f.physicalColumn("watermark_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("watermarkId", f -> f.physicalColumn("watermark_id").asText(36));
        count(eb, "analysed", "analysed");
        count(eb, "submitted", "submitted");
        count(eb, "expired", "expired");
        eb.field("durationSec", f -> f.physicalColumn("duration_sec").required(true).asNumeric(15, 3));
        // Derived from the attempts and rewritten whenever new ones are read: the audit keeps that it changed, not the
        // up to 4,000 characters each time (review Info; docs/quizbuks/perf/Q9e-analytics.md).
        eb.field("questions", f -> f.physicalColumn("questions").required(true).auditMasked()
            .asText(QuestionTotals.MAX_LENGTH));
        count(eb, "shownAnalysed", "shown_analysed");
        count(eb, "shownSubmitted", "shown_submitted");
        count(eb, "shownExpired", "shown_expired");
        eb.field("shownDurationSec", f -> f.physicalColumn("shown_duration_sec").required(true).asNumeric(15, 3));
        eb.field("shownAt", f -> f.physicalColumn("shown_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("finalizedAt", f -> f.physicalColumn("finalized_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("publicationId", "views", "starts", "completions", "analysed", "shownAnalysed", "shownAt",
                "finalizedAt")
            .filters("publicationId", "publisherKind", "publisherId", "finalizedAt")
            .sorts("shownAt", "finalizedAt")
            .defaultSort("shownAt", false));
    });

    /**
     * One question's completions and right answers (its points earned: a multiple choice only when all right, as the
     * scoring counts it) as the sponsor is shown them, written only when the shown figures change (E-4).
     */
    public static final EntityDefinition QUESTION_STAT_ENTITY = EntityDefinition.define(QUESTION_STAT, eb -> {
        eb.physicalTable("qb_question_stat");
        eb.primaryKey("statId");
        eb.field("statId", f -> f.physicalColumn("stat_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:question-stat"));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(QbPublications.PUBLICATION));
        PublisherKind.declare(eb, false);
        eb.field("questionNo", f -> f.physicalColumn("question_no").immutable(true).required(true)
            .asNumeric(4, 0));
        count(eb, "completions", "completions");
        count(eb, "correct", "correct");
        eb.field("shownAt", f -> f.physicalColumn("shown_at").required(true).asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("publicationId", "questionNo", "completions", "correct", "shownAt")
            .filters("publicationId", "publisherKind", "publisherId")
            .sorts("questionNo", "shownAt")
            .defaultSort("questionNo", true));
    });

    private QbAnalytics() {}
}

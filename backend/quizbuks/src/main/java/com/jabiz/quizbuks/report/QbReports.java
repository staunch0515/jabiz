package com.jabiz.quizbuks.report;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.QbPublications;

import java.util.List;

/**
 * Takers' reports of publications (Q49a; Apple App Review Guideline 1.2): what a taker found wrong with a quiz, kept
 * until headquarters' content administrators or the sponsor's operator dealt with it. An ordinary table: a report is
 * written once and only its handling changes.
 */
public final class QbReports {

    public static final String REPORT = "QbReport";
    public static final String DEFAULT_DATASET = "urn:jabiz:dataset:default:" + REPORT;

    public static final String REASON_DICTIONARY = "urn:jabiz:dict:quizbuks:report-reason";
    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:report-status";

    /** False or misleading: the quiz, its answers or its reward misrepresent something. */
    public static final String MISLEADING = "MISLEADING";
    /** Offensive, sexual, violent or otherwise inappropriate. */
    public static final String INAPPROPRIATE = "INAPPROPRIATE";
    /** The reward was not given as promised. */
    public static final String REWARD_NOT_GIVEN = "REWARD_NOT_GIVEN";
    /** Advertising or spam. */
    public static final String SPAM = "SPAM";
    public static final String OTHER = "OTHER";
    public static final List<String> REASONS = List.of(MISLEADING, INAPPROPRIATE, REWARD_NOT_GIVEN, SPAM, OTHER);

    /** Waiting for a reviewer. */
    public static final String OPEN = "OPEN";
    /** Looked at; nothing to do. */
    public static final String DISMISSED = "DISMISSED";
    /** Looked at and acted on (the publication paused or ended, the sponsor contacted). */
    public static final String ACTIONED = "ACTIONED";
    public static final List<String> STATUSES = List.of(OPEN, DISMISSED, ACTIONED);
    /** What a reviewer may decide. */
    public static final List<String> OUTCOMES = List.of(DISMISSED, ACTIONED);

    public static final int NOTE_LENGTH = 500;
    public static final int RESOLUTION_LENGTH = 500;
    /** How many reports one taker may make in a day (Japan's day does not matter: 24 hours back from now). */
    public static final int DAILY_LIMIT = 10;

    /** The type of the reviewers' task, closed by the source key of the publication once its reports are handled. */
    static final String TASK_TYPE = "qb.report";

    static String taskSource(Object publicationId) {
        return "qb.report:" + publicationId;
    }

    /** Reports of one publication take turns, so that its first open report creates the task once. */
    static String lockName(Object publicationId) {
        return "qb.report:" + publicationId;
    }

    /** A taker's reports take turns, so that their reports of a day are counted once each. */
    static String reporterLockName(Object userId) {
        return "qb.report-user:" + userId;
    }

    public static final EntityDefinition ENTITY = EntityDefinition.define(REPORT, eb -> {
        eb.physicalTable("qb_report");
        eb.primaryKey("reportId");
        eb.field("reportId", f -> f.physicalColumn("report_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:report"));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .processOnly().asReference(QbPublications.PUBLICATION));
        PublisherKind.declare(eb, true);
        eb.field("reporterId", f -> f.physicalColumn("reporter_id").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).required(true).processOnly()
            .asCode(REASON_DICTIONARY, REASONS.toArray(String[]::new)));
        eb.field("note", f -> f.physicalColumn("note").immutable(true).processOnly().asText(NOTE_LENGTH, true));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("resolvedAt", f -> f.physicalColumn("resolved_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("resolvedBy", f -> f.physicalColumn("resolved_by").processOnly().asText(128));
        eb.field("resolution", f -> f.physicalColumn("resolution").processOnly()
            .asText(RESOLUTION_LENGTH, true));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("publicationId", "reason", "note", "status", "createdAt", "resolvedAt", "resolvedBy",
                "resolution")
            .filters("publicationId", "publisherId", "reporterId", "reason", "status", "createdAt")
            .sorts("createdAt", "resolvedAt")
            .defaultSort("createdAt", false));
    });

    private QbReports() {}
}

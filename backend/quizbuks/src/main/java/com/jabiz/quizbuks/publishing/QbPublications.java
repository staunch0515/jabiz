package com.jabiz.quizbuks.publishing;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.country.Regions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static com.jabiz.quizbuks.publishing.PublicationStatus.DRAFT;
import static com.jabiz.quizbuks.publishing.PublicationStatus.ENDED;
import static com.jabiz.quizbuks.publishing.PublicationStatus.FUNDS_SHORT;
import static com.jabiz.quizbuks.publishing.PublicationStatus.LIVE;
import static com.jabiz.quizbuks.publishing.PublicationStatus.PAUSED;
import static com.jabiz.quizbuks.publishing.PublicationStatus.PENDING_REVIEW;
import static com.jabiz.quizbuks.publishing.PublicationStatus.SCHEDULED;

/**
 * The four entities of publishing (docs/quizbuks/02-design.md section 3.3, docs/quizbuks/plans/Q4-publishing.md):
 * the publication and its reward tiers, temporal and without scheduled versions, and the counters of a publication
 * that is on (an ordinary table: Q5 updates it on every attempt, under the publication's lock) and the schedule of a
 * submitted publication (an ordinary table the tick and the removal of a quiz read). Each publication and tier
 * carries the sponsor ({@code sponsorId}), so a sponsor's dataset is limited without a join. Only the processes of
 * {@link PublicationProcesses} and {@link PublicationLifecycle} write them.
 */
public final class QbPublications {

    public static final String PUBLICATION = "QbPublication";
    public static final String TIER = "QbRewardTier";
    public static final String COUNTER = "QbPublicationCounter";
    public static final String SCHEDULE = "QbPublicationSchedule";

    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:publication-status";
    public static final String END_REASON_DICTIONARY = "urn:jabiz:dict:quizbuks:publication-end-reason";

    /** The default (administrators' read-only) dataset of an entity. */
    public static String defaultDataset(String entity) {
        return QbContent.defaultDataset(entity);
    }

    /** The sponsor's dataset of an entity: their own rows only. */
    public static String sponsorDataset(String entity) {
        return QbContent.sponsorDataset(entity);
    }

    private static BigDecimal decimal(long value) {
        return BigDecimal.valueOf(value);
    }

    public static final EntityDefinition PUBLICATION_ENTITY = EntityDefinition.define(PUBLICATION, eb -> {
        eb.physicalTable("qb_publication_version");
        eb.primaryKey("publicationId");
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:publication"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        // The quiz of the version, kept with it: removing a quiz looks up the publications that use it.
        eb.field("quizId", f -> f.physicalColumn("quiz_id").asReference(QbContent.QUIZ));
        eb.field("versionId", f -> f.physicalColumn("version_id").asReference(QbContent.VERSION));
        eb.field("versionLabel", f -> f.physicalColumn("version_label").processOnly().asText(20));
        eb.field("name", f -> f.physicalColumn("name").required(true).asText(PublishingLimits.NAME_LENGTH)
            .apply(Rules.notBlank(PublishingCodes.NAME_BLANK)));
        eb.field("regions", f -> f.physicalColumn("regions").asText(Regions.MAX_LENGTH)
            .apply(Rules.pattern(PublishingCodes.REGIONS_FORMAT, Regions.TARGETS.pattern())));
        eb.field("startAt", f -> f.physicalColumn("start_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("endAt", f -> f.physicalColumn("end_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("timeLimitSec", f -> f.physicalColumn("time_limit_sec").asNumeric(5, 0)
            .apply(Rules.range(PublishingCodes.TIME_LIMIT_RANGE, decimal(PublishingLimits.MIN_TIME_LIMIT_SEC),
                decimal(PublishingLimits.MAX_TIME_LIMIT_SEC))));
        eb.field("passScore", f -> f.physicalColumn("pass_score").asNumeric(6, 0)
            .apply(Rules.range(PublishingCodes.SCORE_RANGE, BigDecimal.ZERO, decimal(PublishingLimits.MAX_SCORE))));
        eb.field("budgetCap", f -> f.physicalColumn("budget_cap").asMonetary("JPY", 0)
            .apply(Rules.range(PublishingCodes.BUDGET_RANGE, BigDecimal.ONE, PublishingLimits.MAX_MONEY)));
        eb.field("maxWinners", f -> f.physicalColumn("max_winners").asNumeric(9, 0)
            .apply(Rules.range(PublishingCodes.MAX_WINNERS_RANGE, BigDecimal.ONE,
                decimal(PublishingLimits.MAX_WINNERS))));
        eb.field("fullScore", f -> f.physicalColumn("full_score").processOnly().asNumeric(7, 0));
        eb.field("maxReward", f -> f.physicalColumn("max_reward").processOnly().asMonetary("JPY", 0));
        eb.field("maxSpend", f -> f.physicalColumn("max_spend").processOnly().asMonetary("JPY", 0));
        eb.field("tierCount", f -> f.physicalColumn("tier_count").required(true).processOnly().asNumeric(2, 0));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, PublicationStatus.ALL.toArray(String[]::new)));
        eb.field("rejectReason", f -> f.physicalColumn("reject_reason").processOnly()
            .asText(PublishingLimits.REJECT_REASON_LENGTH, true));
        eb.field("rejectCode", f -> f.physicalColumn("reject_code").processOnly().asText(64));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").processOnly().asText(64));
        eb.field("submittedAt", f -> f.physicalColumn("submitted_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("approvedAt", f -> f.physicalColumn("approved_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("pausedAt", f -> f.physicalColumn("paused_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("endedAt", f -> f.physicalColumn("ended_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("endReason", f -> f.physicalColumn("end_reason").processOnly()
            .asCode(END_REASON_DICTIONARY, PublicationStatus.END_REASONS.toArray(String[]::new)));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        eb.field("editedAt", f -> f.physicalColumn("edited_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        // The source's key, not a reference: a draft that was copied can still be deleted.
        eb.field("copiedFrom", f -> f.physicalColumn("copied_from").immutable(true).asText(36));
        // D-Q4-1. A rejected or withdrawn publication goes back to its draft, so the first state is declared.
        eb.stateTransitions("status", st -> st
            .initial(DRAFT)
            .from(DRAFT).to(PENDING_REVIEW, SCHEDULED, LIVE)
            .from(PENDING_REVIEW).to(DRAFT, SCHEDULED, LIVE)
            .from(SCHEDULED).to(LIVE, PAUSED, ENDED)
            .from(LIVE).to(PAUSED, FUNDS_SHORT, ENDED)
            .from(PAUSED).to(SCHEDULED, LIVE, ENDED)
            .from(FUNDS_SHORT).to(LIVE, PAUSED, ENDED));
        eb.check(PublishingCodes.TIME_ORDER, (state, ctx) -> timeOrder(state));
        eb.display("name");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("name", "sponsorId", "versionLabel", "status", "regions", "startAt", "endAt", "maxReward",
                "maxSpend", "submittedAt", "editedAt")
            .filters("name", "sponsorId", "quizId", "versionId", "status", "startAt", "endAt", "editedAt")
            .sorts("publicationId", "name", "startAt", "endAt", "editedAt", "submittedAt")
            .defaultSort("editedAt", false));
    });

    /** When both times are given, the start comes before the end (D-Q4-6; drafts may lack either). */
    static List<Violation> timeOrder(Map<String, Object> state) {
        Instant start = instant(state.get("startAt"));
        Instant end = instant(state.get("endAt"));
        if (start != null && end != null && !start.isBefore(end)) {
            return List.of(new Violation("endAt", PublishingCodes.TIME_ORDER, "The end must come after the start",
                Map.of()));
        }
        return List.of();
    }

    static Instant instant(Object value) {
        return value == null ? null : value instanceof OffsetDateTime time ? time.toInstant()
            : value instanceof Instant time ? time : Instant.parse(value.toString());
    }

    public static final EntityDefinition TIER_ENTITY = EntityDefinition.define(TIER, eb -> {
        eb.physicalTable("qb_reward_tier_version");
        eb.primaryKey("tierId");
        eb.field("tierId", f -> f.physicalColumn("tier_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:reward-tier"));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(PUBLICATION));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        eb.field("seq", f -> f.physicalColumn("seq").required(true).asNumeric(2, 0));
        eb.field("fromScore", f -> f.physicalColumn("from_score").required(true).asNumeric(6, 0)
            .apply(Rules.range(PublishingCodes.SCORE_RANGE, BigDecimal.ZERO, decimal(PublishingLimits.MAX_SCORE))));
        eb.field("toScore", f -> f.physicalColumn("to_score").required(true).asNumeric(6, 0)
            .apply(Rules.range(PublishingCodes.SCORE_RANGE, BigDecimal.ZERO, decimal(PublishingLimits.MAX_SCORE))));
        eb.field("reward", f -> f.physicalColumn("reward").required(true).asMonetary("JPY", 0)
            .apply(Rules.range(PublishingCodes.TIER_REWARD_RANGE, BigDecimal.ZERO, PublishingLimits.MAX_MONEY)));
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("publicationId", "seq", "fromScore", "toScore", "reward")
            .filters("publicationId", "sponsorId")
            .sorts("seq", "fromScore")
            .defaultSort("seq", true));
    });

    /**
     * The counters of a publication that went on: attempts started, completed and passed, winners (a reward above
     * 0), and the Kudos reserved by attempts in progress and spent. Inserted with zeros when the publication is
     * approved; Q5 updates it under the publication's lock. Not temporal: each attempt would add a version.
     */
    public static final EntityDefinition COUNTER_ENTITY = EntityDefinition.define(COUNTER, eb -> {
        eb.physicalTable("qb_publication_counter");
        eb.primaryKey("publicationId");
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(PUBLICATION));
        eb.field("starts", f -> f.physicalColumn("starts").required(true).asNumeric(9, 0));
        eb.field("completions", f -> f.physicalColumn("completions").required(true).asNumeric(9, 0));
        eb.field("passes", f -> f.physicalColumn("passes").required(true).asNumeric(9, 0));
        eb.field("winners", f -> f.physicalColumn("winners").required(true).asNumeric(9, 0));
        eb.field("reserved", f -> f.physicalColumn("reserved").required(true).asMonetary("JPY", 0));
        eb.field("spent", f -> f.physicalColumn("spent").required(true).asMonetary("JPY", 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("publicationId", "starts", "completions", "passes", "winners", "reserved", "spent")
            .filters("publicationId")
            .sorts("starts", "spent")
            .defaultSort("starts", false));
    });

    /**
     * A publication that was submitted and is not a draft again: its version (so that removing its quiz keeps the
     * version's files) and when the minute's tick must next look at it ({@link PublicationRules#nextDue}; none once it
     * ended). An ordinary table, kept by the processes under the publication's lock, so that the tick and the removal
     * of a quiz find their publications through plain indexes ({@code next_due_at}, {@code quiz_id}) rather than
     * through the current versions of every publication. Removed when the publication goes back to its draft.
     */
    public static final EntityDefinition SCHEDULE_ENTITY = EntityDefinition.define(SCHEDULE, eb -> {
        eb.physicalTable("qb_publication_schedule");
        eb.primaryKey("publicationId");
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(PUBLICATION));
        eb.field("quizId", f -> f.physicalColumn("quiz_id").immutable(true).required(true)
            .asReference(QbContent.QUIZ));
        eb.field("versionId", f -> f.physicalColumn("version_id").immutable(true).required(true)
            .asReference(QbContent.VERSION));
        eb.field("nextDueAt", f -> f.physicalColumn("next_due_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("publicationId", "quizId", "versionId", "nextDueAt")
            .filters("publicationId", "quizId", "nextDueAt")
            .sorts("nextDueAt", "publicationId")
            .defaultSort("nextDueAt", true));
    });

    /**
     * The approval subject of publications (D-Q4-4): the facts the rules test. The rule {@link
     * PublicationApprovals#RULE} asks for a review unless {@code autoApprove}.
     */
    public static ApprovalSubject approvalSubject() {
        return ApprovalSubject.define(PublicationApprovals.SUBJECT, s -> s.entity(PUBLICATION).bool("autoApprove")
            .number("maxReward").number("maxSpend").text("sponsorId").text("regions"));
    }

    private QbPublications() {}
}

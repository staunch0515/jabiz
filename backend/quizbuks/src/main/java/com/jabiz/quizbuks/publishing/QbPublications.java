package com.jabiz.quizbuks.publishing;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.content.ContentLimits;
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
 * that is on (two ordinary tables, Q10a4: the starts' side under the start lock, the completions' side under the
 * publication's lock; every attempt updates each once) and the schedule of a
 * submitted publication (an ordinary table the tick and the removal of a quiz read). Each publication and tier
 * carries its publisher ({@link PublisherKind}: kind and id, S0), so a sponsor's dataset is limited without a join. Only
 * the processes of
 * {@link PublicationProcesses} and {@link PublicationLifecycle} write them.
 */
public final class QbPublications {

    public static final String PUBLICATION = "QbPublication";
    public static final String TIER = "QbRewardTier";
    /** The completions' side of a publication's counters (written under {@code qb.pub:<publication>}). */
    public static final String COUNTER = "QbPublicationCounter";
    /** The starts' side of a publication's counters (written under {@code qb.pub-start:<publication>}, Q10a4). */
    public static final String START_COUNTER = "QbPublicationStartCounter";
    public static final String SCHEDULE = "QbPublicationSchedule";
    public static final String CARD = "QbPublicationCard";
    /** The live publications' cards that a share link shows to anybody (Q15): a whitelist of their fields. */
    public static final String PUBLIC_CARDS = "urn:jabiz:dataset:public:" + CARD;
    public static final String OWNER = "QbPublicationOwner";
    /** Q22: the reward methods a sponsor may publish with, set by an administrator. */
    public static final String SPONSOR_METHODS = "QbSponsorRewardMethods";
    /** The cards of the caller's own publications: the takers' templates leave them out of what can be started. */
    public static final String MY_OWNER_DATASET = "urn:jabiz:dataset:me:" + OWNER;

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
        PublisherKind.declare(eb, false);
        // The quiz of the version, kept with it: removing a quiz looks up the publications that use it.
        eb.field("quizId", f -> f.physicalColumn("quiz_id").asReference(QbContent.QUIZ));
        eb.field("versionId", f -> f.physicalColumn("version_id").asReference(QbContent.VERSION));
        eb.field("versionLabel", f -> f.physicalColumn("version_label").processOnly().asText(20));
        eb.field("name", f -> f.physicalColumn("name").required(true).asText(PublishingLimits.NAME_LENGTH)
            .apply(Rules.notBlank(PublishingCodes.NAME_BLANK)));
        eb.field("regions", f -> f.physicalColumn("regions").asText(Regions.MAX_LENGTH)
            .apply(Rules.pattern(PublishingCodes.REGIONS_FORMAT, Regions.TARGETS.pattern())));
        // Q25: everybody in the regions, or the invitation list only (then the regions are not asked).
        eb.field("audience", f -> f.physicalColumn("audience").required(true)
            .asCode(Audience.DICTIONARY, Audience.ALL.toArray(String[]::new)));
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
        // Q22: how the reward reaches the taker (RewardMethod), and for a prize collected at the sponsor's place what
        // it is and where; both frozen with the policy.
        eb.field("rewardMethod", f -> f.physicalColumn("reward_method").required(true)
            .asCode(RewardMethod.DICTIONARY, RewardMethod.ALL.toArray(String[]::new)));
        eb.field("redeemDescription", f -> f.physicalColumn("redeem_description")
            .asText(PublishingLimits.REDEEM_DESCRIPTION_LENGTH, true));
        // Q33: a digital prize's delivery, what it is (as the winner reads it), the days the sponsor has to send one
        // by hand, and the shared code or link sealed (sensitive: never read back, opened for a winner only).
        eb.field("digitalDelivery", f -> f.physicalColumn("digital_delivery")
            .asCode(DigitalDelivery.DICTIONARY, DigitalDelivery.ALL.toArray(String[]::new)));
        eb.field("digitalDescription", f -> f.physicalColumn("digital_description")
            .asText(PublishingLimits.REDEEM_DESCRIPTION_LENGTH, true));
        eb.field("digitalSendDays", f -> f.physicalColumn("digital_send_days").asNumeric(3, 0)
            .apply(Rules.range(PublishingCodes.DIGITAL_SEND_DAYS_RANGE, BigDecimal.ONE,
                decimal(PublishingLimits.MAX_DIGITAL_SEND_DAYS))));
        eb.field("sealedDigitalContent", f -> f.physicalColumn("sealed_digital_content").sensitive()
            .asText(PublishingLimits.SEALED_LENGTH));
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
        // Q10b (M2): who paused it (PublicationStatus.BY_SPONSOR / BY_ADMIN), and the reason an administrator gave for
        // pausing, resuming or ending it (the sponsor reads it).
        eb.field("pausedBy", f -> f.physicalColumn("paused_by").processOnly().asText(16));
        eb.field("adminReason", f -> f.physicalColumn("admin_reason").processOnly()
            .asText(PublishingLimits.REJECT_REASON_LENGTH, true));
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
            .columns("name", "publisherKind", "publisherId", "versionLabel", "status", "rewardMethod", "audience",
                "regions", "startAt", "endAt", "maxReward", "maxSpend", "submittedAt", "editedAt")
            .filters("name", "publisherKind", "publisherId", "quizId", "versionId", "status", "rewardMethod",
                "startAt", "endAt", "editedAt")
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
        PublisherKind.declare(eb, false);
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
            .filters("publicationId", "publisherKind", "publisherId")
            .sorts("seq", "fromScore")
            .defaultSort("seq", true));
    });

    /**
     * The completions' side of the counters of a publication that went on (docs/quizbuks/plans/Q10-lock-split.md):
     * attempts completed and passed, winners (a reward above 0), the highest rewards their attempts had reserved and
     * now released ({@code released}: the card's {@code maxReward} each) and the Kudos spent. Inserted with zeros,
     * with {@link #START_COUNTER_ENTITY}, when the publication goes on; only a completion updates it, under the
     * publication's lock {@code qb.pub:<publication>}. Attempts in progress ({@code starts - completions}) and the
     * Kudos reserved ({@code reservedTotal - released}) need both sides, read in one statement (one snapshot: never
     * fewer starts than completions). Not temporal: each attempt would add a version.
     */
    public static final EntityDefinition COUNTER_ENTITY = EntityDefinition.define(COUNTER, eb -> {
        eb.physicalTable("qb_publication_counter");
        eb.primaryKey("publicationId");
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(PUBLICATION));
        eb.field("completions", f -> f.physicalColumn("completions").required(true).asNumeric(9, 0));
        eb.field("passes", f -> f.physicalColumn("passes").required(true).asNumeric(9, 0));
        eb.field("winners", f -> f.physicalColumn("winners").required(true).asNumeric(9, 0));
        eb.field("released", f -> f.physicalColumn("released").required(true).asMonetary("JPY", 0));
        eb.field("spent", f -> f.physicalColumn("spent").required(true).asMonetary("JPY", 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("publicationId", "completions", "passes", "winners", "released", "spent")
            .filters("publicationId")
            .sorts("completions", "spent")
            .defaultSort("completions", false));
    });

    /**
     * The starts' side of the counters of a publication that went on (docs/quizbuks/plans/Q10-lock-split.md):
     * attempts started and the highest rewards they reserved ({@code reservedTotal}: the card's {@code maxReward}
     * each), ever. Inserted with zeros, with {@link #COUNTER_ENTITY}, when the publication goes on; only a start
     * updates it, under the start lock {@code qb.pub-start:<publication>}.
     */
    public static final EntityDefinition START_COUNTER_ENTITY = EntityDefinition.define(START_COUNTER, eb -> {
        eb.physicalTable("qb_publication_start_counter");
        eb.primaryKey("publicationId");
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(PUBLICATION));
        eb.field("starts", f -> f.physicalColumn("starts").required(true).asNumeric(9, 0));
        eb.field("reservedTotal", f -> f.physicalColumn("reserved_total").required(true).asMonetary("JPY", 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("publicationId", "starts", "reservedTotal")
            .filters("publicationId")
            .sorts("starts", "reservedTotal")
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
     * The card of a publication that went on (docs/quizbuks/plans/Q5-answering.md, D-Q5-6): what the takers' feed,
     * the detail page and the answering processes need of it, in an ordinary row. Every field is frozen at submission
     * except the state, which the publication's processes keep in step under its lock ({@link PublicationEditing}).
     * The feed filters on state, times and regions; on the temporal publications every such condition would read every
     * version. Inserted with the counters when the publication goes on; never deleted (the takers' history joins it).
     */
    public static final EntityDefinition CARD_ENTITY = EntityDefinition.define(CARD, eb -> {
        eb.physicalTable("qb_publication_card");
        eb.primaryKey("publicationId");
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(PUBLICATION));
        PublisherKind.declare(eb, false);
        eb.field("status", f -> f.physicalColumn("status").required(true)
            .asCode(STATUS_DICTIONARY, PublicationStatus.ALL.toArray(String[]::new)));
        eb.field("regions", f -> f.physicalColumn("regions").immutable(true).required(true)
            .asText(Regions.MAX_LENGTH));
        // Q25: an invited publication is left out of the feed and the public page; only invitees start it.
        eb.field("audience", f -> f.physicalColumn("audience").immutable(true).required(true)
            .asCode(Audience.DICTIONARY, Audience.ALL.toArray(String[]::new)));
        eb.field("startAt", f -> f.physicalColumn("start_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("endAt", f -> f.physicalColumn("end_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("name", f -> f.physicalColumn("name").immutable(true).required(true)
            .asText(PublishingLimits.NAME_LENGTH));
        eb.field("quizTitle", f -> f.physicalColumn("quiz_title").immutable(true).required(true)
            .asText(ContentLimits.TITLE_LENGTH));
        // The cover's file key as text: the version's files keep the file, the card only names it.
        eb.field("cover", f -> f.physicalColumn("cover").immutable(true).asText(36));
        // Q19: the player of the version's video to watch (VideoEmbeds), shown on the App's home.
        eb.field("video", f -> f.physicalColumn("video").immutable(true).asText(80));
        eb.field("versionId", f -> f.physicalColumn("version_id").immutable(true).required(true)
            .asReference(QbContent.VERSION));
        eb.field("maxReward", f -> f.physicalColumn("max_reward").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("fullScore", f -> f.physicalColumn("full_score").immutable(true).required(true).asNumeric(7, 0));
        eb.field("passScore", f -> f.physicalColumn("pass_score").immutable(true).required(true).asNumeric(6, 0));
        eb.field("timeLimitSec", f -> f.physicalColumn("time_limit_sec").immutable(true).required(true)
            .asNumeric(5, 0));
        eb.field("questionCount", f -> f.physicalColumn("question_count").immutable(true).required(true)
            .asNumeric(4, 0));
        eb.field("budgetCap", f -> f.physicalColumn("budget_cap").immutable(true).asMonetary("JPY", 0));
        eb.field("maxWinners", f -> f.physicalColumn("max_winners").immutable(true).asNumeric(9, 0));
        // CardTiers' JSON: the tiers are frozen at submission too.
        eb.field("tiers", f -> f.physicalColumn("tiers").immutable(true).required(true)
            .asText(PublishingLimits.CARD_TIERS_LENGTH));
        // The country of the sponsor's profile when the publication went on, for the feed's filter (U-13).
        eb.field("sponsorCountry", f -> f.physicalColumn("sponsor_country").immutable(true).asText(2));
        // A scored quiz or a survey (Q12), from the version's snapshot.
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(QbContent.QUIZ_KIND_DICTIONARY, QbContent.KIND_QUIZ, QbContent.KIND_SURVEY));
        // Q22: the reward method and, for a prize at the sponsor's place, what it is: the feed filters on the method
        // and the detail page shows the prize.
        eb.field("rewardMethod", f -> f.physicalColumn("reward_method").immutable(true).required(true)
            .asCode(RewardMethod.DICTIONARY, RewardMethod.ALL.toArray(String[]::new)));
        eb.field("redeemDescription", f -> f.physicalColumn("redeem_description").immutable(true)
            .asText(PublishingLimits.REDEEM_DESCRIPTION_LENGTH, true));
        // Q33: the digital prize's delivery and description, and the shared content sealed (copied to each winner).
        eb.field("digitalDelivery", f -> f.physicalColumn("digital_delivery").immutable(true)
            .asCode(DigitalDelivery.DICTIONARY, DigitalDelivery.ALL.toArray(String[]::new)));
        eb.field("digitalDescription", f -> f.physicalColumn("digital_description").immutable(true)
            .asText(PublishingLimits.REDEEM_DESCRIPTION_LENGTH, true));
        eb.field("digitalSendDays", f -> f.physicalColumn("digital_send_days").immutable(true).asNumeric(3, 0));
        eb.field("sealedDigitalContent", f -> f.physicalColumn("sealed_digital_content").immutable(true)
            .sensitive().asText(PublishingLimits.SEALED_LENGTH));
        eb.field("wentOnAt", f -> f.physicalColumn("went_on_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        // When it ended, by whatever reason (its time, its winners, its budget): the statistics job counts a
        // publication for a month after this.
        eb.field("endedAt", f -> f.physicalColumn("ended_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("name", "quizTitle", "publisherKind", "publisherId", "status", "rewardMethod", "audience",
                "regions", "startAt", "endAt", "maxReward")
            .filters("publisherKind", "publisherId", "status", "rewardMethod", "startAt", "endAt", "endedAt")
            .sorts("startAt", "endAt", "wentOnAt", "publicationId")
            .defaultSort("wentOnAt", false));
    });

    /**
     * Who published a publication that went on: a read-only view of the cards' keys and sponsors
     * ({@code qb_publication_owner}). Read only through {@link #MY_OWNER_DATASET}, limited to the caller, so that a
     * template knows which publications are the caller's own without a parameter the caller could choose.
     */
    public static final EntityDefinition OWNER_ENTITY = EntityDefinition.define(OWNER, eb -> {
        eb.physicalTable("qb_publication_owner");
        eb.primaryKey("publicationId");
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(PUBLICATION));
        PublisherKind.declare(eb, false);
        eb.listView("default", lv -> lv
            .columns("publicationId", "publisherKind", "publisherId")
            .filters("publicationId")
            .sorts("publicationId")
            .defaultSort("publicationId", true));
    });

    /**
     * The reward methods a sponsor may publish with (Q22, docs/quizbuks/plans/Q22-reward-methods.md): set by an
     * administrator with a note, one row per sponsor ({@code userId}, unique); a sponsor without one has
     * {@link RewardMethod#DEFAULT}. Temporal: its versions are the record of who allowed what when. Only
     * {@code QB_SPONSOR_REWARD_METHODS_SET} writes it; the sponsor reads their own through the templates.
     */
    public static final EntityDefinition SPONSOR_METHODS_ENTITY = EntityDefinition.define(SPONSOR_METHODS, eb -> {
        eb.physicalTable("qb_sponsor_reward_methods_version");
        eb.primaryKey("settingId");
        eb.field("settingId", f -> f.physicalColumn("setting_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:sponsor-reward-methods"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(128));
        // The methods as RewardMethod.format writes them.
        eb.field("methods", f -> f.physicalColumn("methods").required(true).processOnly().asText(64)
            .apply(Rules.pattern(PublishingCodes.REWARD_METHODS_FORMAT,
                "(PLATFORM|SPONSOR_CASH|OFFLINE_REDEEM|DIGITAL_PRIZE)(,(PLATFORM|SPONSOR_CASH|OFFLINE_REDEEM|DIGITAL_PRIZE))*")));
        eb.field("note", f -> f.physicalColumn("note").processOnly()
            .asText(PublishingLimits.REJECT_REASON_LENGTH, true));
        eb.field("setBy", f -> f.physicalColumn("set_by").required(true).processOnly().asText(128));
        eb.field("setAt", f -> f.physicalColumn("set_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        eb.unique("uk_qb_sponsor_reward_methods_user", "userId");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("userId", "methods", "note", "setBy", "setAt")
            .filters("userId", "setAt")
            .sorts("setAt", "userId")
            .defaultSort("setAt", false));
    });

    /**
     * The approval subject of publications (D-Q4-4): the facts the rules test. The rule {@link
     * PublicationApprovals#RULE} asks for a review unless {@code autoApprove}.
     */
    public static ApprovalSubject approvalSubject() {
        return ApprovalSubject.define(PublicationApprovals.SUBJECT, s -> s.entity(PUBLICATION).bool("autoApprove")
            .bool("takenDown")
            .number("maxReward").number("maxSpend").text("publisherKind").text("publisherId").text("regions")
            .text("rewardMethod"));
    }

    private QbPublications() {}
}

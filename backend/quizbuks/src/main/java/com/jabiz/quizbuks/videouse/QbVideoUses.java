package com.jabiz.quizbuks.videouse;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.quizbuks.identity.AccountLimits;

import java.util.Locale;

/**
 * The ledger of shared videos' uses and both sides' figures (F-S14, docs/quizbuks/plans/impl/F-S14.md):
 * <ul>
 *   <li>{@value #USE}: one use of another sponsor's shared video by a publication, written by the user (the
 *       publication's sponsor) and answered by the provider; temporal and append-only, every save, submission and answer
 *       a new version (audited by the platform). No money moves on the platform for it and it is not in the platform's
 *       books;</li>
 *   <li>{@value #FIGURES}: the publication's opens, exposure successes, takers who went on to answer and those who
 *       passed, from the counting tables up to a cut-off; refreshed every day, finalised once the publication ended.</li>
 * </ul>
 * Written by {@link VideoUseProcesses} and {@link VideoUseFigures} only.
 */
public final class QbVideoUses {

    public static final String USE = "QbVideoUse";
    public static final String FIGURES = "QbVideoUseFigures";
    /** Every version of a use with the operation that wrote it (a read-only view, {@code qb_video_use_history}). */
    public static final String HISTORY = "QbVideoUseHistory";

    public static final String PRICING_DICTIONARY = "urn:jabiz:dict:quizbuks:video-use-pricing";
    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:video-use-status";

    /** The two sides: the user (the publication's sponsor) and the provider of the shared video. */
    public static final String SPONSOR = "SPONSOR";
    public static final String PROVIDER = "PROVIDER";

    public static final int ID_LENGTH = 36;
    public static final int NOTE_LENGTH = 1_000;
    public static final int REASON_LENGTH = 500;

    /** The templates (queries/qb/sponsor, queries/qb/admin, queries/qb/system). */
    public static final String SPONSOR_TEMPLATE = "qb.sponsor.video-uses";
    public static final String PROVIDER_TEMPLATE = "qb.sponsor.video-uses-provided";
    public static final String ADMIN_TEMPLATE = "qb.admin.video-uses";
    public static final String FIGURES_DUE_TEMPLATE = "qb.system.video-use-figures-due";
    public static final String COUNT_TEMPLATE = "qb.system.video-use-count";
    public static final String REMINDERS_DUE_TEMPLATE = "qb.system.video-use-reminders-due";
    public static final String DIFFERENCES_TEMPLATE = "qb.system.video-use-differences";

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** A side's own rows ({@code sponsor} or {@code provider}), for its templates only. */
    public static String sideDataset(String side, String entity) {
        return "urn:jabiz:dataset:" + side.toLowerCase(Locale.ROOT) + ":" + entity;
    }

    /** A publication's use: its four processes take turns (the publication never changes). */
    public static String lockName(Object publicationId) {
        return "qb.video-use:" + publicationId;
    }

    /** The provider's operator's read path (F-S14 follow-up): the uses and figures of its own sponsors' videos. */
    public static String providerOperatorDataset(String entity) {
        return "urn:jabiz:dataset:operator-provider:" + entity;
    }

    public static final String ADMIN_PROVIDED_TEMPLATE = "qb.admin.video-uses-provided";
    /** The history of one use: the user's side, the provider's, the users' operators', the providers' operators'. */
    public static final String SPONSOR_HISTORY_TEMPLATE = "qb.sponsor.video-use-history";
    public static final String PROVIDER_HISTORY_TEMPLATE = "qb.sponsor.video-use-history-provided";
    public static final String ADMIN_HISTORY_TEMPLATE = "qb.admin.video-use-history";
    public static final String ADMIN_PROVIDED_HISTORY_TEMPLATE = "qb.admin.video-use-history-provided";

    /** A publication's figures: the job's item and nothing else writes them. */
    public static String figuresLockName(Object publicationId) {
        return "qb.video-use-figures:" + publicationId;
    }

    public static final EntityDefinition USE_ENTITY = EntityDefinition.define(USE, eb -> {
        eb.physicalTable("qb_video_use_version");
        eb.primaryKey("videoUseId");
        eb.field("videoUseId", f -> f.physicalColumn("video_use_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:video-use"));
        // Text, as the exposures': the card was read in the same process.
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .processOnly().asText(ID_LENGTH));
        eb.field("sharedVideoId", f -> f.physicalColumn("shared_video_id").immutable(true).required(true)
            .processOnly().asText(ID_LENGTH));
        // The user (the publication's sponsor) and the provider, frozen from the card.
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("providerId", f -> f.physicalColumn("provider_id").immutable(true).required(true).processOnly()
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("periodFrom", f -> f.physicalColumn("period_from").required(true).processOnly().asDate());
        eb.field("periodTo", f -> f.physicalColumn("period_to").required(true).processOnly().asDate());
        eb.field("pricing", f -> f.physicalColumn("pricing").required(true).processOnly()
            .asCode(PRICING_DICTIONARY, VideoUseRules.PRICINGS.toArray(String[]::new)));
        // The fixed price, or the price per thousand exposures or per taker who went on to answer: the system's
        // currency, no decimals (as the sponsored benefits').
        eb.field("price", f -> f.physicalColumn("price").required(true).processOnly().asMonetary("JPY", 0));
        eb.field("note", f -> f.physicalColumn("note").processOnly().asText(NOTE_LENGTH, true));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, VideoUseRules.STATUSES.toArray(String[]::new)));
        // Whether the user ever sent it to the provider (true from the first submission on, never false again): the
        // provider's side and the provider's operator read only these (their datasets' scope), with every version.
        eb.field("sentToProvider", f -> f.physicalColumn("sent_to_provider").required(true).processOnly().asBool());
        eb.field("submittedAt", f -> f.physicalColumn("submitted_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("confirmedAt", f -> f.physicalColumn("confirmed_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("confirmedBy", f -> f.physicalColumn("confirmed_by").processOnly()
            .asText(AccountLimits.USER_ID_LENGTH));
        // The revision the provider confirmed; a later correction asks again.
        eb.field("confirmedRevision", f -> f.physicalColumn("confirmed_revision").processOnly().asNumeric(9, 0));
        eb.field("rejectedAt", f -> f.physicalColumn("rejected_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        // Kept until the next submission, for the user to correct by.
        eb.field("rejectReason", f -> f.physicalColumn("reject_reason").processOnly()
            .asText(REASON_LENGTH, true));
        eb.field("editedAt", f -> f.physicalColumn("edited_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("publicationId", "sponsorId", "providerId", "pricing", "price", "status", "editedAt")
            .filters("publicationId", "sharedVideoId", "sponsorId", "providerId", "status")
            .sorts("editedAt")
            .defaultSort("editedAt", false));
    });

    public static final EntityDefinition FIGURES_ENTITY = EntityDefinition.define(FIGURES, eb -> {
        eb.physicalTable("qb_video_use_figures");
        eb.primaryKey("figuresId");
        eb.field("figuresId", f -> f.physicalColumn("figures_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:video-use-figures"));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .processOnly().asText(ID_LENGTH));
        eb.field("sharedVideoId", f -> f.physicalColumn("shared_video_id").immutable(true).required(true)
            .processOnly().asText(ID_LENGTH));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("providerId", f -> f.physicalColumn("provider_id").immutable(true).required(true).processOnly()
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("opens", f -> f.physicalColumn("opens").required(true).processOnly().asNumeric(12, 0));
        eb.field("successes", f -> f.physicalColumn("successes").required(true).processOnly().asNumeric(12, 0));
        eb.field("goes", f -> f.physicalColumn("goes").required(true).processOnly().asNumeric(12, 0));
        eb.field("qualified", f -> f.physicalColumn("qualified").required(true).processOnly().asNumeric(12, 0));
        // What was counted is up to this time: the run's while the publication is on, its end once it ended.
        eb.field("cutoffAt", f -> f.physicalColumn("cutoff_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("refreshedAt", f -> f.physicalColumn("refreshed_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("finalisedAt", f -> f.physicalColumn("finalised_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("qb_video_use_figures_one", "publicationId");
        eb.listView("default", lv -> lv
            .columns("publicationId", "opens", "successes", "goes", "qualified", "refreshedAt", "finalisedAt")
            .filters("publicationId", "sponsorId", "providerId")
            .sorts("refreshedAt")
            .defaultSort("refreshedAt", false));
    });

    /**
     * Every version of a use with who wrote it, which act and when: a read-only view over the append-only versions and
     * the platform's operation records (written by nothing; the processes write {@link #USE_ENTITY}).
     */
    public static final EntityDefinition HISTORY_ENTITY = EntityDefinition.define(HISTORY, eb -> {
        eb.physicalTable("qb_video_use_history");
        eb.primaryKey("historyId");
        eb.field("historyId", f -> f.physicalColumn("history_id").immutable(true).required(true).asText(20));
        eb.field("videoUseId", f -> f.physicalColumn("video_use_id").immutable(true).required(true).asText(ID_LENGTH));
        eb.field("versionNo", f -> f.physicalColumn("version_no").immutable(true).required(true).asNumeric(9, 0));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asText(ID_LENGTH));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true)
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("providerId", f -> f.physicalColumn("provider_id").immutable(true).required(true)
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("status", f -> f.physicalColumn("status").immutable(true).required(true)
            .asCode(STATUS_DICTIONARY, VideoUseRules.STATUSES.toArray(String[]::new)));
        eb.field("pricing", f -> f.physicalColumn("pricing").immutable(true).required(true)
            .asCode(PRICING_DICTIONARY, VideoUseRules.PRICINGS.toArray(String[]::new)));
        eb.field("price", f -> f.physicalColumn("price").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("periodFrom", f -> f.physicalColumn("period_from").immutable(true).required(true).asDate());
        eb.field("periodTo", f -> f.physicalColumn("period_to").immutable(true).required(true).asDate());
        eb.field("rejectReason", f -> f.physicalColumn("reject_reason").immutable(true).asText(REASON_LENGTH, true));
        eb.field("revision", f -> f.physicalColumn("revision").immutable(true).required(true).asNumeric(9, 0));
        eb.field("sentToProvider", f -> f.physicalColumn("sent_to_provider").immutable(true).required(true)
            .asBool());
        eb.field("recordedAt", f -> f.physicalColumn("recorded_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("actorId", f -> f.physicalColumn("actor_id").immutable(true).required(true)
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("processName", f -> f.physicalColumn("process_name").immutable(true).required(true).asText(64));
        eb.listView("default", lv -> lv
            .columns("videoUseId", "versionNo", "status", "processName", "recordedAt")
            .filters("videoUseId", "sponsorId", "providerId")
            .sorts("versionNo")
            .defaultSort("versionNo", true));
    });

    private QbVideoUses() {}
}

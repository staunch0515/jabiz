package com.jabiz.quizbuks.sharedvideo;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.i18n.I18nText;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.identity.AccountLimits;
import com.jabiz.quizbuks.topic.TopicRules;

import java.util.List;

/**
 * The videos sponsors share with other sponsors (F-S12, docs/quizbuks/plans/impl/F-S12.md; Q112 §1): a video link of
 * the provider's asset library, copied when shared with its duration, topics (codes of the word list, F-S9), its title
 * in every language of the platform and whether it is vertical. What is shared never changes: to change it, the
 * provider withdraws it and shares again (a new row, reviewed anew). The provider's operator reviews each once (the
 * platform's approval, subject {@value SharedVideoApprovals#SUBJECT}); once approved, other sponsors pick it for their
 * publications (the card freezes it and its provider) and nobody reviews the video again. A withdrawal stops new picks
 * only; a takedown (the provider's operator, or headquarters) also ends every publication using it.
 *
 * <p>An ordinary table written by {@link SharedVideoProcesses} only ({@code processOnlyWrites}): every change goes
 * through the platform's write path and its audit. The provider is a sponsor's user id ({@code providerId}): the
 * operators' entry scope limits what an operator reads by it.
 */
public final class QbSharedVideos {

    public static final String VIDEO = "QbSharedVideo";
    public static final String DEFAULT_DATASET = "urn:jabiz:dataset:default:" + VIDEO;
    /** The provider's own shares, read only. */
    public static final String MY_DATASET = "urn:jabiz:dataset:me:" + VIDEO;
    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:shared-video-status";

    /** Shared, waiting for its operator's review. */
    public static final String PENDING = "PENDING";
    /** Reviewed: in the catalog, picked by other sponsors. */
    public static final String APPROVED = "APPROVED";
    /** The review sent it back (with the reason); the provider may share it again. */
    public static final String REJECTED = "REJECTED";
    /** The provider withdrew it: no new picks; the publications submitted with it keep it until they end. */
    public static final String WITHDRAWN = "WITHDRAWN";
    /** Illegal or infringing: taken down by its operator or headquarters; the publications using it end. */
    public static final String TAKEN_DOWN = "TAKEN_DOWN";
    public static final List<String> STATUSES = List.of(PENDING, APPROVED, REJECTED, WITHDRAWN, TAKEN_DOWN);
    /** The states of a share that still stands (one per asset at a time). */
    public static final List<String> STANDING = List.of(PENDING, APPROVED);

    public static final int TITLE_LENGTH = ContentLimits.MATERIAL_TITLE_LENGTH;
    public static final int REASON_LENGTH = 500;
    public static final int ID_LENGTH = 36;

    /** The lock of a provider's sharing: its shares and withdrawals one after another (after its library's). */
    public static String providerLockName(Object providerId) {
        return providerId == null ? null : "qb.shared-videos:" + providerId;
    }

    /** The lock of one shared video's changes after it was shared (a review's result, a withdrawal, a takedown). */
    public static String lockName(Object sharedVideoId) {
        return sharedVideoId == null ? null : "qb.shared-video:" + sharedVideoId;
    }

    public static final EntityDefinition ENTITY = EntityDefinition.define(VIDEO, eb -> {
        eb.physicalTable("qb_shared_video");
        eb.primaryKey("sharedVideoId");
        eb.field("sharedVideoId", f -> f.physicalColumn("shared_video_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:shared-video"));
        // The provider's asset it was shared from (the asset may change or go; what was shared stays).
        eb.field("assetId", f -> f.physicalColumn("asset_id").immutable(true).required(true).asText(ID_LENGTH));
        eb.field("providerId", f -> f.physicalColumn("provider_id").immutable(true).required(true)
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("url", f -> f.physicalColumn("url").immutable(true).required(true)
            .asText(ContentLimits.URL_LENGTH));
        eb.field("durationSec", f -> f.physicalColumn("duration_sec").immutable(true).required(true)
            .asNumeric(5, 0));
        // Vertical as the provider says (a YouTube Shorts link is one anyway, VideoEmbeds.shorts).
        eb.field("vertical", f -> f.physicalColumn("vertical").immutable(true).required(true).asBool());
        // The title in every language of the platform, all required.
        eb.field("title", f -> f.physicalColumn("title").immutable(true).required(true)
            .apply(I18nText.of(TITLE_LENGTH).required(QbContent.LANGUAGES.toArray(String[]::new))));
        // Codes of the word list (F-S9), comma-separated in the order chosen: what the catalog is searched by.
        eb.field("topics", f -> f.physicalColumn("topics").immutable(true).required(true)
            .asText(TopicRules.CODES_LENGTH));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").processOnly().asText(64));
        eb.field("sharedAt", f -> f.physicalColumn("shared_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("approvedAt", f -> f.physicalColumn("approved_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("rejectReason", f -> f.physicalColumn("reject_reason").processOnly().asText(REASON_LENGTH, true));
        eb.field("withdrawnAt", f -> f.physicalColumn("withdrawn_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("takenDownAt", f -> f.physicalColumn("taken_down_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("takedownReason", f -> f.physicalColumn("takedown_reason").processOnly()
            .asText(REASON_LENGTH, true));
        // The operator whose member took it down; none: headquarters (who exactly, the audit says).
        eb.field("takedownOperatorId", f -> f.physicalColumn("takedown_operator_id").processOnly().asText(ID_LENGTH));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        eb.field("editedAt", f -> f.physicalColumn("edited_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.display("title");
        eb.listView("default", lv -> lv
            .columns("title", "providerId", "status", "vertical", "durationSec", "sharedAt", "approvedAt",
                "takenDownAt")
            .filters("providerId", "status", "vertical", "assetId")
            .sorts("sharedAt", "approvedAt", "editedAt")
            .defaultSort("sharedAt", false));
    });

    private QbSharedVideos() {
    }
}

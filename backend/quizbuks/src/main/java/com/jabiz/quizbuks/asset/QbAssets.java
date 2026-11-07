package com.jabiz.quizbuks.asset;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import com.jabiz.entity.TemporalRole;
import com.jabiz.file.FileKind;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.identity.AccountLimits;
import com.jabiz.quizbuks.publishing.DigitalDelivery;
import com.jabiz.quizbuks.publishing.RewardMethod;

import java.util.List;

/**
 * A sponsor's asset library (F-C1, docs/quizbuks/plans/impl/F-C1.md): the media, texts and prize cards they keep to
 * copy into their quizzes and publications, and where each was copied to. Records of the sponsor's account, so keyed
 * by {@code sponsorId} (the user), not by a publisher (S0). Ordinary tables written by {@link AssetProcesses} only;
 * the files are referred to by fields of the content policies, so the platform's sweep keeps them while referred to.
 */
public final class QbAssets {

    public static final String ASSET = "QbAsset";
    /** Where an asset was copied to: one row per place (a place comes from one asset); written from F-C1b on. */
    public static final String USE = "QbAssetUse";

    public static final String DEFAULT_DATASET = "urn:jabiz:dataset:default:" + ASSET;
    public static final String MY_DATASET = "urn:jabiz:dataset:me:" + ASSET;
    public static final String USE_DATASET = "urn:jabiz:dataset:default:" + USE;
    public static final String MY_USE_DATASET = "urn:jabiz:dataset:me:" + USE;

    public static final String KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:asset-kind";
    public static final String USAGE_DICTIONARY = "urn:jabiz:dict:quizbuks:asset-text-usage";
    public static final String TARGET_DICTIONARY = "urn:jabiz:dict:quizbuks:asset-use-target";

    /** The places an asset is copied to (and the template of candidates takes content from). */
    public static final String MATERIAL = "MATERIAL";
    public static final String QUESTION_IMAGE = "QUESTION_IMAGE";
    public static final String OPTION_IMAGE = "OPTION_IMAGE";
    public static final String QUIZ_COVER = "QUIZ_COVER";
    public static final String QUIZ_INTRO = "QUIZ_INTRO";
    public static final String PUBLICATION_COVER = "PUBLICATION_COVER";
    public static final String PUBLICATION_REDEEM = "PUBLICATION_REDEEM";
    public static final String PUBLICATION_DIGITAL = "PUBLICATION_DIGITAL";
    public static final String PUBLICATION_REFERRAL = "PUBLICATION_REFERRAL";
    public static final String PUBLICATION_PRIZE = "PUBLICATION_PRIZE";
    public static final List<String> TARGETS = List.of(MATERIAL, QUESTION_IMAGE, OPTION_IMAGE, QUIZ_COVER, QUIZ_INTRO,
        PUBLICATION_COVER, PUBLICATION_REDEEM, PUBLICATION_DIGITAL, PUBLICATION_REFERRAL, PUBLICATION_PRIZE);

    /** The length of a stored tag list (JSON): {@code qb.assets.max-tags} tags of {@code qb.assets.tag-length}. */
    public static final int TAGS_LENGTH = 2_000;
    public static final int HASH_LENGTH = 64;

    /** The lock of a sponsor's library: every writer of their assets holds it, and counts them under it. */
    public static String lockName(Object sponsorId) {
        return sponsorId == null ? null : "qb.assets:" + sponsorId;
    }

    public static final EntityDefinition ASSET_ENTITY = EntityDefinition.define(ASSET, eb -> {
        eb.physicalTable("qb_asset");
        eb.primaryKey("assetId");
        eb.field("assetId", f -> f.physicalColumn("asset_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:asset"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true)
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(KIND_DICTIONARY, AssetKind.codes().toArray(String[]::new)));
        eb.field("name", f -> f.physicalColumn("name").required(true).asText(AssetRules.NAME_LENGTH)
            .apply(Rules.notBlank(AssetCodes.NAME_BLANK)));
        eb.field("description", f -> f.physicalColumn("description").asText(AssetRules.DESCRIPTION_LENGTH, true));
        // The language the texts are in; the others in translations ({language: {field: {text, hash}}}, Q73c).
        eb.field("language", f -> f.physicalColumn("language").required(true)
            .asCode(QbContent.LANGUAGE_DICTIONARY, QbContent.LANGUAGES.toArray(String[]::new)));
        eb.field("translations", f -> f.physicalColumn("translations").asText(null, true));
        // A JSON list of texts: the platform has no field of several values.
        eb.field("tags", f -> f.physicalColumn("tags").asText(TAGS_LENGTH));
        // One column per policy, so that the platform's sweep of unreferred files sees each (the spec's §B).
        eb.field("image", f -> f.physicalColumn("image").kind(FileKind.of(QbContent.IMAGE_POLICY)));
        eb.field("pdf", f -> f.physicalColumn("pdf").kind(FileKind.of(QbContent.PDF_POLICY)));
        eb.field("audio", f -> f.physicalColumn("audio").kind(FileKind.of(QbContent.AUDIO_POLICY)));
        eb.field("url", f -> f.physicalColumn("url").asText(AssetRules.URL_LENGTH)
            .apply(Rules.pattern(AssetCodes.URL_FORMAT, QbContent.URL_PATTERN)));
        eb.field("durationSec", f -> f.physicalColumn("duration_sec").asNumeric(5, 0));
        // Markdown for an article (and a quiz's introduction), plain text for the publications' descriptions.
        eb.field("body", f -> f.physicalColumn("body").asText(AssetRules.BODY_LENGTH, true));
        eb.field("usage", f -> f.physicalColumn("text_usage")
            .asCode(USAGE_DICTIONARY, AssetRules.USAGES.toArray(String[]::new)));
        eb.field("prizeMethod", f -> f.physicalColumn("prize_method")
            .asCode(RewardMethod.DICTIONARY, AssetRules.PRIZE_METHODS.toArray(String[]::new)));
        eb.field("prizeDescription", f -> f.physicalColumn("prize_description")
            .asText(AssetRules.PRIZE_DESCRIPTION_LENGTH, true));
        eb.field("digitalDelivery", f -> f.physicalColumn("digital_delivery")
            .asCode(DigitalDelivery.DICTIONARY, DigitalDelivery.ALL.toArray(String[]::new)));
        eb.field("digitalSendDays", f -> f.physicalColumn("digital_send_days").asNumeric(3, 0));
        // AssetRules.contentHash: what the library is deduplicated by (with the file's id for a file).
        eb.field("contentHash", f -> f.physicalColumn("content_hash").processOnly().asText(HASH_LENGTH));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("editedAt", f -> f.physicalColumn("edited_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.display("name");
        eb.listView("default", lv -> lv
            .columns("name", "kind", "sponsorId", "language", "usage", "revision", "editedAt")
            .filters("name", "kind", "sponsorId", "usage", "language")
            .sorts("name", "editedAt", "createdAt")
            .defaultSort("editedAt", false));
    });

    public static final EntityDefinition USE_ENTITY = EntityDefinition.define(USE, eb -> {
        eb.physicalTable("qb_asset_use");
        eb.primaryKey("useId");
        eb.field("useId", f -> f.physicalColumn("use_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:asset-use"));
        eb.field("assetId", f -> f.physicalColumn("asset_id").immutable(true).required(true).asText(36));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true)
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("targetKind", f -> f.physicalColumn("target_kind").immutable(true).required(true)
            .asCode(TARGET_DICTIONARY, TARGETS.toArray(String[]::new)));
        eb.field("targetId", f -> f.physicalColumn("target_id").immutable(true).required(true).asText(36));
        // The asset's revision when it was copied: a later one makes the place "older content".
        eb.field("assetRevision", f -> f.physicalColumn("asset_revision").required(true).asNumeric(9, 0));
        eb.field("usedAt", f -> f.physicalColumn("used_at").required(true).asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("assetId", "targetKind", "targetId", "assetRevision", "usedAt")
            .filters("assetId", "sponsorId", "targetKind", "targetId")
            .sorts("usedAt")
            .defaultSort("usedAt", false));
    });

    private QbAssets() {
    }
}

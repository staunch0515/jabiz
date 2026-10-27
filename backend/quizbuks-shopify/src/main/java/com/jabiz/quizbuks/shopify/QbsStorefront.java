package com.jabiz.quizbuks.shopify;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

import java.util.List;

/**
 * The entities of a shop's storefront quiz (docs/quizbuks/shopify/S2-storefront.md sections 2 to 4). Ordinary tables
 * written by the module's processes only; deleted with the shop's other rows on {@code shop/redact}.
 *
 * <ul>
 *   <li>{@value #CAMPAIGN}: a quiz campaign, a draft until published as the engine's publication of the shop; at most
 *       one runs (live or paused) per shop.</li>
 *   <li>{@value #TAKER}: a signed-in customer who answered; the engine knows them only as {@code qbs:<takerId>}
 *       ({@link #customerTaker}), and a visitor who is not signed in by a digest ({@link #visitorTaker}).</li>
 *   <li>{@value #BATCH}: a Shopify discount and the codes added to it.</li>
 *   <li>{@value #CODE}: a code of a batch, sealed, issued once to an attempt that passed; deleted with its batch's
 *       discount once the batch ended, so a shop holds about two weeks of codes.</li>
 * </ul>
 */
public final class QbsStorefront {

    private QbsStorefront() {}

    public static final String CAMPAIGN = "QbsCampaign";
    public static final String TAKER = "QbsTaker";
    public static final String BATCH = "QbsDiscountBatch";
    public static final String CODE = "QbsDiscountCode";

    public static final String CAMPAIGN_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:shopify-campaign-status";
    public static final String DRAFT = "DRAFT";
    public static final String LIVE = "LIVE";
    public static final String PAUSED = "PAUSED";
    public static final String ENDED = "ENDED";
    public static final List<String> CAMPAIGN_STATUSES = List.of(DRAFT, LIVE, PAUSED, ENDED);


    public static final String BATCH_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:shopify-discount-batch-status";
    public static final String CODE_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:shopify-discount-code-status";

    public static final String CREATING = "CREATING";
    public static final String READY = "READY";
    public static final String RETIRED = "RETIRED";
    public static final String DELETED = "DELETED";
    public static final List<String> BATCH_STATUSES = List.of(CREATING, READY, RETIRED, DELETED);

    public static final String AVAILABLE = "AVAILABLE";
    public static final String ISSUED = "ISSUED";
    public static final String FAILED = "FAILED";
    public static final List<String> CODE_STATUSES = List.of(AVAILABLE, ISSUED, FAILED);

    public static final int NAME_LENGTH = 100;
    public static final int VIDEO_LENGTH = 2000;
    public static final int PLAYER_LENGTH = 80;
    public static final int QUESTIONS_LENGTH = 64 * 1024;
    public static final int CUSTOMER_LENGTH = 40;
    public static final int TAKER_LENGTH = 128;
    public static final int GID_LENGTH = 255;
    public static final int HINT_LENGTH = 8;

    /** The engine's taker key of a signed-in customer: their row's key, never their id. */
    public static String customerTaker(Object takerId) {
        return "qbs:" + takerId;
    }

    /**
     * The engine's taker key of a visitor who is not signed in: a digest of the shop and the key their browser keeps
     * (random, made by the storefront block), never the key itself. Nothing to erase: it names nobody.
     */
    public static String visitorTaker(String shopDomain, String visitorKey) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest((shopDomain + "\n" + visitorKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "qbs:v:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final EntityDefinition CAMPAIGN_ENTITY = EntityDefinition.define(CAMPAIGN, eb -> {
        eb.physicalTable("qbs_campaign");
        eb.primaryKey("campaignId");
        eb.field("campaignId", f -> f.physicalColumn("campaign_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:shopify-campaign"));
        eb.field("shopId", f -> f.physicalColumn("shop_id").immutable(true).required(true)
            .asText(QbsShops.ID_LENGTH));
        eb.field("shopDomain", f -> f.physicalColumn("shop_domain").immutable(true).required(true)
            .asText(Shopify.SHOP_DOMAIN_LENGTH));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(CAMPAIGN_STATUS_DICTIONARY, CAMPAIGN_STATUSES.toArray(String[]::new)));
        eb.field("name", f -> f.physicalColumn("name").required(true).asText(NAME_LENGTH));
        eb.field("video", f -> f.physicalColumn("video").asText(VIDEO_LENGTH));
        eb.field("timeLimitSec", f -> f.physicalColumn("time_limit_sec").required(true).asNumeric(5, 0));
        eb.field("passScore", f -> f.physicalColumn("pass_score").required(true).asNumeric(3, 0));
        // The questions as the staff wrote them (CampaignProcesses.Question, JSON); frozen into the engine's version
        // when published.
        eb.field("questions", f -> f.physicalColumn("questions").required(true).asText(QUESTIONS_LENGTH, true));
        eb.field("percent", f -> f.physicalColumn("percent").required(true).asNumeric(2, 0));
        // The video's player, as the engine made it of the link when the campaign was published.
        eb.field("player", f -> f.physicalColumn("player").processOnly().asText(QbsStorefront.PLAYER_LENGTH));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").processOnly().asText(QbsShops.ID_LENGTH));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("editedAt", f -> f.physicalColumn("edited_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("publishedAt", f -> f.physicalColumn("published_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("endedAt", f -> f.physicalColumn("ended_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.display("name");
    });

    public static final EntityDefinition TAKER_ENTITY = EntityDefinition.define(TAKER, eb -> {
        eb.physicalTable("qbs_taker");
        eb.primaryKey("takerId");
        eb.field("takerId", f -> f.physicalColumn("taker_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:shopify-taker"));
        eb.field("shopDomain", f -> f.physicalColumn("shop_domain").immutable(true).required(true)
            .asText(Shopify.SHOP_DOMAIN_LENGTH));
        // Personal: never in the audit's values.
        eb.field("customerId", f -> f.physicalColumn("customer_id").immutable(true).required(true).auditMasked()
            .asText(CUSTOMER_LENGTH));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qbs_taker", "shopDomain", "customerId");
    });

    public static final EntityDefinition BATCH_ENTITY = EntityDefinition.define(BATCH, eb -> {
        eb.physicalTable("qbs_discount_batch");
        eb.primaryKey("batchId");
        eb.field("batchId", f -> f.physicalColumn("batch_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:shopify-discount-batch"));
        eb.field("shopDomain", f -> f.physicalColumn("shop_domain").immutable(true).required(true)
            .asText(Shopify.SHOP_DOMAIN_LENGTH));
        eb.field("campaignId", f -> f.physicalColumn("campaign_id").immutable(true).required(true)
            .asText(QbsShops.ID_LENGTH));
        eb.field("percent", f -> f.physicalColumn("percent").immutable(true).required(true).asNumeric(2, 0));
        eb.field("discountId", f -> f.physicalColumn("discount_id").immutable(true).required(true)
            .asText(GID_LENGTH));
        eb.field("bulkCreationId", f -> f.physicalColumn("bulk_creation_id").processOnly().asText(GID_LENGTH));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(BATCH_STATUS_DICTIONARY, BATCH_STATUSES.toArray(String[]::new)));
        eb.field("codeCount", f -> f.physicalColumn("code_count").required(true).processOnly().asNumeric(4, 0));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("endsAt", f -> f.physicalColumn("ends_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
    });

    public static final EntityDefinition CODE_ENTITY = EntityDefinition.define(CODE, eb -> {
        eb.physicalTable("qbs_discount_code");
        eb.primaryKey("codeId");
        eb.field("codeId", f -> f.physicalColumn("code_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:shopify-discount-code"));
        eb.field("batchId", f -> f.physicalColumn("batch_id").immutable(true).required(true)
            .asText(QbsShops.ID_LENGTH));
        eb.field("shopDomain", f -> f.physicalColumn("shop_domain").immutable(true).required(true)
            .asText(Shopify.SHOP_DOMAIN_LENGTH));
        eb.field("seq", f -> f.physicalColumn("seq").immutable(true).required(true).asNumeric(4, 0));
        eb.field("sealedCode", f -> f.physicalColumn("sealed_code").immutable(true).required(true).sensitive()
            .asText(QbsShops.SEALED_LENGTH));
        eb.field("hint", f -> f.physicalColumn("hint").immutable(true).required(true).asText(HINT_LENGTH));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(CODE_STATUS_DICTIONARY, CODE_STATUSES.toArray(String[]::new)));
        eb.field("campaignId", f -> f.physicalColumn("campaign_id").processOnly().asText(QbsShops.ID_LENGTH));
        // The engine's taker key of the attempt it was issued to ({@link #customerTaker}, {@link #visitorTaker}).
        eb.field("taker", f -> f.physicalColumn("taker").processOnly().asText(TAKER_LENGTH));
        eb.field("attemptId", f -> f.physicalColumn("attempt_id").processOnly().asText(QbsShops.ID_LENGTH));
        eb.field("issuedAt", f -> f.physicalColumn("issued_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qbs_discount_code_seq", "batchId", "seq");
    });
}

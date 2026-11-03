package com.jabiz.quizbuks.benefit;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import com.jabiz.entity.TemporalRole;
import com.jabiz.file.FileKind;
import com.jabiz.quizbuks.identity.AccountLimits;

import java.util.List;

/**
 * Sponsored benefits (Q89, docs/quizbuks/plans/Q89-sponsored-benefits.md): one sponsor buys another's goods, services
 * or digital products (a video's playback rights, D-Q89-2) for its takers. Q89a holds what happens between the two
 * sponsors before a publication uses a purchase:
 * <ul>
 *   <li>{@code QbBenefitOffer}: a benefit a provider lists (holding {@code BENEFIT_PROVIDER}), seen by every sponsor
 *       holding either benefit certification (D-Q89-3);</li>
 *   <li>{@code QbBenefitConversation} and {@code QbBenefitMessage}: one conversation between a sponsor and a benefit's
 *       provider, opened by the sponsor; contact and payment details may be exchanged (D-Q89-4);</li>
 *   <li>{@code QbBenefitPurchase}: what the sponsor says it bought and paid, confirmed by the provider (D-Q89-5).
 *       Temporal: every revision a provider confirmed stays readable.</li>
 * </ul>
 * Written by {@link OfferProcesses}, {@link BenefitConversationProcesses} and {@link PurchaseProcesses} only; no money
 * moves on the platform for any of them.
 */
public final class QbBenefits {

    public static final String OFFER = "QbBenefitOffer";
    public static final String CONVERSATION = "QbBenefitConversation";
    public static final String MESSAGE = "QbBenefitMessage";
    public static final String PURCHASE = "QbBenefitPurchase";

    /** The images of a benefit; the proofs of a purchase (both re-encoded or checked, names and uploaders private). */
    public static final String IMAGE_POLICY = "qb.benefit-image";
    public static final String PROOF_POLICY = "qb.benefit-proof";

    public static final String KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:benefit-kind";
    public static final String OFFER_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:benefit-offer-status";
    public static final String SIDE_DICTIONARY = "urn:jabiz:dict:quizbuks:benefit-side";
    public static final String PURCHASE_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:benefit-purchase-status";

    /** Goods and services are collected at the provider's; a digital product is delivered as a code or a link. */
    public static final String GOODS = "GOODS";
    public static final String SERVICE = "SERVICE";
    public static final String DIGITAL = "DIGITAL";
    public static final List<String> KINDS = List.of(GOODS, SERVICE, DIGITAL);

    /** Shown in the catalog; hidden by the provider; hidden by headquarters or the operator (Q89d). */
    public static final String LISTED = "LISTED";
    public static final String UNLISTED = "UNLISTED";
    public static final String TAKEN_DOWN = "TAKEN_DOWN";
    public static final List<String> OFFER_STATUSES = List.of(LISTED, UNLISTED, TAKEN_DOWN);

    /** The two sides of a conversation and a purchase. */
    public static final String SPONSOR = "SPONSOR";
    public static final String PROVIDER = "PROVIDER";
    public static final List<String> SIDES = List.of(SPONSOR, PROVIDER);

    public static final int NAME_LENGTH = 100;
    public static final int DESCRIPTION_LENGTH = 2000;
    public static final int ADDRESS_LENGTH = 500;
    public static final int HOURS_LENGTH = 200;
    public static final int TERMS_LENGTH = 1000;
    public static final int NOTE_LENGTH = 1000;
    public static final int REASON_LENGTH = 500;
    public static final int MAX_IMAGES = 4;
    public static final int MAX_PROOFS = 5;
    /** The most benefits one provider lists (shown or not). */
    public static final int MAX_OFFERS = 50;

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** A side's own rows, for its templates only. */
    public static String sideDataset(String side, String entity) {
        return "urn:jabiz:dataset:" + side.toLowerCase(java.util.Locale.ROOT) + ":" + entity;
    }

    /** The lock of a provider's benefits: its account's (the count and the edits take turns). */
    static String offerLock(Object providerId) {
        return "qb.benefit-offers:" + providerId;
    }

    /** One conversation's writes take turns; named by its benefit and sponsor, which never change. */
    static String conversationLock(Object offerId, Object sponsorId) {
        return "qb.benefit-conversation:" + offerId + ":" + sponsorId;
    }

    /** One purchase's writes take turns (Q89b's allocations take it too). */
    public static String purchaseLock(Object purchaseId) {
        return "qb.benefit-purchase:" + purchaseId;
    }

    static String[] imageFields() {
        return new String[] {"image1", "image2", "image3", "image4"};
    }

    static String[] proofFields() {
        return new String[] {"proof1", "proof2", "proof3", "proof4", "proof5"};
    }

    public static final EntityDefinition OFFER_ENTITY = EntityDefinition.define(OFFER, eb -> {
        eb.physicalTable("qb_benefit_offer");
        eb.primaryKey("offerId");
        eb.field("offerId", f -> f.physicalColumn("offer_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:benefit-offer"));
        eb.field("providerId", f -> f.physicalColumn("provider_id").immutable(true).required(true).processOnly()
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("kind", f -> f.physicalColumn("kind").required(true)
            .asCode(KIND_DICTIONARY, KINDS.toArray(String[]::new)));
        eb.field("name", f -> f.physicalColumn("name").required(true).asText(NAME_LENGTH)
            .apply(Rules.notBlank(BenefitCodes.NAME_BLANK)));
        eb.field("description", f -> f.physicalColumn("description").required(true)
            .asText(DESCRIPTION_LENGTH, true).apply(Rules.notBlank(BenefitCodes.DESCRIPTION_BLANK)));
        for (String image : imageFields()) {
            eb.field(image, f -> f.physicalColumn(column(image)).kind(FileKind.of(IMAGE_POLICY)));
        }
        // The market value of one, as the provider declares it (D-Q89-2): the system's currency, no decimals.
        eb.field("unitValue", f -> f.physicalColumn("unit_value").required(true).asMonetary("JPY", 0));
        eb.field("pickupAddress", f -> f.physicalColumn("pickup_address").asText(ADDRESS_LENGTH, true));
        eb.field("pickupHours", f -> f.physicalColumn("pickup_hours").asText(HOURS_LENGTH, true));
        eb.field("terms", f -> f.physicalColumn("terms").asText(TERMS_LENGTH, true));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(OFFER_STATUS_DICTIONARY, OFFER_STATUSES.toArray(String[]::new)));
        eb.field("editedAt", f -> f.physicalColumn("edited_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.display("name");
        eb.listView("default", lv -> lv
            .columns("name", "providerId", "kind", "unitValue", "status", "editedAt")
            .filters("name", "providerId", "kind", "status")
            .sorts("name", "editedAt")
            .defaultSort("editedAt", false));
    });

    public static final EntityDefinition CONVERSATION_ENTITY = EntityDefinition.define(CONVERSATION, eb -> {
        eb.physicalTable("qb_benefit_conversation");
        eb.primaryKey("conversationId");
        eb.field("conversationId", f -> f.physicalColumn("conversation_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:benefit-conversation"));
        eb.field("offerId", f -> f.physicalColumn("offer_id").immutable(true).required(true).processOnly()
            .asText(36));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("providerId", f -> f.physicalColumn("provider_id").immutable(true).required(true).processOnly()
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("openedAt", f -> f.physicalColumn("opened_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("lastMessageAt", f -> f.physicalColumn("last_message_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("lastSender", f -> f.physicalColumn("last_sender").required(true).processOnly()
            .asCode(SIDE_DICTIONARY, SIDES.toArray(String[]::new)));
        eb.field("sponsorReadAt", f -> f.physicalColumn("sponsor_read_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("providerReadAt", f -> f.physicalColumn("provider_read_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("offerId", "sponsorId", "providerId", "openedAt", "lastMessageAt", "lastSender")
            .filters("offerId", "sponsorId", "providerId", "lastMessageAt")
            .sorts("lastMessageAt", "openedAt")
            .defaultSort("lastMessageAt", false));
    });

    public static final EntityDefinition MESSAGE_ENTITY = EntityDefinition.define(MESSAGE, eb -> {
        eb.physicalTable("qb_benefit_message");
        eb.primaryKey("messageId");
        eb.field("messageId", f -> f.physicalColumn("message_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:benefit-message"));
        eb.field("conversationId", f -> f.physicalColumn("conversation_id").immutable(true).required(true)
            .processOnly().asText(36));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("providerId", f -> f.physicalColumn("provider_id").immutable(true).required(true).processOnly()
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("sender", f -> f.physicalColumn("sender").immutable(true).required(true).processOnly()
            .asCode(SIDE_DICTIONARY, SIDES.toArray(String[]::new)));
        eb.field("senderUserId", f -> f.physicalColumn("sender_user_id").immutable(true).required(true)
            .processOnly().asText(AccountLimits.USER_ID_LENGTH));
        // Emptied when the sender's account is erased; otherwise written once.
        eb.field("body", f -> f.physicalColumn("body").processOnly().asText(BenefitRules.BODY_LENGTH, true));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("conversationId", "sender", "senderUserId", "body", "createdAt")
            .filters("conversationId", "sponsorId", "providerId", "createdAt")
            .sorts("createdAt")
            .defaultSort("createdAt", true));
    });

    public static final EntityDefinition PURCHASE_ENTITY = EntityDefinition.define(PURCHASE, eb -> {
        eb.physicalTable("qb_benefit_purchase_version");
        eb.primaryKey("purchaseId");
        eb.field("purchaseId", f -> f.physicalColumn("purchase_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:benefit-purchase"));
        eb.field("offerId", f -> f.physicalColumn("offer_id").immutable(true).required(true).processOnly()
            .asText(36));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("providerId", f -> f.physicalColumn("provider_id").immutable(true).required(true).processOnly()
            .asText(AccountLimits.USER_ID_LENGTH));
        // The benefit as it was when the sponsor last saved the purchase: what the provider confirms (D-Q89-5).
        eb.field("kind", f -> f.physicalColumn("kind").required(true).processOnly()
            .asCode(KIND_DICTIONARY, KINDS.toArray(String[]::new)));
        eb.field("title", f -> f.physicalColumn("title").required(true).processOnly().asText(NAME_LENGTH));
        eb.field("unitValue", f -> f.physicalColumn("unit_value").required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("pickupAddress", f -> f.physicalColumn("pickup_address").processOnly()
            .asText(ADDRESS_LENGTH, true));
        eb.field("pickupHours", f -> f.physicalColumn("pickup_hours").processOnly().asText(HOURS_LENGTH, true));
        eb.field("terms", f -> f.physicalColumn("terms").processOnly().asText(TERMS_LENGTH, true));
        // What the sponsor says: how many, what it paid and when, and the proofs.
        eb.field("quantity", f -> f.physicalColumn("quantity").required(true).processOnly().asNumeric(9, 0));
        eb.field("paidAmount", f -> f.physicalColumn("paid_amount").required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("paidOn", f -> f.physicalColumn("paid_on").required(true).processOnly().asDate());
        for (String proof : proofFields()) {
            eb.field(proof, f -> f.physicalColumn(column(proof)).processOnly().kind(FileKind.of(PROOF_POLICY)));
        }
        eb.field("note", f -> f.physicalColumn("note").processOnly().asText(NOTE_LENGTH, true));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(PURCHASE_STATUS_DICTIONARY, PurchaseStatus.ALL.toArray(String[]::new)));
        eb.field("submittedAt", f -> f.physicalColumn("submitted_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("confirmedAt", f -> f.physicalColumn("confirmed_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("confirmedBy", f -> f.physicalColumn("confirmed_by").processOnly()
            .asText(AccountLimits.USER_ID_LENGTH));
        // The revision the provider confirmed; a later change asks again (D-Q89-5).
        eb.field("confirmedRevision", f -> f.physicalColumn("confirmed_revision").processOnly().asNumeric(9, 0));
        eb.field("declinedAt", f -> f.physicalColumn("declined_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("declineReason", f -> f.physicalColumn("decline_reason").processOnly()
            .asText(REASON_LENGTH, true));
        eb.field("cancelledAt", f -> f.physicalColumn("cancelled_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("editedAt", f -> f.physicalColumn("edited_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("title", "sponsorId", "providerId", "quantity", "paidAmount", "status", "editedAt")
            .filters("offerId", "sponsorId", "providerId", "status")
            .sorts("editedAt")
            .defaultSort("editedAt", false));
    });

    /** {@code image1} → {@code image_1}. */
    private static String column(String field) {
        return field.replaceAll("(\\d)$", "_$1");
    }

    private QbBenefits() {}
}

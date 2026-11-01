package com.jabiz.quizbuks.referral;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.RewardMethod;

import java.util.List;

/**
 * Referral advertising (Q70, docs/quizbuks/plans/Q70-referrals-and-rounds.md): a referrer's link to a publication that
 * welcomes referrals, and a referral (one per referee and publication), from its claim to its reward, expiry or void.
 * Ordinary tables, written by the {@code QB_REFERRAL_*} processes only, under the lock {@link #lockName}.
 */
public final class QbReferrals {

    public static final String LINK = "QbReferralLink";
    public static final String REFERRAL = "QbReferral";

    public static final String REFERRED = "REFERRED";
    public static final String REWARDED = "REWARDED";
    public static final String EXPIRED = "EXPIRED";
    public static final String VOID = "VOID";
    public static final List<String> STATUSES = List.of(REFERRED, REWARDED, EXPIRED, VOID);
    /** The statuses that hold a place of the quota (D-Q70-15). */
    public static final List<String> COUNTED = List.of(REFERRED, REWARDED);

    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:referral-status";

    /**
     * The scopes of the templates (Q70b-2), which pick the columns: a referee's own referrals, a referrer's own
     * referrals and links, a sponsor's referrals. Read by no role through the datasets API.
     */
    public static final String REFEREE_DATASET = "urn:jabiz:dataset:referee:" + REFERRAL;
    public static final String REFERRER_DATASET = "urn:jabiz:dataset:referrer:" + REFERRAL;
    public static final String REFERRER_LINK_DATASET = "urn:jabiz:dataset:referrer:" + LINK;
    public static final String SPONSOR_DATASET = "urn:jabiz:dataset:sponsor:" + REFERRAL;

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** A publication's referrals are claimed, rewarded and expired one process at a time. */
    public static String lockName(Object publicationId) {
        return "qb.referral:" + publicationId;
    }

    /** A referrer's link to a publication (D-Q70-14): a random key, the same one each time it is asked for. */
    public static final EntityDefinition LINK_ENTITY = EntityDefinition.define(LINK, eb -> {
        eb.physicalTable("qb_referral_link");
        eb.primaryKey("linkId");
        eb.field("linkId", f -> f.physicalColumn("link_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:referral-link"));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true).asText(36));
        // Emptied when the referrer's account is erased (D-Q70-22): the link stays, so that its referrals count.
        eb.field("referrerId", f -> f.physicalColumn("referrer_id").asText(128));
        // Random (not the user's id); the key is in the link, not a secret, so no "token" in its name.
        eb.field("linkKey", f -> f.physicalColumn("link_key").immutable(true).required(true).asText(64));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_referral_link_key", "linkKey");
        eb.unique("uk_qb_referral_link_referrer", "publicationId", "referrerId");
        eb.listView("default", lv -> lv
            .columns("publicationId", "referrerId", "createdAt")
            .filters("publicationId", "referrerId")
            .sorts("createdAt")
            .defaultSort("createdAt", false));
    });

    /**
     * A referral (D-Q70-15 – D-Q70-18): who referred whom to which publication, until when the referee has to pass,
     * and what came of it; the method and amount of its reward copied from the card when claimed.
     */
    public static final EntityDefinition REFERRAL_ENTITY = EntityDefinition.define(REFERRAL, eb -> {
        eb.physicalTable("qb_referral");
        eb.primaryKey("referralId");
        eb.field("referralId", f -> f.physicalColumn("referral_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:referral"));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true).asText(36));
        PublisherKind.declare(eb, true);
        eb.field("referrerId", f -> f.physicalColumn("referrer_id").asText(128));
        eb.field("refereeId", f -> f.physicalColumn("referee_id").asText(128));
        eb.field("linkId", f -> f.physicalColumn("link_id").immutable(true).required(true).asText(36));
        eb.field("status", f -> f.physicalColumn("status").required(true)
            .asCode(STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        eb.field("referredAt", f -> f.physicalColumn("referred_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("expiresAt", f -> f.physicalColumn("expires_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("rewardMethod", f -> f.physicalColumn("reward_method").immutable(true).required(true)
            .asCode(RewardMethod.DICTIONARY, RewardMethod.ALL.toArray(String[]::new)));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("attemptId", f -> f.physicalColumn("attempt_id").asText(36));
        eb.field("rewardedAt", f -> f.physicalColumn("rewarded_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("rewardRecordId", f -> f.physicalColumn("reward_record_id").asText(36));
        eb.field("ledgerTransactionId", f -> f.physicalColumn("ledger_transaction_id").asText(36));
        eb.field("fundsShort", f -> f.physicalColumn("funds_short").required(true).asBool());
        eb.field("endedAt", f -> f.physicalColumn("ended_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("voidReason", f -> f.physicalColumn("void_reason").asText(40));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_referral_referee", "publicationId", "refereeId");
        eb.listView("default", lv -> lv
            .columns("publicationId", "referrerId", "refereeId", "status", "referredAt", "expiresAt", "amount",
                "rewardMethod", "rewardedAt", "fundsShort", "voidReason")
            .filters("publicationId", "publisherId", "referrerId", "refereeId", "status")
            .sorts("referredAt")
            .defaultSort("referredAt", false));
    });

    private QbReferrals() {}
}

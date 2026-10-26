package com.jabiz.quizbuks.reward;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.quizbuks.publishing.DigitalDelivery;
import com.jabiz.quizbuks.publishing.PublishingLimits;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.publishing.PublisherKind;

import static com.jabiz.quizbuks.reward.DigitalPrizeStatus.CONFIRMED;
import static com.jabiz.quizbuks.reward.DigitalPrizeStatus.DELIVERED;
import static com.jabiz.quizbuks.reward.DigitalPrizeStatus.DISPUTED;
import static com.jabiz.quizbuks.reward.DigitalPrizeStatus.DUE;
import static com.jabiz.quizbuks.reward.DigitalPrizeStatus.OVERDUE;
import static com.jabiz.quizbuks.reward.DigitalPrizeStatus.VOID;

/**
 * Digital prizes (Q33, docs/quizbuks/plans/Q33-digital-prize.md): a code, a voucher number or a link the winner
 * gets without going anywhere. The prize record ({@link #DIGITAL_PRIZE}, temporal) is made when an attempt wins
 * ({@code play.AttemptFinishing}), one per attempt, delivered at once from the publication's pool or shared content
 * or owed by the sponsor who sends it by hand. The pool ({@link #DIGITAL_CODE}, an ordinary table) holds the codes
 * a sponsor loaded for a publication, one row each, issued in the order loaded. The content is a secret everywhere:
 * sealed ({@code SealedSecrets}, under the sponsor's id), sensitive in both entities (never read back through the
 * datasets, masked in the audit), opened only for the winner by {@code QB_DIGITAL_PRIZE_OPEN}; the sponsor sees a
 * hint (the last characters) and counts.
 */
public final class QbDigitalPrizes {

    public static final String DIGITAL_PRIZE = "QbDigitalPrize";
    public static final String DIGITAL_CODE = "QbDigitalCode";

    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:digital-prize-status";
    public static final String RESOLUTION_DICTIONARY = "urn:jabiz:dict:quizbuks:digital-prize-resolution";
    public static final String CODE_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:digital-code-status";
    /** The messages naming a prize's state in the reader's language: {@code qb.digital-prize.status.<state>}. */
    public static final String STATUS_MESSAGE_PREFIX = "qb.digital-prize.status.";

    /** How many characters of a code the sponsor and the record show: enough to tell codes apart, not to use one. */
    public static final int HINT_LENGTH = 4;

    public static final String DISPUTE_TASK = "qb.digital-prize.dispute";
    public static final String DISPUTE_TASK_TITLE = "task.qb.digital-prize.dispute";
    public static final String SEND_TASK = "qb.digital-prize.send";
    public static final String SEND_TASK_TITLE = "task.qb.digital-prize.send";

    public static String defaultDataset(String entity) {
        return QbRewards.defaultDataset(entity);
    }

    public static String sponsorDataset(String entity) {
        return QbRewards.sponsorDataset(entity);
    }

    public static String takerDataset(String entity) {
        return QbRewards.takerDataset(entity);
    }

    /** The lock of a prize: taken by every process that writes it, before it is read. */
    public static String prizeLockName(Object digitalPrizeId) {
        return digitalPrizeId == null ? null : "qb.digital:" + digitalPrizeId;
    }

    /** The lock of a publication's pool: loading, revoking and issuing (at settlement) take it, one at a time. */
    public static String poolLockName(Object publicationId) {
        return publicationId == null ? null : "qb.digital-pool:" + publicationId;
    }

    public static String disputeTaskSource(Object digitalPrizeId) {
        return "qb.digital-dispute:" + digitalPrizeId;
    }

    public static String sendTaskSource(Object digitalPrizeId) {
        return "qb.digital-send:" + digitalPrizeId;
    }

    /** The last characters of a code, or nothing of a short one: what the sponsor's lists show. */
    public static String hintOf(String content) {
        if (content == null) {
            return null;
        }
        String text = content.strip();
        return text.length() <= HINT_LENGTH + 2 ? null : "…" + text.substring(text.length() - HINT_LENGTH);
    }

    public static final EntityDefinition PRIZE_ENTITY = EntityDefinition.define(DIGITAL_PRIZE, eb -> {
        eb.physicalTable("qb_digital_prize_version");
        eb.primaryKey("digitalPrizeId");
        eb.field("digitalPrizeId", f -> f.physicalColumn("digital_prize_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:digital-prize"));
        eb.field("attemptId", f -> f.physicalColumn("attempt_id").immutable(true).required(true).asText(36));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(QbPublications.PUBLICATION));
        PublisherKind.declare(eb, false);
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(128));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asMonetary("JPY", 0));
        // What the prize is, as the card said when the attempt finished; how it is delivered.
        eb.field("description", f -> f.physicalColumn("description").immutable(true)
            .asText(PublishingLimits.REDEEM_DESCRIPTION_LENGTH, true));
        eb.field("delivery", f -> f.physicalColumn("delivery").immutable(true).required(true)
            .asCode(DigitalDelivery.DICTIONARY, DigitalDelivery.ALL.toArray(String[]::new)));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, DigitalPrizeStatus.ALL.toArray(String[]::new)));
        eb.field("dueAt", f -> f.physicalColumn("due_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        // When the sponsor must have sent it by (a prize they send by hand).
        eb.field("sendBy", f -> f.physicalColumn("send_by").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("remindedAt", f -> f.physicalColumn("reminded_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("deliveredAt", f -> f.physicalColumn("delivered_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("deliveredBy", f -> f.physicalColumn("delivered_by").processOnly().asText(10));
        // The content sealed under the sponsor's id: opened for the winner only.
        eb.field("sealedContent", f -> f.physicalColumn("sealed_content").sensitive()
            .asText(PublishingLimits.SEALED_LENGTH));
        eb.field("hint", f -> f.physicalColumn("hint").processOnly().asText(HINT_LENGTH + 1));
        eb.field("openedAt", f -> f.physicalColumn("opened_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        // When the taker must have confirmed or disputed a prize the sponsor sent by hand.
        eb.field("confirmBy", f -> f.physicalColumn("confirm_by").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("confirmedAt", f -> f.physicalColumn("confirmed_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("disputedAt", f -> f.physicalColumn("disputed_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("disputeNote", f -> f.physicalColumn("dispute_note").processOnly()
            .asText(PublishingLimits.REJECT_REASON_LENGTH, true));
        eb.field("overdueAt", f -> f.physicalColumn("overdue_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("resolvedAt", f -> f.physicalColumn("resolved_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("resolvedBy", f -> f.physicalColumn("resolved_by").processOnly().asText(128));
        eb.field("resolution", f -> f.physicalColumn("resolution").processOnly()
            .asCode(RESOLUTION_DICTIONARY, DigitalPrizeStatus.RESOLUTIONS.toArray(String[]::new)));
        eb.field("resolveNote", f -> f.physicalColumn("resolve_note").processOnly()
            .asText(PublishingLimits.REJECT_REASON_LENGTH, true));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        // Delivered at once by the system, or owed by the sponsor; confirmed and void are final. A dispute may be
        // found undelivered (owed again) or delivered (confirmed).
        eb.stateTransitions("status", st -> st
            .initial(DUE, DELIVERED)
            .from(DUE).to(DELIVERED, DISPUTED, OVERDUE, VOID)
            .from(DELIVERED).to(CONFIRMED, DISPUTED, VOID)
            .from(OVERDUE).to(DELIVERED, DISPUTED, VOID)
            .from(DISPUTED).to(CONFIRMED, DUE, VOID));
        eb.unique("uk_qb_digital_prize_attempt", "attemptId");
        eb.display("attemptId");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("attemptId", "publicationId", "publisherKind", "publisherId", "userId", "amount", "delivery",
                "status", "dueAt", "deliveredAt", "confirmBy", "confirmedAt", "disputedAt")
            .filters("publicationId", "publisherKind", "publisherId", "userId", "delivery", "status", "dueAt",
                "deliveredAt", "sendBy", "confirmBy")
            .sorts("dueAt", "deliveredAt", "amount", "status")
            .defaultSort("dueAt", false));
    });

    public static final EntityDefinition CODE_ENTITY = EntityDefinition.define(DIGITAL_CODE, eb -> {
        eb.physicalTable("qb_digital_code");
        eb.primaryKey("codeId");
        eb.field("codeId", f -> f.physicalColumn("code_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:digital-code"));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(QbPublications.PUBLICATION));
        PublisherKind.declare(eb, false);
        // The order loaded: issued from the lowest.
        eb.field("seq", f -> f.physicalColumn("seq").immutable(true).required(true).asNumeric(9, 0));
        eb.field("sealedContent", f -> f.physicalColumn("sealed_content").immutable(true).required(true)
            .sensitive().asText(PublishingLimits.SEALED_LENGTH));
        eb.field("hint", f -> f.physicalColumn("hint").immutable(true).asText(HINT_LENGTH + 1));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(CODE_STATUS_DICTIONARY, DigitalPrizeStatus.CODE_STATUSES.toArray(String[]::new)));
        eb.field("loadedAt", f -> f.physicalColumn("loaded_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("issuedAttemptId", f -> f.physicalColumn("issued_attempt_id").processOnly().asText(36));
        eb.field("issuedAt", f -> f.physicalColumn("issued_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("revokedAt", f -> f.physicalColumn("revoked_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.stateTransitions("status", st -> st
            .initial(DigitalPrizeStatus.CODE_AVAILABLE)
            .from(DigitalPrizeStatus.CODE_AVAILABLE).to(DigitalPrizeStatus.CODE_ISSUED,
                DigitalPrizeStatus.CODE_REVOKED));
        eb.unique("uk_qb_digital_code_seq", "publicationId", "seq");
        eb.display("hint");
        eb.listView("default", lv -> lv
            .columns("publicationId", "publisherKind", "publisherId", "seq", "hint", "status", "loadedAt", "issuedAt",
                "revokedAt")
            .filters("publicationId", "publisherKind", "publisherId", "status", "loadedAt")
            .sorts("seq", "loadedAt")
            .defaultSort("seq", true));
    });

    private QbDigitalPrizes() {}
}

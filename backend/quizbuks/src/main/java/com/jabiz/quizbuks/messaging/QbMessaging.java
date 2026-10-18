package com.jabiz.quizbuks.messaging;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

/**
 * The data of messaging (Q7a, docs/quizbuks/plans/Q7-messages-legal.md D-Q7-2): {@value #NOTICE}, when a user was last
 * mailed a notice of a kind that may come often, so that they get at most one in
 * {@link NotificationMails#NOTICE_INTERVAL}: a sponsor's short of funds ({@code FUNDS_SHORT}, one mail naming all
 * their publications short; one that comes within the window marks the row {@code pending}, and the hourly job sends
 * it once the window has passed) and a taker's payout account held back ({@code PAYOUT_ACTION_NEEDED}). An ordinary
 * table, written only by the mail processes under the recipient's {@link #noticeLock}; read by no role.
 */
public final class QbMessaging {

    public static final String NOTICE = "QbMailNotice";

    /** The default dataset of an entity of messaging. */
    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /**
     * The lock of a user's throttled notices: a family of its own, taken only by the mail processes; never with a
     * publication's, account or wallet lock, and no answering or payment process takes it.
     */
    public static String noticeLock(Object recipientId) {
        return recipientId == null ? null : "qb.mail-notice:" + recipientId;
    }

    public static final EntityDefinition NOTICE_ENTITY = EntityDefinition.define(NOTICE, eb -> {
        eb.physicalTable("qb_mail_notice");
        eb.primaryKey("noticeId");
        eb.field("noticeId", f -> f.physicalColumn("notice_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:mail-notice"));
        eb.field("recipientId", f -> f.physicalColumn("recipient_id").immutable(true).required(true)
            .processOnly().asText(128));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true).processOnly().asText(30));
        eb.field("lastSentAt", f -> f.physicalColumn("last_sent_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("pending", f -> f.physicalColumn("pending").required(true).processOnly().asBool());
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_mail_notice", "recipientId", "kind");
        eb.listView("default", lv -> lv
            .columns("recipientId", "kind", "lastSentAt", "pending")
            .filters("recipientId", "kind", "pending")
            .sorts("lastSentAt")
            .defaultSort("lastSentAt", false));
    });

    private QbMessaging() {}
}

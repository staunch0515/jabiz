package com.jabiz.quizbuks.messaging;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

/**
 * The data of messaging (Q7a, docs/quizbuks/plans/Q7-messages-legal.md D-Q7-2): {@value #NOTICE}, when a sponsor was
 * last mailed a notice of a kind that may come often ({@code FUNDS_SHORT}: one mail names all their publications short
 * of funds), so that they get at most one in {@link NotificationMails#FUNDS_SHORT_INTERVAL}. An ordinary table,
 * written only by {@code QB_PUBLICATION_NOTIFY} under the lock {@code qb.sponsor-notice:<sponsorId>}; read by no role.
 */
public final class QbMessaging {

    public static final String NOTICE = "QbSponsorNotice";

    /** The default dataset of an entity of messaging. */
    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /**
     * The lock of a sponsor's notices: a family of its own, never a publication's lock (the consumer takes no other)
     * nor the sponsor's account or wallet lock; no answering or payment process takes it.
     */
    public static String noticeLock(Object sponsorId) {
        return sponsorId == null ? null : "qb.sponsor-notice:" + sponsorId;
    }

    public static final EntityDefinition NOTICE_ENTITY = EntityDefinition.define(NOTICE, eb -> {
        eb.physicalTable("qb_sponsor_notice");
        eb.primaryKey("noticeId");
        eb.field("noticeId", f -> f.physicalColumn("notice_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:sponsor-notice"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true)
            .processOnly().asText(128));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true).processOnly().asText(20));
        eb.field("lastSentAt", f -> f.physicalColumn("last_sent_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_sponsor_notice", "sponsorId", "kind");
        eb.listView("default", lv -> lv
            .columns("sponsorId", "kind", "lastSentAt")
            .filters("sponsorId", "kind")
            .sorts("lastSentAt")
            .defaultSort("lastSentAt", false));
    });

    private QbMessaging() {}
}

package com.jabiz.quizbuks.messaging;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

/**
 * The data of messaging (Q7a, docs/quizbuks/plans/Q7-messages-legal.md D-Q7-2): {@value #NOTICE}, when a publication's
 * sponsor was last mailed a notice of a kind that may come often ({@code FUNDS_SHORT}), so that they get at most one in
 * {@link NotificationMails#FUNDS_SHORT_INTERVAL}. An ordinary table, written only by {@code QB_PUBLICATION_NOTIFY}
 * under the lock {@code qb.pub-notice:<publicationId>}; read by no role.
 */
public final class QbMessaging {

    public static final String NOTICE = "QbPublicationNotice";

    /** The default dataset of an entity of messaging. */
    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** The lock of a publication's notices: apart from the publication's own, which the consumer never takes. */
    public static String noticeLock(Object publicationId) {
        return publicationId == null ? null : "qb.pub-notice:" + publicationId;
    }

    public static final EntityDefinition NOTICE_ENTITY = EntityDefinition.define(NOTICE, eb -> {
        eb.physicalTable("qb_publication_notice");
        eb.primaryKey("noticeId");
        eb.field("noticeId", f -> f.physicalColumn("notice_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:publication-notice"));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .processOnly().asText(36));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true).processOnly().asText(20));
        eb.field("lastSentAt", f -> f.physicalColumn("last_sent_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_publication_notice", "publicationId", "kind");
        eb.listView("default", lv -> lv
            .columns("publicationId", "kind", "lastSentAt")
            .filters("publicationId", "kind")
            .sorts("lastSentAt")
            .defaultSort("lastSentAt", false));
    });

    private QbMessaging() {}
}

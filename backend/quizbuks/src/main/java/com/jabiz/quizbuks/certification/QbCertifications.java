package com.jabiz.quizbuks.certification;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

/**
 * The certifications' records (Q69, docs/quizbuks/plans/Q69-certifications.md): the rounds (which publication counts
 * for which certification), the people's records (passes, grants and revocations, only ever inserted) and the
 * official publishers (the sponsor accounts whose publications may be rounds). Ordinary tables written by the
 * {@code QB_CERTIFICATION_*} and {@code QB_OFFICIAL_PUBLISHER_SET} processes only.
 */
public final class QbCertifications {

    public static final String ROUND = "QbCertificationRound";
    public static final String RECORD = "QbCertificationRecord";
    public static final String OFFICIAL = "QbOfficialPublisher";
    public static final String RECORD_KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:certification-record-kind";
    /** The owner of what headquarters does itself (the direct sponsors' operator, D-Q48-1). */
    public static final String DIRECT = "direct";

    /**
     * The caller's own records (Q70a): only the scope of the App's templates (qb.app.feed, qb.app.publication), which
     * pick the columns; read by headquarters' permission like the default dataset, never by a taker's.
     */
    public static final String MY_RECORD_DATASET = "urn:jabiz:dataset:me:" + RECORD;

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /** One person's certifications are written one process at a time. */
    public static String lockName(Object userId) {
        return "qb.certification:" + userId;
    }

    /**
     * A round: one publication of an official publisher counting for a certification's version (D-Q69-2). Closed, it
     * counts only the passes submitted before it closed (answers leak; the next round is a new publication).
     */
    public static final EntityDefinition ROUND_ENTITY = EntityDefinition.define(ROUND, eb -> {
        eb.physicalTable("qb_certification_round");
        eb.primaryKey("roundId");
        eb.field("roundId", f -> f.physicalColumn("round_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:certification-round"));
        eb.field("certification", f -> f.physicalColumn("certification").immutable(true).required(true)
            .asCode(Certifications.DICTIONARY, Certifications.keys()));
        eb.field("certificationVersion", f -> f.physicalColumn("certification_version").immutable(true)
            .required(true).asNumeric(4, 0));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true).asText(36));
        eb.field("owner", f -> f.physicalColumn("owner").immutable(true).required(true).asText(128));
        eb.field("addedBy", f -> f.physicalColumn("added_by").immutable(true).required(true).asText(128));
        eb.field("addedAt", f -> f.physicalColumn("added_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("closedAt", f -> f.physicalColumn("closed_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_certification_round_publication", "publicationId");
        eb.listView("default", lv -> lv
            .columns("certification", "certificationVersion", "publicationId", "owner", "addedAt", "closedAt")
            .filters("certification", "publicationId", "owner", "closedAt")
            .sorts("addedAt")
            .defaultSort("addedAt", false));
    });

    /**
     * A person's record of a certification (D-Q69-3, D-Q69-4): a pass (the attempt, once), a grant or a revocation by
     * an operator or headquarters with its reason. Only inserted; {@link CertificationHolding} reads them.
     */
    public static final EntityDefinition RECORD_ENTITY = EntityDefinition.define(RECORD, eb -> {
        eb.physicalTable("qb_certification_record");
        eb.primaryKey("recordId");
        eb.field("recordId", f -> f.physicalColumn("record_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:certification-record"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(128));
        eb.field("certification", f -> f.physicalColumn("certification").immutable(true).required(true)
            .asCode(Certifications.DICTIONARY, Certifications.keys()));
        eb.field("certificationVersion", f -> f.physicalColumn("certification_version").immutable(true)
            .required(true).asNumeric(4, 0));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(RECORD_KIND_DICTIONARY, CertificationHolding.PASSED, CertificationHolding.GRANTED,
                CertificationHolding.REVOKED));
        eb.field("attemptId", f -> f.physicalColumn("attempt_id").immutable(true).asText(36));
        eb.field("roundId", f -> f.physicalColumn("round_id").immutable(true).asText(36));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).asText(500, true));
        eb.field("actor", f -> f.physicalColumn("actor").immutable(true).required(true).asText(128));
        eb.field("owner", f -> f.physicalColumn("owner").immutable(true).required(true).asText(128));
        eb.field("at", f -> f.physicalColumn("at").immutable(true).required(true).asTemporal(TemporalRole.EVENT_TIME));
        eb.field("validUntil", f -> f.physicalColumn("valid_until").immutable(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_certification_record_attempt", "attemptId");
        eb.listView("default", lv -> lv
            .columns("userId", "certification", "kind", "owner", "at", "validUntil")
            .filters("userId", "certification", "kind", "owner", "at")
            .sorts("at")
            .defaultSort("at", false));
    });

    /** A sponsor account an operator (or headquarters) publishes its certification quizzes with (D-Q69-5). */
    public static final EntityDefinition OFFICIAL_ENTITY = EntityDefinition.define(OFFICIAL, eb -> {
        eb.physicalTable("qb_official_publisher");
        eb.primaryKey("officialId");
        eb.field("officialId", f -> f.physicalColumn("official_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:official-publisher"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(128));
        eb.field("owner", f -> f.physicalColumn("owner").required(true).asText(128));
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        eb.field("setBy", f -> f.physicalColumn("set_by").required(true).asText(128));
        eb.field("setAt", f -> f.physicalColumn("set_at").required(true).asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_official_publisher_user", "userId");
        eb.listView("default", lv -> lv
            .columns("userId", "owner", "active", "setBy", "setAt")
            .filters("userId", "owner", "active")
            .sorts("setAt")
            .defaultSort("setAt", false));
    });

    private QbCertifications() {}
}

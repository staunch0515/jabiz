package com.jabiz.quizbuks.certification;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

/**
 * A certificate (Q77, docs/quizbuks/plans/Q77-certificates.md): the document a holder issues of one holding of a
 * certification, once per holding (its end), verified by anybody who has its key. An ordinary table written only by
 * {@link CertificateProcesses#ISSUE}; the PDF is the platform's document run whose subject is the row.
 */
public final class QbCertificates {

    public static final String CERTIFICATE = "QbCertificate";

    public static final String DATASET = "urn:jabiz:dataset:default:" + CERTIFICATE;
    public static final String MY_DATASET = "urn:jabiz:dataset:me:" + CERTIFICATE;
    /** The columns a verifier needs, by the certificate's key (no user's id leaves a template). */
    public static final String PUBLIC_DATASET = "urn:jabiz:dataset:public:" + CERTIFICATE;
    /** Who revoked which certification when, for the verify page's state (the records themselves stay private). */
    public static final String PUBLIC_RECORD_DATASET = "urn:jabiz:dataset:public:" + QbCertifications.RECORD;

    public static final String LAYOUT_ID = "qb.certificate";
    public static final String LAYOUT_TEMPLATE = "qb.me.certificate";
    public static final String ISSUER_TEMPLATE = "qb.me.certificate-issuer";

    /** The verify key's random bytes: 16 (128 bits), written as 32 hex digits. */
    public static final int KEY_BYTES = 16;

    public static final EntityDefinition ENTITY = EntityDefinition.define(CERTIFICATE, eb -> {
        eb.physicalTable("qb_certificate");
        eb.primaryKey("certificateId");
        eb.field("certificateId", f -> f.physicalColumn("certificate_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:certificate"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(128));
        eb.field("certification", f -> f.physicalColumn("certification").immutable(true).required(true)
            .asText(40));
        eb.field("certificationVersion", f -> f.physicalColumn("certification_version").immutable(true)
            .required(true).asNumeric(4, 0));
        eb.field("heldFrom", f -> f.physicalColumn("held_from").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("validUntil", f -> f.physicalColumn("valid_until").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        // The holder's display name when issued, as the PDF shows it; it does not follow later renames.
        eb.field("holderName", f -> f.physicalColumn("holder_name").immutable(true).required(true).asText(100));
        eb.field("verifyKey", f -> f.physicalColumn("verify_key").immutable(true).required(true).asText(32));
        eb.field("language", f -> f.physicalColumn("language").immutable(true).required(true).asText(10));
        eb.field("issuedAt", f -> f.physicalColumn("issued_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_certificate_holding", "userId", "certification", "validUntil");
        eb.unique("uk_qb_certificate_key", "verifyKey");
        eb.listView("default", lv -> lv
            .columns("userId", "certification", "heldFrom", "validUntil", "issuedAt")
            .filters("userId", "certification", "verifyKey")
            .sorts("issuedAt")
            .defaultSort("issuedAt", false));
    });

    private QbCertificates() {}
}

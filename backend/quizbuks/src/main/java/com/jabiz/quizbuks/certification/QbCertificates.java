package com.jabiz.quizbuks.certification;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.i18n.I18nText;
import com.jabiz.quizbuks.tree.QbTrees;

/**
 * A certificate, verified by anybody who has its key. Of one of two sources: Q77's (docs/quizbuks/plans/Q77-certificates.md,
 * no longer issued: the holder's issue of one holding of a certification, once per holding, with its end) and (F-S5a,
 * docs/quizbuks/plans/impl/F-S5.md) a node's, written by {@link NodeCertificates#SETTLE} as the node is lit, with no end.
 * An ordinary table: the holder changes {@code shared} ({@link CertificateProcesses#SHARE}) and {@code runId} (the PDF,
 * {@link CertificateProcesses#ISSUE}), nothing else; the PDF is the platform's document run whose subject is the row.
 */
public final class QbCertificates {

    public static final String CERTIFICATE = "QbCertificate";

    public static final String DATASET = "urn:jabiz:dataset:default:" + CERTIFICATE;
    public static final String MY_DATASET = "urn:jabiz:dataset:me:" + CERTIFICATE;
    /** The columns a verifier needs, by the certificate's key (no user's id leaves a template). */
    public static final String PUBLIC_DATASET = "urn:jabiz:dataset:public:" + CERTIFICATE;
    /** Who revoked which certification when, for the verify page's state (the records themselves stay private). */
    public static final String PUBLIC_RECORD_DATASET = "urn:jabiz:dataset:public:" + QbCertifications.RECORD;

    /** F-S5a: the Sponsor's own certificates (the caller as a Sponsor), read only. */
    public static final String MY_SPONSOR_DATASET = "urn:jabiz:dataset:me-sponsor:" + CERTIFICATE;
    /** F-S5a: the certificates of the maps an operator looks after, through the template qb.operator.certificates. */
    public static final String OPERATOR_DATASET = "urn:jabiz:dataset:operator:" + CERTIFICATE;
    /** Who revoked which lit record when, for the verify page's state (no reason, no actor, no subject). */
    public static final String PUBLIC_REVOCATION_DATASET = "urn:jabiz:dataset:public:QbTreeLitRevocation";

    /** F-S5a: a node's certificate, issued by the lighting (no end). */
    public static final String NODE = "NODE";
    /** Q77's: a certification held, with its end. */
    public static final String CERTIFICATION = "CERTIFICATION";
    public static final String USER = "USER";
    public static final String SPONSOR = "SPONSOR";

    /** F-S5a: a node's certificate as a document, the Taker's and the Sponsor's own. */
    public static final String NODE_LAYOUT_ID = "qb.node_certificate";
    public static final String NODE_SPONSOR_LAYOUT_ID = "qb.node_certificate_sponsor";
    public static final String NODE_TEMPLATE = "qb.me.node-certificate";
    public static final String NODE_SPONSOR_TEMPLATE = "qb.sponsor.node-certificate";

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
        // Q77's certificates name the certification held and end (validUntil); a node's (F-S5a) name neither.
        eb.field("certification", f -> f.physicalColumn("certification").immutable(true).asText(40));
        eb.field("certificationVersion", f -> f.physicalColumn("certification_version").immutable(true)
            .asNumeric(4, 0));
        eb.field("heldFrom", f -> f.physicalColumn("held_from").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("validUntil", f -> f.physicalColumn("valid_until").immutable(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        // The holder's display name when issued, as the PDF shows it; it does not follow later renames.
        eb.field("holderName", f -> f.physicalColumn("holder_name").immutable(true).required(true).auditMasked()
            .asText(100));
        eb.field("verifyKey", f -> f.physicalColumn("verify_key").immutable(true).required(true).asText(32));
        // Q77's certificates have the language they were issued in; a node's has the PDF's, chosen at its download.
        eb.field("language", f -> f.physicalColumn("language").immutable(true).asText(10));
        eb.field("issuedAt", f -> f.physicalColumn("issued_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        // F-S5a: where the certificate comes from, and (a node's) who holds it as what, the lit record, the graph and node,
        // the names by language, the issuer and whether its verify page shows; all frozen as lit but whether it shows.
        eb.field("source", f -> f.physicalColumn("source").immutable(true).required(true).asText(16));
        eb.field("subjectKind", f -> f.physicalColumn("subject_kind").immutable(true).required(true).asText(10));
        eb.field("litId", f -> f.physicalColumn("lit_id").immutable(true).asText(36));
        eb.field("treeId", f -> f.physicalColumn("tree_id").immutable(true).asText(36));
        eb.field("nodeId", f -> f.physicalColumn("node_id").immutable(true).asText(36));
        eb.field("certificateNames", f -> f.physicalColumn("certificate_names").immutable(true)
            .apply(I18nText.of(QbTrees.NAME_LENGTH)));
        eb.field("issuerName", f -> f.physicalColumn("issuer_name").immutable(true).asText(200));
        // The one thing the holder changes: whether the verify page shows the certificate (the key is not listed anywhere).
        eb.field("shared", f -> f.physicalColumn("shared").required(true).asBool());
        // The PDF's document run, set when the holder issues it by their first download.
        eb.field("runId", f -> f.physicalColumn("run_id").asText(36));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_certificate_holding", "userId", "certification", "validUntil");
        eb.unique("uk_qb_certificate_lit", "litId");
        eb.unique("uk_qb_certificate_key", "verifyKey");
        eb.listView("default", lv -> lv
            .columns("userId", "source", "certification", "heldFrom", "validUntil", "issuedAt")
            .filters("userId", "source", "subjectKind", "certification", "verifyKey", "litId", "treeId")
            .sorts("issuedAt")
            .defaultSort("issuedAt", false));
    });

    private QbCertificates() {}
}

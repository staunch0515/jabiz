package com.jabiz.quizbuks.certification;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

/**
 * The certifications' records (Q69): passes, grants and revocations, only ever inserted. F-S2c2b (D1) removed the
 * certifications, their rounds and every process that wrote them; the knowledge map's nodes replace them. The records
 * stay, read only, because the certificates issued before (D8) read their revocations from here.
 */
public final class QbCertifications {

    public static final String RECORD = "QbCertificationRecord";
    public static final String RECORD_KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:certification-record-kind";

    public static final String PASSED = "PASSED";
    public static final String GRANTED = "GRANTED";
    public static final String REVOKED = "REVOKED";

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    /**
     * A person's record of a certification (D-Q69-3, D-Q69-4): a pass (the attempt, once), a grant or a revocation by
     * an operator or headquarters with its reason. Nothing writes one any more (F-S2c2b, D1); a revocation after a
     * certificate's holding began still ends it on the verify page and the Taker's page.
     */
    public static final EntityDefinition RECORD_ENTITY = EntityDefinition.define(RECORD, eb -> {
        eb.physicalTable("qb_certification_record");
        eb.primaryKey("recordId");
        eb.field("recordId", f -> f.physicalColumn("record_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:certification-record"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(128));
        eb.field("certification", f -> f.physicalColumn("certification").immutable(true).required(true)
            .asText(40));
        eb.field("certificationVersion", f -> f.physicalColumn("certification_version").immutable(true)
            .required(true).asNumeric(4, 0));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(RECORD_KIND_DICTIONARY, PASSED, GRANTED, REVOKED));
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


    private QbCertifications() {}
}

package com.jabiz.quizbuks.certification;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.i18n.I18nText;

/**
 * A sponsor's own certifications (Q88a, docs/quizbuks/plans/Q88-sponsor-certifications-entry-fee.md, D-Q88-1): what
 * passing one of the sponsor's publications is used for, defined by the sponsor (a name in the languages they
 * choose, a validity, a version they raise when the rules change, retired when no longer given). The rounds, records,
 * prerequisites and certificates refer to one by the key {@code sp:<certificationId>}
 * ({@link SponsorCertifications#keyOf}, D-Q88-2). F-S2c2 (D6): nothing defines, changes or retires one any more;
 * the rows defined before are read only, for the certificates issued of them (D8), the holdings and their pages.
 */
public final class QbSponsorCertifications {

    public static final String CERTIFICATION = "QbSponsorCertification";
    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:sponsor-certification-status";

    public static final String ACTIVE = "ACTIVE";
    public static final String RETIRED = "RETIRED";

    /** Headquarters' and the operators' (through their scope) read; nobody writes. */
    public static final String DEFAULT_DATASET = "urn:jabiz:dataset:default:" + CERTIFICATION;
    /** What the public pages show of one (its name and whose it is), through the public templates only. */
    public static final String PUBLIC_DATASET = "urn:jabiz:dataset:public:" + CERTIFICATION;

    public static final EntityDefinition ENTITY = EntityDefinition.define(CERTIFICATION, eb -> {
        eb.physicalTable("qb_sponsor_certification");
        eb.primaryKey("certificationId");
        eb.field("certificationId", f -> f.physicalColumn("certification_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:sponsor-certification"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        eb.field("name", f -> f.physicalColumn("name").required(true)
            .apply(I18nText.of(SponsorCertifications.NAME_LENGTH)));
        eb.field("description", f -> f.physicalColumn("description")
            .apply(I18nText.of(SponsorCertifications.DESCRIPTION_LENGTH).multiline()));
        eb.field("validityDays", f -> f.physicalColumn("validity_days").required(true).asNumeric(4, 0));
        // The certification's own version (raised by the sponsor, D-Q88-1), not the row's.
        eb.field("certificationVersion", f -> f.physicalColumn("certification_version").required(true)
            .asNumeric(4, 0));
        eb.field("status", f -> f.physicalColumn("status").required(true).asCode(STATUS_DICTIONARY, ACTIVE, RETIRED));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("updatedAt", f -> f.physicalColumn("updated_at").required(true).asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.display("name");
        eb.listView("default", lv -> lv
            .columns("sponsorId", "name", "validityDays", "certificationVersion", "status", "createdAt", "updatedAt")
            .filters("sponsorId", "status", "createdAt")
            .sorts("createdAt", "updatedAt")
            .defaultSort("createdAt", false));
    });

    private QbSponsorCertifications() {}
}

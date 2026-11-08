package com.jabiz.quizbuks.certification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The App's templates write the catalog of certifications (Q70a, D-Q70-1): its keys, versions and validity, the same as
 * {@link Certifications}, so that a round is hidden from its holders by the rule {@link CertificationHolding} applies.
 */
class CertificationTemplatesTest {

    @Test
    void theAppsTemplatesWriteTheCatalog() {
        // Q88a (D-Q88-2): the sponsors' own certifications follow the catalog, under their keys sp:<id>.
        assertThat(Certifications.sqlValues())
            .isEqualTo("(SELECT * FROM (VALUES ('SPONSOR_APPLY', 1, 31536000), ('REFERRER', 1, 31536000), "
                + "('TERMS_APP_UNDERSTOOD', 1, 31536000), ('CONTEST_RULES_UNDERSTOOD', 1, 31536000), "
                + "('TERMS_SPONSOR_UNDERSTOOD', 1, 31536000), ('BENEFIT_PROVIDER', 1, 31536000), "
                + "('BENEFIT_SPONSOR', 1, 31536000)) AS v(cert, ver, secs) UNION ALL SELECT 'sp:' || "
                + "sc.{{QbSponsorCertification.certificationId}}, sc.{{QbSponsorCertification.certificationVersion}}, "
                + "sc.{{QbSponsorCertification.validityDays}} * 86400 FROM {{QbSponsorCertification}} sc)");
        // F-S2c2 (D4, D5): no template tells any more whether somebody holds a certification (the prerequisites and
        // the Q85 tree are gone); the catalog itself goes with D1.
    }
}

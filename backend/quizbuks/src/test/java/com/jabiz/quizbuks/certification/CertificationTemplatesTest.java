package com.jabiz.quizbuks.certification;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The App's templates write the catalog of certifications (Q70a, D-Q70-1): its keys, versions and validity, the same as
 * {@link Certifications}, so that a round is hidden from its holders by the rule {@link CertificationHolding} applies.
 */
class CertificationTemplatesTest {

    @Test
    void theAppsTemplatesWriteTheCatalog() throws IOException {
        // Q88a (D-Q88-2): the sponsors' own certifications follow the catalog, under their keys sp:<id>.
        assertThat(Certifications.sqlValues())
            .isEqualTo("(SELECT * FROM (VALUES ('SPONSOR_APPLY', 1, 31536000), ('REFERRER', 1, 31536000), "
                + "('TERMS_APP_UNDERSTOOD', 1, 31536000), ('CONTEST_RULES_UNDERSTOOD', 1, 31536000), "
                + "('TERMS_SPONSOR_UNDERSTOOD', 1, 31536000), ('BENEFIT_PROVIDER', 1, 31536000), "
                + "('BENEFIT_SPONSOR', 1, 31536000)) AS v(cert, ver, secs) UNION ALL SELECT 'sp:' || "
                + "sc.{{QbSponsorCertification.certificationId}}, sc.{{QbSponsorCertification.certificationVersion}}, "
                + "sc.{{QbSponsorCertification.validityDays}} * 86400 FROM {{QbSponsorCertification}} sc)");
        // Q86: every template that tells whether somebody holds a certification writes the catalog.
        for (String template : new String[] {"queries/qb/app/feed.sql", "queries/qb/app/publication.sql",
"queries/qb/app/sponsor-tree.sql", "queries/qb/system/audience-count.sql",
            "queries/qb/sponsor/tree.sql", "queries/qb/sponsor/tree-people.sql", "queries/qb/sponsor/tree-person.sql"}) {
            // F-S2c: the benefit roles (qb.sponsor.benefit-access, -catalog) are unlocked by a lit node, not held.
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(template)) {
                assertThat(in).as(template).isNotNull();
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).as(template)
                    .contains(Certifications.sqlValues());
            }
        }
    }
}

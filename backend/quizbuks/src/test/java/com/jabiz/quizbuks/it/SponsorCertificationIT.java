package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.certification.CertificateProcesses;
import com.jabiz.quizbuks.certification.SponsorCertifications;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S2c2 (D6) and F-S2c2b (D1): the Sponsors' own certifications, their rounds and holding them are gone; nobody
 * defines one, makes a publication a round of one or holds one any more. A certificate issued before of a Sponsor's
 * certification stays as it was (D8): the holder's list and the verify page name the certification and its Sponsor.
 * A pass records nothing, and no tier may pay nothing unless the publication is bound to a node.
 */
class SponsorCertificationIT extends LegalItSupport {

    /** A certification as a Sponsor defined it before D6 (the processes that wrote it are gone). */
    private String definedBefore(String sponsorId, String name) {
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(clock.instant());
        execute("INSERT INTO qb_sponsor_certification (certification_id, sponsor_id, name, description, validity_days, "
            + "certification_version, status, created_at, updated_at, version) VALUES (?, ?, CAST(? AS jsonb), NULL, "
            + "365, 1, 'ACTIVE', ?, ?, 1)", id, sponsorId, "{\"en\": \"" + name + "\"}", now, now);
        return id;
    }

    @Test
    @SuppressWarnings("unchecked")
    void whatWasIssuedStaysAndNothingIsHeldAnyMore() {
        String sponsor = newSponsor(unique("fs2c2-sponsor"));
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String certificationId = definedBefore(sponsor, "Keyboard expert");
        String key = SponsorCertifications.keyOf(certificationId);
        String company = (String) latest("qb_sponsor_version", "company", "user_id = '" + sponsor + "'").getFirst()
            .get("company");

        // Issued before (D8): the holder's list and the verification page name it and the Sponsor as its issuer.
        String holder = newTaker(unique("fs2c2-holder"), "JP");
        String verifyKey = issuedBefore(holder, key, "Taker JP", "en", Map.of("certificationName", "Keyboard expert",
            "issuerName", company));
        List<Map<String, Object>> mine = (List<Map<String, Object>>) run(CertificateProcesses.MINE, taker(holder),
            body()).get("held");
        assertThat(mine).singleElement().satisfies(h -> {
            assertThat(h).containsEntry("certification", key).containsEntry("sponsorName", company)
                .containsEntry("verifyKey", verifyKey);
            assertThat((Map<String, Object>) h.get("name")).containsEntry("en", "Keyboard expert");
        });
        assertThat(publicItems("qb.public.certificate", "p.key=" + verifyKey)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("certification", key).containsEntry("issuerName", company);
            assertThat(String.valueOf(row.get("certificationNames"))).contains("Keyboard expert");
        });

        // A pass records nothing (no rounds, no records), and the card names no certification.
        String round = livePublication(sponsor, "FS2c2 keyboard basics", Map.of("regions", List.of("GLOBAL")));
        String passer = newTaker(unique("fs2c2-passer"), "JP");
        assertThat(items("qb.app.publication", taker(passer), Map.of("params", Map.of("publicationId", round, "at",
            clock.instant().toString()))).getFirst()).doesNotContainKey("certification");
        assertThat(play(passer, round, true, true)).containsEntry("passed", true);
        assertThat(query("SELECT count(*) AS n FROM qb_certification_record WHERE user_id = ?", passer).getFirst()
            .get("n")).isEqualTo(0L);
        assertThat(run(CertificateProcesses.MINE, taker(passer), body()).get("held")).isEqualTo(List.of());

        // A draft paying nothing cannot be submitted (only a node's may, F-S2b1).
        String versionId = version(token, "FS2c2 free quiz");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "FS2c2 free", now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        save.put("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0)));
        String draft = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", draft), 422)))
            .contains(PublishingCodes.TIER_NO_REWARD);
        run("QB_PUBLICATION_DELETE", token, body("publicationId", draft));
        assertBooksBalance();
    }
}

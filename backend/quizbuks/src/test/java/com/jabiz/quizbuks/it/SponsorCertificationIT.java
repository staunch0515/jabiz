package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.certification.CertificateProcesses;
import com.jabiz.quizbuks.certification.CertificationHolding;
import com.jabiz.quizbuks.certification.CertificationProcesses;
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
 * F-S2c2 (D6): the sponsors' own certifications and rounds (Q88a) are gone; nobody defines one or makes a publication
 * a round of one any more. What was defined, held and issued before stays as it was (D8: issued certificates stay
 * valid): the holder's list and the public pages name the certification and its sponsor. The rounds registered
 * before count no more: a pass after records nothing, the card is no round, a draft that was one is deleted with
 * it, and no tier may pay nothing unless the publication is bound to a node.
 */
class SponsorCertificationIT extends LegalItSupport {

    /** A certification as a sponsor defined it before D6 (the processes that wrote it are gone). */
    private String definedBefore(String sponsorId, String name) {
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(clock.instant());
        execute("INSERT INTO qb_sponsor_certification (certification_id, sponsor_id, name, description, validity_days, "
            + "certification_version, status, created_at, updated_at, version) VALUES (?, ?, CAST(? AS jsonb), NULL, "
            + "365, 1, 'ACTIVE', ?, ?, 1)", id, sponsorId, "{\"en\": \"" + name + "\"}", now, now);
        return id;
    }

    /** The round a publication was made before D6, open. */
    private String roundBefore(String publicationId, String key) {
        String id = UUID.randomUUID().toString();
        execute("INSERT INTO qb_certification_round (round_id, certification, certification_version, publication_id, "
            + "owner, added_by, added_at, closed_at, version) VALUES (?, ?, 1, ?, 'hq', 'hq', ?, NULL, 1)", id, key,
            publicationId, Timestamp.from(clock.instant()));
        return id;
    }

    /** A pass recorded before D6, holding the certification for its validity from now. */
    private void heldBefore(String userId, String key, String roundId) {
        Instant now = clock.instant();
        execute("INSERT INTO qb_certification_record (record_id, user_id, certification, certification_version, kind, "
            + "attempt_id, round_id, reason, actor, owner, at, valid_until, version) VALUES (?, ?, ?, 1, ?, ?, ?, NULL, "
            + "?, 'hq', ?, ?, 1)", UUID.randomUUID().toString(), userId, key, CertificationHolding.PASSED,
            UUID.randomUUID().toString(), roundId, userId, Timestamp.from(now),
            Timestamp.from(now.plus(Duration.ofDays(365))));
    }

    private Map<String, Object> claim(String takerId, String key) {
        return run(CertificationProcesses.CLAIM, taker(takerId), body("certification", key));
    }

    @Test
    @SuppressWarnings("unchecked")
    void whatWasHeldAndIssuedStaysAndTheOldRoundsCountNoMore() {
        String sponsor = newSponsor(unique("fs2c2-sponsor"));
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String certificationId = definedBefore(sponsor, "Keyboard expert");
        String key = SponsorCertifications.keyOf(certificationId);
        String round = livePublication(sponsor, "FS2c2 keyboard basics", Map.of("regions", List.of("GLOBAL")));
        String roundId = roundBefore(round, key);

        // Held before: the holder's list still names the certification and its sponsor.
        String holder = newTaker(unique("fs2c2-holder"), "JP");
        heldBefore(holder, key, roundId);
        String company = (String) latest("qb_sponsor_version", "company", "user_id = '" + sponsor + "'").getFirst()
            .get("company");
        List<Map<String, Object>> mine = (List<Map<String, Object>>) run(CertificateProcesses.MINE, taker(holder),
            body()).get("held");
        assertThat(mine).singleElement().satisfies(h -> {
            assertThat(h).containsEntry("certification", key).containsEntry("sponsorName", company);
            assertThat((Map<String, Object>) h.get("name")).containsEntry("en", "Keyboard expert");
        });
        assertThat(claim(holder, key).get("heldUntil")).isNotNull();

        // Issued before (D8): the verification page names it and the sponsor as its issuer.
        String verifyKey = issuedBefore(holder, key, "Taker JP", "en", Map.of("certificationName", "Keyboard expert",
            "issuerName", company));
        assertThat(publicItems("qb.public.certificate", "p.key=" + verifyKey)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("certification", key).containsEntry("issuerName", company);
            assertThat(String.valueOf(row.get("certificationNames"))).contains("Keyboard expert");
        });

        // The old round counts no more: the card is no round, and a pass now records nothing.
        String passer = newTaker(unique("fs2c2-passer"), "JP");
        assertThat(items("qb.app.publication", taker(passer), Map.of("params", Map.of("publicationId", round, "at",
            clock.instant().toString()))).getFirst()).containsEntry("certification", null);
        assertThat(play(passer, round, true, true)).containsEntry("passed", true);
        assertThat(claim(passer, key)).containsEntry("heldUntil", null);
        assertThat(query("SELECT count(*) AS n FROM qb_certification_record WHERE user_id = ?", passer).getFirst()
            .get("n")).isEqualTo(0L);

        // A draft paying nothing cannot be submitted (only a node's may, F-S2b1); one that was a round before takes
        // its round with it when deleted.
        String versionId = version(token, "FS2c2 free quiz");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "FS2c2 free", now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        save.put("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0)));
        String draft = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        roundBefore(draft, key);
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", draft), 422)))
            .contains(PublishingCodes.TIER_NO_REWARD);
        run("QB_PUBLICATION_DELETE", token, body("publicationId", draft));
        assertThat(query("SELECT count(*) AS n FROM qb_certification_round WHERE publication_id = ?", draft)
            .getFirst().get("n")).isEqualTo(0L);
        assertBooksBalance();
    }
}

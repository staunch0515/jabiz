package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.certification.CertificateProcesses;
import com.jabiz.quizbuks.certification.CertificationCodes;
import com.jabiz.quizbuks.certification.CertificationProcesses;
import com.jabiz.quizbuks.certification.SponsorCertificationProcesses;
import com.jabiz.quizbuks.certification.SponsorCertifications;
import com.jabiz.quizbuks.publishing.PublicationRounds;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A sponsor's own certifications (Q88a, docs/quizbuks/plans/Q88-sponsor-certifications-entry-fee.md): defined behind
 * the switch, a draft made a round of one (which may then pay nothing), passing it holds the certification (key
 * {@code sp:<id>} in the same tables as the catalog's), which gates the sponsor's other publications, is listed,
 * certified with the sponsor as the issuer and shown on the public pages; rounds close, versions rise, certifications
 * retire; another sponsor's is not found.
 */
class SponsorCertificationIT extends LegalItSupport {

    private static boolean switched;

    private void switchOn() {
        if (!switched) {
            Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
                "values", Map.of("paramKey", QbParams.SPONSOR_ROUNDS_ENABLED, "value", "true"), "reason", "Q88a"));
            run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
            switched = true;
        }
    }

    private String hq() {
        return TestTokens.bearer(tokens, "q88-hq", QbPermissions.CERTIFICATION_ADMIN,
            QbPermissions.CERTIFICATION_MANAGE, QbPermissions.ADMIN_PUBLICATION_READ);
    }

    private Map<String, Object> define(String sponsorId, String name) {
        return run(SponsorCertificationProcesses.CREATE, sponsorToken(sponsorId), body("name", Map.of("en", name,
            "ja", name + " JA"), "description", Map.of("en", "Knows the " + name), "validityDays", 365));
    }

    /** A draft whose only tier pays nothing: a certification is what passing it gives (D-Q88-5). */
    private String freeDraft(String sponsorId, String name) {
        String token = sponsorToken(sponsorId);
        String versionId = version(token, name + " quiz");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, name, now, now.plus(Duration.ofDays(7)));
        save.put("maxWinners", 1000);
        save.put("regions", List.of("GLOBAL"));
        save.put("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0)));
        return (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
    }

    private Map<String, Object> setRound(String sponsorId, String publicationId, Object certificationId) {
        return run(PublicationRounds.SET, sponsorToken(sponsorId), body("publicationId", publicationId,
            "certificationId", certificationId));
    }

    private Map<String, Object> claim(String takerId, String key) {
        return run(CertificationProcesses.CLAIM, taker(takerId), body("certification", key));
    }

    private List<Map<String, Object>> round(String sponsorId, String publicationId) {
        return items("qb.sponsor.publication-round", sponsorToken(sponsorId), Map.of("params",
            Map.of("publicationId", publicationId)));
    }

    private Map<String, Object> card(String takerId, String publicationId) {
        return items("qb.app.publication", taker(takerId), Map.of("params", Map.of("publicationId", publicationId,
            "at", clock.instant().toString()))).getFirst();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSponsorsPublicationGivesTheSponsorsOwnCertification() throws Exception {
        String sponsor = newSponsor("q88-sponsor");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        // Off by default (D-Q88-7): nothing can be defined.
        assertThat(codes(refused(SponsorCertificationProcesses.CREATE, token, body("name", Map.of("en", "Early")),
            422))).containsExactly(CertificationCodes.SPONSOR_OFF);
        switchOn();

        // What a certification must be.
        assertThat(codes(refused(SponsorCertificationProcesses.CREATE, token, body("name", Map.of("en", " "),
            "validityDays", 10), 422))).containsExactlyInAnyOrder(CertificationCodes.SPONSOR_NAME_REQUIRED,
            CertificationCodes.SPONSOR_VALIDITY_RANGE);
        Map<String, Object> defined = define(sponsor, "Keyboard expert");
        String certificationId = (String) defined.get("certificationId");
        String key = (String) defined.get("key");
        assertThat(key).isEqualTo(SponsorCertifications.keyOf(certificationId));
        assertThat(defined).containsEntry("status", "ACTIVE").containsEntry("certificationVersion", 1)
            .containsEntry("validityDays", 365);
        assertThat((Map<String, Object>) defined.get("name")).containsEntry("en", "Keyboard expert");

        // A draft paying nothing cannot be submitted until it is a round (D-Q88-5); the save says so.
        String first = freeDraft(sponsor, "Q88 keyboard basics");
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", first), 422)))
            .contains(PublishingCodes.TIER_NO_REWARD);
        assertThat(setRound(sponsor, first, certificationId)).containsEntry("status", "DRAFT");
        assertThat(round(sponsor, first)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("certification", key).containsEntry("certificationId", certificationId)
                .containsEntry("closedAt", null);
            assertThat(String.valueOf(row.get("name"))).contains("Keyboard expert");
        });
        assertThat(run("QB_PUBLICATION_SUBMIT", token, body("publicationId", first)).get("status")).isIn("LIVE",
            "SCHEDULED");
        // Submitted: the round can only be closed (D-Q88-3).
        assertThat(codes(refused(PublicationRounds.SET, token, body("publicationId", first, "certificationId",
            certificationId), 422))).containsExactly(CertificationCodes.ROUND_AFTER_SUBMIT);

        // The App names the certification on the card and the page.
        String passer = newTaker("q88-passer", "JP");
        String failer = newTaker("q88-failer", "JP");
        Map<String, Object> page = card(passer, first);
        assertThat(page).containsEntry("certification", key).containsEntry("maxReward", 0);
        assertThat(String.valueOf(page.get("certificationNames"))).contains("Keyboard expert");
        assertThat(feed(taker(passer), "JP", Map.of())).anySatisfy(row -> assertThat(row)
            .containsEntry("publicationId", first).containsEntry("certification", key));

        // Passing holds it for the validity; failing does not; no reward is posted (nothing to pay).
        assertThat(play(passer, first, true, true)).containsEntry("passed", true).containsEntry("reward", 0);
        assertThat(play(failer, first, true, false)).containsEntry("passed", false);
        Map<String, Object> held = claim(passer, key);
        assertThat(Instant.parse((String) held.get("heldUntil"))).isAfter(clock.instant().plus(Duration.ofDays(364)));
        assertThat(claim(failer, key)).containsEntry("heldUntil", null).containsEntry("roundPublicationId", first);

        // F-S2c (D2): holding it no longer keeps anybody from another of its rounds.
        String second = freeDraft(sponsor, "Q88 keyboard basics II");
        setRound(sponsor, second, certificationId);
        run("QB_PUBLICATION_SUBMIT", token, body("publicationId", second));
        assertThat(start(taker(passer), second)).containsKey("attemptId");
        assertThat(start(taker(failer), second)).containsKey("attemptId");

        // Another sponsor cannot make their publication a round of it: another's is not found.
        String other = newSponsor("q88-other");
        credit(other, 10_000);
        String othersDraft = freeDraft(other, "Q88 other");
        refused(PublicationRounds.SET, sponsorToken(other), body("publicationId", othersDraft, "certificationId",
            certificationId), 404);

        // The holder's list and certificate name the sponsor (D-Q88-6); the public pages too.
        String company = (String) latest("qb_sponsor_version", "company", "user_id = '" + sponsor + "'").getFirst()
            .get("company");
        List<Map<String, Object>> mine = (List<Map<String, Object>>) run(CertificateProcesses.MINE, taker(passer),
            body()).get("held");
        assertThat(mine).singleElement().satisfies(h -> {
            assertThat(h).containsEntry("certification", key).containsEntry("sponsorName", company);
            assertThat((Map<String, Object>) h.get("name")).containsEntry("en", "Keyboard expert");
        });
        // F-S2c (D8): no certificate is issued any more; one issued before names the sponsor as its issuer.
        assertThat(codes(refused(CertificateProcesses.ISSUE, taker(passer), body("certification", key, "language",
            "en"), 422))).containsExactly(CertificateProcesses.PAUSED);
        String verifyKey = issuedBefore(passer, key, "Taker JP", "en", Map.of("certificationName", "Keyboard expert",
            "issuerName", company));
        Object certificateId = query("SELECT certificate_id FROM qb_certificate WHERE verify_key = ?", verifyKey)
            .getFirst().get("certificate_id");
        List<Map<String, Object>> runs = client.get().uri("/api/documents/runs?layout={layout}&subject={subject}",
                "qb.certificate", certificateId).header(HttpHeaders.AUTHORIZATION, taker(passer))
            .exchange().expectStatus().isOk().expectBodyList(Map.class).returnResult().getResponseBody().stream()
            .map(row -> (Map<String, Object>) row).toList();
        byte[] pdf = client.get().uri("/api/documents/runs/{id}/pdf", runs.getFirst().get("runId"))
            .header(HttpHeaders.AUTHORIZATION, taker(passer)).exchange().expectStatus().isOk()
            .expectBody(byte[].class).returnResult().getResponseBody();
        String text = CertificateIT.text(pdf).replaceAll("\\s+", " ");
        // The sponsor is the issuer (D-Q88-6); a long name wraps in its column.
        assertThat(text).contains("Keyboard expert").contains("Issued by");
        assertThat(text.replace(" ", "")).contains(company.replace(" ", ""));
        assertThat(publicItems("qb.public.certificate", "p.key=" + verifyKey)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("certification", key).containsEntry("issuerName", company);
            assertThat(String.valueOf(row.get("certificationNames"))).contains("Keyboard expert");
        });

        // The sponsor's list and the administration's.
        assertThat(items("qb.sponsor.certifications", token, Map.of("params", Map.of("at",
            clock.instant().toString())))).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("certificationId", certificationId).containsEntry("rounds", 2)
            .containsEntry("openRounds", 2).containsEntry("holders", 1));
        assertThat(items("qb.admin.sponsor-certifications", hq(), Map.of("params", Map.of("at",
            clock.instant().toString(), "q", company)))).anySatisfy(row -> assertThat(row)
            .containsEntry("certificationId", certificationId).containsEntry("company", company));

        // Closing: the sponsor closes one round, its operator (F-S1: the platform operator) the other.
        clock.advance(Duration.ofMinutes(1));
        assertThat(run(PublicationRounds.CLOSE, token, body("publicationId", first)).get("closedAt")).isNotNull();
        String secondRoundId = (String) round(sponsor, second).getFirst().get("roundId");
        assertThat(run(CertificationProcesses.ROUND_CLOSE, platformOperatorMember(), body("roundId", secondRoundId)).get("closedAt"))
            .isNotNull();
        String plain = livePublication(sponsor, "Q88 advanced", Map.of("regions", List.of("GLOBAL")));
        assertThat(codes(refused(PublicationRounds.CLOSE, token, body("publicationId", plain), 422)))
            .containsExactly(CertificationCodes.NOT_A_ROUND);

        // A new version: what was held under the old one counts no more (D-Q88-1), and the open rounds close
        // (their passes would count under the old version): the draft registered before the raise is one.
        String third = freeDraft(sponsor, "Q88 keyboard III");
        setRound(sponsor, third, certificationId);
        assertThat(round(sponsor, third).getFirst()).containsEntry("certificationVersion", 1)
            .containsEntry("closedAt", null);
        run(SponsorCertificationProcesses.UPDATE, token, body("certificationId", certificationId, "name",
            Map.of("en", "Keyboard expert v2"), "raiseVersion", true));
        assertThat(claim(passer, key)).containsEntry("heldUntil", null);
        assertThat(round(sponsor, third).getFirst()).containsEntry("currentVersion", 2)
            .doesNotContainEntry("closedAt", null);
        // Registering the draft again replaces the closed round by one of the new version.
        setRound(sponsor, third, certificationId);
        assertThat(round(sponsor, third).getFirst()).containsEntry("certificationVersion", 2)
            .containsEntry("closedAt", null);

        // Retired: no new round; its draft is refused, a draft deleted takes its round.
        run(SponsorCertificationProcesses.RETIRE, token, body("certificationId", certificationId));
        String fourth = freeDraft(sponsor, "Q88 keyboard IV");
        assertThat(codes(refused(PublicationRounds.SET, token, body("publicationId", fourth, "certificationId",
            certificationId), 422))).containsExactly(CertificationCodes.SPONSOR_RETIRED);
        assertThat(codes(refused(SponsorCertificationProcesses.UPDATE, token, body("certificationId",
            certificationId, "name", Map.of("en", "x")), 422))).containsExactly(CertificationCodes.SPONSOR_RETIRED);
        run("QB_PUBLICATION_DELETE", token, body("publicationId", third));
        assertThat(query("SELECT count(*) AS n FROM qb_certification_round WHERE publication_id = ?", third)
            .getFirst().get("n")).isEqualTo(0L);
        assertBooksBalance();
    }
}

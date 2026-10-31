package com.jabiz.quizbuks.it;

import com.jabiz.runtime.context.Actor;
import com.jabiz.context.EntryScope;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.certification.CertificationCodes;
import com.jabiz.quizbuks.certification.CertificationProcesses;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Certifications (Q69, docs/quizbuks/plans/Q69-certifications.md): an official publisher's publication made a round,
 * a taker who passes it holds the certification for a year, records only inserted; operators run their own rounds and
 * grants and see only theirs; the answering path is not touched.
 */
// The sponsor entry takes registrations (the signed-in join needs it), as in production.
@org.springframework.test.context.TestPropertySource(properties = "jabiz.security.entries.sponsor.self-registration=true")
class CertificationIT extends PlayItSupport {

    private static final String CERT = "SPONSOR_APPLY";

    private String hq() {
        return TestTokens.bearer(tokens, "q69-hq", QbPermissions.CERTIFICATION_ADMIN,
            QbPermissions.CERTIFICATION_MANAGE, QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    private String operatorSession(String userId, String operatorId) {
        Actor actor = new Actor(userId, null, Set.of(QbRoles.OPERATOR),
            Set.copyOf(QbRoles.of(QbRoles.OPERATOR).permissions()), clock.instant(), null, QbOperators.ENTRY, true)
            .inScope(new EntryScope(QbOperators.SCOPE, operatorId));
        return "Bearer " + tokens.issue(actor).token();
    }

    /** Marks the user's address verified, as the mailed link does (the join requires it). */
    private void verify(String userId) {
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'sec_user_version' AND column_name NOT IN ('row_id', 'version_no', "
            + "'verified_email') ORDER BY ordinal_position").stream().map(row -> (String) row.get("column_name"))
            .toList();
        String list = String.join(", ", columns);
        execute("INSERT INTO sec_user_version (" + list + ", version_no, verified_email) SELECT " + list
            + ", version_no + 1, email FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC LIMIT 1",
            userId);
    }

    private String member(String operatorId, String name) {
        String userId = createUser(name);
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'sec_user_version' AND column_name NOT IN ('row_id', 'version_no', "
            + "'verified_email') ORDER BY ordinal_position").stream().map(row -> (String) row.get("column_name"))
            .toList();
        String list = String.join(", ", columns);
        execute("INSERT INTO sec_user_version (" + list + ", version_no, verified_email) SELECT " + list
            + ", version_no + 1, email FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC LIMIT 1",
            userId);
        run(OperatorProcesses.MEMBER_ADD, hq(), body("operatorId", operatorId, "userId", userId, "lead", true));
        return userId;
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec join(String takerId) {
        return client.post().uri("/api/auth/account/entries/sponsor/join")
            .header(org.springframework.http.HttpHeaders.AUTHORIZATION, taker(takerId)).exchange();
    }

    private long roleRows(String userId) {
        return ((Number) query("SELECT count(*) AS n FROM sec_user_role_version WHERE user_id = ?::uuid", userId)
            .getFirst().get("n")).longValue();
    }

    private Map<String, Object> claim(String takerId) {
        return run(CertificationProcesses.CLAIM, taker(takerId), body("certification", CERT));
    }

    @AfterEach
    void recordsAreOnlyInserted() {
        assertThat(query("SELECT count(*) AS n FROM qb_certification_record WHERE version <> 1").getFirst().get("n"))
            .isEqualTo(0L);
    }

    @Test
    void passingAnOfficialRoundHoldsTheCertificationForAYear() {
        String official = newSponsor("q69-official");
        credit(official, 10_000);
        run(CertificationProcesses.OFFICIAL_SET, hq(), body("userId", official, "active", true));
        String round = livePublication(official, "Q69 sponsor rules");
        Map<String, Object> added = run(CertificationProcesses.ROUND_ADD, hq(), body("publicationId", round,
            "certification", CERT));
        assertThat(added).containsEntry("certification", CERT).containsEntry("certificationVersion", 1);

        String passer = newTaker("q69-pass", "JP");
        String failer = newTaker("q69-fail", "JP");
        // Before answering: not held, and the round to answer is named.
        assertThat(claim(passer)).containsEntry("heldUntil", null).containsEntry("roundPublicationId", round);
        assertThat(play(passer, round, true, true)).containsEntry("passed", true);
        assertThat(play(failer, round, true, false)).containsEntry("passed", false);

        Map<String, Object> held = claim(passer);
        assertThat(Instant.parse((String) held.get("heldUntil"))).isAfter(clock.instant().plus(Duration.ofDays(364)));
        assertThat(claim(failer)).containsEntry("heldUntil", null);
        // Claiming again records nothing new.
        claim(passer);
        assertThat(query("SELECT count(*) AS n FROM qb_certification_record WHERE user_id = ?", passer).getFirst()
            .get("n")).isEqualTo(1L);

        // Q69 D-Q69-7: the sponsor entry's signed-in join needs the certification (the platform's sign-up guard).
        verify(passer);
        verify(failer);
        assertThat(join(failer).expectStatus().isForbidden().expectBody(MAP).returnResult().getResponseBody()
            .toString()).contains("SIGN_UP_REFUSED");
        long rolesBefore = roleRows(passer);
        join(passer).expectStatus().isNoContent();
        // The sponsor entry's registration role is added to the account.
        assertThat(roleRows(passer)).isGreaterThan(rolesBefore);

        // A year and a day on, it is held no more.
        clock.advance(Duration.ofDays(366));
        assertThat(claim(passer)).containsEntry("heldUntil", null);
    }

    @Test
    void onlyAnOfficialPublishersPublicationIsARoundAndAClosedRoundCountsOnlyEarlierPasses() {
        String plain = newSponsor("q69-plain");
        credit(plain, 10_000);
        String notOfficial = livePublication(plain, "Q69 not official");
        assertThat(codes(refused(CertificationProcesses.ROUND_ADD, hq(), body("publicationId", notOfficial,
            "certification", CERT), 422))).containsExactly(CertificationCodes.NOT_OFFICIAL);
        // A taker is no official publisher's sponsor; an unknown taker cannot be marked.
        String taker = newTaker("q69-not-sponsor", "JP");
        assertThat(codes(refused(CertificationProcesses.OFFICIAL_SET, hq(), body("userId", taker, "active", true),
            422))).containsExactly(CertificationCodes.NOT_APPROVED_SPONSOR);

        String official = newSponsor("q69-official-2");
        credit(official, 10_000);
        run(CertificationProcesses.OFFICIAL_SET, hq(), body("userId", official, "active", true));
        String round = livePublication(official, "Q69 closing");
        String roundId = (String) run(CertificationProcesses.ROUND_ADD, hq(), body("publicationId", round,
            "certification", CERT)).get("roundId");
        assertThat(codes(refused(CertificationProcesses.ROUND_ADD, hq(), body("publicationId", round,
            "certification", CERT), 422))).containsExactly(CertificationCodes.ALREADY_A_ROUND);

        String early = newTaker("q69-early", "JP");
        play(early, round, true, true);
        clock.advance(Duration.ofMinutes(1));
        run(CertificationProcesses.ROUND_CLOSE, hq(), body("roundId", roundId));
        String late = newTaker("q69-late", "JP");
        play(late, round, true, true);
        assertThat(claim(early).get("heldUntil")).isNotNull();
        Map<String, Object> lateClaim = claim(late);
        assertThat(lateClaim).containsEntry("heldUntil", null);
        // The closed round is not offered to answer.
        assertThat(lateClaim.get("roundPublicationId")).isNotEqualTo(round);
    }

    @Test
    void grantsAndRevocationsByHandNeedAReasonAndARevocationOutweighsEarlierPasses() {
        String person = newTaker("q69-hand", "JP");
        refused(CertificationProcesses.GRANT, hq(), body("userId", person, "certification", CERT, "reason", ""), 400);
        run(CertificationProcesses.GRANT, hq(), body("userId", person, "certification", CERT, "reason",
            "Met at the fair"));
        assertThat(claim(person).get("heldUntil")).isNotNull();
        clock.advance(Duration.ofMinutes(1));
        run(CertificationProcesses.REVOKE, hq(), body("userId", person, "certification", CERT, "reason", "Mistake"));
        assertThat(claim(person)).containsEntry("heldUntil", null);
        // Takers cannot grant themselves anything.
        refused(CertificationProcesses.GRANT, taker(person), body("userId", person, "certification", CERT,
            "reason", "Me"), 403);
        Map<String, Object> overview = run(CertificationProcesses.OVERVIEW, hq(), body());
        assertThat(overview).containsEntry("owner", "direct");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byHand = (List<Map<String, Object>>) overview.get("byHand");
        assertThat(byHand).filteredOn(row -> person.equals(row.get("userId")))
            .extracting(row -> row.get("kind"), row -> row.get("reason"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("REVOKED", "Mistake"),
                org.assertj.core.groups.Tuple.tuple("GRANTED", "Met at the fair"));
    }

    @Test
    void anOperatorRunsItsOwnOfficialPublishersRoundsAndSeesOnlyItsOwn() {
        String north = (String) run(OperatorProcesses.SAVE, hq(), body("name", unique("North"), "contactName", "N",
            "contactEmail", "ops@north.example")).get("operatorId");
        String south = (String) run(OperatorProcesses.SAVE, hq(), body("name", unique("South"), "contactName", "S",
            "contactEmail", "ops@south.example")).get("operatorId");
        String northSession = operatorSession(member(north, unique("q69-n")), north);
        String southSession = operatorSession(member(south, unique("q69-s")), south);
        String northOfficial = newSponsor("q69-north-official");
        credit(northOfficial, 10_000);
        // An operator's official publisher is one of its sponsors (its scope covers the publications).
        run(OperatorProcesses.ASSIGN, hq(), body("userId", northOfficial, "operatorId", north, "reason", "Q69"));
        run(CertificationProcesses.OFFICIAL_SET, hq(), body("userId", northOfficial, "operatorId", north,
            "active", true));
        String round = livePublication(northOfficial, "Q69 north");
        // Another operator's sponsor's publication is not found (the operators' scope); headquarters may not use it.
        refused(CertificationProcesses.ROUND_ADD, southSession, body("publicationId", round, "certification", CERT),
            404);
        assertThat(codes(refused(CertificationProcesses.ROUND_ADD, hq(), body("publicationId", round,
            "certification", CERT), 422))).containsExactly(CertificationCodes.NOT_OFFICIAL);
        String roundId = (String) run(CertificationProcesses.ROUND_ADD, northSession, body("publicationId", round,
            "certification", CERT)).get("roundId");
        refused(CertificationProcesses.ROUND_CLOSE, southSession, body("roundId", roundId), 404);
        // Operators do not mark official publishers.
        refused(CertificationProcesses.OFFICIAL_SET, northSession, body("userId", northOfficial, "active", false),
            403);

        String taker = newTaker("q69-north-taker", "JP");
        play(taker, round, true, true);
        assertThat(claim(taker).get("heldUntil")).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> northRounds = (List<Map<String, Object>>) run(CertificationProcesses.OVERVIEW,
            northSession, body()).get("rounds");
        assertThat(northRounds).singleElement().satisfies(row -> assertThat(row).containsEntry("roundId", roundId)
            .containsEntry("publicationName", "Q69 north").containsEntry("holders", 1));
        assertThat((List<?>) run(CertificationProcesses.OVERVIEW, southSession, body()).get("rounds")).isEmpty();
    }
}

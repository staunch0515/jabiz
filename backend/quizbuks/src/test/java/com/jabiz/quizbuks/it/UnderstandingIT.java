package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.certification.CertificationProcesses;
import com.jabiz.quizbuks.certification.Certifications;
import com.jabiz.quizbuks.page.SponsorPageProcesses;
import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.quizbuks.publishing.QbPrerequisites;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q86 (docs/quizbuks/plans/Q86-agreement-quiz-tree.md): a publication requiring a certification (saved, checked,
 * frozen, copied; the feed, the page and the start follow whether the taker holds it; the count agrees); the
 * understanding cards' template; the trees drawing the certification as an edge.
 */
class UnderstandingIT extends PlayItSupport {

    private static final String CERT = "CONTEST_RULES_UNDERSTOOD";

    private String hq() {
        return TestTokens.bearer(tokens, "q86-hq", QbPermissions.CERTIFICATION_ADMIN, QbPermissions.CERTIFICATION_MANAGE);
    }

    private Map<String, Object> at() {
        return Map.of("at", clock.instant().toString());
    }

    /** An official account's live round of the certification; returns the publication. */
    private String round(String certification) {
        String official = newSponsor(unique("s-q86-official"));
        credit(official, 100_000);
        run(CertificationProcesses.OFFICIAL_SET, hq(), body("userId", official, "active", true));
        String publication = livePublication(official, "Q86 round " + certification, Map.of("regions", List.of("GLOBAL")));
        run(CertificationProcesses.ROUND_ADD, hq(), body("publicationId", publication, "certification", certification));
        return publication;
    }

    private List<String> feedIds(String token) {
        return items("qb.app.feed", token, Map.of("params", Map.of("at", clock.instant().toString(), "country", "JP")))
            .stream().map(row -> (String) row.get("publicationId")).toList();
    }

    private Map<String, Object> page(String token, String publicationId) {
        List<Map<String, Object>> rows = items("qb.app.publication", token, Map.of("params", Map.of(
            "publicationId", publicationId, "at", clock.instant().toString(), "country", "JP")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Map<String, Object>> understanding(String token) {
        return items("qb.app.understanding", token, Map.of("params", at())).stream()
            .collect(Collectors.toMap(row -> (String) row.get("certification"), Function.identity()));
    }

    @Test
    void aPublicationMayRequireACertificationTakersHold() {
        String sponsor = newSponsor(unique("s-q86"));
        credit(sponsor, 100_000);
        String token = sponsorToken(sponsor);
        String basics = livePublication(sponsor, "Q86 basics", Map.of("regions", List.of("GLOBAL")));
        String draft = draft(token, version(token, "Q86 advanced quiz"), "Q86 advanced");
        // Every problem at once: both names, neither, a sponsor's certification, twice.
        Map<String, Object> problem = refused("QB_PUBLICATION_PREREQUISITES_SAVE", token, body("publicationId", draft,
            "prerequisites", List.of(
                body("requiredPublicationId", basics, "certification", CERT),
                body("requirement", "PASSED"),
                body("certification", "TERMS_SPONSOR_UNDERSTOOD", "requirement", "PASSED"),
                body("certification", CERT, "requirement", "PASSED"),
                body("certification", CERT, "requirement", "PASSED"))), 422);
        assertThat(violations(problem)).extracting(v -> v.get("field") + ":" + v.get("ruleCode")).containsExactly(
            "prerequisites[0]:" + PublishingCodes.PREREQUISITE_ONE_OF,
            "prerequisites[1]:" + PublishingCodes.PREREQUISITE_ONE_OF,
            "prerequisites[2]:" + PublishingCodes.PREREQUISITE_CERTIFICATION,
            "prerequisites[4]:" + PublishingCodes.PREREQUISITE_REPEATED);
        // A certification beside a publication: saved as PASSED, named by the sponsor's template, copied.
        run("QB_PUBLICATION_PREREQUISITES_SAVE", token, body("publicationId", draft, "prerequisites", List.of(
            body("requiredPublicationId", basics, "requirement", QbPrerequisites.ANSWERED),
            body("certification", CERT, "requirement", "PASSED"))));
        assertThat(items("qb.sponsor.publication-prerequisites", token, Map.of("params", Map.of("publicationId", draft))))
            .extracting(row -> row.get("certification") + "/" + row.get("name") + "/" + row.get("requirement"))
            .containsExactlyInAnyOrder("null/Q86 basics/ANSWERED", CERT + "/null/PASSED");
        String copy = (String) run("QB_PUBLICATION_COPY", token, body("publicationId", draft)).get("publicationId");
        assertThat(query("SELECT certification, required_publication_id FROM qb_publication_prerequisite"
            + " WHERE publication_id = ?::uuid ORDER BY certification NULLS FIRST", copy))
            .extracting(row -> String.valueOf(row.get("certification"))).containsExactly("null", CERT);
        run("QB_PUBLICATION_DELETE", token, body("publicationId", copy));
        // Live, open to every region (the draft above keeps the sponsor's default regions).
        String advanced = liveRequiring(sponsor, "Q86 advanced", Map.of("regions", List.of("GLOBAL")), List.of(
            body("requiredPublicationId", basics, "requirement", QbPrerequisites.ANSWERED),
            body("certification", CERT, "requirement", "PASSED")));

        // A taker who answered the basics but holds nothing: not listed, the page names the certification, no start.
        String taker = verifiedTaker("t-q86-" + UUID.randomUUID());
        String mine = taker(taker);
        play(taker, basics, false, false);
        // (The feed no longer lists the basics once answered; the gated one is kept out by the certification.)
        assertThat(feedIds(mine)).doesNotContain(advanced);
        Map<String, Object> page = page(mine, advanced);
        assertThat(page).containsEntry("prerequisitesMet", false).containsEntry("canStart", false);
        assertThat((String) page.get("prerequisites")).contains("\"certification\": \"" + CERT + "\"")
            .contains("\"met\": false").contains("\"roundPublicationId\": null");
        Map<String, Object> refusal = refused("QB_ATTEMPT_START", mine, body("publicationId", advanced), 422);
        assertThat(violations(refusal)).singleElement().satisfies(v -> assertThat(v)
            .containsEntry("field", "publicationId").containsEntry("ruleCode", PlayCodes.PREREQUISITE_NOT_MET));
        // The exact count agrees: the taker (verified below) does not meet it yet.
        assertThat(count(advanced, sponsor)).isEqualTo(0L);

        // A round opens: the page sends the taker to it; passing it lights the certification, and the way opens.
        String roundPublication = round(CERT);
        assertThat((String) page(mine, advanced).get("prerequisites")).matches("(?s).*\"roundPublicationId\": \"[0-9a-f-]{36}\".*");
        play(taker, roundPublication, true, true);
        assertThat(feedIds(mine)).contains(advanced);
        assertThat(page(mine, advanced)).containsEntry("prerequisitesMet", true).containsEntry("canStart", true);
        assertThat((String) page(mine, advanced).get("prerequisites")).contains("\"met\": true");
        assertThat(count(advanced, sponsor)).isEqualTo(1L);
        assertThat(start(mine, advanced)).containsKey("attemptId");
        // The holding is read only for a publication that requires a certification: an ordinary start reads nothing
        // more (AttemptStatementBudgetIT.aStartWithACertificationPrerequisite counts the statements).
    }

    /** The exact count of takers meeting the publication's rules (qb.system.audience-count: verified takers only). */
    private long count(String publicationId, String sponsor) {
        String auditor = TestTokens.bearer(tokens, "q86-auditor", QbPermissions.AUDIENCE_SYSTEM);
        Map<String, Object> params = new java.util.HashMap<>(at());
        params.put("publicationId", publicationId);
        params.put("publisherId", sponsor);
        return ((Number) items("qb.system.audience-count", auditor, Map.of("params", params)).getFirst().get("takers"))
            .longValue();
    }

    @Test
    void theUnderstandingTemplateNamesTheRoundsAndTheHoldings() {
        // Its own kind: the other tests open rounds of CONTEST_RULES_UNDERSTOOD in the same schema.
        String kind = "TERMS_APP_UNDERSTOOD";
        String taker = newTaker("t-q86-und-" + UUID.randomUUID(), "JP");
        String mine = taker(taker);
        Map<String, Map<String, Object>> before = understanding(mine);
        assertThat(before).containsOnlyKeys("TERMS_APP_UNDERSTOOD", "CONTEST_RULES_UNDERSTOOD", "TERMS_SPONSOR_UNDERSTOOD");
        assertThat(before.get(kind)).containsEntry("enabled", true).containsEntry("heldUntil", null)
            .containsEntry("roundPublicationId", null).containsEntry("platformHandle", "");
        String roundPublication = round(kind);
        assertThat(understanding(mine).get(kind)).containsEntry("roundPublicationId", roundPublication)
            .containsEntry("heldUntil", null);
        play(taker, roundPublication, true, true);
        Map<String, Object> after = understanding(mine).get(kind);
        assertThat(after.get("heldUntil")).isNotNull();
        assertThat(after).containsEntry("roundPublicationId", roundPublication);
        // The platform's tree is a parameter.
        run("PARAM_SET", TestTokens.bearer(tokens, "q86-admin", "*"), body("key", QbParams.TREE_PLATFORM_HANDLE,
            "value", "quizbuks-hq"));
        assertThat(understanding(mine).get(kind)).containsEntry("platformHandle", "quizbuks-hq");
        // A sponsor's session (the console's overview) reads the same template: the sponsor terms, not held yet.
        String sponsor = newSponsor(unique("s-q86-und"));
        Map<String, Map<String, Object>> theirs = understanding(sponsorToken(sponsor));
        assertThat(theirs.get("TERMS_SPONSOR_UNDERSTOOD")).containsEntry("enabled", true).containsEntry("heldUntil", null)
            .containsEntry("platformHandle", "quizbuks-hq");
    }

    @Test
    void theTreesDrawACertificationRequiredAsAnEdge() {
        String sponsor = newSponsor(unique("s-q86-tree"));
        credit(sponsor, 100_000);
        String token = sponsorToken(sponsor);
        Map<String, Object> global = Map.of("regions", List.of("GLOBAL"));
        String gated = liveRequiring(sponsor, "Q86 gated", global, List.of(body("certification", CERT, "requirement", "PASSED")));
        String handle = "q86-" + UUID.randomUUID().toString().substring(0, 8);
        run(SponsorPageProcesses.SAVE, token, body("handle", handle, "language", "en", "headline", "Q86"));
        Map<String, Object> submitted = run(SponsorPageProcesses.SUBMIT, token, body());
        run("APPROVAL_DECIDE", sponsorReviewer(), body("requestId", submitted.get("approvalRequestId"),
            "decision", "APPROVE"));
        deliverEvents();
        String holder = newTaker("t-q86-holder-" + UUID.randomUUID(), "JP");
        String other = newTaker("t-q86-other-" + UUID.randomUUID(), "JP");
        // Both are the sponsor's customers through another publication of theirs.
        String open = livePublication(sponsor, "Q86 open", global);
        play(holder, open, true, true);
        play(other, open, true, true);
        String roundPublication = round(CERT);
        play(holder, roundPublication, true, true);

        // The sponsor's tree: the edge names the certification; the holder could start, the other is locked out.
        Map<String, Object> node = items("qb.sponsor.tree", token, Map.of("params", at())).stream()
            .filter(row -> gated.equals(row.get("publicationId"))).findFirst().orElseThrow();
        assertThat((String) node.get("prerequisites")).contains("\"certification\": \"" + CERT + "\"");
        assertThat(((Number) node.get("unlocked")).intValue()).isEqualTo(1);
        assertThat(((Number) node.get("locked")).intValue()).isEqualTo(1);
        Map<String, Object> params = new java.util.HashMap<>(at());
        params.put("publicationId", gated);
        params.put("state", "LOCKED");
        assertThat(items("qb.sponsor.tree-people", token, Map.of("params", params))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("userName", "Taker JP"));
        // The takers' own trees on the sponsor's page.
        Function<String, Map<String, Object>> myNode = t -> items("qb.app.sponsor-tree", t, Map.of("params",
            Map.of("handle", handle, "at", clock.instant().toString(), "lang", "en"))).stream()
            .filter(row -> gated.equals(row.get("publicationId"))).findFirst().orElseThrow();
        assertThat(myNode.apply(taker(holder))).containsEntry("state", "UNLOCKED");
        assertThat((String) myNode.apply(taker(holder)).get("prerequisites")).contains("\"held\": true")
            .matches("(?s).*\"roundPublicationId\": \"[0-9a-f-]{36}\".*");
        assertThat(myNode.apply(taker(other))).containsEntry("state", "LOCKED");
        assertThat((String) myNode.apply(taker(other)).get("prerequisites")).contains("\"held\": false");
        // The public page's JSON names the certification too.
        List<Map<String, Object>> listed = publicItems("qb.public.sponsor-page-publications", handle);
        assertThat(listed).anySatisfy(row -> {
            assertThat(row).containsEntry("publicationId", gated);
            assertThat((String) row.get("prerequisites")).contains("\"certification\": \"" + CERT + "\"");
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> publicItems(String template, String handle) {
        Map<String, Object> page = client.get().uri(b -> b.path("/api/public/queries/" + template)
                .queryParam("p.handle", "{handle}").build(handle))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (List<Map<String, Object>>) page.get("items");
    }

    /** A taker in Japan whose address is verified (the exact count reads verified takers only), as PrerequisiteIT. */
    private String verifiedTaker(String name) {
        String userId = newTaker(name, "JP");
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'sec_user_version' AND column_name NOT IN ('row_id', 'version_no', "
            + "'verified_email') ORDER BY ordinal_position").stream().map(row -> (String) row.get("column_name"))
            .toList();
        String list = String.join(", ", columns);
        execute("INSERT INTO sec_user_version (" + list + ", version_no, verified_email) SELECT " + list
            + ", version_no + 1, email FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC LIMIT 1",
            userId);
        return userId;
    }
}

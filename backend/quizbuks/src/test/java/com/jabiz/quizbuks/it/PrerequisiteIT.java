package com.jabiz.quizbuks.it;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.play.PrerequisiteCheck;
import com.jabiz.quizbuks.publishing.PublicationPrerequisites;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A publication's prerequisites (Q76, docs/quizbuks/plans/Q76-prerequisites.md): a draft requires other submitted
 * publications of its sponsor's, passed or answered; they are frozen once it is submitted, copied with it and deleted
 * with its draft. A taker who has not met them sees the publication neither on the home nor startable on its page,
 * and its start is refused; the templates' rule is {@link PrerequisiteCheck}'s.
 */
class PrerequisiteIT extends PlayItSupport {

    private static final String SAVE = PublicationPrerequisites.SAVE;
    private static final ObjectMapper JSON = new ObjectMapper();

    private Map<String, Object> prerequisites(String publicationId, Object... pairs) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            list.add(body("publicationId", pairs[i], "requirement", pairs[i + 1]));
        }
        return body("publicationId", publicationId, "prerequisites", list);
    }

    /** A complete draft of the sponsor's, live from now for a week once submitted, aimed at GLOBAL. */
    private String liveDraft(String sponsor, String name) {
        return liveDraft(sponsor, name, Map.of());
    }

    private String liveDraft(String sponsor, String name, Map<String, Object> more) {
        String token = sponsorToken(sponsor);
        Instant now = clock.instant();
        Map<String, Object> save = complete(version(token, name + " quiz"), name, now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        save.put("maxWinners", 1000);
        save.putAll(more);
        return (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
    }

    private List<Map<String, Object>> feed(String token) {
        return items("qb.app.feed", token, body("params", body("at", clock.instant().toString()), "limit", 200));
    }

    private Map<String, Object> page(String token, String publicationId) {
        return items("qb.app.publication", token, body("params", body("publicationId", publicationId,
            "at", clock.instant().toString()), "limit", 1)).getFirst();
    }

    /** The sponsor's published page (submitted and approved, as SiteLanguagesIT does); returns its handle. */
    private String publishPage(String sponsor) {
        String token = sponsorToken(sponsor);
        String handle = "q76-" + UUID.randomUUID().toString().substring(0, 8);
        run(com.jabiz.quizbuks.page.SponsorPageProcesses.SAVE, token, body("handle", handle, "language", "en",
            "headline", "Learn with us"));
        Map<String, Object> submitted = run(com.jabiz.quizbuks.page.SponsorPageProcesses.SUBMIT, token, body());
        run("APPROVAL_DECIDE", sponsorReviewer(), body("requestId", submitted.get("approvalRequestId"),
            "decision", "APPROVE"));
        deliverEvents();
        return handle;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> publicItems(String template, String handle) {
        Map<String, Object> page = client.get().uri(b -> b.path("/api/public/queries/" + template)
                .queryParam("p.handle", "{handle}").build(handle))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (List<Map<String, Object>>) page.get("items");
    }

    private List<Map<String, Object>> parsed(Object json) throws Exception {
        return json == null ? List.of() : JSON.readValue(json.toString(), new TypeReference<>() {});
    }

    @Test
    void aDraftRequiresTheSponsorsSubmittedPublicationsCheckedAtOnce() {
        String sponsor = newSponsor(unique("s-q76-rules"));
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String live = livePublication(sponsor, "Basics", Map.of("regions", List.of("GLOBAL")));
        String otherDraft = liveDraft(sponsor, "Another draft");
        String draft = liveDraft(sponsor, "Advanced");
        String someoneElses = livePublication(newSponsor(unique("s-q76-other")), "Theirs",
            Map.of("regions", List.of("GLOBAL")));
        credit(sponsor, 10_000);

        assertThat(codes(refused(SAVE, token, prerequisites(draft, draft, "PASSED", live, "PASSED", live, "ANSWERED",
            someoneElses, "PASSED", otherDraft, "PASSED"), 422)))
            .containsExactly(PublishingCodes.PREREQUISITE_SELF, PublishingCodes.PREREQUISITE_REPEATED,
                PublishingCodes.PREREQUISITE_NOT_OWN, PublishingCodes.PREREQUISITE_DRAFT);
        refused(SAVE, token, prerequisites(draft, live, "SOMETIMES"), 400);
        // Another sponsor's draft is no one's to edit.
        refused(SAVE, sponsorToken(newSponsor(unique("s-q76-stranger"))), prerequisites(draft, live, "PASSED"), 404);

        Map<String, Object> saved = run(SAVE, token, prerequisites(draft, live, "PASSED"));
        assertThat(((Number) saved.get("revision")).intValue()).isGreaterThan(1);
        // Saving the whole again changes the requirement; an empty list removes them.
        run(SAVE, token, prerequisites(draft, live, "ANSWERED"));
        assertThat(query("SELECT requirement FROM qb_publication_prerequisite WHERE publication_id = ?::uuid", draft))
            .extracting(row -> row.get("requirement")).containsExactly("ANSWERED");
        run(SAVE, token, prerequisites(draft));
        assertThat(query("SELECT 1 FROM qb_publication_prerequisite WHERE publication_id = ?::uuid", draft)).isEmpty();
        run(SAVE, token, prerequisites(draft, live, "PASSED"));

        // A copy takes them with it; deleting the copy's draft deletes its own.
        String copy = (String) run("QB_PUBLICATION_COPY", token, body("publicationId", draft)).get("publicationId");
        assertThat(query("SELECT required_publication_id FROM qb_publication_prerequisite "
            + "WHERE publication_id = ?::uuid", copy)).extracting(row -> row.get("required_publication_id").toString())
            .containsExactly(live);
        run("QB_PUBLICATION_DELETE", token, body("publicationId", copy));
        assertThat(query("SELECT 1 FROM qb_publication_prerequisite WHERE publication_id = ?::uuid", copy)).isEmpty();

        // Submitted, they are frozen.
        submit(token, draft);
        assertThat(codes(refused(SAVE, token, prerequisites(draft), 422))).containsExactly(PublishingCodes.NOT_DRAFT);

        // An invited publication may have them too (D-Q76-5).
        String invited = liveDraft(sponsor, "Invited", Map.of("audience", "INVITED"));
        run(SAVE, token, prerequisites(invited, live, "PASSED"));
    }

    @Test
    void aTakerSeesAndStartsItOnlyOnceThePrerequisitesAreMet() throws Exception {
        String sponsor = newSponsor(unique("s-q76-play"));
        credit(sponsor, 100_000);
        String token = sponsorToken(sponsor);
        String basics = livePublication(sponsor, "Basics", Map.of("regions", List.of("GLOBAL")));
        String advanced = liveDraft(sponsor, "Advanced");
        run(SAVE, token, prerequisites(advanced, basics, "PASSED"));
        submit(token, advanced);
        String practice = liveDraft(sponsor, "Practice");
        run(SAVE, token, prerequisites(practice, basics, "ANSWERED"));
        submit(token, practice);

        String taker = newTaker(unique("t-q76"), null);
        String takerToken = taker(taker);
        // Not met: not on the home, on its page not startable with what to complete first, and the start refused.
        assertThat(feed(takerToken)).extracting(row -> row.get("publicationId"))
            .contains(basics).doesNotContain(advanced, practice);
        Map<String, Object> locked = page(takerToken, advanced);
        assertThat(locked).containsEntry("prerequisitesMet", false).containsEntry("canStart", false);
        assertThat(parsed(locked.get("prerequisites"))).singleElement().satisfies(p -> assertThat(p)
            .containsEntry("publicationId", basics).containsEntry("requirement", "PASSED")
            .containsEntry("name", "Basics").containsEntry("met", false));
        assertThat(codes(refused("QB_ATTEMPT_START", takerToken, body("publicationId", advanced), 422)))
            .containsExactly(PlayCodes.PREREQUISITE_NOT_MET);

        // Answered but not passed: enough for "answered", not for "passed".
        play(taker, basics, false, false);
        assertThat(feed(takerToken)).extracting(row -> row.get("publicationId")).contains(practice)
            .doesNotContain(advanced);
        assertThat(page(takerToken, practice)).containsEntry("prerequisitesMet", true).containsEntry("canStart", true);
        assertThat(codes(refused("QB_ATTEMPT_START", takerToken, body("publicationId", advanced), 422)))
            .containsExactly(PlayCodes.PREREQUISITE_NOT_MET);
        run("QB_ATTEMPT_START", takerToken, body("publicationId", practice));

        // Another taker who passed sees it, and starts it.
        String passer = newTaker(unique("t-q76-pass"), null);
        play(passer, basics, true, true);
        assertThat(feed(taker(passer))).extracting(row -> row.get("publicationId")).contains(advanced);
        assertThat(parsed(page(taker(passer), advanced).get("prerequisites"))).singleElement()
            .satisfies(p -> assertThat(p).containsEntry("met", true));
        run("QB_ATTEMPT_START", taker(passer), body("publicationId", advanced));
        // Continuing it asks nothing more.
        assertThat(run("QB_ATTEMPT_START", taker(passer), body("publicationId", advanced)))
            .containsEntry("resumed", true);
    }

    @Test
    void theTemplatesAgreeWithTheJavaRule() {
        String sponsor = newSponsor(unique("s-q76-agree"));
        credit(sponsor, 100_000);
        String token = sponsorToken(sponsor);
        String first = livePublication(sponsor, "First", Map.of("regions", List.of("GLOBAL")));
        String second = livePublication(sponsor, "Second", Map.of("regions", List.of("GLOBAL")));
        String both = liveDraft(sponsor, "Both");
        run(SAVE, token, prerequisites(both, first, "PASSED", second, "ANSWERED"));
        submit(token, both);

        String marker = UUID.randomUUID().toString().substring(0, 8);
        // Nothing done, the first passed, the second answered, both, the first failed and the second answered.
        boolean[][] plays = {{}, {true}, {false, false, true}, {true, false, true}, {false, true, true}};
        int met = 0;
        for (int i = 0; i < plays.length; i++) {
            String taker = newTaker("t-q76-agree-" + marker + "-" + i, "JP");
            boolean[] p = plays[i];
            List<PrerequisiteCheck.Attempt> attempts = new ArrayList<>();
            if (p.length > 0 && (p[0] || p.length > 1)) {
                boolean pass = p[0];
                play(taker, first, pass, pass);
                attempts.add(new PrerequisiteCheck.Attempt(first, "SUBMITTED", pass));
            }
            if (p.length > 2 && p[2]) {
                play(taker, second, false, false);
                attempts.add(new PrerequisiteCheck.Attempt(second, "SUBMITTED", false));
            }
            boolean java = PrerequisiteCheck.unmet(List.of(new PrerequisiteCheck.Prerequisite(first, "PASSED"),
                new PrerequisiteCheck.Prerequisite(second, "ANSWERED")), attempts).isEmpty();
            String takerToken = taker(taker);
            assertThat(page(takerToken, both).get("prerequisitesMet")).as("taker " + i).isEqualTo(java);
            assertThat(feed(takerToken).stream().anyMatch(row -> row.get("publicationId").equals(both)))
                .as("taker " + i).isEqualTo(java);
            met += java ? 1 : 0;
        }
        assertThat(met).isEqualTo(1);
        // qb.system.audience-count writes the same clause; its exact count needs takers with verified addresses,
        // which the platform's append-only accounts give these tests none of (AudienceIT counts the same way).
    }

    @Test
    void theSponsorsPageSaysWhatEachRequires() throws Exception {
        String sponsor = newSponsor(unique("s-q76-page"));
        credit(sponsor, 100_000);
        String token = sponsorToken(sponsor);
        String basics = livePublication(sponsor, "Basics", Map.of("regions", List.of("GLOBAL")));
        String advanced = liveDraft(sponsor, "Advanced");
        run(SAVE, token, prerequisites(advanced, basics, "PASSED"));
        submit(token, advanced);
        String handle = publishPage(sponsor);
        List<Map<String, Object>> rows = publicItems("qb.public.sponsor-page-publications", handle);
        assertThat(rows).filteredOn(row -> row.get("publicationId").equals(basics)).singleElement()
            .satisfies(row -> assertThat(row.get("prerequisites")).isNull());
        Map<String, Object> row = rows.stream().filter(r -> r.get("publicationId").equals(advanced)).findFirst()
            .orElseThrow();
        assertThat(parsed(row.get("prerequisites"))).singleElement().satisfies(p -> assertThat(p)
            .containsEntry("publicationId", basics).containsEntry("requirement", "PASSED"));
    }
}

package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.play.SponsorPlay;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Challenges (F-S4a, docs/quizbuks/plans/impl/F-S4.md section 2): a publication bound to a node of an operator's copy
 * of a platform map Sponsors light, by the operator's direct Sponsor. Its shape (paying nothing, no invitation,
 * referral, entry fee or shared video); Takers never see or start it (422 {@code QB_ATTEMPT_FOR_SPONSORS}); the
 * operator's Sponsors answer it ({@code QB_SPONSOR_ATTEMPT_*}) and a pass lights the node for the Sponsor; another
 * operator's Sponsors find nothing.
 */
class SponsorChallengeIT extends TreeItSupport {

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("qbAnalyticsJob")
    com.jabiz.job.JobDefinition<com.jabiz.quizbuks.stats.AnalyticsJob.ItemInput> analyticsJob;

    private static List<Object> problemCodes(Map<String, Object> saved) {
        return ((List<?>) saved.get("problems")).stream().map(p -> (Object) ((Map<?, ?>) p).get("code")).toList();
    }

    private Map<String, Object> sponsorStart(String sponsor, String publicationId) {
        return run(SponsorPlay.START, sponsorToken(sponsor), body("publicationId", publicationId));
    }

    private Map<String, Object> sponsorSubmit(String sponsor, String attemptId, boolean first, boolean second) {
        Map<String, Object> input = submission(attemptId);
        input.put("answers", shown(attemptId, answers(first, second)));
        return run(SponsorPlay.SUBMIT, sponsorToken(sponsor), input);
    }

    private List<Map<String, Object>> lits(String subjectKind, String subjectId) {
        return query("SELECT tree_id, node_id FROM qb_tree_lit_version WHERE subject_kind = ? AND subject_id = ? "
            + "ORDER BY row_id", subjectKind, subjectId);
    }

    @Test
    void aChallengeIsBoundToTheOperatorsCopyByItsDirectSponsorAndPaysNothing() {
        Copy copy = copy();
        String direct = sponsorToken(copy.operator().leadId());
        // The template itself is never bound: its Sponsors light the operator's copy.
        assertThat(problemCodes(run("QB_PUBLICATION_SAVE", direct, challenge(direct, copy.templateId(), copy.start(),
            "Template")))).contains(PublishingCodes.NODE_SPONSOR_LIT);
        // Another Sponsor of the operator, and another operator's direct Sponsor, cannot bind the copy.
        String member = sponsorToken(sponsorOf(copy.operator(), "s-fs4a-member"));
        assertThat(problemCodes(run("QB_PUBLICATION_SAVE", member, challenge(member, copy.treeId(), copy.start(),
            "Member")))).contains(PublishingCodes.NODE_UNKNOWN);
        String stranger = sponsorToken(officialOperator("FS4a other").leadId());
        assertThat(problemCodes(run("QB_PUBLICATION_SAVE", stranger, challenge(stranger, copy.treeId(), copy.start(),
            "Stranger")))).contains(PublishingCodes.NODE_UNKNOWN);

        // The wizard offers what the save accepts (qb.sponsor.tree-bindable-nodes, the same rule): the copy's nodes
        // asking for a pass to its direct Sponsor, never the template's, and none to the operator's other Sponsors.
        assertThat(items("qb.sponsor.tree-bindable-nodes", direct, Map.of("limit", 500)))
            .filteredOn(row -> List.of(copy.treeId(), copy.templateId()).contains(String.valueOf(row.get("treeId"))))
            .extracting(row -> row.get("treeId") + "/" + row.get("nodeId"))
            .containsExactlyInAnyOrder(copy.treeId() + "/" + copy.start(), copy.treeId() + "/" + copy.next());
        assertThat(items("qb.sponsor.tree-bindable-nodes", member, Map.of("limit", 500)))
            .extracting(row -> String.valueOf(row.get("treeId"))).doesNotContain(copy.treeId(), copy.templateId());

        // The direct Sponsor binds it; a challenge pays nothing, invites nobody, and has no referral or entry fee.
        Map<String, Object> paying = challenge(direct, copy.treeId(), copy.start(), "Paying");
        paying.put("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 10)));
        paying.put("audience", "INVITED");
        assertThat(problemCodes(run("QB_PUBLICATION_SAVE", direct, paying)))
            .contains(PublishingCodes.CHALLENGE_REWARD, PublishingCodes.CHALLENGE_INVITED)
            .doesNotContain(PublishingCodes.NODE_UNKNOWN, PublishingCodes.NODE_SPONSOR_LIT);
        Map<String, Object> saved = run("QB_PUBLICATION_SAVE", direct, challenge(direct, copy.treeId(), copy.start(),
            "Challenge"));
        assertThat(problemCodes(saved)).isEmpty();
        String publicationId = (String) saved.get("publicationId");
        assertThat(submit(direct, publicationId).get("status")).isIn("LIVE", "SCHEDULED");
        assertThat(query("SELECT for_sponsors FROM qb_publication_card WHERE publication_id = ?::uuid",
            publicationId).getFirst()).containsEntry("for_sponsors", true);
        // An ordinary publication's card is not one.
        String plain = livePublication(copy.operator().leadId(), "FS4a plain");
        assertThat(query("SELECT for_sponsors FROM qb_publication_card WHERE publication_id = ?::uuid", plain)
            .getFirst()).containsEntry("for_sponsors", false);
    }

    @Test
    void takersNeitherSeeNorStartAChallenge() {
        Copy copy = copy();
        String publicationId = liveBound(copy.operator().leadId(), unique("FS4a hidden"), copy.treeId(), copy.start(),
            Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0))));
        String userId = newTaker(unique("t-fs4a"), "JP");
        String token = taker(userId);
        assertThat(feed(token, "JP", Map.of())).extracting(row -> String.valueOf(row.get("publicationId")))
            .doesNotContain(publicationId);
        assertThat(items("qb.app.publication", token, Map.of("params", Map.of("publicationId", publicationId, "at",
            clock.instant().toString(), "country", "JP")))).isEmpty();
        refused("QB_VIEW", token, body("publicationId", publicationId), 404);
        assertThat(codes(refused("QB_ATTEMPT_START", token, body("publicationId", publicationId), 422)))
            .contains(PlayCodes.FOR_SPONSORS);
        assertThat(query("SELECT count(*) AS n FROM qb_attempt WHERE publication_id = ?::uuid", publicationId)
            .getFirst()).containsEntry("n", 0L);
    }

    @Test
    void theOperatorsSponsorsAnswerItAndAPassLightsTheNodeForTheSponsor() {
        Copy copy = copy();
        String publicationId = liveBound(copy.operator().leadId(), unique("FS4a answered"), copy.treeId(),
            copy.start(), Map.of("tiers", List.of(body("fromScore", 0, "toScore", 5, "reward", 0))));
        String sponsor = sponsorOf(copy.operator(), "s-fs4a-answer");

        // Another operator's Sponsor finds nothing; the publisher does not answer their own; an ordinary publication
        // is no challenge; staff hold no permission to answer.
        refused(SponsorPlay.START, sponsorToken(newSponsor(unique("s-fs4a-elsewhere"))), body("publicationId",
            publicationId), 404);
        assertThat(codes(refused(SponsorPlay.START, sponsorToken(copy.operator().leadId()), body("publicationId",
            publicationId), 422))).contains(PlayCodes.OWN_PUBLICATION);
        refused(SponsorPlay.START, sponsorToken(sponsor), body("publicationId",
            livePublication(copy.operator().leadId(), "FS4a not a challenge")), 404);
        refused(SponsorPlay.START, TestTokens.bearer(tokens, sponsor, QbPermissions.SPONSOR_APP), body(
            "publicationId", publicationId), 403);

        // Started, continued, saved, failed: nothing lit.
        String failer = sponsorOf(copy.operator(), "s-fs4a-fail");
        String failed = (String) sponsorStart(failer, publicationId).get("attemptId");
        assertThat(sponsorSubmit(failer, failed, false, false)).containsEntry("passed", false);
        deliverEvents();
        assertThat(lits("SPONSOR", failer)).isEmpty();

        Map<String, Object> started = sponsorStart(sponsor, publicationId);
        String attemptId = (String) started.get("attemptId");
        assertThat(sponsorStart(sponsor, publicationId)).containsEntry("attemptId", attemptId)
            .containsEntry("resumed", true);
        assertThat(query("SELECT user_id, on_behalf_of FROM qb_attempt WHERE attempt_id = ?", attemptId).getFirst())
            .containsEntry("user_id", sponsor).containsEntry("on_behalf_of", sponsor);
        Map<String, Object> save = new LinkedHashMap<>(body("attemptId", attemptId, "currentIndex", 1));
        save.put("answers", shown(attemptId, List.of(body("questionNo", 1, "selected", List.of(1)))));
        run(SponsorPlay.SAVE, sponsorToken(sponsor), save);
        // The Taker's processes take no Sponsor's attempt, and the Sponsor's no Taker's.
        refused("QB_ATTEMPT_START", sponsorToken(sponsor), body("publicationId", publicationId), 422);
        credit(copy.operator().leadId(), 10_000);
        String takerId = newTaker(unique("t-fs4a-own"), "JP");
        String ordinary = livePublication(copy.operator().leadId(), "FS4a ordinary");
        String takersAttempt = (String) start(taker(takerId), ordinary).get("attemptId");
        refused(SponsorPlay.RESULT, TestTokens.bearer(tokens, takerId, QbPermissions.SPONSOR_CHALLENGE),
            body("attemptId", takersAttempt), 404);

        Map<String, Object> submitted = sponsorSubmit(sponsor, attemptId, true, true);
        assertThat(submitted).containsEntry("passed", true);
        assertThat(query("SELECT reward FROM qb_attempt WHERE attempt_id = ?", attemptId).getFirst().get("reward"))
            .satisfiesAnyOf(reward -> assertThat(reward).isNull(),
                reward -> assertThat(((Number) reward).intValue()).isZero());
        assertThat(run(SponsorPlay.RESULT, sponsorToken(sponsor), body("attemptId", attemptId)))
            .containsEntry("passed", true);

        // The pass lights the copy's node for the Sponsor (not as a Taker), outside the answering.
        deliverEvents();
        assertThat(lits("SPONSOR", sponsor)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("tree_id", copy.treeId()).containsEntry("node_id", copy.start()));
        assertThat(lits("USER", sponsor)).isEmpty();
        // The Sponsor's answers are not in their Taker history.
        assertThat(items("qb.app.history", sponsorToken(sponsor), Map.of())).isEmpty();

        // Nor in the analytics: the job reads the ordinary publication, never the challenge.
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        assertThat(jobRunner.execute(analyticsJob, clock.instant()).outcome())
            .isEqualTo(com.jabiz.runtime.job.JobRunner.Outcome.SUCCEEDED);
        assertThat(query("SELECT 1 FROM qb_analytics_cursor WHERE publication_id = ?::uuid", ordinary)).hasSize(1);
        assertThat(query("SELECT 1 FROM qb_analytics_cursor WHERE publication_id = ?::uuid", publicationId))
            .isEmpty();
        assertThat(query("SELECT 1 FROM qb_analytics_hour WHERE publication_id = ?::uuid", publicationId)).isEmpty();
        // Support sees whom the attempt was answered as.
        assertThat(items("qb.admin.attempts", TestTokens.bearer(tokens, "fs4a-support", QbPermissions.ADMIN_USERS_READ),
            Map.of("filters", List.of(Map.of("field", "publicationId", "op", "eq", "value", publicationId)))))
            .extracting(row -> row.get("onBehalfOf")).containsOnly(failer, sponsor);
    }
}

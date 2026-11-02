package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.play.PrerequisiteCheck;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.quizbuks.publishing.QbPrerequisites;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prerequisites (docs/quizbuks/plans/Q76-prerequisites.md): a draft's prerequisites, checked and frozen with it; the
 * feed, the page and the start that follow the taker's finished attempts; the sponsor's count and template; the
 * templates agreeing with {@link PrerequisiteCheck}.
 */
class PrerequisiteIT extends PlayItSupport {

    @Test
    void aDraftsPrerequisitesAreReplacedCheckedFrozenAndCopied() {
        String sponsor = newSponsor("s-pre-draft");
        credit(sponsor, 1_000);
        String token = sponsorToken(sponsor);
        String first = livePublication(sponsor, "Pre level 1");
        String second = livePublication(sponsor, "Pre level 2");
        String publicationId = draft(token, version(token, "Pre draft quiz"), "Pre draft");
        Map<String, Object> saved = run("QB_PUBLICATION_PREREQUISITES_SAVE", token, prerequisites(publicationId,
            prerequisite(first, QbPrerequisites.PASSED)));
        assertThat(saved).containsEntry("publicationId", publicationId);
        assertThat(prerequisitesOf(token, publicationId)).containsExactly(first + "=PASSED");
        // Replaced as a whole: a changed requirement is a new row, one not given goes.
        run("QB_PUBLICATION_PREREQUISITES_SAVE", token, prerequisites(publicationId,
            prerequisite(first, QbPrerequisites.ANSWERED), prerequisite(second, QbPrerequisites.PASSED)));
        assertThat(prerequisitesOf(token, publicationId)).containsExactlyInAnyOrder(first + "=ANSWERED",
            second + "=PASSED");
        // The sponsor's template names them.
        assertThat(items("qb.sponsor.publication-prerequisites", token, Map.of("params", Map.of("publicationId",
            publicationId)))).extracting(row -> row.get("name") + "=" + row.get("requirement") + "/" + row.get("status"))
            .containsExactly("Pre level 1=ANSWERED/LIVE", "Pre level 2=PASSED/LIVE");
        // Every problem at once, each at its place: itself, a draft, somebody else's, the same one twice.
        String otherSponsor = newSponsor("s-pre-other");
        credit(otherSponsor, 1_000);
        String others = livePublication(otherSponsor, "Pre other");
        String anotherDraft = draft(token, version(token, "Pre other draft quiz"), "Pre other draft");
        Map<String, Object> problem = refused("QB_PUBLICATION_PREREQUISITES_SAVE", token, prerequisites(publicationId,
            prerequisite(first, QbPrerequisites.PASSED), prerequisite(publicationId, QbPrerequisites.PASSED),
            prerequisite(anotherDraft, QbPrerequisites.PASSED), prerequisite(others, QbPrerequisites.PASSED),
            prerequisite(first, QbPrerequisites.ANSWERED)), 422);
        assertThat(violations(problem)).extracting(v -> v.get("field") + ":" + v.get("ruleCode")).containsExactly(
            "prerequisites[1]:" + PublishingCodes.PREREQUISITE_SELF,
            "prerequisites[2]:" + PublishingCodes.PREREQUISITE_DRAFT,
            "prerequisites[3]:" + PublishingCodes.PREREQUISITE_NOT_OWN,
            "prerequisites[4]:" + PublishingCodes.PREREQUISITE_REPEATED);
        assertThat(prerequisitesOf(token, publicationId)).hasSize(2);
        // More than the limit is refused by the input's validation (400) before anything is read.
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i <= QbPrerequisites.MAX_PREREQUISITES; i++) {
            many.add(prerequisite(UUID.randomUUID().toString(), QbPrerequisites.PASSED));
        }
        refused("QB_PUBLICATION_PREREQUISITES_SAVE", token, body("publicationId", publicationId, "prerequisites",
            many), 400);
        refused("QB_PUBLICATION_PREREQUISITES_SAVE", token, prerequisites(publicationId, prerequisite(first,
            "SOMETIMES")), 400);
        // Somebody else's draft: not found; nothing of it in their view.
        String other = sponsorToken(otherSponsor);
        refused("QB_PUBLICATION_PREREQUISITES_SAVE", other, prerequisites(publicationId), 404);
        assertThat(rows(QbPrerequisites.SPONSOR_DATASET, other)).noneMatch(row -> publicationId.equals(
            String.valueOf(row.get("publicationId"))));
        // A copy carries them; deleting a draft removes its own.
        String copy = (String) run("QB_PUBLICATION_COPY", token, body("publicationId", publicationId))
            .get("publicationId");
        assertThat(prerequisitesOf(token, copy)).containsExactlyInAnyOrder(first + "=ANSWERED", second + "=PASSED");
        run("QB_PUBLICATION_DELETE", token, body("publicationId", copy));
        assertThat(query("SELECT count(*) AS n FROM qb_publication_prerequisite WHERE publication_id = ?::uuid", copy)
            .getFirst()).containsEntry("n", 0L);
        // Submitted, they are frozen; the submitted one can now be required itself.
        submit(token, publicationId);
        assertThat(codes(refused("QB_PUBLICATION_PREREQUISITES_SAVE", token, prerequisites(publicationId), 422)))
            .containsExactly(PublishingCodes.NOT_DRAFT);
        assertThat(prerequisitesOf(token, publicationId)).hasSize(2);
        run("QB_PUBLICATION_PREREQUISITES_SAVE", token, prerequisites(anotherDraft,
            prerequisite(publicationId, QbPrerequisites.PASSED)));
        assertThat(prerequisitesOf(token, anotherDraft)).containsExactly(publicationId + "=PASSED");
    }

    @Test
    void theFeedThePageAndTheStartFollowTheTakersFinishedAttempts() {
        String sponsor = newSponsor("s-pre-play");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String basics = livePublication(sponsor, "Pre basics", Map.of("regions", List.of("GLOBAL")));
        String advanced = liveRequiring(sponsor, "Pre advanced", Map.of("regions", List.of("GLOBAL")),
            List.of(prerequisite(basics, QbPrerequisites.PASSED)));
        String survey = liveRequiring(sponsor, "Pre survey", Map.of("regions", List.of("GLOBAL")),
            List.of(prerequisite(basics, QbPrerequisites.ANSWERED)));
        String taker = newTaker("t-pre-play-" + UUID.randomUUID(), "JP");
        String mine = taker(taker);
        // Nothing done: the basics only; the advanced one's page shows what to do first, and cannot be started.
        assertThat(feedIds(mine)).contains(basics).doesNotContain(advanced, survey);
        Map<String, Object> page = detail(mine, advanced);
        assertThat(page).containsEntry("prerequisitesMet", false).containsEntry("canStart", false)
            .containsEntry("matched", true);
        assertThat((String) page.get("prerequisites")).contains("\"requiredPublicationId\": \"" + basics + "\"")
            .contains("\"name\": \"Pre basics\"").contains("\"requirement\": \"PASSED\"").contains("\"met\": false")
            .contains("\"live\": true");
        Map<String, Object> refusal = refused("QB_ATTEMPT_START", mine, body("publicationId", advanced), 422);
        assertThat(codes(refusal)).containsExactly(PlayCodes.PREREQUISITE_NOT_MET);
        assertThat(violations(refusal).getFirst()).containsEntry("field", "publicationId");
        // In progress does not count.
        String attemptId = (String) start(mine, basics).get("attemptId");
        assertThat(feedIds(mine)).doesNotContain(advanced, survey);
        refused("QB_ATTEMPT_START", mine, body("publicationId", survey), 422);
        // Failed: answered, not passed.
        assertThat(submit(mine, attemptId, answers(true, false))).containsEntry("passed", false);
        assertThat(feedIds(mine)).contains(survey).doesNotContain(advanced);
        assertThat(detail(mine, survey)).containsEntry("prerequisitesMet", true).containsEntry("canStart", true);
        assertThat(detail(mine, advanced)).containsEntry("prerequisitesMet", false);
        assertThat(start(mine, survey)).containsKey("attemptId");
        assertThat(codes(refused("QB_ATTEMPT_START", mine, body("publicationId", advanced), 422)))
            .containsExactly(PlayCodes.PREREQUISITE_NOT_MET);
        // Another taker passes the basics: both open to them; the one who failed has no second try (one attempt).
        String passer = newTaker("t-pre-pass-" + UUID.randomUUID(), "JP");
        assertThat(play(passer, basics, true, true)).containsEntry("passed", true);
        assertThat(feedIds(taker(passer))).contains(advanced, survey);
        assertThat(detail(taker(passer), advanced)).containsEntry("prerequisitesMet", true)
            .containsEntry("canStart", true);
        assertThat(start(taker(passer), advanced)).containsKey("attemptId");
        // Learnt once is learnt: the required publication paused, the advanced one still opens.
        run("QB_PUBLICATION_PAUSE", token, body("publicationId", basics));
        String another = newTaker("t-pre-late-" + UUID.randomUUID(), "JP");
        assertThat(feedIds(taker(another))).doesNotContain(basics, advanced);
        Map<String, Object> late = detail(taker(another), advanced);
        assertThat(late).containsEntry("prerequisitesMet", false);
        assertThat((String) late.get("prerequisites")).contains("\"live\": false");
        assertThat(detail(taker(passer), advanced)).containsEntry("prerequisitesMet", true);
    }

    @Test
    void theCountAndTheTemplatesAgreeWithTheJavaCheck() {
        String sponsor = newSponsor("s-pre-count");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String basics = livePublication(sponsor, "Pre count basics", Map.of("regions", List.of("GLOBAL")));
        String marker = UUID.randomUUID().toString().substring(0, 8);
        // Takers of every kind (with verified addresses, as the count asks): nothing done, failed, passed, in progress.
        verifiedTaker("t-pre-count-" + marker + "-none");
        play(verifiedTaker("t-pre-count-" + marker + "-failed"), basics, true, false);
        play(verifiedTaker("t-pre-count-" + marker + "-passed"), basics, true, true);
        start(taker(verifiedTaker("t-pre-count-" + marker + "-open")), basics);
        String auditor = TestTokens.bearer(tokens, "prerequisite-auditor", QbPermissions.AUDIENCE_SYSTEM);
        Map<String, Long> counts = new HashMap<>();
        for (String requirement : List.of(QbPrerequisites.PASSED, QbPrerequisites.ANSWERED)) {
            String publicationId = draft(token, version(token, "Pre count quiz"), "Pre count");
            run("QB_PUBLICATION_PREREQUISITES_SAVE", token, prerequisites(publicationId,
                prerequisite(basics, requirement)));
            Map<String, Object> params = new HashMap<>();
            params.put("publicationId", publicationId);
            params.put("at", clock.instant().toString());
            params.put("publisherId", sponsor);
            Object counted = items("qb.system.audience-count", auditor, Map.of("params", params)).getFirst()
                .get("takers");
            long expected = javaCount(publicationId, sponsor);
            assertThat(((Number) counted).longValue()).as(requirement).isEqualTo(expected);
            counts.put(requirement, expected);
            // The sponsor's rounded count says the same (fewer than 20 here).
            assertThat(run("QB_AUDIENCE_COUNT", token, body("publicationId", publicationId)))
                .containsEntry("fewerThan", 20);
        }
        // The one who failed counts for an answer, not for a pass; the one in progress for neither.
        assertThat(counts.get(QbPrerequisites.PASSED)).isPositive();
        assertThat(counts.get(QbPrerequisites.ANSWERED)).isEqualTo(counts.get(QbPrerequisites.PASSED) + 1);
    }

    /** A taker in Japan whose address is verified: a new version of the platform's user, as the verification writes. */
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

    /** The Java check over every complete, unbanned profile (a GLOBAL publication reaches all of them). */
    private static long javaCount(String publicationId, String sponsor) {
        List<PrerequisiteCheck.Prerequisite> prerequisites = query("SELECT required_publication_id, requirement "
            + "FROM qb_publication_prerequisite WHERE publication_id = ?::uuid", publicationId).stream()
            .map(row -> new PrerequisiteCheck.Prerequisite(UUID.fromString(String.valueOf(
                row.get("required_publication_id"))), (String) row.get("requirement"))).toList();
        long count = 0;
        for (Map<String, Object> profile : latest("qb_profile_version", "profile_id", "country_code IS NOT NULL")) {
            String userId = (String) profile.get("user_id");
            if (userId.equals(sponsor) || !canAnswer(userId)) {
                continue;
            }
            List<PrerequisiteCheck.Attempt> attempts = query("SELECT publication_id, status, passed FROM qb_attempt "
                + "WHERE user_id = ?", userId).stream().map(row -> new PrerequisiteCheck.Attempt(UUID.fromString(
                String.valueOf(row.get("publication_id"))), (String) row.get("status"), (Boolean) row.get("passed")))
                .toList();
            if (PrerequisiteCheck.unmet(prerequisites, attempts).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** An enabled account with a verified address, as the count template asks. */
    private static boolean canAnswer(String userId) {
        List<Map<String, Object>> users = latest("sec_user_version", "user_id", "user_id = '" + userId + "'");
        if (users.isEmpty()) {
            return false;
        }
        Map<String, Object> user = users.getFirst();
        return Boolean.TRUE.equals(user.get("enabled")) && user.get("verified_email") != null
            && String.valueOf(user.get("verified_email")).equalsIgnoreCase(String.valueOf(user.get("email")));
    }

    private Map<String, Object> detail(String token, String publicationId) {
        List<Map<String, Object>> rows = items("qb.app.publication", token, Map.of("params", Map.of(
            "publicationId", publicationId, "at", clock.instant().toString(), "country", "JP")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<String> feedIds(String token) {
        return feed(token, "JP", Map.of()).stream().map(row -> String.valueOf(row.get("publicationId"))).toList();
    }

    private List<String> prerequisitesOf(String token, String publicationId) {
        return rows(QbPrerequisites.SPONSOR_DATASET, token).stream()
            .filter(row -> publicationId.equals(String.valueOf(row.get("publicationId"))))
            .map(row -> row.get("requiredPublicationId") + "=" + row.get("requirement")).toList();
    }

    @SafeVarargs
    private static Map<String, Object> prerequisites(String publicationId, Map<String, Object>... prerequisites) {
        return body("publicationId", publicationId, "prerequisites", new ArrayList<>(List.of(prerequisites)));
    }
}

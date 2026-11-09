package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.play.QbPlay;
import com.jabiz.quizbuks.publishing.PublicationVersions;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q81 (docs/quizbuks/plans/Q81-question-pick.md): a publication drawing k of its version's n questions. Each taker is
 * given their own k, in the version's order, numbered 1..k; only those are answered, scored and shown; the version's
 * numbers stay in the attempt's record for the statistics and never reach the taker.
 */
class QuestionPickIT extends PlayItSupport {

    static final List<String> CAPITALS = List.of("France|Paris|Lyon", "Japan|Tokyo|Osaka", "Italy|Rome|Milan",
        "Germany|Berlin|Munich", "Spain|Madrid|Seville");

    /** A version of {@code count} one-point questions, the right option written first. */
    private String equalVersion(String token, String title, int count) {
        String quizId = newQuiz(token, title);
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", title, "timeLimitSec", 300));
        for (int i = 0; i < count; i++) {
            String[] parts = CAPITALS.get(i).split("\\|");
            run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of " + parts[0] + "?", "points", 1,
                "options", List.of(body("text", parts[1], "correct", true), body("text", parts[2]))));
        }
        return (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
    }

    /** A draft of three of the five questions: pass at 2, nothing below 2, 10 Kudos for 2, 100 for 3. */
    private Map<String, Object> pickingDraft(String versionId, String name) {
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, name, now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        save.put("maxWinners", 1000);
        save.put("pickCount", 3);
        save.put("passScore", 2);
        save.put("tiers", List.of(body("fromScore", 0, "toScore", 1, "reward", 0),
            body("fromScore", 2, "toScore", 2, "reward", 10), body("fromScore", 3, "toScore", 3, "reward", 100)));
        return save;
    }

    private String livePicking(String sponsor, String name) {
        String token = sponsorToken(sponsor);
        String versionId = equalVersion(token, name + " quiz", 5);
        Map<String, Object> saved = run("QB_PUBLICATION_SAVE", token, pickingDraft(versionId, name));
        assertThat(saved).containsEntry("fullScore", 3).containsEntry("problems", List.of());
        String publicationId = (String) saved.get("publicationId");
        Map<String, Object> submitted = run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId));
        assertThat(submitted.get("status")).as("submitted " + submitted).isIn("LIVE", "SCHEDULED");
        return publicationId;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> questions(Map<String, Object> output) {
        return (List<Map<String, Object>>) output.get("questions");
    }

    private List<Integer> questionSet(String attemptId) {
        Object json = query("SELECT question_set FROM qb_attempt WHERE attempt_id = ?", attemptId).getFirst()
            .get("question_set");
        return com.jabiz.quizbuks.play.QuestionPick.fromJson(json);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aTakerIsGivenTheirThreeQuestionsNumberedByPositionAndScoredOnThem() {
        String sponsor = newSponsor("s-pick");
        credit(sponsor, 10_000);
        String publicationId = livePicking(sponsor, "Pick");
        // The card says 3 of 5, full score 3.
        Map<String, Object> card = query("SELECT question_count, pick_count, full_score FROM qb_publication_card "
            + "WHERE publication_id = ?::uuid", publicationId).getFirst();
        assertThat(card).hasEntrySatisfying("question_count", v -> assertThat(String.valueOf(v)).isEqualTo("5"))
            .hasEntrySatisfying("pick_count", v -> assertThat(String.valueOf(v)).isEqualTo("3"))
            .hasEntrySatisfying("full_score", v -> assertThat(String.valueOf(v)).isEqualTo("3"));

        String token = taker(newTaker("t-pick-1", "JP"));
        Map<String, Object> started = start(token, publicationId);
        String attemptId = (String) started.get("attemptId");
        List<Map<String, Object>> shown = questions(started);
        // Three questions, numbered 1..3 (D-Q81-5), each a capital of the version's five, in the version's order.
        assertThat(shown).extracting(question -> question.get("no")).containsExactly(1, 2, 3);
        List<Integer> set = questionSet(attemptId);
        assertThat(set).hasSize(3).doesNotHaveDuplicates().isSorted().allSatisfy(no -> assertThat(no).isBetween(1, 5));
        for (int i = 0; i < 3; i++) {
            assertThat(shown.get(i).get("stem")).isEqualTo("Capital of " + CAPITALS.get(set.get(i) - 1).split("\\|")[0]
                + "?");
        }
        // Continued: the same three.
        assertThat(questions(start(token, publicationId))).isEqualTo(shown);
        // The taker's record names neither the set nor the order.
        assertThat(rows(QbPlay.TAKER_ATTEMPTS, token)).singleElement().satisfies(row -> assertThat(row)
            .doesNotContainKey("questionSet").doesNotContainKey("optionOrder"));
        // A fourth question does not exist for this attempt.
        Map<String, Object> problem = refused("QB_ATTEMPT_SAVE", token, body("attemptId", attemptId, "answers",
            List.of(body("questionNo", 4, "selected", List.of(1)))), 422);
        assertThat(codes(problem)).containsOnly(PlayCodes.ANSWER_INVALID);
        // All three right, by position: full marks 3 of 3, the top tier.
        List<Object> answers = new ArrayList<>();
        for (int q = 1; q <= 3; q++) {
            answers.add(body("questionNo", q, "selected", List.of(position(attemptId, q, 1))));
        }
        Map<String, Object> result = run("QB_ATTEMPT_SUBMIT", token, body("attemptId", attemptId, "publicationId",
            publicationId, "answers", answers));
        assertThat(result).containsEntry("score", 3).containsEntry("fullScore", 3).containsEntry("passed", true)
            .containsEntry("reward", 100);
        assertThat((List<Map<String, Object>>) result.get("questions")).extracting(q -> q.get("no"))
            .containsExactly(1, 2, 3);
        // The record keeps the version's numbers (the statistics are by them); the result page shows positions.
        String stored = (String) attempt(attemptId).get("result");
        for (Integer no : set) {
            assertThat(stored).contains("\"no\":" + no + ",");
        }
        Map<String, Object> page = run("QB_ATTEMPT_RESULT", token, body("attemptId", attemptId));
        assertThat(questions(page)).extracting(q -> q.get("no")).containsExactly(1, 2, 3);
        assertThat((List<Map<String, Object>>) page.get("result")).extracting(q -> q.get("no"))
            .containsExactly(1, 2, 3);
        assertThat(page).containsEntry("fullScore", 3);
    }

    @Test
    void takersAreGivenDifferentSets() {
        String sponsor = newSponsor("s-pick-sets");
        credit(sponsor, 10_000);
        String publicationId = livePicking(sponsor, "Pick sets");
        Set<List<Integer>> sets = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            String token = taker(newTaker("t-pick-sets-" + i, "JP"));
            String attemptId = (String) start(token, publicationId).get("attemptId");
            sets.add(questionSet(attemptId));
        }
        // Ten possible sets of three of five: twelve takers do not all draw one (the chance is below 1e-11).
        assertThat(sets.size()).isGreaterThan(1);
    }

    @Test
    void thePickIsCheckedWhenSavedCopiedAndSwitched() {
        String sponsor = newSponsor("s-pick-rules");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String versionId = equalVersion(token, "Pick rules quiz", 5);
        // Five of five is not a pick; the full score is then the version's.
        Map<String, Object> draft = pickingDraft(versionId, "Pick rules");
        draft.put("pickCount", 5);
        Map<String, Object> saved = run("QB_PUBLICATION_SAVE", token, draft);
        assertThat(saved).containsEntry("fullScore", 5);
        assertThat(((List<Map<String, Object>>) saved.get("problems")).stream().map(p -> p.get("code")))
            .contains(PublishingCodes.PICK_RANGE);
        String publicationId = (String) saved.get("publicationId");
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId), 422)))
            .contains(PublishingCodes.PICK_RANGE);
        // Unequal points (the standard quiz: 2 and 3): no pick.
        Map<String, Object> unequal = pickingDraft(version(token, "Pick unequal quiz"), "Pick unequal");
        unequal.put("pickCount", 1);
        Map<String, Object> savedUnequal = run("QB_PUBLICATION_SAVE", token, unequal);
        assertThat(savedUnequal).containsEntry("fullScore", 5);
        assertThat(((List<Map<String, Object>>) savedUnequal.get("problems")).stream().map(p -> p.get("code")))
            .contains(PublishingCodes.PICK_UNEQUAL_POINTS);
        // A good pick: saved with full score 3; the copy carries it.
        draft.put("publicationId", publicationId);
        draft.put("pickCount", 3);
        draft.put("baseRevision", saved.get("revision"));
        Map<String, Object> good = run("QB_PUBLICATION_SAVE", token, draft);
        assertThat(good).containsEntry("fullScore", 3).containsEntry("problems", List.of());
        Map<String, Object> copy = run("QB_PUBLICATION_COPY", token, body("publicationId", publicationId));
        assertThat(copy).containsEntry("fullScore", 3);
        assertThat(query("SELECT pick_count FROM qb_publication_version WHERE publication_id = ?::uuid ORDER BY "
            + "version_no DESC LIMIT 1", String.valueOf(copy.get("publicationId"))).getFirst())
            .hasEntrySatisfying("pick_count", v -> assertThat(String.valueOf(v)).isEqualTo("3"));
        // Live, a switch (Q51) needs a version that can give three equal-point questions.
        String live = livePicking(sponsor, "Pick switch");
        String quizId = String.valueOf(query("SELECT quiz_id FROM qb_quiz_version_version WHERE version_id = "
            + "(SELECT version_id FROM qb_publication_card WHERE publication_id = ?::uuid) ORDER BY version_no DESC "
            + "LIMIT 1", live).getFirst().get("quiz_id"));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Peru?", "points", 2,
            "options", List.of(body("text", "Lima", "correct", true), body("text", "Cusco"))));
        String unequalVersion = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        assertThat(codes(refused(PublicationVersions.SWITCH, token, body("publicationId", live, "versionId",
            unequalVersion), 422))).containsExactly(PublishingCodes.SWITCH_PICK);
    }

    /** F-S7: a version of five one-point questions, shuffled, each with the difficulty given (null: none). */
    private String gradedVersion(String token, String title, String... levels) {
        String quizId = newQuiz(token, title);
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", title, "timeLimitSec", 300,
            "shuffleQuestions", true));
        for (int i = 0; i < levels.length; i++) {
            String[] parts = CAPITALS.get(i).split("\\|");
            Map<String, Object> question = body("quizId", quizId, "stem", "Capital of " + parts[0] + "?", "points", 1,
                "options", List.of(body("text", parts[1], "correct", true), body("text", parts[2])));
            if (levels[i] != null) {
                question.put("difficulty", levels[i]);
            }
            run("QB_QUESTION_SAVE", token, question);
        }
        return (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
    }

    private String stemOf(int no) {
        return "Capital of " + CAPITALS.get(no - 1).split("\\|")[0] + "?";
    }

    /** F-S7 (§1): a shuffled quiz drawing three: the set is the taker's three in an order of their own, and stays. */
    @Test
    void aShuffledPickGivesTheTakersThreeInTheirOwnOrder() {
        String sponsor = newSponsor("s-pick-order");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        String versionId = gradedVersion(token, "Pick order quiz", null, null, null, null, null);
        Map<String, Object> saved = run("QB_PUBLICATION_SAVE", token, pickingDraft(versionId, "Pick order"));
        String publicationId = (String) saved.get("publicationId");
        run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId));
        Set<List<Integer>> orders = new HashSet<>();
        boolean unsorted = false;
        for (int i = 0; i < 8; i++) {
            String taker = taker(newTaker("t-pick-order-" + i, "JP"));
            Map<String, Object> started = start(taker, publicationId);
            List<Integer> set = questionSet((String) started.get("attemptId"));
            assertThat(set).hasSize(3).doesNotHaveDuplicates().allSatisfy(no -> assertThat(no).isBetween(1, 5));
            // The taker sees them in the set's order, numbered by position.
            List<Map<String, Object>> shown = questions(started);
            assertThat(shown).extracting(q -> q.get("no")).containsExactly(1, 2, 3);
            for (int q = 0; q < 3; q++) {
                assertThat(shown.get(q).get("stem")).isEqualTo(stemOf(set.get(q)));
            }
            assertThat(questions(start(taker, publicationId))).isEqualTo(shown);
            orders.add(set);
            unsorted |= !set.equals(set.stream().sorted().toList());
        }
        assertThat(orders.size()).isGreaterThan(1);
        // Eight takers' sets are not all in the version's order (the chance is (1/6)^8).
        assertThat(unsorted).isTrue();
    }

    /**
     * F-S7 (§2): one question of each difficulty; refused while a question has none or a level has too few, and a
     * switch to a version that cannot give them.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aDrawByDifficultyTakesOneOfEachLevel() {
        String sponsor = newSponsor("s-pick-levels");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        // One question without a difficulty: refused.
        String ungraded = gradedVersion(token, "Levels ungraded", "EASY", "EASY", "MEDIUM", "HARD", null);
        Map<String, Object> draft = pickingDraft(ungraded, "Levels ungraded");
        draft.remove("pickCount");
        draft.put("pickByDifficulty", Map.of("EASY", 1, "MEDIUM", 1, "HARD", 1));
        Map<String, Object> saved = run("QB_PUBLICATION_SAVE", token, draft);
        assertThat(((List<Map<String, Object>>) saved.get("problems")).stream().map(p -> p.get("code")))
            .contains(PublishingCodes.PICK_DIFFICULTY_UNSET);
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", saved.get("publicationId")),
            422))).contains(PublishingCodes.PICK_DIFFICULTY_UNSET);

        String versionId = gradedVersion(token, "Levels quiz", "EASY", "EASY", "MEDIUM", "HARD", "HARD");
        // Three hard questions of two: refused.
        Map<String, Object> tooHard = pickingDraft(versionId, "Levels too hard");
        tooHard.remove("pickCount");
        tooHard.put("pickByDifficulty", Map.of("HARD", 3));
        Map<String, Object> savedTooHard = run("QB_PUBLICATION_SAVE", token, tooHard);
        assertThat(((List<Map<String, Object>>) savedTooHard.get("problems")).stream().map(p -> p.get("code")))
            .contains(PublishingCodes.PICK_DIFFICULTY_SHORT);

        Map<String, Object> good = pickingDraft(versionId, "Levels");
        good.remove("pickCount");
        good.put("pickByDifficulty", Map.of("EASY", 1, "MEDIUM", 1, "HARD", 1));
        Map<String, Object> savedGood = run("QB_PUBLICATION_SAVE", token, good);
        assertThat(savedGood).containsEntry("fullScore", 3).containsEntry("problems", List.of());
        String publicationId = (String) savedGood.get("publicationId");
        run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId));
        Map<String, Object> card = query("SELECT pick_count, pick_by_difficulty FROM qb_publication_card "
            + "WHERE publication_id = ?::uuid", publicationId).getFirst();
        assertThat(String.valueOf(card.get("pick_count"))).isEqualTo("3");
        assertThat(card.get("pick_by_difficulty")).isEqualTo("{\"EASY\":1,\"MEDIUM\":1,\"HARD\":1}");
        for (int i = 0; i < 6; i++) {
            String taker = taker(newTaker("t-pick-levels-" + i, "JP"));
            List<Integer> set = questionSet((String) start(taker, publicationId).get("attemptId"));
            // Questions 1-2 are easy, 3 medium, 4-5 hard: one of each.
            assertThat(set).hasSize(3).contains(3);
            assertThat(set.stream().filter(no -> no <= 2)).hasSize(1);
            assertThat(set.stream().filter(no -> no >= 4)).hasSize(1);
        }

        // A switch to a version without a medium question cannot give one: refused.
        String quizId = String.valueOf(query("SELECT quiz_id FROM qb_quiz_version_version WHERE version_id = ?::uuid "
            + "ORDER BY version_no DESC LIMIT 1", versionId).getFirst().get("quiz_id"));
        String questionId = String.valueOf(query("SELECT question_id FROM qb_question_version WHERE quiz_id = ?::uuid "
            + "AND difficulty = 'MEDIUM' ORDER BY version_no DESC LIMIT 1", quizId).getFirst().get("question_id"));
        run("QB_QUESTION_DIFFICULTY_SET", token, body("quizId", quizId, "questionIds", List.of(questionId),
            "difficulty", "HARD"));
        String noMedium = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        assertThat(codes(refused(PublicationVersions.SWITCH, token, body("publicationId", publicationId, "versionId",
            noMedium), 422))).containsExactly(PublishingCodes.SWITCH_PICK);
    }
}

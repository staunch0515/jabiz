package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.QuizSnapshot;
import com.jabiz.quizbuks.content.QuizSnapshots;
import com.jabiz.quizbuks.play.AttemptFinishing;
import com.jabiz.quizbuks.play.AttemptResults;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The result read later (docs/quizbuks/plans/Q20-result-review.md, gap A-3): a finished attempt of the caller's own
 * gives its questions as that attempt showed them, with the options chosen and the correct ones by the same
 * positions; in progress it gives its state only, and another taker's is not found. The option order never leaves
 * the server, and nothing before the finish tells the correct options apart (as {@code AnswerSecrecyIT}).
 */
class AttemptResultIT extends PlayItSupport {

    private Map<String, Object> result(String token, String attemptId) {
        return run(AttemptResults.RESULT, token, body("attemptId", attemptId));
    }

    private QuizSnapshot snapshotOf(String publicationId) {
        return QuizSnapshots.fromJson((String) query("SELECT content FROM qb_quiz_version_version "
            + "WHERE version_id = (SELECT version_id FROM qb_publication_card WHERE publication_id = ?::uuid)",
            publicationId).getFirst().get("content"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFinishedAttemptGivesItsQuestionsAndAnswersAsShown() {
        String sponsor = newSponsor("s-result");
        credit(sponsor, 1_000);
        String publicationId = livePublication(sponsor, "Result");
        String token = taker(u1());
        String attemptId = (String) start(token, publicationId).get("attemptId");

        // In progress: the state, nothing of the questions or the answers.
        Map<String, Object> before = result(token, attemptId);
        assertThat(before).containsEntry("status", "IN_PROGRESS").containsEntry("kind", "QUIZ")
            .containsEntry("questions", List.of()).containsEntry("result", List.of()).containsEntry("score", null);
        assertThat(before.get("deadline")).isNotNull();

        // Paris (1) right, Osaka (2) instead of Tokyo (1): 2 of 5.
        submit(token, attemptId, answers(true, false));
        Map<String, Object> after = result(token, attemptId);
        assertThat(after).containsOnlyKeys("attemptId", "publicationId", "name", "status", "kind", "deadline",
            "serverTime", "score", "fullScore", "passScore", "passed", "reward", "rewardStatus", "questions", "result");
        assertThat(after).containsEntry("attemptId", attemptId).containsEntry("publicationId", publicationId)
            .containsEntry("name", "Result")
            .containsEntry("status", "SUBMITTED").containsEntry("kind", "QUIZ").containsEntry("score", 2)
            .containsEntry("fullScore", 5).containsEntry("passScore", 3).containsEntry("passed", false)
            .containsEntry("rewardStatus", "NONE");

        // The questions as the attempt showed them: the same view as the start's, numbered by position.
        QuizSnapshot snapshot = snapshotOf(publicationId);
        List<Map<String, Object>> questions = (List<Map<String, Object>>) after.get("questions");
        assertThat(questions).hasSameSizeAs(snapshot.questions());
        for (int i = 0; i < questions.size(); i++) {
            QuizSnapshot.Question question = snapshot.questions().get(i);
            Map<String, Object> shown = questions.get(i);
            assertThat(shown).containsOnlyKeys("no", "stem", "image", "points", "options", "selection")
                .containsEntry("no", question.no()).containsEntry("stem", question.stem());
            List<Map<String, Object>> options = (List<Map<String, Object>>) shown.get("options");
            assertThat(options).allSatisfy(option -> assertThat(option).containsOnlyKeys("no", "text", "image"));
            assertThat(options).extracting(option -> option.get("no"))
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, options.size()).boxed().toList());
            // The texts in the attempt's order: the option at a position is the version's option shown there.
            for (QuizSnapshot.Option option : question.options()) {
                int position = position(attemptId, question.no(), option.no());
                assertThat(options.get(position - 1)).containsEntry("text", option.text());
            }
        }
        // The answers by the same positions: chosen, and correct.
        List<Map<String, Object>> rows = (List<Map<String, Object>>) after.get("result");
        assertThat(rows).extracting(row -> row.get("no")).containsExactly(1, 2);
        assertThat(rows.get(0)).containsEntry("selected", List.of(position(attemptId, 1, 1)))
            .containsEntry("correct", List.of(position(attemptId, 1, 1))).containsEntry("points", 2)
            .containsEntry("earned", 2);
        assertThat(rows.get(1)).containsEntry("selected", List.of(position(attemptId, 2, 2)))
            .containsEntry("correct", List.of(position(attemptId, 2, 1))).containsEntry("points", 3)
            .containsEntry("earned", 0);

        // Another taker's attempt is not found, and so is one that does not exist.
        refused(AttemptResults.RESULT, taker(u2()), body("attemptId", attemptId), 404);
        refused(AttemptResults.RESULT, token, body("attemptId", "0192f0a0-0000-7000-8000-000000000bad"), 404);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAttemptFinishedByTheTimeGivesItsSavedAnswers() {
        String sponsor = newSponsor("s-result-expired");
        credit(sponsor, 1_000);
        String publicationId = livePublication(sponsor, "Result expiry");
        String token = taker(u1());
        Map<String, Object> started = start(token, publicationId);
        String attemptId = (String) started.get("attemptId");
        run("QB_ATTEMPT_SAVE", token, body("attemptId", attemptId, "answers", shown(attemptId,
            List.of(body("questionNo", 1, "selected", List.of(1))))));
        clock.set(java.time.Instant.parse((String) started.get("deadline")).plus(Duration.ofMinutes(1)));
        systemRun(AttemptFinishing.EXPIRE, body("attemptId", attemptId));

        Map<String, Object> after = result(token, attemptId);
        assertThat(after).containsEntry("status", "EXPIRED").containsEntry("score", 2);
        assertThat((List<Map<String, Object>>) after.get("questions")).hasSize(2);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) after.get("result");
        assertThat(rows.get(0)).containsEntry("selected", List.of(position(attemptId, 1, 1)));
        assertThat(rows.get(1)).containsEntry("selected", List.of()).containsEntry("earned", 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSurveyGivesTheChoicesAndNoCorrectOptions() {
        String sponsor = newSponsor("s-result-survey");
        credit(sponsor, 1_000);
        String sponsorToken = sponsorToken(sponsor);
        String quizId = (String) run("QB_QUIZ_SAVE", sponsorToken, body("title", "Habits", "kind", "SURVEY",
            "timeLimitSec", 300)).get("quizId");
        run("QB_QUESTION_SAVE", sponsorToken, body("quizId", quizId, "stem", "How often?", "selection", "SINGLE",
            "options", List.of(body("text", "Daily"), body("text", "Weekly"))));
        run("QB_QUESTION_SAVE", sponsorToken, body("quizId", quizId, "stem", "Which ones?", "selection", "MULTIPLE",
            "options", List.of(body("text", "Tea"), body("text", "Coffee"), body("text", "Water"))));
        String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", sponsorToken, body("quizId", quizId))
            .get("versionId");
        String publicationId = livePublication(sponsor, "Habits", Map.of("versionId", versionId, "passScore", 2,
            "tiers", List.of(body("fromScore", 0, "toScore", 1, "reward", 0), body("fromScore", 2, "toScore", 2,
                "reward", 50)), "regions", List.of("GLOBAL")));
        String token = taker(u1());
        String attemptId = (String) start(token, publicationId).get("attemptId");
        submit(token, attemptId, List.of(body("questionNo", 1, "selected", List.of(2)),
            body("questionNo", 2, "selected", List.of(1, 3))));

        Map<String, Object> after = result(token, attemptId);
        assertThat(after).containsEntry("kind", "SURVEY").containsEntry("passScore", null)
            .containsEntry("passed", true).containsEntry("rewardStatus", "PAID");
        List<Map<String, Object>> questions = (List<Map<String, Object>>) after.get("questions");
        assertThat(questions).extracting(question -> question.get("selection")).containsExactly("SINGLE", "MULTIPLE");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) after.get("result");
        assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("correct", List.of()));
        assertThat(rows.get(0)).containsEntry("selected", List.of(position(attemptId, 1, 2)));
        assertThat((List<Integer>) rows.get(1).get("selected"))
            .containsExactlyInAnyOrder(position(attemptId, 2, 1), position(attemptId, 2, 3));
    }
}

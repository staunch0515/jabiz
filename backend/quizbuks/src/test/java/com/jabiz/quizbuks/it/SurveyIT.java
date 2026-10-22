package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.content.QuizSnapshot;
import com.jabiz.quizbuks.content.QuizSnapshots;
import com.jabiz.quizbuks.play.AttemptFinishing;
import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Surveys (docs/quizbuks/plans/Q12-survey.md, Q12a): no correct options, every question answered to submit, one
 * reward for a complete response, nothing for one the deadline cut short; the kind of a quiz never changes.
 */
class SurveyIT extends PlayItSupport {

    /**
     * A survey's version: question 1 single choice (Daily 1, Weekly 2), question 2 multiple choice (Tea 1, Coffee 2,
     * Water 3). The points and correctness sent are not taken: a point each, nothing correct.
     */
    String surveyVersion(String token, String title) {
        String quizId = (String) run("QB_QUIZ_SAVE", token, body("title", title, "kind", "SURVEY",
            "timeLimitSec", 300)).get("quizId");
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "How often?", "selection", "SINGLE",
            "points", 5, "options", List.of(body("text", "Daily", "correct", true), body("text", "Weekly"))));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Which ones?", "selection", "MULTIPLE",
            "options", List.of(body("text", "Tea"), body("text", "Coffee"), body("text", "Water"))));
        return (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
    }

    /** One reward of 50 for a complete response of the two questions. */
    static List<Object> surveyTiers() {
        return List.of(body("fromScore", 0, "toScore", 1, "reward", 0), body("fromScore", 2, "toScore", 2,
            "reward", 50));
    }

    String liveSurvey(String sponsorId, String name) {
        String versionId = surveyVersion(sponsorToken(sponsorId), name + " survey");
        return livePublication(sponsorId, name, Map.of("versionId", versionId, "passScore", 2, "tiers",
            surveyTiers(), "regions", List.of("GLOBAL")));
    }

    @Test
    void theVersionHasNoCorrectOptionsAPointEachAndItsKind() {
        String versionId = surveyVersion(sponsorToken(sponsorA()), "Habits");
        QuizSnapshot snapshot = QuizSnapshots.fromJson((String) query("SELECT content FROM qb_quiz_version_version "
            + "WHERE version_id = ?::uuid", versionId).getFirst().get("content"));
        assertThat(snapshot.survey()).isTrue();
        assertThat(snapshot.questions()).extracting(QuizSnapshot.Question::selection)
            .containsExactly("SINGLE", "MULTIPLE");
        assertThat(snapshot.questions()).extracting(QuizSnapshot.Question::points).containsExactly(1, 1);
        assertThat(snapshot.questions()).flatExtracting(QuizSnapshot.Question::options)
            .extracting(QuizSnapshot.Option::correct).containsOnly(false);
        assertThat(snapshot.fullScore()).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCompleteResponsePaysTheRewardWhateverTheAnswers() {
        credit(sponsorA(), 1_000);
        String publicationId = liveSurvey(sponsorA(), "Habits");
        assertThat(card(publicationId)).containsEntry("kind", "SURVEY");
        String token = taker(u1());
        Map<String, Object> started = start(token, publicationId);
        assertThat(started).containsEntry("kind", "SURVEY");
        assertThat((List<Map<String, Object>>) started.get("questions")).extracting(q -> q.get("selection"))
            .containsExactly("SINGLE", "MULTIPLE");
        String attemptId = (String) started.get("attemptId");

        // Not every question answered, or a single choice with two options: refused, nothing finished.
        assertThat(codes(refused("QB_ATTEMPT_SUBMIT", token, submission(attemptId, "answers",
            List.of(body("questionNo", 1, "selected", List.of(2)))), 422))).containsExactly(PlayCodes.INCOMPLETE);
        assertThat(codes(refused("QB_ATTEMPT_SUBMIT", token, submission(attemptId, "answers",
            List.of(body("questionNo", 1, "selected", List.of(1, 2)), body("questionNo", 2, "selected",
                List.of(3)))), 422))).containsExactly(PlayCodes.SINGLE_CHOICE);
        assertThat(attempt(attemptId)).containsEntry("status", "IN_PROGRESS");

        Map<String, Object> result = submit(token, attemptId, List.of(body("questionNo", 1, "selected", List.of(2)),
            body("questionNo", 2, "selected", List.of(1, 3))));
        assertThat(result).containsEntry("status", "SUBMITTED").containsEntry("kind", "SURVEY")
            .containsEntry("score", 2).containsEntry("fullScore", 2).containsEntry("passed", true)
            .containsEntry("rewardStatus", "PAID");
        assertThat(((Number) result.get("reward")).intValue()).isEqualTo(50);
        assertThat((List<Map<String, Object>>) result.get("questions"))
            .allSatisfy(q -> assertThat(q).containsEntry("correct", List.of()).containsEntry("earned", 1));
        assertBooksBalance();
    }

    @Test
    void aSurveyCutShortByItsDeadlinePaysNothing() {
        credit(sponsorA(), 1_000);
        String publicationId = liveSurvey(sponsorA(), "Cut short");
        String token = taker(u2());
        String attemptId = (String) start(token, publicationId).get("attemptId");
        run("QB_ATTEMPT_SAVE", token, body("attemptId", attemptId, "answers",
            shown(attemptId, List.of(body("questionNo", 1, "selected", List.of(1))))));
        clock.advance(Duration.ofSeconds(301).plus(com.jabiz.quizbuks.play.PlayLimits.SUBMIT_GRACE));
        assertThat(systemRun("QB_ATTEMPT_EXPIRE", body("attemptId", attemptId)))
            .isEqualTo(new AttemptFinishing.ExpireOutput(attemptId, "EXPIRED", true));
        assertThat(attempt(attemptId)).containsEntry("status", "EXPIRED").containsEntry("score", BigDecimal.ONE)
            .containsEntry("passed", false).containsEntry("reward_status", "NONE");
        assertBooksBalance();
    }

    @Test
    void aSurveysTiersRewardOnlyACompleteResponse() {
        String token = sponsorToken(sponsorA());
        String versionId = surveyVersion(token, "Partial");
        java.time.Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Partial", now, now.plus(Duration.ofDays(7)));
        save.put("passScore", 1);
        save.put("tiers", List.of(body("fromScore", 0, "toScore", 0, "reward", 0), body("fromScore", 1,
            "toScore", 2, "reward", 50)));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId), 422)))
            .contains(PublishingCodes.SURVEY_PASS_SCORE, PublishingCodes.SURVEY_TIERS);
    }

    @Test
    void aQuizKeepsItsKindAndOnlyASurveysQuestionsHaveASelection() {
        String token = sponsorToken(sponsorA());
        String survey = (String) run("QB_QUIZ_SAVE", token, body("title", "Kind", "kind", "SURVEY")).get("quizId");
        assertThat(codes(refused("QB_QUIZ_SAVE", token, body("quizId", survey, "title", "Kind", "kind", "QUIZ"),
            422))).containsExactly(ContentCodes.QUIZ_KIND_FIXED);
        // Its own kind, or none, may be given.
        run("QB_QUIZ_SAVE", token, body("quizId", survey, "title", "Kind 2", "kind", "SURVEY"));
        run("QB_QUIZ_SAVE", token, body("quizId", survey, "title", "Kind 3"));
        String quiz = newQuiz(token, "Scored");
        assertThat(codes(refused("QB_QUESTION_SAVE", token, body("quizId", quiz, "stem", "Q", "selection",
            "SINGLE"), 422))).containsExactly(ContentCodes.SELECTION_NOT_ALLOWED);
        // A copy is of the same kind.
        String copy = (String) run("QB_QUIZ_CLONE", token, body("quizId", survey)).get("quizId");
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + copy + "'").getFirst())
            .containsEntry("kind", "SURVEY");
    }

    @Test
    void aSurveyCompleteAtItsDeadlineIsPaidAndASingleChoiceWithTwoOptionsIsNotAnAnswer() {
        credit(sponsorA(), 1_000);
        String publicationId = liveSurvey(sponsorA(), "At the deadline");
        String complete = taker(u1());
        String doubled = taker(u2());
        String paid = (String) start(complete, publicationId).get("attemptId");
        String unpaid = (String) start(doubled, publicationId).get("attemptId");
        run("QB_ATTEMPT_SAVE", complete, body("attemptId", paid, "answers", shown(paid, List.of(
            body("questionNo", 1, "selected", List.of(2)), body("questionNo", 2, "selected", List.of(1))))));
        // Saving does not read the questions: two options for the single choice are kept, and count for nothing.
        run("QB_ATTEMPT_SAVE", doubled, body("attemptId", unpaid, "answers", shown(unpaid, List.of(
            body("questionNo", 1, "selected", List.of(1, 2)), body("questionNo", 2, "selected", List.of(3))))));
        clock.advance(Duration.ofSeconds(301).plus(com.jabiz.quizbuks.play.PlayLimits.SUBMIT_GRACE));
        systemRun("QB_ATTEMPT_EXPIRE", body("attemptId", paid));
        systemRun("QB_ATTEMPT_EXPIRE", body("attemptId", unpaid));
        assertThat(attempt(paid)).containsEntry("status", "EXPIRED").containsEntry("passed", true)
            .containsEntry("reward_status", "PAID");
        assertThat(attempt(unpaid)).containsEntry("status", "EXPIRED").containsEntry("score", BigDecimal.ONE)
            .containsEntry("passed", false).containsEntry("reward_status", "NONE");
        assertBooksBalance();
    }
}

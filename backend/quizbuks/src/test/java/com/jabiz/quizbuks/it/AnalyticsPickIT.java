package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q81 (D-Q81-6): the question figures of a publication drawing k of n questions count each question over the
 * completions it was given to, and each question's figures appear and change only after k new completions of that
 * question (the minimum group), not of the version.
 */
class AnalyticsPickIT extends AnalyticsItSupport {

    @Test
    @SuppressWarnings("unchecked")
    void eachQuestionIsCountedAndShownOverItsOwnCompletions() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        minGroup("3");
        String sponsor = newSponsor("s-pick-stats-" + run);
        credit(sponsor, 10_000);
        Instant h = nextHour();
        at(h);
        String token = sponsorToken(sponsor);
        String quizId = newQuiz(token, "Pick stats " + run);
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "Pick stats " + run, "timeLimitSec", 300));
        for (String country : List.of("France", "Japan", "Italy", "Germany", "Spain")) {
            run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of " + country + "?", "points", 1,
                "options", List.of(body("text", "Right", "correct", true), body("text", "Wrong"))));
        }
        String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        Map<String, Object> save = complete(versionId, "Pick stats " + run, h, h.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        save.put("maxWinners", 1000);
        save.put("pickCount", 2);
        save.put("passScore", 1);
        save.put("tiers", List.of(body("fromScore", 0, "toScore", 0, "reward", 0),
            body("fromScore", 1, "toScore", 2, "reward", 10)));
        String publication = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publication));
        // Eight takers each answer their two questions, all right (the right option is the version's first; the
        // support's submit turns the version's numbers into the attempt's positions).
        for (int i = 1; i <= 8; i++) {
            String taker = taker(newTaker("t-pick-stats-" + i + "-" + run, null));
            String attemptId = (String) start(taker, publication).get("attemptId");
            submit(taker, attemptId, List.of(body("questionNo", 1, "selected", List.of(1)),
                body("questionNo", 2, "selected", List.of(1))));
        }
        runAnalytics(h.plus(Duration.ofMinutes(5)));
        List<Map<String, Object>> rows = questions(token, publication);
        assertThat(rows).hasSize(5);
        // The completions of the questions add up to the sixteen given; a question is shown only over at least three
        // of its own completions, and then all right.
        long shownTotal = 0;
        int shown = 0;
        for (Map<String, Object> row : rows) {
            boolean suppressed = Boolean.TRUE.equals(row.get("suppressed"));
            if (suppressed) {
                assertThat(row.get("completions")).isNull();
            } else {
                long completions = count(row.get("completions"));
                assertThat(completions).isGreaterThanOrEqualTo(3);
                assertThat(decimal(row.get("correctRate")).stripTrailingZeros().toPlainString()).isEqualTo("1");
                shownTotal += completions;
                shown++;
            }
        }
        assertThat(shown).isBetween(1, 5);
        assertThat(shownTotal).isLessThanOrEqualTo(16);
        // Only the questions with three or more completions got a row; one with fewer has none (not even hidden).
        List<Map<String, Object>> stored = query("SELECT question_no, completions FROM qb_question_stat WHERE "
            + "publication_id = ?::uuid", publication);
        assertThat(stored).hasSize(shown);
        assertThat(stored).allSatisfy(row -> assertThat(count(row.get("completions"))).isGreaterThanOrEqualTo(3));
        assertThat(stored.stream().mapToLong(row -> count(row.get("completions"))).sum()).isEqualTo(shownTotal);
    }
}

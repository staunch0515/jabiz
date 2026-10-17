package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.play.QbPlay;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Options are named by their position in the attempt (Q10b, review M1): an attempt shows each question's options
 * numbered 1..k in its own order, answers name those positions, and the result names them again; the version's
 * numbers (the author's order) never reach the taker, neither in the outputs nor through the attempt's dataset.
 */
class AttemptOptionIdsIT extends PlayItSupport {

    @Test
    @SuppressWarnings("unchecked")
    void optionNumbersArePositionsAndTheResultMapsBack() {
        String sponsor = newSponsor("s-option-ids");
        credit(sponsor, 10_000);
        String publicationId = livePublication(sponsor, "Option ids");
        // A taker for whom the correct option of question 1 (Paris, the first the author wrote) is not shown first:
        // its version number would then name the wrong option.
        String token = null;
        String attemptId = null;
        Map<String, Object> started = null;
        for (int i = 0; i < 30 && attemptId == null; i++) {
            String candidate = taker(newTaker("t-option-ids-" + i, "JP"));
            Map<String, Object> output = start(candidate, publicationId);
            String id = (String) output.get("attemptId");
            if (position(id, 1, 1) != 1) {
                token = candidate;
                attemptId = id;
                started = output;
            }
        }
        assertThat(attemptId).as("an attempt that shows Paris second").isNotNull();

        List<Map<String, Object>> questions = (List<Map<String, Object>>) started.get("questions");
        List<Map<String, Object>> first = (List<Map<String, Object>>) questions.getFirst().get("options");
        assertThat(first).extracting(option -> option.get("no")).containsExactly(1, 2);
        assertThat(first).extracting(option -> option.get("text")).containsExactly("Lyon", "Paris");

        // The version's number of Paris (1) is Lyon here: a wrong answer, not an error.
        int tokyo = position(attemptId, 2, 1);
        Map<String, Object> saved = run("QB_ATTEMPT_SAVE", token, body("attemptId", attemptId, "answers",
            List.of(body("questionNo", 1, "selected", List.of(1)))));
        assertThat((List<Object>) saved.get("answers")).containsExactly(Map.of("questionNo", 1, "selected",
            List.of(1)));

        // Positions outside the question are refused, all at once; nothing is saved.
        Map<String, Object> problem = refused("QB_ATTEMPT_SAVE", token, body("attemptId", attemptId, "answers",
            List.of(body("questionNo", 1, "selected", List.of(0, 3)), body("questionNo", 2, "selected",
                List.of(-1)))), 422);
        assertThat(codes(problem)).containsOnly(PlayCodes.ANSWER_INVALID);
        assertThat(violations(problem)).extracting(v -> v.get("field")).containsExactly("answers[0].selected[0]",
            "answers[0].selected[1]", "answers[1].selected[0]");
        assertThat(attempt(attemptId)).containsEntry("answers", "[[1],[]]");

        // The taker's own record never carries the order that maps positions back.
        assertThat(rows(QbPlay.TAKER_ATTEMPTS, token)).singleElement()
            .satisfies(row -> assertThat(row).doesNotContainKey("optionOrder"));

        // Submitted: question 1 wrong (Lyon at 1), question 2 right; the result is in the attempt's positions.
        Map<String, Object> result = run("QB_ATTEMPT_SUBMIT", token, body("attemptId", attemptId, "publicationId",
            publicationId, "answers", List.of(body("questionNo", 2, "selected", List.of(tokyo)))));
        assertThat(result).containsEntry("score", 3);
        assertThat((List<Map<String, Object>>) result.get("questions")).containsExactly(
            Map.of("no", 1, "selected", List.of(1), "correct", List.of(2), "points", 2, "earned", 0),
            Map.of("no", 2, "selected", List.of(tokyo), "correct", List.of(tokyo), "points", 3, "earned", 3));
        assertThat(rows(QbPlay.TAKER_ATTEMPTS, token)).singleElement().satisfies(row -> {
            assertThat((String) row.get("result")).contains("{\"correct\":[2],\"earned\":0,\"no\":1,\"points\":2,"
                + "\"selected\":[1]}");
            assertThat(row).doesNotContainKey("optionOrder");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachAttemptNumbersItsOwnOrderAndKeepsIt() {
        String sponsor = newSponsor("s-option-ids-2");
        credit(sponsor, 10_000);
        String publicationId = livePublication(sponsor, "Option ids 2");
        java.util.Set<String> parisAt = new java.util.HashSet<>();
        for (int i = 0; i < 16; i++) {
            String token = taker(newTaker("t-option-own-" + i, "JP"));
            Map<String, Object> started = start(token, publicationId);
            String attemptId = (String) started.get("attemptId");
            List<Map<String, Object>> options = (List<Map<String, Object>>) ((List<Map<String, Object>>) started
                .get("questions")).getFirst().get("options");
            int shownAt = options.stream().filter(option -> "Paris".equals(option.get("text"))).findFirst()
                .map(option -> (Integer) option.get("no")).orElseThrow();
            assertThat(shownAt).isEqualTo(position(attemptId, 1, 1));
            parisAt.add(String.valueOf(shownAt));
            // Continued, the same numbers.
            assertThat(start(token, publicationId).get("questions")).isEqualTo(started.get("questions"));
        }
        // Sixteen attempts do not all show Paris at the same place (the chance of that is 1 in 32768).
        assertThat(parisAt).containsExactlyInAnyOrder("1", "2");
    }
}

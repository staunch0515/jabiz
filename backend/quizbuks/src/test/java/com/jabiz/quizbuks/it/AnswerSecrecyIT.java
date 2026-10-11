package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.QuizSnapshot;
import com.jabiz.quizbuks.content.QuizSnapshots;
import com.jabiz.quizbuks.play.QbPlay;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No correct answer before an attempt is finished (design section 9; docs/quizbuks/plans/Q5-answering.md, D-Q5-2):
 * every output a taker gets while answering (the start and continue outputs, the save, the feed, the detail and
 * tiers, the history, the taker's dataset) is compared with the version's snapshot: the questions and options are
 * there, in the attempt's order, and nothing tells the correct ones apart; a quiz's correctness only by its result.
 */
class AnswerSecrecyIT extends PlayItSupport {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    @SuppressWarnings("unchecked")
    void inProgressNothingRevealsTheCorrectOptions() {
        String sponsor = newSponsor("s-secret");
        credit(sponsor, 1_000);
        String publicationId = livePublication(sponsor, "Secret");
        String token = taker(u1());
        Map<String, Object> started = start(token, publicationId);
        String attemptId = (String) started.get("attemptId");
        Map<String, Object> saved = run("QB_ATTEMPT_SAVE", token, body("attemptId", attemptId, "answers",
            List.of(body("questionNo", 1, "selected", List.of(2)))));
        Map<String, Object> continued = start(token, publicationId);

        QuizSnapshot snapshot = QuizSnapshots.fromJson((String) query("SELECT content FROM qb_quiz_version_version "
            + "WHERE version_id = (SELECT version_id FROM qb_publication_card WHERE publication_id = ?::uuid)",
            publicationId).getFirst().get("content"));
        for (Map<String, Object> output : List.of(started, continued)) {
            assertThat(output).containsOnlyKeys("attemptId", "publicationId", "status", "startedAt", "deadline",
                "serverTime", "timeLimitSec", "currentIndex", "resumed", "questions", "answers");
            List<Map<String, Object>> questions = (List<Map<String, Object>>) output.get("questions");
            assertThat(questions).hasSameSizeAs(snapshot.questions());
            for (int i = 0; i < questions.size(); i++) {
                QuizSnapshot.Question question = snapshot.questions().get(i);
                Map<String, Object> shown = questions.get(i);
                assertThat(shown).containsOnlyKeys("no", "stem", "image", "points", "options")
                    .containsEntry("no", question.no()).containsEntry("stem", question.stem())
                    .containsEntry("points", question.points());
                List<Map<String, Object>> options = (List<Map<String, Object>>) shown.get("options");
                // Every option, each with its number, text and image only: the same for right and wrong ones.
                assertThat(options).allSatisfy(option -> assertThat(option).containsOnlyKeys("no", "text", "image"));
                List<Map<String, Object>> expected = new ArrayList<>();
                for (QuizSnapshot.Option option : question.options()) {
                    Map<String, Object> plain = new LinkedHashMap<>();
                    plain.put("no", option.no());
                    plain.put("text", option.text());
                    plain.put("image", null);
                    expected.add(plain);
                }
                assertThat(options).containsExactlyInAnyOrderElementsOf(expected);
            }
        }
        assertThat(saved).containsOnlyKeys("attemptId", "currentIndex", "answers", "deadline", "serverTime");

        // The pages a taker reads while answering: the feed, the detail and its tiers, the history, the attempts.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("publicationId", publicationId);
        params.put("at", clock.instant().toString());
        params.put("country", "JP");
        List<Object> pages = new ArrayList<>();
        pages.add(feed(token, "JP", Map.of()));
        pages.add(items("qb.app.publication", token, Map.of("params", params)));
        pages.add(items("qb.app.publication-tiers", token, Map.of("params", params)));
        pages.add(items("qb.app.history", token, Map.of()));
        List<Map<String, Object>> mine = rows(QbPlay.TAKER_ATTEMPTS, token);
        pages.add(mine);
        for (Object page : pages) {
            String text = json.writeValueAsString(page);
            assertThat(text).doesNotContain("correct").doesNotContain("\"questions\"");
        }
        assertThat(mine).singleElement().satisfies(row -> {
            assertThat(row.get("result")).isNull();
            assertThat(row.get("score")).isNull();
            assertThat(row).containsEntry("answers", "[[2],[]]");
        });
        // The version itself is not the taker's to read, through its dataset or the sponsors' templates.
        queryDataset(QbContent.defaultDataset(QbContent.VERSION), token).expectStatus().isForbidden();
        client.post().uri("/api/queries/qb.sponsor.quiz-version").contentType(org.springframework.http.MediaType
                .APPLICATION_JSON).header(org.springframework.http.HttpHeaders.AUTHORIZATION, token)
            .bodyValue(Map.of("params", Map.of("versionId", started.get("publicationId")))).exchange()
            .expectStatus().isForbidden();

        // Finished, the result names them.
        Map<String, Object> result = submit(token, attemptId, answers(true, true));
        List<Map<String, Object>> questions = (List<Map<String, Object>>) result.get("questions");
        assertThat(questions).extracting(q -> q.get("correct")).containsExactly(List.of(1), List.of(1));
        assertThat(rows(QbPlay.TAKER_ATTEMPTS, token)).singleElement()
            .satisfies(row -> assertThat((String) row.get("result")).contains("\"correct\":[1]"));
    }
}

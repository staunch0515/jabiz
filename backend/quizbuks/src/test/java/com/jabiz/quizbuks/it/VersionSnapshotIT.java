package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.QuizSnapshots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A version never changes (M-27; docs/quizbuks/plans/Q3-content.md): whatever happens to the draft, and when the
 * quiz is removed, its content and hash stay, and the stored text still hashes to the stored hash.
 */
class VersionSnapshotIT extends ContentItSupport {

    @AfterEach
    void onlyInserted() {
        assertOnlyInserted();
    }

    private static Map<String, Object> stored(Object versionId) {
        return query("SELECT content, content_hash, title, full_score FROM qb_quiz_version_version "
            + "WHERE version_id = ?::uuid", versionId).getFirst();
    }

    @Test
    void laterEditsAndRemovalLeaveTheVersionAsItWas() {
        String quizId = newQuiz(sponsorA(), "Fixed");
        String material = (String) run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "LINK",
            "title", "Source", "url", "https://example.com")).get("itemId");
        String q1 = question(sponsorA(), quizId, "One");
        String q2 = question(sponsorA(), quizId, "Two");
        Map<String, Object> v1 = run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quizId));
        Map<String, Object> before = stored(v1.get("versionId"));

        // The copy of v1.0 holds the same content: versioned in turn, it hashes the same.
        String copy = (String) run("QB_QUIZ_CLONE", sponsorA(), body("quizId", quizId, "versionNo", 1))
            .get("quizId");
        Map<String, Object> copied = run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", copy));
        assertThat(copied.get("contentHash")).isEqualTo(before.get("content_hash"));
        assertThat(stored(copied.get("versionId")).get("content")).isEqualTo(before.get("content"));

        run("QB_QUIZ_SAVE", sponsorA(), body("quizId", quizId, "title", "Changed"));
        run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "questionId", q1, "stem", "One, changed",
            "points", 9, "options", List.of(body("text", "a", "correct", true), body("text", "b"))));
        run("QB_QUESTION_DELETE", sponsorA(), body("quizId", quizId, "questionId", q2));
        run("QB_MATERIAL_DELETE", sponsorA(), body("quizId", quizId, "materialId", material));
        assertThat(run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quizId))).containsEntry("label", "v1.1");
        run("QB_QUIZ_DELETE", sponsorA(), body("quizId", quizId));

        Map<String, Object> after = stored(v1.get("versionId"));
        assertThat(after).isEqualTo(before);
        assertThat(QuizSnapshots.hashOfText((String) after.get("content"))).isEqualTo(after.get("content_hash"));
        assertThat(QuizSnapshots.fromJson((String) after.get("content")).questions()).hasSize(2);
        assertThat(query("SELECT count(*) AS n FROM qb_quiz_version_version WHERE version_id = ?::uuid",
            v1.get("versionId")).getFirst()).containsEntry("n", 1L);

        // Nobody changes or deletes a version through its dataset.
        for (String action : List.of("UPDATE", "DELETE")) {
            Map<String, Object> change = body("action", action, "id", v1.get("versionId"), "version", 1);
            if (action.equals("UPDATE")) {
                change.put("attributes", body("title", "Rewritten"));
            }
            int status = client.post().uri("/api/datasets/" + QbContent.defaultDataset(QbContent.VERSION)
                    + "/commit").contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, admin())
                .bodyValue(body("changes", List.of(change))).exchange().expectBody(MAP).returnResult().getStatus()
                .value();
            assertThat(status).as(action).isEqualTo(422);
        }
        assertThat(stored(v1.get("versionId"))).isEqualTo(before);
    }
}

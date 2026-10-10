package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only the owner sees and changes a quiz (M-21; docs/quizbuks/plans/Q3-content.md): another sponsor finds nothing
 * of it anywhere, nobody writes through the datasets, and content administrators read everything but change nothing.
 */
class ContentOwnerIT extends ContentItSupport {

    @AfterEach
    void onlyInserted() {
        assertOnlyInserted();
    }

    private record Quiz(String quizId, String questionId, String materialId) {}

    private Quiz quizOfA() {
        String quizId = newQuiz(sponsorA(), "A's quiz");
        String questionId = question(sponsorA(), quizId, "A's question");
        String materialId = (String) run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "ARTICLE",
            "title", "A's material", "body", "text")).get("itemId");
        run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quizId));
        return new Quiz(quizId, questionId, materialId);
    }

    @Test
    void anotherSponsorFindsNothingOfTheQuiz() {
        Quiz a = quizOfA();
        for (String entity : List.of(QbContent.QUIZ, QbContent.QUESTION, QbContent.MATERIAL)) {
            String id = switch (entity) {
                case QbContent.QUIZ -> a.quizId();
                case QbContent.QUESTION -> a.questionId();
                default -> a.materialId();
            };
            readEntity(QbContent.sponsorDataset(entity), id, sponsorA()).expectStatus().isOk();
            readEntity(QbContent.sponsorDataset(entity), id, sponsorB()).expectStatus().isNotFound();
        }
        assertThat(queryDataset(QbContent.sponsorDataset(QbContent.VERSION), sponsorB()))
            .extracting(ContentOwnerIT::quizIdOf).doesNotContain(a.quizId());
        assertThat(queryDataset(QbContent.sponsorDataset(QbContent.VERSION), sponsorA()))
            .extracting(ContentOwnerIT::quizIdOf).contains(a.quizId());
        assertThat(items("qb.sponsor.quizzes", sponsorB(), body())).extracting(row -> row.get("quizId"))
            .doesNotContain(a.quizId());
        assertThat(items("qb.sponsor.quiz-versions", sponsorB(), body("params", body("quizId", a.quizId()))))
            .isEmpty();

        // Every process answers as if the quiz did not exist.
        Map<String, Map<String, Object>> calls = Map.of(
            "QB_QUIZ_SAVE", body("quizId", a.quizId(), "title", "Mine now"),
            "QB_QUIZ_DELETE", body("quizId", a.quizId()),
            "QB_QUIZ_CLONE", body("quizId", a.quizId()),
            "QB_QUIZ_PUBLISH_VERSION", body("quizId", a.quizId()),
            "QB_QUESTION_SAVE", body("quizId", a.quizId(), "stem", "x"),
            "QB_QUESTION_DELETE", body("quizId", a.quizId(), "questionId", a.questionId()),
            "QB_QUESTION_REORDER", body("quizId", a.quizId(), "questionIds", List.of(a.questionId())),
            "QB_MATERIAL_SAVE", body("quizId", a.quizId(), "kind", "ARTICLE"),
            "QB_MATERIAL_DELETE", body("quizId", a.quizId(), "materialId", a.materialId()),
            "QB_MATERIAL_REORDER", body("quizId", a.quizId(), "materialIds", List.of(a.materialId())));
        calls.forEach((process, input) -> refused(process, sponsorB(), input, 404));

        // A's question or material in B's quiz is not B's.
        String quizOfB = newQuiz(sponsorB(), "B's quiz");
        assertThat(codes(refused("QB_QUESTION_SAVE", sponsorB(), body("quizId", quizOfB, "questionId",
            a.questionId(), "stem", "taken"), 422))).containsExactly(ContentCodes.CONTENT_NOT_IN_QUIZ);
        assertThat(codes(refused("QB_QUESTION_DELETE", sponsorB(), body("quizId", quizOfB, "questionId",
            a.questionId()), 422))).containsExactly(ContentCodes.CONTENT_NOT_IN_QUIZ);
        assertThat(codes(refused("QB_MATERIAL_SAVE", sponsorB(), body("quizId", quizOfB, "materialId",
            a.materialId(), "kind", "ARTICLE"), 422))).containsExactly(ContentCodes.CONTENT_NOT_IN_QUIZ);
        assertThat(codes(refused("QB_QUESTION_REORDER", sponsorB(), body("quizId", quizOfB, "questionIds",
            List.of(a.questionId())), 422))).containsExactly(ContentCodes.ORDER_MISMATCH);
        assertThat(latest("qb_question_version", "question_id", "question_id = '" + a.questionId() + "'"))
            .singleElement().satisfies(row -> assertThat(row.get("stem")).isEqualTo("A's question"));
    }

    @Test
    void nobodyWritesThroughTheDatasets() {
        Quiz a = quizOfA();
        Map<String, Object> change = body("action", "UPDATE", "id", a.quizId(), "version", 1,
            "attributes", body("title", "Changed"));
        for (String token : List.of(sponsorA(), admin())) {
            for (String entity : List.of(QbContent.QUIZ, QbContent.QUESTION, QbContent.VERSION)) {
                int status = commit(QbContent.defaultDataset(entity), token, change);
                assertThat(status).as(entity).isIn(403, 422);
                assertThat(commit(QbContent.sponsorDataset(entity), token, change)).as(entity).isIn(400, 403, 422);
            }
            assertThat(commit(QbContent.defaultDataset(QbContent.QUIZ), token, body("action", "INSERT",
                "attributes", body("title", "Sneaked in")))).isIn(403, 422);
        }
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + a.quizId() + "'").getFirst())
            .containsEntry("title", "A's quiz");
    }

    @Test
    void withoutThePermissionNothingIsAllowed() {
        Quiz a = quizOfA();
        String visitor = TestTokens.bearer(tokens, "sponsor-a", QbPermissions.COUNTRY_READ);
        call("QB_QUIZ_SAVE", visitor, body("title", "x")).expectStatus().isForbidden();
        call("QB_QUESTION_SAVE", visitor, body("quizId", a.quizId())).expectStatus().isForbidden();
        client.post().uri("/api/queries/qb.sponsor.quizzes").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, visitor).bodyValue(body()).exchange().expectStatus().isForbidden();
        readEntity(QbContent.sponsorDataset(QbContent.QUIZ), a.quizId(), visitor).expectStatus().isForbidden();
        // A sponsor does not read the administrators' datasets.
        readEntity(QbContent.defaultDataset(QbContent.QUIZ), a.quizId(), sponsorA()).expectStatus().isForbidden();
    }

    @Test
    void contentAdministratorsReadEverythingAndChangeNothing() {
        Quiz a = quizOfA();
        String quizOfB = newQuiz(sponsorB(), "B's quiz");
        run("QB_QUIZ_DELETE", sponsorA(), body("quizId", a.quizId()));

        assertThat(queryDataset(QbContent.defaultDataset(QbContent.QUIZ), contentAdmin()))
            .extracting(row -> row.get("id")).contains(a.quizId(), quizOfB);
        readEntity(QbContent.defaultDataset(QbContent.QUIZ), a.quizId(), contentAdmin()).expectStatus().isOk()
            .expectBody(MAP).value(row -> assertThat(row.toString()).contains("removed=true"));
        assertThat(queryDataset(QbContent.defaultDataset(QbContent.VERSION), contentAdmin()))
            .extracting(ContentOwnerIT::quizIdOf).contains(a.quizId());
        call("QB_QUIZ_SAVE", contentAdmin(), body("title", "x")).expectStatus().isForbidden();

        // Removed: its owner no longer sees it, and nothing more can be done with it.
        readEntity(QbContent.sponsorDataset(QbContent.QUIZ), a.quizId(), sponsorA()).expectStatus().isNotFound();
        refused("QB_QUIZ_CLONE", sponsorA(), body("quizId", a.quizId()), 404);
        refused("QB_QUIZ_DELETE", sponsorA(), body("quizId", a.quizId()), 404);
        assertThat(items("qb.sponsor.quizzes", sponsorA(), body())).extracting(row -> row.get("quizId"))
            .doesNotContain(a.quizId());
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec readEntity(String dataset,
        String id, String token) {
        return client.get().uri("/api/datasets/" + dataset + "/entities/" + id)
            .header(HttpHeaders.AUTHORIZATION, token).exchange();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> queryDataset(String dataset, String token) {
        Map<String, Object> page = client.post().uri("/api/datasets/" + dataset + "/query")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(body("limit", 100)).exchange().expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        return (List<Map<String, Object>>) page.get("items");
    }

    private static Object quizIdOf(Map<String, Object> row) {
        return ((Map<?, ?>) row.get("attributes")).get("quizId");
    }

    private int commit(String dataset, String token, Map<String, Object> change) {
        return client.post().uri("/api/datasets/" + dataset + "/commit").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token).bodyValue(body("changes", List.of(change))).exchange()
            .expectBody(MAP).returnResult().getStatus().value();
    }
}

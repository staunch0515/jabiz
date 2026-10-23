package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The administrators' overview (docs/quizbuks/plans/Q17-admin-overview.md): every sponsor's quizzes, removed ones
 * included, with their versions (without the content) and draft parts; every publication in every state, by quiz.
 * The content administrators read them; finance reads the publications but not the quizzes; a sponsor reads none.
 */
class AdminOverviewIT extends PublicationItSupport {

    @AfterEach
    void onlyInserted() {
        assertNothingUpdated();
    }

    private static Map<String, Object> where(String field, Object value) {
        return body("filters", List.of(body("field", field, "op", "eq", "value", value)), "limit", 100);
    }

    /** A name no other test of the run uses. */
    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private int templateStatus(String template, String token, Map<String, Object> request) {
        return client.post().uri("/api/queries/" + template).contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token).bodyValue(request).exchange().expectBody(MAP).returnResult()
            .getStatus().value();
    }

    /** A finance administrator's reading permissions, as {@code QB_ADMIN_FINANCE} has them. */
    private String financeReader() {
        return TestTokens.bearer(tokens, "finance-reader", QbPermissions.ADMIN_FINANCE_READ,
            QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.ADMIN_USERS_READ);
    }

    @Test
    void theContentAdministratorsSeeEverySponsorsQuizzes() {
        String title = unique("Overview");
        version(sponsorA(), title + " A");
        String draftOfB = newQuiz(sponsorB(), title + " B draft");
        String removedOfB = newQuiz(sponsorB(), title + " B removed");
        question(sponsorB(), removedOfB, "Gone?");
        run("QB_QUIZ_DELETE", sponsorB(), body("quizId", removedOfB));

        // Searched by title, across sponsors; removed quizzes stay, marked.
        List<Map<String, Object>> found = items("qb.admin.quizzes", reviewer(), body("params", body("q",
            title.toLowerCase()), "limit", 100));
        assertThat(found).extracting(row -> row.get("title"))
            .containsExactlyInAnyOrder(title + " A", title + " B draft", title + " B removed");
        assertThat(found).filteredOn(row -> row.get("quizId").equals(removedOfB)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("removed", true).containsEntry("ownerId", SPONSOR_B));
        assertThat(found).filteredOn(row -> row.get("title").equals(title + " A")).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("ownerId", SPONSOR_A).containsEntry("status", "VERSIONED")
                .containsEntry("versionLabel", "v1.0").containsEntry("questionCount", 2)
                .containsEntry("publicationCount", 0));
        // By sponsor, and only what is not removed.
        assertThat(items("qb.admin.quizzes", reviewer(), body("params", body("q", title), "filters", List.of(
            body("field", "ownerId", "op", "eq", "value", SPONSOR_B),
            body("field", "removed", "op", "eq", "value", false)))))
            .extracting(row -> row.get("quizId")).containsExactly(draftOfB);
    }

    @Test
    void aQuizsVersionsDraftAndPublicationsAreReadInEveryState() {
        String title = unique("Detail");
        String versionId = version(sponsorA(), title);
        String quizId = quizOf(versionId);
        run("QB_QUIZ_SAVE", sponsorA(), body("quizId", quizId, "title", title + " again", "timeLimitSec", 300));
        String second = (String) run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quizId)).get("versionId");
        // Changed since the second version: only the draft has the third question.
        question(sponsorA(), quizId, "Changed since?");
        String draft = draft(sponsorA(), versionId, title + " draft");
        String approved = draft(sponsorA(), second, title + " live");
        decide((String) submit(sponsorA(), approved).get("approvalRequestId"), "APPROVE", null);
        String rejected = draft(sponsorA(), second, title + " rejected");
        decide((String) submit(sponsorA(), rejected).get("approvalRequestId"), "REJECT", "Not this one");

        // Newest version first, without the content (it holds the answers; it is read by version).
        List<Map<String, Object>> versions = items("qb.admin.quiz-versions", reviewer(), body("params",
            body("quizId", quizId)));
        assertThat(versions).extracting(row -> row.get("versionId")).containsExactly(second, versionId);
        assertThat(versions.getFirst()).containsEntry("versionNo", 2).containsEntry("title", title + " again")
            .containsEntry("questionCount", 2)
            .doesNotContainKey("content");
        // The draft's parts, from the default datasets the page reads.
        assertThat(datasetRows("urn:jabiz:dataset:default:QbQuestion", "quizId", quizId, reviewer()))
            .extracting(row -> row.get("stem"))
            .containsExactlyInAnyOrder("Capital of France?", "Capital of Japan?", "Changed since?");
        assertThat(datasetRows("urn:jabiz:dataset:default:QbOption", "quizId", quizId, reviewer())).hasSize(6);
        for (String part : List.of("QbMaterial", "QbMaterialImage")) {
            assertThat(datasetRows("urn:jabiz:dataset:default:" + part, "quizId", quizId, reviewer())).as(part)
                .isEmpty();
        }
        // The quiz's own row by its key (its dataset is not filtered by key).
        assertThat(client.get().uri("/api/datasets/{dataset}/entities/{id}", "urn:jabiz:dataset:default:QbQuiz",
            quizId).header(HttpHeaders.AUTHORIZATION, reviewer()).exchange().expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody()).extractingByKey("attributes")
            .satisfies(attributes -> assertThat(((Map<?, ?>) attributes).get("title")).isEqualTo(title + " again"));

        // Every publication of the quiz, whatever its state; and the count on the quiz's row.
        List<Map<String, Object>> publications = items("qb.admin.publications", reviewer(), where("quizId", quizId));
        assertThat(publications).extracting(row -> row.get("publicationId"))
            .containsExactlyInAnyOrder(draft, approved, rejected);
        assertThat(publications).extracting(row -> row.get("status")).containsExactlyInAnyOrder(
            PublicationStatus.DRAFT, PublicationStatus.SCHEDULED, PublicationStatus.DRAFT);
        // A rejected publication is a draft again, with the reason it was sent back.
        assertThat(publications).filteredOn(row -> row.get("publicationId").equals(rejected)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("rejectReason", "Not this one"));
        assertThat(items("qb.admin.publications", reviewer(), body("filters", List.of(
            body("field", "quizId", "op", "eq", "value", quizId),
            body("field", "status", "op", "eq", "value", PublicationStatus.SCHEDULED)))))
            .extracting(row -> row.get("publicationId")).containsExactly(approved);
        assertThat(items("qb.admin.quizzes", reviewer(), where("quizId", quizId))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("publicationCount", 3));
    }

    @Test
    void financeReadsThePublicationsButNotTheQuizzesAndASponsorReadsNeither() {
        String quizId = quizOf(version(sponsorA(), unique("Access")));
        assertThat(templateStatus("qb.admin.publications", financeReader(), where("quizId", quizId))).isEqualTo(200);
        assertThat(templateStatus("qb.admin.quizzes", financeReader(), body())).isEqualTo(403);
        assertThat(templateStatus("qb.admin.quiz-versions", financeReader(), body("params", body("quizId", quizId))))
            .isEqualTo(403);
        for (String template : List.of("qb.admin.quizzes", "qb.admin.publications")) {
            assertThat(templateStatus(template, sponsorA(), body())).as(template).isEqualTo(403);
        }
        assertThat(templateStatus("qb.admin.quiz-versions", sponsorA(), body("params", body("quizId", quizId))))
            .isEqualTo(403);
    }

    /** The quiz a version is of. */
    private String quizOf(String versionId) {
        return String.valueOf(query("SELECT quiz_id FROM qb_quiz_version_version WHERE version_id = ?::uuid",
            versionId).getFirst().get("quiz_id"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> datasetRows(String dataset, String field, Object value, String token) {
        Map<String, Object> page = client.post().uri("/api/datasets/{dataset}/query", dataset)
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(body("filters", List.of(body("field", field, "op", "eq", "value", value)), "limit", 100))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) page.get("items")).stream()
            .map(item -> (Map<String, Object>) item.getOrDefault("attributes", item)).toList();
    }
}

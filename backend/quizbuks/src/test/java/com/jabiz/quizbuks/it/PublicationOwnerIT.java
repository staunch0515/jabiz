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
 * Publications are their sponsor's (docs/quizbuks/plans/Q4-publishing.md, 测试 PublicationOwnerIT): another sponsor
 * sees none of them and every process answers 404; nobody writes through a dataset; the processes need their
 * permission, the system's ones a permission no role holds; a sponsor cannot approve their own publication; the
 * administrators read everything and write nothing.
 */
class PublicationOwnerIT extends PublicationItSupport {

    @AfterEach
    void onlyInserted() {
        assertNothingUpdated();
    }

    @Test
    void anotherSponsorSeesNothingAndEveryProcessIs404() {
        String versionOfA = version(sponsorA(), "A's quiz");
        String draftOfA = draft(sponsorA(), versionOfA, "A's draft");
        String liveOfA = draft(sponsorA(), versionOfA, "A's publication");
        decide((String) submit(sponsorA(), liveOfA).get("approvalRequestId"), "APPROVE", null);

        List<Object> ofA = List.of(draftOfA, liveOfA);
        assertThat(items("qb.sponsor.publications", sponsorB(), body())).extracting(item -> item.get("publicationId"))
            .doesNotContainAnyElementsOf(ofA);
        assertThat(datasetItems("urn:jabiz:dataset:sponsor:QbPublication", sponsorB()))
            .extracting(PublicationOwnerIT::sponsorOf).containsOnly("sponsor-b");
        assertThat(datasetItems("urn:jabiz:dataset:sponsor:QbRewardTier", sponsorB()))
            .extracting(PublicationOwnerIT::sponsorOf).containsOnly("sponsor-b");
        assertThat(datasetItems("urn:jabiz:dataset:sponsor:QbPublication", sponsorA()))
            .extracting(item -> item.get("id")).containsAll(ofA);

        for (String id : List.of(draftOfA, liveOfA)) {
            refused("QB_PUBLICATION_SAVE", sponsorB(), body("publicationId", id, "name", "Mine now"), 404);
            refused("QB_PUBLICATION_DELETE", sponsorB(), body("publicationId", id), 404);
            refused("QB_PUBLICATION_COPY", sponsorB(), body("publicationId", id), 404);
            refused("QB_PUBLICATION_SUBMIT", sponsorB(), body("publicationId", id), 404);
            refused("QB_PUBLICATION_WITHDRAW", sponsorB(), body("publicationId", id), 404);
            refused("QB_PUBLICATION_PAUSE", sponsorB(), body("publicationId", id), 404);
            refused("QB_PUBLICATION_RESUME", sponsorB(), body("publicationId", id), 404);
        }
        // Nor A's version for a publication of B's.
        refused("QB_PUBLICATION_SAVE", sponsorB(), body("name", "Borrowed", "versionId", versionOfA), 404);
        String draftOfB = (String) run("QB_PUBLICATION_SAVE", sponsorB(), body("name", "B's")).get("publicationId");
        refused("QB_PUBLICATION_SAVE", sponsorB(), body("publicationId", draftOfB, "name", "B's",
            "versionId", versionOfA), 404);
        refused("QB_PUBLICATION_COPY", sponsorB(), body("publicationId", draftOfB, "versionId", versionOfA), 404);
        assertThat(status(liveOfA)).isEqualTo(PublicationStatus.SCHEDULED);
    }

    @Test
    void nobodyWritesThroughADataset() {
        String id = draft(sponsorA(), version(sponsorA(), "Datasets"), "Datasets");
        Map<String, Object> current = row(id);
        long version = ((Number) current.get("version_no")).longValue();
        Map<String, Object> change = Map.of("action", "UPDATE", "id", id, "version", version,
            "attributes", Map.of("name", "Changed"));
        for (String token : List.of(sponsorA(), admin())) {
            for (String dataset : List.of("urn:jabiz:dataset:sponsor:QbPublication",
                "urn:jabiz:dataset:default:QbPublication")) {
                int status = client.post().uri("/api/datasets/" + dataset + "/commit")
                    .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
                    .bodyValue(Map.of("changes", List.of(change))).exchange().expectBody(MAP).returnResult()
                    .getStatus().value();
                assertThat(status).as(dataset).isBetween(400, 499);
            }
        }
        assertThat(row(id)).containsEntry("name", "Datasets");
    }

    @Test
    void theProcessesNeedTheirPermissions() {
        String id = draft(sponsorA(), version(sponsorA(), "Permissions"), "Permissions");
        String visitor = TestTokens.bearer(tokens, "visitor", QbPermissions.CONTENT_WRITE);
        call("QB_PUBLICATION_SAVE", visitor, body("name", "No")).expectStatus().isForbidden();
        call("QB_PUBLICATION_SUBMIT", visitor, body("publicationId", id)).expectStatus().isForbidden();
        // The system's processes: not even a sponsor runs them.
        call("QB_PUBLICATION_TICK", sponsorA(), body("scheduledTime", clock.instant().toString()))
            .expectStatus().isForbidden();
        call("QB_PUBLICATION_ADVANCE", sponsorA(), body("publicationId", id)).expectStatus().isForbidden();
        call("QB_PUBLICATION_APPROVAL_RESULT", sponsorA(), body("subject", "qb.publication", "entityId", id,
            "status", "APPROVED")).expectStatus().isForbidden();
        // Nor a content administrator, who reviews: a review result comes only from the approval events.
        call("QB_PUBLICATION_APPROVAL_RESULT", reviewer(), body("subject", "qb.publication", "entityId", id,
            "status", "REJECTED", "requestId", UUID.randomUUID().toString())).expectStatus().isForbidden();
        call("QB_PUBLICATION_ADVANCE", reviewer(), body("publicationId", id)).expectStatus().isForbidden();
        // The administrators read; writing a publication is not theirs.
        call("QB_PUBLICATION_SAVE", reviewer(), body("name", "No")).expectStatus().isForbidden();
    }

    @Test
    void aSponsorCannotApproveTheirOwnPublication() {
        String id = draft(sponsorA(), version(sponsorA(), "Own review"), "Own review");
        String requestId = (String) submit(sponsorA(), id).get("approvalRequestId");
        String alsoReviewer = TestTokens.bearer(tokens, "sponsor-a", QbPermissions.PUBLICATION_WRITE,
            QbPermissions.REVIEW_PUBLICATION, "approval.decide");
        assertThat(codes(refused("APPROVAL_DECIDE", alsoReviewer, body("requestId", requestId, "decision",
            "APPROVE"), 422))).containsExactly("APPROVAL_OWN_REQUEST");
        assertThat(status(id)).isEqualTo(PublicationStatus.PENDING_REVIEW);
    }

    @Test
    void theAdministratorsReadEveryPublication() {
        String ofA = draft(sponsorA(), version(sponsorA(), "Admin A"), "Admin A");
        String ofB = draft(sponsorB(), version(sponsorB(), "Admin B"), "Admin B");
        List<Object> ids = datasetItems("urn:jabiz:dataset:default:QbPublication", reviewer()).stream()
            .map(item -> item.get("id")).toList();
        assertThat(ids).contains(ofA, ofB);
        assertThat(datasetItems("urn:jabiz:dataset:default:QbRewardTier", reviewer())).hasSizeGreaterThanOrEqualTo(6);
        assertThat(items("qb.admin.publications", reviewer(), body())).extracting(item -> item.get("publicationId"))
            .contains(ofA, ofB);
        // A sponsor does not read the administrators' views.
        client.post().uri("/api/queries/qb.admin.publications").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, sponsorA()).bodyValue(body()).exchange().expectStatus().isForbidden();
        client.post().uri("/api/datasets/urn:jabiz:dataset:default:QbPublication/query")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, sponsorA())
            .bodyValue(Map.of()).exchange().expectStatus().isForbidden();
        assertThat(UUID.fromString(ofA)).isNotNull();
    }

    private static Object sponsorOf(Map<String, Object> item) {
        return ((Map<?, ?>) item.get("attributes")).get("sponsorId");
    }

    /** The rows of a dataset as the caller reads them. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> datasetItems(String dataset, String token) {
        Map<String, Object> page = client.post().uri("/api/datasets/" + dataset + "/query")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(Map.of("limit", 100)).exchange().expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        return (List<Map<String, Object>>) page.get("items");
    }
}

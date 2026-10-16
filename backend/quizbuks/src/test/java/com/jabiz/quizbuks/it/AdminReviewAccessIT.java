package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.QuizSnapshots;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.publishing.PublicationApprovals;
import com.jabiz.quizbuks.publishing.PublicationReadiness;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.publishing.TierCoverage;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the admin extension's review pages read (docs/quizbuks/plans/Q9a-admin-extension.md): a content
 * administrator, with the permissions of {@code QB_ADMIN_CONTENT}, reads another sponsor's pending publication, its
 * frozen version (correct answers included), its tiers, the sponsor's onboarding and every content file; what they
 * read is what the approval is bound to. A finance administrator ({@code QB_ADMIN_FINANCE}) reads the publication but
 * neither the version nor the content files; a sponsor reads none of the administrators' datasets. The user summary
 * (P-01) looks one user up in {@code qb.admin.users} by key.
 */
class AdminReviewAccessIT extends AccountItSupport {

    private static final String PUBLICATIONS = QbPublications.defaultDataset(QbPublications.PUBLICATION);
    private static final String VERSIONS = QbContent.defaultDataset(QbContent.VERSION);
    private static final String TIERS = QbPublications.defaultDataset(QbPublications.TIER);
    private static final String SPONSORS = QbAccounts.defaultDataset(QbAccounts.SPONSOR);

    @AfterEach
    void onlyInserted() {
        assertAccountsOnlyInserted();
    }

    /** A pending publication of a real sponsor's: its sponsor's id, token, files and the submission's output. */
    private record Pending(String sponsorId, String sponsorToken, String publicationId, String requestId,
        String cover, String picture, String pdf, String avatar) {}

    private Pending pendingPublication(String name) {
        String sponsorId = createUser(unique(name));
        assignRole(sponsorId, QbRoles.SPONSOR);
        String token = tokenOf(sponsorId, QbRoles.SPONSOR);
        String avatar = uploadAs(token, QbAccounts.AVATAR_POLICY, FileSamples.jpeg(200, 200), "me.jpg", "image/jpeg");
        Map<String, Object> applied = run("QB_SPONSOR_APPLY", token, body("displayName", name, "company",
            name + " Co", "intro", "We ask **questions**.", "avatar", avatar, "acceptTerms", true));
        run("APPROVAL_DECIDE", sponsorReviewer(), body("requestId", applied.get("approvalRequestId"),
            "decision", "APPROVE"));
        deliverEvents();

        String cover = uploadAs(token, QbContent.IMAGE_POLICY, FileSamples.jpeg(400, 300), "cover.jpg", "image/jpeg");
        String picture = uploadAs(token, QbContent.IMAGE_POLICY, FileSamples.jpeg(80, 60), "small.jpg", "image/jpeg");
        String pdf = uploadAs(token, QbContent.PDF_POLICY, FileSamples.pdf(), "notes.pdf", "application/pdf");
        String quizId = newQuiz(token, name + " quiz");
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", name + " quiz", "intro", "Read *first*.",
            "cover", cover, "timeLimitSec", 300));
        run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "PDF", "title", "Notes", "pdf", pdf));
        run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "IMAGES", "title", "Pictures",
            "images", List.of(body("image", picture, "caption", "A small one"))));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?", "points", 2,
            "image", picture, "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?", "points", 3,
            "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
        String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        Instant now = clock.instant();
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, complete(versionId, name + " run",
            now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(8)))).get("publicationId");
        Map<String, Object> submitted = submit(token, publicationId);
        assertThat(status(publicationId)).isEqualTo(PublicationStatus.PENDING_REVIEW);
        return new Pending(sponsorId, token, publicationId, (String) submitted.get("approvalRequestId"), cover,
            picture, pdf, avatar);
    }

    private String uploadAs(String token, String policy, byte[] content, String fileName, String contentType) {
        return (String) upload(policy, content, fileName, contentType, token).expectStatus().isCreated()
            .expectBody(MAP).returnResult().getResponseBody().get("fileId");
    }

    private String contentAdministrator() {
        return tokenOf("qb-content-admin", QbRoles.ADMIN_CONTENT);
    }

    private String financeAdministrator() {
        return tokenOf("qb-finance-admin", QbRoles.ADMIN_FINANCE);
    }

    private int readStatus(String dataset, String id, String token) {
        return client.get().uri("/api/datasets/{dataset}/entities/{id}", dataset, id)
            .header(HttpHeaders.AUTHORIZATION, token).exchange().expectBody(MAP).returnResult().getStatus().value();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String dataset, String id, String token) {
        Map<String, Object> instance = client.get().uri("/api/datasets/{dataset}/entities/{id}", dataset, id)
            .header(HttpHeaders.AUTHORIZATION, token).exchange().expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        return (Map<String, Object>) instance.get("attributes");
    }

    private int queryStatus(String dataset, String field, Object value, String token) {
        return client.post().uri("/api/datasets/{dataset}/query", dataset).contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token).bodyValue(filteredBy(field, value)).exchange().expectBody(MAP)
            .returnResult().getStatus().value();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rowsWhere(String dataset, String field, Object value, String token) {
        Map<String, Object> page = client.post().uri("/api/datasets/{dataset}/query", dataset)
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(filteredBy(field, value)).exchange().expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        return ((List<Map<String, Object>>) page.get("items")).stream()
            .map(item -> (Map<String, Object>) item.get("attributes")).toList();
    }

    private static Map<String, Object> filteredBy(String field, Object value) {
        return Map.of("filters", List.of(Map.of("field", field, "op", "eq", "value", value)), "limit", 100);
    }

    private int fileStatus(String fileId, String token) {
        return client.get().uri("/api/files/{id}/content", fileId).header(HttpHeaders.AUTHORIZATION, token)
            .exchange().expectBody(byte[].class).returnResult().getStatus().value();
    }

    @Test
    void aContentAdministratorReadsEverythingOfAnotherSponsorsPendingPublication() {
        Pending pending = pendingPublication("Rhea");
        String admin = contentAdministrator();

        Map<String, Object> publication = read(PUBLICATIONS, pending.publicationId(), admin);
        assertThat(publication).containsEntry("status", PublicationStatus.PENDING_REVIEW)
            .containsEntry("sponsorId", pending.sponsorId())
            .containsEntry("approvalRequestId", pending.requestId());
        Map<String, Object> version = read(VERSIONS, (String) publication.get("versionId"), admin);
        String content = (String) version.get("content");
        assertThat(content).contains("Capital of France?", "Paris", "\"correct\":true", pending.cover(),
            pending.picture(), pending.pdf());
        assertThat(QuizSnapshots.hashOfText(content)).isEqualTo(version.get("contentHash"));
        assertThat(rowsWhere(TIERS, "publicationId", pending.publicationId(), admin)).hasSize(3);
        assertThat(rowsWhere(SPONSORS, "userId", pending.sponsorId(), admin)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("company", "Rhea Co")
                .containsEntry("intro", "We ask **questions**.").containsEntry("avatar", pending.avatar()));
        for (String file : List.of(pending.cover(), pending.picture(), pending.pdf(), pending.avatar())) {
            assertThat(fileStatus(file, admin)).as(file).isEqualTo(200);
        }
        // The queue's row, found by the publication's key: without the sponsor's money.
        assertThat(items("qb.admin.publications", admin, body("filters", List.of(body("field", "publicationId",
            "op", "eq", "value", pending.publicationId())), "limit", 10))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("approvalRequestId", pending.requestId())
                .doesNotContainKeys(MONEY_OF_PUBLICATIONS));
    }

    /** The sponsor's funds, which only the finance administrators' template carries. */
    private static final String[] MONEY_OF_PUBLICATIONS = {"fundsShort", "pendingCount", "pendingAmount"};
    /** The amounts of an attempt, likewise. */
    private static final String[] MONEY_OF_ATTEMPTS = {"reward", "fee", "ledgerTransactionId"};

    /** The result columns of a template as the catalog lists them to the caller; empty when it is not listed. */
    @SuppressWarnings("unchecked")
    private List<String> columns(String template, String token) {
        List<Map<String, Object>> catalog = client.get().uri("/api/meta/queries")
            .header(HttpHeaders.AUTHORIZATION, token).exchange().expectStatus().isOk()
            .expectBodyList(Map.class).returnResult().getResponseBody().stream()
            .map(entry -> (Map<String, Object>) entry).toList();
        return catalog.stream().filter(entry -> template.equals(entry.get("id"))).findFirst()
            .map(entry -> {
                Object results = entry.get("results");
                if (results instanceof Map<?, ?> map) {
                    return map.keySet().stream().map(String::valueOf).toList();
                }
                return ((List<Object>) results).stream()
                    .map(r -> r instanceof Map<?, ?> m ? String.valueOf(m.get("name")) : String.valueOf(r)).toList();
            })
            .orElse(List.of());
    }

    private int templateStatus(String template, String token, Map<String, Object> request) {
        return client.post().uri("/api/queries/" + template).contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token).bodyValue(request).exchange().expectBody(MAP).returnResult()
            .getStatus().value();
    }

    @Test
    void aContentAdministratorGetsNoMoneyByEitherPath() {
        Pending pending = pendingPublication("Vida");
        String admin = contentAdministrator();
        String finance = financeAdministrator();
        // The templates content administrators read carry no money; their columns say so whatever the rows.
        assertThat(columns("qb.admin.publications", admin)).contains("publicationId", "status")
            .doesNotContain(MONEY_OF_PUBLICATIONS);
        assertThat(columns("qb.admin.attempts", admin)).contains("attemptId", "rewardStatus")
            .doesNotContain(MONEY_OF_ATTEMPTS);
        // The money is in finance-only templates.
        Map<String, Object> byPublication = body("filters", List.of(body("field", "publicationId", "op", "eq",
            "value", pending.publicationId())), "limit", 10);
        assertThat(templateStatus("qb.admin.publication-funds", admin, byPublication)).isEqualTo(403);
        assertThat(templateStatus("qb.admin.attempt-rewards", admin, body())).isEqualTo(403);
        assertThat(items("qb.admin.publication-funds", finance, byPublication)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("fundsShort", false).containsEntry("pendingCount", 0));
        assertThat(templateStatus("qb.admin.attempt-rewards", finance, body("filters", List.of(body("field",
            "userId", "op", "eq", "value", pending.sponsorId()))))).isEqualTo(200);
        assertThat(columns("qb.admin.attempt-rewards", finance)).contains(MONEY_OF_ATTEMPTS);
    }

    @Test
    void whatTheReviewerReadsIsWhatTheApprovalIsBoundTo() {
        Pending pending = pendingPublication("Iris");
        String admin = contentAdministrator();
        Map<String, Object> publication = read(PUBLICATIONS, pending.publicationId(), admin);
        Map<String, Object> version = read(VERSIONS, (String) publication.get("versionId"), admin);
        List<TierCoverage.Tier> tiers = rowsWhere(TIERS, "publicationId", pending.publicationId(), admin).stream()
            .map(row -> new TierCoverage.Tier(number(row.get("fromScore")).intValue(),
                number(row.get("toScore")).intValue(), number(row.get("reward"))))
            .toList();
        PublicationReadiness.Draft draft = new PublicationReadiness.Draft(
            UUID.fromString((String) publication.get("versionId")), (String) publication.get("name"),
            (String) publication.get("regions"), Instant.parse((String) publication.get("startAt")),
            Instant.parse((String) publication.get("endAt")), integer(publication.get("timeLimitSec")),
            integer(publication.get("passScore")), number(publication.get("budgetCap")),
            integer(publication.get("maxWinners")));
        String shown = PublicationApprovals.hash(PublicationApprovals.content(draft,
            (String) version.get("contentHash"), tiers));
        Object bound = query("SELECT content_hash FROM (SELECT DISTINCT ON (request_id) * FROM "
            + "sys_approval_request_version WHERE request_id = ? ORDER BY request_id, effect_start_time DESC, "
            + "version_no DESC) r", UUID.fromString(pending.requestId())).getFirst().get("content_hash");
        assertThat(String.valueOf(bound).trim()).isEqualTo(shown);
    }

    @Test
    void aFinanceAdministratorDoesNotReadTheQuizContent() {
        Pending pending = pendingPublication("Nora");
        String finance = financeAdministrator();
        Map<String, Object> publication = read(PUBLICATIONS, pending.publicationId(), finance);
        assertThat(readStatus(VERSIONS, (String) publication.get("versionId"), finance)).isEqualTo(403);
        assertThat(queryStatus(VERSIONS, "quizId", publication.get("quizId"), finance)).isEqualTo(403);
        for (String file : List.of(pending.cover(), pending.picture(), pending.pdf())) {
            assertThat(fileStatus(file, finance)).as(file).isIn(403, 404);
        }
        // Nor does the finance administrator decide the review: the rule's level needs qb.review.publication.
        var refused = call("APPROVAL_DECIDE", finance, body("requestId", pending.requestId(), "decision", "APPROVE"))
            .expectBody(MAP).returnResult();
        assertThat(refused.getStatus().value()).isBetween(400, 499);
        assertThat(status(pending.publicationId())).isEqualTo(PublicationStatus.PENDING_REVIEW);
    }

    @Test
    void aSponsorReadsNoneOfTheAdministratorsDatasets() {
        Pending pending = pendingPublication("Opal");
        Map<String, Object> publication = read(PUBLICATIONS, pending.publicationId(), contentAdministrator());
        String versionId = (String) publication.get("versionId");
        // Not even their own rows: a sponsor reads publications through the sponsor datasets only.
        for (String token : List.of(pending.sponsorToken(), sponsorB())) {
            assertThat(readStatus(PUBLICATIONS, pending.publicationId(), token)).isEqualTo(403);
            assertThat(readStatus(VERSIONS, versionId, token)).isEqualTo(403);
            assertThat(queryStatus(TIERS, "publicationId", pending.publicationId(), token)).isEqualTo(403);
            assertThat(queryStatus(SPONSORS, "userId", pending.sponsorId(), token)).isEqualTo(403);
            client.post().uri("/api/queries/qb.admin.users").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, token).bodyValue(body("params", body("userId",
                    pending.sponsorId()))).exchange().expectStatus().isForbidden();
        }
    }

    @Test
    void theUserSummaryLooksOneUserUpByKey() {
        String first = createUser(unique("Una"));
        String second = createUser(unique("Una"));
        String reader = contentAdministrator();
        for (String userId : List.of(first, second)) {
            assertThat(items("qb.admin.users", reader, body("params", body("userId", userId), "limit", 10)))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("userId", userId));
        }
        assertThat(items("qb.admin.users", reader, body("params", body("userId", UUID.randomUUID().toString()),
            "limit", 10))).isEmpty();
        // Not a user id at all: refused as a parameter, not run.
        client.post().uri("/api/queries/qb.admin.users").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, reader).bodyValue(body("params", body("userId", "not-a-key")))
            .exchange().expectStatus().isBadRequest();
        // The finance sections of the summary are the finance administrator's only.
        for (String template : List.of("qb.admin.transfers", "qb.admin.topups", "qb.admin.payout-accounts",
            "qb.admin.attempt-rewards")) {
            client.post().uri("/api/queries/" + template).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, reader).bodyValue(body()).exchange().expectStatus()
                .isForbidden();
            client.post().uri("/api/queries/" + template).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, financeAdministrator()).bodyValue(body("filters",
                    List.of(body("field", template.equals("qb.admin.topups") ? "sponsorId" : "userId", "op", "eq",
                        "value", first)))).exchange().expectStatus().isOk();
        }
    }

    private static BigDecimal number(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private static Integer integer(Object value) {
        return value == null ? null : number(value).intValueExact();
    }
}

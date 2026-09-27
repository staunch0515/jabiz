package com.jabiz.app.it;

import com.jabiz.app.it.fixture.ItContentFixtures;
import com.jabiz.app.it.fixture.ItFixtures;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.app.it.fixture.ItContentFixtures.JP_DATASET;
import static com.jabiz.app.it.fixture.ItContentFixtures.KR_DATASET;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Content authoring on an entity that is not temporal (docs/design/16-content-authoring.md): process-only fields,
 * multilingual texts through the dataset API, lookups and labels within the scope, the process catalog's actions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ContentAuthoringIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
        new ParameterizedTypeReference<>() {};

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    private String writer() {
        return TestTokens.bearer(tokens, "it-editor", ItContentFixtures.READ, ItContentFixtures.WRITE, "entity.write");
    }

    private WebTestClient.ResponseSpec post(String path, String bearer, Object body) {
        return client.post().uri(path).header(HttpHeaders.AUTHORIZATION, bearer)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec commit(String dataset, Map<String, Object> change) {
        return post("/api/datasets/" + dataset + "/commit", writer(), Map.of("changes", List.of(change)));
    }

    private static Map<String, Object> insert(String id, Map<String, Object> attributes) {
        Map<String, Object> all = new HashMap<>(attributes);
        all.put("articleId", id);
        return Map.of("action", "INSERT", "attributes", all);
    }

    private static String newId() {
        return "A-" + UUID.randomUUID();
    }

    private Map<String, Object> created(String dataset, String id, Map<String, Object> attributes) {
        return commit(dataset, insert(id, attributes)).expectStatus().isOk().expectBody(LIST).returnResult()
            .getResponseBody().getFirst();
    }

    @SuppressWarnings("unchecked")
    private static List<String> codes(Map<String, Object> problem) {
        return ((List<Map<String, Object>>) problem.get("violations")).stream()
            .map(v -> v.get("field") + ":" + v.get("ruleCode")).toList();
    }

    private Map<String, Object> problem(WebTestClient.ResponseSpec response, int status) {
        return response.expectStatus().isEqualTo(status).expectBody(MAP).returnResult().getResponseBody();
    }

    private Map<String, Object> read(String id) {
        return client.get().uri("/api/datasets/" + JP_DATASET + "/entities/" + id)
            .header(HttpHeaders.AUTHORIZATION, writer()).exchange().expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> instance) {
        return (Map<String, Object>) instance.get("attributes");
    }

    @Test
    void insertsFillProcessOnlyFieldsWithTheInitialStateAndTheScope() {
        String id = newId();
        Map<String, Object> article = created(JP_DATASET, id, Map.of("title", "Filled"));
        assertThat(attributes(article)).containsEntry("status", "DRAFT").containsEntry("region", "JP");
        assertThat(attributes(read(id))).containsEntry("status", "DRAFT").containsEntry("region", "JP");
    }

    @Test
    void theDatasetApiRefusesProcessOnlyFieldsEvenWhenNull() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("title", "Refused");
        attributes.put("status", "PUBLISHED");
        attributes.put("note", null);
        assertThat(codes(problem(commit(JP_DATASET, insert(newId(), attributes)), 400)))
            .containsExactlyInAnyOrder("status:PROCESS_ONLY_FIELD", "note:PROCESS_ONLY_FIELD");

        String id = newId();
        created(JP_DATASET, id, Map.of("title", "Kept"));
        assertThat(codes(problem(commit(JP_DATASET, Map.of("action", "UPDATE", "id", id, "version", 1,
            "attributes", Map.of("note", "sneaky"))), 400))).containsExactly("note:PROCESS_ONLY_FIELD");
        assertThat(attributes(read(id))).containsEntry("note", null);
    }

    @Test
    void theGenericEntityProcessesRefuseProcessOnlyFields() {
        assertThat(codes(problem(post("/api/entities/ItArticle", writer(),
            Map.of("articleId", newId(), "title", "Generic", "status", "PUBLISHED")), 400)))
            .containsExactly("status:PROCESS_ONLY_FIELD");

        String id = newId();
        created(JP_DATASET, id, Map.of("title", "Generic update"));
        assertThat(codes(problem(client.patch().uri("/api/entities/ItArticle/" + id)
            .header(HttpHeaders.AUTHORIZATION, writer()).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("version", 1, "attributes", Map.of("region", "KR"))).exchange(), 400)))
            .containsExactly("region:PROCESS_ONLY_FIELD");
    }

    @Test
    void processesWriteThem() {
        String id = newId();
        created(JP_DATASET, id, Map.of("title", "Publish me"));
        Map<String, Object> result = post("/api/processes/" + ItContentFixtures.PUBLISH + "/1", writer(),
            Map.of("articleId", id, "note", "looks good")).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(attributes(read(id))).containsEntry("status", "PUBLISHED").containsEntry("note", "looks good");

        assertThat(result).containsKey("processSeqId");

        // The action's condition (status DRAFT) only tells clients when to offer it; the server does not apply it:
        // staying PUBLISHED is no transition, so the lifecycle lets the run through.
        post("/api/processes/" + ItContentFixtures.PUBLISH + "/1", writer(), Map.of("articleId", id, "note", "again"))
            .expectStatus().isOk();
        assertThat(attributes(read(id))).containsEntry("status", "PUBLISHED").containsEntry("note", "again");
    }

    @Test
    @SuppressWarnings("unchecked")
    void multilingualTextsAreStoredByLanguageAndValidated() {
        String id = newId();
        created(JP_DATASET, id, Map.of("title", "Texts", "headline", Map.of("zh", "标题", "ja", " ", "en", "Title")));
        Map<String, Object> headline = (Map<String, Object>) attributes(read(id)).get("headline");
        assertThat(headline).containsExactly(Map.entry("zh", "标题"), Map.entry("en", "Title"));

        assertThat(codes(problem(commit(JP_DATASET, insert(newId(), Map.of("title", "x",
            "headline", Map.of("zh", "标题")))), 400))).containsExactly("headline:TRANSLATION_REQUIRED");
        assertThat(codes(problem(commit(JP_DATASET, insert(newId(), Map.of("title", "x",
            "headline", Map.of("en", "x".repeat(21))))), 400))).containsExactly("headline:TOO_LONG");
        assertThat(codes(problem(commit(JP_DATASET, insert(newId(), Map.of("title", "x",
            "headline", Map.of("fr", "Titre")))), 400))).containsExactly("headline:INVALID_VALUE");
        assertThat(codes(problem(commit(JP_DATASET, insert(newId(), Map.of("title", "x",
            "headline", "Title"))), 400))).containsExactly("headline:INVALID_VALUE");

        String blank = newId();
        created(JP_DATASET, blank, Map.of("title", "Blank", "headline", Map.of("en", "  ")));
        assertThat(attributes(read(blank))).containsEntry("headline", null);
    }

    @Test
    void lookupsMatchTheDisplayFieldWithinTheScope() {
        String prefix = "L" + UUID.randomUUID().toString().substring(0, 8);
        String sale = newId();
        String under = newId();
        String other = newId();
        created(JP_DATASET, sale, Map.of("title", prefix + " Alpha 50% off"));
        created(JP_DATASET, under, Map.of("title", prefix + " alpha_beta"));
        created(JP_DATASET, other, Map.of("title", prefix + " Gamma"));
        created(KR_DATASET, newId(), Map.of("title", prefix + " Alpha Seoul"));

        assertThat(lookup(JP_DATASET, prefix + " ALPHA")).extracting(m -> m.get("id"))
            .containsExactly(sale, under);
        assertThat(lookup(JP_DATASET, prefix + " Alpha 50%")).extracting(m -> m.get("label"))
            .containsExactly(prefix + " Alpha 50% off");
        assertThat(lookup(JP_DATASET, "%")).extracting(m -> m.get("id")).contains(sale).doesNotContain(under, other);
        assertThat(lookup(JP_DATASET, "a_b")).extracting(m -> m.get("id")).containsExactly(under);
        assertThat(lookup(KR_DATASET, prefix)).extracting(m -> m.get("label")).containsExactly(prefix + " Alpha Seoul");
    }

    @Test
    void lookupsReturnAtMostTwentyAndCheckTheirArguments() {
        String prefix = "M" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 22; i++) {
            created(JP_DATASET, newId(), Map.of("title", prefix + " " + (100 + i)));
        }
        assertThat(lookup(JP_DATASET, prefix)).hasSize(20);
        assertThat(client.get().uri(uri -> uri.path("/api/datasets/" + JP_DATASET + "/lookup")
                .queryParam("q", prefix).queryParam("limit", 3).build())
            .header(HttpHeaders.AUTHORIZATION, writer()).exchange().expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody()).hasSize(3);
        client.get().uri(uri -> uri.path("/api/datasets/" + JP_DATASET + "/lookup").queryParam("limit", 0).build())
            .header(HttpHeaders.AUTHORIZATION, writer()).exchange().expectStatus().isBadRequest();
    }

    @Test
    @SuppressWarnings("unchecked")
    void labelsNameTheInstancesWithinTheScopeOnly() {
        String jp = newId();
        String kr = newId();
        created(JP_DATASET, jp, Map.of("title", "Tokyo"));
        created(KR_DATASET, kr, Map.of("title", "Seoul"));

        Map<String, Object> labels = post("/api/datasets/" + JP_DATASET + "/labels", writer(),
            Map.of("ids", List.of(jp, kr, "missing"))).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(labels).containsExactly(Map.entry(jp, "Tokyo"));

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            tooMany.add("id-" + i);
        }
        assertThat(codes(problem(post("/api/datasets/" + JP_DATASET + "/labels", writer(), Map.of("ids", tooMany)),
            400))).containsExactly("ids:INVALID_VALUE");
        assertThat((Map<String, Object>) post("/api/datasets/" + JP_DATASET + "/labels", writer(),
            Map.of("ids", List.of())).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody()).isEmpty();
    }

    @Test
    void lookupsNeedTheReadPermissionAndADisplayField() {
        String stranger = TestTokens.bearer(tokens, "it-stranger", "it.read");
        client.get().uri("/api/datasets/" + JP_DATASET + "/lookup?q=a").header(HttpHeaders.AUTHORIZATION, stranger)
            .exchange().expectStatus().isForbidden();
        post("/api/datasets/" + JP_DATASET + "/labels", stranger, Map.of("ids", List.of("a")))
            .expectStatus().isForbidden();

        Map<String, Object> problem = problem(client.get().uri("/api/datasets/" + ItFixtures.TICKET_DATASET
            + "/lookup?q=a").header(HttpHeaders.AUTHORIZATION, stranger).exchange(), 400);
        assertThat(codes(problem)).containsExactly("null:DISPLAY_NOT_DECLARED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCatalogAndTheExportDescribeTheNewMetadata() {
        List<Map<String, Object>> processes = client.get().uri("/api/meta/processes")
            .header(HttpHeaders.AUTHORIZATION, writer()).exchange().expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody();
        assertThat(processes).filteredOn(p -> ItContentFixtures.PUBLISH.equals(p.get("name"))).singleElement()
            .satisfies(p -> assertThat(p.get("actsOn")).isEqualTo(Map.of("entity", "ItArticle", "input", "articleId",
                "when", Map.of("field", "status", "values", List.of("DRAFT")))));

        Map<String, Object> export = client.get().uri("/api/meta/entities/ItArticle")
            .header(HttpHeaders.AUTHORIZATION, writer()).exchange().expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(export).containsEntry("display", "title").containsKey("defaultLocale");
        assertThat((List<Map<String, Object>>) export.get("fields"))
            .filteredOn(f -> Boolean.TRUE.equals(f.get("processOnly"))).extracting(f -> f.get("name"))
            .containsExactly("region", "status", "note");
        assertThat((Map<String, Object>) export.get("messages")).containsKeys("TRANSLATION_REQUIRED", "TOO_LONG");
    }

    private List<Map<String, Object>> lookup(String dataset, String q) {
        return client.get().uri(uri -> uri.path("/api/datasets/" + dataset + "/lookup").queryParam("q", q).build())
            .header(HttpHeaders.AUTHORIZATION, writer()).exchange().expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody();
    }
}

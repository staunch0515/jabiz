package com.jabiz.app.it.process;

import com.jabiz.app.PriceAdjustment;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.context.DevHeaderActorResolver;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The process API {@code POST /api/processes/{name}/{version|latest}} (docs/design/06-process.md section 8): input
 * conversion and Bean Validation, permissions, versions, accumulated violations, the {@code Idempotency-Key} header,
 * and the sample processes of the application.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class ProcessApiIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final String IT = "it.process";

    @Autowired
    ApplicationContext context;

    @Autowired
    DatasetEntityManager entities;

    @Autowired
    DatasetRegistry datasets;

    private WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    private WebTestClient.ResponseSpec post(String path, String permissions, Object body, String idempotencyKey) {
        WebTestClient.RequestBodySpec request = client.post().uri("/api/processes/" + path)
            .contentType(MediaType.APPLICATION_JSON)
            .header(DevHeaderActorResolver.ACTOR_HEADER, "api-user")
            .header(DevHeaderActorResolver.PERMISSIONS_HEADER, permissions);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request.bodyValue(body).exchange();
    }

    private static Map<String, Object> ticket(String id, String title, Object amount) {
        return Map.of("id", id, "title", title, "amount", amount);
    }

    private static String id() {
        return "api-" + UUID.randomUUID();
    }

    @SuppressWarnings("unchecked")
    private static List<String> ruleCodes(Map<String, Object> problem) {
        return ((List<Map<String, Object>>) problem.get("violations")).stream()
            .map(v -> v.get("field") + ":" + v.get("ruleCode")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(Map<String, Object> response) {
        return (Map<String, Object>) response.get("output");
    }

    // ---------------------------------------------------------------- execution and versions

    @Test
    void runsTheRequestedVersionAndAnswersWithOutputAndOperation() {
        String v1 = id();
        String latest = id();

        Map<String, Object> first = post("IT_CREATE_TICKET/1", IT, ticket(v1, "one", 1), null)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        post("IT_CREATE_TICKET/latest", IT, ticket(latest, "two", 1), null).expectStatus().isOk();

        assertThat(first.get("processSeqId")).isInstanceOf(Number.class);
        assertThat(output(first)).containsEntry("id", v1).containsEntry("version", 1);
        assertThat(query("SELECT f_title FROM it_ticket WHERE f_id = ?", v1).getFirst()).containsEntry("f_title", "one");
        assertThat(query("SELECT f_title FROM it_ticket WHERE f_id = ?", latest).getFirst())
            .containsEntry("f_title", "v2 two");
        assertThat(query("SELECT actor_id, process_version FROM op_process WHERE process_seq_id = ?",
            ((Number) first.get("processSeqId")).longValue()).getFirst())
            .containsEntry("actor_id", "api-user").containsEntry("process_version", 1);
    }

    @Test
    void unknownProcessesAndVersionsAreNotFound() {
        post("IT_NOPE/1", IT, Map.of(), null).expectStatus().isNotFound();
        post("IT_CREATE_TICKET/9", IT, Map.of(), null).expectStatus().isNotFound();
        post("IT_CREATE_TICKET/first", IT, Map.of(), null).expectStatus().isBadRequest();
    }

    @Test
    void theCallerNeedsTheProcessPermissions() {
        String id = id();
        post("IT_CREATE_TICKET/1", "it.read", ticket(id, "x", 1), null)
            .expectStatus().isForbidden()
            .expectBody(MAP).value(body -> assertThat(ruleCodes(body)).containsExactly("null:PERMISSION_DENIED"));
        assertThat(query("SELECT 1 FROM it_ticket WHERE f_id = ?", id)).isEmpty();
    }

    // ---------------------------------------------------------------- input

    @Test
    void theInputIsValidatedAsAWhole() {
        post("IT_CREATE_TICKET/1", IT, ticket("", " ", -3), null)
            .expectStatus().isBadRequest()
            .expectBody(MAP).value(body -> assertThat(ruleCodes(body))
                .containsExactly("amount:INVALID_VALUE", "id:REQUIRED", "title:REQUIRED"));
    }

    @Test
    void aBodyThatIsNotTheInputIsRejected() {
        post("IT_CREATE_TICKET/1", IT, ticket(id(), "t", "a lot"), null)
            .expectStatus().isBadRequest()
            .expectBody(MAP).value(body -> assertThat(ruleCodes(body)).containsExactly("null:INVALID_VALUE"));
    }

    /** Acceptance 5 over HTTP: one 422 with the violations of every step. */
    @Test
    void violationsOfAllStepsComeInOneResponse() {
        post("IT_MULTI_VIOLATION/1", IT, ticket(id(), "t", 1), null)
            .expectStatus().isEqualTo(422)
            .expectBody(MAP).value(body -> assertThat(ruleCodes(body))
                .containsExactly("title:IT_TITLE_TAKEN", "amount:IT_AMOUNT_TOO_HIGH"));
    }

    // ---------------------------------------------------------------- idempotency

    /** Acceptance 4 over HTTP: the repeated request gets the first result, and the process ran once. */
    @Test
    void theIdempotencyKeyHeaderReplaysTheFirstResult() {
        String key = "api-" + UUID.randomUUID();
        String id = id();

        var first = post("IT_CREATE_TICKET/1", IT, ticket(id, "once", 1), key).expectStatus().isOk()
            .expectHeader().doesNotExist("Idempotency-Replayed")
            .expectBody(MAP).returnResult().getResponseBody();
        var second = post("IT_CREATE_TICKET/1", IT, ticket(id, "once", 1), key).expectStatus().isOk()
            .expectHeader().valueEquals("Idempotency-Replayed", "true")
            .expectBody(MAP).returnResult().getResponseBody();

        assertThat(second).isEqualTo(first);
        assertThat(query("SELECT count(*) AS n FROM op_process WHERE idempotency_key = ?", key).getFirst())
            .containsEntry("n", 1L);
    }

    @Test
    void idempotencyKeysAreCheckedAndBoundToTheirProcess() {
        String key = "api-" + UUID.randomUUID();
        post("IT_CREATE_TICKET/1", IT, ticket(id(), "a", 1), "not a key!")
            .expectStatus().isBadRequest()
            .expectBody(MAP).value(body -> assertThat(ruleCodes(body))
                .containsExactly("Idempotency-Key:INVALID_IDEMPOTENCY_KEY"));
        post("IT_CREATE_TICKET/1", IT, ticket(id(), "a", 1), key).expectStatus().isOk();
        post("IT_MULTI_VIOLATION/1", IT, ticket(id(), "a", 1), key)
            .expectStatus().isEqualTo(409)
            .expectBody(MAP).value(body -> assertThat(ruleCodes(body)).containsExactly("null:IDEMPOTENCY_KEY_REUSED"));
    }

    // ---------------------------------------------------------------- sample processes

    private EntityInstance newPrice(long amount) {
        UUID id = UUID.randomUUID();
        Map<String, Object> attributes = Map.of("priceId", id, "sku", "S-" + id.toString().substring(0, 8),
            "amount", amount);
        return asTestRequest(entities.commitBatch(datasets.findById("urn:jabiz:dataset:default:Price").orElseThrow(),
            List.of(new EntityChange(EntityAction.INSERT, new EntityInstance(id, "Price", 0, null, attributes)))))
            .block().getFirst();
    }

    @Test
    void priceAdjustmentWritesANewVersionOfThePrice() {
        EntityInstance price = newPrice(1000);

        Map<String, Object> response = post("PRICE_ADJUST/latest", PriceAdjustment.PERMISSION,
            Map.of("priceId", price.id().toString(), "version", price.version(), "percent", 15), null)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();

        assertThat(output(response)).containsEntry("oldAmount", 1000).containsEntry("newAmount", 1150);
        List<Map<String, Object>> versions = query(
            "SELECT f_amount, version_no FROM t_price WHERE f_price_id = ?::uuid ORDER BY version_no",
            price.id().toString());
        assertThat(versions).extracting(row -> ((BigDecimal) row.get("f_amount")).intValue()).containsExactly(1000, 1150);
    }

    @Test
    void priceAdjustmentReportsEveryBrokenRule() {
        EntityInstance price = newPrice(10);

        post("PRICE_ADJUST/1", PriceAdjustment.PERMISSION,
            Map.of("priceId", price.id().toString(), "version", price.version(), "percent", -100), null)
            .expectStatus().isEqualTo(422)
            .expectBody(MAP).value(body -> assertThat(ruleCodes(body))
                .containsExactly("percent:PRICE_ADJUSTMENT_RANGE", "percent:PRICE_NOT_POSITIVE"));
        post("PRICE_ADJUST/1", "todo.write",
            Map.of("priceId", price.id().toString(), "version", price.version(), "percent", 1), null)
            .expectStatus().isForbidden();
    }

    @Test
    void theSingleStepSampleCompletesATodo() {
        String id = UUID.randomUUID().toString();
        execute("INSERT INTO todo (id, title, done, owner_id) VALUES (?, 'write tests', false, 'api-user')", id);
        long version = ((Number) query("SELECT version FROM todo WHERE id = ?", id).getFirst().get("version"))
            .longValue();

        Map<String, Object> response = post("TODO_COMPLETE/1", "todo.write", Map.of("id", id, "version", version), null)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();

        assertThat(output(response)).containsEntry("id", id).containsEntry("processSeqId", response.get("processSeqId"));
        assertThat(query("SELECT done FROM todo WHERE id = ?", id).getFirst()).containsEntry("done", true);
    }
}

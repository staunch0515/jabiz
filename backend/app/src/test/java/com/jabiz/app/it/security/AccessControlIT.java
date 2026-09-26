package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.ItTemporalFixtures;
import com.jabiz.runtime.security.SecurityEntities;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ROADMAP phase 7 acceptance: unauthenticated requests to protected endpoints get 401, authenticated requests without
 * the permission get 403 - datasets, SQL templates and processes alike (docs/design/10-security.md section 5). The
 * application runs without the dev profile, so only real access tokens authenticate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class AccessControlIT extends SecurityItSupport {

    private static final String PRICES = "urn:jabiz:dataset:default:Price";

    /** One call of each kind of protected endpoint, made with the given Authorization header (or none). */
    private List<Function<String, WebTestClient.ResponseSpec>> protectedCalls() {
        return List.of(
            auth -> get("/api/datasets/" + PRICES + "/entities/" + java.util.UUID.randomUUID(), auth),
            auth -> post("/api/datasets/" + PRICES + "/query", auth, Map.of()),
            auth -> post("/api/datasets/" + PRICES + "/commit", auth, Map.of("changes", List.of())),
            auth -> post("/api/queries/it.jp_prices", auth, Map.of()),
            auth -> post("/api/processes/PRICE_ADJUST/latest", auth, Map.of()),
            auth -> get("/api/entities/Price", auth),
            auth -> get("/api/processes/executions/1", auth));
    }

    @Test
    void protectedEndpointsNeedAnAuthenticatedCaller() {
        List<Function<String, WebTestClient.ResponseSpec>> calls = new java.util.ArrayList<>(protectedCalls());
        calls.add(auth -> get("/api/meta/entities/Price", auth));
        calls.add(auth -> get("/api/dictionaries/urn:jabiz:dict:customs_port", auth));
        calls.add(auth -> get("/api/auth/me", auth));
        calls.add(auth -> get("/api/auth/menus", auth));
        for (Function<String, WebTestClient.ResponseSpec> call : calls) {
            Map<String, Object> problem = call.apply(null)
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectHeader().exists("X-Request-Id")
                .expectBody(MAP).returnResult().getResponseBody();
            assertThat(ruleCode(problem)).isEqualTo("UNAUTHENTICATED");
        }
    }

    @Test
    void theUnauthenticatedAnswerIsLocalized() {
        Map<String, Object> problem = client.get().uri("/api/auth/me").header("Accept-Language", "ja").exchange()
            .expectStatus().isUnauthorized().expectBody(MAP).returnResult().getResponseBody();
        assertThat(violations(problem).getFirst().get("message")).isEqualTo("ログインしてください。");
    }

    @Test
    void expiredForgedAndForeignCredentialsAreUnauthenticated() {
        String token = bearer("*");
        get("/api/auth/me", token).expectStatus().isOk();

        clock.advance(Duration.ofMinutes(15));
        get("/api/auth/me", token).expectStatus().isUnauthorized();

        String fresh = bearer("*");
        get("/api/auth/me", fresh.substring(0, fresh.length() - 3) + "abc").expectStatus().isUnauthorized();
        get("/api/auth/me", "Basic aXQtYWRtaW46YWRtaW4=").expectStatus().isUnauthorized();
        // Development headers mean nothing outside the dev profile.
        client.get().uri("/api/auth/me").header("X-Jabiz-Actor", "root").header("X-Jabiz-Permissions", "*")
            .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void callersWithoutThePermissionAreForbidden() {
        String nobody = bearer("unrelated.permission");
        for (Function<String, WebTestClient.ResponseSpec> call : protectedCalls()) {
            Map<String, Object> problem = call.apply(nobody).expectStatus().isForbidden()
                .expectBody(MAP).returnResult().getResponseBody();
            assertThat(ruleCode(problem)).isEqualTo("PERMISSION_DENIED");
        }
    }

    @Test
    void theDeclaredPermissionOpensEachEndpoint() {
        // Datasets: reading and writing need their own permissions.
        get("/api/datasets/" + PRICES + "/entities/" + java.util.UUID.randomUUID(), bearer("pricing.price.read"))
            .expectStatus().isNotFound();
        post("/api/datasets/" + PRICES + "/query", bearer("pricing.price.read"), Map.of()).expectStatus().isOk();
        post("/api/datasets/" + PRICES + "/commit", bearer("pricing.price.read"), Map.of("changes", List.of()))
            .expectStatus().isForbidden();
        post("/api/datasets/" + PRICES + "/commit", bearer("pricing.price.write"), Map.of("changes", List.of(
            Map.of("action", "INSERT", "attributes", Map.of("sku", unique("SKU"), "amount", 100)))))
            .expectStatus().isOk();
        // Templates.
        post("/api/queries/it.jp_prices", bearer("it.query"), Map.of()).expectStatus().isOk();
        // Processes: past the permission check, the unknown price is a 404.
        post("/api/processes/PRICE_ADJUST/latest", bearer("price.adjust"),
            Map.of("priceId", java.util.UUID.randomUUID().toString(), "version", 1, "percent", 10))
            .expectStatus().isNotFound();
        // The generic entity API: reading needs the dataset's permission, writing also the process's.
        get("/api/entities/Price", bearer("pricing.price.read")).expectStatus().isOk();
        post("/api/entities/Price", bearer("pricing.price.write"), Map.of("sku", unique("SKU"), "amount", 1))
            .expectStatus().isForbidden();
        post("/api/entities/Price", bearer("pricing.price.write", "entity.write"),
            Map.of("sku", unique("SKU"), "amount", 1)).expectStatus().isCreated();
        // Operations need their own permission.
        get("/api/processes/executions/" + Long.MAX_VALUE, bearer("operation.read")).expectStatus().isNotFound();
    }

    @Test
    void sensitiveTemporalOperationsNeedDedicatedPermissions() {
        String writer = bearer("it.read", "it.write");
        List<Map<String, Object>> created = post("/api/datasets/" + ItTemporalFixtures.PRICE_DATASET + "/commit",
            writer, Map.of("changes", List.of(Map.of("action", "INSERT", "attributes",
                Map.of("sku", unique("SKU"), "amount", 100, "region", "JP")))))
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
        String id = String.valueOf(created.getFirst().get("id"));

        // A correction of the past.
        Map<String, Object> backdated = Map.of("action", "UPDATE", "id", id, "version", 1,
            "attributes", Map.of("amount", 90), "effectiveTime", START.minus(Duration.ofDays(1)).toString());
        Map<String, Object> problem = post("/api/datasets/" + ItTemporalFixtures.PRICE_DATASET + "/commit", writer,
            Map.of("changes", List.of(backdated), "reason", "typo"))
            .expectStatus().isForbidden().expectBody(MAP).returnResult().getResponseBody();
        assertThat(violations(problem).getFirst().get("message").toString()).contains("temporal.backdate");

        // Reverting an operation.
        long seq = ((Number) query("SELECT max(process_seq_id) AS seq FROM op_process").getFirst().get("seq"))
            .longValue();
        post("/api/processes/executions/" + seq + "/revert", writer, Map.of("reason", "undo"))
            .expectStatus().isForbidden();
        post("/api/processes/executions/" + seq + "/revert", bearer("temporal.revert"), Map.of("reason", "undo"))
            .expectStatus().value(status -> assertThat(status).isNotEqualTo(HttpStatus.FORBIDDEN.value()));
    }

    /** The generic processes write any entity: whatever the entry point, the entity's dataset decides. */
    @Test
    void theGenericProcessesNeedTheWritePermissionOfTheEntitysDataset() {
        String roleId = createRole(unique("ADMINISH"), "*");
        Map<String, Object> input = Map.of("entityType", SecurityEntities.USER_ROLE,
            "attributes", Map.of("userId", createUser(unique("mallory"), "long enough password"), "roleId", roleId));

        Map<String, Object> problem = post("/api/processes/ADD_ENTITY/latest", bearer("entity.write"), input)
            .expectStatus().isForbidden().expectBody(MAP).returnResult().getResponseBody();
        assertThat(violations(problem).getFirst().get("message").toString()).contains("security.user-role.write");
        post("/api/processes/ADD_ENTITY/latest", bearer("entity.write", "security.user-role.write"), input)
            .expectStatus().isOk();
    }

    @Test
    void theSecurityDatasetsAreProtectedLikeAnyOther() {
        post("/api/datasets/" + SecurityEntities.USER_DATASET + "/query", bearer("pricing.price.read"), Map.of())
            .expectStatus().isForbidden();
        post("/api/datasets/" + SecurityEntities.ROLE_DATASET + "/commit", bearer("security.role.read"),
            Map.of("changes", List.of())).expectStatus().isForbidden();
        post("/api/processes/SEC_USER_CREATE/latest", bearer("security.user.write"),
            Map.of("userName", unique("u"), "password", "long enough password")).expectStatus().isForbidden();
        // The sign-in process itself is reachable only through /api/auth/login.
        post("/api/processes/SPONSOR_SIGN_IN/latest", bearer("security.user.write"),
            Map.of("userName", "x", "password", "y")).expectStatus().isForbidden();
    }
}

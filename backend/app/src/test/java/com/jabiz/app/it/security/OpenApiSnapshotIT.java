package com.jabiz.app.it.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The OpenAPI document of the web API, kept in the repository as {@code frontend/openapi/openapi.json}: the frontend
 * generates its TypeScript types and request functions from it (docs/design/12-frontend.md section 3). The test
 * fails when the API and the committed document differ, so the frontend never compiles against a stale API.
 * {@code -Dopenapi.update-snapshot=true} rewrites it; a missing document is written, except on CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class OpenApiSnapshotIT extends SecurityItSupport {

    private static final ObjectMapper JSON = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    @Test
    void theCommittedDocumentDescribesTheCurrentApi() throws IOException {
        String body = get("/api/meta/openapi", admin()).expectStatus().isOk().expectBody(String.class)
            .returnResult().getResponseBody();
        JsonNode document = JSON.readTree(body);
        // The server URL depends on how the test reached the application; it is not part of the API.
        ((ObjectNode) document).remove("servers");
        String actual = JSON.writeValueAsString(JSON.treeToValue(document, Object.class)) + "\n";

        Path snapshot = Path.of(System.getProperty("openapi.snapshot", "../../frontend/openapi/openapi.json"));
        boolean update = Boolean.getBoolean("openapi.update-snapshot");
        if (!Files.exists(snapshot) && System.getenv("CI") != null && !update) {
            fail(snapshot + " is missing; generate it with ./gradlew :app:test --tests '*OpenApiSnapshotIT' "
                 + "-Dopenapi.update-snapshot=true and commit it");
        }
        if (update || !Files.exists(snapshot)) {
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, actual, StandardCharsets.UTF_8);
        }
        assertThat(Files.readString(snapshot, StandardCharsets.UTF_8))
            .as("The API changed: run ./gradlew :app:test --tests '*OpenApiSnapshotIT' -Dopenapi.update-snapshot=true, "
                + "then pnpm gen:api in frontend/, and commit both")
            .isEqualTo(actual);
    }
}

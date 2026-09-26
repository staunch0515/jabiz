package com.jabiz.app.it.security;

import com.jabiz.runtime.security.SecurityEntities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ROADMAP phase 7 acceptance: passwords appear neither in the logs nor in {@code op_process.input_summary}; password
 * hashes are never returned and cannot be written through the generic APIs (docs/design/10-security.md section 6).
 * Everything is logged at DEBUG to give leaks every chance to show.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {"logging.level.com.jabiz=DEBUG", "logging.level.org.springframework.security=DEBUG"})
@ExtendWith(OutputCaptureExtension.class)
class SensitiveDataIT extends SecurityItSupport {

    private static final String FIRST = "Pa55word-first-9Xq";
    private static final String WRONG = "Pa55word-wrong-7Zk";
    private static final String SECOND = "Pa55word-second-3Vw";
    /** As PostgreSQL renders jsonb. */
    private static final String MASKED_PASSWORD = "\"password\":\\s*\"\\*\\*\\*\"";

    @Test
    void passwordsReachNeitherLogsNorOperationRecords(CapturedOutput output) {
        String name = unique("nina");
        String userId = userWith(name, FIRST, "p");
        login(name, WRONG).expectStatus().isUnauthorized();
        signIn(name, FIRST);
        client.post().uri("/api/processes/SEC_USER_SET_PASSWORD/latest")
            .header("Authorization", admin()).header("Idempotency-Key", unique("key"))
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("userId", userId, "password", SECOND)).exchange().expectStatus().isOk();
        signIn(name, SECOND);

        assertThat(output.getAll()).isNotEmpty().doesNotContain(FIRST, WRONG, SECOND);

        List<Map<String, Object>> operations = query("SELECT process_name, input_summary::text AS summary "
            + "FROM op_process WHERE input_summary::text LIKE ?", "%" + name + "%");
        assertThat(operations).extracting(r -> r.get("process_name"))
            .contains("SEC_USER_CREATE", "SPONSOR_SIGN_IN");
        assertThat(operations).allSatisfy(r -> assertThat((String) r.get("summary"))
            .doesNotContain(FIRST, WRONG, SECOND).doesNotContain("$2a$"));
        assertThat(operations).filteredOn(r -> "SPONSOR_SIGN_IN".equals(r.get("process_name")))
            .allSatisfy(r -> assertThat((String) r.get("summary")).containsPattern(MASKED_PASSWORD));
        List<Map<String, Object>> setPassword = query("SELECT input_summary::text AS summary FROM op_process "
            + "WHERE process_name = 'SEC_USER_SET_PASSWORD' AND input_summary::text LIKE ?", "%" + userId + "%");
        assertThat(setPassword).singleElement()
            .satisfies(r -> assertThat((String) r.get("summary")).containsPattern(MASKED_PASSWORD));
        assertThat(query("SELECT output::text AS output FROM op_process_result")).allSatisfy(
            r -> assertThat((String) r.get("output")).doesNotContain(FIRST, WRONG, SECOND, "$2a$"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void passwordHashesAreNeverReturned() {
        String name = unique("olga");
        String userId = userWith(name, FIRST, "p");
        String dataset = "/api/datasets/" + SecurityEntities.USER_DATASET;

        Map<String, Object> read = get(dataset + "/entities/" + userId, admin()).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat((Map<String, Object>) read.get("attributes")).containsEntry("userName", name)
            .doesNotContainKey("passwordHash");
        Map<String, Object> page = post(dataset + "/query", admin(), Map.of("filters",
            List.of(Map.of("field", "userName", "op", "eq", "value", name))))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(page.toString()).contains(name).doesNotContain("passwordHash", "$2a$");
        List<Map<String, Object>> history = get(dataset + "/entities/" + userId + "/history", admin())
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
        // The history tells that the hash was set (changedFields), never what it is.
        assertThat(history).singleElement().satisfies(version -> {
            assertThat((Map<String, Object>) version.get("attributes")).containsEntry("userName", name)
                .doesNotContainKey("passwordHash");
            assertThat(version.toString()).doesNotContain("$2a$");
        });
        String list = get("/api/entities/SecUser", admin()).expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();
        assertThat(list).contains(name).doesNotContain("passwordHash", "$2a$");
        String meta = get("/api/meta/schema/SecUser", admin()).expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();
        assertThat(meta).contains("\"writeOnly\":true");
    }

    @Test
    void passwordHashesCannotBeWrittenThroughTheGenericApis() {
        String name = unique("paul");
        String userId = userWith(name, FIRST, "p");
        Map<String, Object> user = get("/api/datasets/" + SecurityEntities.USER_DATASET + "/entities/" + userId,
            admin()).expectBody(MAP).returnResult().getResponseBody();

        Map<String, Object> problem = post("/api/datasets/" + SecurityEntities.USER_DATASET + "/commit", admin(),
            Map.of("changes", List.of(Map.of("action", "UPDATE", "id", userId, "version", user.get("version"),
                "attributes", Map.of("passwordHash", "$2a$04$forged")))))
            .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(violations(problem)).singleElement().satisfies(v -> assertThat(v)
            .containsEntry("field", "passwordHash").containsEntry("ruleCode", "SENSITIVE_FIELD"));

        post("/api/entities/SecUser", admin(), Map.of("userName", unique("q"), "enabled", true,
            "passwordHash", "$2a$04$forged")).expectStatus().isBadRequest();
        // The original password still works.
        signIn(name, FIRST);
    }

    @Test
    void weakPasswordsAreRefused() {
        Map<String, Object> problem = post("/api/processes/SEC_USER_CREATE/latest", admin(),
            Map.of("userName", unique("r"), "password", "short"))
            .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCode(problem)).isEqualTo("PASSWORD_TOO_SHORT");
    }
}

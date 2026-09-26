package com.jabiz.app.it;

import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP edge of phase 2: localized violations (02 §3.1) and the request id in every log line (01 §5).
 * Requests go through the full WebFlux handler chain, including all web filters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "logging.level.com.jabiz.runtime=DEBUG")
@ExtendWith(OutputCaptureExtension.class)
class WebRequestIT extends PostgresIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    private WebTestClient client;

    @BeforeEach
    void client() {
        // An authenticated administrator: this class is about messages and request ids, not permissions.
        client = WebTestClient.bindToApplicationContext(context).configureClient()
            .defaultHeader(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "web-user", "*")).build();
    }

    private WebTestClient.ResponseSpec createTicket(String language, Map<String, Object> body) {
        return client.post().uri("/api/entities/ItTicket")
            .header("Accept-Language", language)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange();
    }

    // ---------------------------------------------------------------- Localized messages

    @Test
    void theSameValidationErrorInChineseJapaneseAndEnglish() {
        Map<String, Object> missingTitle = Map.of("ticketId", "T-1");

        assertThat(titleMessage(createTicket("zh-CN,zh;q=0.9", missingTitle))).isEqualTo("字段“title”为必填项。");
        assertThat(titleMessage(createTicket("ja", missingTitle))).isEqualTo("項目「title」は必須です。");
        assertThat(titleMessage(createTicket("en-US", missingTitle))).isEqualTo("Field \"title\" is required.");
    }

    @Test
    void unsupportedOrMissingLanguageFallsBackToTheDefault() {
        Map<String, Object> missingTitle = Map.of("ticketId", "T-1");

        assertThat(titleMessage(createTicket("fr", missingTitle))).isEqualTo("Field \"title\" is required.");
        assertThat(titleMessage(client.post().uri("/api/entities/ItTicket")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(missingTitle).exchange()))
            .isEqualTo("Field \"title\" is required.");
    }

    @Test
    void ruleParametersFillTheMessage() {
        List<Map<String, Object>> violations = violations(
            createTicket("ja", Map.of("ticketId", "T-1", "title", "t", "amount", -5)), HttpStatus.BAD_REQUEST);

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v).containsEntry("field", "amount").containsEntry("ruleCode", "NON_NEGATIVE_AMOUNT");
            assertThat(v.get("message")).isEqualTo("項目「amount」に負の値は指定できません。");
        });
    }

    @Test
    void businessRuleViolationsAreAccumulatedAndLocalized() {
        createTicket("en", Map.of("ticketId", "T-RULES", "title", "t", "owner", "alice"))
            .expectStatus().isCreated();

        WebTestClient.ResponseSpec response = client.patch().uri("/api/entities/ItTicket/T-RULES")
            .header("Accept-Language", "ja")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("version", 1, "attributes", Map.of("owner", "bob", "status", "DONE")))
            .exchange();

        assertThat(violations(response, HttpStatus.UNPROCESSABLE_CONTENT))
            .extracting(v -> v.get("field") + "|" + v.get("ruleCode") + "|" + v.get("message"))
            .containsExactlyInAnyOrder(
                "owner|IMMUTABLE_FIELD|項目「owner」は変更できません。",
                "status|ILLEGAL_TRANSITION|ステータスを OPEN から DONE に変更することはできません。");
    }

    // ---------------------------------------------------------------- Request id

    @Test
    void everyLogLineOfARequestCarriesItsRequestId(CapturedOutput output) throws InterruptedException {
        int before = output.getOut().length();

        client.post().uri("/api/entities/ItTicket")
            .header("X-Request-Id", "log-it-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("ticketId", "T-LOG", "title", "t"))
            .exchange()
            .expectStatus().isCreated()
            .expectHeader().valueEquals("X-Request-Id", "log-it-1");

        // The completion line is written in doFinally, which may run just after the client has the response.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!output.getOut().substring(before).contains("POST /api/entities/ItTicket -> 201")
            && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        List<String> lines = Arrays.stream(output.getOut().substring(before).split("\\R"))
            .filter(line -> !line.isBlank())
            .toList();
        assertThat(lines)
            .as("lines logged while the request ran")
            .anySatisfy(line -> assertThat(line).contains("Committed 1 change(s)"))
            .anySatisfy(line -> assertThat(line).contains("POST /api/entities/ItTicket -> 201"))
            .allSatisfy(line -> assertThat(line).contains("requestId=log-it-1"));
        // The commit is logged from an R2DBC callback, so the id crossed threads through the Reactor context.
        assertThat(lines).filteredOn(line -> line.contains("Committed"))
            .allSatisfy(line -> assertThat(line).doesNotContain("Test worker"));
    }

    @Test
    void aRequestWithoutIdGetsOneInTheResponse() {
        String id = client.get().uri("/api/entities/ItTicket").exchange()
            .expectStatus().isOk()
            .returnResult(String.class).getResponseHeaders().getFirst("X-Request-Id");

        assertThat(id).matches("[0-9a-f-]{36}");
    }

    // ---------------------------------------------------------------- Helpers

    private static String titleMessage(WebTestClient.ResponseSpec response) {
        return violations(response, HttpStatus.BAD_REQUEST).stream()
            .filter(v -> "title".equals(v.get("field")))
            .map(v -> {
                assertThat(v).containsEntry("ruleCode", "REQUIRED");
                return (String) v.get("message");
            })
            .findFirst()
            .orElseThrow(() -> new AssertionError("no violation for field title"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> violations(WebTestClient.ResponseSpec response, HttpStatus status) {
        Map<String, Object> problem = response.expectStatus().isEqualTo(status.value())
            .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(problem).containsKey("violations");
        return (List<Map<String, Object>>) problem.get("violations");
    }
}

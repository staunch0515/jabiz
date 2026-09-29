package com.jabiz.app.it;

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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An application with English as its only interface language (decision D22 item 7), as `jabizApp { languages("en") }`
 * configures it: every request is answered in English, whatever language it asks for, and the startup checks pass
 * although the application's messages need not exist in Chinese or Japanese.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "jabiz.i18n.languages=en")
class LanguageSubsetIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    private WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToApplicationContext(context).configureClient()
            .defaultHeader(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "subset-user", "*")).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void violationsAreInEnglishWhateverLanguageIsAskedFor() {
        for (String language : List.of("ja", "zh-CN,zh;q=0.9", "en-US")) {
            Map<String, Object> problem = client.post().uri("/api/entities/ItTicket")
                .header("Accept-Language", language)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("ticketId", "T-1"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(MAP).returnResult().getResponseBody();
            List<Map<String, Object>> violations = (List<Map<String, Object>>) problem.get("violations");
            assertThat(violations).filteredOn(v -> "title".equals(v.get("field")))
                .extracting(v -> v.get("message")).as(language).containsExactly("Field \"title\" is required.");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theMetadataIsInEnglishToo() {
        Map<String, Object> meta = client.get().uri("/api/meta/entities/ItTicket")
            .header("Accept-Language", "ja")
            .exchange()
            .expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat((Map<String, Object>) meta.get("messages")).containsEntry("REQUIRED", "Field \"{field}\" is required.");
    }
}

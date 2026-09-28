package com.jabiz.app.it.publicread;

import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Each client address may make {@code jabiz.public.rate-limit.per-minute} public requests (15 section 5). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {"jabiz.public.enabled=true", "jabiz.public.rate-limit.per-minute=3"})
class PublicRateLimitIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    @Autowired
    ApplicationContext context;

    @Autowired
    MeterRegistry meters;

    @Test
    @SuppressWarnings("unchecked")
    void aClientBeyondItsRateIsToldToWait() {
        WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
        String catalog = "/api/public/queries/commerce.public.catalog";
        for (int i = 0; i < 3; i++) {
            client.get().uri(catalog).exchange().expectStatus().isOk();
        }
        Map<String, Object> problem = client.get().uri(catalog).header(HttpHeaders.ACCEPT_LANGUAGE, "ja")
            .exchange().expectStatus().isEqualTo(429)
            .expectHeader().value(HttpHeaders.RETRY_AFTER, value -> assertThat(Long.parseLong(value)).isPositive())
            .expectBody(MAP).returnResult().getResponseBody();
        List<Map<String, Object>> violations = (List<Map<String, Object>>) problem.get("violations");
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v).containsEntry("ruleCode", "RATE_LIMITED");
            assertThat((String) v.get("message")).isNotBlank().doesNotContain("RATE_LIMITED");
        });
        // Files count against the same budget.
        client.get().uri("/api/public/files/019c1347-d280-74b3-a3e9-25b7e53b7bac").exchange()
            .expectStatus().isEqualTo(429);
        // Counted, without any tag (never the client's address).
        assertThat(meters.find(PlatformObservations.PUBLIC_RATE_LIMITED).timer()).isNotNull()
            .satisfies(timer -> {
                assertThat(timer.count()).isEqualTo(2);
                assertThat(timer.getId().getTags()).allSatisfy(tag -> assertThat(tag.getKey()).isNotEqualTo("client"));
            });
    }
}

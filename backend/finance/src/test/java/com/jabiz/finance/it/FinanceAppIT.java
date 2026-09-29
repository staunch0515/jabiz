package com.jabiz.finance.it;

import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The finance application starts on a freshly migrated database with the platform's startup checks passing, is
 * healthy, and keeps the platform's default: the API refuses callers without a token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FinanceAppIT extends PostgresIntegrationTest {

    @Autowired
    ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    @Test
    void isHealthy() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void refusesAnonymousApiCalls() {
        client.get().uri("/api/meta/datasets").exchange().expectStatus().isUnauthorized();
    }
}

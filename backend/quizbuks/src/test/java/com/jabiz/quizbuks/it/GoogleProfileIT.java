package com.jabiz.quizbuks.it;

import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Spring profile "google" (application-google.yml; docs/quizbuks/plans/QP-pages.md section 2, A-2): with a client
 * id, secret and the app's callback from the environment, the application starts (its OIDC checks pass) and offers
 * Google to the app's entry only (Q2-16): neither the sponsor console nor the administration has a callback, so neither
 * offers it. Nothing is asked of Google until somebody signs in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.profiles.active=google",
    "JABIZ_OIDC_GOOGLE_CLIENT_ID=test-only-client.apps.googleusercontent.com",
    "JABIZ_OIDC_GOOGLE_CLIENT_SECRET=test-only-google-secret",
    "QB_OIDC_REDIRECT_APP=https://quizbuks.example.com/login/oidc",
    "jabiz.security.entries.app.self-registration=true"})
class GoogleProfileIT extends PostgresIntegrationTest {

    @Autowired
    ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    private List<Map<String, Object>> providers(String entry) {
        return client.get().uri("/api/auth/oidc/providers?entry=" + entry).exchange().expectStatus().isOk()
            .expectBody(new ParameterizedTypeReference<List<Map<String, Object>>>() { }).returnResult()
            .getResponseBody();
    }

    @Test
    void googleIsOfferedToTheAppOnly() {
        assertThat(providers("app")).singleElement().satisfies(provider -> assertThat(provider)
            .containsEntry("id", "google").containsEntry("kind", "google").containsEntry("label", "Google"));
        assertThat(providers("sponsor")).isEmpty();
        assertThat(providers("admin")).isEmpty();
    }
}

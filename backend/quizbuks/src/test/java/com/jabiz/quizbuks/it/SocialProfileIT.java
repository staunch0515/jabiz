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
 * The Spring profile "social" (application-social.yml; docs/quizbuks/plans/Q40-social-sign-in.md): with the clients of
 * Google, Facebook and LINE and the callbacks of the app and the sponsor console from the environment, the application
 * starts (its OIDC checks pass, the LINE channel secret long enough) and offers all three to takers and sponsors, in
 * that order and with their kinds (the sign-in pages draw their marks by kind); the administration has no callback, so
 * it offers none. Nothing is asked of a provider until somebody signs in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.profiles.active=social",
    "JABIZ_OIDC_GOOGLE_CLIENT_ID=test-only-client.apps.googleusercontent.com",
    "JABIZ_OIDC_GOOGLE_CLIENT_SECRET=test-only-google-secret",
    "JABIZ_OIDC_FACEBOOK_CLIENT_ID=1234567890",
    "JABIZ_OIDC_FACEBOOK_CLIENT_SECRET=test-only-facebook-secret",
    "JABIZ_OIDC_LINE_CLIENT_ID=2000000000",
    "JABIZ_OIDC_LINE_CLIENT_SECRET=0123456789abcdef0123456789abcdef",
    "QB_OIDC_REDIRECT_APP=https://quizbuks.example.com/taker/login/oidc",
    "QB_OIDC_REDIRECT_SPONSOR=https://quizbuks.example.com/sponsor/login/oidc",
    "jabiz.security.entries.app.self-registration=true",
    "jabiz.security.entries.sponsor.self-registration=true"})
class SocialProfileIT extends PostgresIntegrationTest {

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
    void googleFacebookAndLineAreOfferedToTakersAndSponsorsNotToTheAdministration() {
        for (String entry : List.of("app", "sponsor")) {
            assertThat(providers(entry)).as(entry)
                .extracting(provider -> provider.get("id") + "/" + provider.get("kind") + "/" + provider.get("label"))
                .containsExactly("google/google/Google", "facebook/facebook/Facebook", "line/line/LINE");
        }
        assertThat(providers("admin")).isEmpty();
    }
}

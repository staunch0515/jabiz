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
 * The Spring profile "apple" (application-apple.yml, in place of "social"; platform decision D67): with the clients of
 * Google, Apple, Facebook and LINE and the callbacks of the app and the sponsor console from the environment, the
 * application starts (its OIDC checks pass: the LINE channel secret long enough, Apple's key a P-256 key, its form-post
 * address this server's) and offers all four to takers and sponsors, in that order and with their kinds; the
 * administration offers none. The three it shares with "social" are declared alike in both profiles.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.profiles.active=apple",
    "JABIZ_OIDC_GOOGLE_CLIENT_ID=test-only-client.apps.googleusercontent.com",
    "JABIZ_OIDC_GOOGLE_CLIENT_SECRET=test-only-google-secret",
    "JABIZ_OIDC_FACEBOOK_CLIENT_ID=1234567890",
    "JABIZ_OIDC_FACEBOOK_CLIENT_SECRET=test-only-facebook-secret",
    "JABIZ_OIDC_LINE_CLIENT_ID=2000000000",
    "JABIZ_OIDC_LINE_CLIENT_SECRET=0123456789abcdef0123456789abcdef",
    "JABIZ_OIDC_APPLE_CLIENT_ID=com.example.quizbuks.web",
    "JABIZ_OIDC_APPLE_TEAM_ID=TEAM123456",
    "JABIZ_OIDC_APPLE_KEY_ID=KEY1234567",
    "QB_OIDC_APPLE_FORM_POST=https://quizbuks.example.com/api/auth/oidc/apple/form-post",
    "QB_OIDC_REDIRECT_APP=https://quizbuks.example.com/taker/login/oidc",
    "QB_OIDC_REDIRECT_SPONSOR=https://quizbuks.example.com/sponsor/login/oidc",
    "jabiz.security.entries.app.self-registration=true",
    "jabiz.security.entries.sponsor.self-registration=true"})
class AppleProfileIT extends PostgresIntegrationTest {

    /** Apple's developer key: an EC P-256 key made for the run (the body of a .p8 file); none is kept in the code. */
    @org.springframework.test.context.DynamicPropertySource
    static void appleKey(org.springframework.test.context.DynamicPropertyRegistry registry) throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        String key = java.util.Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        registry.add("JABIZ_OIDC_APPLE_PRIVATE_KEY", () -> key);
    }

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
    void googleAppleFacebookAndLineAreOfferedToTakersAndSponsorsNotToTheAdministration() {
        for (String entry : List.of("app", "sponsor")) {
            assertThat(providers(entry)).as(entry)
                .extracting(provider -> provider.get("id") + "/" + provider.get("kind") + "/" + provider.get("label"))
                .containsExactly("google/google/Google", "apple/apple/Apple", "facebook/facebook/Facebook",
                    "line/line/LINE");
        }
        assertThat(providers("admin")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theProvidersSharedWithTheSocialProfileAreDeclaredAlike() throws Exception {
        java.util.function.Function<String, List<Map<String, Object>>> providers = file -> {
            try (var in = getClass().getResourceAsStream("/" + file)) {
                Map<String, Object> yaml = new org.yaml.snakeyaml.Yaml().load(in);
                return (List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                    yaml.get("jabiz")).get("security")).get("oidc")).get("providers");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        List<Map<String, Object>> withApple = providers.apply("application-apple.yml");
        assertThat(withApple.stream().filter(provider -> !"apple".equals(provider.get("id"))).toList())
            .isEqualTo(providers.apply("application-social.yml"));
    }
}

package com.jabiz.app.it.security;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.jabiz.runtime.test.TestOidcProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sign in with Apple (docs/design/10-security.md section 12; decision D67; ROADMAP phase 17j), against the test provider
 * acting as Apple: the client secret is a token signed with the developer's key, the round trip has no PKCE, Apple posts
 * its answer (and the first time the name) to this server, which sends the browser on to the entry's callback, and a
 * relay address opens an account named as Apple told.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=jabiz@example.com", "jabiz.mail.base-url=https://app.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3033", SignUpItSupport.ENTRY_PROPERTIES_REGISTRATION,
    SignUpItSupport.ENTRY_PROPERTIES_ADMIN, SignUpItSupport.ENTRY_PROPERTIES_LOOSE})
@Import(SignUpItSupport.FixedTimeLimits.class)
class OidcAppleIT extends OidcItSupport {

    static final String APPLE = "apple";
    static final String FORM_POST = "http://localhost/api/auth/oidc/apple/form-post";

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3033, null, ServerSetup.PROTOCOL_SMTP));

    /** The other providers' directory. */
    static final TestOidcProvider IDP = new TestOidcProvider();
    /** Apple. */
    static final TestOidcProvider APPLE_IDP = new TestOidcProvider();
    static final String PRIVATE_KEY = APPLE_IDP.asApple("TEAM123456", "KEY1234567");

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        providers(registry, IDP);
        String prefix = "jabiz.security.oidc.providers[4].";
        registry.add(prefix + "id", () -> APPLE);
        registry.add(prefix + "kind", () -> "apple");
        // The test provider in Apple's place; the client secret's audience stays Apple's.
        registry.add(prefix + "issuer", APPLE_IDP::issuer);
        registry.add(prefix + "client-id", () -> TestOidcProvider.CLIENT_ID);
        registry.add(prefix + "apple.team-id", () -> "TEAM123456");
        registry.add(prefix + "apple.key-id", () -> "KEY1234567");
        registry.add(prefix + "apple.private-key", () -> PRIVATE_KEY);
        registry.add(prefix + "apple.form-post-uri", () -> FORM_POST);
        registry.add(prefix + "redirect-uris." + PORTAL, () -> callbackOf(PORTAL));
        registry.add(prefix + "labels.en", () -> "Apple");
        registry.add(prefix + "auto-provision", () -> "true");
    }

    @AfterAll
    static void stop() {
        IDP.close();
        APPLE_IDP.close();
    }

    @Override
    GreenMailExtension mail() {
        return MAIL;
    }

    @Override
    TestOidcProvider idp() {
        return APPLE_IDP;
    }

    /** Apple posts its answer to this server, as the browser carries it. */
    WebTestClient.ResponseSpec formPost(String provider, Map<String, String> fields) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        fields.forEach(form::add);
        return client.post().uri("/api/auth/oidc/" + provider + "/form-post")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(BodyInserters.fromFormData(form)).exchange();
    }

    /** Where the browser is sent on, and its query. */
    static Map<String, String> sentOn(WebTestClient.ResponseSpec response) {
        URI location = response.expectStatus().isSeeOther()
            .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
            .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
            .returnResult(Void.class).getResponseHeaders().getLocation();
        assertThat(location).isNotNull();
        assertThat(location.toString()).startsWith(callbackOf(PORTAL) + "?");
        return TestOidcProvider.query(location.toString());
    }

    @Test
    void appleIsOfferedAndAsksForAFormPostWithoutPkce() {
        List<Map<String, Object>> providers = client.get().uri("/api/auth/oidc/providers?entry=" + PORTAL).exchange()
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
        assertThat(providers).anySatisfy(p -> assertThat(p).containsEntry("id", APPLE).containsEntry("kind", "apple"));
        Map<String, Object> started = anonymous("/api/auth/oidc/apple/start?entry=" + PORTAL, Map.of())
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        Map<String, String> query = TestOidcProvider.query((String) started.get("authorizationUrl"));
        assertThat(query).containsEntry("response_mode", "form_post").containsEntry("redirect_uri", FORM_POST)
            .containsEntry("scope", "openid name email").containsKeys("state", "nonce")
            .doesNotContainKeys("code_challenge", "code_challenge_method");
        // Not offered where it has no callback.
        anonymous("/api/auth/oidc/apple/start?entry=" + LOOSE, Map.of()).expectStatus().isUnauthorized();
    }

    @Test
    void aRelayAddressOpensAnAccountNamedAsAppleToldTheFirstTime() {
        String subject = unique("apple");
        String relay = unique("r").toLowerCase() + "@privaterelay.appleid.com";
        Request request = start(APPLE, PORTAL);
        String code = APPLE_IDP.code(null, idToken(request, subject, c -> c.claim("email", relay)
            .claim("email_verified", "true").claim("is_private_email", "true")), FORM_POST);

        Map<String, String> query = sentOn(formPost(APPLE, Map.of("state", request.state(), "code", code,
            "user", "{\"name\":{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\"},\"email\":\"" + relay + "\"}")));
        assertThat(query).containsEntry("state", request.state()).containsEntry("code", code)
            .doesNotContainKey("error");
        // Posting again (a reload) keeps the first name and still sends the browser on, until the callback uses the state.
        sentOn(formPost(APPLE, Map.of("state", request.state(), "code", code,
            "user", "{\"name\":{\"firstName\":\"Eve\"}}")));
        assertThat(query("SELECT provided_name FROM sec_oidc_state_name WHERE state_hash = ?",
            com.jabiz.runtime.security.OidcStateStore.hash(request.state()))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("provided_name", "Ada Lovelace"));

        Map<String, Object> session = anonymous("/api/auth/oidc/callback", Map.of("state", query.get("state"),
            "code", query.get("code"), "binder", request.binder())).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(session).containsEntry("status", "SIGNED_IN");
        assertThat(APPLE_IDP.clientAuthentication()).isEqualTo("post");
        String userId = (String) userOf(relay).get("user_id");
        assertThat(query("SELECT display_name FROM sec_user_version WHERE user_id = ?::uuid", userId))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("display_name", "Ada Lovelace"));
        assertThat(linkedUser(APPLE, subject)).isEqualTo(userId);
        // The name went with the request that used it.
        assertThat(query("SELECT provided_name FROM sec_oidc_state_name WHERE state_hash = ?",
            com.jabiz.runtime.security.OidcStateStore.hash(request.state()))).isEmpty();

        // The state is used: posting it once more is refused.
        formPost(APPLE, Map.of("state", request.state(), "code", code)).expectStatus().isUnauthorized();

        // The next sign-in brings no name and finds the link.
        Request again = start(APPLE, PORTAL);
        String next = APPLE_IDP.code(null, idToken(again, subject, c -> c.claim("email", relay)
            .claim("email_verified", true)), FORM_POST);
        Map<String, String> back = sentOn(formPost(APPLE, Map.of("state", again.state(), "code", next)));
        assertThat(anonymous("/api/auth/oidc/callback", Map.of("state", back.get("state"), "code", back.get("code"),
            "binder", again.binder())).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody())
            .containsEntry("userId", userId);
    }

    @Test
    void aCancelledSignInGoesBackWithoutACode() {
        Request request = start(APPLE, PORTAL);
        Map<String, String> query = sentOn(formPost(APPLE, Map.of("state", request.state(),
            "error", "user_cancelled_authorize")));
        assertThat(query).containsEntry("state", request.state()).containsEntry("error", "user_cancelled_authorize")
            .doesNotContainKey("code");
        // An error that is no OAuth code is not passed on as it came.
        Request other = start(APPLE, PORTAL);
        assertThat(sentOn(formPost(APPLE, Map.of("state", other.state(), "code", "c",
            "error", "<script>")))).containsEntry("error", "access_denied").doesNotContainKey("code");
    }

    @Test
    void onlyAPendingStateOfAppleIsSentOn() {
        // Unknown state, none, and another provider's: refused, sent nowhere.
        assertThat(refusal(formPost(APPLE, Map.of("state", "nope", "code", "c")), 401)).isEqualTo("LOGIN_FAILED");
        assertThat(refusal(formPost(APPLE, Map.of("code", "c")), 401)).isEqualTo("LOGIN_FAILED");
        Request open = start(OPEN, PORTAL);
        assertThat(refusal(formPost(APPLE, Map.of("state", open.state(), "code", "c",
            "user", "{\"name\":{\"firstName\":\"Mallory\"}}")), 401)).isEqualTo("LOGIN_FAILED");
        // A provider that is not Apple takes no posted answers.
        formPost(OPEN, Map.of("state", open.state(), "code", "c")).expectStatus().isNotFound();
        // Nothing was kept for the other provider's state.
        assertThat(query("SELECT provided_name FROM sec_oidc_state_name WHERE state_hash = ?",
            com.jabiz.runtime.security.OidcStateStore.hash(open.state()))).isEmpty();
    }
}

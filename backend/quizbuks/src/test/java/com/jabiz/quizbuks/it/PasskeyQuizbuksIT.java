package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.TestAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Passkeys in QuizBuks (docs/quizbuks/plans/Q97-passkeys.md; platform decision D71) with the platform's software
 * authenticator: the deployment's root address gives the relying party (here {@code http://localhost}, the
 * authenticator's origin), the app, the sponsor console, the administration and the operators' back office take
 * passkeys and Shopify's embedded pages do not; a taker adds one with their password and signs in with it at the app,
 * not at the sponsor console (no role there); a banned taker's passkey signs nobody in.
 */
@TestPropertySource(properties = "jabiz.mail.base-url=http://localhost")
class PasskeyQuizbuksIT extends AccountItSupport {

    private final TestAuthenticator authenticator = new TestAuthenticator();

    private Map<String, Object> entry(String name) {
        return client.get().uri("/api/auth/entries/" + name).exchange().expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec post(String path, String bearer, Map<String, ?> body) {
        WebTestClient.RequestBodySpec spec = client.post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, bearer);
        }
        return spec.bodyValue(body).exchange();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> publicKey(WebTestClient.ResponseSpec response) {
        return (Map<String, Object>) response.expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody()
            .get("publicKey");
    }

    /** Adds a passkey to the session's user, proven by the password. */
    private void addPasskey(String bearer) {
        Map<String, Object> options = publicKey(post("/api/auth/account/passkeys/options", bearer,
            Map.of("currentPassword", PASSWORD)));
        Map<String, Object> body = new HashMap<>();
        body.put("credential", authenticator.create(options, TestAuthenticator.Answer.right()));
        body.put("name", "Phone");
        post("/api/auth/account/passkeys", bearer, body).expectStatus().isCreated();
    }

    /** Signs in at an entry with the authenticator's passkey. */
    private WebTestClient.ResponseSpec passkeySignIn(String entry) {
        Map<String, Object> options = publicKey(post("/api/auth/passkey/options", null, Map.of("entry", entry)));
        return post("/api/auth/passkey", null, Map.of("entry", entry, "credential",
            authenticator.get(options, TestAuthenticator.Answer.right())));
    }

    @Test
    void theFourEntriesTakePasskeysOfTheDeploymentsHost() {
        for (String name : List.of("app", "sponsor", "admin", "operator")) {
            assertThat(entry(name)).as(name).containsEntry("passkeys", true);
        }
        // Shopify's embedded pages sign in with Shopify's session token only.
        assertThat(entry("shopify").get("passkeys")).isNotEqualTo(true);
        // The relying party is the root address's host.
        Map<String, Object> options = publicKey(post("/api/auth/passkey/options", null, Map.of("entry", "app")));
        assertThat(String.valueOf(options.get("rpId"))).isEqualTo("localhost");
    }

    @Test
    void aTakerSignsInWithTheirPasskeyAtTheAppOnly() {
        String name = unique("pia");
        String taker = userWithRole(name, QbRoles.TAKER);
        addPasskey(bearerOf(session(name, "app")));

        Map<String, Object> signedIn = passkeySignIn("app").expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(signedIn).containsKey("accessToken");
        assertThat(query("SELECT factor, entry FROM sec_login_record_version WHERE user_id = ?::uuid "
            + "ORDER BY attempt_no DESC LIMIT 1", taker).getFirst()).containsEntry("factor", "PASSKEY")
            .containsEntry("entry", "app");
        // The sponsor console does not take a taker, with a passkey or a password.
        assertThat(passkeySignIn("sponsor").returnResult(Void.class).getStatus().is4xxClientError()).isTrue();
    }

    @Test
    void aBannedTakersPasskeySignsNobodyIn() {
        String name = unique("ben");
        String taker = userWithRole(name, QbRoles.TAKER);
        addPasskey(bearerOf(session(name, "app")));
        String admin = userWithRole(unique("boss"), QbRoles.ADMIN_SUPER);
        run("QB_USER_BAN", tokenOf(admin, QbRoles.ADMIN_SUPER), body("userId", taker, "reason", "Cheating"));

        assertThat(refusalCode(passkeySignIn("app").expectStatus().isForbidden().expectBody(MAP).returnResult()
            .getResponseBody())).isEqualTo("SIGN_IN_REFUSED");
    }
}

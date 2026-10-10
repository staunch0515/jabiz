package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.ItSignInFixtures;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestOidcProvider;
import com.jabiz.security.SignInAttempt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sign-in through an identity provider into an entry (docs/design/10-security.md section 15; decision D36): the entry
 * is chosen when the sign-in starts and kept with its state; the callback cannot change it. The sign-in guards are
 * asked on this path too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "jabiz.security.entries.portal.accepted-roles=IT_OIDC_CUSTOMER")
class OidcEntryIT extends SecurityItSupport {

    static final TestOidcProvider IDP = new TestOidcProvider();

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        registry.add("jabiz.security.oidc.providers[0].id", () -> "test");
        registry.add("jabiz.security.oidc.providers[0].issuer", IDP::issuer);
        registry.add("jabiz.security.oidc.providers[0].client-id", () -> TestOidcProvider.CLIENT_ID);
        registry.add("jabiz.security.oidc.providers[0].client-secret", () -> TestOidcProvider.CLIENT_SECRET);
        registry.add("jabiz.security.oidc.providers[0].redirect-uri", () -> TestOidcProvider.REDIRECT_URI);
    }

    @AfterAll
    static void stop() {
        IDP.close();
    }

    private static String customerRole;

    private record Request(String state, String nonce, String challenge, String binder) {}

    private WebTestClient.ResponseSpec start(String entry) {
        return post("/api/auth/oidc/test/start" + (entry == null ? "" : "?entry=" + entry), null, Map.of());
    }

    private Request started(String entry) {
        Map<String, Object> started = start(entry).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        Map<String, String> query = TestOidcProvider.query((String) started.get("authorizationUrl"));
        return new Request(query.get("state"), query.get("nonce"), query.get("code_challenge"),
            (String) started.get("binder"));
    }

    private WebTestClient.ResponseSpec callback(Request request, String subject) {
        String idToken = IDP.idToken(IDP.claims(subject, request.nonce(), clock.instant()).build());
        return post("/api/auth/oidc/callback", null, Map.of("state", request.state(),
            "code", IDP.code(request.challenge(), idToken), "binder", request.binder(), "entry", "admin"));
    }

    /** A user with the portal's role and another one, linked to a subject; returns the user id. */
    private String linkedCustomer(String subject) {
        if (customerRole == null) {
            customerRole = createRole("IT_OIDC_CUSTOMER", "oidc.customer");
        }
        String name = unique("sso");
        @SuppressWarnings("unchecked")
        String userId = (String) ((Map<String, Object>) post("/api/processes/SEC_USER_CREATE/latest", admin(),
            Map.of("userName", name, "displayName", name)).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody().get("output")).get("userId");
        assign(userId, customerRole, null);
        assign(userId, createRole(unique("R"), "oidc.back"), null);
        insert(SecurityEntities.USER_IDENTITY_DATASET, Map.of("userId", userId, "provider", "test", "subject",
            subject), null);
        return userId;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theEntryChosenAtTheStartIsTheSessions() {
        String subject = unique("sub");
        String userId = linkedCustomer(subject);

        Request request = started("portal");
        assertThat(query("SELECT entry FROM sec_oidc_state WHERE state_hash = encode(sha256(?::bytea), 'hex')",
            request.state())).singleElement().satisfies(r -> assertThat(r).containsEntry("entry", "portal"));
        // The callback names another entry: it does not count.
        Map<String, Object> session = callback(request, subject).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat((List<String>) session.get("permissions")).containsExactly("oidc.customer");
        assertThat(tokens.verify((String) session.get("accessToken")).entry()).isEqualTo("portal");
        assertThat(query("SELECT entry, factor FROM sec_login_record_version WHERE user_id = ?::uuid", userId))
            .singleElement().satisfies(r -> assertThat(r).containsEntry("entry", "portal")
                .containsEntry("factor", "OIDC"));
        post("/api/auth/refresh", null, Map.of("refreshToken", session.get("refreshToken"), "entry", "portal"))
            .expectStatus().isOk();

        // Without an entry: the administration, with every role.
        Map<String, Object> admin = callback(started(null), subject).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat((List<String>) admin.get("permissions")).containsExactly("oidc.back", "oidc.customer");
    }

    @Test
    void anUnknownEntryCannotStart() {
        assertThat(ruleCode(start("nowhere").expectStatus().isUnauthorized().expectBody(MAP).returnResult()
            .getResponseBody())).isEqualTo("LOGIN_FAILED");
    }

    @Test
    void theGuardsAreAskedOnThisPathToo() {
        String subject = unique("sub");
        String userId = linkedCustomer(subject);
        insert(SecurityEntities.USER_IDENTITY_DATASET, Map.of("userId", userId, "provider", ItSignInFixtures.BLOCKED,
            "subject", unique("s")), null);

        assertThat(ruleCode(callback(started("portal"), subject).expectStatus().isForbidden().expectBody(MAP)
            .returnResult().getResponseBody())).isEqualTo("SIGN_IN_REFUSED");
        assertThat(ItSignInFixtures.SEEN.get(userId).factor()).isEqualTo(SignInAttempt.Factor.OIDC);
        assertThat(query("SELECT outcome FROM sec_login_record_version WHERE user_id = ?::uuid", userId))
            .singleElement().satisfies(r -> assertThat(r).containsEntry("outcome", "REFUSED"));
    }
}

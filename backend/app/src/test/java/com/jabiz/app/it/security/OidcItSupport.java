package com.jabiz.app.it.security;

import com.jabiz.runtime.test.TestOidcProvider;
import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Round trips through the test identity provider for the provisioning and linking tests (docs/design/10-security.md
 * section 12; decision D39; ROADMAP phase 16f): providers that open accounts at the portal ({@value #OPEN}), that do
 * not ({@value #CLOSED}), Google's rule for addresses ({@value #GOOGLE}) and one whose accounts the test guard refuses
 * ({@code it-blocked}), each with a callback for the entries
 * {@code admin}, {@code portal}, {@code loose} and {@value #STRICT} (a registering entry whose role requires a second
 * factor).
 */
abstract class OidcItSupport extends SignUpItSupport {

    static final String OPEN = "open";
    static final String CLOSED = "closed";
    static final String GOOGLE = "google";
    static final String STRICT = "strict";
    /** Opens accounts whose users the test guard then refuses (its link is to this provider). */
    static final String BLOCKED = com.jabiz.app.it.fixture.ItSignInFixtures.BLOCKED;
    static final String STRICT_ROLE = "IT_STRICT";

    /** The entries of {@link SignUpItSupport}, and {@value #STRICT}. */
    static final String STRICT_ACCEPTS = "jabiz.security.entries.strict.accepted-roles=" + STRICT_ROLE;
    static final String STRICT_REGISTERS = "jabiz.security.entries.strict.self-registration=true";
    static final String STRICT_GRANTS = "jabiz.security.entries.strict.registration-roles=" + STRICT_ROLE;

    /** A started round trip, with the callback its authorization request named. */
    record Request(String state, String nonce, String challenge, String binder, String redirectUri) {}

    abstract TestOidcProvider idp();

    static String callbackOf(String entry) {
        return "http://localhost/" + entry + "/login/oidc";
    }

    /** The three providers at the test provider. */
    static void providers(DynamicPropertyRegistry registry, TestOidcProvider idp) {
        List<String> ids = List.of(OPEN, CLOSED, GOOGLE, BLOCKED);
        for (int i = 0; i < ids.size(); i++) {
            String prefix = "jabiz.security.oidc.providers[" + i + "].";
            String id = ids.get(i);
            registry.add(prefix + "id", () -> id);
            registry.add(prefix + "issuer", idp::issuer);
            registry.add(prefix + "client-id", () -> TestOidcProvider.CLIENT_ID);
            registry.add(prefix + "client-secret", () -> TestOidcProvider.CLIENT_SECRET);
            registry.add(prefix + "redirect-uri", () -> TestOidcProvider.REDIRECT_URI);
            for (String entry : List.of(PORTAL, LOOSE, STRICT)) {
                registry.add(prefix + "redirect-uris." + entry, () -> callbackOf(entry));
            }
            registry.add(prefix + "labels.en", () -> "Label " + id);
            registry.add(prefix + "auto-provision", () -> String.valueOf(!CLOSED.equals(id)));
            if (GOOGLE.equals(id)) {
                registry.add(prefix + "kind", () -> "google");
            }
        }
    }

    /** Starts a sign-in through {@code provider} at {@code entry}. */
    Request start(String provider, String entry) {
        Map<String, Object> started = anonymous("/api/auth/oidc/" + provider + "/start?entry=" + entry, Map.of())
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return request(started);
    }

    static Request request(Map<String, Object> started) {
        Map<String, String> query = TestOidcProvider.query((String) started.get("authorizationUrl"));
        return new Request(query.get("state"), query.get("nonce"), query.get("code_challenge"),
            (String) started.get("binder"), query.get("redirect_uri"));
    }

    /** An ID token for the request's nonce, with the subject and whatever {@code change} adds. */
    String idToken(Request request, String subject, UnaryOperator<JWTClaimsSet.Builder> change) {
        return idp().idToken(change.apply(idp().claims(subject, request.nonce(), clock.instant())).build());
    }

    /** An ID token with a verified address (and a name). */
    String verified(Request request, String subject, String email) {
        return idToken(request, subject, c -> c.claim("email", email).claim("email_verified", true)
            .claim("name", "Ada\u0000 Lovelace"));
    }

    /** The provider sends the user back with a code for the token. */
    WebTestClient.ResponseSpec callback(Request request, String idToken) {
        Map<String, Object> body = new HashMap<>();
        body.put("state", request.state());
        body.put("code", idp().code(request.challenge(), idToken, request.redirectUri()));
        body.put("binder", request.binder());
        return anonymous("/api/auth/oidc/callback", body);
    }

    WebTestClient.ResponseSpec authorized(String method, String path, String bearer, Object body) {
        WebTestClient.RequestBodySpec request = client.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path).contentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, bearer);
        }
        return body == null ? request.exchange() : request.bodyValue(body).exchange();
    }

    /** The user linked to the subject at the provider (current links), or null. */
    static String linkedUser(String provider, String subject) {
        List<Map<String, Object>> rows = query("""
            SELECT user_id::text AS user_id FROM (SELECT DISTINCT ON (user_identity_id) user_id, is_deleted
                FROM sec_user_identity_version WHERE provider = ? AND subject = ?
                ORDER BY user_identity_id, version_no DESC) l WHERE NOT l.is_deleted""", provider, subject);
        return rows.isEmpty() ? null : String.valueOf(rows.getFirst().get("user_id"));
    }

    /** How many users have the address, regardless of case (every version). */
    static long usersWith(String email) {
        return ((Number) query("SELECT count(DISTINCT user_id) AS n FROM sec_user_version WHERE lower(email) = lower(?)",
            email).getFirst().get("n")).longValue();
    }
}

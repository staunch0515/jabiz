package com.jabiz.runtime.security;

import com.jabiz.runtime.test.TestOidcProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider-specific parts of talking to an identity provider (decision D61 item 1): Facebook's discovery
 * document at the path with a trailing slash, and its Graph API's token endpoint when the document names none; the
 * client's credentials in the token request's form for Facebook and LINE, Basic for everybody else.
 */
class OidcClientTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T09:00:00Z"), ZoneOffset.UTC);
    private static final String LONG_SECRET = "a-channel-secret-of-thirty-two-b";

    private static OidcProvider provider(String issuer, String kind, String secret) {
        return new OidcProvider(kind + "-p", issuer, TestOidcProvider.CLIENT_ID, secret, TestOidcProvider.REDIRECT_URI,
            null, null, null, null, kind, false);
    }

    @Test
    void facebooksDiscoveryHasATrailingSlashAndItsTokenEndpointIsTheGraphApisWhenNotNamed() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://localhost:" + server.getAddress().getPort();
        // Only the path with the slash answers, as Facebook's does.
        server.createContext("/fb/.well-known/openid-configuration/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = (path.endsWith("/") ? """
                {"issuer": "%s/fb", "authorization_endpoint": "%s/fb/dialog/oauth", "jwks_uri": "%s/fb/jwks"}"""
                .formatted(base, base, base) : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(path.endsWith("/") ? 200 : 404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            OidcClient.Metadata found = new OidcClient(CLOCK).metadata(provider(base + "/fb", "facebook", "s"))
                .block();
            assertThat(found.authorizationEndpoint()).isEqualTo(base + "/fb/dialog/oauth");
            assertThat(found.tokenEndpoint()).isEqualTo("https://graph.facebook.com/oauth/access_token");
            assertThat(found.jwksUri()).isEqualTo(base + "/fb/jwks");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void lineGetsTheClientCredentialsInTheFormAndAGenericProviderByBasic() {
        try (TestOidcProvider line = new TestOidcProvider(LONG_SECRET);
             TestOidcProvider generic = new TestOidcProvider()) {
            String token = "header.claims.signature";
            String verifier = "verifier-of-forty-three-characters-or-more-123456";
            assertThat(new OidcClient(CLOCK).tokens(provider(line.issuer(), "line", LONG_SECRET),
                line.code(challenge(verifier), token), verifier, TestOidcProvider.REDIRECT_URI).block().idToken())
                .isEqualTo(token);
            assertThat(line.clientAuthentication()).isEqualTo("post");
            assertThat(new OidcClient(CLOCK).tokens(provider(generic.issuer(), null, TestOidcProvider.CLIENT_SECRET),
                generic.code(challenge(verifier), token), verifier, TestOidcProvider.REDIRECT_URI).block().idToken())
                .isEqualTo(token);
            assertThat(generic.clientAuthentication()).isEqualTo("basic");
        }
    }

    @Test
    void appleGetsASignedSecretInTheFormNoVerifierAndGivesARefreshToken() {
        try (TestOidcProvider apple = new TestOidcProvider()) {
            String key = apple.asApple("TEAM123456", "KEY1234567");
            OidcProvider provider = new OidcProvider("apple", apple.issuer(), TestOidcProvider.CLIENT_ID, null, null,
                null, null, null, java.util.Map.of("app", "https://app.example.com/login/oidc"), "apple", true, false,
                new OidcProvider.Apple("TEAM123456", "KEY1234567", key,
                    "https://api.example.com/api/auth/oidc/apple/form-post"));
            OidcClient client = new OidcClient(CLOCK);
            OidcClient.Tokens tokens = client.tokens(provider, apple.code(null, "header.claims.signature",
                "https://api.example.com/api/auth/oidc/apple/form-post"), "unused-verifier",
                provider.providerRedirectUri("app")).block();
            assertThat(tokens.idToken()).isEqualTo("header.claims.signature");
            assertThat(tokens.refreshToken()).isEqualTo(apple.refreshTokens().getLast());
            assertThat(tokens).hasToString("Tokens[***]");
            assertThat(apple.clientAuthentication()).isEqualTo("post");

            // The authorization request asks for a form post, Apple's scopes and no PKCE.
            String url = client.authorizationUrl(provider, new OidcStateStore.Started("st", "no", "ch", "b"),
                provider.providerRedirectUri("app")).block();
            assertThat(TestOidcProvider.query(url)).containsEntry("response_mode", "form_post")
                .containsEntry("scope", "openid name email")
                .containsEntry("redirect_uri", "https://api.example.com/api/auth/oidc/apple/form-post")
                .doesNotContainKeys("code_challenge", "code_challenge_method");
        }
    }

    private static String challenge(String verifier) {
        try {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.jabiz.runtime.test;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An OpenID Connect provider for tests (docs/design/10-security.md section 12): discovery, JWKS and a token endpoint
 * on a local port. Tests make the ID token themselves ({@link #idToken}, any claims, any key) and register it for an
 * authorization code ({@link #code}); the token endpoint checks the client credentials, the redirect URI and the
 * PKCE verifier like a real provider, then hands the token out.
 */
public final class TestOidcProvider implements AutoCloseable {

    public static final String CLIENT_ID = "jabiz-test";
    public static final String CLIENT_SECRET = "test-client-secret";
    public static final String REDIRECT_URI = "http://localhost/login/oidc";

    private record Grant(String codeChallenge, String idToken) {}

    private final HttpServer server;
    private final String issuer;
    private final RSAKey rsa;
    private final ECKey ec;
    private final List<JWK> published = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private volatile int jwksReads;

    public TestOidcProvider() {
        try {
            rsa = new RSAKeyGenerator(2048).keyID("rsa-1").generate();
            ec = new ECKeyGenerator(Curve.P_256).keyID("ec-1").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        issuer = "http://localhost:" + server.getAddress().getPort() + "/idp";
        published.add(rsa.toPublicJWK());
        published.add(ec.toPublicJWK());
        server.createContext("/idp/.well-known/openid-configuration", exchange -> json(exchange, 200, """
            {"issuer": "%s", "authorization_endpoint": "%s/authorize", "token_endpoint": "%s/token",
             "jwks_uri": "%s/jwks"}""".formatted(issuer, issuer, issuer, issuer)));
        server.createContext("/idp/jwks", exchange -> {
            jwksReads++;
            json(exchange, 200, new JWKSet(List.copyOf(published)).toString(true));
        });
        server.createContext("/idp/token", this::token);
        server.start();
    }

    public String issuer() {
        return issuer;
    }

    /** How often the JWKS was read. */
    public int jwksReads() {
        return jwksReads;
    }

    /**
     * Publishes a new RSA key with a new key id, as a provider does when it rotates its keys (the old ones stay
     * published for tokens already out, and for the other tests of the class).
     */
    public RSAKey rotate() {
        try {
            RSAKey next = new RSAKeyGenerator(2048).keyID("rsa-" + UUID.randomUUID()).generate();
            published.add(0, next.toPublicJWK());
            return next;
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public RSAKey rsaKey() {
        return rsa;
    }

    public ECKey ecKey() {
        return ec;
    }

    /** Claims a correct token for the nonce would carry, valid for five minutes from {@code now}. */
    public JWTClaimsSet.Builder claims(String subject, String nonce, Instant now) {
        return new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(CLIENT_ID)
            .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).claim("nonce", nonce);
    }

    /** A token signed with RS256 by the published RSA key. */
    public String idToken(JWTClaimsSet claims) {
        return sign(claims, JWSAlgorithm.RS256, rsa);
    }

    /** A token signed as asked: RS256 / ES256 with the given key, HS256 with the given secret bytes. */
    public static String sign(JWTClaimsSet claims, JWSAlgorithm algorithm, Object key) {
        try {
            JWSSigner signer = switch (key) {
                case RSAKey rsaKey -> new RSASSASigner(rsaKey);
                case ECKey ecKey -> new ECDSASigner(ecKey);
                case byte[] secret -> new MACSigner(secret);
                default -> throw new IllegalArgumentException("Unsupported key " + key);
            };
            String keyId = key instanceof JWK jwk ? jwk.getKeyID() : null;
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).keyID(keyId)
                .build(), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Registers the token the code will be exchanged for; the verifier must match {@code codeChallenge}. */
    public String code(String codeChallenge, String idToken) {
        String code = UUID.randomUUID().toString();
        grants.put(code, new Grant(codeChallenge, idToken));
        return code;
    }

    /** The query parameters of an authorization URL, as the provider would read them. */
    public static Map<String, String> query(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        String query = url.substring(url.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    private void token(HttpExchange exchange) throws IOException {
        String expected = "Basic " + Base64.getEncoder().encodeToString((CLIENT_ID + ":" + CLIENT_SECRET)
            .getBytes(StandardCharsets.UTF_8));
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            json(exchange, 401, "{\"error\": \"invalid_client\"}");
            return;
        }
        Grant grant = grants.remove(String.valueOf(form.get("code")));
        if (grant == null || !"authorization_code".equals(form.get("grant_type"))
            || !REDIRECT_URI.equals(form.get("redirect_uri"))
            || !grant.codeChallenge().equals(challenge(String.valueOf(form.get("code_verifier"))))) {
            json(exchange, 400, "{\"error\": \"invalid_grant\"}");
            return;
        }
        json(exchange, 200, "{\"access_token\": \"at\", \"token_type\": \"Bearer\", \"id_token\": \""
            + grant.idToken() + "\"}");
    }

    private static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

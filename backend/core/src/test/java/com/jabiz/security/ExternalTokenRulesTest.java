package com.jabiz.security;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalTokenRulesTest {

    static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    static final byte[] OTHER = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
    static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");
    static final ExternalTokenRules.Settings SETTINGS = new ExternalTokenRules.Settings("app-key",
        Pattern.compile("[a-z0-9][a-z0-9-]*\\.myshopify\\.com"), "https://{tenant}/admin", "https://{tenant}",
        Duration.ofSeconds(10));
    static final String SIGNING_INPUT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0";

    static Map<String, Object> header() {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        return header;
    }

    static Map<String, Object> claims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "https://demo.myshopify.com/admin");
        claims.put("dest", "https://demo.myshopify.com");
        claims.put("aud", "app-key");
        claims.put("sub", "42");
        claims.put("exp", NOW.getEpochSecond() + 60);
        claims.put("nbf", NOW.getEpochSecond() - 1);
        claims.put("iat", NOW.getEpochSecond() - 1);
        claims.put("jti", "00000000-0000-0000-0000-000000000001");
        claims.put("sid", "s-1");
        return claims;
    }

    static ExternalTokenRules.Verified verify(Map<String, Object> header, Map<String, Object> claims, Instant now) {
        byte[] signature = ExternalTokenRules.hmac(SECRET, SIGNING_INPUT.getBytes(StandardCharsets.US_ASCII));
        return ExternalTokenRules.verify(header, claims, SIGNING_INPUT, signature, List.of(SECRET), SETTINGS, now);
    }

    static ExternalTokenRules.Failure failure(Consumer<Map<String, Object>> header, Consumer<Map<String, Object>> claims,
        Instant now) {
        Map<String, Object> h = header();
        Map<String, Object> c = claims();
        header.accept(h);
        claims.accept(c);
        try {
            verify(h, c, now);
        } catch (ExternalTokenRules.RefusedException e) {
            return e.failure();
        }
        throw new AssertionError("accepted");
    }

    static ExternalTokenRules.Failure claim(Consumer<Map<String, Object>> claims) {
        return failure(h -> { }, claims, NOW);
    }

    @Test
    void aSignedTokenOfOneTenantIsVerified() {
        ExternalTokenRules.Verified verified = verify(header(), claims(), NOW);
        assertThat(verified).isEqualTo(new ExternalTokenRules.Verified("demo.myshopify.com", "42",
            "00000000-0000-0000-0000-000000000001", NOW.plusSeconds(60)));
        // Numbers as JSON parsers give them; a list audience naming the application.
        Map<String, Object> claims = claims();
        claims.put("exp", BigDecimal.valueOf(NOW.getEpochSecond() + 60));
        claims.put("nbf", (int) (NOW.getEpochSecond() - 1));
        claims.put("aud", List.of("other", "app-key"));
        assertThat(verify(header(), claims, NOW).tenant()).isEqualTo("demo.myshopify.com");
        // No typ, no nbf: fine.
        Map<String, Object> header = header();
        header.remove("typ");
        claims.remove("nbf");
        assertThat(verify(header, claims, NOW).subject()).isEqualTo("42");
    }

    @Test
    void onlyHs256WithTheSecretIsAccepted() {
        assertThat(failure(h -> h.put("alg", "none"), c -> { }, NOW)).isEqualTo(ExternalTokenRules.Failure.ALGORITHM);
        assertThat(failure(h -> h.put("alg", "RS256"), c -> { }, NOW)).isEqualTo(ExternalTokenRules.Failure.ALGORITHM);
        assertThat(failure(h -> h.put("typ", "JOSE"), c -> { }, NOW)).isEqualTo(ExternalTokenRules.Failure.ALGORITHM);
        assertThat(failure(h -> h.put("crit", List.of("b64")), c -> { }, NOW))
            .isEqualTo(ExternalTokenRules.Failure.ALGORITHM);
        byte[] byOther = ExternalTokenRules.hmac(OTHER, SIGNING_INPUT.getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> ExternalTokenRules.verify(header(), claims(), SIGNING_INPUT, byOther,
            List.of(SECRET), SETTINGS, NOW)).isInstanceOfSatisfying(ExternalTokenRules.RefusedException.class,
                e -> assertThat(e.failure()).isEqualTo(ExternalTokenRules.Failure.SIGNATURE));
        // Any configured secret may have signed (one being replaced).
        assertThat(ExternalTokenRules.verify(header(), claims(), SIGNING_INPUT, byOther, List.of(SECRET, OTHER),
            SETTINGS, NOW).subject()).isEqualTo("42");
        // The signature covers the input as carried.
        byte[] signature = ExternalTokenRules.hmac(SECRET, SIGNING_INPUT.getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> ExternalTokenRules.verify(header(), claims(), SIGNING_INPUT + "x", signature,
            List.of(SECRET), SETTINGS, NOW)).isInstanceOf(ExternalTokenRules.RefusedException.class);
        // A short secret is a configuration error, not a refusal.
        assertThatThrownBy(() -> ExternalTokenRules.verify(header(), claims(), SIGNING_INPUT, signature,
            List.of(new byte[8]), SETTINGS, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExternalTokenRules.verify(null, claims(), SIGNING_INPUT, signature, List.of(SECRET),
            SETTINGS, NOW)).isInstanceOfSatisfying(ExternalTokenRules.RefusedException.class,
                e -> assertThat(e.failure()).isEqualTo(ExternalTokenRules.Failure.MALFORMED));
    }

    @Test
    void timesAreCheckedWithTheSkew() {
        Instant expiry = NOW.plusSeconds(60);
        assertThat(verify(header(), claims(), expiry.plusSeconds(9)).subject()).isEqualTo("42");
        assertThat(failure(h -> { }, c -> { }, expiry.plusSeconds(10))).isEqualTo(ExternalTokenRules.Failure.EXPIRED);
        assertThat(failure(h -> { }, c -> c.put("nbf", NOW.getEpochSecond() + 11), NOW))
            .isEqualTo(ExternalTokenRules.Failure.NOT_YET_VALID);
        assertThat(verify(header(), withNbf(NOW.getEpochSecond() + 10), NOW).subject()).isEqualTo("42");
        assertThat(claim(c -> c.remove("exp"))).isEqualTo(ExternalTokenRules.Failure.MALFORMED);
        assertThat(claim(c -> c.put("exp", "soon"))).isEqualTo(ExternalTokenRules.Failure.MALFORMED);
        assertThat(claim(c -> c.put("exp", new BigDecimal("1.5")))).isEqualTo(ExternalTokenRules.Failure.MALFORMED);
        assertThat(claim(c -> c.put("nbf", "now"))).isEqualTo(ExternalTokenRules.Failure.MALFORMED);
    }

    private static Map<String, Object> withNbf(long nbf) {
        Map<String, Object> claims = claims();
        claims.put("nbf", nbf);
        return claims;
    }

    @Test
    void issuerAndDestinationNameOneAllowedTenantForTheApplication() {
        assertThat(claim(c -> c.put("aud", "another-app"))).isEqualTo(ExternalTokenRules.Failure.AUDIENCE);
        assertThat(claim(c -> c.remove("aud"))).isEqualTo(ExternalTokenRules.Failure.AUDIENCE);
        assertThat(claim(c -> c.put("iss", "https://demo.myshopify.com"))).isEqualTo(ExternalTokenRules.Failure.ISSUER);
        assertThat(claim(c -> c.put("iss", "https://evil.example/admin"))).isEqualTo(ExternalTokenRules.Failure.ISSUER);
        assertThat(claim(c -> c.put("iss", "https://demo.myshopify.com.evil.example/admin")))
            .isEqualTo(ExternalTokenRules.Failure.ISSUER);
        assertThat(claim(c -> c.put("iss", "https:///admin"))).isEqualTo(ExternalTokenRules.Failure.ISSUER);
        assertThat(claim(c -> c.put("iss", 42))).isEqualTo(ExternalTokenRules.Failure.ISSUER);
        assertThat(claim(c -> c.put("dest", "https://other.myshopify.com")))
            .isEqualTo(ExternalTokenRules.Failure.DESTINATION);
        assertThat(claim(c -> c.remove("dest"))).isEqualTo(ExternalTokenRules.Failure.DESTINATION);
        assertThat(claim(c -> c.put("sub", " "))).isEqualTo(ExternalTokenRules.Failure.SUBJECT);
        assertThat(claim(c -> c.put("sub", 42))).isEqualTo(ExternalTokenRules.Failure.SUBJECT);
        assertThat(claim(c -> c.put("sub", "x".repeat(256)))).isEqualTo(ExternalTokenRules.Failure.SUBJECT);
        assertThat(claim(c -> c.remove("jti"))).isEqualTo(ExternalTokenRules.Failure.TOKEN_ID);
        assertThat(ExternalTokenRules.isTenant(SETTINGS, "demo.myshopify.com")).isTrue();
        assertThat(ExternalTokenRules.isTenant(SETTINGS, "demo.myshopify.com/x")).isFalse();
        assertThat(ExternalTokenRules.isTenant(SETTINGS, null)).isFalse();
    }

    @Test
    void settingsAreChecked() {
        Pattern tenant = SETTINGS.tenant();
        assertThatThrownBy(() -> new ExternalTokenRules.Settings("a", tenant, "https://admin", "https://{tenant}",
            null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalTokenRules.Settings("a", tenant, "https://{tenant}/{tenant}",
            "https://{tenant}", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalTokenRules.Settings("a", tenant, "https://{tenant}", "https://{tenant}",
            Duration.ofMinutes(2))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ExternalTokenRules.Settings("a", tenant, "https://{tenant}", "https://{tenant}", null).skew())
            .isEqualTo(Duration.ofSeconds(10));
        assertThat(new ExternalTokenRules.RefusedException(ExternalTokenRules.Failure.EXPIRED))
            .hasMessage("External token refused: expired");
    }
}

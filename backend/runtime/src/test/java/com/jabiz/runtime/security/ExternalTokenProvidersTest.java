package com.jabiz.runtime.security;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.web.JabizWebProperties;
import com.jabiz.security.ExternalTokenRules;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * External token providers, their startup check and the reading of their tokens (decision D59;
 * docs/design/10-security.md section 15.12).
 */
class ExternalTokenProvidersTest {

    static final String SECRET = "a-secret-of-thirty-two-characters-at-least";
    static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");

    static ExternalTokenProviders.Config config(String tenantPattern, List<String> secrets) {
        return new ExternalTokenProviders.Config(secrets, "app-key", tenantPattern, "https://{tenant}/admin",
            "https://{tenant}", null, null, List.of("https://admin.shop.example"), null);
    }

    static ExternalTokenProviders.Config shop() {
        return config("[a-z0-9][a-z0-9-]*\\.shop\\.example", List.of(SECRET));
    }

    static SignInEntries entries(String provider) {
        return new SignInEntries(Map.of("shop", new SignInEntries.Config(List.of("A"), true, List.of("A"), null, null,
            null, null, provider)), false, null);
    }

    @Test
    void everyProblemOfEveryProviderIsReportedAndOnlyGoodOnesAreUsed() {
        ExternalTokenProviders providers = new ExternalTokenProviders(Map.of(
            "shop", shop(),
            "Bad_Name", shop(),
            "weak", config("[a-z]+", List.of("short")),
            "broken", new ExternalTokenProviders.Config(List.of(), " ", "[", "http://{tenant}",
                "https://{tenant}/{tenant}", Duration.ofMinutes(5), true, List.of("https://admin.shop.example/x",
                "http://x.example"), "Bad Param")), () -> entries("nowhere"), () -> null);

        assertThat(providers.find("shop")).isPresent();
        assertThat(providers.find("weak")).isEmpty();
        assertThat(providers.find(null)).isEmpty();
        assertThat(providers.check()).allMatch(CheckProblem::isError).extracting(p -> p.location() + ": " + p.message())
            .containsExactlyInAnyOrder(
                "jabiz.security.external-tokens.Bad_Name: not a valid name (lower-case letters, digits and '-', at"
                    + " most 36, starting with a letter)",
                "jabiz.security.external-tokens.weak: secrets[0]: missing or shorter than 32 characters",
                "jabiz.security.external-tokens.broken: at least one secret is required",
                "jabiz.security.external-tokens.broken: audience is required",
                "jabiz.security.external-tokens.broken: tenant-pattern is not a valid pattern",
                "jabiz.security.external-tokens.broken: issuer-format must be an https address with {tenant} exactly"
                    + " once",
                "jabiz.security.external-tokens.broken: destination-format must be an https address with {tenant}"
                    + " exactly once",
                "jabiz.security.external-tokens.broken: skew must be between 0 and PT1M",
                "jabiz.security.external-tokens.broken: frame-ancestors[0] must be an https origin without path"
                    + " (https://host)",
                "jabiz.security.external-tokens.broken: frame-ancestors[1] must be an https origin without path"
                    + " (https://host)",
                "jabiz.security.external-tokens.broken: tenant-parameter is not a valid parameter name",
                "jabiz.security.entries.shop.external-token: names no configured provider: nowhere");
        // The secrets never show.
        assertThat(providers.find("shop").orElseThrow().toString()).doesNotContain(SECRET).contains("1 ***");
    }

    @Test
    void anEmbeddedSpaSignsInAtAnEntryTakingTheProvidersTokens() {
        JabizWebProperties web = new JabizWebProperties(List.of(
            new JabizWebProperties.Spa("/shop", null, null, "shop", null, "shop"),
            new JabizWebProperties.Spa("/other", null, null, "portal", null, "shop"),
            new JabizWebProperties.Spa("/gone", null, null, "shop", null, "gone")));
        ExternalTokenProviders providers = new ExternalTokenProviders(Map.of("shop", shop()), () -> entries("shop"),
            () -> web);
        assertThat(providers.check()).extracting(p -> p.location() + ": " + p.message()).containsExactlyInAnyOrder(
            "jabiz.web.spa[1].embedding: the SPA's entry (portal) must take the tokens of shop"
                + " (jabiz.security.entries.<entry>.external-token)",
            "jabiz.web.spa[2].embedding: names no configured provider: gone");
    }

    @Test
    void onlyATenantOfTheProviderMayFrameAndOnlyAsAnHttpsOrigin() {
        ExternalTokenProviders providers = new ExternalTokenProviders(Map.of("shop", shop(),
            "loose", config(".+", List.of(SECRET))), () -> null, () -> null);
        assertThat(providers.tenantParameter("shop")).contains("shop");
        assertThat(providers.tenantParameter("none")).isEmpty();
        assertThat(providers.frameAncestors("shop", "demo.shop.example"))
            .contains("https://demo.shop.example https://admin.shop.example");
        assertThat(providers.frameAncestors("shop", "evil.example")).isEmpty();
        assertThat(providers.frameAncestors("shop", null)).isEmpty();
        assertThat(providers.frameAncestors("none", "demo.shop.example")).isEmpty();
        // A pattern that lets anything through still never writes anything but an origin into the policy.
        assertThat(providers.frameAncestors("loose", "demo.example")).contains(
            "https://demo.example https://admin.shop.example");
        for (String tenant : List.of("x; script-src *", "x https://evil.example", "x/path", "X.example", "x'")) {
            assertThat(providers.frameAncestors("loose", tenant)).as(tenant).isEmpty();
        }
    }

    @Test
    void providersAreBoundFromTheEnvironment() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("jabiz.security.external-tokens.shop.secrets[0]", SECRET)
            .withProperty("jabiz.security.external-tokens.shop.audience", "app-key")
            .withProperty("jabiz.security.external-tokens.shop.tenant-pattern", "[a-z]+\\.shop\\.example")
            .withProperty("jabiz.security.external-tokens.shop.issuer-format", "https://{tenant}/admin")
            .withProperty("jabiz.security.external-tokens.shop.destination-format", "https://{tenant}")
            .withProperty("jabiz.security.external-tokens.shop.skew", "5s")
            .withProperty("jabiz.security.external-tokens.shop.auto-provision", "true")
            .withProperty("jabiz.security.external-tokens.shop.tenant-parameter", "store");
        ExternalTokenProvider provider = new ExternalTokenProviders(environment, new StaticProvider<>(null),
            new StaticProvider<>(null)).find("shop").orElseThrow();
        assertThat(provider.autoProvision()).isTrue();
        assertThat(provider.skew()).isEqualTo(Duration.ofSeconds(5));
        assertThat(provider.tenantParameter()).isEqualTo("store");
        assertThat(provider.identityProvider()).isEqualTo("ext:shop");
    }

    // Reading tokens: the parts, their JSON and the length; the rules themselves are ExternalTokenRules'.

    static final JsonMapper JSON = JsonMapper.builder().build();

    static String part(Object value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(value));
    }

    static String sign(String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return input + "." + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
    }

    static Map<String, Object> claims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "https://demo.shop.example/admin");
        claims.put("dest", "https://demo.shop.example");
        claims.put("aud", "app-key");
        claims.put("sub", "42");
        claims.put("exp", NOW.getEpochSecond() + 60);
        claims.put("jti", "j-1");
        return claims;
    }

    static ExternalTokenRules.Failure failure(ExternalTokens tokens, ExternalTokenProvider provider, String token) {
        try {
            tokens.verify(provider, token);
        } catch (ExternalTokenRules.RefusedException e) {
            return e.failure();
        }
        throw new AssertionError("accepted");
    }

    @Test
    void tokensAreReadStrictly() throws Exception {
        ExternalTokenProvider provider = new ExternalTokenProviders(Map.of("shop", shop()), () -> null, () -> null)
            .find("shop").orElseThrow();
        ExternalTokens tokens = new ExternalTokens(null, "default", Clock.fixed(NOW, ZoneOffset.UTC));
        String header = part(Map.of("alg", "HS256", "typ", "JWT"));
        String good = sign(header + "." + part(claims()));
        ExternalTokenRules.Verified verified = tokens.verify(provider, good);
        assertThat(ExternalTokenProvider.identitySubject(verified)).isEqualTo("demo.shop.example/42");

        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"sub\":\"1\",\"sub\":\"2\"}".getBytes(StandardCharsets.UTF_8));
        String trailing = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"sub\":\"1\"} {}".getBytes(StandardCharsets.UTF_8));
        String array = part(List.of(1, 2));
        for (String token : List.of(
            sign(header + "." + body),
            sign(header + "." + trailing),
            sign(header + "." + array),
            good + "=",
            good.replace(".", ".."),
            header + "." + part(claims()),
            good + ".x",
            "a".repeat(ExternalTokens.MAX_TOKEN_LENGTH + 1))) {
            assertThat(failure(tokens, provider, token)).as(token.length() > 60 ? token.substring(0, 60) : token)
                .isEqualTo(ExternalTokenRules.Failure.MALFORMED);
        }
        assertThat(failure(tokens, provider, null)).isEqualTo(ExternalTokenRules.Failure.MALFORMED);
        // An identity that would not fit the identity's subject.
        Map<String, Object> long_ = claims();
        long_.put("sub", "x".repeat(240));
        assertThat(failure(tokens, provider, sign(header + "." + part(long_))))
            .isEqualTo(ExternalTokenRules.Failure.SUBJECT);
        assertThatThrownBy(() -> tokens.verify(provider, sign(header + "." + part(Map.of("sub", "1")))))
            .isInstanceOf(ExternalTokenRules.RefusedException.class);
    }

    /** An {@link org.springframework.beans.factory.ObjectProvider} of a fixed value. */
    record StaticProvider<T>(T value) implements org.springframework.beans.factory.ObjectProvider<T> {
        @Override
        public T getObject() {
            return value;
        }

        @Override
        public T getObject(Object... args) {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }
    }
}

package com.jabiz.runtime.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The settings of passkeys (decision D71 item 6): nothing set, or a relying party its origins are under. */
class PasskeyPropertiesTest {

    private static PasskeyProperties of(String rpId, List<String> origins) {
        return new PasskeyProperties(rpId, null, origins, null, null);
    }

    @Test
    void nothingSetIsNoProblemAndNoPasskeys() {
        PasskeyProperties none = PasskeyProperties.none();
        assertThat(none.problems()).isEmpty();
        assertThat(none.configured()).isFalse();
        assertThat(none.challengeTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(none.maxPerUser()).isEqualTo(10);
    }

    @Test
    void theOriginsAreHttpsHostsUnderTheRelyingParty() {
        PasskeyProperties good = of(" Example.COM ", List.of("https://example.com", "https://app.example.com:8443",
            "http://localhost:5173"));
        assertThat(good.rpId()).isEqualTo("example.com");
        assertThat(good.rpName()).isEqualTo("example.com");
        assertThat(good.problems()).containsExactly(
            "origin 'http://localhost:5173' is not under rp-id example.com: its pages could not use the passkeys");
        assertThat(of("example.com", List.of("https://app.example.com")).configured()).isTrue();
        assertThat(of("localhost", List.of("http://localhost")).problems()).isEmpty();

        assertThat(of("example.com", List.of("http://app.example.com", "https://app.example.com/login",
            "https://notexample.com", "https://example.com.evil.net", "https://user@example.com", "nonsense")).problems())
            .containsExactly(
                "origin 'http://app.example.com' must be https://host[:port] (http only for localhost), without a path",
                "origin 'https://app.example.com/login' must be https://host[:port] (http only for localhost), without "
                    + "a path",
                "origin 'https://notexample.com' is not under rp-id example.com: its pages could not use the passkeys",
                "origin 'https://example.com.evil.net' is not under rp-id example.com: its pages could not use the "
                    + "passkeys",
                "origin 'https://user@example.com' must be https://host[:port] (http only for localhost), without a "
                    + "path",
                "origin 'nonsense' must be https://host[:port] (http only for localhost), without a path");
    }

    @Test
    void halfSetUpAndOutOfRangeAreReported() {
        assertThat(of(null, List.of("https://example.com")).problems())
            .containsExactly("rp-id is not set: it is the domain the passkeys are bound to");
        assertThat(of("example.com", null).problems()).containsExactly("origins is empty: no page could use a passkey");
        assertThat(of("https://example.com", List.of("https://example.com")).problems()).first().asString()
            .startsWith("rp-id must be a domain");
        assertThat(new PasskeyProperties(null, null, null, Duration.ofSeconds(5), 0).problems()).containsExactly(
            "challenge-ttl must be between 30 seconds and 15 minutes, not PT5S",
            "max-per-user must be between 1 and 50, not 0");
    }
}

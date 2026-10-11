package com.jabiz.external;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalServiceTest {

    private static ExternalService demo() {
        return ExternalService.define("demo-ai", s -> s.baseUrl("https://ai.example.com/v1")
            .allowedHosts("AI.example.com ", "api.example.com")
            .secret("apiKey", "JABIZ_DEMO_AI_KEY")
            .connectTimeout(ofSeconds(5)).requestTimeout(ofSeconds(30))
            .idempotencyHeader("Idempotency-Key").maxResponseSize(4 * 1024 * 1024));
    }

    @Test
    void aDeclarationKeepsWhatItSays() {
        ExternalService service = demo();
        assertThat(service.name()).isEqualTo("demo-ai");
        assertThat(service.baseUrl()).isEqualTo(URI.create("https://ai.example.com/v1"));
        assertThat(service.allowedHosts()).containsExactly("ai.example.com", "api.example.com");
        assertThat(service.secrets()).containsExactly(Map.entry("apiKey", "JABIZ_DEMO_AI_KEY"));
        assertThat(service.connectTimeout()).isEqualTo(ofSeconds(5));
        assertThat(service.requestTimeout()).isEqualTo(ofSeconds(30));
        assertThat(service.idempotencyHeader()).isEqualTo("Idempotency-Key");
        assertThat(service.maxResponseSize()).isEqualTo(4 * 1024 * 1024);
        assertThat(service.allowInDryRun()).isFalse();
        assertThat(service.problems()).isEmpty();
        assertThat(service.allowsHost("Api.Example.com")).isTrue();
        assertThat(service.allowsHost("evil.example.com")).isFalse();
    }

    @Test
    void defaultsAreBounded() {
        ExternalService service = ExternalService.define("plain", s -> s.baseUrl("https://x.example.com")
            .allowedHosts("x.example.com"));
        assertThat(service.connectTimeout()).isEqualTo(ExternalService.DEFAULT_CONNECT_TIMEOUT);
        assertThat(service.requestTimeout()).isEqualTo(ExternalService.DEFAULT_REQUEST_TIMEOUT);
        assertThat(service.maxResponseSize()).isEqualTo(ExternalService.DEFAULT_MAX_RESPONSE_SIZE);
        assertThat(service.idempotencyHeader()).isNull();
        assertThat(service.secrets()).isEmpty();
        assertThat(service.problems()).isEmpty();
        assertThat(ExternalService.define("dry", s -> s.baseUrl("https://x.example.com").allowedHosts("x.example.com")
            .allowInDryRun()).allowInDryRun()).isTrue();
    }

    @Test
    void namesMustBeShortLowerCaseWords() {
        for (String bad : List.of("Demo", "1ai", "-ai", "ai_x", "a".repeat(41), "")) {
            ExternalService service = ExternalService.define(bad, s -> s.baseUrl("https://x.example.com")
                .allowedHosts("x.example.com"));
            assertThat(service.problems()).as(bad).containsExactly("name: '" + bad + "' must match "
                + ExternalService.NAME.pattern());
        }
        assertThat(ExternalService.define("a" + "b".repeat(39), s -> s.baseUrl("https://x.example.com")
            .allowedHosts("x.example.com")).problems()).isEmpty();
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        ExternalService service = ExternalService.define("bad", s -> s
            .secret("api key", "lower_case")
            .secret("token", "1TOKEN")
            .connectTimeout(Duration.ZERO)
            .requestTimeout(ExternalService.MAX_REQUEST_TIMEOUT.plusSeconds(1))
            .idempotencyHeader("Bad Header")
            .maxResponseSize(0));
        assertThat(service.problems()).containsExactly(
            "allowedHosts: name at least one host",
            "baseUrl: missing",
            "secret 'api key': name must match " + ExternalService.SECRET_NAME.pattern(),
            "secret 'api key': environment variable 'lower_case' must match " + ExternalService.ENV_NAME.pattern(),
            "secret token: environment variable '1TOKEN' must match " + ExternalService.ENV_NAME.pattern(),
            "connectTimeout: must be positive and at most PT30S",
            "requestTimeout: must be positive and at most PT2M",
            "idempotencyHeader: 'Bad Header' is not a header name a request may set",
            "maxResponseSize: must be between 1 and 16777216 bytes");
    }

    @Test
    void hostsAreNamesOnly() {
        ExternalService service = ExternalService.define("hosts", s -> s.baseUrl("https://x.example.com")
            .allowedHosts("x.example.com", "https://y.example.com", "z.example.com:443", "a/b", "[::1]", "localhost"));
        assertThat(service.problems()).containsExactly(
            "allowedHosts: 'https://y.example.com' is not a host name",
            "allowedHosts: 'z.example.com:443' is not a host name",
            "allowedHosts: 'a/b' is not a host name");
    }

    @Test
    void overridesReplaceTheAddressAndTheHostsOnly() {
        ExternalService overridden = demo().withOverrides(URI.create("http://localhost:8091/v1"),
            List.of("LOCALHOST"));
        assertThat(overridden.baseUrl()).isEqualTo(URI.create("http://localhost:8091/v1"));
        assertThat(overridden.allowedHosts()).containsExactly("localhost");
        assertThat(overridden.secrets()).isEqualTo(demo().secrets());
        assertThat(demo().withOverrides(null, null)).isEqualTo(demo());
        assertThat(demo().withOverrides(null, List.of()).allowedHosts()).containsExactly("ai.example.com",
            "api.example.com");
    }

    @Test
    void theIdempotencyHeaderCannotBeOneTheClientSets() {
        assertThat(ExternalService.define("h", s -> s.baseUrl("https://x.example.com").allowedHosts("x.example.com")
            .idempotencyHeader("Host")).problems())
            .containsExactly("idempotencyHeader: 'Host' is not a header name a request may set");
    }

    @Test
    void theDescriptionLeavesOutCredentialsQueriesAndSecretVariables() {
        ExternalService service = ExternalService.define("q", s -> s
            .baseUrl("https://user:pw@x.example.com:8443/v1?key=s3cr3t#frag").allowedHosts("x.example.com")
            .secret("apiKey", "Q_KEY"));
        assertThat(service.toString()).contains("https://x.example.com:8443/v1", "secrets=[apiKey]")
            .doesNotContain("s3cr3t", "pw", "frag", "Q_KEY");
    }

    @Test
    void aMalformedAddressFailsAtOnce() {
        assertThatThrownBy(() -> ExternalService.define("x", s -> s.baseUrl("https://bad host/")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

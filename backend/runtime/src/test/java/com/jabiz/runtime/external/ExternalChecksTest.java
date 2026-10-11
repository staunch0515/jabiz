package com.jabiz.runtime.external;

import com.jabiz.external.ExternalService;
import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalChecksTest {

    private static ExternalService demo() {
        return ExternalService.define("demo-ai", s -> s.baseUrl("https://ai.example.com/v1")
            .allowedHosts("ai.example.com").secret("apiKey", "JABIZ_DEMO_AI_KEY"));
    }

    private static List<String> problems(MockEnvironment environment, ExternalService... services) {
        return new ExternalChecks(new ExternalServiceRegistry(List.of(services), environment)).check().stream()
            .map(CheckProblem::format).toList();
    }

    @Test
    void aWellDeclaredServicePasses() {
        assertThat(problems(new MockEnvironment().withProperty("JABIZ_DEMO_AI_KEY", "k"), demo())).isEmpty();
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        ExternalService plainHttp = ExternalService.define("plain", s -> s.baseUrl("http://plain.example.com")
            .allowedHosts("plain.example.com").requestTimeout(Duration.ofMinutes(10)));
        ExternalService elsewhere = ExternalService.define("elsewhere", s -> s.baseUrl("https://other.example.com")
            .allowedHosts("allowed.example.com", "bad host"));
        ExternalService credentials = ExternalService.define("creds", s -> s
            .baseUrl("https://user:pw@creds.example.com").allowedHosts("creds.example.com"));
        ExternalService noHosts = ExternalService.define("Bad_Name", s -> s.baseUrl("https://x.example.com"));
        MockEnvironment environment = new MockEnvironment()
            .withProperty("jabiz.external-services.ghost.base-url", "https://ghost.example.com");
        ExternalService badTwin = ExternalService.define("demo-ai", s -> s.baseUrl("http://twin.example.com")
            .allowedHosts("twin.example.com"));
        assertThat(problems(environment, demo(), badTwin, plainHttp, elsewhere, credentials, noHosts)).containsExactly(
            "EXTERNAL | demo-ai | secret apiKey: the environment variable JABIZ_DEMO_AI_KEY is not set (set it, or run "
                + "with the dev profile)",
            "EXTERNAL | demo-ai | declared twice",
            "EXTERNAL | demo-ai | baseUrl: not https (http only to this machine): http://twin.example.com",
            "EXTERNAL | plain | requestTimeout: must be positive and at most PT2M",
            "EXTERNAL | plain | baseUrl: not https (http only to this machine): http://plain.example.com",
            "EXTERNAL | elsewhere | allowedHosts: 'bad host' is not a host name",
            "EXTERNAL | elsewhere | baseUrl: host other.example.com is not in the allowed hosts of external service "
                + "elsewhere",
            "EXTERNAL | creds | baseUrl: carries credentials",
            "EXTERNAL | Bad_Name | name: 'Bad_Name' must match [a-z][a-z0-9-]{0,39}",
            "EXTERNAL | Bad_Name | allowedHosts: name at least one host",
            "EXTERNAL | Bad_Name | baseUrl: host x.example.com is not in the allowed hosts of external service "
                + "Bad_Name",
            "EXTERNAL | jabiz.external-services.ghost | no external service of this name is declared");
    }

    @Test
    void missingSecretsAreWarningsInDevelopmentAndInTheStaticCheck() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThat(problems(dev, demo())).containsExactly(
            "WARNING EXTERNAL | demo-ai | secret apiKey: the environment variable JABIZ_DEMO_AI_KEY is not set; calls "
                + "that use the secret will fail");
        assertThat(problems(new MockEnvironment().withProperty("jabiz.external.missing-secrets", "warning"), demo()))
            .hasSize(1).allMatch(problem -> problem.startsWith("WARNING "));
        assertThat(problems(new MockEnvironment().withProperty("JABIZ_DEMO_AI_KEY", "  "), demo()))
            .containsExactly("EXTERNAL | demo-ai | secret apiKey: the environment variable JABIZ_DEMO_AI_KEY is not "
                + "set (set it, or run with the dev profile)");
    }

    /** The static check runs without the deployment's environment, so it does not look at secrets at all. */
    @Test
    void theStaticCheckIgnoresSecrets() {
        assertThat(problems(new MockEnvironment().withProperty("jabiz.external.missing-secrets", "ignore"), demo()))
            .isEmpty();
        MockEnvironment dev = new MockEnvironment().withProperty("jabiz.external.missing-secrets", "IGNORE");
        dev.setActiveProfiles("dev");
        assertThat(problems(dev, demo())).isEmpty();
        assertThat(problems(new MockEnvironment().withProperty("jabiz.external.missing-secrets", "sometimes")
            .withProperty("JABIZ_DEMO_AI_KEY", "k"), demo()))
            .containsExactly("EXTERNAL | jabiz.external.missing-secrets | must be error, warning or ignore");
    }

    /** Settings that cannot be converted are problems of the check, reported with the others, not a failed start. */
    @Test
    void settingsThatAreNotAddressesAreReportedWithTheRest() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("jabiz.external-services.demo-ai.base-url", "http://bad host:x/?key=s3cr3t")
            .withProperty("jabiz.external-services.other.base-url", "relative/path");
        List<String> problems = problems(environment, demo(), ExternalService.define("other", s -> s
            .baseUrl("https://other.example.com").allowedHosts("other.example.com")));
        assertThat(problems).contains(
            "EXTERNAL | jabiz.external-services.demo-ai.base-url | not an absolute address; the declared one is kept",
            "EXTERNAL | jabiz.external-services.other.base-url | not an absolute address; the declared one is kept")
            .anyMatch(problem -> problem.contains("JABIZ_DEMO_AI_KEY"))
            .noneMatch(problem -> problem.contains("s3cr3t"));
    }

    @Test
    void theEnvironmentMayPointAServiceElsewhere() {
        MockEnvironment environment = new MockEnvironment().withProperty("JABIZ_DEMO_AI_KEY", "k")
            .withProperty("jabiz.external-services.demo-ai.base-url", "http://localhost:8091/v1")
            .withProperty("jabiz.external-services.demo-ai.allowed-hosts", "localhost, 127.0.0.1");
        ExternalServiceRegistry registry = new ExternalServiceRegistry(List.of(demo()), environment);
        ExternalService effective = registry.find("demo-ai").orElseThrow();
        assertThat(effective.baseUrl()).isEqualTo(URI.create("http://localhost:8091/v1"));
        assertThat(effective.allowedHosts()).containsExactly("localhost", "127.0.0.1");
        assertThat(new ExternalChecks(registry).check()).isEmpty();
        assertThat(registry.find("nothing")).isEmpty();
    }

    @Test
    void secretsAreReadOnceAndNeverPrinted() {
        MockEnvironment environment = new MockEnvironment().withProperty("JABIZ_DEMO_AI_KEY", "sk-very-secret");
        ExternalServiceRegistry registry = new ExternalServiceRegistry(List.of(demo()), environment);
        environment.setProperty("JABIZ_DEMO_AI_KEY", "changed later");
        assertThat(registry.secret("demo-ai", "apiKey")).contains("sk-very-secret");
        assertThat(registry.toString()).doesNotContain("sk-very-secret");
        assertThat(registry.secret("demo-ai", "other")).isEmpty();
    }
}

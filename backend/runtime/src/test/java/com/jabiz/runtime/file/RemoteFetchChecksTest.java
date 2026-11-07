package com.jabiz.runtime.file;

import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The startup check of {@code jabiz.files.fetch.*} (decision D75). */
class RemoteFetchChecksTest {

    private static List<String> problems(RemoteFetchProperties properties) {
        return new RemoteFetchChecks(properties).check().stream()
            .map(p -> p.severity() + " " + p.location() + ": " + p.message()).toList();
    }

    @Test
    void theDefaultsPass() {
        RemoteFetchProperties defaults = new RemoteFetchProperties(null, null, null, null, null, null, null);
        assertThat(problems(defaults)).isEmpty();
        assertThat(defaults.maxRedirects()).isEqualTo(3);
        assertThat(defaults.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.readTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(defaults.deadline()).isEqualTo(Duration.ofSeconds(60));
        assertThat(defaults.ports()).containsExactly(443);
        assertThat(defaults.maxConcurrent()).isEqualTo(16);
        assertThat(defaults.maxConcurrentPerUser()).isEqualTo(2);
    }

    @Test
    void everyValueOutOfRangeIsReportedAtOnce() {
        List<String> problems = problems(new RemoteFetchProperties(11, Duration.ZERO, Duration.ofMinutes(3),
            Duration.ofMinutes(10), Arrays.asList(443, 0, 70_000, 8443), 0, 2));
        assertThat(problems).containsExactlyInAnyOrder(
            "ERROR jabiz.files.fetch.max-redirects: must be 0 to 10, not 11",
            "ERROR jabiz.files.fetch.connect-timeout: must be positive and at most PT30S, not PT0S",
            "ERROR jabiz.files.fetch.read-timeout: must be positive and at most PT2M, not PT3M",
            "ERROR jabiz.files.fetch.deadline: must be at least the read timeout (PT3M) and at most PT5M, not PT10M",
            "ERROR jabiz.files.fetch.allowed-ports: must be ports 1 to 65535, not 0",
            "ERROR jabiz.files.fetch.max-concurrent: must be 1 to 1024, not 0",
            "ERROR jabiz.files.fetch.max-concurrent-per-user: must be 1 to max-concurrent (0), not 2",
            "ERROR jabiz.files.fetch.allowed-ports: must be ports 1 to 65535, not 70000",
            "WARNING jabiz.files.fetch.allowed-ports: allows port 8443: links to public hosts on ports other than 443"
                + " are followed");
        assertThat(problems(new RemoteFetchProperties(-1, null, Duration.ofSeconds(30), Duration.ofSeconds(10),
            null, 4, 5))).containsExactlyInAnyOrder("ERROR jabiz.files.fetch.max-redirects: must be 0 to 10, not -1",
            "ERROR jabiz.files.fetch.deadline: must be at least the read timeout (PT30S) and at most PT5M, not PT10S",
            "ERROR jabiz.files.fetch.max-concurrent-per-user: must be 1 to max-concurrent (4), not 5");
        assertThat(CheckProblem.class).isNotNull();
    }
}

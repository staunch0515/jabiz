package com.jabiz.external;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalJobCancellationTest {

    @Test
    void itKeepsWhatItIsGivenAndCopiesTheUsage() {
        Map<String, Long> usage = new HashMap<>(Map.of("promptTokens", 12L));
        Instant finished = Instant.parse("2026-10-19T10:00:00Z");
        Instant cancelled = Instant.parse("2026-10-19T09:59:00Z");
        ExternalJobCancellation cancellation = new ExternalJobCancellation("job-1", "it.type", "alice", "ref", usage, 1,
            finished, "bob", cancelled, true);
        usage.put("completionTokens", 34L);
        assertThat(cancellation.usage()).containsExactly(Map.entry("promptTokens", 12L));
        assertThat(cancellation.usage("promptTokens")).isEqualTo(12L);
        assertThat(cancellation.usage("completionTokens")).isZero();
        assertThat(cancellation.cancelledBy()).isEqualTo("bob");
        assertThat(cancellation.cancelledTime()).isEqualTo(cancelled);
        assertThat(cancellation.finishedTime()).isEqualTo(finished);
        assertThat(cancellation.attemptRan()).isTrue();
        assertThatThrownBy(() -> cancellation.usage().put("x", 1L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void noUsageIsAnEmptyMap() {
        ExternalJobCancellation cancellation = new ExternalJobCancellation("job-1", "it.type", "alice", null, null, 0,
            Instant.EPOCH, "alice", Instant.EPOCH, false);
        assertThat(cancellation.usage()).isEmpty();
        assertThat(cancellation.attemptRan()).isFalse();
    }
}

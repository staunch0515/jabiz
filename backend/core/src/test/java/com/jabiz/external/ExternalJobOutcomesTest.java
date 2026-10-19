package com.jabiz.external;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The count of attempts without recorded usage on the inputs of the completion and failure processes (decision D52
 * item 5): the constructors that came before it count none, and {@code toString} still leaves out the result and the
 * handler's message.
 */
class ExternalJobOutcomesTest {

    private static final Instant FINISHED = Instant.parse("2026-10-19T10:00:00Z");

    record Answer(String text) {}

    @Test
    void theOldCompletionConstructorsCountNoUnrecordedAttempts() {
        ExternalJobCompletion eight = new ExternalJobCompletion("job-1", "it.type", "alice", "ref", new Answer("a"),
            Map.of("units", 3L), 1, FINISHED);
        UUID file = UUID.randomUUID();
        ExternalJobCompletion nine = new ExternalJobCompletion("job-1", "it.type", "alice", "ref", new Answer("a"),
            Map.of("units", 3L), 1, FINISHED, Map.of("image", file));
        assertThat(eight.unrecordedAttempts()).isZero();
        assertThat(eight.hasUnrecordedAttempts()).isFalse();
        assertThat(nine.unrecordedAttempts()).isZero();
        assertThat(nine.file("image")).isEqualTo(file);
        assertThat(nine).isEqualTo(new ExternalJobCompletion("job-1", "it.type", "alice", "ref", new Answer("a"),
            Map.of("units", 3L), 1, FINISHED, Map.of("image", file), 0));
    }

    @Test
    void aCompletionTellsWhetherAnAttemptEndedWithoutUsage() {
        ExternalJobCompletion completion = new ExternalJobCompletion("job-1", "it.type", "alice", null,
            new Answer("personal answer"), Map.of("units", 3L), 3, FINISHED, null, 2);
        assertThat(completion.unrecordedAttempts()).isEqualTo(2);
        assertThat(completion.hasUnrecordedAttempts()).isTrue();
        assertThat(completion.files()).isEmpty();
        assertThat(completion.toString()).contains("unrecordedAttempts=2", "attempts=3", "units=3")
            .doesNotContain("personal answer");
        assertThatThrownBy(() -> new ExternalJobCompletion("job-1", "it.type", "alice", null, new Answer("a"),
            Map.of(), 1, FINISHED, Map.of(), -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theOldFailureConstructorCountsNoUnrecordedAttempts() {
        ExternalJobFailure old = new ExternalJobFailure("job-1", "it.type", "alice", null, Map.of(), 2, FINISHED,
            "TIMEOUT", "no result");
        assertThat(old.unrecordedAttempts()).isZero();
        assertThat(old.hasUnrecordedAttempts()).isFalse();
        assertThat(old).isEqualTo(new ExternalJobFailure("job-1", "it.type", "alice", null, Map.of(), 2, FINISHED,
            "TIMEOUT", "no result", 0));
    }

    @Test
    void aFailureTellsWhetherAnAttemptEndedWithoutUsage() {
        ExternalJobFailure failure = new ExternalJobFailure("job-1", "it.type", "alice", null, null, 2, FINISHED,
            "LEASE_EXPIRED", "the handler said something personal", 2);
        assertThat(failure.unrecordedAttempts()).isEqualTo(2);
        assertThat(failure.hasUnrecordedAttempts()).isTrue();
        assertThat(failure.usage()).isEmpty();
        assertThat(failure.toString()).contains("unrecordedAttempts=2", "errorCode=LEASE_EXPIRED", "*** (")
            .doesNotContain("personal");
        assertThat(new ExternalJobFailure("job-1", "it.type", "alice", null, null, 1, FINISHED, "X", null, 0)
            .toString()).contains("errorMessage=null");
        assertThatThrownBy(() -> new ExternalJobFailure("job-1", "it.type", "alice", null, null, 1, FINISHED, "X",
            null, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}

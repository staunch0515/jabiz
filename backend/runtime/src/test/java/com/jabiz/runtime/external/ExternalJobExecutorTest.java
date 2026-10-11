package com.jabiz.runtime.external;

import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** The parts of the executor that need no database: the waits it hands to SQL, and how failures are recorded. */
class ExternalJobExecutorTest {

    record In(String id) {}

    record Out(String text) {}

    private static ExternalJobType<In, Out> type(int attempts) {
        return ExternalJobType.define("it.type", In.class, Out.class, t -> t
            .retry(attempts, Duration.ofSeconds(10), Duration.ofSeconds(50)).onCompleted("A").onFailed("B"));
    }

    /** The SQL compares with exactly what {@link ExternalJobType#backoffAfter} says (its property test covers it). */
    @Test
    void theWaitsAreTheTypesBackoffInMilliseconds() {
        ExternalJobType<In, Out> type = type(5);
        Long[] waits = ExternalJobExecutor.waits(type);
        assertThat(waits).containsExactly(10_000L, 20_000L, 40_000L, 50_000L, 50_000L);
        for (int failures = 1; failures <= waits.length; failures++) {
            assertThat(waits[failures - 1]).isEqualTo(type.backoffAfter(failures).toMillis());
        }
        assertThat(ExternalJobExecutor.waits(type(1))).containsExactly(10_000L);
    }

    @Test
    void failuresAreRecordedWithTheirCodeAndWhetherToRetry() {
        ExternalJobType<In, Out> type = type(3);
        assertThat(ExternalJobExecutor.failure(type, new TimeoutException()))
            .satisfies(a -> {
                assertThat(a.outcome()).isEqualTo(ExternalJobRecorder.AttemptOutcome.TIMED_OUT);
                assertThat(a.retryable()).isTrue();
                assertThat(a.errorCode()).isEqualTo("TIMEOUT");
            });
        assertThat(ExternalJobExecutor.failure(type, ExternalJobException.permanent("NOPE", "refused")))
            .satisfies(a -> assertThat(a.retryable()).isFalse());
        assertThat(ExternalJobExecutor.failure(type, new ExternalCallException("svc",
            ExternalCallException.Kind.REFUSED, "not allowed"))).satisfies(a -> {
                assertThat(a.retryable()).isFalse();
                assertThat(a.errorCode()).isEqualTo("EXTERNAL_CALL_FAILED");
            });
        assertThat(ExternalJobExecutor.failure(type, new ExternalCallException("svc",
            ExternalCallException.Kind.UNAVAILABLE, "down"))).satisfies(a -> assertThat(a.retryable()).isTrue());
        assertThat(ExternalJobExecutor.failure(type, new IllegalStateException("token sk-123 leaked")))
            .satisfies(a -> {
                assertThat(a.errorCode()).isEqualTo("HANDLER_ERROR");
                assertThat(a.errorDetail()).isEqualTo("IllegalStateException");
            });
    }
}

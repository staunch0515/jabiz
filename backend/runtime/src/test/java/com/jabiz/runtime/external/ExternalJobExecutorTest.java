package com.jabiz.runtime.external;

import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobType;
import com.jabiz.external.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

    /** Files a handler returns beyond its type's declaration fail the attempt for good, before any is stored (D50). */
    @Test
    void filesBeyondTheDeclarationFailTheAttemptForGood() {
        List<GeneratedFile> two = List.of(new GeneratedFile("a", null, new byte[] {1}),
            new GeneratedFile("b", null, new byte[] {1, 2, 3}));
        assertThat(ExternalJobExecutor.refusedFiles(type(3), List.of())).isNull();
        assertThat(ExternalJobExecutor.refusedFiles(type(3), two)).satisfies(a -> {
            assertThat(a.errorCode()).isEqualTo("BAD_RESULT");
            assertThat(a.retryable()).isFalse();
        });
        assertThat(ExternalJobExecutor.refusedFiles(withOutput(1, 10), two).errorCode())
            .isEqualTo("OUTPUT_FILE_LIMIT");
        assertThat(ExternalJobExecutor.refusedFiles(withOutput(2, 2), two)).satisfies(a -> {
            assertThat(a.errorCode()).isEqualTo("OUTPUT_FILE_TOO_LARGE");
            assertThat(a.errorDetail()).contains("file b has 3 bytes");
        });
        assertThat(ExternalJobExecutor.refusedFiles(withOutput(2, 3), two)).isNull();
    }

    /** A type with files has a longer lease: copying and storing them happen besides the handler's timeout. */
    @Test
    void theLeaseCoversTheWorkOnFiles() {
        Instant now = Instant.parse("2026-10-18T00:00:00Z");
        Instant plain = ExternalJobExecutor.leaseUntil(type(1), now);
        assertThat(plain).isEqualTo(now.plus(ExternalJobType.DEFAULT_TIMEOUT).plusSeconds(30));
        assertThat(ExternalJobExecutor.leaseUntil(withOutput(1, 1), now))
            .isEqualTo(plain.plus(ExternalJobFiles.FILE_WORK_TIMEOUT));
        ExternalJobType<In, Out> both = ExternalJobType.define("it.both", In.class, Out.class, t -> t
            .inputFiles(1, 1, "p").outputFiles("p", 1, 1).onCompleted("A").onFailed("B"));
        assertThat(ExternalJobExecutor.leaseUntil(both, now))
            .isEqualTo(plain.plus(ExternalJobFiles.FILE_WORK_TIMEOUT.multipliedBy(2)));
    }

    /** A failure to store comes back with the files stored before it; success clears what was to be stored. */
    @Test
    void storingTurnsAnAttemptIntoAFailureWithWhatWasStored() {
        ExternalJobExecutor.Attempt produced = ExternalJobExecutor.Attempt.succeeded("{}")
            .producing(List.of(new GeneratedFile("a", null, new byte[] {1})));
        ExternalJobFiles.Output output = new ExternalJobFiles.Output(0, "a", new ExternalJobRecorder.StoredFile(
            UUID.randomUUID(), "p", "image/png", 1, "00"));
        ExternalJobExecutor.Attempt stored = produced.storing(new ExternalJobFiles.Outputs(List.of(output), null));
        assertThat(stored.success()).isTrue();
        assertThat(stored.produced()).isEmpty();
        assertThat(stored.stored()).containsExactly(output);
        ExternalJobExecutor.Attempt refused = produced.storing(new ExternalJobFiles.Outputs(List.of(output),
            ExternalJobException.permanent("OUTPUT_FILE_REJECTED", "file b was refused")));
        assertThat(refused.success()).isFalse();
        assertThat(refused.retryable()).isFalse();
        assertThat(refused.errorCode()).isEqualTo("OUTPUT_FILE_REJECTED");
        assertThat(refused.resultJson()).isNull();
        assertThat(refused.stored()).containsExactly(output);
    }

    private static ExternalJobType<In, Out> withOutput(int files, long bytes) {
        return ExternalJobType.define("it.out", In.class, Out.class, t -> t.outputFiles("p", files, bytes)
            .onCompleted("A").onFailed("B"));
    }
}

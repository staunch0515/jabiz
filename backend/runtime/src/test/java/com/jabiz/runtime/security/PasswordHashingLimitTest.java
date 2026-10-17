package com.jabiz.runtime.security;

import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.AuthBusyException;
import com.jabiz.runtime.process.sponsor.AuthenticationStep;
import com.jabiz.runtime.process.sponsor.SponsorSignInProcess;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The cap on password hashing (decision D47 item 2): at most {@code max-concurrent} computations at once, a wait of at
 * most {@code max-wait} and then 503 {@code AUTH_BUSY} with {@code Retry-After}; processes that hash wait before their
 * transaction starts (so they hold no connection while waiting), others never wait.
 */
class PasswordHashingLimitTest {

    record In(String value) {}

    /** A step that hashes, as the platform's password steps are marked. */
    static final class Hashing implements BlockingStep<NoMetadata, ProcessContext>, PasswordHashingLimit.HashingStep {
        @Override
        public void run(NoMetadata metadata, ProcessContext ctx) {}
    }

    private static ProcessDefinition<In, In, ProcessContext> process(String name, boolean hashes) {
        return ProcessDefinition.define(name, 1, In.class, In.class, ProcessContext.class, pb -> {
            pb.permissions("p")
                .contextFactory((start, input) -> new ProcessContext(start))
                .outputMapper(ctx -> new In("x"))
                .compute("Nothing", (NoMetadata metadata, ProcessContext ctx) -> { });
            if (hashes) {
                pb.step("Hash", Hashing.class, NoMetadata.INSTANCE);
            }
        });
    }

    private static final ProcessDefinition<In, In, ProcessContext> HASHES = process("HASHES", true);
    private static final ProcessDefinition<In, In, ProcessContext> PLAIN = process("PLAIN", false);

    @Test
    void thePlatformsPasswordStepsAreMarkedAndSigningInHashes() {
        assertThat(PasswordHashingLimit.HashingStep.class).isAssignableFrom(AuthenticationStep.class)
            .isAssignableFrom(HashPasswordStep.class).isAssignableFrom(CurrentPasswordStep.class);
        PasswordHashingLimit limit = PasswordHashingLimit.of(2, Duration.ofSeconds(2));
        assertThat(limit.hashesPasswords(SponsorSignInProcess.DEFINITION)).isTrue();
        assertThat(limit.hashesPasswords(HASHES)).isTrue();
        assertThat(limit.hashesPasswords(PLAIN)).isFalse();
    }

    @Test
    void noMoreComputationsAtOnceThanAllowed() throws Exception {
        PasswordHashingLimit limit = PasswordHashingLimit.of(3, Duration.ofSeconds(10));
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                results.add(threads.submit(() -> limit.compute(() -> {
                    most.accumulateAndGet(running.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return running.decrementAndGet();
                })));
            }
            for (Future<Integer> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        }
        assertThat(most.get()).isEqualTo(3);
        assertThat(running.get()).isZero();
    }

    @Test
    void aComputationThatWaitsTooLongIsBusyAndFreedPermitsServeAgain() throws Exception {
        PasswordHashingLimit limit = PasswordHashingLimit.of(1, Duration.ofMillis(200));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> holder = threads.submit(() -> limit.compute(() -> {
                holding.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "held";
            }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            AuthBusyException busy = catchThrowableOfType(AuthBusyException.class, () -> limit.compute(() -> "late"));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(150));
            assertThat(busy.retryAfterSeconds()).isEqualTo(1);
            assertThat(busy.violations()).singleElement().satisfies(v -> {
                assertThat(v.ruleCode()).isEqualTo("AUTH_BUSY");
                assertThat(v.params()).containsEntry("retryAfter", 1L);
            });
            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo("held");
        }
        assertThat(limit.compute(() -> "again")).isEqualTo("again");
    }

    @Test
    void aHasherUnderTheLimitWaitsForItsTurn() throws Exception {
        PasswordHashingLimit limit = PasswordHashingLimit.of(1, Duration.ofMillis(100));
        PasswordHasher hasher = new PasswordHasher(4, 10, limit);
        String hash = hasher.hash("correct horse battery");
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            threads.submit(() -> limit.compute(() -> {
                holding.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> hasher.matches("correct horse battery", hash))
                .isInstanceOf(AuthBusyException.class);
            assertThatThrownBy(() -> hasher.hash("another horse battery")).isInstanceOf(AuthBusyException.class);
            release.countDown();
        }
        assertThat(hasher.matches("correct horse battery", hash)).isTrue();
    }

    @Test
    void aProcessThatHashesStartsItsTransactionOnlyOnceItsTurnHasCome() {
        PasswordHashingLimit limit = PasswordHashingLimit.of(1, Duration.ofSeconds(10));
        Sinks.One<String> first = Sinks.one();
        AtomicBoolean secondStarted = new AtomicBoolean();
        List<String> results = java.util.Collections.synchronizedList(new ArrayList<>());

        Disposable one = limit.around(HASHES, first.asMono()).subscribe(results::add);
        // Subscribing returns at once: the wait blocks no thread, and the work (which would borrow the connection)
        // has not been subscribed to.
        Disposable two = limit.around(HASHES, Mono.fromCallable(() -> {
            secondStarted.set(true);
            return "second";
        })).subscribe(results::add);
        assertThat(secondStarted).isFalse();
        // A process that does not hash never waits.
        assertThat(limit.around(PLAIN, Mono.just("plain")).block(Duration.ofSeconds(1))).isEqualTo("plain");

        first.tryEmitValue("first");
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (results.size() < 2 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(results).containsExactly("first", "second");
        assertThat(secondStarted).isTrue();
        one.dispose();
        two.dispose();
        // Every permit came back: another run goes at once.
        assertThat(limit.around(HASHES, Mono.just("third")).block(Duration.ofSeconds(1))).isEqualTo("third");
    }

    @Test
    void aProcessThatWaitsTooLongIsBusyWithoutStarting() {
        PasswordHashingLimit limit = PasswordHashingLimit.of(1, Duration.ofMillis(150));
        Sinks.One<String> holder = Sinks.one();
        Disposable held = limit.around(HASHES, holder.asMono()).subscribe();
        AtomicBoolean started = new AtomicBoolean();
        assertThatThrownBy(() -> limit.around(HASHES, Mono.fromCallable(() -> {
            started.set(true);
            return "late";
        })).block(Duration.ofSeconds(10)))
            .isInstanceOfSatisfying(AuthBusyException.class, busy ->
                assertThat(busy.retryAfterSeconds()).isPositive());
        assertThat(started).isFalse();

        // A failed or cancelled run gives its permit back as well.
        held.dispose();
        assertThatThrownBy(() -> limit.around(HASHES, Mono.error(new IllegalStateException("boom")))
            .block(Duration.ofSeconds(1))).hasMessage("boom");
        assertThat(limit.around(HASHES, Mono.just("free")).block(Duration.ofSeconds(1))).isEqualTo("free");
    }

    @Test
    void withoutALimitNothingWaits() {
        assertThat(PasswordHashingLimit.NONE.compute(() -> "now")).isEqualTo("now");
        assertThat(PasswordHashingLimit.NONE.around(HASHES, Mono.just("now")).block()).isEqualTo("now");
        assertThat(PasswordHashingLimit.NONE.maxConcurrent()).isEqualTo(Integer.MAX_VALUE);
        assertThat(PasswordHashingLimit.NONE.check()).isEmpty();
    }

    @Test
    void settingsOutOfRangeAreStartupErrors() {
        assertThat(PasswordHashingLimit.of(4, Duration.ofSeconds(2)).check()).isEmpty();
        assertThat(PasswordHashingLimit.of(0, Duration.ZERO).check())
            .extracting(Object::toString)
            .anySatisfy(problem -> assertThat(problem).contains("max-concurrent"))
            .anySatisfy(problem -> assertThat(problem).contains("max-wait"));
        // It still works meanwhile, with one permit and the default wait.
        assertThat(PasswordHashingLimit.of(0, Duration.ZERO).compute(() -> "one")).isEqualTo("one");
    }
}

package com.jabiz.quizbuks;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ending a sampled span on an event loop may wake the span exporter's worker through a one-place
 * {@link ArrayBlockingQueue}, whose lock the worker takes too. When the event loop had to wait for that lock, BlockHound
 * failed the request with a bare 500 ({@code SetupScheduledIT} on CI, PR #123; any request could hit it).
 * {@link QbBlockHoundIntegration} tolerates the wait, which is a hand-off and not I/O. Here the wake-up queue is one
 * whose lock the test holds, so the event loop always waits.
 */
class TracingUnderBlockHoundTest {

    @Test
    void anEventLoopMayWaitToWakeTheSpanExporter() throws Exception {
        BatchSpanProcessor processor = BatchSpanProcessor.builder(new Discard())
            .setScheduleDelay(1, TimeUnit.HOURS).build();
        SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(processor).build();
        Object worker = read(processor, "worker");
        Object signal = read(worker, "signal");
        try {
            Tracer tracer = provider.get("blockhound-test");
            ReentrantLock held = new ReentrantLock();
            // Every span ends with a wake-up, through a queue that waits while the test holds its lock.
            ((AtomicInteger) read(worker, "spansNeeded")).set(0);
            write(worker, "signal", new ArrayBlockingQueue<Boolean>(1) {
                @Override
                public boolean offer(Boolean value) {
                    held.lock();
                    try {
                        return super.offer(value);
                    } finally {
                        held.unlock();
                    }
                }
            });

            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = Thread.ofPlatform().start(() -> {
                held.lock();
                try {
                    holding.countDown();
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    held.unlock();
                }
            });
            assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

            CountDownLatch ending = new CountDownLatch(1);
            Thread.ofPlatform().start(() -> {
                try {
                    // Long enough for the event loop to be waiting for the lock.
                    if (ending.await(30, TimeUnit.SECONDS)) {
                        Thread.sleep(200);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                release.countDown();
            });
            Mono<String> onEventLoop = Mono.fromCallable(() -> {
                ending.countDown();
                tracer.spanBuilder("span").startSpan().end();
                return "ended";
            }).subscribeOn(Schedulers.parallel());
            assertThat(onEventLoop.onErrorResume(e -> Mono.just(e.toString())).block(Duration.ofSeconds(30)))
                .isEqualTo("ended");
            holder.join(30_000);
        } finally {
            // The worker waits on its own queue: shutting down wakes it through that one.
            write(worker, "signal", signal);
            provider.shutdown().join(10, TimeUnit.SECONDS);
        }
    }

    /** The control: BlockHound is on for this thread pool, so the tolerance is what lets the span above end. */
    @Test
    void blockingOnAnEventLoopStillFails() {
        Mono<Integer> sleeping = Mono.fromCallable(() -> {
            Thread.sleep(1);
            return 1;
        }).subscribeOn(Schedulers.parallel());
        assertThat(sleeping.map(x -> "ran").onErrorResume(e -> Mono.just(e.getClass().getSimpleName()))
            .block(Duration.ofSeconds(30))).isEqualTo("BlockingOperationError");
    }

    private static Object read(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void write(Object owner, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static final class Discard implements SpanExporter {

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}

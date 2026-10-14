package com.jabiz.runtime.process;

import org.hibernate.validator.internal.util.ConcurrentReferenceHashMap;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hibernate Validator caches bean metadata in a {@link ConcurrentReferenceHashMap} whose segments are locks. Two event
 * loops validating the same process input on a cache miss met there, and BlockHound failed one request with a bare
 * 500, at random (QuizBuks {@code PlayConcurrencyIT}). {@code JabizBlockHoundIntegration} tolerates the wait, which is
 * a short critical section and not I/O. Here the test holds every segment's lock, so the event loop always waits. Reads
 * the map's package-private {@code segments} field by reflection, so a Hibernate Validator upgrade may need this test
 * adjusted.
 */
class ValidatorCacheUnderBlockHoundTest {

    @Test
    void anEventLoopMayWaitForTheValidatorsCache() throws Exception {
        ConcurrentReferenceHashMap<String, String> cache = new ConcurrentReferenceHashMap<>(16,
            ConcurrentReferenceHashMap.ReferenceType.SOFT, ConcurrentReferenceHashMap.ReferenceType.SOFT);
        Field field = ConcurrentReferenceHashMap.class.getDeclaredField("segments");
        field.setAccessible(true);
        Object[] segments = (Object[]) field.get(cache);

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch waiting = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> {
            for (Object segment : segments) {
                ((ReentrantLock) segment).lock();
            }
            try {
                holding.countDown();
                // Long enough for the event loop to be waiting for the lock.
                if (waiting.await(30, TimeUnit.SECONDS)) {
                    Thread.sleep(200);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                for (Object segment : segments) {
                    ((ReentrantLock) segment).unlock();
                }
            }
        });
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        Mono<String> onEventLoop = Mono.fromCallable(() -> {
            waiting.countDown();
            cache.putIfAbsent("input", "metadata");
            return "cached";
        }).subscribeOn(Schedulers.parallel());
        assertThat(onEventLoop.onErrorResume(e -> Mono.just(e.toString())).block(Duration.ofSeconds(30)))
            .isEqualTo("cached");
        holder.join(30_000);
    }
}

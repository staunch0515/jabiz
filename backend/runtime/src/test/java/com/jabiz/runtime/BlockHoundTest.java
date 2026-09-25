package com.jabiz.runtime;

import org.junit.jupiter.api.Test;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BlockHound is active in every test run; blocking work belongs on boundedElastic, which uses virtual threads. */
class BlockHoundTest {

    @Test
    void blockingOnANonBlockingThreadFails() {
        assertThatThrownBy(() -> Mono.delay(Duration.ofMillis(1))
                .doOnNext(tick -> sleep())
                .block())
            .hasCauseInstanceOf(BlockingOperationError.class);
    }

    @Test
    void boundedElasticAllowsBlockingAndRunsOnVirtualThreads() {
        Boolean virtual = Mono.fromCallable(() -> {
                sleep();
                return Thread.currentThread().isVirtual();
            })
            .subscribeOn(Schedulers.boundedElastic())
            .block();

        assertThat(Schedulers.DEFAULT_BOUNDED_ELASTIC_ON_VIRTUAL_THREADS).isTrue();
        assertThat(virtual).isTrue();
    }

    private static void sleep() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

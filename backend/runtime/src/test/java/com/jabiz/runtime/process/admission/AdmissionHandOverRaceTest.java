package com.jabiz.runtime.process.admission;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A permit handed over just as its caller cancels (decision D45; ROADMAP 17zz): the sink drops the value
 * ({@code onNextDropped}, not a discard) when the cancellation lands between its own checks, so neither the discard
 * hook nor the cancellation gives the permit back unless the gate does. Afterwards every permit must be back.
 */
class AdmissionHandOverRaceTest {

    @Test
    void aPermitHandedOverWhileItsCallerCancelsIsNotLost() throws Exception {
        Scheduler handOverThread = Schedulers.newSingle("hand-over");
        try {
            AdmissionGate gate = new AdmissionGate(1, 10, 10,
                family -> new AdmissionGate.Limits(1, 10, Duration.ofSeconds(1)), System::nanoTime,
                () -> handOverThread);
            for (int round = 0; round < 20_000; round++) {
                AdmissionGate.Permit holder = gate.acquire("race", "k", Duration.ofSeconds(5)).block();
                AtomicReference<AdmissionGate.Permit> received = new AtomicReference<>();
                AtomicReference<Subscription> subscription = new AtomicReference<>();
                gate.acquire("race", "k", Duration.ofSeconds(5)).subscribe(new BaseSubscriber<>() {
                    @Override
                    protected void hookOnSubscribe(Subscription s) {
                        subscription.set(s);
                        s.request(1);
                    }

                    @Override
                    protected void hookOnNext(AdmissionGate.Permit permit) {
                        received.set(permit);
                    }
                });
                CyclicBarrier together = new CyclicBarrier(2);
                // The hand-over is scheduled by the release; the cancellation comes at the same moment.
                Thread canceller = Thread.ofPlatform().start(() -> {
                    try {
                        together.await();
                    } catch (Exception e) {
                        return;
                    }
                    subscription.get().cancel();
                });
                together.await();
                holder.release();
                canceller.join();
                // Give back whatever the caller does receive (its run would), however late it arrives.
                boolean givenBack = false;
                for (int i = 0; i < 2000 && gate.active() > 0; i++) {
                    AdmissionGate.Permit got = received.get();
                    if (got != null && !givenBack) {
                        got.release();
                        givenBack = true;
                    }
                    Thread.sleep(0, 100_000);
                }
                assertThat(gate.active()).as("round " + round + ": every permit back").isZero();
            }
        } finally {
            handOverThread.dispose();
        }
    }
}

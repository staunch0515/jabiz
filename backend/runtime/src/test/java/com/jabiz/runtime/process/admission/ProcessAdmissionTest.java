package com.jabiz.runtime.process.admission;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.ProcessBusyException;
import com.jabiz.runtime.observability.PlatformObservations;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The admission of top-level runs (decision D45) around their work: only declared processes with a key queue, the
 * switch turns it off, the permit is held exactly while the work runs, and a lock of the family under another name is
 * warned about once.
 */
class ProcessAdmissionTest {

    record In(String key) {}

    private static ProcessDefinition<In, In, ProcessContext> process(String name, boolean admitted) {
        return ProcessDefinition.define(name, 1, In.class, In.class, ProcessContext.class, pb -> {
            pb.permissions("p")
                .contextFactory((start, input) -> new ProcessContext(start))
                .outputMapper(ctx -> new In("x"))
                .compute("Nothing", (NoMetadata metadata, ProcessContext ctx) -> { });
            if (admitted) {
                pb.admission("test.hot", In::key);
            }
        });
    }

    private static final ProcessDefinition<In, In, ProcessContext> HOT = process("HOT", true);
    private static final ProcessDefinition<In, In, ProcessContext> COLD = process("COLD", false);

    private static ProcessAdmission admission(AdmissionSettings settings) {
        return new ProcessAdmission(settings, 10, PlatformObservations.NOOP, null);
    }

    private static AdmissionSettings oneAtATime(boolean enabled) {
        return new AdmissionSettings(enabled, null, null, null, null,
            Map.of("test.hot", new AdmissionSettings.Family(1, 0, Duration.ofSeconds(1), Duration.ofSeconds(1))));
    }

    @Test
    void thePermitIsHeldWhileTheWorkRunsAndReturnedHoweverItEnds() {
        ProcessAdmission admission = admission(oneAtATime(true));
        Sinks.One<String> running = Sinks.one();
        AtomicReference<ProcessAdmission.Admitted> seen = new AtomicReference<>();
        var holder = admission.admit(HOT, new In("k"), null, Mono.deferContextual(view -> {
            seen.set(view.get(ProcessAdmission.Admitted.class));
            return running.asMono();
        })).subscribe();
        assertThat(admission.active()).isEqualTo(1);
        assertThat(seen.get()).isEqualTo(new ProcessAdmission.Admitted("HOT", "test.hot", "test.hot:k"));
        // max-queue 0: the next run of the key is refused at once; another key, a process without the declaration
        // and a null key are not.
        assertThatThrownBy(() -> admission.admit(HOT, new In("k"), null, Mono.just("x")).block())
            .isInstanceOf(ProcessBusyException.class);
        assertThat(admission.admit(HOT, new In("other"), null, Mono.just("x")).block()).isEqualTo("x");
        assertThat(admission.admit(COLD, new In("k"), null, Mono.just("x")).block()).isEqualTo("x");
        assertThat(admission.admit(HOT, new In(null), null, Mono.just("x")).block()).isEqualTo("x");

        running.tryEmitValue("done");
        assertThat(admission.active()).isZero();
        assertThat(holder.isDisposed()).isTrue();

        // An error, and a cancellation, give it back too.
        assertThatThrownBy(() -> admission.admit(HOT, new In("k"), null, Mono.error(new IllegalStateException("no")))
            .block()).isInstanceOf(IllegalStateException.class);
        assertThat(admission.active()).isZero();
        admission.admit(HOT, new In("k"), null, Mono.never()).subscribe().dispose();
        assertThat(admission.active()).isZero();
        assertThat(admission.keys()).isZero();
    }

    @Test
    void theSwitchTurnsItOff() {
        ProcessAdmission off = admission(oneAtATime(false));
        Sinks.One<String> running = Sinks.one();
        off.admit(HOT, new In("k"), null, running.asMono()).subscribe();
        assertThat(off.admit(HOT, new In("k"), null, Mono.just("x")).block()).isEqualTo("x");
        assertThat(off.active()).isZero();
    }

    @Test
    void aShorterLockWaitShortensTheWait() {
        ProcessAdmission admission = admission(new AdmissionSettings(true, null, null, null, null,
            Map.of("test.hot", new AdmissionSettings.Family(1, 5, Duration.ofSeconds(20), Duration.ofSeconds(1)))));
        admission.admit(HOT, new In("k"), null, Mono.never()).subscribe();
        long started = System.nanoTime();
        assertThatThrownBy(() -> admission.admit(HOT, new In("k"), Duration.ofMillis(100), Mono.just("x"))
            .block(Duration.ofSeconds(5))).isInstanceOfSatisfying(ProcessBusyException.class,
            busy -> assertThat(busy.reason()).isEqualTo(ProcessBusyException.Reason.WAIT_TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aLockOfTheFamilyUnderAnotherNameIsWarnedAboutOncePerProcess() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProcessAdmission.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String process = "WARNED_" + System.nanoTime();
            Context admitted = Context.of(ProcessAdmission.Admitted.class,
                new ProcessAdmission.Admitted(process, "test.hot", "test.hot:k"));
            ProcessAdmission.checkLock(admitted, "test.hot:k");
            ProcessAdmission.checkLock(admitted, "other.family:k");
            ProcessAdmission.checkLock(Context.empty(), "test.hot:other");
            assertThat(appender.list).isEmpty();
            ProcessAdmission.checkLock(admitted, "test.hot:other");
            ProcessAdmission.checkLock(admitted, "test.hot:another");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).contains(process, "test.hot").doesNotContain("test.hot:k", "test.hot:other");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }
}

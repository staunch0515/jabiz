package com.jabiz.runtime.process.steps;

import com.jabiz.context.RequestContext;
import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import com.jabiz.external.ExternalJobType;
import com.jabiz.process.IdAssigner;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessStart;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.external.ExternalJobRecorder;
import com.jabiz.runtime.external.ExternalJobRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The step {@code CancelExternalJob} (decision D52 item 2) without a database: it hands the recorder a check that only
 * admits jobs of its declared type, whether a finished job is an error, and the process's actor, time and operation;
 * the recorder's own behaviour is covered by {@code ExternalJobCancelIT}.
 */
class CancelExternalJobTest {

    record In(String id) {}

    record Out(String text) {}

    static final class Handler implements ExternalJobHandler<In, Out> {
        @Override
        public ExternalJobResult<Out> run(In input, ExternalJobContext ctx) {
            return ExternalJobResult.of(new Out(input.id()));
        }
    }

    static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");

    record Call(String jobId, Predicate<ExternalJobRecorder.JobOwner> mayCancel, boolean ifOpen, String cancelledBy,
        Instant time, long processSeqId) {}

    /** Records what the step asks for, and answers as told. */
    static final class FakeRecorder extends ExternalJobRecorder {
        final List<Call> calls = new ArrayList<>();
        final Mono<Boolean> answer;

        FakeRecorder(Mono<Boolean> answer) {
            super(null, "default", null, null, null);
            this.answer = answer;
        }

        @Override
        public Mono<Boolean> cancel(String jobId, Predicate<JobOwner> mayCancel, boolean ifOpen, String cancelledBy,
            Instant time, long processSeqId) {
            calls.add(new Call(jobId, mayCancel, ifOpen, cancelledBy, time, processSeqId));
            return answer;
        }
    }

    private static final ExternalJobRegistry JOBS = new ExternalJobRegistry(List.of(ExternalJobType.define(
        "it.cancel", In.class, Out.class, t -> t.handler(Handler.class).onCompleted("A").onFailed("B"))));

    private static ProcessContext context(String actor) {
        ProcessContext ctx = new ProcessContext(new ProcessStart(42L, NOW, new RequestContext(actor, null,
            Locale.ENGLISH, "req-1", Set.of(), Set.of("it.process")), IdAssigner.NONE));
        ctx.put("jobId", "job-7");
        return ctx;
    }

    @Test
    void itCancelsOnlyJobsOfItsTypeAsTheProcesssActor() {
        FakeRecorder recorder = new FakeRecorder(Mono.just(true));
        CancelExternalJob<ProcessContext> step = new CancelExternalJob<>(JOBS, recorder);
        ProcessContext ctx = context("alice");
        CancelExternalJob.Spec<ProcessContext> spec = CancelExternalJob.<ProcessContext>of("it.cancel",
            c -> c.get("jobId", String.class)).outputKey("cancelled");
        step.execute(spec.metadata(), ctx).block();
        assertThat(recorder.calls).singleElement().satisfies(call -> {
            assertThat(call.jobId()).isEqualTo("job-7");
            assertThat(call.ifOpen()).isFalse();
            assertThat(call.cancelledBy()).isEqualTo("alice");
            assertThat(call.time()).isEqualTo(NOW);
            assertThat(call.processSeqId()).isEqualTo(42L);
            // By default the actor's own jobs of the type only: another type, or another requester's job, is refused
            // like a missing one.
            assertThat(call.mayCancel().test(new ExternalJobRecorder.JobOwner("job-7", "it.cancel", "alice")))
                .isTrue();
            assertThat(call.mayCancel().test(new ExternalJobRecorder.JobOwner("job-7", "it.cancel", "bob")))
                .isFalse();
            assertThat(call.mayCancel().test(new ExternalJobRecorder.JobOwner("job-7", "it.other", "alice")))
                .isFalse();
        });
        assertThat(ctx.get("cancelled", Boolean.class)).isTrue();
    }

    @Test
    void ifOpenIsPassedOnAndNoOutputKeyKeepsTheContextUnchanged() {
        FakeRecorder recorder = new FakeRecorder(Mono.just(false));
        CancelExternalJob<ProcessContext> step = new CancelExternalJob<>(JOBS, recorder);
        ProcessContext ctx = context("alice");
        step.execute(CancelExternalJob.<ProcessContext>of("it.cancel", c -> c.get("jobId", String.class)).ifOpen()
            .metadata(), ctx).block();
        assertThat(recorder.calls).singleElement().satisfies(call -> assertThat(call.ifOpen()).isTrue());
        assertThat(ctx.contains("cancelled")).isFalse();
    }

    @Test
    void theRecordersRefusalIsTheStepsAnswer() {
        FakeRecorder recorder = new FakeRecorder(Mono.error(new EntityNotFoundException("External job not found")));
        CancelExternalJob<ProcessContext> step = new CancelExternalJob<>(JOBS, recorder);
        assertThatThrownBy(() -> step.execute(CancelExternalJob.<ProcessContext>of("it.cancel",
            c -> c.get("jobId", String.class)).metadata(), context("alice")).block())
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void anUndeclaredTypeIsAStartupProblemAndAnErrorWhenRun() {
        FakeRecorder recorder = new FakeRecorder(Mono.just(true));
        CancelExternalJob<ProcessContext> step = new CancelExternalJob<>(JOBS, recorder);
        CancelExternalJob.Metadata<ProcessContext> ghost = CancelExternalJob.<ProcessContext>of("it.ghost",
            c -> "job-7").metadata();
        assertThat(step.problems(ghost)).containsExactly("cancels undeclared external job type it.ghost");
        assertThat(step.problems(CancelExternalJob.<ProcessContext>of("it.cancel", c -> "job-7").metadata()))
            .isEmpty();
        assertThatThrownBy(() -> step.execute(ghost, context("alice")).block())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("it.ghost");
        assertThat(recorder.calls).isEmpty();
    }

    @Test
    void theSpecIsImmutable() {
        CancelExternalJob.Spec<ProcessContext> plain = CancelExternalJob.of("it.cancel", c -> "job-7");
        CancelExternalJob.Spec<ProcessContext> open = plain.ifOpen().outputKey("k");
        assertThat(plain.metadata().ifOpen()).isFalse();
        assertThat(plain.metadata().outputKey()).isNull();
        assertThat(open.metadata().ifOpen()).isTrue();
        assertThat(open.metadata().outputKey()).isEqualTo("k");
        assertThat(open.toStepSpec().handlerClass()).isEqualTo(CancelExternalJob.class);
        assertThatThrownBy(() -> CancelExternalJob.of(null, c -> "x")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void anyRequesterAndANamedOwnerAreExplicit() {
        FakeRecorder recorder = new FakeRecorder(Mono.just(true));
        CancelExternalJob<ProcessContext> step = new CancelExternalJob<>(JOBS, recorder);
        ProcessContext ctx = context("operator");
        ctx.put("owner", "bob");
        step.execute(CancelExternalJob.<ProcessContext>of("it.cancel", c -> "job-7").anyRequester().metadata(), ctx)
            .block();
        step.execute(CancelExternalJob.<ProcessContext>of("it.cancel", c -> "job-7")
            .requestedBy(c -> c.get("owner", String.class)).metadata(), ctx).block();
        step.execute(CancelExternalJob.<ProcessContext>of("it.cancel", c -> "job-7")
            .requestedBy(c -> null).metadata(), ctx).block();
        assertThat(recorder.calls).hasSize(3);
        Predicate<ExternalJobRecorder.JobOwner> any = recorder.calls.get(0).mayCancel();
        Predicate<ExternalJobRecorder.JobOwner> bobs = recorder.calls.get(1).mayCancel();
        Predicate<ExternalJobRecorder.JobOwner> nobodys = recorder.calls.get(2).mayCancel();
        var ofBob = new ExternalJobRecorder.JobOwner("job-7", "it.cancel", "bob");
        var ofCarol = new ExternalJobRecorder.JobOwner("job-7", "it.cancel", "carol");
        var otherType = new ExternalJobRecorder.JobOwner("job-7", "it.other", "bob");
        assertThat(any.test(ofBob)).isTrue();
        assertThat(any.test(ofCarol)).isTrue();
        assertThat(any.test(otherType)).isFalse();
        assertThat(bobs.test(ofBob)).isTrue();
        assertThat(bobs.test(ofCarol)).isFalse();
        assertThat(bobs.test(otherType)).isFalse();
        // An owner that is not known matches nothing: 404, never everyone's.
        assertThat(nobodys.test(ofBob)).isFalse();
        // The canceller is still the actor.
        assertThat(recorder.calls).allSatisfy(call -> assertThat(call.cancelledBy()).isEqualTo("operator"));
        assertThatThrownBy(() -> new CancelExternalJob.Metadata<ProcessContext>("it.cancel", c -> "x", false, null,
            c -> "bob", true)).isInstanceOf(IllegalArgumentException.class);
        // The last of requestedBy and anyRequester wins.
        assertThat(CancelExternalJob.<ProcessContext>of("it.cancel", c -> "x").requestedBy(c -> "bob").anyRequester()
            .metadata().requestedBy()).isNull();
    }
}

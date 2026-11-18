package com.jabiz.runtime.firstlaunch;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.entity.UuidV7Generator;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The steps of the first launch's processes ({@link FirstLaunchProcesses}). */
final class FirstLaunchSteps {

    static final String INPUT = "first_launch_input";

    private static final String RUN = FirstLaunchRuns.RUN;
    private static final String EVENT = FirstLaunchRuns.EVENT;

    /** Which event may follow the run's current state; nothing follows an ended run. */
    private static final Map<FirstLaunchRun.State, Set<String>> NEXT = Map.of(
        FirstLaunchRun.State.RUNNING, Set.of("CONTENT_DONE", "FAILED"),
        FirstLaunchRun.State.CONTENT_DONE, Set.of("DONE", "EXPIRED", "REVISED"));

    private FirstLaunchSteps() {}

    private static StorageEngine engine(StorageAdapterRegistry storages, String poolRef) {
        return storages.getEngine(poolRef);
    }

    /**
     * The run's state inside the transaction, with the run's row locked: the events of one run are written one
     * after another, and a step runs only while no event has ended the content phase.
     */
    private static Mono<FirstLaunchRun.State> lockedState(StorageEngine engine, UUID runId) {
        return engine.select("SELECT run_id FROM " + RUN + " WHERE run_slot = 1 AND run_id = :run FOR UPDATE",
                Map.of("run", BoundValue.of(runId)))
            .hasElements()
            .flatMap(exists -> {
                if (!exists) {
                    return Mono.error(new IllegalStateException("There is no first-launch run " + runId));
                }
                return engine.select("SELECT kind FROM " + EVENT + " WHERE run_id = :run AND kind <> 'REVISED'"
                        + " ORDER BY process_seq_id DESC LIMIT 1", Map.of("run", BoundValue.of(runId)))
                    .next()
                    .map(row -> FirstLaunchRun.State.valueOf(Rows.string(row.get("kind"))))
                    .defaultIfEmpty(FirstLaunchRun.State.RUNNING);
            });
    }

    /** Begins the run when the database never had a user and no run exists. */
    @Component
    static class Begin implements StepHandler<Begin.Metadata, ProcessContext> {

        record Metadata() {}

        private final StorageAdapterRegistry storages;
        private final String poolRef;
        private final UuidV7Generator ids;

        Begin(StorageAdapterRegistry storages, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
            Clock clock) {
            this.storages = storages;
            this.poolRef = poolRef;
            this.ids = new UuidV7Generator(clock);
        }

        @Override
        public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
            FirstLaunchProcesses.StartInput input = ctx.get(INPUT, FirstLaunchProcesses.StartInput.class);
            StorageEngine engine = engine(storages, poolRef);
            return engine.select("SELECT count(DISTINCT user_id) AS users FROM sec_user_version", Map.of())
                .next().map(row -> Rows.longValue(row.get("users"))).defaultIfEmpty(0L)
                .flatMap(users -> {
                    if (users > 0) {
                        return Mono.just(new FirstLaunchProcesses.StartOutput(null, users));
                    }
                    UUID runId = UUID.fromString(String.valueOf(ids.next(null)));
                    // A second instance starting at the same moment conflicts on run_slot and does not begin.
                    return engine.select("INSERT INTO " + RUN + " (run_slot, run_id, config_hash, started_time,"
                                + " process_seq_id) VALUES (1, :run, :hash, :now, :seq)"
                                + " ON CONFLICT (run_slot) DO NOTHING RETURNING run_id",
                            Map.of("run", BoundValue.of(runId), "hash", BoundValue.of(input.configHash()),
                                "now", BoundValue.of(ctx.opTime()), "seq", BoundValue.of(ctx.processSeqId())))
                        .hasElements()
                        .map(inserted -> new FirstLaunchProcesses.StartOutput(inserted ? runId.toString() : null,
                            0L));
                })
                .doOnNext(output -> ctx.put(FirstLaunchProcesses.STARTED, output))
                .then();
        }
    }

    /** Records an event, when the run's state allows it. */
    @Component
    static class Record implements StepHandler<Record.Metadata, ProcessContext> {

        record Metadata() {}

        private final StorageAdapterRegistry storages;
        private final String poolRef;
        private final UuidV7Generator ids;

        Record(StorageAdapterRegistry storages, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
            Clock clock) {
            this.storages = storages;
            this.poolRef = poolRef;
            this.ids = new UuidV7Generator(clock);
        }

        @Override
        public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
            FirstLaunchProcesses.RecordInput input = ctx.get(INPUT, FirstLaunchProcesses.RecordInput.class);
            UUID runId = UUID.fromString(input.runId());
            StorageEngine engine = engine(storages, poolRef);
            return lockedState(engine, runId).flatMap(state -> {
                if (!NEXT.getOrDefault(state, Set.of()).contains(input.kind())) {
                    return Mono.error(new IllegalStateException("The first-launch run is " + state + ": "
                        + input.kind() + " cannot follow"));
                }
                UUID eventId = UUID.fromString(String.valueOf(ids.next(null)));
                Map<String, BoundValue> params = new LinkedHashMap<>();
                params.put("id", BoundValue.of(eventId));
                params.put("run", BoundValue.of(runId));
                params.put("kind", BoundValue.of(input.kind()));
                params.put("step", text(input.stepKey()));
                params.put("code", text(input.reasonCode()));
                params.put("reason", text(input.reason()));
                params.put("hash", BoundValue.of(input.configHash()));
                params.put("detail", text(input.detail()));
                params.put("now", BoundValue.of(ctx.opTime()));
                params.put("seq", BoundValue.of(ctx.processSeqId()));
                return engine.select("INSERT INTO " + EVENT + " (event_id, run_id, kind, step_key, reason_code,"
                        + " reason, config_hash, detail, event_time, process_seq_id) VALUES (:id, :run, :kind, :step,"
                        + " :code, :reason, :hash, CAST(:detail AS jsonb), :now, :seq) RETURNING event_id", params)
                    .then(Mono.fromRunnable(() -> ctx.put(FirstLaunchProcesses.RECORDED,
                        new FirstLaunchProcesses.RecordOutput(eventId.toString()))));
            }).then();
        }

        private static BoundValue text(String value) {
            return value == null ? BoundValue.nullOf(String.class) : BoundValue.of(value);
        }
    }

    /** Runs the step's process as a sub-process, while the run is in its content phase. */
    @Component
    static class RunStep implements StepHandler<RunStep.Metadata, ProcessContext> {

        record Metadata() {}

        private final StorageAdapterRegistry storages;
        private final String poolRef;
        private final ProcessRegistry registry;
        private final ProcessExecutor executor;
        private final FirstLaunchPowers powers;

        RunStep(StorageAdapterRegistry storages, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
            ProcessRegistry registry, ProcessExecutor executor, FirstLaunchPowers powers) {
            this.storages = storages;
            this.poolRef = poolRef;
            this.registry = registry;
            this.executor = executor;
            this.powers = powers;
        }

        @Override
        public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
            FirstLaunchProcesses.StepInput input = ctx.get(INPUT, FirstLaunchProcesses.StepInput.class);
            return lockedState(engine(storages, poolRef), UUID.fromString(input.runId())).flatMap(state -> {
                if (state != FirstLaunchRun.State.RUNNING) {
                    return Mono.error(new IllegalStateException("The first launch's content phase is over (" + state
                        + "): step " + input.stepKey() + " does not run"));
                }
                ProcessDefinition<?, ?, ?> target = registry.findLatest(input.process()).orElse(null);
                if (target == null) {
                    return Mono.error(new IllegalStateException("Step " + input.stepKey() + " names the unknown"
                        + " process " + input.process()));
                }
                if (com.jabiz.context.RequestContext.SYSTEM_ACTOR.equals(ctx.request().actorId())) {
                    return call(target, input);
                }
                // Acting as an account (decision D82): only a process the application named, only as a user this
                // run created who has no credential of their own yet.
                if (!powers.actingProcesses().contains(target.name())) {
                    return Mono.error(new com.jabiz.runtime.PermissionDeniedException(FirstLaunchProcesses.PERMISSION,
                        "First launch: " + target.name() + " is not among the processes it may run as an account"));
                }
                return powers.contentPhase(ctx.request(), false)
                    .flatMap(run -> powers.requireActable(run, ctx.request().actorId()))
                    .then(call(target, input));
            }).doOnNext(output -> ctx.put(FirstLaunchProcesses.OUTPUT, output)).then();
        }

        private <I> Mono<Object> call(ProcessDefinition<I, ?, ?> target, FirstLaunchProcesses.StepInput step) {
            if (!target.inputType().isInstance(step.input())) {
                return Mono.error(new IllegalStateException("Step " + step.stepKey() + ": process " + target.name()
                    + " takes " + target.inputType().getName() + " but was given "
                    + (step.input() == null ? "null" : step.input().getClass().getName())));
            }
            return executor.executeChild(target, target.inputType().cast(step.input())).map(output -> (Object) output);
        }
    }
}

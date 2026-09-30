package com.jabiz.runtime.process;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.BlockingStep;
import com.jabiz.process.ComputeStep;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.process.StepDefinition;
import com.jabiz.process.StepImplementation;
import com.jabiz.process.StepPhase;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.IdempotencyConflictException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.operation.Operation;
import com.jabiz.runtime.operation.OperationRecorder;
import com.jabiz.runtime.operation.OperationRequest;
import com.jabiz.runtime.operation.Operations;
import com.jabiz.runtime.security.SensitiveDataMasker;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.UniqueKeyViolationException;
import io.micrometer.common.KeyValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Runs processes (docs/design/06-process.md). One top-level execution is one operation and one transaction:
 * <ol>
 *   <li>the {@code op_process} row is written first, in the transaction (with the idempotency key, if any);</li>
 *   <li>the in-transaction steps run one after another; sub-processes ({@code CallProcess}) run in the same
 *       transaction as operations of their own, with the parent's operation time;</li>
 *   <li>violations the steps collected fail the process as a whole (422), before anything is committed;</li>
 *   <li>the registered changes are committed and the output is computed; with an idempotency key it is stored in
 *       {@code op_process_result} for replay;</li>
 *   <li>after the commit, the after-commit steps of the process and its sub-processes start on their own, each
 *       retried by its policy with every attempt recorded; the result does not wait for them or depend on them.</li>
 * </ol>
 * Any failure before the commit rolls everything back, the operation records included. Domain exceptions propagate
 * unchanged. The caller's {@link RequestContext} must be in the Reactor context; permissions are checked by the
 * entry point (the process API), not here, so that jobs and tests call the same executor (decision D11).
 *
 * <p>Steps run as their kind requires: platform {@link StepHandler}s return their own publisher,
 * {@link ComputeStep}s run synchronously on the current thread, {@link BlockingStep}s on a virtual thread of
 * {@code boundedElastic}, after which execution returns to a non-blocking thread.
 */
@Component
public class ProcessExecutor {

    private static final Logger log = LoggerFactory.getLogger(ProcessExecutor.class);

    /** Name of the unique constraint on {@code op_process (actor_id, idempotency_key)}. */
    static final String IDEMPOTENCY_CONSTRAINT = "op_process_actor_id_idempotency_key_key";

    private static final int MAX_ERROR_LENGTH = 2000;

    private final ApplicationContext beans;
    private final ProcessSequence sequence;
    private final OperationRecorder operations;
    private final ChangeSetCommitter committer;
    private final StorageAdapterRegistry storages;
    private final JsonMapper json;
    private final SensitiveDataMasker masker;
    private final String poolRef;
    private final PlatformObservations observations;

    public ProcessExecutor(ApplicationContext beans, ProcessSequence sequence, OperationRecorder operations,
        ChangeSetCommitter committer, StorageAdapterRegistry storages, JsonMapper json, SensitiveDataMasker masker,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef, PlatformObservations observations) {
        this.observations = Objects.requireNonNull(observations, "observations must not be null");
        this.masker = masker;
        this.beans = beans;
        this.sequence = sequence;
        this.operations = operations;
        this.committer = committer;
        this.storages = storages;
        this.json = json;
        this.poolRef = Objects.requireNonNull(poolRef, "poolRef must not be null");
    }

    /** Executes the process and returns its output. */
    public <I, O, C extends ProcessContext> Mono<O> execute(ProcessDefinition<I, O, C> definition, I input) {
        return run(definition, input, ExecutionOptions.NONE).map(ProcessResult::output);
    }

    /**
     * Executes the process as a top-level operation. With an idempotency key, a request the actor already made with
     * the same key returns the stored result of that execution instead (decision D4); concurrent duplicates wait
     * for the first one and then replay it, so the process runs once.
     */
    public <I, O, C extends ProcessContext> Mono<ProcessResult<O>> run(
        ProcessDefinition<I, O, C> definition, I input, ExecutionOptions options
    ) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(options, "options must not be null");
        return observations.mono(PlatformObservations.PROCESS, "process " + definition.name(), tags(definition),
            RequestContexts.current().flatMap(request -> {
                String key = options.idempotencyKey();
                if (key == null) {
                    return executeRoot(definition, input, request, null, options);
                }
                if (!ExecutionOptions.KEY_PATTERN.matcher(key).matches()) {
                    return Mono.error(new ValidationException(List.of(new Violation("Idempotency-Key",
                        PlatformErrorCodes.INVALID_IDEMPOTENCY_KEY, "Idempotency keys are 1 to 128 characters of "
                            + "letters, digits, '.', '_', ':' and '-'"))));
                }
                Mono<ProcessResult<O>> replay = replay(definition, request, key);
                return replay.switchIfEmpty(Mono.defer(() -> executeRoot(definition, input, request, key, options)
                    .onErrorResume(ProcessExecutor::isIdempotencyRace,
                        race -> replay.switchIfEmpty(Mono.error(race)))));
            }));
    }

    private static KeyValues tags(ProcessDefinition<?, ?, ?> definition) {
        return KeyValues.of("process", definition.name(), "version", String.valueOf(definition.version()));
    }

    private static boolean isIdempotencyRace(Throwable error) {
        return error instanceof UniqueKeyViolationException unique
            && IDEMPOTENCY_CONSTRAINT.equals(unique.constraintName());
    }

    private <O> Mono<ProcessResult<O>> replay(ProcessDefinition<?, O, ?> definition, RequestContext request,
        String key) {
        return operations.findByIdempotencyKey(engine(), request.actorId(), key).map(found -> {
            if (!found.processName().equals(definition.name())) {
                throw new IdempotencyConflictException("Idempotency key " + key + " was used for process "
                    + found.processName() + ", not " + definition.name());
            }
            O output = json.readValue(found.output(), definition.outputType());
            return new ProcessResult<>(found.processSeqId(), output, true);
        });
    }

    private <I, O, C extends ProcessContext> Mono<ProcessResult<O>> executeRoot(
        ProcessDefinition<I, O, C> definition, I input, RequestContext request, String idempotencyKey,
        ExecutionOptions options
    ) {
        StorageEngine engine = engine();
        List<PendingStep<?>> afterCommit = Collections.synchronizedList(new ArrayList<>());
        Mono<ProcessResult<O>> transaction = sequence.next().flatMap(seq -> {
            OperationRequest operation = new OperationRequest(definition.name(), definition.version(), seq, null,
                null, null, idempotencyKey, null, masker.summary(input));
            return operations.begin(engine, operation, request)
                .flatMap(started -> options.beforeSteps().apply(started)
                    .then(runProcess(definition, input, started, request, afterCommit, idempotencyKey != null)));
        });
        if (options.dryRun()) {
            // Rolled back by failing the transaction on purpose once everything has run; after-commit steps never run.
            return engine.inTransaction(transaction.flatMap(result -> Mono.<ProcessResult<O>>error(
                    new DryRunComplete(result))))
                .onErrorResume(DryRunComplete.class, done -> Mono.just(cast(done.result)));
        }
        return engine.inTransaction(transaction)
            .flatMap(result -> Mono.deferContextual(view -> {
                startAfterCommit(engine, List.copyOf(afterCommit), view);
                return Mono.just(result);
            }));
    }

    /**
     * Executes a sub-process from a step of a running process: in the same transaction, as an operation of its own
     * whose parent is the running one, at the same operation time. Its after-commit steps run once the top-level
     * transaction has committed.
     */
    public <I, O, C extends ProcessContext> Mono<O> executeChild(ProcessDefinition<I, O, C> definition, I input) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(input, "input must not be null");
        return observations.mono(PlatformObservations.PROCESS, "sub-process " + definition.name(), tags(definition),
            Mono.deferContextual(view -> {
                Frame frame = view.getOrDefault(Frame.class, null);
                Operation parent = view.getOrDefault(Operation.class, null);
                if (frame == null || parent == null) {
                    return Mono.error(new IllegalStateException("Process " + definition.name()
                        + " can only be called as a sub-process from a step of a running process"));
                }
                return RequestContexts.current().flatMap(request -> sequence.next().flatMap(seq -> {
                    OperationRequest operation = new OperationRequest(definition.name(), definition.version(), seq,
                        parent.processSeqId(), null, null, null, parent.opTime(), masker.summary(input));
                    return operations.begin(engine(), operation, request)
                        .flatMap(started -> runProcess(definition, input, started, request, frame.afterCommit(), false));
                })).map(ProcessResult::output);
            }));
    }

    /**
     * Runs {@code work} - sub-processes of the running process, usually behind a savepoint - so that the after-commit
     * steps they register are kept only if {@code work} completes: when it fails, and its writes are undone by a
     * rollback to the savepoint, the after-commit steps of the sub-processes that had succeeded within it are dropped
     * as well, instead of running once the transaction commits (an import's rows, docs/design/20-imports.md section 5).
     */
    public <T> Mono<T> keepingAfterCommitOnSuccess(Mono<T> work) {
        return Mono.deferContextual(view -> {
            Frame frame = view.getOrDefault(Frame.class, null);
            if (frame == null) {
                return Mono.error(new IllegalStateException("Only within a step of a running process"));
            }
            List<PendingStep<?>> local = Collections.synchronizedList(new ArrayList<>());
            return work
                .doOnSuccess(result -> frame.afterCommit().addAll(local))
                .contextWrite(context -> context.put(Frame.class, new Frame(local)));
        });
    }

    /** Carries a dry run's result out of the transaction it rolls back. */
    private static final class DryRunComplete extends RuntimeException {
        private final transient ProcessResult<?> result;

        DryRunComplete(ProcessResult<?> result) {
            super("dry run complete", null, false, false);
            this.result = result;
        }
    }

    @SuppressWarnings("unchecked")
    private static <O> ProcessResult<O> cast(ProcessResult<?> result) {
        return (ProcessResult<O>) result;
    }

    /** The storage of the operation tables, where every process transaction runs. */
    StorageEngine engine() {
        return storages.getEngine(poolRef);
    }

    private <I, O, C extends ProcessContext> Mono<ProcessResult<O>> runProcess(
        ProcessDefinition<I, O, C> definition, I input, Operation operation, RequestContext request,
        List<PendingStep<?>> afterCommit, boolean keepOutput
    ) {
        return Mono.defer(() -> {
            ProcessStart start = new ProcessStart(operation.processSeqId(), operation.opTime(), request,
                committer.idAssigner());
            C ctx = Objects.requireNonNull(definition.contextFactory().create(start, input),
                "Process " + definition.name() + ": context factory returned null");
            StorageEngine engine = engine();
            return Flux.fromIterable(definition.steps())
                .filter(step -> step.phase() == StepPhase.IN_TX)
                .concatMap(step -> runStep(definition, step, ctx)
                    .onErrorMap(BusinessRuleViolationException.class, error -> withCollected(ctx, error)))
                .then(Mono.defer(() -> {
                    if (ctx.hasViolations()) {
                        return Mono.error(new BusinessRuleViolationException(List.copyOf(ctx.violations())));
                    }
                    return committer.commit(ctx);
                }))
                .then(Mono.fromCallable(() -> Objects.requireNonNull(definition.outputMapper().apply(ctx),
                    "Process " + definition.name() + ": output mapper returned null")))
                // The output is kept only where a replay needs it: op_process_result cannot be purged. Secrets are
                // left out (null), so a replay returns them as null.
                .flatMap(output -> (keepOutput
                        ? operations.recordResult(engine, operation.processSeqId(), masker.withoutSecrets(output))
                        : Mono.<Void>empty())
                    .thenReturn(new ProcessResult<>(operation.processSeqId(), output, false)))
                .doOnSuccess(result -> definition.steps().stream()
                    .filter(step -> step.phase() == StepPhase.AFTER_COMMIT)
                    .forEach(step -> afterCommit.add(new PendingStep<>(definition, step, ctx))))
                .contextWrite(view -> Operations.with(view, operation).put(Frame.class, new Frame(afterCommit)));
        });
    }

    /**
     * Violations a failing step reports come together with those collected before it, each once: a step that
     * refuses to go on because of the collected violations (such as {@code SaveChanges}) reports those very ones.
     */
    private static BusinessRuleViolationException withCollected(ProcessContext ctx, BusinessRuleViolationException e) {
        if (!ctx.hasViolations()) {
            return e;
        }
        List<Violation> all = new ArrayList<>(ctx.violations());
        e.violations().stream().filter(violation -> !all.contains(violation)).forEach(all::add);
        return new BusinessRuleViolationException(all);
    }

    private <M, C extends ProcessContext> Mono<Void> runStep(
        ProcessDefinition<?, ?, C> definition, StepDefinition<M, C> step, C ctx
    ) {
        return Mono.defer(() -> dispatch(implementation(step), step.metadata(), ctx))
            .doOnError(error -> log.warn("Process {} v{} (seq {}) failed at step '{}': {}",
                definition.name(), definition.version(), ctx.processSeqId(), step.stepName(), error.toString()));
    }

    private <M, C extends ProcessContext> StepImplementation<M, C> implementation(StepDefinition<M, C> step) {
        // Checked at startup (ProcessChecks): every bean step class has exactly one bean.
        return step.isInline() ? step.inline() : beans.getBean(step.handlerClass());
    }

    private static <M, C extends ProcessContext> Mono<Void> dispatch(StepImplementation<M, C> implementation,
        M metadata, C ctx) {
        return switch (implementation) {
            case StepHandler<M, C> handler -> handler.execute(metadata, ctx);
            case ComputeStep<M, C> compute -> Mono.fromRunnable(() -> compute.compute(metadata, ctx));
            case BlockingStep<M, C> blocking -> Mono.fromCallable(() -> {
                    blocking.run(metadata, ctx);
                    return Boolean.TRUE;
                })
                .subscribeOn(Schedulers.boundedElastic())
                // Leave the virtual thread: the steps after this one are held to the non-blocking rules again.
                .publishOn(Schedulers.parallel())
                .then();
            default -> Mono.error(new IllegalStateException(implementation.getClass().getName()
                + " is neither a StepHandler, a ComputeStep nor a BlockingStep"));
        };
    }

    // ================= After commit =================

    /** Carried in the Reactor context of a running process, for its sub-processes. */
    private record Frame(List<PendingStep<?>> afterCommit) {}

    /** An after-commit step of a process that took part in a committed transaction. */
    private record PendingStep<C extends ProcessContext>(
        ProcessDefinition<?, ?, C> definition, StepDefinition<?, C> step, C ctx) {}

    /**
     * Runs the after-commit steps on their own, so that the response does not wait for their retries and a caller
     * that goes away cannot cancel them: the data is committed either way. They keep the caller's Reactor context
     * (request context, request id in the logs).
     */
    private void startAfterCommit(StorageEngine engine, List<PendingStep<?>> steps, ContextView view) {
        if (steps.isEmpty()) {
            return;
        }
        Flux.fromIterable(steps)
            .concatMap(pending -> attempt(engine, pending, 1))
            .contextWrite(view)
            .subscribe(null, error -> log.error("After-commit steps stopped unexpectedly", error));
    }

    /** An after-commit step registered changes, which can no longer be committed; retrying would not help. */
    private static final class ChangesAfterCommit extends IllegalStateException {
        ChangesAfterCommit(String message) {
            super(message);
        }
    }

    private <C extends ProcessContext> Mono<Void> attempt(StorageEngine engine, PendingStep<C> pending, int attempt) {
        StepDefinition<?, C> step = pending.step();
        long seq = pending.ctx().processSeqId();
        return Mono.delay(step.retryPolicy().backoffBefore(attempt))
            .then(runStep(pending.definition(), step, pending.ctx()))
            .then(Mono.fromRunnable(() -> {
                if (!pending.ctx().changes().drain().isEmpty()) {
                    throw new ChangesAfterCommit("After-commit step '" + step.stepName()
                        + "' registered changes; its transaction is over, so they cannot be committed");
                }
            }))
            .then(record(engine, seq, step.stepName(), attempt, null))
            .onErrorResume(error -> record(engine, seq, step.stepName(), attempt, describe(error))
                .then(Mono.defer(() -> {
                    if (attempt < step.retryPolicy().maxAttempts() && !(error instanceof ChangesAfterCommit)) {
                        return attempt(engine, pending, attempt + 1);
                    }
                    log.error("After-commit step '{}' of process {} v{} (seq {}) failed after {} attempt(s)",
                        step.stepName(), pending.definition().name(), pending.definition().version(), seq, attempt,
                        error);
                    return Mono.empty();
                })));
    }

    private Mono<Void> record(StorageEngine engine, long seq, String stepName, int attempt, String error) {
        return operations.recordAfterCommitAttempt(engine, seq, stepName, attempt, error)
            .onErrorResume(failure -> {
                log.error("Could not record attempt {} of after-commit step '{}' (seq {})", attempt, stepName, seq,
                    failure);
                return Mono.empty();
            });
    }

    private static String describe(Throwable error) {
        String text = error.getClass().getName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }
}

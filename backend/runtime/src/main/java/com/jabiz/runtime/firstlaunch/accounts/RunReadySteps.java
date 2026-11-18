package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.firstlaunch.FirstLaunchStep;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.process.StepHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Runs the application's steps for a ready account (section 9) in {@code FIRST_LAUNCH_ACCOUNT_READY}'s transaction,
 * each as its sub-process, skipping those recorded already and recording each that ran
 * ({@code sys_first_launch_ready}): so each runs once, and a failure rolls the item back to be tried again. Does nothing
 * unless the run waits, the account is ready (a configured role in effect) and not left to people. A platform I/O step.
 */
@Component
public class RunReadySteps<C extends ProcessContext> implements StepHandler<RunReadySteps.Metadata, C> {

    private static final Logger log = LoggerFactory.getLogger(RunReadySteps.class);

    /** The context key of the account's user id. */
    public static final String USER = "ready_user";
    /** The context key of how many steps ran. */
    public static final String RAN = "ready_ran";

    public record Metadata() {}

    public static <C extends ProcessContext> StepSpec<Metadata, C> of() {
        return StepSpec.of(RunReadySteps.class, new Metadata());
    }

    private final ReadySteps steps;
    private final FirstLaunchAccountStore store;
    private final ProcessRegistry registry;
    private final ProcessExecutor executor;

    public RunReadySteps(ReadySteps steps, FirstLaunchAccountStore store, ProcessRegistry registry,
        ProcessExecutor executor) {
        this.steps = steps;
        this.store = store;
        this.registry = registry;
        this.executor = executor;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, C ctx) {
        return Mono.defer(() -> {
            ctx.put(RAN, 0);
            if (!ctx.contains(LoadWaitingState.STATE)) {
                return Mono.empty();
            }
            LoadWaitingState.State state = ctx.get(LoadWaitingState.STATE, LoadWaitingState.State.class);
            UUID user = (UUID) ctx.get(USER);
            FirstLaunchAccountStore.Recorded recorded = state.accounts().stream()
                .filter(account -> account.userId().equals(user)).findFirst().orElse(null);
            GrantPlan.Account account = FirstLaunchWaiting.accounts(state).stream()
                .filter(found -> found.userId().equals(user)).findFirst().orElse(null);
            if (recorded == null || account == null || account.leftToPeople() || !account.anyApplied()) {
                return Mono.empty();
            }
            ReadySteps.Plan plan = steps.of(state, recorded);
            if (plan.configMissing()) {
                // Reported by the startup and the run's report; failing every minute would change nothing.
                return Mono.empty();
            }
            if (plan.problem() != null) {
                return Mono.error(new IllegalStateException("First launch: account " + recorded.label() + " (user "
                    + user + "): " + plan.problem()));
            }
            UUID run = state.run().runId();
            return store.readyDone(run, user).flatMap(done -> Flux.fromIterable(plan.steps())
                .filter(step -> !done.contains(step.key()))
                .concatMap(step -> call(step, plan, recorded, user)
                    .then(store.recordReady(run, user, step.key(), ctx.processSeqId()))
                    .doOnSuccess(ignored -> ctx.put(RAN, (Integer) ctx.get(RAN) + 1)))
                .then());
        });
    }

    private Mono<Object> call(FirstLaunchStep step, ReadySteps.Plan plan, FirstLaunchAccountStore.Recorded recorded,
        UUID user) {
        ProcessDefinition<?, ?, ?> target = registry.findLatest(step.process()).orElseThrow(
            () -> new IllegalStateException("Unknown process " + step.process()));
        log.info("First launch: account {} (user {}) is ready: {}", recorded.label(), user, step.describe());
        // The input from the handler's function: user ids only, no outputs of the content phase.
        return call(target, step.input().apply(plan.results()));
    }

    @SuppressWarnings("unchecked")
    private <I, O, D extends ProcessContext> Mono<Object> call(ProcessDefinition<I, O, D> target, Object input) {
        if (!target.inputType().isInstance(input)) {
            return Mono.error(new IllegalStateException("Process " + target.name() + " takes "
                + target.inputType().getName() + " but was given " + (input == null ? "null"
                : input.getClass().getName())));
        }
        return (Mono<Object>) executor.executeChild(target, target.inputType().cast(input));
    }
}

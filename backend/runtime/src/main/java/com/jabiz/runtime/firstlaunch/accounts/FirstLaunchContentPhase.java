package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The account steps of the content phase: refuses to run outside it (a second line behind {@code FIRST_LAUNCH_STEP},
 * which checks the run too), puts the run's id into the context, and records the accounts the step created
 * ({@code sys_first_launch_account}). A platform I/O step.
 */
@Component
public class FirstLaunchContentPhase<C extends ProcessContext>
    implements StepHandler<FirstLaunchContentPhase.Metadata<C>, C> {

    /** The context key of the run's id. */
    public static final String RUN_ID = "first_launch_run_id";

    /** @param record the accounts to record, built on the context (null: only check the phase) */
    public record Metadata<C>(Function<C, List<FirstLaunchAccountStore.Recorded>> record) {}

    /** Checks that a run is in its content phase and puts its id under {@link #RUN_ID}. */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> require() {
        return StepSpec.of(FirstLaunchContentPhase.class, new Metadata<>(null));
    }

    /** Records the accounts {@code record} gives, in the run of {@link #RUN_ID}. */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> record(
        Function<C, List<FirstLaunchAccountStore.Recorded>> record) {
        return StepSpec.of(FirstLaunchContentPhase.class, new Metadata<>(record));
    }

    private final FirstLaunchAccountStore store;

    public FirstLaunchContentPhase(FirstLaunchAccountStore store) {
        this.store = store;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        if (metadata.record() != null) {
            return Mono.defer(() -> reactor.core.publisher.Flux.fromIterable(metadata.record().apply(ctx))
                .concatMap(account -> store.record(account, ctx.processSeqId())).then());
        }
        return store.runningRun().flatMap(run -> {
            if (run.isEmpty()) {
                return Mono.error(new IllegalStateException("No first launch is in its content phase: the first"
                    + " launch's accounts are created only then"));
            }
            UUID runId = run.get();
            ctx.put(RUN_ID, runId);
            return Mono.empty();
        });
    }
}

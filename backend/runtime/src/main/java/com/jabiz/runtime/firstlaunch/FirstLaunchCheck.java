package com.jabiz.runtime.firstlaunch;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Function;

/**
 * A step of the platform's first-launch powers (decision D82): the process runs only as the system, inside a step of
 * a first launch whose content phase goes on, on an instance that run made, and (when {@code key} is given) for a
 * target key the application declared. Puts the run's configuration hash under {@link #HASH}.
 */
@Component
public class FirstLaunchCheck implements StepHandler<FirstLaunchCheck.Metadata, ProcessContext> {

    /** Where the check puts the hash of the run's configuration. */
    public static final String HASH = "first_launch_config_hash";

    /**
     * @param table    the temporal table of the instance
     * @param idColumn its key column
     * @param id       the instance's id, from the context
     * @param key      the control's target key, from the context, checked against
     *                 {@code FirstLaunchHandler.publishableControlKeys()}; {@code null} for no such check
     */
    public record Metadata(String table, String idColumn, Function<ProcessContext, Object> id,
        Function<ProcessContext, String> key) {
        public Metadata {
            Objects.requireNonNull(table, "table must not be null");
            Objects.requireNonNull(idColumn, "idColumn must not be null");
            Objects.requireNonNull(id, "id must not be null");
        }
    }

    public static StepSpec<Metadata, ProcessContext> of(String table, String idColumn,
        Function<ProcessContext, Object> id, Function<ProcessContext, String> key) {
        return StepSpec.of(FirstLaunchCheck.class, new Metadata(table, idColumn, id, key));
    }

    private final FirstLaunchPowers powers;

    public FirstLaunchCheck(FirstLaunchPowers powers) {
        this.powers = powers;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
        return powers.contentPhase(ctx.request(), true).flatMap(run -> {
            if (metadata.key() != null) {
                String key = metadata.key().apply(ctx);
                if (key == null || !powers.publishableControlKeys().contains(key)) {
                    return Mono.error(new PermissionDeniedException(FirstLaunchProcesses.PERMISSION,
                        "First launch: " + key + " is not among the controls the application lets it publish"));
                }
            }
            return powers.requireMadeInRun(run, metadata.table(), metadata.idColumn(), metadata.id().apply(ctx))
                .then(Mono.fromRunnable(() -> ctx.put(HASH, run.configHash())));
        });
    }
}

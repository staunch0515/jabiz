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
     * @param entity   the control's target entity, from the context: only {@link #PUBLISHABLE_ENTITIES} (with
     *                 {@code key}); {@code null} with no key
     * @param proposer who proposed the control's change, from the context: the system only (with {@code key})
     */
    public record Metadata(String table, String idColumn, Function<ProcessContext, Object> id,
        Function<ProcessContext, String> key, Function<ProcessContext, String> entity,
        Function<ProcessContext, String> proposer) {
        public Metadata {
            Objects.requireNonNull(table, "table must not be null");
            Objects.requireNonNull(idColumn, "idColumn must not be null");
            Objects.requireNonNull(id, "id must not be null");
        }
    }

    /**
     * The controls whose initial values the first launch may publish: controlled parameters and approval rules. Never
     * SoD rules or approver limits (decision D82): those are people's to publish.
     */
    public static final java.util.Set<String> PUBLISHABLE_ENTITIES = java.util.Set.of(
        com.jabiz.runtime.param.ParamEntities.ENTITY, com.jabiz.runtime.approval.ApprovalEntities.RULE);

    /** The check of an approval request (or any instance) the run must have made. */
    public static StepSpec<Metadata, ProcessContext> of(String table, String idColumn,
        Function<ProcessContext, Object> id) {
        return StepSpec.of(FirstLaunchCheck.class, new Metadata(table, idColumn, id, null, null, null));
    }

    /** The check of a controlled change the run proposed, as the system, for a publishable control. */
    public static StepSpec<Metadata, ProcessContext> ofControl(String table, String idColumn,
        Function<ProcessContext, Object> id, Function<ProcessContext, String> entity,
        Function<ProcessContext, String> key, Function<ProcessContext, String> proposer) {
        return StepSpec.of(FirstLaunchCheck.class, new Metadata(table, idColumn, id,
            Objects.requireNonNull(key), Objects.requireNonNull(entity), Objects.requireNonNull(proposer)));
    }

    private final FirstLaunchPowers powers;

    public FirstLaunchCheck(FirstLaunchPowers powers) {
        this.powers = powers;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
        return powers.contentPhase(ctx.request(), ctx.processSeqId(), true).flatMap(run -> {
            if (metadata.key() != null) {
                String entity = metadata.entity().apply(ctx);
                if (!PUBLISHABLE_ENTITIES.contains(entity)) {
                    return Mono.error(new PermissionDeniedException(FirstLaunchProcesses.PERMISSION,
                        "First launch: it publishes no " + entity + " (only parameters and approval rules)"));
                }
                if (!com.jabiz.context.RequestContext.SYSTEM_ACTOR.equals(metadata.proposer().apply(ctx))) {
                    return Mono.error(new PermissionDeniedException(FirstLaunchProcesses.PERMISSION,
                        "First launch: it publishes only what the system proposed in it"));
                }
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

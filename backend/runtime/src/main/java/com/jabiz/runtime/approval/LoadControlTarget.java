package com.jabiz.runtime.approval;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Function;

/**
 * Builds the {@link ControlTarget.Request} of a controlled change once (from the proposal's input or from the
 * recorded change) and loads the state it is based on through the change's {@link ControlTarget}: the entity and so
 * the target are only known from the change ({@link ControlChanges}). Nothing is loaded for an entity no target
 * changes, or when there is nothing to base the change on.
 */
@Component
class LoadControlTarget<C extends ProcessContext> implements StepHandler<LoadControlTarget.Metadata<C>, C> {

    record Metadata<C>(Function<C, ControlTarget.Request> request, String requestKey, String targetKey) {
        Metadata {
            Objects.requireNonNull(request, "request must not be null");
            Objects.requireNonNull(requestKey, "requestKey must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
        }
    }

    static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(Function<C, ControlTarget.Request> request,
        String requestKey, String targetKey) {
        return StepSpec.of(LoadControlTarget.class, new Metadata<>(request, requestKey, targetKey));
    }

    private final ControlTargets targets;

    LoadControlTarget(ControlTargets targets) {
        this.targets = targets;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            ControlTarget.Request request = metadata.request().apply(ctx);
            ctx.put(metadata.requestKey(), request);
            return targets.find(request.entity())
                .map(target -> target.load(request, ctx).doOnNext(found -> ctx.put(metadata.targetKey(), found))
                    .then())
                .orElseGet(Mono::empty);
        });
    }
}

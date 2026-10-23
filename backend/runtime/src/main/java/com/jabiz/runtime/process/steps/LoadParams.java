package com.jabiz.runtime.process.steps;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.param.ParamService;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Loads business parameters as they were in effect at a time taken from the context, and puts them as
 * {@link com.jabiz.param.ParamValues} into the context (docs/design/04-temporal-append-only.md section 9,
 * docs/design/06-process.md section 2.1). Pass the time the business event happened, not the current time. A key
 * without a value in effect then fails the process with {@code PARAM_NOT_FOUND} (422), all such keys at once; with
 * {@link #optional} such keys are absent instead ({@link com.jabiz.param.ParamValues#find}), for settings an
 * application treats as "not set" (phase 16zc).
 */
@Component
public class LoadParams<C extends ProcessContext> implements StepHandler<LoadParams.Metadata<C>, C>,
    CheckedStep<LoadParams.Metadata<C>> {

    /** What to load; {@code optional}: keys without a value in effect are absent rather than refused. */
    public record Metadata<C>(Function<C, Instant> asOf, String targetKey, List<String> keys, boolean optional) {
        public Metadata {
            Objects.requireNonNull(asOf, "asOf must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
            keys = List.copyOf(keys);
        }

        public Metadata(Function<C, Instant> asOf, String targetKey, List<String> keys) {
            this(asOf, targetKey, keys, false);
        }
    }

    /** Loads {@code keys} in effect at {@code asOf(ctx)} into {@code targetKey}. */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(Function<C, Instant> asOf, String targetKey,
        String... keys) {
        return StepSpec.of(LoadParams.class, new Metadata<>(asOf, targetKey, List.of(keys)));
    }

    /**
     * Loads {@code keys} in effect at {@code asOf(ctx)} into {@code targetKey}, leaving out those without a value
     * then: the process reads them with {@code find} and decides what "not set" means (a feature not configured yet).
     */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> optional(Function<C, Instant> asOf,
        String targetKey, String... keys) {
        return StepSpec.of(LoadParams.class, new Metadata<>(asOf, targetKey, List.of(keys), true));
    }

    private final ParamService params;

    public LoadParams(ParamService params) {
        this.params = params;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            Instant asOf = Objects.requireNonNull(metadata.asOf().apply(ctx), "asOf must not be null");
            return (metadata.optional() ? params.loadAvailable(metadata.keys(), asOf)
                : params.load(metadata.keys(), asOf))
                .doOnNext(values -> ctx.put(metadata.targetKey(), values))
                .then();
        });
    }

    @Override
    public List<String> problems(Metadata<C> metadata) {
        if (metadata.keys().isEmpty()) {
            return List.of("no parameter keys");
        }
        return metadata.keys().stream().filter(key -> key == null || key.isBlank())
            .map(key -> "blank parameter key").distinct().toList();
    }
}

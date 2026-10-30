package com.jabiz.runtime.process.steps;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Function;

/**
 * Checks that what a process acts on lies in the caller's entry scope (decision D64 item 4; docs/design/10-security.md
 * section 13.4): a session of the scope {@code scope} whose value differs from the object's, or acting on an object
 * of no value, gets 404 as if the object did not exist. Callers without that scope (headquarters, the system, other
 * entries) pass. Datasets limit reads; a process reads through default datasets, so it states the check here, after
 * loading the object and before acting on it: {@code .step("In the operator's scope", RequireScope.of("operator",
 * ctx -> operatorOf(ctx)))}.
 */
@Component
public class RequireScope<C extends ProcessContext> implements StepHandler<RequireScope.Metadata<C>, C> {

    /**
     * @param scope the entry scope's name
     * @param value the object's value of it, from the context; null when the object has none
     * @param what  what the 404 names (the object's entity and id), from the context
     */
    public record Metadata<C>(String scope, Function<C, String> value, Function<C, String> what) {
        public Metadata {
            Objects.requireNonNull(scope, "scope must not be null");
            Objects.requireNonNull(value, "value must not be null");
            Objects.requireNonNull(what, "what must not be null");
        }
    }

    /** The object's value of the entry scope {@code scope} must be the caller's, if the caller has that scope. */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(String scope, Function<C, String> value) {
        return StepSpec.of(RequireScope.class, new Metadata<>(scope, value, ctx -> "The object"));
    }

    /** As {@link #of(String, Function)}, the 404 naming the object ({@code "QbSponsor 42"}). */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(String scope, Function<C, String> value,
        Function<C, String> what) {
        return StepSpec.of(RequireScope.class, new Metadata<>(scope, value, what));
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.fromRunnable(() -> {
            String mine = ctx.request().entryScopeValue(metadata.scope());
            if (mine != null && !mine.equals(metadata.value().apply(ctx))) {
                // The same answer as for an object that does not exist: a scope does not reveal the others' objects.
                throw new EntityNotFoundException(metadata.what().apply(ctx) + " not found");
            }
        });
    }
}

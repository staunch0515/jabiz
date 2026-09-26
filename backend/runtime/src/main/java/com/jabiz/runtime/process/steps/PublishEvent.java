package com.jabiz.runtime.process.steps;

import com.jabiz.event.EventSubscription;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Publishes an event built from the context through the {@link EventPublisher} (docs/design/06-process.md section
 * 2.1). The event is written to the outbox in the process's transaction and delivered after the commit
 * ({@code OutboxEventPublisher}, docs/design/11-ledger-events-jobs.md section 2); the payload must be an object.
 */
@Component
public class PublishEvent<C extends ProcessContext> implements StepHandler<PublishEvent.Metadata<C>, C>,
    CheckedStep<PublishEvent.Metadata<C>> {

    /**
     * @param condition whether to publish at all, decided on the context when the step is reached; always, if null
     */
    public record Metadata<C>(String eventType, Function<C, ?> payload, Predicate<C> condition) {
        public Metadata {
            Objects.requireNonNull(eventType, "eventType must not be null");
            Objects.requireNonNull(payload, "payload must not be null");
            condition = condition == null ? ctx -> true : condition;
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(String eventType, Function<C, ?> payload) {
        return StepSpec.of(PublishEvent.class, new Metadata<>(eventType, payload, null));
    }

    /**
     * Publishes only when {@code condition} holds, for example only when an earlier step produced a result (a step
     * after a rejecting one still runs; the process fails once all steps ran).
     */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> when(Predicate<C> condition, String eventType,
        Function<C, ?> payload) {
        return StepSpec.of(PublishEvent.class, new Metadata<>(eventType, payload,
            Objects.requireNonNull(condition, "condition must not be null")));
    }

    private final ObjectProvider<EventPublisher> publisher;

    public PublishEvent(ObjectProvider<EventPublisher> publisher) {
        this.publisher = publisher;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            if (!metadata.condition().test(ctx)) {
                return Mono.empty();
            }
            EventPublisher target = publisher.getIfUnique();
            if (target == null) {
                return Mono.error(new IllegalStateException("No EventPublisher is configured"));
            }
            return target.publish(metadata.eventType(), metadata.payload().apply(ctx), ctx.processSeqId());
        });
    }

    @Override
    public List<String> problems(Metadata<C> metadata) {
        List<String> problems = new java.util.ArrayList<>();
        if (!EventSubscription.NAME.matcher(metadata.eventType()).matches()) {
            problems.add("event type '" + metadata.eventType() + "' must match " + EventSubscription.NAME.pattern());
        }
        if (publisher.getIfUnique() == null) {
            problems.add("publishes " + metadata.eventType() + " but no EventPublisher is configured");
        }
        return problems;
    }
}

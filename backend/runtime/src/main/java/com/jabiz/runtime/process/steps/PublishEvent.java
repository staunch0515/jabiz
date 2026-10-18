package com.jabiz.runtime.process.steps;

import com.jabiz.event.EventSubscription;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.security.CodeSource;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Publishes an event built from the context through the {@link EventPublisher} (docs/design/06-process.md section
 * 2.1). The event is written to the outbox in the process's transaction and delivered after the commit
 * ({@code OutboxEventPublisher}, docs/design/11-ledger-events-jobs.md section 2); the payload must be an object.
 *
 * <p>Event types beginning with {@value #RESERVED} are the platform's (an approval's outcome, an issued document or
 * report): only a step the platform's own code built may publish one (review of 16r). The platform's subscribers
 * still trust nothing in such an event but what they read back from the records it names; this keeps an application's
 * process from posing as the platform by mistake, and is not a boundary: code that builds its own {@link StepSpec}
 * reusing a platform step's metadata passes, and the test is where the building class was loaded from - it fails open
 * when an application is shaded into one jar with the runtime, and closed (nothing reserved is allowed) where classes
 * have no code source.
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

    /** Event types the platform keeps for itself. */
    public static final String RESERVED = "jabiz.";

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    /**
     * The steps of reserved event types the platform's own code built, by identity: only those of reserved types (a
     * handful, built when the platform's definitions are) - every other step is allowed anyway, and nothing is kept
     * for it.
     */
    private static final Set<Object> PLATFORM = Collections.synchronizedSet(
        Collections.newSetFromMap(new IdentityHashMap<>()));
    /** Whether a class comes from where this one does, computed once per class. */
    private static final ClassValue<Boolean> FROM_PLATFORM = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            CodeSource ours = PublishEvent.class.getProtectionDomain().getCodeSource();
            CodeSource theirs = type.getProtectionDomain().getCodeSource();
            return ours != null && theirs != null && ours.getLocation() != null
                && ours.getLocation().equals(theirs.getLocation());
        }
    };

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(String eventType, Function<C, ?> payload) {
        return StepSpec.of(PublishEvent.class, built(new Metadata<>(eventType, payload, null),
            WALKER.getCallerClass()));
    }

    /**
     * Publishes only when {@code condition} holds, for example only when an earlier step produced a result (a step
     * after a rejecting one still runs; the process fails once all steps ran).
     */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> when(Predicate<C> condition, String eventType,
        Function<C, ?> payload) {
        return StepSpec.of(PublishEvent.class, built(new Metadata<>(eventType, payload,
            Objects.requireNonNull(condition, "condition must not be null")), WALKER.getCallerClass()));
    }

    private static <M> M built(M metadata, Class<?> caller) {
        if (metadata instanceof Metadata<?> built && built.eventType().startsWith(RESERVED)
            && FROM_PLATFORM.get(caller)) {
            PLATFORM.add(metadata);
        }
        return metadata;
    }

    /** Whether the step may publish its event type: any but a reserved one, which only the platform's own steps. */
    static boolean allowed(Metadata<?> metadata) {
        return !metadata.eventType().startsWith(RESERVED) || PLATFORM.contains(metadata);
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
            if (!allowed(metadata)) {
                return Mono.error(new IllegalStateException("Event type " + metadata.eventType()
                    + " is reserved for the platform (" + RESERVED + "*)"));
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
        if (!allowed(metadata)) {
            problems.add("event type '" + metadata.eventType() + "' is reserved for the platform (" + RESERVED
                + "*): name the application's events after the application");
        }
        if (publisher.getIfUnique() == null) {
            problems.add("publishes " + metadata.eventType() + " but no EventPublisher is configured");
        }
        return problems;
    }
}

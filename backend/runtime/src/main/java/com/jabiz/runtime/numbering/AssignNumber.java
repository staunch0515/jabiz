package com.jabiz.runtime.numbering;

import com.jabiz.numbering.NumberSequence;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.process.steps.CheckedStep;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Draws the next number of a sequence inside the process's transaction and puts it (its written form) into the
 * context (docs/design/18-numbering-approvals-tasks.md section 2, decision D23).
 *
 * <p>The counter row of the sequence and scope is incremented with one statement that holds its row lock until the
 * transaction ends: a process that fails afterwards rolls the increment back, so no number is lost; a concurrent
 * process of the same scope waits and draws the next one, so none is issued twice. Every issued number is also
 * recorded as a {@link NumberingEntities#NUMBER_ASSIGNMENT} row with the process that drew it.
 *
 * <p>Draw the number as late as possible (after the checks that can reject the process): the lock serializes the
 * processes of one scope until they end.
 */
@Component
public class AssignNumber<C extends ProcessContext> implements StepHandler<AssignNumber.Metadata<C>, C>,
    CheckedStep<AssignNumber.Metadata<C>> {

    /**
     * @param scope the scope of a scoped sequence (a fiscal year, a source); null for an unscoped one
     * @param when  whether to draw at all (null: always); a number that is not needed must not be drawn, since every
     *              drawn number is kept
     */
    public record Metadata<C>(String sequence, Function<C, String> scope, String targetKey, Predicate<C> when) {
        public Metadata {
            Objects.requireNonNull(sequence, "sequence must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
        }
    }

    /** The next number of the unscoped {@code sequence}, into {@code targetKey}. */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(String sequence, String targetKey) {
        return StepSpec.of(AssignNumber.class, new Metadata<C>(sequence, null, targetKey, null));
    }

    /** The next number of the scoped {@code sequence} in the scope {@code scope(ctx)}, into {@code targetKey}. */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(String sequence, Function<C, String> scope,
        String targetKey) {
        return StepSpec.of(AssignNumber.class,
            new Metadata<>(sequence, Objects.requireNonNull(scope), targetKey, null));
    }

    /**
     * As {@link #of(String, Function, String)} (or, with a null {@code scope}, the unscoped form) when
     * {@code condition(ctx)} holds; otherwise nothing is drawn and {@code targetKey} stays unset.
     */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> when(Predicate<C> condition, String sequence,
        Function<C, String> scope, String targetKey) {
        return StepSpec.of(AssignNumber.class,
            new Metadata<>(sequence, scope, targetKey, Objects.requireNonNull(condition)));
    }

    private static final String NEXT = "INSERT INTO sys_number_counter (sequence_name, scope_key, last_value)"
        + " VALUES (:sequence, :scope, :first)"
        + " ON CONFLICT (sequence_name, scope_key)"
        + " DO UPDATE SET last_value = sys_number_counter.last_value + 1"
        + " RETURNING last_value";

    private final NumberSequenceRegistry sequences;
    private final StorageAdapterRegistry storages;
    private final EntityIdGenerator ids;
    private final String poolRef;

    public AssignNumber(NumberSequenceRegistry sequences, StorageAdapterRegistry storages, EntityIdGenerator ids,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.sequences = sequences;
        this.storages = storages;
        this.ids = ids;
        this.poolRef = poolRef;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            if (metadata.when() != null && !metadata.when().test(ctx)) {
                return Mono.empty();
            }
            NumberSequence sequence = sequences.find(metadata.sequence()).orElseThrow(() ->
                new IllegalStateException("Number sequence " + metadata.sequence() + " is not declared"));
            String scopeKey = sequence.scopeKey(metadata.scope() == null ? null : metadata.scope().apply(ctx));
            var engine = storages.getEngine(poolRef);
            return engine.select(NEXT, Map.of(
                    "sequence", BoundValue.of(sequence.name()),
                    "scope", BoundValue.of(scopeKey),
                    "first", BoundValue.of(sequence.startAt())))
                .single()
                .flatMap(row -> {
                    long value = ((Number) row.get("last_value")).longValue();
                    String number = sequence.format(value, scopeKey);
                    Map<String, Object> assignment = new LinkedHashMap<>();
                    assignment.put("assignment_id",
                        UUID.fromString(String.valueOf(ids.next(NumberingEntities.NUMBER_ASSIGNMENT))));
                    assignment.put("sequence_name", sequence.name());
                    assignment.put("scope_key", scopeKey);
                    assignment.put("value_no", value);
                    assignment.put("number", number);
                    assignment.put("process_seq_id", ctx.processSeqId());
                    assignment.put("assigned_time", ctx.opTime());
                    assignment.put("version", 1L);
                    return engine.insert(NumberingEntities.TABLE, assignment)
                        .then(Mono.fromRunnable(() -> ctx.put(metadata.targetKey(), number)));
                })
                .then();
        });
    }

    @Override
    public List<String> problems(Metadata<C> metadata) {
        return sequences.find(metadata.sequence())
            .map(sequence -> {
                if (sequence.scoped() && metadata.scope() == null) {
                    return List.of("number sequence " + sequence.name() + " is scoped: give the scope");
                }
                if (!sequence.scoped() && metadata.scope() != null) {
                    return List.of("number sequence " + sequence.name() + " is not scoped: give no scope");
                }
                return List.<String>of();
            })
            .orElseGet(() -> List.of("number sequence " + metadata.sequence() + " is not declared"));
    }
}

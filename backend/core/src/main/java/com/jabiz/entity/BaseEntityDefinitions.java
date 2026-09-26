package com.jabiz.entity;

import java.math.BigDecimal;
import java.util.function.Consumer;

/**
 * Field and rule patterns shared by several entity definitions.
 *
 * EntityDefinition is final, so reuse is provided by the classes that hold definitions
 * (for example WaybillEntityDefinitions): they extend this class and use its protected
 * static helpers. Each helper returns a Consumer of FieldBuilder that can be passed to
 * {@code eb.field(name, ...)}. To add configuration on top of a shared pattern, compose with
 * {@code andThen}:
 *
 * <pre>
 *   eb.field("x", nonNegativeMonetary("f_x", "RULE_X", "JPY", 0)
 *           .andThen(f -> f.immutable(true)));
 * </pre>
 *
 * Rule predicates receive values already normalized to their canonical types
 * (see {@link FieldValueCoercer}): Instant for temporal fields, BigDecimal for monetary
 * fields. Domain specific patterns (physical quantities, spatial cells) live in extension modules.
 */
public abstract class BaseEntityDefinitions {

    /** Identity field: immutable, required, globally identified by a URN. */
    protected static Consumer<FieldBuilder> semanticIdentity(String physicalColumn, String urn) {
        return f -> f.physicalColumn(physicalColumn).immutable(true).required(true).asSemanticIdentity(urn);
    }

    /** Audit field issued by the system at insert time; immutable and never accepted from callers. */
    protected static Consumer<FieldBuilder> systemRecordedTime(String physicalColumn) {
        return f -> f.physicalColumn(physicalColumn).immutable(true).asTemporal(TemporalRole.SYSTEM_RECORDED);
    }

    /** Optimistic-lock version field, managed by the storage layer. */
    protected static Consumer<FieldBuilder> rowVersion(String physicalColumn) {
        return f -> f.physicalColumn(physicalColumn).immutable(true).asVersion();
    }

    /**
     * Temporal causality: a business event time must not be later than the current time
     * (as reported by the injected clock) plus toleranceSeconds. This constraint is common to
     * audit, logistics and financial entities.
     */
    protected static Consumer<FieldBuilder> temporalCausality(String physicalColumn, String ruleCode, int toleranceSeconds) {
        return f -> f.physicalColumn(physicalColumn)
            .asTemporal(TemporalRole.EVENT_TIME)
            .apply(Rules.notFuture(ruleCode, toleranceSeconds));
    }

    /** Non-negative monetary amount that respects the currency scale. */
    protected static Consumer<FieldBuilder> nonNegativeMonetary(String physicalColumn, String ruleCode, String currency, int scale) {
        return f -> f.physicalColumn(physicalColumn)
            .asMonetary(currency, scale)
            .apply(Rules.range(ruleCode, BigDecimal.ZERO, null))
            .apply(Rules.scale(ruleCode + "_SCALE", scale));
    }
}

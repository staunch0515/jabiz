package com.jabiz.entity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Business meaning of a field (docs/design/02-metamodel.md section 1). The kind drives value conversion,
 * validation, the query operators a field accepts and how clients render it.
 *
 * <p>Domain specific kinds (physical quantities, spatial cells ...) are not part of the core: they are
 * {@link Custom} kinds whose behaviour comes from a registered {@link CustomKindSupport}.
 */
public sealed interface SemanticKind {

    /** No declared meaning; transitional only, new definitions should not use it. */
    record None() implements SemanticKind {}

    /** Business identifier, by default immutable and required. */
    record SemanticIdentity(String urn) implements SemanticKind {}

    /** Amount of money; always {@link java.math.BigDecimal}. */
    record Monetary(String currency, int scale) implements SemanticKind {}

    /** Point in time with its role. */
    record Temporal(TemporalRole role) implements SemanticKind {}

    /**
     * Calendar date without a time or a zone ({@link java.time.LocalDate}, column {@code date}): a posting date, a
     * due date, a birthday. Unlike {@link Temporal} it names a day, not a moment, so it reads the same in every zone.
     */
    record Date() implements SemanticKind {}

    /**
     * Dictionary code.
     *
     * @param dictUrn       dictionary the code belongs to
     * @param allowedValues fixed set of codes; empty when the dictionary registry provides them
     */
    record Code(String dictUrn, List<String> allowedValues) implements SemanticKind {
        public Code {
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        }
    }

    /** Optimistic-lock version. */
    record Version() implements SemanticKind {}

    /**
     * Plain text.
     *
     * @param maxLength maximum number of characters, or null when unbounded
     * @param multiline whether clients should offer a multi-line editor
     */
    record Text(Integer maxLength, boolean multiline) implements SemanticKind {
        public Text {
            if (maxLength != null && maxLength <= 0) {
                throw new IllegalArgumentException("maxLength must be positive");
            }
        }
    }

    /** Non-monetary decimal number with at most {@code precision} digits, {@code scale} of them after the point. */
    record Numeric(int precision, int scale) implements SemanticKind {
        public Numeric {
            if (precision <= 0 || scale < 0 || scale > precision) {
                throw new IllegalArgumentException("invalid numeric precision/scale: " + precision + "/" + scale);
            }
        }
    }

    /** Boolean flag. */
    record Bool() implements SemanticKind {}

    /** Primary key of an instance of {@code targetEntity}. */
    record Reference(String targetEntity) implements SemanticKind {
        public Reference {
            if (targetEntity == null || targetEntity.isBlank()) {
                throw new IllegalArgumentException("targetEntity must not be blank");
            }
        }
    }

    /**
     * Extension kind implemented by the {@link CustomKindSupport} registered under {@code kindId}.
     *
     * @param params kind parameters (plain data: strings, numbers, booleans, lists)
     */
    record Custom(String kindId, Map<String, Object> params) implements SemanticKind {
        public Custom {
            if (kindId == null || kindId.isBlank()) {
                throw new IllegalArgumentException("kindId must not be blank");
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            if (params != null) {
                params.forEach((k, v) -> copy.put(k, Objects.requireNonNull(v, "custom kind parameter " + k)));
            }
            params = Collections.unmodifiableMap(copy);
        }
    }
}

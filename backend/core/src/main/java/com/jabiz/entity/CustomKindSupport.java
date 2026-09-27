package com.jabiz.entity;

import com.jabiz.query.QueryOperator;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Behaviour of a {@link SemanticKind.Custom} kind (docs/design/02-metamodel.md section 1.2).
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader} (see {@link CustomKinds}); they must be
 * stateless and synchronous.
 */
public interface CustomKindSupport {

    /** Identifier used by {@link SemanticKind.Custom#kindId()}, for example {@code "geo.h3"}. */
    String kindId();

    /**
     * Converts a raw value to the kind's canonical Java type.
     *
     * @param forInput true for caller input (stricter checks may apply), false for values read from storage
     * @throws IllegalArgumentException if the value cannot be represented
     */
    Object coerce(Map<String, Object> params, Object raw, boolean forInput);

    /** Canonical Java type, also used to bind NULL. */
    Class<?> javaType(Map<String, Object> params);

    /** Query operators the kind supports. */
    Set<QueryOperator> allowedOperators(Map<String, Object> params);

    /** Description for clients; merged into the exported field. */
    Map<String, Object> export(Map<String, Object> params);

    /**
     * Constraints of the kind that a successfully converted input value breaks, most important first; the validator
     * reports the first one, like the constraints of the built-in kinds (docs/design/09-decisions.md D20).
     *
     * @param value the non-null result of {@link #coerce} for input
     */
    default List<KindViolation> validate(Map<String, Object> params, Object value) {
        return List.of();
    }

    /** Codes {@link #validate} may report, so clients get their messages with the entity export. */
    default List<String> violationCodes(Map<String, Object> params) {
        return List.of();
    }
}

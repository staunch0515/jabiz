package com.jabiz.entity;

import com.jabiz.query.QueryOperator;

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
}

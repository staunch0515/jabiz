package com.jabiz.entity;

import java.util.List;
import java.util.Map;

/**
 * Condition a state transition must satisfy beyond being allowed by the lifecycle
 * (docs/design/02-metamodel.md section 4). Guards are synchronous and must not perform I/O: data they need is
 * loaded beforehand and passed in the context.
 */
@FunctionalInterface
public interface TransitionGuard {

    /**
     * @param from     state before the change; null when the entity is being inserted
     * @param to       state after the change
     * @param current  attribute values before the change (empty on insert)
     * @param incoming attribute values supplied by this change
     * @return the rules the transition breaks; empty when it is allowed
     */
    List<Violation> check(String from, String to,
                          Map<String, Object> current, Map<String, Object> incoming,
                          ValidationContext ctx);
}

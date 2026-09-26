package com.jabiz.entity;

import java.util.List;
import java.util.Map;

/**
 * A rule over the whole state of an entity rather than one field (docs/design/02-metamodel.md section 4.1), for
 * example "the value conforms to the kind declared next to it". It is evaluated on every insert and on every update
 * that changes something, against the complete state the write would store, on every write path (dataset API,
 * processes, generic entity processes); its violations are collected with the other business rules of the change
 * (422). Checks are synchronous and must not perform I/O.
 */
@FunctionalInterface
public interface EntityCheck {

    /**
     * @param state the values the write would store, by logical field name (read-only)
     * @return the rules the state breaks; empty when it is acceptable
     */
    List<Violation> check(Map<String, Object> state, ValidationContext ctx);
}

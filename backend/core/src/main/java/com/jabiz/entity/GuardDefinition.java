package com.jabiz.entity;

import java.util.Objects;

/**
 * A {@link TransitionGuard} attached to the transitions {@code from -> to} of an entity's lifecycle.
 *
 * @param code identifies the guard; also the error code reported when the guard itself fails
 * @param from source state, or {@link #ANY} for every source state (including insert)
 * @param to   target state
 */
public record GuardDefinition(String code, String from, String to, TransitionGuard guard) {

    /** Matches every source state, and insertion. */
    public static final String ANY = "*";

    public GuardDefinition {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("guard code must not be blank");
        }
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new IllegalArgumentException("guard " + code + " needs a source and a target state");
        }
        Objects.requireNonNull(guard, "guard must not be null");
    }

    /** True if the guard applies to a change from {@code source} (null on insert) to {@code target}. */
    public boolean appliesTo(String source, String target) {
        return to.equals(target) && (ANY.equals(from) || from.equals(source));
    }
}

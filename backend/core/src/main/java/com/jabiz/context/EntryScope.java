package com.jabiz.context;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The share of the data a session of a scoped sign-in entry works on (decision D64; docs/design/10-security.md section
 * 13.4), for example the operator whose sponsors an operator's member sees: the scope's name and the session's value.
 *
 * @param name  the scope's name, as datasets, approvals and tasks name it
 * @param value the session's value, worked out by the entry's {@code EntryScopeResolver} at sign-in and refresh
 */
public record EntryScope(String name, String value) {

    /** Names of scopes: as dataset fields are named. */
    public static final Pattern NAME = Pattern.compile("[a-z][A-Za-z0-9_.-]{0,63}");

    /** Longest value: an identifier, not data. */
    public static final int MAX_VALUE = 128;

    public EntryScope {
        Objects.requireNonNull(name, "name must not be null");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid scope name: " + name);
        }
        if (value == null || value.isBlank() || value.length() > MAX_VALUE) {
            throw new IllegalArgumentException("Scope " + name + " needs a value of 1 to " + MAX_VALUE + " characters");
        }
    }

    /** Whether this is the scope {@code scope}'s value. */
    public boolean is(String scope) {
        return name.equals(scope);
    }
}

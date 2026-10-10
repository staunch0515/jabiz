package com.jabiz.param;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Business parameters that change only with four eyes (decision D40, docs/design/04-temporal-append-only.md
 * section 9.1), declared by the application as a bean: whether a key is controlled lives in reviewed code, so no
 * single person can lift it at run time. Every write of a controlled key, its creation included, is a controlled
 * change that one person proposes and another publishes ({@code CONTROL_CHANGE_PROPOSE} /
 * {@code CONTROL_CHANGE_PUBLISH}). Several beans may be declared; a key need not exist yet.
 *
 * <p>Malformed keys are not refused here: the startup check reports all of them at once.
 */
public record ControlledParams(Set<String> keys) {

    /** Lower case words of letters and digits, joined by {@code .}, {@code -} or {@code _}. */
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final int MAX_LENGTH = 200;

    public ControlledParams {
        keys.forEach(key -> Objects.requireNonNull(key, "a controlled key must not be null"));
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("a declaration of controlled parameters names at least one key");
        }
        keys = Collections.unmodifiableSet(new LinkedHashSet<>(keys));
    }

    public static ControlledParams of(String... keys) {
        List<String> given = new ArrayList<>(keys.length);
        for (String key : keys) {
            given.add(Objects.requireNonNull(key, "a controlled key must not be null"));
        }
        return new ControlledParams(new LinkedHashSet<>(given));
    }

    public boolean controls(String key) {
        return key != null && keys.contains(key);
    }

    /** Whether {@code key} is written as a declared key must be; the keys of parameters in general are not limited. */
    public static boolean validKey(String key) {
        return key != null && key.length() <= MAX_LENGTH && KEY.matcher(key).matches();
    }

    /** One message per malformed key; empty when all are well formed. */
    public List<String> problems() {
        return keys.stream().filter(key -> !validKey(key))
            .map(key -> "'" + key + "' is not a parameter key (lower case words of letters and digits joined by"
                + " '.', '-' or '_', at most " + MAX_LENGTH + " characters)")
            .toList();
    }
}

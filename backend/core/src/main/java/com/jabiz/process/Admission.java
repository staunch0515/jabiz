package com.jabiz.process;

import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Where a process queues before its transaction (docs/design/06-process.md section 4.1; decision D45): the platform
 * admits at most a few runs of one key at a time per instance, in order of arrival, before they take a database
 * connection; the others wait in the process, not on the database. Declared for the outermost hot named lock of a
 * process, with the same name as its {@code HoldLock}: the lock still keeps the runs apart, the admission only keeps
 * its waiters from holding connections while they wait.
 *
 * <p>The admission key is {@code family + ":" + key(input)}. The family is a constant
 * ({@code [a-z][a-z0-9._-]{0,47}}, checked at startup); it names the queue's settings
 * ({@code jabiz.process.admission.families.<family>}) and is the only part of the key observations show (decision D16).
 * The key is computed from the input alone, before anything is read; null or blank does not queue.
 *
 * @param family the queue's name, shared by every process that takes the same lock
 * @param key    the rest of the key from the input, as text by {@link String#valueOf}; null for none
 * @param <I>    the process input type
 */
public record Admission<I>(String family, Function<I, ?> key) {

    /** The names a family may have. */
    public static final Pattern FAMILY = Pattern.compile("[a-z][a-z0-9._-]{0,47}");

    public Admission {
        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(key, "key must not be null");
    }

    /** Whether {@link #family()} is a valid name (the startup check reports one that is not). */
    public boolean validFamily() {
        return FAMILY.matcher(family).matches();
    }

    /** The admission key of {@code input}, or null when it does not queue (a null or blank key). */
    public String keyOf(I input) {
        Object value = key.apply(input);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : family + ":" + text;
    }
}

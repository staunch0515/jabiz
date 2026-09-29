package com.jabiz.numbering;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A gap-free sequence of document numbers (docs/design/18-numbering-approvals-tasks.md section 2, decision D23),
 * declared as a bean and drawn from with the step {@code AssignNumber} inside the process that needs the number. A
 * number is drawn in the process's transaction: a rolled-back process gives its number back, so the numbers that
 * exist are 1, 2, 3 … without gaps and without duplicates. A scoped sequence counts separately per scope (a fiscal
 * year, a source); an unscoped one has a single count.
 *
 * <pre>{@code
 * NumberSequence.define("fin.journal", s -> s.format("JE-{n:4}").scoped());
 * NumberSequence.define("fin.invoice", s -> s.format("INV-{n}").startAt(1004));
 * }</pre>
 */
public record NumberSequence(String name, NumberFormat format, boolean scoped, long startAt) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9._-]{0,99}");
    /** Scopes are short keys such as {@code 2026} or {@code 2026/MAN}. */
    private static final Pattern SCOPE = Pattern.compile("[A-Za-z0-9._/-]{1,100}");

    /** The scope key of an unscoped sequence. */
    public static final String NO_SCOPE = "";

    public NumberSequence {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(format, "format must not be null");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Sequence name '" + name
                + "' must be 1-100 lowercase letters, digits and . _ - starting with a letter");
        }
        if (startAt < 1) {
            throw new IllegalArgumentException("Sequence " + name + ": startAt must be at least 1, was " + startAt);
        }
        if (format.usesScope() && !scoped) {
            throw new IllegalArgumentException("Sequence " + name + ": format " + format + " uses {scope} but the"
                + " sequence is not scoped");
        }
    }

    public static NumberSequence define(String name, Consumer<Builder> spec) {
        Builder builder = new Builder();
        spec.accept(builder);
        if (builder.format == null) {
            throw new IllegalArgumentException("Sequence " + name + " needs a format");
        }
        return new NumberSequence(name, NumberFormat.parse(builder.format), builder.scoped, builder.startAt);
    }

    /**
     * The scope key a number is drawn in: the given scope of a scoped sequence (required), {@link #NO_SCOPE} for an
     * unscoped one (which takes none).
     */
    public String scopeKey(String scope) {
        if (!scoped) {
            if (scope != null && !scope.isEmpty()) {
                throw new IllegalArgumentException("Sequence " + name + " is not scoped, got scope '" + scope + "'");
            }
            return NO_SCOPE;
        }
        if (scope == null || !SCOPE.matcher(scope).matches()) {
            throw new IllegalArgumentException("Sequence " + name + " needs a scope of 1-100 letters, digits and"
                + " . _ / -, got '" + scope + "'");
        }
        return scope;
    }

    public String format(long value, String scopeKey) {
        return format.format(value, scopeKey);
    }

    /** Declaration of a {@link NumberSequence}. */
    public static final class Builder {
        private String format;
        private boolean scoped;
        private long startAt = 1;

        private Builder() {}

        /** The number's written form, see {@link NumberFormat}. */
        public Builder format(String pattern) {
            this.format = pattern;
            return this;
        }

        /** Counts separately per scope (a fiscal year, a source …), each from {@link #startAt}. */
        public Builder scoped() {
            this.scoped = true;
            return this;
        }

        /** The first number, for books that continue a series (default 1). */
        public Builder startAt(long first) {
            this.startAt = first;
            return this;
        }
    }
}

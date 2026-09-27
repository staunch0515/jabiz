package com.jabiz.process;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The entity a process acts on (docs/design/16-content-authoring.md section 3): {@code input} is the component of
 * the process input that holds the entity's primary key. Clients offer the process as an action on the entity's
 * rows, with that input filled in. {@code whenField} / {@code whenValues} only tell clients when to show the action;
 * they are not a permission or a rule, the server decides as for any other run.
 *
 * @param whenField  field whose value decides whether the action is shown, or null to always show it
 * @param whenValues values of {@code whenField} for which the action is shown; empty when {@code whenField} is null
 */
public record ActsOn(String entity, String input, String whenField, List<String> whenValues) {

    public ActsOn {
        requireText(entity, "entity");
        requireText(input, "input");
        whenValues = whenValues == null ? List.of() : List.copyOf(whenValues);
        if ((whenField == null) != whenValues.isEmpty()) {
            throw new IllegalArgumentException("actsOn " + entity + ": a condition needs a field and at least one value");
        }
    }

    static ActsOn of(String entity, String input, Consumer<Builder> block) {
        Builder builder = new Builder();
        if (block != null) {
            block.accept(builder);
        }
        return new ActsOn(entity, input, builder.field, builder.values);
    }

    private static void requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("actsOn: " + what + " must not be blank");
        }
    }

    /** Condition of an {@link ActsOn}. */
    public static final class Builder {
        private String field;
        private List<String> values = List.of();

        Builder() {}

        /** Shows the action only while {@code field} equals one of {@code values}. */
        public Builder whenField(String field, String... values) {
            this.field = Objects.requireNonNull(field, "field");
            this.values = List.of(values);
            return this;
        }
    }
}

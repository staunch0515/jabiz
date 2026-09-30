package com.jabiz.ledger;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * An analysis dimension of ledger entries (docs/design/11-ledger-events-jobs.md section 1.5; decision D24), declared
 * by the application as a bean: which of the {@value #MAX_POSITION} dimension columns it uses, its name in posting
 * input ({@code {"department": "SALES"}}) and where its valid values come from.
 *
 * <pre>{@code
 * LedgerDimension.define(1, "department", d -> d.dictionary("urn:jabiz:dict:fin:department"))
 * LedgerDimension.define(2, "location", d -> d.entity("FinLocation", "locationCode"))
 * }</pre>
 *
 * @param position the column, 1 to {@value #MAX_POSITION}
 * @param name     the name in posting input; a letter, then letters, digits and {@code _}
 * @param source   the valid values
 */
public record LedgerDimension(int position, String name, Source source) {

    /** Number of dimension columns of ledger entries. */
    public static final int MAX_POSITION = 4;

    /** Longest value of a dimension. */
    public static final int MAX_VALUE_LENGTH = 100;

    public static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,49}");

    /** Where the values of a dimension come from. */
    public sealed interface Source {}

    /** The enabled codes of a dictionary. */
    public record DictionarySource(String dictionaryUrn) implements Source {
        public DictionarySource {
            Objects.requireNonNull(dictionaryUrn, "dictionaryUrn must not be null");
        }
    }

    /** The values of a text field of an entity's current instances (read through its default dataset). */
    public record EntitySource(String entity, String field) implements Source {
        public EntitySource {
            Objects.requireNonNull(entity, "entity must not be null");
            Objects.requireNonNull(field, "field must not be null");
        }
    }

    public LedgerDimension {
        if (position < 1 || position > MAX_POSITION) {
            throw new IllegalArgumentException("Ledger dimension position must be 1 to " + MAX_POSITION + ", not "
                + position);
        }
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Ledger dimension name '" + name + "' must match " + NAME.pattern());
        }
        Objects.requireNonNull(source, "Ledger dimension " + name + " needs a source of values");
    }

    public static LedgerDimension define(int position, String name, Consumer<Builder> spec) {
        Builder builder = new Builder();
        spec.accept(builder);
        return new LedgerDimension(position, name, builder.source);
    }

    /** Name of the entry field that holds this dimension. */
    public String field() {
        return field(position);
    }

    /** Name of the entry field of a position: {@code dimension1} … */
    public static String field(int position) {
        return "dimension" + position;
    }

    public static final class Builder {
        private Source source;

        private Builder() {}

        public Builder dictionary(String dictionaryUrn) {
            source = new DictionarySource(dictionaryUrn);
            return this;
        }

        public Builder entity(String entity, String field) {
            source = new EntitySource(entity, field);
            return this;
        }
    }
}

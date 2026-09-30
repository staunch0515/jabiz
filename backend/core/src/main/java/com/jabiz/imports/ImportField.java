package com.jabiz.imports;

import com.jabiz.entity.SemanticKind;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A value each row of an import carries.
 *
 * @param name     the field's name, used by the mapping and by the code building the process input; its label is
 *                 the message {@code import.<import id>.<name>}
 * @param kind     how the cell is read and checked: text (length), code (allowed values), monetary (scale), numeric
 *                 (precision and scale), temporal (an instant; a date alone is the start of that day in the
 *                 import's zone), boolean, version (an integer)
 * @param required whether a row must have a value
 * @param columns  column names the field is found under when the mapping does not say, compared ignoring case;
 *                 the first one the file has counts
 */
public record ImportField(String name, SemanticKind kind, boolean required, List<String> columns) {

    public static final Pattern NAME = Pattern.compile("[a-z][A-Za-z0-9_]{0,62}");

    public ImportField {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Import field name '" + name + "' must match " + NAME.pattern());
        }
        Objects.requireNonNull(kind, "kind must not be null");
        if (kind instanceof SemanticKind.None || kind instanceof SemanticKind.SemanticIdentity
            || kind instanceof SemanticKind.Custom) {
            throw new IllegalArgumentException("Import field " + name + ": kind " + kind.getClass().getSimpleName()
                + " cannot be read from a file");
        }
        columns = List.copyOf(columns);
    }
}

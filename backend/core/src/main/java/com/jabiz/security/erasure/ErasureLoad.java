package com.jabiz.security.erasure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Data the {@link PersonalDataEraser#notices} of an eraser need, from the rows of {@code dataset} whose
 * {@code userField} equals the user's id (an equality on the user id, as for a sign-in guard: the read stays small and
 * indexed). Three ways to read them (phase 17zz22):
 * <ul>
 *   <li>{@link #of rows}: the rows themselves, at most {@link ErasureSubject#MAX_ROWS} (more is an error, never cut
 *   short), read with {@link ErasureSubject#rows};</li>
 *   <li>{@link #count}: how many there are, read with {@link ErasureSubject#count}, however many;</li>
 *   <li>{@link #sum}: the total of a numeric field, read with {@link ErasureSubject#sum}, however many.</li>
 * </ul>
 * Each may hold {@link #where fixed conditions}: a field equal to one of the values the code gives (constants, never
 * the user's input nor another load's rows), all to hold. The dataset's scope applies, not its permissions.
 *
 * @param name       under which the data reach the eraser ({@link ErasureSubject})
 * @param dataset    resource id of the dataset to read through
 * @param userField  field of the dataset's entity holding the user id
 * @param kind       how the rows are read
 * @param field      the field a {@link Kind#SUM} adds up; null otherwise
 * @param conditions fixed conditions, all to hold
 */
public record ErasureLoad(String name, String dataset, String userField, Kind kind, String field,
    List<Condition> conditions) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

    /** How a load reads its rows. */
    public enum Kind {
        /** The rows, at most {@link ErasureSubject#MAX_ROWS}. */
        ROWS,
        /** How many rows. */
        COUNT,
        /** The total of a numeric field over the rows (0 when there are none). */
        SUM
    }

    /**
     * {@code field} equal to one of {@code values} (one value: an equality; more: {@code IN}). The values are the
     * code's constants, converted to the field's type when read (the startup check refuses one that is not).
     */
    public record Condition(String field, List<Object> values) {
        public Condition {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("A condition names no field");
            }
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("The condition on " + field + " gives no value");
            }
            if (values.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("The condition on " + field + " gives a null value");
            }
            values = List.copyOf(values);
        }
    }

    public ErasureLoad {
        Objects.requireNonNull(name, "name must not be null");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid load name: " + name);
        }
        if (dataset == null || dataset.isBlank()) {
            throw new IllegalArgumentException("Load " + name + " names no dataset");
        }
        if (userField == null || userField.isBlank()) {
            throw new IllegalArgumentException("Load " + name + " names no user field");
        }
        Objects.requireNonNull(kind, "kind must not be null");
        if (kind == Kind.SUM && (field == null || field.isBlank())) {
            throw new IllegalArgumentException("Load " + name + " sums no field");
        }
        if (kind != Kind.SUM && field != null) {
            throw new IllegalArgumentException("Load " + name + " names a field but does not sum it");
        }
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    /** The user's rows, without conditions (as before phase 17zz22). */
    public ErasureLoad(String name, String dataset, String userField) {
        this(name, dataset, userField, Kind.ROWS, null, List.of());
    }

    /** The rows of {@code dataset} whose {@code userField} is the user's id, at most {@link ErasureSubject#MAX_ROWS}. */
    public static ErasureLoad of(String name, String dataset, String userField) {
        return new ErasureLoad(name, dataset, userField);
    }

    /** How many rows of {@code dataset} have the user's id in {@code userField}. */
    public static ErasureLoad count(String name, String dataset, String userField) {
        return new ErasureLoad(name, dataset, userField, Kind.COUNT, null, List.of());
    }

    /** The total of {@code field} (a number or an amount) over the rows whose {@code userField} is the user's id. */
    public static ErasureLoad sum(String name, String dataset, String userField, String field) {
        return new ErasureLoad(name, dataset, userField, Kind.SUM, field, List.of());
    }

    /** This load with {@code field} also equal to one of {@code values}: constants of the code. */
    public ErasureLoad where(String field, Object... values) {
        List<Condition> more = new ArrayList<>(conditions);
        more.add(new Condition(field, values == null ? null : Arrays.asList(values)));
        return new ErasureLoad(name, dataset, userField, kind, this.field, more);
    }
}

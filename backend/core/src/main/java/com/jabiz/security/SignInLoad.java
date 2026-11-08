package com.jabiz.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Data a {@link SignInGuard} or {@link SignUpGuard} needs: the rows of {@code dataset} whose {@code userField} equals
 * the user's id, at most {@link SignInGuard#MAX_ROWS}. Only equalities: a guard decides about one user, and the read
 * stays small and indexed.
 *
 * <p>Decision D80 adds {@link #whereParam conditions on business parameters}: an equality on a field whose value
 * comes from a parameter the guard declares ({@link SignInGuard#params()}), optionally through a pure function (a
 * parameter {@code "<map>/<node>"} split into its parts). With conditions a load may also be {@link #global global}:
 * rows that belong to no user, such as the node a parameter names. A condition whose parameter has no value at the
 * attempt's time (or whose function gives null) leaves its load empty without reading; a function that throws refuses
 * the attempt. Conditions take their values only from parameters and the user's id, never from another load's rows.
 *
 * @param name       under which the rows reach the guard ({@link SignInAttempt#rows})
 * @param dataset    resource id of the dataset to read through (its scope applies, not its permissions)
 * @param userField  field of the dataset's entity holding the user id; null for a {@link #global global} load
 * @param conditions equalities on parameters (decision D80), all to hold
 */
public record SignInLoad(String name, String dataset, String userField, List<Condition> conditions) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

    /**
     * An equality on {@code field} whose value is the parameter {@code paramKey} as in effect at the attempt's time,
     * through {@code value} (the parameter's value as it is when null).
     */
    public record Condition(String field, String paramKey, Function<Object, Object> value) {
        public Condition {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("A condition names no field");
            }
            Objects.requireNonNull(paramKey, "paramKey must not be null");
        }

        /** The value compared with, from the parameter's value; null when there is none. */
        public Object valueOf(Object paramValue) {
            if (paramValue == null) {
                return null;
            }
            return value == null ? paramValue : value.apply(paramValue);
        }
    }

    public SignInLoad {
        Objects.requireNonNull(name, "name must not be null");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid load name: " + name);
        }
        if (dataset == null || dataset.isBlank()) {
            throw new IllegalArgumentException("Load " + name + " names no dataset");
        }
        if (userField != null && userField.isBlank()) {
            throw new IllegalArgumentException("Load " + name + " names a blank user field");
        }
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    /** A load of the user's rows, without conditions (as before decision D80). */
    public SignInLoad(String name, String dataset, String userField) {
        this(name, dataset, requireUserField(name, userField), List.of());
    }

    private static String requireUserField(String name, String userField) {
        if (userField == null || userField.isBlank()) {
            throw new IllegalArgumentException("Load " + name + " names no user field");
        }
        return userField;
    }

    /** The rows of {@code dataset} whose {@code userField} is the user's id. */
    public static SignInLoad of(String name, String dataset, String userField) {
        return new SignInLoad(name, dataset, userField);
    }

    /**
     * Rows of {@code dataset} that belong to no user (decision D80): give at least one {@link #whereParam condition}
     * (the startup check refuses a global load without one: it would read the whole table).
     */
    public static SignInLoad global(String name, String dataset) {
        return new SignInLoad(name, dataset, null, List.of());
    }

    /** This load with {@code field} equal to the parameter {@code paramKey} as it is (decision D80). */
    public SignInLoad whereParam(String field, String paramKey) {
        return whereParam(field, paramKey, null);
    }

    /**
     * This load with {@code field} equal to {@code value} of the parameter {@code paramKey} (decision D80): a pure
     * function, run before the read; null gives no rows.
     */
    public SignInLoad whereParam(String field, String paramKey, Function<Object, Object> value) {
        List<Condition> more = new ArrayList<>(conditions);
        more.add(new Condition(field, paramKey, value));
        return new SignInLoad(name, dataset, userField, more);
    }

    /** Whether the load reads rows that belong to no user (decision D80). */
    public boolean global() {
        return userField == null;
    }

    /** The keys of the parameters its conditions read. */
    public List<String> paramKeys() {
        return conditions.stream().map(Condition::paramKey).distinct().toList();
    }
}

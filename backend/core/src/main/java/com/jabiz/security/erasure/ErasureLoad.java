package com.jabiz.security.erasure;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Data the {@link PersonalDataEraser#notices} of an eraser need: the rows of {@code dataset} whose {@code userField}
 * equals the user's id, at most {@link ErasureSubject#MAX_ROWS} (more is an error). Only an equality on the user id,
 * as for a sign-in guard: the read stays small and indexed.
 *
 * @param name      under which the rows reach the eraser ({@link ErasureSubject#data})
 * @param dataset   resource id of the dataset to read through (its scope applies, not its permissions)
 * @param userField field of the dataset's entity holding the user id
 */
public record ErasureLoad(String name, String dataset, String userField) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

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
    }

    public static ErasureLoad of(String name, String dataset, String userField) {
        return new ErasureLoad(name, dataset, userField);
    }
}

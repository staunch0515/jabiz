package com.jabiz.security;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Data a {@link SignInGuard} needs: the rows of {@code dataset} whose {@code userField} equals the signing-in user's
 * id, at most {@link SignInGuard#MAX_ROWS}. Only an equality on the user id: a guard decides about one user, and the
 * read stays small and indexed.
 *
 * @param name      under which the rows reach the guard ({@link SignInAttempt#rows})
 * @param dataset   resource id of the dataset to read through (its scope applies, not its permissions)
 * @param userField field of the dataset's entity holding the user id
 */
public record SignInLoad(String name, String dataset, String userField) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

    public SignInLoad {
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

    public static SignInLoad of(String name, String dataset, String userField) {
        return new SignInLoad(name, dataset, userField);
    }
}

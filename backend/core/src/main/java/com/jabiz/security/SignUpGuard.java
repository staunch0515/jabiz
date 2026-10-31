package com.jabiz.security;

import java.util.List;

/**
 * An application's check before an account gets the registration roles of a sign-in entry (docs/design/10-security.md
 * section 15.15; decision D66), for example "the account of a blocked customer may not join the portal again".
 * Synchronous and free of I/O like {@link SignInGuard}: the application data it needs is declared by {@link #loads()}
 * (rows of datasets whose field holds the user id) and read by the platform beforehand.
 *
 * <p>Asked on every path that gives an account an entry's registration roles ({@link SignUpAttempt.Path}): a
 * registration, the link of the "you already have an account" mail, a signed-in user joining another entry, and an
 * account opened by a mailed sign-in link, by an identity provider or by another platform's token. A new account has
 * no user id yet: its loads find no rows. All guards must allow; one that throws, or whose data cannot be read,
 * refuses (fail closed). A refusal is answered 403 {@code SIGN_UP_REFUSED} with the guard's reason
 * ({@link SignUpDecision#refuse}), and nothing is written. Without guards everything is allowed.
 */
public interface SignUpGuard {

    /** Most rows one load reads; more is an error, and the guard refuses. */
    int MAX_ROWS = SignInGuard.MAX_ROWS;

    /** The application data the guard reads: rows of datasets whose field holds the user id. None by default. */
    default List<SignInLoad> loads() {
        return List.of();
    }

    /** Whether the account may get the entry's registration roles. */
    SignUpDecision check(SignUpAttempt attempt);
}

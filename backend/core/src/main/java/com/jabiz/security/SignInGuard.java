package com.jabiz.security;

import java.util.List;

/**
 * An application's check before a session is granted or renewed (docs/design/10-security.md section 15; decision D36
 * item 6), for example "a blocked customer may not sign in". Synchronous and free of I/O like every business extension
 * point: the application data it needs is declared by {@link #loads()} and read by the platform beforehand.
 *
 * <p>Called after the password (or identity provider, or second factor) was found right and the roles accepted, before
 * any token is issued, on every sign-in path and at every refresh. All guards must allow; one that throws refuses
 * (fail closed). A refusal is recorded as {@link LoginOutcome#REFUSED}, does not count towards the lock, and is
 * answered 403 {@code SIGN_IN_REFUSED} at sign-in (the caller has proven the password) and 401 at refresh, which also
 * ends the session.
 */
public interface SignInGuard {

    /** Most rows one load reads; more is an error, and the guard refuses (the platform does not cut lists short). */
    int MAX_ROWS = 100;

    /** The application data the guard reads: rows of datasets whose field holds the user id. None by default. */
    default List<SignInLoad> loads() {
        return List.of();
    }

    /** Whether the attempt may go on. */
    SignInDecision check(SignInAttempt attempt);
}

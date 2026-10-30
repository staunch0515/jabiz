package com.jabiz.security;

import java.util.List;

/**
 * Gives a sign-in entry a scope and works out each session's value (decision D64; docs/design/10-security.md section
 * 13.4): the operator an operator's member works for, for example. Synchronous and free of I/O like every business
 * extension point: the data it needs is declared by {@link #loads()} and read by the platform beforehand, as for a
 * {@link SignInGuard}.
 *
 * <p>Called after the guards allowed, at every sign-in and refresh of the entry. A null or blank value refuses the
 * session as a guard's refusal does; one that throws refuses too (fail closed). The value goes into the access token,
 * so a change of the data takes effect at the next refresh. At most one resolver per entry.
 */
public interface EntryScopeResolver {

    /** The sign-in entry whose sessions are scoped. */
    String entry();

    /** The scope's name ({@link com.jabiz.context.EntryScope#NAME}), as datasets, approvals and tasks name it. */
    String scope();

    /** The application data the resolver reads: rows of datasets whose field holds the user id. None by default. */
    default List<SignInLoad> loads() {
        return List.of();
    }

    /** The session's value; null or blank when the user has none, which refuses the session. */
    String resolve(SignInAttempt attempt);
}

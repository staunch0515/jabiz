package com.jabiz.security;

import java.util.Locale;

/**
 * Whether a user's e-mail address is verified (docs/design/10-security.md section 15; decision D36): the address a
 * verification proved ({@code verifiedEmail}) is the current one, regardless of case. Changing the address therefore
 * ends its verification without anyone having to clear a flag.
 */
public final class EmailVerification {

    private EmailVerification() {}

    /** Whether {@code verifiedEmail} proves {@code email}; never for a user without an address. */
    public static boolean verified(Object email, Object verifiedEmail) {
        if (!(email instanceof String current) || current.isBlank() || !(verifiedEmail instanceof String proven)) {
            return false;
        }
        return normalize(current).equals(normalize(proven));
    }

    /** An address as compared and as its uniqueness is checked: lower case, as {@code lower()} in the database. */
    public static String normalize(String email) {
        return email == null ? null : email.toLowerCase(Locale.ROOT);
    }
}

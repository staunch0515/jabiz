package com.jabiz.runtime.process.sponsor;

/**
 * An identity an OpenID Connect provider vouched for (docs/design/10-security.md section 12): its provider and
 * subject, and whether its authentication methods count as a second factor.
 *
 * @param source the entry the sign-in started in (stored with its state, decision D36) and where it comes from
 */
public record SponsorOidcSignInInput(String provider, String subject, boolean secondFactor, SignInSource source) {

    public SponsorOidcSignInInput {
        source = source == null ? SignInSource.NONE : source;
    }

    public SponsorOidcSignInInput(String provider, String subject, boolean secondFactor) {
        this(provider, subject, secondFactor, SignInSource.NONE);
    }
}

package com.jabiz.runtime.process.sponsor;

import com.jabiz.runtime.security.SecurityEntities;

/**
 * An identity a provider vouched for (docs/design/10-security.md section 12): an OpenID Connect provider's, or another
 * platform's signed token (decision D59); its provider and subject, and whether its authentication methods count as a
 * second factor.
 *
 * @param source the entry the sign-in started in (stored with its state, decision D36) and where it comes from
 * @param factor what the login record says proved the user: {@code OIDC} (null) or {@code EXTERNAL_TOKEN}
 * @param refreshToken the provider's refresh token to keep for revoking the link later (Apple, decision D67 item 5);
 *               null for other providers
 */
public record SponsorOidcSignInInput(String provider, String subject, boolean secondFactor, SignInSource source,
    String factor, @com.jabiz.security.Sensitive String refreshToken) {

    public SponsorOidcSignInInput {
        source = source == null ? SignInSource.NONE : source;
        factor = factor == null ? SecurityEntities.FACTOR_OIDC : factor;
        if (!SecurityEntities.FACTOR_OIDC.equals(factor) && !SecurityEntities.FACTOR_EXTERNAL_TOKEN.equals(factor)) {
            throw new IllegalArgumentException("Not a factor of a provider's sign-in: " + factor);
        }
    }

    public SponsorOidcSignInInput(String provider, String subject, boolean secondFactor, SignInSource source,
        String factor) {
        this(provider, subject, secondFactor, source, factor, null);
    }

    public SponsorOidcSignInInput(String provider, String subject, boolean secondFactor, SignInSource source) {
        this(provider, subject, secondFactor, source, null);
    }

    @Override
    public String toString() {
        return "SponsorOidcSignInInput[provider=" + provider + ", subject=" + subject + ", secondFactor="
            + secondFactor + ", source=" + source + ", factor=" + factor + ", refreshToken="
            + (refreshToken == null ? null : "***") + "]";
    }

    public SponsorOidcSignInInput(String provider, String subject, boolean secondFactor) {
        this(provider, subject, secondFactor, SignInSource.NONE);
    }
}

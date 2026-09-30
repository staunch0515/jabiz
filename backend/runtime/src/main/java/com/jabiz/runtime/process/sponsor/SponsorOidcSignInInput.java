package com.jabiz.runtime.process.sponsor;

/**
 * An identity an OpenID Connect provider vouched for (docs/design/10-security.md section 12): its provider and
 * subject, and whether its authentication methods count as a second factor.
 */
public record SponsorOidcSignInInput(String provider, String subject, boolean secondFactor) {}

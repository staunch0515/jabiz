package com.jabiz.runtime.process.sponsor;

import com.jabiz.security.Sensitive;

/**
 * A passkey's answer to a challenge the platform handed out and has taken back (decision D71;
 * docs/design/10-security.md section 15.16).
 *
 * @param credentialId the passkey's credential id the answer names (Base64url)
 * @param assertion    the answer, WebAuthn's JSON: single use, kept out of the operation record
 * @param challenge    the challenge it must answer (Base64url)
 * @param expectedUser the user it must be the passkey of (second step, step-up); null at sign-in
 * @param source       the challenge's entry and where the request comes from
 */
public record SponsorPasskeySignInInput(String credentialId, @Sensitive String assertion, String challenge,
    String expectedUser, SignInSource source) {

    public SponsorPasskeySignInInput {
        source = source == null ? SignInSource.NONE : source;
    }

    @Override
    public String toString() {
        return "SponsorPasskeySignInInput[credentialId=" + credentialId + ", expectedUser=" + expectedUser + ", ***]";
    }
}

package com.jabiz.runtime.process.sponsor;

import com.jabiz.security.Sensitive;

/**
 * A second factor submitted for a user (docs/design/10-security.md section 9): after the password of a sign-in
 * ({@code challengeAttemptNo} is the attempt number of its login record) or for a step-up (null).
 *
 * @param mfaCode a TOTP code or a recovery code; named apart from ordinary "code" properties, since the names of
 *                sensitive components are masked wherever they appear in logged JSON
 * @param source  the entry of the session the code completes (taken from the challenge or the caller's token, never
 *                from the request, decision D36) and where the attempt comes from
 */
public record SponsorMfaVerifyInput(String userId, @Sensitive String mfaCode, Long challengeAttemptNo,
    SignInSource source) {

    public SponsorMfaVerifyInput {
        source = source == null ? SignInSource.NONE : source;
    }

    public SponsorMfaVerifyInput(String userId, String mfaCode, Long challengeAttemptNo) {
        this(userId, mfaCode, challengeAttemptNo, SignInSource.NONE);
    }

    @Override
    public String toString() {
        return "SponsorMfaVerifyInput[userId=" + userId + ", mfaCode=***, challengeAttemptNo=" + challengeAttemptNo
            + ", entry=" + source.entryOrDefault() + "]";
    }
}

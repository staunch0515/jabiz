package com.jabiz.runtime.process.sponsor;

import com.jabiz.security.Sensitive;

/**
 * A second factor submitted for a user (docs/design/10-security.md section 9): after the password of a sign-in
 * ({@code challengeAttemptNo} is the attempt number of its login record) or for a step-up (null).
 *
 * @param mfaCode a TOTP code or a recovery code; named apart from ordinary "code" properties, since the names of
 *                sensitive components are masked wherever they appear in logged JSON
 */
public record SponsorMfaVerifyInput(String userId, @Sensitive String mfaCode, Long challengeAttemptNo) {

    @Override
    public String toString() {
        return "SponsorMfaVerifyInput[userId=" + userId + ", mfaCode=***, challengeAttemptNo=" + challengeAttemptNo + "]";
    }
}

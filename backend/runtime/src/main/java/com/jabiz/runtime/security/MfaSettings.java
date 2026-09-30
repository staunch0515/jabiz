package com.jabiz.runtime.security;

import com.jabiz.security.MfaRequirement;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Settings of the second factor, step-up and idle lock (docs/design/10-security.md sections 9–11; decision D28).
 *
 * @param issuer         name authenticator apps show for the account ({@code jabiz.security.mfa.issuer})
 * @param challengeTtl   lifetime of the challenge between the password and the second factor
 * @param stepUpMaxAge   how recent a second factor must be for operations that require one
 * @param administration whether platform administration requires a second factor
 * @param idleTimeout    how long after its access token expires a session can still be refreshed
 */
public record MfaSettings(String issuer, Duration challengeTtl, Duration stepUpMaxAge, boolean administration,
    Duration idleTimeout) {

    public MfaSettings {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("jabiz.security.mfa.issuer must not be blank");
        }
        requirePositive(challengeTtl, "jabiz.security.mfa.challenge-ttl");
        requirePositive(stepUpMaxAge, "jabiz.security.mfa.step-up-max-age");
        requirePositive(idleTimeout, "jabiz.security.session.idle-timeout");
    }

    /** Whether the requirement applies in this deployment. */
    public boolean applies(MfaRequirement requirement) {
        return requirement.applies(administration);
    }

    /** Whether a second factor passed at {@code mfaAt} is recent enough at {@code now}. */
    public boolean recent(Instant mfaAt, Instant now) {
        return mfaAt != null && !mfaAt.isBefore(now.minus(stepUpMaxAge)) && !mfaAt.isAfter(now.plusSeconds(60));
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

package com.jabiz.runtime.process.sponsor;

import com.jabiz.context.DataPeriod;
import com.jabiz.security.LoginOutcome;

import java.time.Instant;
import java.util.List;

/**
 * Result of a sign-in attempt. Only a {@link LoginOutcome#SUCCESS} carries the user, roles and permissions; the
 * other outcomes are not told apart to the caller (docs/design/10-security.md section 4), except that
 * {@link LoginOutcome#MFA_REQUIRED} and {@link LoginOutcome#MFA_ENROLLMENT_REQUIRED} carry the user and attempt the
 * second step continues (section 9).
 *
 * @param attemptNo attempt number of the login record registered, for the second step
 * @param refusal    why a second-factor attempt left no record ({@code STALE}, {@code NOT_ENROLLED},
 *                   {@code UNREADABLE}), or null
 * @param identityId the provider account a sign-in came through (section 12), or null
 * @param dataFrom   start of the data period of a {@link LoginOutcome#SUCCESS}, or null (section 13.2)
 * @param dataTo     end of that data period (exclusive), or null; both null: not limited in time
 */
public record SponsorSignInOutput(
    LoginOutcome outcome,
    String userId,
    String tenantId,
    List<String> roles,
    List<String> permissions,
    String loginRecordId,
    Long attemptNo,
    String refusal,
    String identityId,
    Instant dataFrom,
    Instant dataTo
) {
    public SponsorSignInOutput(LoginOutcome outcome, String userId, String tenantId, List<String> roles,
        List<String> permissions, String loginRecordId, Long attemptNo, String refusal, String identityId) {
        this(outcome, userId, tenantId, roles, permissions, loginRecordId, attemptNo, refusal, identityId, null, null);
    }

    /** The data period of a successful sign-in, or null when not limited. */
    public DataPeriod dataPeriod() {
        return DataPeriod.of(dataFrom, dataTo);
    }

    public SponsorSignInOutput {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}

package com.jabiz.runtime.process.sponsor;

import com.jabiz.security.LoginOutcome;

import java.util.List;

/**
 * Result of a sign-in attempt. Only a {@link LoginOutcome#SUCCESS} carries the user, roles and permissions; the
 * other outcomes are not told apart to the caller (docs/design/10-security.md section 4).
 */
public record SponsorSignInOutput(
    LoginOutcome outcome,
    String userId,
    String tenantId,
    List<String> roles,
    List<String> permissions,
    String loginRecordId
) {
    public SponsorSignInOutput {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}

package com.jabiz.app.commerce;

import com.jabiz.security.GuardParam;
import com.jabiz.security.SignUpAttempt;
import com.jabiz.security.SignUpDecision;
import com.jabiz.security.SignUpGuard;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A switch that closes the customer portal to new customers (docs/design/10-security.md section 15.17; decision D79):
 * while the business parameter {@value #CLOSED} is {@code true}, no account gets the portal's role, on any path. Unset
 * (the default) or {@code false}, the portal is open. The parameter is read as in effect at the attempt's time, so a
 * closing planned for a date takes effect then by itself.
 */
@Component
class PortalSignUpSwitch implements SignUpGuard {

    /** The parameter (bool): whether the portal takes no new customers. */
    static final String CLOSED = "commerce.portal.sign-up-closed";

    /** The reason the caller reads ({@code SIGN_UP_REFUSED.reason.commerce.sign-up-closed}, messages_*.properties). */
    static final String REASON = "commerce.sign-up-closed";

    @Override
    public List<GuardParam> params() {
        // May be unset: an application that never sets it keeps the portal open.
        return List.of(GuardParam.optional(CLOSED));
    }

    @Override
    public SignUpDecision check(SignUpAttempt attempt) {
        if (!PortalSignInGuard.PORTAL.equals(attempt.entry())) {
            return SignUpDecision.ALLOW;
        }
        return attempt.params().find(CLOSED, Boolean.class).orElse(false)
            ? SignUpDecision.refuse(REASON) : SignUpDecision.ALLOW;
    }
}

package com.jabiz.app.commerce;

import com.jabiz.security.SignInLoad;
import com.jabiz.security.SignUpAttempt;
import com.jabiz.security.SignUpDecision;
import com.jabiz.security.SignUpGuard;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The customer portal's check before an existing account joins it (docs/design/10-security.md section 15.15; decision
 * D66): the account of a blocked customer does not get the portal's role (again) by the "you already have an account"
 * link nor by joining while signed in elsewhere, as {@link PortalSignInGuard} keeps it from signing in there. A new
 * registration has no customer yet: it is not this guard's concern, nor are other entries.
 */
@Component
class PortalSignUpGuard implements SignUpGuard {

    /** The reason the caller reads ({@code SIGN_UP_REFUSED.reason.commerce.customer-blocked}, messages_*.properties). */
    static final String BLOCKED = "commerce.customer-blocked";

    @Override
    public List<SignInLoad> loads() {
        // The same load as the sign-in guard's: one name, one meaning.
        return List.of(SignInLoad.of(PortalSignInGuard.CUSTOMERS, CommerceEntities.CUSTOMER_DATASET, "userId"));
    }

    @Override
    public SignUpDecision check(SignUpAttempt attempt) {
        if (!PortalSignInGuard.PORTAL.equals(attempt.entry()) || !attempt.existingUser()) {
            return SignUpDecision.ALLOW;
        }
        return attempt.rows(PortalSignInGuard.CUSTOMERS).stream().anyMatch(PortalSignInGuard::blocked)
            ? SignUpDecision.refuse(BLOCKED) : SignUpDecision.ALLOW;
    }
}

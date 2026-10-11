package com.jabiz.app.commerce;

import com.jabiz.security.SignInAttempt;
import com.jabiz.security.SignInDecision;
import com.jabiz.security.SignInGuard;
import com.jabiz.security.SignInLoad;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The customer portal's check before a session (docs/design/10-security.md section 15.3; decision D36 item 6): an
 * account whose customer is blocked does not get into the portal, at sign-in and at the next refresh of a session it
 * holds. Other entries (the administration) are not its concern.
 */
@Component
class PortalSignInGuard implements SignInGuard {

    /** The sign-in entry of the portal (application configuration {@code jabiz.security.entries.portal}). */
    static final String PORTAL = "portal";
    static final String CUSTOMERS = "customers";

    @Override
    public List<SignInLoad> loads() {
        return List.of(SignInLoad.of(CUSTOMERS, CommerceEntities.CUSTOMER_DATASET, "userId"));
    }

    @Override
    public SignInDecision check(SignInAttempt attempt) {
        if (!PORTAL.equals(attempt.entry())) {
            return SignInDecision.ALLOW;
        }
        return attempt.rows(CUSTOMERS).stream().anyMatch(PortalSignInGuard::blocked)
            ? SignInDecision.refuse("customer blocked") : SignInDecision.ALLOW;
    }

    static boolean blocked(Map<String, Object> customer) {
        return Boolean.TRUE.equals(customer.get("blocked"));
    }
}

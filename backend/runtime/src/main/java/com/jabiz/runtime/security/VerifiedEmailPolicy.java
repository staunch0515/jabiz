package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;

/**
 * Verified-address checks of the entry points (docs/design/10-security.md section 15; decision D36 item 3), next to
 * {@link Permissions} and {@link MfaPolicy}: an operation declaring {@code requiresVerifiedEmail()} needs a session
 * whose access token says the address is verified. Nested processes, scenario replays, jobs and event consumers are
 * not entry points and are not checked, just as with permissions; nor is the system actor. Stateless: the token's
 * claim is all it reads.
 */
public final class VerifiedEmailPolicy {

    private VerifiedEmailPolicy() {}

    /**
     * @param required whether the operation declares the requirement
     * @throws EmailNotVerifiedException when it does and the caller's address is not verified
     */
    public static void require(RequestContext context, boolean required, String what) {
        if (!required || RequestContext.SYSTEM_ACTOR.equals(context.actorId())) {
            return;
        }
        if (!context.emailVerified()) {
            throw new EmailNotVerifiedException(what + " requires a verified e-mail address");
        }
    }
}

package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.security.MfaRequirement;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Step-up checks of the entry points (docs/design/10-security.md section 10; decision D28 item 4), next to
 * {@link Permissions}: an operation that requires a second factor needs one passed within
 * {@code jabiz.security.mfa.step-up-max-age}. Nested processes, scenario replays, jobs and event consumers are not
 * entry points and are not checked, just as with permissions.
 */
@Component
public class MfaPolicy {

    private final MfaSettings settings;
    private final Clock clock;

    public MfaPolicy(MfaSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /** Whether the requirement applies in this deployment (catalogs show it to clients). */
    public boolean applies(MfaRequirement requirement) {
        return settings.applies(requirement);
    }

    /**
     * @throws MfaRequiredException when the requirement applies and the caller's second factor is missing or too old
     */
    public void require(RequestContext context, MfaRequirement requirement, String what) {
        if (!settings.applies(requirement) || RequestContext.SYSTEM_ACTOR.equals(context.actorId())) {
            return;
        }
        if (!settings.recent(context.mfaAt(), clock.instant())) {
            throw new MfaRequiredException(what + " requires a recent second factor");
        }
    }
}

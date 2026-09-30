package com.jabiz.runtime.security;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Startup check of the session settings (docs/design/10-security.md section 11): an access token must not outlive the
 * idle timeout, or an idle session would stay usable for as long as its access token.
 */
@Component
class SessionChecks implements PlatformCheck {

    static final String CATEGORY = "SECURITY";

    private final JwtService tokens;
    private final MfaSettings settings;

    SessionChecks(JwtService tokens, MfaSettings settings) {
        this.tokens = tokens;
        this.settings = settings;
    }

    @Override
    public List<CheckProblem> check() {
        if (tokens.ttl().compareTo(settings.idleTimeout()) > 0) {
            return List.of(CheckProblem.error(CATEGORY, "jabiz.security.jwt.access-token-ttl",
                "the access token lifetime " + tokens.ttl() + " is longer than jabiz.security.session.idle-timeout "
                    + settings.idleTimeout()));
        }
        return List.of();
    }
}

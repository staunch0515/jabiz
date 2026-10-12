package com.jabiz.runtime.security;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Startup check of the session settings (docs/design/10-security.md section 11; decision D43 item 2): an access token
 * must not outlive the idle timeout, or an idle session would stay usable for as long as its access token. Checked for
 * the global setting and for every sign-in entry whose sessions end when idle (an idle timeout of 0 ends none); every
 * problem is reported at once.
 */
@Component
class SessionChecks implements PlatformCheck {

    static final String CATEGORY = "SECURITY";

    private final JwtService tokens;
    private final MfaSettings settings;
    private final SignInEntries entries;

    SessionChecks(JwtService tokens, MfaSettings settings, SignInEntries entries) {
        this.tokens = tokens;
        this.settings = settings;
        this.entries = entries;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        if (tokens.ttl().compareTo(settings.idleTimeout()) > 0) {
            problems.add(CheckProblem.error(CATEGORY, "jabiz.security.jwt.access-token-ttl",
                "the access token lifetime " + tokens.ttl() + " is longer than jabiz.security.session.idle-timeout "
                    + settings.idleTimeout()));
        }
        if (!entries.defaults().refreshTokenTtl().isPositive()) {
            problems.add(CheckProblem.error(CATEGORY, "jabiz.security.refresh-token-ttl",
                "the refresh token lifetime must be positive, not " + entries.defaults().refreshTokenTtl()));
        }
        for (SignInEntries.Entry entry : entries.all()) {
            Duration idle = entry.session().idleTimeout();
            // Unset, the entry has the global idle timeout, reported above already.
            if (entry.session().idleEnds() && !idle.equals(settings.idleTimeout())
                && tokens.ttl().compareTo(idle) > 0) {
                problems.add(CheckProblem.error(CATEGORY, SignInEntries.PROPERTY + "." + entry.name()
                    + ".session.idle-timeout", "the access token lifetime " + tokens.ttl() + " is longer than the "
                    + "idle timeout " + idle + " of entry " + entry.name()));
            }
        }
        return problems;
    }
}

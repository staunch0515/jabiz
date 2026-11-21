package com.jabiz.quizbuks.identity;

import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.security.SignInEntries;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The sign-in entries keep a session for as long as they say without activity (24 hours by default,
 * {@code QB_SESSION_IDLE_TIMEOUT}; the requester, 2026-11-21): an idle session can be refreshed until its access token
 * plus the idle timeout have passed, but its refresh token is only good for the entry's {@code refresh-token-ttl} from
 * the last refresh. A shorter refresh token ends the session sooner than the entry says, so it is reported here
 * (category {@value CountryCatalog#CATEGORY}, a warning: the session is only shorter, never longer). Entries that never
 * lock for idleness (the app) are not concerned.
 */
@Component
public class SessionLengthCheck implements PlatformCheck {

    /** An entry's refresh token lifetime and idle timeout (0: no idle end). */
    record Entry(String name, Duration refreshTokenTtl, Duration idleTimeout) {}

    private final SignInEntries entries;
    private final JwtService tokens;

    public SessionLengthCheck(SignInEntries entries, JwtService tokens) {
        this.entries = entries;
        this.tokens = tokens;
    }

    @Override
    public List<CheckProblem> check() {
        return problems(tokens.ttl(), entries.all().stream()
            .map(entry -> new Entry(entry.name(), entry.session().refreshTokenTtl(), entry.session().idleTimeout()))
            .toList());
    }

    static List<CheckProblem> problems(Duration accessTokenTtl, List<Entry> entries) {
        List<CheckProblem> problems = new ArrayList<>();
        for (Entry entry : entries) {
            if (!entry.idleTimeout().isPositive()) {
                continue;
            }
            Duration window = accessTokenTtl.plus(entry.idleTimeout());
            if (entry.refreshTokenTtl().compareTo(window) < 0) {
                problems.add(CheckProblem.warning(CountryCatalog.CATEGORY, "jabiz.security.entries." + entry.name()
                    + ".session.refresh-token-ttl", "the refresh token lives " + entry.refreshTokenTtl()
                    + ", less than the access token plus the idle timeout " + entry.idleTimeout() + " (" + window
                    + "): an idle session ends sooner than the idle timeout says. Set QB_SESSION_REFRESH_TOKEN_TTL to "
                    + "at least " + window));
            }
        }
        return List.copyOf(problems);
    }
}

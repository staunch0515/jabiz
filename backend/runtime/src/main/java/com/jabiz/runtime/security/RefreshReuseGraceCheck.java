package com.jabiz.runtime.security;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Startup check of the refresh token reuse grace (decision D73): between 0 (no grace) and
 * {@link RefreshTokenStore#MAX_REUSE_GRACE}. A longer grace would let a stolen, consumed token buy a session for longer
 * than a lost answer to a refresh can explain.
 */
@Component
class RefreshReuseGraceCheck implements PlatformCheck {

    static final String PROPERTY = "jabiz.security.refresh.reuse-grace";
    static final String DEFAULT = "30s";

    private final Duration grace;

    RefreshReuseGraceCheck(@Value("${" + PROPERTY + ":" + DEFAULT + "}") Duration grace) {
        this.grace = grace;
    }

    @Override
    public List<CheckProblem> check() {
        return problems(grace);
    }

    static List<CheckProblem> problems(Duration grace) {
        if (grace.isNegative() || grace.compareTo(RefreshTokenStore.MAX_REUSE_GRACE) > 0) {
            return List.of(CheckProblem.error(SessionChecks.CATEGORY, PROPERTY, "the refresh token reuse grace must be "
                + "between 0 and " + RefreshTokenStore.MAX_REUSE_GRACE + ", not " + grace));
        }
        return List.of();
    }
}

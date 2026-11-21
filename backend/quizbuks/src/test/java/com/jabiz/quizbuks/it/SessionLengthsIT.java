package com.jabiz.quizbuks.it;

import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.security.SignInEntries;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every signed-in front end stays signed in for 24 hours without activity (the requester, 2026-11-21: "长时间未操作的
 * 需要登录的时间全部设置为24小时"; docs/quizbuks/plans/QP-pages.md section 2): the administration, the sponsor console,
 * the operators' back office and the Shopify pages lock after 24 hours idle, and their refresh tokens live long enough
 * for that (a refresh token that expired first would end the session sooner). The app still never locks for idleness.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class SessionLengthsIT extends PostgresIntegrationTest {

    private static final Duration DAY = Duration.ofHours(24);

    @Autowired
    SignInEntries entries;

    @Autowired
    JwtService tokens;

    @Test
    void theSignedInFrontEndsLockAfterADayIdleAndTheirRefreshTokensLastThatLong() {
        for (String entry : List.of("admin", "sponsor", "operator", "shopify")) {
            SignInEntries.Session session = entries.session(entry);
            assertThat(session.idleTimeout()).as("%s idle timeout", entry).isEqualTo(DAY);
            assertThat(session.refreshTokenTtl()).as("%s refresh token", entry)
                .isGreaterThanOrEqualTo(tokens.ttl().plus(DAY));
        }
    }

    @Test
    void theAppStillNeverLocksForIdleness() {
        SignInEntries.Session app = entries.session("app");
        assertThat(app.idleEnds()).isFalse();
        assertThat(app.refreshTokenTtl()).isEqualTo(Duration.ofDays(30));
    }
}

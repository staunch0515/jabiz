package com.jabiz.quizbuks.identity;

import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A refresh token that expires before the idle timeout ends the session sooner than the entry says. */
class SessionLengthCheckTest {

    private static final Duration ACCESS = Duration.ofMinutes(15);

    @Test
    void aRefreshTokenShorterThanTheIdleWindowIsReported() {
        List<CheckProblem> problems = SessionLengthCheck.problems(ACCESS, List.of(
            new SessionLengthCheck.Entry("sponsor", Duration.ofHours(8), Duration.ofHours(24))));
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.location()).contains("sponsor");
            assertThat(problem.message()).contains("PT8H").contains("PT24H").contains("QB_SESSION_REFRESH_TOKEN_TTL");
        });
    }

    @Test
    void aLongEnoughRefreshTokenOrNoIdleEndIsFine() {
        assertThat(SessionLengthCheck.problems(ACCESS, List.of(
            new SessionLengthCheck.Entry("admin", Duration.ofHours(48), Duration.ofHours(24)),
            new SessionLengthCheck.Entry("edge", Duration.ofHours(24).plus(ACCESS), Duration.ofHours(24)),
            new SessionLengthCheck.Entry("app", Duration.ofDays(30), Duration.ZERO)))).isEmpty();
    }
}

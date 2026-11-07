package com.jabiz.runtime.security;

import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshReuseGraceCheckTest {

    @Test
    void acceptsZeroToTwoMinutes() {
        assertThat(RefreshReuseGraceCheck.problems(Duration.ZERO)).isEmpty();
        assertThat(RefreshReuseGraceCheck.problems(Duration.ofSeconds(30))).isEmpty();
        assertThat(RefreshReuseGraceCheck.problems(Duration.ofSeconds(120))).isEmpty();
        assertThat(new RefreshReuseGraceCheck(Duration.ofSeconds(30)).check()).isEmpty();
    }

    @Test
    void refusesANegativeOrLongerGrace() {
        assertThat(RefreshReuseGraceCheck.problems(Duration.ofSeconds(121))).singleElement()
            .satisfies(problem -> {
                assertThat(problem.location()).isEqualTo("jabiz.security.refresh.reuse-grace");
                assertThat(problem.category()).isEqualTo("SECURITY");
            });
        assertThat(new RefreshReuseGraceCheck(Duration.ofSeconds(-1)).check()).extracting(CheckProblem::location)
            .containsExactly("jabiz.security.refresh.reuse-grace");
    }
}

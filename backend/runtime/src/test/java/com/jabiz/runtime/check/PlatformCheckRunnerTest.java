package com.jabiz.runtime.check;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformCheckRunnerTest {

    private static PlatformCheckRunner runner(MockEnvironment environment, PlatformCheck... checks) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int i = 0; i < checks.length; i++) {
            beans.addBean("check" + i, checks[i]);
        }
        return new PlatformCheckRunner(beans.getBeanProvider(PlatformCheck.class), environment);
    }

    private static final PlatformCheck FIRST = () -> List.of(CheckProblem.error("A", "a.sql:1", "broken"),
        CheckProblem.warning("A", "a.sql:2", "odd"));
    private static final PlatformCheck SECOND = () -> List.of(CheckProblem.error("B", "Entity X: field y missing"));
    private static final PlatformCheck FAILING = () -> {
        throw new IllegalStateException("no database");
    };

    @Test
    void everyErrorOfEveryCheckIsReportedAtOnce() {
        assertThatThrownBy(() -> runner(new MockEnvironment(), FIRST, SECOND, FAILING).afterSingletonsInstantiated())
            .isInstanceOfSatisfying(PlatformCheckFailedException.class, e -> {
                assertThat(e.problems()).extracting(CheckProblem::format).containsExactly(
                    "A | a.sql:1 | broken",
                    "B | Entity X | field y missing",
                    "CHECK | " + FAILING.getClass().getSimpleName() + " | check could not run: no database");
                assertThat(e).hasMessageStartingWith("Platform check failed (3 problems):\n - A | a.sql:1 | broken");
            });
    }

    @Test
    void warningsDoNotStopTheStart() {
        PlatformCheck warnOnly = () -> List.of(CheckProblem.warning("A", "-", "odd"));

        assertThatCode(() -> runner(new MockEnvironment(), warnOnly).afterSingletonsInstantiated())
            .doesNotThrowAnyException();
        assertThat(runner(new MockEnvironment(), warnOnly).runAll()).extracting(CheckProblem::format)
            .containsExactly("WARNING A | - | odd");
    }

    @Test
    void theStartupRunCanBeTurnedOff() {
        MockEnvironment off = new MockEnvironment().withProperty(PlatformCheckRunner.ON_STARTUP_PROPERTY, "false");

        assertThatCode(() -> runner(off, FIRST).afterSingletonsInstantiated()).doesNotThrowAnyException();
        assertThat(runner(off, FIRST).runAll()).hasSize(2);
    }

    @Test
    void problemTextsAreSplitIntoLocationAndMessage() {
        assertThat(CheckProblem.error("C", "plain text").format()).isEqualTo("C | - | plain text");
        assertThat(CheckProblem.error("C", "Entity X (temporal) -> no index: on y").format())
            .isEqualTo("C | Entity X (temporal) | no index: on y");
        assertThat(CheckProblem.error("C", " ", "m").location()).isEqualTo("-");
        assertThatThrownBy(() -> PlatformCheckRunner.verify(SECOND)).hasMessageContaining("(1 problem)");
    }
}

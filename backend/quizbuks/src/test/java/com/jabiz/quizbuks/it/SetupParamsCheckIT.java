package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.SetupParamsCheck;
import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup check of the business parameters (docs/quizbuks/plans/Q26-param-check.md): a database without any of
 * them is reported once; after {@code QB_SETUP} only the controlled parameters, whose proposals wait for another
 * administrator, are named; once published, nothing. Warnings only: the application starts either way.
 */
class SetupParamsCheckIT extends QbItSupport {

    @Autowired
    SetupParamsCheck check;

    @Test
    void namesTheParametersWithoutAValueUntilTheSetupRanAndItsProposalsWerePublished() {
        // A fresh schema: the setup has not run.
        List<CheckProblem> before = check.check();
        assertThat(before).singleElement().satisfies(problem -> {
            assertThat(problem.isError()).isFalse();
            assertThat(problem.location()).isEqualTo("QB_SETUP");
            assertThat(problem.message()).contains("QB_SETUP has not run yet");
        });

        // Run: the plain parameters exist; the controlled ones are proposals until published.
        Map<String, Object> setupOutput = setup();
        List<CheckProblem> proposed = check.check();
        assertThat(proposed).allSatisfy(problem -> assertThat(problem.isError()).isFalse());
        assertThat(proposed).extracting(CheckProblem::location)
            .containsExactlyInAnyOrderElementsOf(QbParams.CONTROLLED.stream().map(key -> "parameter " + key).toList());
        assertThat(proposed).extracting(CheckProblem::message)
            .allSatisfy(message -> assertThat(message).contains("publish its proposal"));

        publishProposals(setupOutput);
        assertThat(check.check()).isEmpty();
    }
}

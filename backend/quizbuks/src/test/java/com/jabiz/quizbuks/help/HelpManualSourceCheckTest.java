package com.jabiz.quizbuks.help;

import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** F-C10b: a repository manual not loaded into its draft, or no manual at all, is a startup warning. */
class HelpManualSourceCheckTest {

    @Test
    void aManualNotLoadedIntoItsDraftIsAWarning() {
        assertThat(HelpManualSourceCheck.problems(Map.of("app", "h1", "sponsor", "h2"),
            Map.of("app", "h1", "sponsor", "h2"))).isEmpty();
        assertThat(HelpManualSourceCheck.problems(Map.of("app", "h1"), Map.of("app", "new", "sponsor", "h2")))
            .hasSize(2).allSatisfy(problem -> assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.WARNING))
            .anySatisfy(problem -> assertThat(problem.message()).contains("QB_HELP_MANUAL_LOAD", "QB_HELP_PUBLISH"))
            .anySatisfy(problem -> assertThat(problem.message()).contains("QB_SETUP"));
    }
}

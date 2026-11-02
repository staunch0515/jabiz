package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.play.PrerequisiteCheck.Attempt;
import com.jabiz.quizbuks.play.PrerequisiteCheck.Prerequisite;
import com.jabiz.quizbuks.publishing.QbPrerequisites;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Q76 (D-Q76-2): what meets a prerequisite. */
class PrerequisiteCheckTest {

    @Test
    void answeredTakesAFinishedAttemptPassedOrNot() {
        List<Prerequisite> answered = List.of(new Prerequisite("a", QbPrerequisites.ANSWERED));
        assertThat(PrerequisiteCheck.unmet(answered, List.of(new Attempt("a", AttemptStatus.SUBMITTED, false))))
            .isEmpty();
        assertThat(PrerequisiteCheck.unmet(answered, List.of(new Attempt("a", AttemptStatus.EXPIRED, null))))
            .isEmpty();
        // In progress is not finished.
        assertThat(PrerequisiteCheck.unmet(answered, List.of(new Attempt("a", AttemptStatus.IN_PROGRESS, null))))
            .containsExactly("a");
    }

    @Test
    void passedTakesAPassedOne() {
        List<Prerequisite> passed = List.of(new Prerequisite("a", QbPrerequisites.PASSED));
        assertThat(PrerequisiteCheck.unmet(passed, List.of(new Attempt("a", AttemptStatus.SUBMITTED, false))))
            .containsExactly("a");
        assertThat(PrerequisiteCheck.unmet(passed, List.of(new Attempt("a", AttemptStatus.SUBMITTED, true))))
            .isEmpty();
    }

    @Test
    void everyOneMustBeMet() {
        List<Prerequisite> two = List.of(new Prerequisite("a", QbPrerequisites.ANSWERED),
            new Prerequisite("b", QbPrerequisites.PASSED));
        assertThat(PrerequisiteCheck.unmet(two, List.of(new Attempt("a", AttemptStatus.SUBMITTED, false))))
            .containsExactly("b");
        assertThat(PrerequisiteCheck.unmet(two, List.of(new Attempt("a", AttemptStatus.SUBMITTED, false),
            new Attempt("b", AttemptStatus.SUBMITTED, true)))).isEmpty();
        assertThat(PrerequisiteCheck.unmet(List.of(), List.of())).isEmpty();
    }
}

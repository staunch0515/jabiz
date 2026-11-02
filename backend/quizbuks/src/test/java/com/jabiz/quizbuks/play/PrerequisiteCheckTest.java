package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.play.PrerequisiteCheck.Attempt;
import com.jabiz.quizbuks.play.PrerequisiteCheck.Prerequisite;
import com.jabiz.quizbuks.publishing.QbPrerequisites;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PrerequisiteCheckTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final Prerequisite PASS_A = new Prerequisite(A, QbPrerequisites.PASSED);
    private static final Prerequisite ANSWER_A = new Prerequisite(A, QbPrerequisites.ANSWERED);
    private static final Prerequisite PASS_B = new Prerequisite(B, QbPrerequisites.PASSED);

    @Test
    void noPrerequisiteIsAlwaysMet() {
        assertThat(PrerequisiteCheck.unmet(List.of(), List.of())).isEmpty();
    }

    @Test
    void aPassNeedsAFinishedPassedAttempt() {
        assertThat(PrerequisiteCheck.unmet(List.of(PASS_A), List.of())).containsExactly(PASS_A);
        assertThat(PrerequisiteCheck.unmet(List.of(PASS_A), List.of(new Attempt(A, AttemptStatus.SUBMITTED, false))))
            .containsExactly(PASS_A);
        assertThat(PrerequisiteCheck.unmet(List.of(PASS_A), List.of(new Attempt(A, AttemptStatus.SUBMITTED, true))))
            .isEmpty();
        // Finished by its time running out: counts the same as submitted.
        assertThat(PrerequisiteCheck.unmet(List.of(PASS_A), List.of(new Attempt(A, AttemptStatus.EXPIRED, true))))
            .isEmpty();
    }

    @Test
    void anAnswerNeedsAFinishedAttemptWhateverTheOutcome() {
        assertThat(PrerequisiteCheck.unmet(List.of(ANSWER_A), List.of(new Attempt(A, AttemptStatus.SUBMITTED, false))))
            .isEmpty();
        assertThat(PrerequisiteCheck.unmet(List.of(ANSWER_A), List.of(new Attempt(A, AttemptStatus.EXPIRED, null))))
            .isEmpty();
    }

    @Test
    void anAttemptInProgressDoesNotCount() {
        assertThat(PrerequisiteCheck.unmet(List.of(ANSWER_A), List.of(new Attempt(A, AttemptStatus.IN_PROGRESS, null))))
            .containsExactly(ANSWER_A);
    }

    @Test
    void everyPrerequisiteMustBeMetAndTheUnmetKeepTheirOrder() {
        List<Attempt> attempts = List.of(new Attempt(B, AttemptStatus.SUBMITTED, true),
            new Attempt(UUID.randomUUID(), AttemptStatus.SUBMITTED, true));
        assertThat(PrerequisiteCheck.unmet(List.of(PASS_A, PASS_B), attempts)).containsExactly(PASS_A);
        assertThat(PrerequisiteCheck.unmet(List.of(PASS_B, PASS_A), attempts)).containsExactly(PASS_A);
        assertThat(PrerequisiteCheck.unmet(List.of(PASS_B), attempts)).isEmpty();
    }
}

package com.jabiz.quizbuks.tree;

import com.jabiz.quizbuks.play.PrerequisiteCheck.Attempt;
import com.jabiz.quizbuks.play.PrerequisiteCheck.Prerequisite;
import com.jabiz.quizbuks.publishing.QbPrerequisites;
import com.jabiz.quizbuks.tree.TreeStates.State;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TreeStatesTest {

    private static final UUID BASICS = UUID.randomUUID();
    private static final UUID ADVANCED = UUID.randomUUID();

    private static final List<Prerequisite> PASSED_BASICS = List.of(new Prerequisite(BASICS, QbPrerequisites.PASSED));
    private static final List<Prerequisite> ANSWERED_BASICS = List.of(new Prerequisite(BASICS,
        QbPrerequisites.ANSWERED));

    @Test
    void anAttemptOnTheNodeDecidesFirstAPassOverAFailureOverOneUnderWay() {
        assertThat(TreeStates.of(BASICS, true, List.of(), List.of(new Attempt(BASICS, "SUBMITTED", true))))
            .isEqualTo(State.LIT);
        assertThat(TreeStates.of(BASICS, true, List.of(), List.of(new Attempt(BASICS, "EXPIRED", false))))
            .isEqualTo(State.ANSWERED);
        assertThat(TreeStates.of(BASICS, true, List.of(), List.of(new Attempt(BASICS, "IN_PROGRESS", null))))
            .isEqualTo(State.IN_PROGRESS);
        // A pass wins however the attempts are ordered; a failure beside one under way is answered.
        assertThat(TreeStates.of(BASICS, false, List.of(), List.of(new Attempt(BASICS, "IN_PROGRESS", null),
            new Attempt(BASICS, "SUBMITTED", true)))).isEqualTo(State.LIT);
        assertThat(TreeStates.of(BASICS, false, List.of(), List.of(new Attempt(BASICS, "IN_PROGRESS", null),
            new Attempt(BASICS, "SUBMITTED", false)))).isEqualTo(State.ANSWERED);
        // Even when the publication closed since: learnt once is learnt.
        assertThat(TreeStates.of(BASICS, false, List.of(), List.of(new Attempt(BASICS, "SUBMITTED", true))))
            .isEqualTo(State.LIT);
    }

    @Test
    void withoutAnAttemptThePrerequisitesThenTheOpeningDecide() {
        assertThat(TreeStates.of(ADVANCED, true, List.of(), List.of())).isEqualTo(State.UNLOCKED);
        assertThat(TreeStates.of(ADVANCED, false, List.of(), List.of())).isEqualTo(State.CLOSED);
        assertThat(TreeStates.of(ADVANCED, true, PASSED_BASICS, List.of())).isEqualTo(State.LOCKED);
        // Locked wins over closed: the person has something to do first either way.
        assertThat(TreeStates.of(ADVANCED, false, PASSED_BASICS, List.of())).isEqualTo(State.LOCKED);
        assertThat(TreeStates.of(ADVANCED, true, PASSED_BASICS, List.of(new Attempt(BASICS, "SUBMITTED", true))))
            .isEqualTo(State.UNLOCKED);
    }

    @Test
    void aFailureMeetsAnAnsweredRequirementButNotAPassedOne() {
        List<Attempt> failed = List.of(new Attempt(BASICS, "SUBMITTED", false));
        assertThat(TreeStates.of(ADVANCED, true, PASSED_BASICS, failed)).isEqualTo(State.LOCKED);
        assertThat(TreeStates.of(ADVANCED, true, ANSWERED_BASICS, failed)).isEqualTo(State.UNLOCKED);
        // Under way does not count for either.
        List<Attempt> underWay = List.of(new Attempt(BASICS, "IN_PROGRESS", null));
        assertThat(TreeStates.of(ADVANCED, true, ANSWERED_BASICS, underWay)).isEqualTo(State.LOCKED);
    }

    @Test
    void attemptsOnOtherNodesNeverLightThisOne() {
        assertThat(TreeStates.of(ADVANCED, true, List.of(), List.of(new Attempt(BASICS, "SUBMITTED", true))))
            .isEqualTo(State.UNLOCKED);
    }
}

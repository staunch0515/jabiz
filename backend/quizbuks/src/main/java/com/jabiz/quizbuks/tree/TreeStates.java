package com.jabiz.quizbuks.tree;

import com.jabiz.quizbuks.play.AttemptStatus;
import com.jabiz.quizbuks.play.PrerequisiteCheck;

import java.util.List;
import java.util.UUID;

/**
 * Where one person stands on one node of a sponsor's tree (docs/quizbuks/plans/Q85-tech-tree.md, D-Q85-2): the
 * node is a publication, the edges are its prerequisites (Q76), and the person's attempts decide. The one definition;
 * the templates {@code qb.sponsor.tree}, {@code qb.sponsor.tree-people}, {@code qb.sponsor.tree-person} and
 * {@code qb.app.sponsor-tree} write the same in SQL ({@code TreeIT} compares them). Nothing is stored: the tree is
 * read, never written.
 */
public final class TreeStates {

    /** The states, in the order they win: the first that holds is the person's. */
    public enum State {
        /** A finished attempt that passed: the node is theirs. */
        LIT,
        /** A finished attempt that did not pass: half lit (it meets a downstream {@code ANSWERED} requirement). */
        ANSWERED,
        /** An attempt under way. */
        IN_PROGRESS,
        /** Nothing started, every prerequisite met, and the publication open now. */
        UNLOCKED,
        /** Nothing started and a prerequisite not met. */
        LOCKED,
        /** Nothing started, prerequisites met, but the publication is not open (ended, paused, not yet started). */
        CLOSED
    }

    private TreeStates() {}

    /**
     * @param node          the publication the state is for
     * @param live          whether it accepts attempts now (LIVE, between its start and end)
     * @param prerequisites what it requires first
     * @param attempts      every attempt of the person on this sponsor's publications (this node's among them)
     */
    public static State of(UUID node, boolean live, List<PrerequisiteCheck.Prerequisite> prerequisites,
        List<PrerequisiteCheck.Attempt> attempts) {
        boolean inProgress = false;
        boolean answered = false;
        for (PrerequisiteCheck.Attempt attempt : attempts) {
            if (!node.equals(attempt.publicationId())) {
                continue;
            }
            if (AttemptStatus.finished(attempt.status())) {
                if (Boolean.TRUE.equals(attempt.passed())) {
                    return State.LIT;
                }
                answered = true;
            } else {
                inProgress = true;
            }
        }
        if (answered) {
            return State.ANSWERED;
        }
        if (inProgress) {
            return State.IN_PROGRESS;
        }
        if (!PrerequisiteCheck.unmet(prerequisites, attempts).isEmpty()) {
            return State.LOCKED;
        }
        return live ? State.UNLOCKED : State.CLOSED;
    }
}

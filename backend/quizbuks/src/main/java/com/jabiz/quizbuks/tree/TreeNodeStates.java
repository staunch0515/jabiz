package com.jabiz.quizbuks.tree;

import java.util.List;
import java.util.Set;

/**
 * What the graph component shows of a node to a person (F-S3, docs/quizbuks/plans/impl/F-S3.md §2): the spec's five
 * states, finer than {@link TreeLights.State} (lit = {@code LIT}, prerequisites not lit = {@code LOCKED}, the other
 * three split "open to be lit"). The facts come from the templates ({@code qb.app.tree}; whether the person can start a
 * publication bound to the node from {@code qb.app.feed} with its {@code tree} parameter); the classification is here
 * only. The pages' {@code qb/tree/states.ts} copies it and passes the same cases
 * ({@code quizbuks-web/app/src/qb/tree/tree-state-cases.json}, {@code TreeNodeStatesTest}).
 */
public final class TreeNodeStates {

    private TreeNodeStates() {}

    /** The five states, in the order they are decided. */
    public enum State {
        /** Lit now (a lit record with no revocation). */
        LIT,
        /** A node to be lit before answering is not lit: its publications are as if they did not exist. */
        LOCKED,
        /** Conditions other than a pass are met and the person can start a publication bound to it now. */
        CAN_ANSWER,
        /** Only a pass is missing, but no publication bound to it can be started by the person now. */
        WAITING,
        /** Something other than a pass is missing (the page lists what). */
        MISSING
    }

    /**
     * What is known of a node and a person.
     *
     * @param lit       the node is lit now
     * @param unmet     the nodes to be lit before answering that are not ({@code <graph>/<node>})
     * @param startable the person can start a publication bound to the node now
     * @param condition the node's condition in effect
     * @param litRefs   the nodes the condition names that are lit now (by node id, as {@link TreeRules#holds})
     */
    public record Facts(boolean lit, List<String> unmet, boolean startable, TreeCondition condition,
        Set<String> litRefs) {
        public Facts {
            unmet = List.copyOf(unmet);
            litRefs = Set.copyOf(litRefs);
        }
    }

    /** The state the node shows. */
    public static State of(Facts facts) {
        if (facts.lit()) {
            return State.LIT;
        }
        if (!facts.unmet().isEmpty()) {
            return State.LOCKED;
        }
        // A pass would light it only if everything else holds; a pass already made but not yet evaluated counts the
        // same (lighting runs after the answer).
        if (!TreeRules.holds(facts.condition(), facts.litRefs(), true)) {
            return State.MISSING;
        }
        return facts.startable() ? State.CAN_ANSWER : State.WAITING;
    }
}

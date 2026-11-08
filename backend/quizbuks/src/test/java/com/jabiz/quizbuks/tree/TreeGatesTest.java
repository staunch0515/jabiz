package com.jabiz.quizbuks.tree;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The platform features a lit node unlocks (F-S2c): what a gate parameter names, and when it is open. */
class TreeGatesTest {

    private static final String TREE = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b";
    private static final String NODE = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5c";
    private static final TreeGates.Tree TAKERS = new TreeGates.Tree(QbTrees.PLATFORM, QbTrees.TAKER, QbTrees.ACTIVE);

    @Test
    void aGateNamesOneNodeOfOneMapAsWritten() {
        assertThat(TreeGates.parse(TREE + "/" + NODE)).contains(new TreeGates.GateNode(TREE, NODE));
        // Anything else names nothing: the feature is closed (an absent parameter is the default).
        assertThat(TreeGates.parse(null)).isEmpty();
        assertThat(TreeGates.parse("")).isEmpty();
        assertThat(TreeGates.parse(NODE)).isEmpty();
        assertThat(TreeGates.parse(TREE + "/" + NODE + "/x")).isEmpty();
        assertThat(TreeGates.parse(" " + TREE + "/" + NODE)).isEmpty();
        assertThat(TreeGates.parse(TREE.toUpperCase() + "/" + NODE)).isEmpty();
        assertThat(TreeGates.parse(42)).isEmpty();
    }

    @Test
    void itIsOpenToWhoeverLitTheNodeOfAPlatformMapTakersLightWhileTheNodeIsInEffect() {
        assertThat(TreeGates.open(TAKERS, true, List.of("l1"), List.of())).isTrue();
        // Not lit, or every lit record revoked: closed. A record lit again after a revocation opens it again.
        assertThat(TreeGates.open(TAKERS, true, List.of(), List.of())).isFalse();
        assertThat(TreeGates.open(TAKERS, true, List.of("l1"), List.of("l1"))).isFalse();
        assertThat(TreeGates.open(TAKERS, true, List.of("l1", "l2"), List.of("l1"))).isTrue();
        // The node out of effect, not found, or a map of another kind: closed, and the daily check reports it.
        assertThat(TreeGates.open(TAKERS, false, List.of("l1"), List.of())).isFalse();
        assertThat(TreeGates.open(TAKERS, null, List.of("l1"), List.of())).isFalse();
        assertThat(TreeGates.open(null, true, List.of("l1"), List.of())).isFalse();
        for (TreeGates.Tree other : List.of(new TreeGates.Tree(QbTrees.PLATFORM, QbTrees.SPONSOR, QbTrees.ACTIVE),
            new TreeGates.Tree(QbTrees.SPONSOR, QbTrees.TAKER, QbTrees.ACTIVE),
            new TreeGates.Tree(QbTrees.OPERATOR, QbTrees.TAKER, QbTrees.ACTIVE),
            new TreeGates.Tree(QbTrees.PLATFORM, QbTrees.TAKER, QbTrees.RETIRED))) {
            assertThat(TreeGates.names(other, true)).as(other.toString()).isFalse();
            assertThat(TreeGates.open(other, true, List.of("l1"), List.of())).as(other.toString()).isFalse();
        }
        assertThat(TreeGates.names(TAKERS, true)).isTrue();
    }
}

package com.jabiz.quizbuks.tree;

import com.jabiz.quizbuks.tree.TreeLights.NodeRef;
import com.jabiz.quizbuks.tree.TreeLights.State;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TreeLightsTest {

    private static final NodeRef A = new NodeRef("t1", "a");
    private static final NodeRef B = new NodeRef("t1", "b");
    private static final NodeRef C = new NodeRef("t1", "c");

    @Test
    void aNodeIsLitWhileARecordOfItIsNotRevoked() {
        List<TreeLights.Lit> lits = List.of(new TreeLights.Lit("l1", "t1", "a"), new TreeLights.Lit("l2", "t1", "b"),
            new TreeLights.Lit("l3", "t1", "b"), new TreeLights.Lit("l4", "t1", "c"));
        // b was lit again after a revocation: one standing record is enough.
        assertThat(TreeLights.litNow(lits, List.of("l2", "l4"))).containsExactlyInAnyOrder(A, B);
        assertThat(TreeLights.litNow(lits, List.of())).containsExactlyInAnyOrder(A, B, C);
        assertThat(TreeLights.litNow(List.of(), List.of("l1"))).isEmpty();
    }

    @Test
    void aNodeIsItsGraphAndItsIdTogether() {
        // An operator's copy keeps its template's ids: lighting the copy's node is not lighting the template's.
        Set<NodeRef> lit = TreeLights.litNow(List.of(new TreeLights.Lit("l1", "copy", "a")), List.of());
        assertThat(lit).containsExactly(new NodeRef("copy", "a")).doesNotContain(new NodeRef("template", "a"));
        assertThat(TreeLights.unmet(List.of("template/a"), lit)).containsExactly("template/a");
        assertThat(TreeLights.unmet(List.of("copy/a"), lit)).isEmpty();
    }

    @Test
    void theEntriesToBeLitBeforeAnsweringThatAreNotAreUnmetInTheirOrder() {
        assertThat(TreeLights.unmet(List.of("t1/c", "t1/a", "t1/b"), Set.of(A))).containsExactly("t1/c", "t1/b");
        assertThat(TreeLights.unmet(List.of("t1/a"), Set.of(A, B))).isEmpty();
        assertThat(TreeLights.unmet(List.of(), Set.of())).isEmpty();
        // An entry that names no single graph (a bare id) is never lit.
        assertThat(TreeLights.unmet(List.of("a"), Set.of(A))).containsExactly("a");
    }

    @Test
    void anEntryNamesAGraphAndANode() {
        assertThat(NodeRef.parse("t1/a")).contains(A);
        assertThat(A.key()).isEqualTo("t1/a");
        for (String bad : new String[] {null, "", "a", "/a", "t1/", "t1/a/b"}) {
            assertThat(NodeRef.parse(bad)).as(String.valueOf(bad)).isEmpty();
        }
    }

    @Test
    void aNodeIsUnmetOpenOrLitToAPerson() {
        List<String> needs = List.of("t1/a", "t1/b");
        assertThat(TreeLights.state(C, needs, Set.of())).isEqualTo(State.UNMET);
        assertThat(TreeLights.state(C, needs, Set.of(A))).isEqualTo(State.UNMET);
        assertThat(TreeLights.state(C, needs, Set.of(A, B))).isEqualTo(State.OPEN);
        assertThat(TreeLights.state(C, List.of(), Set.of())).isEqualTo(State.OPEN);
        assertThat(TreeLights.state(C, needs, Set.of(A, B, C))).isEqualTo(State.LIT);
        // Lit stays lit when a prerequisite's lighting is revoked later (no cascade).
        assertThat(TreeLights.state(C, needs, Set.of(C))).isEqualTo(State.LIT);
    }

    @Test
    void aPublicationIsHiddenWhileItsNodesPrerequisitesAreNotAllLitUnlessAnsweredAlready() {
        List<String> needs = List.of("t1/a");
        assertThat(TreeLights.visible(null, Set.of(), false)).as("bound to no node").isTrue();
        assertThat(TreeLights.visible(List.of(), Set.of(), false)).as("no prerequisites").isTrue();
        assertThat(TreeLights.visible(needs, Set.of(), false)).isFalse();
        assertThat(TreeLights.visible(needs, Set.of(A), false)).isTrue();
        assertThat(TreeLights.visible(needs, Set.of(), true)).as("their own result stays theirs").isTrue();
    }
}

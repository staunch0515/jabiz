package com.jabiz.quizbuks.tree;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TreeLightsTest {

    @Test
    void aNodeIsLitWhileARecordOfItIsNotRevoked() {
        List<TreeLights.Lit> lits = List.of(new TreeLights.Lit("l1", "a"), new TreeLights.Lit("l2", "b"),
            new TreeLights.Lit("l3", "b"), new TreeLights.Lit("l4", "c"));
        // b was lit again after a revocation: one standing record is enough.
        assertThat(TreeLights.litNow(lits, List.of("l2", "l4"))).containsExactlyInAnyOrder("a", "b");
        assertThat(TreeLights.litNow(lits, List.of())).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(TreeLights.litNow(List.of(), List.of("l1"))).isEmpty();
    }

    @Test
    void theNodesToBeLitBeforeAnsweringThatAreNotAreUnmetInTheirOrder() {
        assertThat(TreeLights.unmet(List.of("c", "a", "b"), Set.of("a"))).containsExactly("c", "b");
        assertThat(TreeLights.unmet(List.of("a"), Set.of("a", "b"))).isEmpty();
        assertThat(TreeLights.unmet(List.of(), Set.of())).isEmpty();
    }
}

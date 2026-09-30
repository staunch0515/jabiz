package com.jabiz.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SodRuleTest {

    static final SodRule RULE = SodRule.of("prepare-approve", "fin.journal.prepare, fin.journal.post",
        "fin.journal.approve");

    @Test
    void aRuleIsViolatedByOnePermissionOfEachGroup() {
        assertThat(RULE.violatedBy(List.of("fin.journal.prepare", "fin.journal.approve"))).isTrue();
        assertThat(RULE.violatedBy(List.of("fin.journal.post", "fin.journal.approve", "x"))).isTrue();
        assertThat(RULE.violatedBy(List.of("fin.journal.prepare", "fin.journal.post"))).isFalse();
        assertThat(RULE.violatedBy(List.of("*"))).isFalse();
        assertThat(SodRule.coveredByAll(Set.of("*", "a"))).isTrue();
        assertThat(SodRule.coveredByAll(Set.of("a"))).isFalse();
        assertThat(RULE.involves("fin.journal.approve")).isTrue();
        assertThat(RULE.involves("fin.journal.read")).isFalse();
        assertThat(SodRule.join(RULE.left())).isEqualTo("fin.journal.post,fin.journal.prepare");
    }

    @Test
    void groupsMustBeValidAndDisjoint() {
        assertThatThrownBy(() -> SodRule.of("r", "a,b", "b, *")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("right: '*' is not a permission code")
            .hasMessageContaining("permissions in both groups: b");
        assertThatThrownBy(() -> SodRule.of("r", null, " , ")).hasMessageContaining("left names no permission")
            .hasMessageContaining("right names no permission");
        String many = String.join(",", java.util.stream.IntStream.range(0, 51).mapToObj(i -> "p" + i).toList());
        assertThatThrownBy(() -> SodRule.of("r", many, "x")).hasMessageContaining("more than 50");
    }
}

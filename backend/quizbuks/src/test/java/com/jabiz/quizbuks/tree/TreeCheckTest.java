package com.jabiz.quizbuks.tree;

import com.jabiz.quizbuks.tree.TreeCheck.Stored;
import com.jabiz.quizbuks.tree.TreeRules.Limits;
import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TreeCheckTest {

    private static final Limits LIMITS = new Limits(200, 20, 20);

    private static Map<String, TreeCondition> nodes(Object... pairs) {
        Map<String, TreeCondition> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], TreeCondition.parse(pairs[i + 1]));
        }
        return map;
    }

    @Test
    void theMapsInEffectAreCheckedAgainstTheMapsTheyMayName() {
        Stored platform = new Stored("p", QbTrees.PLATFORM, null, QbTrees.TAKER, nodes("p1", Map.of("passed", true)));
        Stored platformSponsors = new Stored("ps", QbTrees.PLATFORM, null, QbTrees.SPONSOR,
            nodes("x1", Map.of("passed", true)));
        // A Sponsor's map naming the platform's Takers' node: fine; naming the Sponsors' template: not.
        Stored good = new Stored("s1", QbTrees.SPONSOR, "u1", QbTrees.TAKER, nodes("a",
            Map.of("op", "ALL", "items", List.of(Map.of("node", "p1", "beforeAnswer", true), Map.of("passed", true)))));
        Stored bad = new Stored("s2", QbTrees.SPONSOR, "u2", QbTrees.TAKER, nodes("b",
            Map.of("op", "ALL", "items", List.of(Map.of("node", "x1"), Map.of("passed", true)))));
        List<CheckProblem> problems = TreeCheck.problems(List.of(platform, platformSponsors, good, bad), LIMITS);
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.location()).isEqualTo("knowledge map s2 node b");
            assertThat(problem.message()).startsWith(TreeRules.UNKNOWN_NODE);
            assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.WARNING);
        });
    }

    @Test
    void anOperatorsCopyNamesOnlyItsOperatorsOtherCopies() {
        Stored mine = new Stored("c1", QbTrees.OPERATOR, "op1", QbTrees.SPONSOR, nodes("n1", Map.of("passed", true)));
        Stored theirs = new Stored("c2", QbTrees.OPERATOR, "op2", QbTrees.SPONSOR, nodes("n2", Map.of("passed", true)));
        Stored also = new Stored("c3", QbTrees.OPERATOR, "op1", QbTrees.SPONSOR, nodes("n3", Map.of("passed", true)));
        assertThat(TreeCheck.mayName(mine, also)).isTrue();
        assertThat(TreeCheck.mayName(mine, theirs)).isFalse();
        assertThat(TreeCheck.problems(List.of(mine, theirs, also), LIMITS)).isEmpty();
    }
}

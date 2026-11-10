package com.jabiz.quizbuks.tree;

import com.jabiz.quizbuks.tree.TreeCondition.Group;
import com.jabiz.quizbuks.tree.TreeCondition.NodeLit;
import com.jabiz.quizbuks.tree.TreeCondition.Op;
import com.jabiz.quizbuks.tree.TreeCondition.Passed;
import com.jabiz.quizbuks.tree.TreeRules.Graph;
import com.jabiz.quizbuks.tree.TreeRules.Limits;
import com.jabiz.quizbuks.tree.TreeRules.Problem;
import com.jabiz.quizbuks.tree.TreeRules.Subject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TreeRulesTest {

    private static final Limits LIMITS = new Limits(200, 20, 20);

    private static TreeCondition all(TreeCondition... items) {
        return new Group(Op.ALL, 0, List.of(items));
    }

    private static TreeCondition any(TreeCondition... items) {
        return new Group(Op.ANY, 0, List.of(items));
    }

    private static TreeCondition atLeast(int n, TreeCondition... items) {
        return new Group(Op.AT_LEAST, n, List.of(items));
    }

    private static TreeCondition node(String id) {
        return new NodeLit(id, false);
    }

    private static TreeCondition before(String id) {
        return new NodeLit(id, true);
    }

    private static final TreeCondition PASSED = new Passed(false);

    private static Graph graph(String id, boolean platform, Map<String, TreeCondition> nodes) {
        return new Graph(id, platform, Subject.TAKER, nodes);
    }

    private static Map<String, TreeCondition> nodes(Object... pairs) {
        Map<String, TreeCondition> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (TreeCondition) pairs[i + 1]);
        }
        return map;
    }

    private static List<String> codes(List<Problem> problems) {
        return problems.stream().map(Problem::code).toList();
    }

    @Test
    void aWellFormedNetHasNoProblems() {
        // Two starts, a fork and a join, two ends.
        Graph net = graph("g", true, nodes(
            "a", PASSED,
            "b", PASSED,
            "c", all(before("a"), PASSED),
            "d", all(before("a"), before("b"), PASSED),
            "e", atLeast(1, node("c"), node("d"))));
        assertThat(TreeRules.check(net, List.of(), LIMITS)).isEmpty();
        assertThat(TreeRules.beforeAnswer(net.nodes().get("d"))).containsExactly("a", "b");
        assertThat(TreeRules.asksForAPass(net.nodes().get("e"))).isFalse();
        assertThat(TreeRules.asksForAPass(net.nodes().get("c"))).isTrue();
    }

    @Test
    void beforeAnsweringOnlyInTheOutermostAll() {
        Graph net = graph("g", true, nodes(
            "a", PASSED,
            "b", any(before("a"), PASSED),
            "c", all(any(before("a"), node("b")), PASSED)));
        assertThat(TreeRules.check(net, List.of(), LIMITS)).extracting(Problem::nodeId, Problem::code)
            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("b", TreeRules.BEFORE_ANSWER_NESTED),
                org.assertj.core.groups.Tuple.tuple("c", TreeRules.BEFORE_ANSWER_NESTED));
        // A single item is an "all" of one.
        assertThat(TreeRules.check(graph("g", true, nodes("a", PASSED, "b", before("a"))), List.of(), LIMITS))
            .isEmpty();
    }

    @Test
    void atMostOnePassAndNeverBeforeAnswering() {
        Graph net = graph("g", true, nodes(
            "a", all(PASSED, any(PASSED)),
            "b", all(new Passed(true))));
        assertThat(codes(TreeRules.check(net, List.of(), LIMITS)))
            .containsExactlyInAnyOrder(TreeRules.PASSED_TWICE, TreeRules.PASSED_BEFORE_ANSWER);
    }

    @Test
    void groupsAreNotEmptyAndAtLeastIsInRange() {
        Graph net = graph("g", true, nodes(
            "a", all(),
            "b", atLeast(3, PASSED, node("a")),
            "c", atLeast(0, PASSED)));
        assertThat(codes(TreeRules.check(net, List.of(), LIMITS))).containsExactlyInAnyOrder(TreeRules.EMPTY_GROUP,
            TreeRules.AT_LEAST_RANGE, TreeRules.AT_LEAST_RANGE);
    }

    @Test
    void cyclesAreFoundWithinAGraphAndAcrossGraphs() {
        Graph net = graph("g", true, nodes("a", all(node("b"), PASSED), "b", node("a")));
        assertThat(codes(TreeRules.check(net, List.of(), LIMITS))).containsExactly(TreeRules.CYCLE);
        assertThat(codes(TreeRules.check(graph("g", true, nodes("a", node("a"))), List.of(), LIMITS)))
            .containsExactly(TreeRules.CYCLE);
        // A sponsor's graph through a platform node back to itself: the platform node names the sponsor's (which
        // is reported too: the platform may not use a sponsor's graph).
        Graph platform = graph("p", true, nodes("p1", all(node("s1"), PASSED)));
        Graph sponsor = graph("s", false, nodes("s1", all(node("p1"), PASSED)));
        assertThat(codes(TreeRules.check(sponsor, List.of(platform), LIMITS))).containsExactly(TreeRules.CYCLE);
        assertThat(codes(TreeRules.check(platform, List.of(sponsor), LIMITS)))
            .contains(TreeRules.SPONSOR_NODE_IN_PLATFORM, TreeRules.CYCLE);
    }

    @Test
    void referencesAreToKnownNodesOfGraphsOfTheSameSubject() {
        Graph platformSponsors = new Graph("ps", true, Subject.SPONSOR, nodes("x", PASSED));
        Graph platformTakers = graph("pt", true, nodes("y", PASSED));
        Graph sponsor = graph("s", false, nodes("s1", all(node("y"), node("x"), node("nowhere"), PASSED)));
        assertThat(TreeRules.check(sponsor, List.of(platformSponsors, platformTakers), LIMITS))
            .extracting(Problem::code, problem -> problem.params().get("node"))
            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(TreeRules.OTHER_SUBJECT, "x"),
                org.assertj.core.groups.Tuple.tuple(TreeRules.UNKNOWN_NODE, "nowhere"));
    }

    @Test
    void eachReferenceIsTheNodeOfOneGraphItsOwnFirst() {
        // F-S2d: ids are unique within a graph only (a copy keeps its template's), so a node is its graph and its id.
        Graph platform = graph("p", true, nodes("a", PASSED, "s", PASSED));
        Graph sponsor = graph("s1", false, nodes("s", PASSED, "x", all(before("a"), before("s"), PASSED)));
        assertThat(TreeRules.resolve(sponsor, List.of(platform)))
            .containsExactlyInAnyOrderEntriesOf(Map.of("a", "p", "s", "s1"));
        assertThat(TreeRules.check(sponsor, List.of(platform), LIMITS)).isEmpty();

        // Two other graphs with the node: neither (the check says so); unknown: neither, as before.
        Graph twin = graph("p2", true, nodes("a", PASSED));
        Graph draft = graph("s2", false, nodes("x", all(before("a"), node("nowhere"), PASSED)));
        assertThat(TreeRules.resolve(draft, List.of(platform, twin))).isEmpty();
        assertThat(TreeRules.check(draft, List.of(platform, twin), LIMITS)).extracting(Problem::code, p -> p.params()
                .get("node"))
            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(TreeRules.AMBIGUOUS_NODE, "a"),
                org.assertj.core.groups.Tuple.tuple(TreeRules.UNKNOWN_NODE, "nowhere"));
        // The draft's own node wins over the others' (its own graph in effect is the draft's).
        Graph own = graph("p2", true, nodes("a", all(before("s"), PASSED)));
        assertThat(TreeRules.resolve(own, List.of(platform, twin))).containsExactlyEntriesOf(Map.of("s", "p"));
    }

    @Test
    void theDraftReplacesItsGraphInEffect() {
        // In effect: b depends on a. The draft drops the dependency, so a new one the other way is no cycle.
        Graph inEffect = graph("g", true, nodes("a", PASSED, "b", all(node("a"), PASSED)));
        Graph draft = graph("g", true, nodes("a", all(node("b"), PASSED), "b", PASSED));
        assertThat(TreeRules.check(draft, List.of(inEffect), LIMITS)).isEmpty();
    }

    @Test
    void theLimitsAreTheParameters() {
        Map<String, TreeCondition> chain = new LinkedHashMap<>();
        chain.put("n0", PASSED);
        for (int i = 1; i < 5; i++) {
            chain.put("n" + i, all(node("n" + (i - 1)), PASSED));
        }
        Limits small = new Limits(4, 1, 3);
        List<Problem> problems = TreeRules.check(graph("g", true, chain), List.of(), small);
        assertThat(codes(problems)).contains(TreeRules.TOO_MANY_NODES, TreeRules.TOO_MANY_CONDITIONS,
            TreeRules.TOO_DEEP);
        assertThat(problems).filteredOn(problem -> problem.code().equals(TreeRules.TOO_DEEP))
            .extracting(Problem::nodeId).containsExactlyInAnyOrder("n3", "n4");
    }

    @Test
    void aConditionHoldsAsItsGroupsSay() {
        TreeCondition condition = all(before("a"), atLeast(2, node("b"), node("c"), node("d")), PASSED);
        assertThat(TreeRules.holds(condition, Set.of("a", "b", "c"), true)).isTrue();
        assertThat(TreeRules.holds(condition, Set.of("a", "b", "c"), false)).isFalse();
        assertThat(TreeRules.holds(condition, Set.of("a", "b"), true)).isFalse();
        assertThat(TreeRules.holds(any(node("x"), PASSED), Set.of(), true)).isTrue();
        assertThat(TreeRules.leaves(condition)).isEqualTo(5);
        assertThat(TreeRules.references(condition)).containsExactly("a", "b", "c", "d");
    }

    @Test
    void theJsonFormRoundTrips() {
        TreeCondition condition = all(before("a"), atLeast(1, node("b"), node("c")), PASSED);
        assertThat(TreeCondition.parse(condition.toMap())).isEqualTo(condition);
        assertThat(TreeCondition.parse(Map.of("passed", true, "beforeAnswer", true))).isEqualTo(new Passed(true));
        assertThatThrownBy(() -> TreeCondition.parse(Map.of("op", "SOME", "items", List.of())))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TreeCondition.parse(Map.of("op", "AT_LEAST", "items", List.of())))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TreeCondition.parse(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TreeCondition.parse(Map.of("node", " "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TreeCondition.parse(Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- F-S4b: grants ----------------------------------------------------------------------------------------------

    private static List<String> grantCodes(boolean platform, Subject subject, java.math.BigDecimal max,
        Map<String, java.math.BigDecimal> grants) {
        return TreeRules.grantProblems(platform, subject, grants, max).stream().map(Problem::code).toList();
    }

    @Test
    void onlyAPlatformTemplateSponsorsLightGrantsAndWithinTheMostPerNode() {
        java.math.BigDecimal max = new java.math.BigDecimal("500");
        Map<String, java.math.BigDecimal> grants = new LinkedHashMap<>();
        grants.put("a", new java.math.BigDecimal("500"));
        grants.put("b", null);
        assertThat(grantCodes(true, Subject.SPONSOR, max, grants)).isEmpty();
        // Another graph, or no limit published: none may be set (a node without a grant is never a problem).
        assertThat(grantCodes(true, Subject.TAKER, max, grants)).containsExactly(TreeRules.GRANT_NOT_ALLOWED);
        assertThat(grantCodes(false, Subject.SPONSOR, max, grants)).containsExactly(TreeRules.GRANT_NOT_ALLOWED);
        assertThat(grantCodes(true, Subject.SPONSOR, null, grants)).containsExactly(TreeRules.GRANT_NOT_ALLOWED);
        assertThat(grantCodes(true, Subject.TAKER, null, Map.of("b", java.math.BigDecimal.ZERO)))
            .containsExactly(TreeRules.GRANT_NOT_ALLOWED);
        // Above the most, none, negative or a fraction.
        assertThat(TreeRules.grantProblems(true, Subject.SPONSOR, Map.of("a", new java.math.BigDecimal("501")), max))
            .singleElement().satisfies(p -> {
                assertThat(p.code()).isEqualTo(TreeRules.GRANT_TOO_LARGE);
                assertThat(p.nodeId()).isEqualTo("a");
                assertThat(p.params()).containsEntry("max", "500");
            });
        assertThat(grantCodes(true, Subject.SPONSOR, max, Map.of("a", java.math.BigDecimal.ZERO)))
            .containsExactly(TreeRules.GRANT_INVALID);
        assertThat(grantCodes(true, Subject.SPONSOR, max, Map.of("a", new java.math.BigDecimal("-1"))))
            .containsExactly(TreeRules.GRANT_INVALID);
        assertThat(grantCodes(true, Subject.SPONSOR, max, Map.of("a", new java.math.BigDecimal("1.5"))))
            .containsExactly(TreeRules.GRANT_INVALID);
        assertThat(grantCodes(true, Subject.SPONSOR, max, Map.of("a", new java.math.BigDecimal("2.00")))).isEmpty();
    }

    @Test
    void aCertificateNeedsATextInAPlatformLanguageNotTooLong() {
        Map<String, Map<String, String>> names = new LinkedHashMap<>();
        names.put("a", Map.of("en", "Fair dealing"));
        names.put("b", null);
        names.put("c", Map.of());
        names.put("d", Map.of("ja", "認定", "zh", ""));
        assertThat(TreeRules.certificateProblems(names, 120)).isEmpty();
        // Blank in every language given, too long in one, a language the platform has not.
        assertThat(TreeRules.certificateProblems(Map.of("a", Map.of("en", "  ", "zh", "")), 120)).singleElement()
            .satisfies(p -> {
                assertThat(p.code()).isEqualTo(TreeRules.CERTIFICATE_INVALID);
                assertThat(p.nodeId()).isEqualTo("a");
                assertThat(p.params()).containsEntry("max", 120);
            });
        assertThat(TreeRules.certificateProblems(Map.of("a", Map.of("en", "x".repeat(121))), 120)).hasSize(1);
        assertThat(TreeRules.certificateProblems(Map.of("a", Map.of("en", "x".repeat(120))), 120)).isEmpty();
        assertThat(TreeRules.certificateProblems(Map.of("a", Map.of("xx", "Name")), 120)).hasSize(1);
    }

    @Test
    void storedNamesAreTheNonBlankTextsStrippedAndNoneForNone() {
        Map<String, String> given = new LinkedHashMap<>();
        given.put("en", " Fair dealing ");
        given.put("zh", " ");
        given.put("ja", null);
        assertThat(TreeRules.certificateNames(given)).containsExactly(Map.entry("en", "Fair dealing"));
        assertThat(TreeRules.certificateNames(Map.of("en", ""))).isNull();
        assertThat(TreeRules.certificateNames(Map.of())).isNull();
        assertThat(TreeRules.certificateNames(null)).isNull();
    }
}

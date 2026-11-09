package com.jabiz.quizbuks.tree;

import com.jabiz.quizbuks.tree.TreeCondition.Group;
import com.jabiz.quizbuks.tree.TreeCondition.NodeLit;
import com.jabiz.quizbuks.tree.TreeCondition.Op;
import com.jabiz.quizbuks.tree.TreeCondition.Passed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules of knowledge graphs (F-S2, docs/quizbuks/plans/impl/F-S2.md §3), in plain Java: what a graph's nodes may
 * say, and whether a node's condition holds for a subject. The processes report every problem at once; the startup
 * check {@code QB_TREE} runs the same checks on the graphs in effect.
 */
public final class TreeRules {

    public static final String TOO_MANY_NODES = "QB_TREE_TOO_MANY_NODES";
    public static final String TOO_MANY_CONDITIONS = "QB_TREE_TOO_MANY_CONDITIONS";
    public static final String EMPTY_GROUP = "QB_TREE_EMPTY_GROUP";
    public static final String AT_LEAST_RANGE = "QB_TREE_AT_LEAST_RANGE";
    public static final String BEFORE_ANSWER_NESTED = "QB_TREE_BEFORE_ANSWER_NESTED";
    public static final String PASSED_TWICE = "QB_TREE_PASSED_TWICE";
    public static final String PASSED_BEFORE_ANSWER = "QB_TREE_PASSED_BEFORE_ANSWER";
    public static final String UNKNOWN_NODE = "QB_TREE_UNKNOWN_NODE";
    public static final String SPONSOR_NODE_IN_PLATFORM = "QB_TREE_SPONSOR_NODE_IN_PLATFORM";
    public static final String OTHER_SUBJECT = "QB_TREE_OTHER_SUBJECT";
    public static final String CYCLE = "QB_TREE_CYCLE";
    public static final String TOO_DEEP = "QB_TREE_TOO_DEEP";
    /** F-S2d: a node named that more than one graph the draft may use has (ids are unique within a graph only). */
    public static final String AMBIGUOUS_NODE = "QB_TREE_AMBIGUOUS_NODE";

    /** Every code, for the messages' test. */
    public static final List<String> CODES = List.of(TOO_MANY_NODES, TOO_MANY_CONDITIONS, EMPTY_GROUP, AT_LEAST_RANGE,
        BEFORE_ANSWER_NESTED, PASSED_TWICE, PASSED_BEFORE_ANSWER, UNKNOWN_NODE, SPONSOR_NODE_IN_PLATFORM, OTHER_SUBJECT,
        CYCLE, TOO_DEEP, AMBIGUOUS_NODE);

    private TreeRules() {}

    /** Who lights a graph's nodes. */
    public enum Subject { TAKER, SPONSOR }

    /** A graph as the rules see it: whether it is the platform's, who lights it, and its nodes' conditions. */
    public record Graph(String treeId, boolean platform, Subject subject, Map<String, TreeCondition> nodes) {
        public Graph {
            nodes = Map.copyOf(new LinkedHashMap<>(nodes));
        }
    }

    /** The limits (ordinary parameters, read by the processes). */
    public record Limits(int maxNodes, int maxConditionsPerNode, int maxDepth) {}

    /** One problem, on a node (null for the graph). */
    public record Problem(String nodeId, String code, Map<String, Object> params) {
        public Problem {
            params = Map.copyOf(params);
        }
    }

    /**
     * Everything wrong with {@code draft} given the other graphs in effect ({@code others}, which it may reference):
     * the limits, the three restrictions (before answering only in the outermost "all"; at most one pass, never
     * before answering), references to known nodes of graphs it may use, no cycle through any graph, and no chain
     * deeper than the limit.
     */
    public static List<Problem> check(Graph draft, List<Graph> others, Limits limits) {
        List<Problem> problems = new ArrayList<>();
        if (draft.nodes().size() > limits.maxNodes()) {
            problems.add(new Problem(null, TOO_MANY_NODES, Map.of("max", limits.maxNodes(),
                "count", draft.nodes().size())));
        }
        Map<String, List<Graph>> holders = holders(draft, others);
        for (Map.Entry<String, TreeCondition> node : draft.nodes().entrySet()) {
            String id = node.getKey();
            TreeCondition condition = node.getValue();
            int leaves = leaves(condition);
            if (leaves > limits.maxConditionsPerNode()) {
                problems.add(new Problem(id, TOO_MANY_CONDITIONS, Map.of("max", limits.maxConditionsPerNode(),
                    "count", leaves)));
            }
            shape(id, condition, true, problems);
            if (passes(condition) > 1) {
                problems.add(new Problem(id, PASSED_TWICE, Map.of()));
            }
            for (String ref : references(condition)) {
                List<Graph> found = draft.nodes().containsKey(ref) ? List.of(draft)
                    : holders.getOrDefault(ref, List.of());
                Graph target = found.size() == 1 ? found.getFirst() : null;
                if (found.isEmpty()) {
                    problems.add(new Problem(id, UNKNOWN_NODE, Map.of("node", ref)));
                } else if (target == null) {
                    problems.add(new Problem(id, AMBIGUOUS_NODE, Map.of("node", ref, "count", found.size())));
                } else if (target != draft) {
                    if (draft.platform() && !target.platform()) {
                        problems.add(new Problem(id, SPONSOR_NODE_IN_PLATFORM, Map.of("node", ref)));
                    } else if (target.subject() != draft.subject()) {
                        problems.add(new Problem(id, OTHER_SUBJECT, Map.of("node", ref)));
                    }
                }
            }
        }
        // Cycles and depth over every graph the draft can reach, with the draft in place of its graph in effect.
        Map<String, TreeCondition> all = new HashMap<>();
        for (Graph other : others) {
            if (!other.treeId().equals(draft.treeId())) {
                all.putAll(other.nodes());
            }
        }
        all.putAll(draft.nodes());
        Set<String> reported = new HashSet<>();
        Map<String, Integer> depth = new HashMap<>();
        for (String id : draft.nodes().keySet()) {
            List<String> cycle = cycleFrom(id, all);
            if (!cycle.isEmpty()) {
                if (reported.add(String.join(",", new java.util.TreeSet<>(cycle)))) {
                    problems.add(new Problem(id, CYCLE, Map.of("nodes", List.copyOf(cycle))));
                }
                continue;
            }
            int d = depth(id, all, depth, new HashSet<>());
            if (d > limits.maxDepth()) {
                problems.add(new Problem(id, TOO_DEEP, Map.of("max", limits.maxDepth(), "depth", d)));
            }
        }
        return List.copyOf(problems);
    }

    /** The other graphs that have each node id (never the draft's own: its draft replaces it). */
    private static Map<String, List<Graph>> holders(Graph draft, List<Graph> others) {
        Map<String, List<Graph>> holders = new HashMap<>();
        for (Graph other : others) {
            if (!other.treeId().equals(draft.treeId())) {
                other.nodes().keySet().forEach(node -> holders.computeIfAbsent(node, n -> new ArrayList<>())
                    .add(other));
            }
        }
        return holders;
    }

    /**
     * F-S2d: the graph each node the draft's conditions name is of: its own when it has the node, else the one other
     * graph it may use that has it. A node no graph or more than one has is left out ({@link #check} reports it).
     */
    public static Map<String, String> resolve(Graph draft, List<Graph> others) {
        Map<String, List<Graph>> holders = holders(draft, others);
        Map<String, String> resolved = new LinkedHashMap<>();
        for (TreeCondition condition : draft.nodes().values()) {
            for (String ref : references(condition)) {
                if (draft.nodes().containsKey(ref)) {
                    resolved.put(ref, draft.treeId());
                } else {
                    List<Graph> found = holders.getOrDefault(ref, List.of());
                    if (found.size() == 1) {
                        resolved.put(ref, found.getFirst().treeId());
                    }
                }
            }
        }
        return resolved;
    }

    private static void shape(String id, TreeCondition condition, boolean outermostAll, List<Problem> problems) {
        switch (condition) {
            case Group g -> {
                if (g.items().isEmpty()) {
                    problems.add(new Problem(id, EMPTY_GROUP, Map.of()));
                }
                if (g.op() == Op.AT_LEAST && (g.n() < 1 || g.n() > g.items().size())) {
                    problems.add(new Problem(id, AT_LEAST_RANGE, Map.of("n", g.n(), "count", g.items().size())));
                }
                boolean top = outermostAll && g.op() == Op.ALL;
                for (TreeCondition item : g.items()) {
                    // Only the outermost "all"'s own items may be "before answering".
                    shape(id, item, top && !(item instanceof Group), problems);
                }
            }
            case NodeLit lit -> {
                if (lit.beforeAnswer() && !outermostAll) {
                    problems.add(new Problem(id, BEFORE_ANSWER_NESTED, Map.of("node", lit.nodeId())));
                }
            }
            case Passed p -> {
                if (p.beforeAnswer()) {
                    problems.add(new Problem(id, PASSED_BEFORE_ANSWER, Map.of()));
                }
            }
        }
    }

    /** How many items (nodes and passes) the condition has, at every level. */
    public static int leaves(TreeCondition condition) {
        return switch (condition) {
            case Group g -> g.items().stream().mapToInt(TreeRules::leaves).sum();
            case NodeLit lit -> 1;
            case Passed p -> 1;
        };
    }

    private static int passes(TreeCondition condition) {
        return switch (condition) {
            case Group g -> g.items().stream().mapToInt(TreeRules::passes).sum();
            case NodeLit lit -> 0;
            case Passed p -> 1;
        };
    }

    /** Whether the condition asks for a pass: only such a node can have publications bound to it. */
    public static boolean asksForAPass(TreeCondition condition) {
        return passes(condition) > 0;
    }

    /** The nodes the condition names, in order, once each. */
    public static Set<String> references(TreeCondition condition) {
        Set<String> refs = new LinkedHashSet<>();
        collect(condition, refs);
        return refs;
    }

    private static void collect(TreeCondition condition, Set<String> refs) {
        switch (condition) {
            case Group g -> g.items().forEach(item -> collect(item, refs));
            case NodeLit lit -> refs.add(lit.nodeId());
            case Passed p -> { }
        }
    }

    /**
     * The nodes that must be lit before answering a publication bound to the node: the outermost "all"'s items
     * marked so (the node row keeps them for the start).
     */
    public static List<String> beforeAnswer(TreeCondition condition) {
        List<String> nodes = new ArrayList<>();
        if (condition instanceof Group g && g.op() == Op.ALL) {
            for (TreeCondition item : g.items()) {
                if (item instanceof NodeLit lit && lit.beforeAnswer()) {
                    nodes.add(lit.nodeId());
                }
            }
        } else if (condition instanceof NodeLit lit && lit.beforeAnswer()) {
            nodes.add(lit.nodeId());
        }
        return List.copyOf(nodes);
    }

    /** Whether the condition holds for a subject with these nodes lit and (or not) a pass on this node. */
    public static boolean holds(TreeCondition condition, Set<String> lit, boolean passed) {
        return switch (condition) {
            case Group g -> {
                long met = g.items().stream().filter(item -> holds(item, lit, passed)).count();
                yield switch (g.op()) {
                    case ALL -> met == g.items().size();
                    case ANY -> met >= 1;
                    case AT_LEAST -> met >= g.n();
                };
            }
            case NodeLit l -> lit.contains(l.nodeId());
            case Passed p -> passed;
        };
    }

    /** The first cycle through {@code start}, as the nodes on it (empty when none). */
    static List<String> cycleFrom(String start, Map<String, TreeCondition> nodes) {
        List<String> path = new ArrayList<>();
        return walk(start, start, nodes, path, new HashSet<>()) ? List.copyOf(path) : List.of();
    }

    private static boolean walk(String start, String at, Map<String, TreeCondition> nodes, List<String> path,
        Set<String> seen) {
        path.add(at);
        TreeCondition condition = nodes.get(at);
        if (condition != null) {
            for (String next : references(condition)) {
                if (next.equals(start)) {
                    return true;
                }
                if (seen.add(next) && walk(start, next, nodes, path, seen)) {
                    return true;
                }
            }
        }
        path.removeLast();
        return false;
    }

    /** The longest chain of references from the node (1 for a node naming none), the graph known to have no cycle. */
    static int depth(String id, Map<String, TreeCondition> nodes, Map<String, Integer> memo, Set<String> onPath) {
        Integer known = memo.get(id);
        if (known != null) {
            return known;
        }
        TreeCondition condition = nodes.get(id);
        int deepest = 0;
        if (condition != null && onPath.add(id)) {
            for (String next : references(condition)) {
                if (!onPath.contains(next)) {
                    deepest = Math.max(deepest, depth(next, nodes, memo, onPath));
                }
            }
            onPath.remove(id);
        }
        memo.put(id, deepest + 1);
        return deepest + 1;
    }
}

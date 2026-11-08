package com.jabiz.quizbuks.tree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A node's condition (F-S2, docs/quizbuks/plans/impl/F-S2.md §3): a group (all, any, at least n) of items, each
 * another group, another node lit ({@code node:<id>}), or a pass of a publication bound to this node. Stored as JSON
 * ({@link #toMap()} / {@link #parse(Object)}); an item marked "before answering" must hold for a start.
 */
public sealed interface TreeCondition {

    /** The kinds of group. */
    enum Op { ALL, ANY, AT_LEAST }

    /** A group of items: all of them, any one, or at least {@code n}. */
    record Group(Op op, int n, List<TreeCondition> items) implements TreeCondition {
        public Group {
            items = List.copyOf(items);
        }
    }

    /** Another node lit by the same subject; {@code beforeAnswer} only in the outermost "all". */
    record NodeLit(String nodeId, boolean beforeAnswer) implements TreeCondition {}

    /**
     * Passed a publication bound to this node (at most one per node; {@code beforeAnswer} is never allowed and kept
     * only so that the rules can say so).
     */
    record Passed(boolean beforeAnswer) implements TreeCondition {}

    /** The JSON form stored in the node's definition. */
    default Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        switch (this) {
            case Group g -> {
                map.put("op", g.op().name());
                if (g.op() == Op.AT_LEAST) {
                    map.put("n", g.n());
                }
                map.put("items", g.items().stream().map(TreeCondition::toMap).toList());
            }
            case NodeLit lit -> {
                map.put("node", lit.nodeId());
                if (lit.beforeAnswer()) {
                    map.put("beforeAnswer", true);
                }
            }
            case Passed p -> {
                map.put("passed", true);
                if (p.beforeAnswer()) {
                    map.put("beforeAnswer", true);
                }
            }
        }
        return map;
    }

    /**
     * Reads a condition from its JSON form; throws {@link IllegalArgumentException} on a shape that is no condition
     * (the rules report what is wrong with a well-formed one).
     */
    static TreeCondition parse(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("A condition is an object");
        }
        if (map.containsKey("op")) {
            Op op;
            try {
                op = Op.valueOf(String.valueOf(map.get("op")));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown condition operator " + map.get("op"));
            }
            if (!(map.get("items") instanceof List<?> raw)) {
                throw new IllegalArgumentException("A group lists its items");
            }
            List<TreeCondition> items = new ArrayList<>();
            for (Object item : raw) {
                items.add(parse(item));
            }
            int n = 0;
            if (op == Op.AT_LEAST) {
                if (!(map.get("n") instanceof Number number)) {
                    throw new IllegalArgumentException("\"At least\" names how many");
                }
                n = number.intValue();
            }
            return new Group(op, n, items);
        }
        if (map.containsKey("node")) {
            Object node = map.get("node");
            if (node == null || String.valueOf(node).isBlank()) {
                throw new IllegalArgumentException("A node item names the node");
            }
            return new NodeLit(String.valueOf(node), Boolean.TRUE.equals(map.get("beforeAnswer")));
        }
        if (Boolean.TRUE.equals(map.get("passed"))) {
            return new Passed(Boolean.TRUE.equals(map.get("beforeAnswer")));
        }
        throw new IllegalArgumentException("A condition is a group, a node or a pass");
    }
}

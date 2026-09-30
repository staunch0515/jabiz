package com.jabiz.approval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Parser of {@link ApprovalCondition#parse}: recursive descent over the JSON tree, collecting every problem. */
final class ConditionParser {
    private final ApprovalSubject subject;
    private final List<String> problems = new ArrayList<>();
    private int comparisons;

    ConditionParser(ApprovalSubject subject) {
        this.subject = subject;
    }

    ApprovalCondition parseAll(Object tree) {
        ApprovalCondition condition = parse(tree, "condition", 0);
        if (comparisons > ApprovalCondition.MAX_COMPARISONS) {
            problems.add("condition has more than " + ApprovalCondition.MAX_COMPARISONS + " comparisons");
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        return condition;
    }

    private ApprovalCondition parse(Object tree, String at, int depth) {
        if (tree == null) {
            return new ApprovalCondition.Always();
        }
        if (!(tree instanceof Map<?, ?> map)) {
            problems.add(at + " must be an object");
            return new ApprovalCondition.Always();
        }
        if (map.isEmpty()) {
            return new ApprovalCondition.Always();
        }
        if (map.containsKey("all") || map.containsKey("any")) {
            String key = map.containsKey("all") ? "all" : "any";
            if (map.size() != 1) {
                problems.add(at + ": '" + key + "' stands alone");
            }
            if (depth >= ApprovalCondition.MAX_DEPTH) {
                problems.add(at + " is nested deeper than " + ApprovalCondition.MAX_DEPTH);
                return new ApprovalCondition.Always();
            }
            if (!(map.get(key) instanceof List<?> list) || list.isEmpty()) {
                problems.add(at + "." + key + " must be a non-empty list");
                return new ApprovalCondition.Always();
            }
            List<ApprovalCondition> parts = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                parts.add(parse(list.get(i), at + "." + key + "[" + i + "]", depth + 1));
            }
            return key.equals("all") ? new ApprovalCondition.All(parts) : new ApprovalCondition.Any(parts);
        }
        return compare(map, at);
    }

    private ApprovalCondition compare(Map<?, ?> map, String at) {
        comparisons++;
        for (Object key : map.keySet()) {
            if (!List.of("fact", "op", "value").contains(key)) {
                problems.add(at + ": unknown key '" + key + "'");
            }
        }
        Object fact = map.get("fact");
        FactType type = fact instanceof String name ? subject.facts().get(name) : null;
        if (type == null) {
            problems.add(at + ": fact '" + fact + "' is not declared by approval subject " + subject.name());
        }
        ApprovalCondition.Op op = ApprovalCondition.Op.of(map.get("op"));
        if (op == null) {
            problems.add(at + ": unknown operator '" + map.get("op") + "'");
        }
        if (type == null || op == null) {
            return new ApprovalCondition.Always();
        }
        if (op.ordering() && type != FactType.NUMBER || op.membership() && type == FactType.BOOLEAN) {
            problems.add(at + ": operator " + op.json + " does not apply to " + type + " fact " + fact);
            return new ApprovalCondition.Always();
        }
        Object raw = map.get("value");
        Object value;
        if (op.membership()) {
            if (!(raw instanceof List<?> list) || list.isEmpty()) {
                problems.add(at + ": " + op.json + " takes a non-empty list of values");
                return new ApprovalCondition.Always();
            }
            List<Object> values = new ArrayList<>();
            for (Object item : list) {
                Object canonical = type.normalize(item);
                if (canonical == null) {
                    problems.add(at + ": value " + item + " is not " + type);
                }
                values.add(canonical);
            }
            value = values;
        } else {
            value = type.normalize(raw);
            if (value == null) {
                problems.add(at + ": value " + raw + " is not " + type);
            }
        }
        if (value == null || value instanceof List<?> list && list.contains(null)) {
            return new ApprovalCondition.Always();
        }
        return new ApprovalCondition.Compare((String) fact, type, op, value);
    }
}

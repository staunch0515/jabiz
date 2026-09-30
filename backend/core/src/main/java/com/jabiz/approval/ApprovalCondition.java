package com.jabiz.approval;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * When an approval rule applies (docs/design/18-numbering-approvals-tasks.md section 3.2), parsed from the JSON form
 * the rule stores:
 * <pre>{@code
 * {"all": [{"fact": "amount", "op": "gte", "value": 10000},
 *          {"any": [{"fact": "source", "op": "in", "value": ["MANUAL", "IMPORT"]},
 *                   {"fact": "manual", "op": "eq", "value": true}]}]}
 * }</pre>
 * An empty object (or none) always applies. Operators: {@code eq ne} for every type, {@code gt gte lt lte} for
 * numbers, {@code in notIn} (a list of values) for numbers and texts. A comparison with a fact the case does not
 * give (or gives as null) is false, whatever the operator.
 */
public sealed interface ApprovalCondition {

    /** Deepest nesting of {@code all}/{@code any}. */
    int MAX_DEPTH = 5;

    /** Most comparisons in one condition. */
    int MAX_COMPARISONS = 100;

    /** Whether the condition holds for the (normalized) facts. */
    boolean test(Map<String, Object> facts);

    /** Always applies. */
    record Always() implements ApprovalCondition {
        @Override
        public boolean test(Map<String, Object> facts) {
            return true;
        }
    }

    /** Every part holds. */
    record All(List<ApprovalCondition> parts) implements ApprovalCondition {
        public All {
            parts = List.copyOf(parts);
        }

        @Override
        public boolean test(Map<String, Object> facts) {
            return parts.stream().allMatch(part -> part.test(facts));
        }
    }

    /** At least one part holds. */
    record Any(List<ApprovalCondition> parts) implements ApprovalCondition {
        public Any {
            parts = List.copyOf(parts);
        }

        @Override
        public boolean test(Map<String, Object> facts) {
            return parts.stream().anyMatch(part -> part.test(facts));
        }
    }

    /** Operators of {@link Compare}. */
    enum Op {
        EQ("eq"), NE("ne"), GT("gt"), GTE("gte"), LT("lt"), LTE("lte"), IN("in"), NOT_IN("notIn");

        final String json;

        Op(String json) {
            this.json = json;
        }

        static Op of(Object json) {
            for (Op op : values()) {
                if (op.json.equals(json)) {
                    return op;
                }
            }
            return null;
        }

        boolean ordering() {
            return this == GT || this == GTE || this == LT || this == LTE;
        }

        boolean membership() {
            return this == IN || this == NOT_IN;
        }
    }

    /**
     * Compares one fact with a value (for {@code in}/{@code notIn}: a list of values), both in canonical form.
     */
    record Compare(String fact, FactType type, Op op, Object value) implements ApprovalCondition {
        public Compare {
            Objects.requireNonNull(fact, "fact must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(op, "op must not be null");
            Objects.requireNonNull(value, "value must not be null");
        }

        @Override
        public boolean test(Map<String, Object> facts) {
            Object actual = facts.get(fact);
            if (actual == null) {
                return false;
            }
            return switch (op) {
                case EQ -> same(actual, value);
                case NE -> !same(actual, value);
                case IN -> ((List<?>) value).stream().anyMatch(candidate -> same(actual, candidate));
                case NOT_IN -> ((List<?>) value).stream().noneMatch(candidate -> same(actual, candidate));
                case GT -> compare(actual) > 0;
                case GTE -> compare(actual) >= 0;
                case LT -> compare(actual) < 0;
                case LTE -> compare(actual) <= 0;
            };
        }

        private int compare(Object actual) {
            return ((BigDecimal) actual).compareTo((BigDecimal) value);
        }

        private static boolean same(Object a, Object b) {
            if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
                return x.compareTo(y) == 0;
            }
            return a.equals(b);
        }
    }

    /**
     * Parses the JSON form (maps, lists, numbers, strings and booleans as a JSON reader gives them) against the
     * facts of {@code subject}. Fails listing every problem: unknown facts and operators, operators the fact's type
     * does not support, values of the wrong type, too deep or too large conditions.
     */
    static ApprovalCondition parse(Object tree, ApprovalSubject subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        return new ConditionParser(subject).parseAll(tree);
    }
}

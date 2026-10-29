package com.jabiz.quizbuks.operator;

import java.util.List;
import java.util.Map;

/** The rules of operators that need no data beyond the rows given (docs/quizbuks/plans/Q48-operators.md, Q48a). */
public final class OperatorRules {

    /**
     * Whether a user's membership rows admit them to the operators' entry: one is current (not removed) and its
     * operator runs.
     */
    public static boolean admits(List<Map<String, Object>> memberships) {
        return memberships != null && memberships.stream().anyMatch(row -> row.get("removedAt") == null
            && Boolean.TRUE.equals(row.get("operatorActive")));
    }

    private OperatorRules() {}
}

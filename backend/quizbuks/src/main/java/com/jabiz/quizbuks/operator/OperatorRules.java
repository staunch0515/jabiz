package com.jabiz.quizbuks.operator;

import java.math.BigDecimal;
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

    /**
     * F-M4: whether an operator's lead may move {@code amount} of its account to its direct Sponsor: whole yen, more
     * than nothing, at most the account's balance.
     */
    public static boolean transferable(BigDecimal amount, BigDecimal balance) {
        return amount != null && balance != null && amount.signum() > 0 && amount.stripTrailingZeros().scale() <= 0
            && amount.compareTo(balance) <= 0;
    }

    private OperatorRules() {}
}

package com.jabiz.quizbuks.identity;

import java.util.List;
import java.util.Map;

/**
 * Whether a user is banned (D-Q2-4), from the current rows of {@code QbUserBan} for them: banned while a row says so.
 * The sign-in guard and the processes that check a ban again before a valuable action share this one rule.
 */
public final class BanRules {

    /** Whether the user of these rows (one at most is expected; any that says so counts) is banned. */
    public static boolean refuses(List<Map<String, Object>> rows) {
        return rows != null && rows.stream().anyMatch(BanRules::banned);
    }

    /** Whether one row says the user is banned. */
    public static boolean banned(Map<String, Object> row) {
        return row != null && Boolean.TRUE.equals(row.get("banned"));
    }

    private BanRules() {}
}

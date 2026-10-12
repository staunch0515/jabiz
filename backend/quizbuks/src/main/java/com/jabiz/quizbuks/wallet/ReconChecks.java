package com.jabiz.quizbuks.wallet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * The daily reconciliation's judgement (F-06; docs/quizbuks/plans/Q6-payments.md, D-Q6-8): the differences the
 * templates found, as items. Each template compares in one statement (one snapshot, so a settlement committing
 * meanwhile is either wholly in it or wholly not: no false difference) and returns only the rows that differ; the
 * rows are compared again here, so a row that does not differ is never reported.
 */
public final class ReconChecks {

    /** A sponsor's cached balance against account 2110 for them. */
    public static final String SPONSOR_WALLET = "SPONSOR_WALLET";
    /** A sponsor's cached pending count against their pending attempts. */
    public static final String SPONSOR_PENDING_COUNT = "SPONSOR_PENDING_COUNT";
    /** A sponsor's cached pending amount against their pending attempts' rewards. */
    public static final String SPONSOR_PENDING_AMOUNT = "SPONSOR_PENDING_AMOUNT";
    /** Account 2110 in all against all sponsors' cached balances. */
    public static final String TOTAL_2110 = "TOTAL_2110";
    /** Every posted line's debit less credit: 0. */
    public static final String LEDGER_ZERO = "LEDGER_ZERO";
    /** A top-up's credit of 2110 against its amount when paid (0 otherwise). */
    public static final String TOPUP_LEDGER = "TOPUP_LEDGER";
    /** A top-up's crediting transactions: one when paid, none otherwise. */
    public static final String TOPUP_ARRIVALS = "TOPUP_ARRIVALS";
    /** A top-up's debits of 2110 against what it recorded taken back (refunds and lost disputes). */
    public static final String TOPUP_REFUNDS = "TOPUP_REFUNDS";
    /** A top-up's posted fee against the fee it recorded. */
    public static final String TOPUP_FEE = "TOPUP_FEE";

    /** Every check, in the order of the summary. */
    public static final List<String> ALL = List.of(SPONSOR_WALLET, SPONSOR_PENDING_COUNT, SPONSOR_PENDING_AMOUNT,
        TOTAL_2110, LEDGER_ZERO, TOPUP_LEDGER, TOPUP_ARRIVALS, TOPUP_REFUNDS, TOPUP_FEE);

    public static final String OK = "OK";
    public static final String DIFF = "DIFF";

    /**
     * One difference.
     *
     * @param partyId   the sponsor, when the check is per sponsor or top-up
     * @param reference the top-up, when the check is per top-up
     * @param expected  what the truth says (the ledger, the attempts, the record)
     * @param actual    what was found (the cache, the postings)
     */
    public record Item(String check, String partyId, String reference, BigDecimal expected, BigDecimal actual) {
        public BigDecimal difference() {
            return actual.subtract(expected);
        }
    }

    /**
     * A run's outcome.
     *
     * @param items   the differences, at most the given maximum
     * @param counts  the differences of each check, all of them
     * @param summary {@code counts} as canonical JSON (keys sorted)
     */
    public record Result(String status, List<Item> items, Map<String, Integer> counts, String summary) {
        public int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** Rows {@code partyId, expected, actual} of a check per sponsor. */
    public static List<Item> perParty(String check, List<Map<String, Object>> rows) {
        List<Item> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            add(items, check, text(row.get("partyId")), null, row.get("expected"), row.get("actual"));
        }
        return items;
    }

    /** Rows {@code partyId, expectedCount, actualCount, expectedAmount, actualAmount} of the pending rewards. */
    public static List<Item> pending(List<Map<String, Object>> rows) {
        List<Item> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String party = text(row.get("partyId"));
            add(items, SPONSOR_PENDING_COUNT, party, null, row.get("expectedCount"), row.get("actualCount"));
            add(items, SPONSOR_PENDING_AMOUNT, party, null, row.get("expectedAmount"), row.get("actualAmount"));
        }
        return items;
    }

    /** Rows {@code checkCode, expected, actual} of the totals, or {@code topUpId, sponsorId, …} of the top-ups. */
    public static List<Item> coded(List<Map<String, Object>> rows) {
        List<Item> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String check = text(row.get("checkCode"));
            if (check != null && ALL.contains(check)) {
                add(items, check, text(row.get("sponsorId")), text(row.get("topUpId")), row.get("expected"),
                    row.get("actual"));
            }
        }
        return items;
    }

    /** The run's outcome from every difference found, keeping the first {@code maxItems} as items. */
    public static Result result(List<Item> found, int maxItems) {
        Map<String, Integer> counts = new TreeMap<>();
        ALL.forEach(check -> counts.put(check, 0));
        found.forEach(item -> counts.merge(item.check(), 1, Integer::sum));
        StringJoiner json = new StringJoiner(",", "{", "}");
        counts.forEach((check, count) -> json.add("\"" + check + "\":" + count));
        List<Item> kept = found.size() <= maxItems ? List.copyOf(found) : List.copyOf(found.subList(0, maxItems));
        return new Result(found.isEmpty() ? OK : DIFF, kept, Map.copyOf(counts), json.toString());
    }

    private static void add(List<Item> items, String check, String party, String reference, Object expected,
        Object actual) {
        BigDecimal e = number(expected);
        BigDecimal a = number(actual);
        if (e.compareTo(a) != 0) {
            items.add(new Item(check, party, reference, e, a));
        }
    }

    private static BigDecimal number(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private ReconChecks() {}
}

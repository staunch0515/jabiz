package com.jabiz.quizbuks.wallet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;

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

    /** A taker's cached balance against account 2120 for them, found by two runs in a row (Q6b). */
    public static final String TAKER_WALLET = "TAKER_WALLET";
    /** A taker's cached amount on its way out against 2130 for them, found by two runs in a row (Q6b). */
    public static final String TAKER_IN_TRANSFER = "TAKER_IN_TRANSFER";
    /**
     * A taker's cache found behind the ledger by this run only: a note, not a difference (the cache follows by events,
     * seconds later); a resync is asked for, and the next run counts it if it is still there.
     */
    public static final String TAKER_WALLET_LAG = "TAKER_WALLET_LAG";
    /** What left a transfer's taker balance (2120) against its state and amount (Q6b). */
    public static final String TRANSFER_LEDGER = "TRANSFER_LEDGER";
    /** What a transfer holds in transit (2130) against its state (Q6b). */
    public static final String TRANSFER_IN_TRANSIT = "TRANSFER_IN_TRANSIT";

    /** Every check, in the order of the summary. */
    public static final List<String> ALL = List.of(SPONSOR_WALLET, SPONSOR_PENDING_COUNT, SPONSOR_PENDING_AMOUNT,
        TOTAL_2110, LEDGER_ZERO, TOPUP_LEDGER, TOPUP_ARRIVALS, TOPUP_REFUNDS, TOPUP_FEE, TAKER_WALLET,
        TAKER_IN_TRANSFER, TAKER_WALLET_LAG, TRANSFER_LEDGER, TRANSFER_IN_TRANSIT);

    /** The checks that only note something: a run with notes alone is {@code OK}. */
    public static final Set<String> NOTES = Set.of(TAKER_WALLET_LAG);

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
        /** Every item found, notes included. */
        public int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }

        /** The differences: the items that are not notes. */
        public int differences() {
            return counts.entrySet().stream().filter(e -> !NOTES.contains(e.getKey()))
                .mapToInt(Map.Entry::getValue).sum();
        }
    }

    /**
     * The takers' items and whom to resync (D-Q6-8, "two runs in a row").
     *
     * @param resync the takers whose cache differs, sorted
     */
    public record TakerItems(List<Item> items, List<String> resync) {}

    /**
     * A taker whose cache differs is a difference ({@value #TAKER_WALLET}, {@value #TAKER_IN_TRANSFER}) only when the
     * previous run found them too (noted or counted); otherwise a note ({@value #TAKER_WALLET_LAG}). Every taker found
     * is resynced.
     *
     * @param rows        {@code partyId, expectedBalance, actualBalance, expectedOut, actualOut}
     * @param foundBefore the takers the previous run found
     */
    public static TakerItems takers(List<Map<String, Object>> rows, Set<String> foundBefore) {
        List<Item> items = new ArrayList<>();
        TreeSet<String> resync = new TreeSet<>();
        for (Map<String, Object> row : rows) {
            String party = text(row.get("partyId"));
            BigDecimal expectedBalance = number(row.get("expectedBalance"));
            BigDecimal actualBalance = number(row.get("actualBalance"));
            BigDecimal expectedOut = number(row.get("expectedOut"));
            BigDecimal actualOut = number(row.get("actualOut"));
            boolean balanceDiffers = expectedBalance.compareTo(actualBalance) != 0;
            boolean outDiffers = expectedOut.compareTo(actualOut) != 0;
            if (party == null || (!balanceDiffers && !outDiffers)) {
                continue;
            }
            resync.add(party);
            if (foundBefore.contains(party)) {
                add(items, TAKER_WALLET, party, null, expectedBalance, actualBalance);
                add(items, TAKER_IN_TRANSFER, party, null, expectedOut, actualOut);
            } else if (balanceDiffers) {
                items.add(new Item(TAKER_WALLET_LAG, party, null, expectedBalance, actualBalance));
            } else {
                items.add(new Item(TAKER_WALLET_LAG, party, "out", expectedOut, actualOut));
            }
        }
        return new TakerItems(List.copyOf(items), List.copyOf(resync));
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

    /**
     * Rows {@code checkCode, expected, actual} of the totals, {@code topUpId, sponsorId, …} of the top-ups or
     * {@code transferId, userId, …} of the transfers.
     */
    public static List<Item> coded(List<Map<String, Object>> rows) {
        List<Item> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String check = text(row.get("checkCode"));
            if (check != null && ALL.contains(check)) {
                String party = row.containsKey("userId") ? text(row.get("userId")) : text(row.get("sponsorId"));
                String reference = row.containsKey("transferId") ? text(row.get("transferId"))
                    : text(row.get("topUpId"));
                add(items, check, party, reference, row.get("expected"), row.get("actual"));
            }
        }
        return items;
    }

    /** The checks of the takers' cache: the next run reads them back ("found before"), notes included. */
    public static final Set<String> TAKER_CHECKS = Set.of(TAKER_WALLET, TAKER_IN_TRANSFER, TAKER_WALLET_LAG);

    /**
     * The run's outcome from every difference found, keeping {@code maxItems} as items: the takers' first, so that
     * the next run finds whom this one found (the "two runs in a row" rule) however many other differences there are.
     */
    public static Result result(List<Item> found, int maxItems) {
        Map<String, Integer> counts = new TreeMap<>();
        ALL.forEach(check -> counts.put(check, 0));
        found.forEach(item -> counts.merge(item.check(), 1, Integer::sum));
        StringJoiner json = new StringJoiner(",", "{", "}");
        counts.forEach((check, count) -> json.add("\"" + check + "\":" + count));
        List<Item> ordered = new ArrayList<>(found.stream().filter(i -> TAKER_CHECKS.contains(i.check())).toList());
        found.stream().filter(i -> !TAKER_CHECKS.contains(i.check())).forEach(ordered::add);
        List<Item> kept = List.copyOf(ordered.size() <= maxItems ? ordered : ordered.subList(0, maxItems));
        boolean differs = found.stream().anyMatch(item -> !NOTES.contains(item.check()));
        return new Result(differs ? DIFF : OK, kept, Map.copyOf(counts), json.toString());
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

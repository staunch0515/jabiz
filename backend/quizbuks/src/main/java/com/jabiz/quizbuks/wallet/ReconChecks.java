package com.jabiz.quizbuks.wallet;

import com.jabiz.quizbuks.QbPermissions;

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
    /** Q87a: a sponsor's cached cash balance against account 2160 for them. */
    public static final String CASH_ACCOUNT = "CASH_ACCOUNT";
    /** Q87a: account 2160 in all against all sponsors' cached cash balances. */
    public static final String TOTAL_2160 = "TOTAL_2160";
    /** Q87b: 1130 for a sponsor against their open and overdue invoices and the postpaid charges not invoiced yet. */
    public static final String FEES_RECEIVABLE = "FEES_RECEIVABLE";
    /** Q88b: 2170 for a sponsor (the entry fees not yet settled into their cash account) is never below zero. */
    public static final String ENTRY_FEES_UNSETTLED = "ENTRY_FEES_UNSETTLED";
    /** F-M1: 2190 for a sponsor against their fee credit wallet and what their grants have left. */
    public static final String FEE_CREDIT = "FEE_CREDIT";
    /** F-M1: 2160 for a sponsor against what their cash lots have left. */
    public static final String CASH_LOTS = "CASH_LOTS";
    /** F-M1: what fee payments took from 2160 and 2190 for a sponsor against the payments' parts. */
    public static final String FEE_PAYMENTS = "FEE_PAYMENTS";
    /** F-M2: the promotion expenses of the grants (5140) by operator against what was granted less what was voided. */
    public static final String FEE_CREDIT_PROMOTION = "FEE_CREDIT_PROMOTION";
    /** F-S4b: a sponsor's lighting lots (source LIGHT) that no lit record of theirs earned. */
    public static final String LIGHT_GRANTS = "LIGHT_GRANTS";
    /** F-S4b: what is left of a sponsor's lighting lots whose node they no longer hold lit (to be voided). */
    public static final String LIGHT_VOIDS = "LIGHT_VOIDS";
    /**
     * F-M3: a sponsor's top-up of a verified company (source CERTIFIED_TOP_UP) though never verified, or with the
     * onboarding above the certified amount recorded when it was granted.
     */
    public static final String CERTIFIED_GRANTS = "CERTIFIED_GRANTS";
    /** F-M4: an operator's account (2140) against its settlements, paid withdrawals and transfers to its Sponsor. */
    public static final String OPERATOR_ACCOUNT = "OPERATOR_ACCOUNT";
    /** F-M5: a sponsor's prepayments (2180) against what their open prepaid prepayments hold. */
    public static final String PREPAYMENTS = "PREPAYMENTS";
    /**
     * F-M5: prepayments still open that should have been given back: of a publication back in its draft (withdrawn,
     * rejected, review expired), ended more than {@code qb.prepayment.release.overdue-days} ago, or not there.
     */
    public static final String PREPAYMENT_STALE = "PREPAYMENT_STALE";
    /** F-M5 (M3 of its review): prepaid prepayments whose parts do not add up to what they hold. */
    public static final String PREPAYMENT_PARTS = "PREPAYMENT_PARTS";
    /** F-M5 (N1 of its recheck): audience fees not charged though the publication started. */
    public static final String PREPAYMENT_UNCHARGED = "PREPAYMENT_UNCHARGED";
    /** F-M6: a sponsor's distribution fee deposits (2180, purpose DISTRIBUTION) against what they hold. */
    public static final String DISTRIBUTION_DEPOSITS = "DISTRIBUTION_DEPOSITS";
    /**
     * F-M6: publications due for the month before and not settled for it (no row of the month, no final one)
     * {@code qb.distribution.unsettled-after-hours} into the month; actual: how many.
     */
    public static final String DISTRIBUTION_UNSETTLED = "DISTRIBUTION_UNSETTLED";
    /** F-M6: publications ended {@code qb.distribution.final-missing-after-hours} ago without a final settlement. */
    public static final String DISTRIBUTION_FINAL_MISSING = "DISTRIBUTION_FINAL_MISSING";
    /** F-M6: operators' prices outside the headquarters' range as it is now (sponsorId holds the operator). */
    public static final String DISTRIBUTION_PRICE_RANGE = "DISTRIBUTION_PRICE_RANGE";
    /**
     * Retired (never reported): the first name of {@link #DISTRIBUTION_PRICE_RANGE}. Its 31 characters did not fit
     * {@code qb_recon_item_version.check_code} (30): the template's cast cut it short and the run dropped it. Runs
     * written before the rename hold it in their sealed summaries ({@code ":0"}), so the dictionary keeps it, disabled.
     */
    public static final String DISTRIBUTION_PRICE_OUT_OF_RANGE = "DISTRIBUTION_PRICE_OUT_OF_RANGE";
    /** F-M6: publications whose price per start is the operator's floor: a note for the operators, not a difference. */
    public static final String DISTRIBUTION_AT_FLOOR = "DISTRIBUTION_AT_FLOOR";
    /** F-M7 (N9): audience fees not decided {@code qb.audience.refund.missing-after-hours} after the end; how many. */
    public static final String AUDIENCE_REFUND_MISSING = "AUDIENCE_REFUND_MISSING";
    /** F-M7 (N5): audience fees decided without a period to count the days by (finance decides on a note by hand). */
    public static final String AUDIENCE_REFUND_NO_PERIOD = "AUDIENCE_REFUND_NO_PERIOD";
    /** F-M7: audience fees whose publication never went on. */
    public static final String AUDIENCE_FEE_NOT_LIVE = "AUDIENCE_FEE_NOT_LIVE";
    /** F-M7: a credit note that gave back and paid more than its total (by note). */
    public static final String CREDIT_NOTE_USES = "CREDIT_NOTE_USES";
    /** F-M7 (N7): a fee whose notes come to more than its amount or tax (by fee). */
    public static final String CREDIT_NOTE_LIMITS = "CREDIT_NOTE_LIMITS";
    /** F-M7: a note's credit used on a voided invoice. */
    public static final String CREDIT_NOTE_USE_VOID = "CREDIT_NOTE_USE_VOID";
    /** F-M7 (N4): credit left while an invoice is unpaid. */
    public static final String CREDIT_UNUSED_WITH_DUE = "CREDIT_UNUSED_WITH_DUE";
    /**
     * F-M10: a month of the AI help's usage in which everybody's row is below the sum of the people's (questions, input
     * or output tokens, estimated dollars); {@code reference} names the month and the measure. Not "unequal": a deleted
     * account's row is erased and a deleted conversation adds only to everybody's.
     */
    public static final String HELP_USAGE_TOTAL = "HELP_USAGE_TOTAL";
    /**
     * F-M12 c (plan §3.1): an operator without its settlement for a month more than
     * {@code qb.operator.settlement.waiting-after-hours} after the month's end; {@code reference} names the earliest
     * such month and the steps it waits for ({@code 2027-03:DISTRIBUTION,INVOICE}, or the month alone when none is
     * pending); actual: how many months it is behind. {@code partyId} holds the operator.
     */
    public static final String OPERATOR_SETTLEMENT_WAITING = "OPERATOR_SETTLEMENT_WAITING";
    /**
     * F-M12 c (plan §6): what a settlement of the new kind (from its switch point on) counted against its own row
     * ({@code reference}: {@code <month>:<column>}: the lines' sum against the row's column), and the fees and parts
     * of payments made with granted credit that two settlements that could have counted them still have not
     * ({@code fee:<period>}, {@code gift:<month paid>}; expected: what is missing, actual 0). A fee a capped
     * settlement left, after its watermark, is not missing. {@code partyId} holds the operator.
     */
    public static final String OPERATOR_SETTLEMENT_COVERAGE = "OPERATOR_SETTLEMENT_COVERAGE";
    /**
     * F-M12 c (plan §4, §6): the granted credit taken back from the operators. {@code reference} {@code recovered}: what
     * the settlements say they took back against the net credit to 5140 of the postings made for them (not 2140: a
     * refund that paid the cost carried posts that part to 4110); {@code <month>}: the flow of a month, what was due
     * against what was taken back and carried, less what was carried in. {@code partyId} holds the operator.
     */
    public static final String GIFT_RECOVERY = "GIFT_RECOVERY";
    /** F-M12 c (plan §3.2): fees with no operator, which no settlement counts ({@code reference}: the period; partyId: the sponsor). */
    public static final String OPERATOR_CHARGE_NO_OPERATOR = "OPERATOR_CHARGE_NO_OPERATOR";
    /**
     * F-M12 c (plan §3.8, a note): an operator that is suspended and still has granted credit to take back, carried by
     * its latest settlement; nobody is asked for it (it is not collected).
     */
    public static final String OPERATOR_GIFT_CARRY_OPEN = "OPERATOR_GIFT_CARRY_OPEN";
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
        TAKER_IN_TRANSFER, TAKER_WALLET_LAG, TRANSFER_LEDGER, TRANSFER_IN_TRANSIT, CASH_ACCOUNT, TOTAL_2160,
        FEES_RECEIVABLE, ENTRY_FEES_UNSETTLED, FEE_CREDIT, CASH_LOTS, FEE_PAYMENTS, FEE_CREDIT_PROMOTION,
        LIGHT_GRANTS, LIGHT_VOIDS, CERTIFIED_GRANTS, OPERATOR_ACCOUNT, PREPAYMENTS, PREPAYMENT_STALE,
        PREPAYMENT_PARTS, PREPAYMENT_UNCHARGED, DISTRIBUTION_DEPOSITS, DISTRIBUTION_UNSETTLED,
        DISTRIBUTION_FINAL_MISSING, DISTRIBUTION_PRICE_RANGE, DISTRIBUTION_AT_FLOOR, AUDIENCE_REFUND_MISSING,
        AUDIENCE_REFUND_NO_PERIOD, AUDIENCE_FEE_NOT_LIVE, CREDIT_NOTE_USES, CREDIT_NOTE_LIMITS, CREDIT_NOTE_USE_VOID,
        CREDIT_UNUSED_WITH_DUE, HELP_USAGE_TOTAL, OPERATOR_SETTLEMENT_WAITING, OPERATOR_SETTLEMENT_COVERAGE,
        GIFT_RECOVERY, OPERATOR_CHARGE_NO_OPERATOR, OPERATOR_GIFT_CARRY_OPEN);

    /** Codes no longer reported that runs written before may still name: readable, never written. */
    public static final List<String> RETIRED = List.of(DISTRIBUTION_PRICE_OUT_OF_RANGE);

    /** The checks that only note something: a run with notes alone is {@code OK}, and nobody is asked. */
    public static final Set<String> NOTES = Set.of(TAKER_WALLET_LAG, DISTRIBUTION_AT_FLOOR, OPERATOR_GIFT_CARRY_OPEN);

    /**
     * F-M14 (docs/quizbuks/plans/impl/F-M14.md §1.2): the checks that need someone to look or decide while the books
     * agree and no month already closed is wrong or short. A run with these (and notes) alone is {@code OK}: the gauge,
     * the alert and the journal export see differences only; the owner gets one task per check and object
     * ({@link ReconTasks}).
     */
    public static final Set<String> ATTENTION = Set.of(DISTRIBUTION_PRICE_RANGE, AUDIENCE_REFUND_NO_PERIOD,
        CREDIT_UNUSED_WITH_DUE, HELP_USAGE_TOTAL);

    /** F-M14: the dictionary of each check's level (made from these sets). */
    public static final String LEVEL_DICTIONARY = "urn:jabiz:dict:quizbuks:recon-level";

    /** How much a check found matters (F-M14 §1.2): one level per check. */
    public enum Level {
        /** A month already closed is wrong or short because of it: the run is DIFF, the export waits. */
        DIFFERENCE,
        /** The books agree; someone looks or decides: a task, the run stays OK. */
        ATTENTION,
        /** Information only. */
        NOTE
    }

    /** The level of a check. */
    public static Level level(String check) {
        return NOTES.contains(check) ? Level.NOTE : ATTENTION.contains(check) ? Level.ATTENTION : Level.DIFFERENCE;
    }

    /**
     * The permission whose holders are asked about a check (F-M14 §3.1b, G4): finance's {@code qb.reconcile} unless
     * named here. A task goes to one permission, so each owner gets their own.
     */
    static final Map<String, String> OWNERS = Map.of(HELP_USAGE_TOTAL, QbPermissions.ADMIN_HELP_GAPS);

    /**
     * Where a check's task leads when its owner cannot open finance's reconciliation page: the AI help's usage is on
     * headquarters' AI page, which the content administrators (owners of {@link #HELP_USAGE_TOTAL}) can open.
     */
    static final Map<String, String> LINKS = Map.of(HELP_USAGE_TOTAL, "/qb/ai");

    /** The page a check's task leads to: its own, else the reconciliation page. */
    public static String link(String check) {
        return LINKS.getOrDefault(check, ReconTasks.RECONCILIATION_PAGE);
    }

    /** Who is asked about a check: its owner's permission. */
    public static String owner(String check) {
        return OWNERS.getOrDefault(check, QbPermissions.RECONCILE);
    }

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

        /** The differences: the items neither notes nor for attention (F-M14). */
        public int differences() {
            return count(Level.DIFFERENCE);
        }

        /** The items for attention. */
        public int attention() {
            return count(Level.ATTENTION);
        }

        /** How many checks found differences. */
        public int checksDiffering() {
            return (int) counts.entrySet().stream().filter(e -> e.getValue() > 0
                && level(e.getKey()) == Level.DIFFERENCE).count();
        }

        private int count(Level level) {
            return counts.entrySet().stream().filter(e -> level(e.getKey()) == level)
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
     *
     * @throws IllegalStateException for a code that is not one of {@link #ALL} (one cut short by its column, a typo in
     *     a template): the run fails rather than leave the check out unseen, and qb-reconcile-missing reports it
     */
    public static List<Item> coded(List<Map<String, Object>> rows) {
        List<Item> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String check = text(row.get("checkCode"));
            if (check == null || !ALL.contains(check)) {
                throw new IllegalStateException("A reconciliation template returned the check code '" + check
                    + "', which is not one of ReconChecks.ALL");
            }
            String party = row.containsKey("userId") ? text(row.get("userId")) : text(row.get("sponsorId"));
            // F-M6 (review M2, M4): the fee account's template names a publication or a month as its reference.
            String reference = row.containsKey("transferId") ? text(row.get("transferId"))
                : row.containsKey("reference") ? text(row.get("reference")) : text(row.get("topUpId"));
            add(items, check, party, reference, row.get("expected"), row.get("actual"));
        }
        return items;
    }

    /** The checks of the takers' cache: the next run reads them back ("found before"), notes included. */
    public static final Set<String> TAKER_CHECKS = Set.of(TAKER_WALLET, TAKER_IN_TRANSFER, TAKER_WALLET_LAG);

    /**
     * The run's outcome from every item found, keeping {@code maxItems} as items: the takers' first, so that the next
     * run finds whom this one found (the "two runs in a row" rule) however many other items there are; then the
     * differences, those for attention and the notes (F-M14, review M5: a note never pushes out a difference). The
     * counts are always all of them. The run is {@code DIFF} only for a difference.
     */
    public static Result result(List<Item> found, int maxItems) {
        Map<String, Integer> counts = new TreeMap<>();
        ALL.forEach(check -> counts.put(check, 0));
        found.forEach(item -> counts.merge(item.check(), 1, Integer::sum));
        StringJoiner json = new StringJoiner(",", "{", "}");
        counts.forEach((check, count) -> json.add("\"" + check + "\":" + count));
        List<Item> ordered = new ArrayList<>(found.stream().filter(i -> TAKER_CHECKS.contains(i.check())).toList());
        for (Level level : Level.values()) {
            found.stream().filter(i -> !TAKER_CHECKS.contains(i.check()) && level(i.check()) == level)
                .forEach(ordered::add);
        }
        List<Item> kept = List.copyOf(ordered.size() <= maxItems ? ordered : ordered.subList(0, maxItems));
        boolean differs = found.stream().anyMatch(item -> level(item.check()) == Level.DIFFERENCE);
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

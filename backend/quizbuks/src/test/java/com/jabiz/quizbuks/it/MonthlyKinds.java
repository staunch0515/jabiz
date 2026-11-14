package com.jabiz.quizbuks.it;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard of the monthly report's classification (F-M14 G13, docs/quizbuks/plans/impl/F-M14.md §3.2 point 4), shared
 * by {@link MonthlyKindsIT} and {@link MonthlyKindsFeeIT}: the table of the source entities a ledger transaction is
 * posted with and the categories {@code qb.admin.monthly} puts each into. A new source (or a new category) that is
 * not added here fails both tests, so a new kind of posting cannot slip into {@code OTHER} unseen.
 */
final class MonthlyKinds {

    /** Source entity of a posting, to the categories of the report that may hold its transactions. */
    static final Map<String, Set<String>> CATEGORIES_BY_SOURCE = new LinkedHashMap<>();

    static {
        // Stripe, bank and hand-made top-ups (and what is taken back from them), into the Kudos or the cash account.
        source("QbTopUp", "TOPUP", "BANK_TOPUP", "MANUAL_TOPUP", "GRANT", "REFUND", "DISPUTE", "STRIPE_FEE",
            "CASH_TOPUP", "CASH_BANK_TOPUP", "CASH_REFUND", "CASH_DISPUTE");
        source("QbAttempt", "REWARD", "ENTRY_FEE");
        source("QbReferral", "REFERRAL_REWARD");
        source("QbTransfer", "TRANSFER_SENT", "TRANSFER_SETTLED", "WITHDRAWAL_PAID", "TRANSFER_FAILED",
            "TRANSFER_REVERSED");
        source("QbStripePayout", "PAYOUT", "PAYOUT_FAILED");
        // F-M12: the take-back of granted credit (and its giving back) is posted with the settlement.
        source("QbOperatorSettlement", "OPERATOR_SHARE", "OPERATOR_PAID", "FEE_CREDIT_RECOVERED");
        source("QbOperatorWithdrawal", "OPERATOR_PAID");
        source("QbOperatorTransfer", "OPERATOR_TRANSFER_TO_DIRECT");
        source("QbKudosExchange", "KUDOS_BOUGHT", "KUDOS_SOLD");
        // The platform's fees: charged, paid (from the cash, the credit, a prepayment), voided, the distribution fee.
        source("QbFeeCharge", "DISTRIBUTION_CHARGED", "EXPOSURE_CHARGED", "FEE_CHARGED", "FEE_PAID", "FEE_VOIDED",
            "PREPAYMENT_USED");
        source("QbFeeInvoice", "FEE_PAID", "FEE_VOIDED");
        source("QbFeeCreditNote", "FEE_CREDIT_NOTE", "FEE_PAID");
        // The entry fees settled into the sponsor's cash account are posted from the cash wallet row.
        source("QbWallet", "ENTRY_FEE_SETTLED");
        source("QbFeeCreditGrant", "FEE_CREDIT_GRANTED", "FEE_CREDIT_VOIDED");
        // F-M8c: the exposure fee's deposit (set aside and topped up) and its release have kinds of their own.
        source("QbPrepayment", "PREPAYMENT_FROZEN", "PREPAYMENT_RELEASED", "EXPOSURE_DEPOSIT", "EXPOSURE_RELEASED",
            "DISTRIBUTION_DEPOSIT", "DISTRIBUTION_RELEASED");
    }

    private static void source(String entity, String... categories) {
        CATEGORIES_BY_SOURCE.put(entity, new LinkedHashSet<>(List.of(categories)));
    }

    static Set<String> categories() {
        Set<String> all = new LinkedHashSet<>();
        CATEGORIES_BY_SOURCE.values().forEach(all::addAll);
        return all;
    }

    /**
     * What the guard asserts about a report over the whole ledger of a test class's schema: every category of the
     * report is one of the table's (or {@code OTHER}) and every table category has its row; every source the ledger
     * holds is in the table; {@code OTHER} holds only the transactions that have no source; each of {@code driven}
     * has at least one transaction; and the report closes (#688 review M1): its rows' transactions are all the ledger's
     * and their amounts all its debits, and each row's account columns add up to 0. A category the report's CASE gives
     * but its VALUES list lacks drops its transactions from the report (LEFT JOIN): only the closure sees that.
     *
     * @param totals {@link #TOTALS_SQL}'s one row: the ledger's transactions and debits
     */
    static void assertGuard(List<Map<String, Object>> report, List<String> driven, List<Map<String, Object>> sourceRows,
        long withoutSource, Map<String, Object> totals) {
        Set<String> known = categories();
        assertThat(report).extracting(row -> String.valueOf(row.get("category")))
            .as("the report's rows are the table's categories and OTHER").containsAll(known).contains("OTHER")
            .allSatisfy(category -> assertThat(known.contains(category) || "OTHER".equals(category))
                .as("category " + category + " is not in MonthlyKinds.CATEGORIES_BY_SOURCE").isTrue());
        List<Object> sources = sourceRows.stream().map(row -> row.get("source_entity")).toList();
        assertThat(sources).as("a source of a posting that MonthlyKinds.CATEGORIES_BY_SOURCE (and monthly.sql) does "
            + "not know: add its row to both").isSubsetOf(CATEGORIES_BY_SOURCE.keySet().toArray());
        Map<String, Number> counts = new LinkedHashMap<>();
        report.forEach(row -> counts.put(String.valueOf(row.get("category")), (Number) row.get("transactionCount")));
        assertThat(counts.get("OTHER").longValue()).as("OTHER holds only the postings of no source")
            .isEqualTo(withoutSource);
        for (String category : driven) {
            assertThat(counts.get(category).longValue()).as(category + " was driven").isPositive();
        }
        long transactions = report.stream().mapToLong(row -> ((Number) row.get("transactionCount")).longValue()).sum();
        assertThat(transactions).as("every transaction of the ledger is in one row of the report")
            .isEqualTo(((Number) totals.get("n")).longValue());
        java.math.BigDecimal amount = report.stream().map(row -> new java.math.BigDecimal(String.valueOf(row.get("amount"))))
            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        assertThat(amount).as("the rows' amounts are all the ledger's debits")
            .isEqualByComparingTo(new java.math.BigDecimal(String.valueOf(totals.get("debits"))));
        for (Map<String, Object> row : report) {
            java.math.BigDecimal net = ACCOUNT_COLUMNS.stream()
                .map(column -> new java.math.BigDecimal(String.valueOf(row.get(column))))
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            assertThat(net).as("the account columns of " + row.get("category") + " add up to 0").isZero();
        }
    }

    /** The report's account columns (debit less credit), one an account of the ledger. */
    static final List<String> ACCOUNT_COLUMNS = List.of("stripeBalance", "bank", "sponsorWallets", "takerWallets",
        "inTransit", "revenue", "paymentFees", "promotion", "operatorPayable", "operatorShares", "sponsorCash",
        "feesReceivable", "feeRevenue", "taxPayable", "entryFeesUnsettled", "feeCredit", "feeCreditPromotion",
        "feePrepayments");

    /** The ledger's transactions and their debits (the argument of {@link #assertGuard}). */
    static final String TOTALS_SQL = "SELECT (SELECT count(*) FROM ledger_transaction_version) AS n, "
        + "(SELECT coalesce(sum(amount), 0) FROM ledger_entry_version WHERE direction = 'DEBIT') AS debits";

    /** The distinct sources of the postings in the class's schema (the argument of {@link #assertGuard}). */
    static final String SOURCES_SQL = "SELECT DISTINCT source_entity FROM ledger_transaction_version "
        + "WHERE source_entity IS NOT NULL";

    /** The postings of no source (the argument of {@link #assertGuard}). */
    static final String NO_SOURCE_SQL = "SELECT count(*) AS n FROM ledger_transaction_version "
        + "WHERE source_entity IS NULL";

    private MonthlyKinds() {}
}

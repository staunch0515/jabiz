package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.KudosExchanges;
import com.jabiz.quizbuks.wallet.ReconChecks;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M14 a1 (docs/quizbuks/plans/impl/F-M14.md §7): the daily check's codes that no integration test made report. Each
 * test makes the smallest change that is realistic (a cache column changed behind the processes' back, as
 * {@link ReconcileIT} does, never a row of an append-only table), reads the check's template as the reconciliation does
 * ({@link ReconChecks#coded}, {@link ReconChecks#perParty}, {@link ReconChecks#pending}, {@link ReconChecks#takers})
 * and asserts the code, the party and the numbers; then it puts the change back and asserts the check is quiet again
 * (and was quiet before). Every test has its own sponsor, top-up or transfer and looks only at its own.
 *
 * <p>Of the twelve codes the plan lists, this class has {@code SPONSOR_PENDING_AMOUNT}, {@code TOPUP_LEDGER},
 * {@code TOPUP_ARRIVALS}, {@code TOPUP_FEE}, {@code TAKER_IN_TRANSFER}, {@code TRANSFER_LEDGER},
 * {@code CASH_ACCOUNT} and {@code TOTAL_2160}; {@link ReconCoverageFeeIT} has {@code FEES_RECEIVABLE},
 * {@code ENTRY_FEES_UNSETTLED}, {@code FEE_PAYMENTS} and {@code DISTRIBUTION_DEPOSITS} (it needs the fee side's
 * fixtures, which cannot be had together with Stripe's stub in one test class).
 *
 * <p>What was there before (searched by name and by what the test provokes): none of the twelve was ever made to report.
 * {@link ReconcileIT} provokes {@code SPONSOR_WALLET}, {@code SPONSOR_PENDING_COUNT}, {@code TOTAL_2110} and
 * {@code TOPUP_REFUNDS} only (pending count, not amount); {@code ReconChecksTest} and the scenario snapshots hold
 * zero-count summaries and the code lists; {@code FeeAccountIT}, {@code FeeOwedIT}, {@code PrepaymentIT},
 * {@code DistributionIT}, {@code CashAccountIT}, {@code EntryFeeIT} and {@code OperatorTransferIT} read the templates
 * (or the whole run) only to assert that nothing is wrong. So all twelve are covered here and nowhere else.
 */
class ReconCoverageIT extends PayoutItSupport {

    static final Map<String, Object> ALL_ROWS = Map.of("limit", 500);

    String systemToken() {
        return TestTokens.bearer(tokens, "recon-coverage", QbPermissions.WALLET_SYSTEM, QbPermissions.PAYOUT_SYSTEM);
    }

    List<Map<String, Object>> rows(String template) {
        return items(template, systemToken(), ALL_ROWS);
    }

    /** The top-up's items of the daily check (all four of the top-up checks). */
    List<ReconChecks.Item> topUpItems(String topUpId) {
        return ReconChecks.coded(rows("qb.system.recon-topups")).stream()
            .filter(item -> topUpId.equals(item.reference())).toList();
    }

    List<ReconChecks.Item> transferItems(String transferId) {
        return ReconChecks.coded(rows("qb.system.recon-transfers")).stream()
            .filter(item -> transferId.equals(item.reference())).toList();
    }

    List<ReconChecks.Item> pendingItems(String sponsor) {
        return ReconChecks.pending(rows("qb.system.recon-sponsor-pending")).stream()
            .filter(item -> sponsor.equals(item.partyId())).toList();
    }

    List<ReconChecks.Item> cashItems(String sponsor) {
        return ReconChecks.perParty(ReconChecks.CASH_ACCOUNT, rows("qb.system.recon-cash-accounts")).stream()
            .filter(item -> sponsor.equals(item.partyId())).toList();
    }

    List<ReconChecks.Item> totals() {
        return ReconChecks.coded(rows("qb.system.recon-totals"));
    }

    String paidTopUp(String sponsor, long amount) {
        String topUpId = openTopUp(sponsor, amount);
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
        return topUpId;
    }

    static List<String> codes(List<ReconChecks.Item> items) {
        return items.stream().map(ReconChecks.Item::check).toList();
    }

    @Test
    void aSponsorsCachedPendingAmountThatTheirAttemptsDoNotHoldIsReported() {
        String sponsor = newSponsor(unique("s-cov-pending"));
        credit(sponsor, 500);
        assertThat(pendingItems(sponsor)).isEmpty();
        execute("UPDATE qb_wallet SET pending_amount = pending_amount + 25 WHERE owner_id = ? AND kind = 'SPONSOR'",
            sponsor);
        try {
            assertThat(pendingItems(sponsor)).singleElement().satisfies(item -> {
                assertThat(item.check()).isEqualTo(ReconChecks.SPONSOR_PENDING_AMOUNT);
                assertThat(item.expected()).isEqualByComparingTo("0");
                assertThat(item.actual()).isEqualByComparingTo("25");
            });
            assertThat(ReconChecks.level(ReconChecks.SPONSOR_PENDING_AMOUNT)).isEqualTo(ReconChecks.Level.DIFFERENCE);
        } finally {
            execute("UPDATE qb_wallet SET pending_amount = pending_amount - 25 WHERE owner_id = ? AND kind = 'SPONSOR'",
                sponsor);
        }
        assertThat(pendingItems(sponsor)).isEmpty();
    }

    @Test
    void aPaidTopUpWhoseAmountDiffersFromItsCreditIsReportedAsTheLedger() {
        String sponsor = newSponsor(unique("s-cov-topup-ledger"));
        String topUpId = paidTopUp(sponsor, 1_000);
        assertThat(topUpItems(topUpId)).isEmpty();
        execute("UPDATE qb_topup SET amount = amount + 100 WHERE topup_id = ?", topUpId);
        try {
            assertThat(topUpItems(topUpId)).singleElement().satisfies(item -> {
                assertThat(item.check()).isEqualTo(ReconChecks.TOPUP_LEDGER);
                assertThat(item.partyId()).isEqualTo(sponsor);
                assertThat(item.expected()).isEqualByComparingTo("1100");
                assertThat(item.actual()).isEqualByComparingTo("1000");
            });
        } finally {
            execute("UPDATE qb_topup SET amount = amount - 100 WHERE topup_id = ?", topUpId);
        }
        assertThat(topUpItems(topUpId)).isEmpty();
    }

    @Test
    void aTopUpNoLongerRecordedPaidThoughItsCreditingTransactionExistsIsReportedAsAnArrivalAndAsTheLedger() {
        String sponsor = newSponsor(unique("s-cov-topup-arrival"));
        String topUpId = paidTopUp(sponsor, 2_000);
        assertThat(topUpItems(topUpId)).isEmpty();
        // The record says it never was paid (the database refuses the opposite: a paid top-up must be posted).
        execute("UPDATE qb_topup SET status = 'EXPIRED' WHERE topup_id = ?", topUpId);
        try {
            List<ReconChecks.Item> found = topUpItems(topUpId);
            assertThat(codes(found)).containsExactlyInAnyOrder(ReconChecks.TOPUP_LEDGER, ReconChecks.TOPUP_ARRIVALS);
            assertThat(found).filteredOn(item -> ReconChecks.TOPUP_ARRIVALS.equals(item.check())).singleElement()
                .satisfies(item -> {
                    assertThat(item.expected()).isEqualByComparingTo("0");
                    assertThat(item.actual()).isEqualByComparingTo("1");
                });
        } finally {
            execute("UPDATE qb_topup SET status = 'PAID' WHERE topup_id = ?", topUpId);
        }
        assertThat(topUpItems(topUpId)).isEmpty();
    }

    @Test
    void aStripeFeeRecordedButNotPostedIsReported() {
        String sponsor = newSponsor(unique("s-cov-topup-fee"));
        String topUpId = paidTopUp(sponsor, 3_000);
        assertThat(topUp(topUpId).get("stripe_fee")).isNull();
        assertThat(topUpItems(topUpId)).isEmpty();
        execute("UPDATE qb_topup SET stripe_fee = 108 WHERE topup_id = ?", topUpId);
        try {
            assertThat(topUpItems(topUpId)).singleElement().satisfies(item -> {
                assertThat(item.check()).isEqualTo(ReconChecks.TOPUP_FEE);
                assertThat(item.expected()).isEqualByComparingTo("108");
                assertThat(item.actual()).isEqualByComparingTo("0");
            });
        } finally {
            execute("UPDATE qb_topup SET stripe_fee = NULL WHERE topup_id = ?", topUpId);
        }
        assertThat(topUpItems(topUpId)).isEmpty();
    }

    @Test
    void aTakersAmountOnItsWayOutThatThe2130LinesDoNotHoldIsReportedOnceTwoRunsInARowFindIt() {
        String taker = payee(unique("t-cov-out"));
        earn(taker, 700);
        assertThat(rows("qb.system.recon-taker-wallets")).filteredOn(row -> taker.equals(row.get("partyId")))
            .isEmpty();
        execute("UPDATE qb_wallet SET pending_out = pending_out + 40 WHERE owner_id = ? AND kind = 'TAKER'", taker);
        try {
            List<Map<String, Object>> found = rows("qb.system.recon-taker-wallets");
            // The first run to find it only notes it (the cache follows the ledger by events) ...
            assertThat(ReconChecks.takers(found, Set.of()).items()).filteredOn(i -> taker.equals(i.partyId()))
                .singleElement().satisfies(item -> assertThat(item.check()).isEqualTo(ReconChecks.TAKER_WALLET_LAG));
            // ... the second, finding the same taker again, reports the difference.
            assertThat(ReconChecks.takers(found, Set.of(taker)).items()).filteredOn(i -> taker.equals(i.partyId()))
                .singleElement().satisfies(item -> {
                    assertThat(item.check()).isEqualTo(ReconChecks.TAKER_IN_TRANSFER);
                    assertThat(item.expected()).isEqualByComparingTo("0");
                    assertThat(item.actual()).isEqualByComparingTo("40");
                });
        } finally {
            execute("UPDATE qb_wallet SET pending_out = pending_out - 40 WHERE owner_id = ? AND kind = 'TAKER'",
                taker);
        }
        assertThat(rows("qb.system.recon-taker-wallets")).filteredOn(row -> taker.equals(row.get("partyId")))
            .isEmpty();
    }

    @Test
    void aSentTransfersRecordedReversalThatNoPostingMatchesIsReported() {
        param(QbParams.TRANSFER_FEE, "100");
        param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        transfersOn();
        String taker = payee(unique("t-cov-transfer"));
        enabledPayee(taker);
        earn(taker, 2_000);
        runTransfers();
        String transferId = (String) transfersOf(taker).getLast().get("transfer_id");
        runJobs();
        assertThat(transfer(transferId)).containsEntry("status", "SENT");
        assertThat(transferItems(transferId)).isEmpty();
        // Stripe's reversal recorded on the transfer but never posted.
        execute("UPDATE qb_transfer SET reversed_amount = 300 WHERE transfer_id = ?", transferId);
        try {
            assertThat(transferItems(transferId)).singleElement().satisfies(item -> {
                assertThat(item.check()).isEqualTo(ReconChecks.TRANSFER_LEDGER);
                assertThat(item.partyId()).isEqualTo(taker);
                // The amount (2,000) less the reversal; the ledger still holds the whole sending.
                assertThat(item.expected()).isEqualByComparingTo("1700");
                assertThat(item.actual()).isEqualByComparingTo("2000");
            });
        } finally {
            execute("UPDATE qb_transfer SET reversed_amount = 0 WHERE transfer_id = ?", transferId);
        }
        assertThat(transferItems(transferId)).isEmpty();
    }

    @Test
    void aCashBalanceThatAccount2160DoesNotHoldIsReportedForTheSponsorAndInAll() {
        param(QbParams.KUDOS_SELL_ENABLED, "true");
        String sponsor = newSponsor(unique("s-cov-cash"));
        credit(sponsor, 1_500);
        run(KudosExchanges.SELL, sponsorToken(sponsor), body("amount", 1_500));
        assertThat(cashItems(sponsor)).isEmpty();
        assertThat(codes(totals())).doesNotContain(ReconChecks.TOTAL_2160);
        execute("UPDATE qb_wallet SET balance = balance + 9 WHERE owner_id = ? AND kind = 'CASH'", sponsor);
        try {
            assertThat(cashItems(sponsor)).singleElement().satisfies(item -> {
                assertThat(item.check()).isEqualTo(ReconChecks.CASH_ACCOUNT);
                assertThat(item.expected()).isEqualByComparingTo("1500");
                assertThat(item.actual()).isEqualByComparingTo("1509");
            });
            assertThat(totals()).filteredOn(item -> ReconChecks.TOTAL_2160.equals(item.check())).singleElement()
                .satisfies(item -> assertThat(item.difference()).isEqualByComparingTo(new BigDecimal("9")));
        } finally {
            execute("UPDATE qb_wallet SET balance = balance - 9 WHERE owner_id = ? AND kind = 'CASH'", sponsor);
        }
        assertThat(cashItems(sponsor)).isEmpty();
        assertThat(codes(totals())).doesNotContain(ReconChecks.TOTAL_2160);
    }
}

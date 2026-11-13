package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.ReconChecks;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M14 a1: the fee side's four codes that no integration test made report ({@code FEES_RECEIVABLE},
 * {@code ENTRY_FEES_UNSETTLED}, {@code FEE_PAYMENTS}, {@code DISTRIBUTION_DEPOSITS}); the other eight of the plan's
 * twelve, and what was covered before, are in {@link ReconCoverageIT}. The fee fixtures and Stripe's stub cannot be in
 * one test class, hence two. The change is a posting through {@code LEDGER_POST} (as the fee tests' other fixtures
 * do) of the kind a defect would make, reversed afterwards through {@code LEDGER_REVERSE} (the ledger is never
 * changed), so that the check is asserted quiet before and after; each test has its own sponsor.
 */
class ReconCoverageFeeIT extends CreditNoteItSupport {

    String ledgerPost() {
        return TestTokens.bearer(tokens, "ledger-test", "ledger.post");
    }

    String ledgerReverse() {
        return TestTokens.bearer(tokens, "ledger-test", "ledger.reverse");
    }

    String systemToken() {
        return TestTokens.bearer(tokens, "recon-coverage-fee", QbPermissions.WALLET_SYSTEM);
    }

    /** Posts one balanced pair of lines; the party goes on the sponsor's lines, the other lines carry none. */
    String post(String sourceEntity, String sourceId, String debit, Map<String, String> debitDims, String credit,
        Map<String, String> creditDims, long amount) {
        Map<String, Object> input = body("bookingTime", clock.instant().toString(), "description",
            "Posted by hand (test)", "entries", List.of(
                body("accountCode", debit, "direction", "DEBIT", "amount", amount, "dimensions", debitDims),
                body("accountCode", credit, "direction", "CREDIT", "amount", amount, "dimensions", creditDims)));
        if (sourceEntity != null) {
            input.put("sourceEntity", sourceEntity);
            input.put("sourceId", sourceId);
        }
        return (String) run("LEDGER_POST", ledgerPost(), input).get("transactionId");
    }

    void reverse(String transactionId) {
        run("LEDGER_REVERSE", ledgerReverse(), body("transactionId", transactionId, "reason", "Reversed (test)"));
    }

    /** The items of one template, by the code the check reports and the party (the sponsor) it names. */
    List<ReconChecks.Item> found(String template, String check, String sponsor) {
        return ReconChecks.coded(items(template, systemToken(), Map.of("limit", 500))).stream()
            .filter(item -> check.equals(item.check()) && sponsor.equals(item.partyId())).toList();
    }

    @Test
    void feesReceivableThatNoInvoiceExplainsAreReported() {
        String sponsor = newSponsor(unique("s-cov-receivable"));
        assertThat(found("qb.system.recon-fee-receivable", ReconChecks.FEES_RECEIVABLE, sponsor)).isEmpty();
        // A fee posted as receivable without a charge or invoice behind it.
        String tx = post(null, null, "1130", Map.of("party", sponsor), "4120", Map.of(), 300);
        assertThat(found("qb.system.recon-fee-receivable", ReconChecks.FEES_RECEIVABLE, sponsor)).singleElement()
            .satisfies(item -> {
                assertThat(item.expected()).isEqualByComparingTo("0");
                assertThat(item.actual()).isEqualByComparingTo("300");
            });
        reverse(tx);
        assertThat(found("qb.system.recon-fee-receivable", ReconChecks.FEES_RECEIVABLE, sponsor)).isEmpty();
    }

    @Test
    void entryFeesUnsettledBelowZeroAreReported() {
        String sponsor = newSponsor(unique("s-cov-entry"));
        assertThat(found("qb.system.recon-entry-fees", ReconChecks.ENTRY_FEES_UNSETTLED, sponsor)).isEmpty();
        // More moved out of 2170 than was ever paid into it for the sponsor.
        String tx = post(null, null, "2170", Map.of("party", sponsor), "1110", Map.of(), 120);
        assertThat(found("qb.system.recon-entry-fees", ReconChecks.ENTRY_FEES_UNSETTLED, sponsor)).singleElement()
            .satisfies(item -> {
                assertThat(item.expected()).isEqualByComparingTo("0");
                assertThat(item.actual()).isEqualByComparingTo("-120");
            });
        reverse(tx);
        assertThat(found("qb.system.recon-entry-fees", ReconChecks.ENTRY_FEES_UNSETTLED, sponsor)).isEmpty();
    }

    @Test
    void aFeePostingThatTookFromTheAccountWithoutPartsIsReported() {
        param(QbParams.FEE_MONTHLY, "1000");
        String sponsor = newSponsor(unique("s-cov-fee-payments"));
        cash(sponsor, 5_000);
        // A real payment (1,100 from the cash account, with its parts) first: the check is on from then and quiet.
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        assertThat(query("SELECT 1 FROM qb_fee_payment_version WHERE sponsor_id = ?", sponsor)).isNotEmpty();
        assertThat(found("qb.system.recon-fee-credit", ReconChecks.FEE_PAYMENTS, sponsor)).isEmpty();
        // A later posting of a fee that took 200 more from 2160, though no part of it was written.
        clock.set(clock.instant().plus(java.time.Duration.ofMinutes(5)));
        Object chargeId = monthlyCharge(sponsor).get("charge_id");
        String tx = post("QbFeeCharge", String.valueOf(chargeId), "2160", Map.of("party", sponsor), "1130",
            Map.of("party", sponsor), 200);
        assertThat(found("qb.system.recon-fee-credit", ReconChecks.FEE_PAYMENTS, sponsor)).singleElement()
            .satisfies(item -> assertThat(item.difference()).isEqualByComparingTo("-200"));
        reverse(tx);
        assertThat(found("qb.system.recon-fee-credit", ReconChecks.FEE_PAYMENTS, sponsor)).isEmpty();
    }

    @Test
    void aDistributionDepositInTheLedgerThatNoPrepaymentHoldsIsReported() {
        String sponsor = newSponsor(unique("s-cov-distribution"));
        credit(sponsor, 1_000);
        String publicationId = livePublication(sponsor, "Cov distribution", Map.of("regions", List.of("GLOBAL")));
        assertThat(distribution(sponsor)).isEmpty();
        // 2180 for the purpose DISTRIBUTION (the third dimension) with no open prepayment behind it.
        String tx = post(null, null, "1110", Map.of(), "2180", Map.of("party", sponsor, "publication", publicationId,
            "purpose", "DISTRIBUTION"), 400);
        assertThat(distribution(sponsor)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("reference", publicationId);
            assertThat(new java.math.BigDecimal(String.valueOf(row.get("expected")))).isEqualByComparingTo("0");
            assertThat(new java.math.BigDecimal(String.valueOf(row.get("actual")))).isEqualByComparingTo("400");
        });
        reverse(tx);
        assertThat(distribution(sponsor)).isEmpty();
    }

    /** What the daily check of the fee account finds about the sponsor under DISTRIBUTION_DEPOSITS. */
    List<Map<String, Object>> distribution(String sponsor) {
        return differences(sponsor).stream()
            .filter(row -> ReconChecks.DISTRIBUTION_DEPOSITS.equals(row.get("checkCode"))).toList();
    }
}

package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.StubHttpServer;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Automatic transfers (U-53, P-03, requirement 5.3-14; docs/quizbuks/plans/Q6-payments.md, D-Q6-12): the daily job
 * transfers the whole balance of each taker at the threshold with an enabled account, once (the group looked up
 * before the creation, the transfer's own key); the fee goes to 4110; transfers off select nobody; large ones wait for a
 * review the payee cannot give; banned takers are skipped. The taker's cache follows the ledger.
 */
class TransferIT extends PayoutItSupport {

    @Test
    void theWholeBalanceIsTransferredOnceAndSettled() {
        param(QbParams.TRANSFER_FEE, "0");
        param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        transfersOn();
        String taker = payee("t-transfer");
        String accountId = enabledPayee(taker);
        earn(taker, 1500);
        assertTakerCacheIsTheLedger(taker);

        runTransfers();
        Map<String, Object> created = transfersOf(taker).getFirst();
        String transferId = (String) created.get("transfer_id");
        assertThat(created).containsEntry("status", "SENDING").containsEntry("external_account_id", accountId);
        assertThat(money(created.get("amount"))).isEqualByComparingTo("1500");
        assertThat(money(created.get("net"))).isEqualByComparingTo("1500");
        // Sending: out of 2120, into 2130; the cache with it.
        assertThat(ledger("2120", taker)).isEqualByComparingTo("0");
        assertThat(ledger("2130", taker)).isEqualByComparingTo("1500");
        assertTakerCacheIsTheLedger(taker);

        runJobs();
        // The group is looked up before the transfer is created, with the transfer's own key.
        List<StubHttpServer.Received> calls = transferCalls(transferId);
        assertThat(calls).extracting(StubHttpServer.Received::method).containsExactly("GET", "POST");
        assertThat(calls.getFirst().query()).contains("transfer_group=qb-" + transferId);
        StubHttpServer.Received post = calls.get(1);
        assertThat(post.header("Idempotency-Key")).isEqualTo("qb-transfer-" + transferId);
        assertThat(form(post.body())).containsEntry("amount", "1500").containsEntry("currency", "jpy")
            .containsEntry("destination", accountId).containsEntry("transfer_group", "qb-" + transferId)
            .containsEntry("metadata[transferId]", transferId);
        Map<String, Object> sent = transfer(transferId);
        assertThat(sent).containsEntry("status", "SENT");
        assertThat((String) sent.get("stripe_transfer_id")).startsWith("tr_test");
        assertThat(transferPostings(transferId)).extracting(l -> l.get("account_code") + " " + l.get("direction"))
            .containsExactly("2120 DEBIT", "2130 CREDIT", "2130 DEBIT", "1110 CREDIT");
        assertThat(ledger("2130", taker)).isEqualByComparingTo("0");
        assertTakerCacheIsTheLedger(taker);
        assertThat(outboxEvents("qb.transfer.sent", "transferId", transferId)).isEqualTo(1);
        assertThat(StripeSim.TRANSFERS.values()).filteredOn(t -> accountId.equals(t.get("destination"))).hasSize(1);

        // The next run finds nothing to send; the transfer appears in the taker's transactions.
        runTransfers();
        assertThat(transfersOf(taker)).hasSize(1);
        assertThat(items("qb.app.ledger", taker(taker), Map.of())).anySatisfy(row -> assertThat(row)
            .containsEntry("type", "TRANSFER").containsEntry("status", "COMPLETED"));
        assertBooksBalance();
    }

    @Test
    void theFeeGoesToTheRevenue() {
        param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        transfersOn();
        param(QbParams.TRANSFER_FEE, "100");
        try {
            String taker = payee("t-transfer-fee");
            enabledPayee(taker);
            earn(taker, 1500);
            runTransfers();
            runJobs();
            Map<String, Object> sent = transfersOf(taker).getFirst();
            assertThat(sent).containsEntry("status", "SENT");
            assertThat(money(sent.get("fee"))).isEqualByComparingTo("100");
            assertThat(money(sent.get("net"))).isEqualByComparingTo("1400");
            assertThat(form(transferCalls((String) sent.get("transfer_id")).get(1).body())).containsEntry("amount",
                "1400");
            assertThat(transferPostings((String) sent.get("transfer_id")))
                .anySatisfy(line -> assertThat(line).containsEntry("account_code", "4110")
                    .satisfies(l -> assertThat(money(l.get("amount"))).isEqualByComparingTo("100")));
            assertTakerCacheIsTheLedger(taker);
        } finally {
            param(QbParams.TRANSFER_FEE, "0");
        }
    }

    @Test
    void transfersOffSelectNobodyAndBelowTheThresholdWaits() {
        param(QbParams.TRANSFER_ENABLED, "false");
        String taker = payee("t-transfer-off");
        enabledPayee(taker);
        earn(taker, 5000);
        runTransfers();
        assertThat(transfersOf(taker)).isEmpty();
        transfersOn();
        String small = payee("t-transfer-small");
        enabledPayee(small);
        earn(small, 999);
        // Not enabled: no account, or one Stripe has not enabled.
        String pending = payee("t-transfer-pending");
        openPayoutAccount(pending);
        earn(pending, 5000);
        String none = payee("t-transfer-none");
        earn(none, 5000);
        runTransfers();
        assertThat(transfersOf(small)).isEmpty();
        assertThat(transfersOf(pending)).isEmpty();
        assertThat(transfersOf(none)).isEmpty();
        assertThat(transfersOf(taker)).hasSize(1);
    }

    @Test
    void aLargeTransferWaitsForAReviewThePayeeCannotGive() {
        param(QbParams.TRANSFER_FEE, "0");
        transfersOn();
        param(QbParams.TRANSFER_REVIEW_ABOVE, "1000");
        try {
            String taker = payee("t-transfer-review");
            enabledPayee(taker);
            earn(taker, 1500);
            runTransfers();
            Map<String, Object> review = transfersOf(taker).getFirst();
            String transferId = (String) review.get("transfer_id");
            assertThat(review).containsEntry("status", "REVIEW");
            String requestId = (String) review.get("approval_request_id");
            assertThat(requestId).isNotNull();
            // Nothing posted while in review; nothing sent.
            assertThat(transferPostings(transferId)).isEmpty();
            assertThat(ledger("2120", taker)).isEqualByComparingTo("1500");
            // The payee, even holding the review permission, cannot approve their own transfer.
            String selfReviewer = TestTokens.bearer(tokens, taker, "qb.payout.review", "approval.decide");
            assertThat(codes(refused("APPROVAL_DECIDE", selfReviewer, body("requestId", requestId, "decision",
                "APPROVE"), 422))).containsExactly("APPROVAL_OWN_REQUEST");
            // Another one approves: sent.
            run("APPROVAL_DECIDE", transferReviewer(), body("requestId", requestId, "decision", "APPROVE"));
            deliverEvents();
            assertThat(transfer(transferId)).containsEntry("status", "SENDING");
            runJobs();
            assertThat(transfer(transferId)).containsEntry("status", "SENT");
            assertTakerCacheIsTheLedger(taker);

            // A rejected one: nothing posted, the balance stays.
            String other = payee("t-transfer-reject");
            enabledPayee(other);
            earn(other, 2000);
            runTransfers();
            Map<String, Object> second = transfersOf(other).getFirst();
            run("APPROVAL_DECIDE", transferReviewer(), body("requestId", second.get("approval_request_id"),
                "decision", "REJECT", "reason", "Check the account first."));
            deliverEvents();
            // The reviewer's note stays with the approval (finance's); the taker's own record has the code only.
            assertThat(transfer((String) second.get("transfer_id"))).containsEntry("status", "REJECTED")
                .containsEntry("failure_code", "REVIEW_REJECTED").containsEntry("failure_message", null);
            assertThat(rows(com.jabiz.quizbuks.wallet.QbPayouts.MY_TRANSFERS, taker(other)))
                .singleElement().satisfies(row -> assertThat(row.values()).doesNotContain("Check the account first."));
            assertThat(transferPostings((String) second.get("transfer_id"))).isEmpty();
            assertThat(ledger("2120", other)).isEqualByComparingTo("2000");
            assertBooksBalance();

            // Held: the same balance is not queued again until finance releases the taker.
            runTransfers();
            assertThat(transfersOf(other)).hasSize(1);
            Map<?, ?> held = (Map<?, ?>) toMap(systemRun("QB_TRANSFER_CREATE", body("userId", other)));
            assertThat(held.get("reason")).isEqualTo("HELD");
            // Not by the taker.
            assertThat(client.post().uri("/api/processes/QB_TRANSFER_HOLD_RELEASE/latest")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header(org.springframework.http.HttpHeaders.AUTHORIZATION, taker(other))
                .bodyValue(body("userId", other)).exchange().returnResult(String.class).getStatus().value())
                .isEqualTo(403);
            // Nor by a payee who holds the permission: four eyes, as with the review.
            assertThat(codes(refused("QB_TRANSFER_HOLD_RELEASE", tokenOf(other, QbRoles.ADMIN_FINANCE),
                body("userId", other), 422))).containsExactly("QB_TRANSFER_RELEASE_OWN");
            Map<String, Object> released = releaseHold(other);
            assertThat(released).containsEntry("transferId", second.get("transfer_id"));
            assertThat(transfer((String) second.get("transfer_id")).get("released_by")).isNotNull();
            // Released once: a second release finds nothing held.
            String finance = userWithRole(unique("finance"), QbRoles.ADMIN_FINANCE);
            assertThat(codes(refused("QB_TRANSFER_HOLD_RELEASE", tokenOf(finance, QbRoles.ADMIN_FINANCE),
                body("userId", other), 422))).containsExactly("QB_TRANSFER_NOT_HELD");
            runTransfers();
            assertThat(transfersOf(other)).hasSize(2);
            assertThat(transfersOf(other).getLast()).containsEntry("status", "REVIEW");
        } finally {
            param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        }
    }

    @Test
    void anApprovedTransferThatCanNoLongerBeSentIsRejected() {
        param(QbParams.TRANSFER_FEE, "0");
        transfersOn();
        param(QbParams.TRANSFER_REVIEW_ABOVE, "1000");
        try {
            String taker = payee("t-transfer-late-ban");
            String accountId = enabledPayee(taker);
            earn(taker, 1500);
            runTransfers();
            Map<String, Object> review = transfersOf(taker).getFirst();
            String boss = userWithRole(unique("boss"), QbRoles.ADMIN_SUPER);
            run("QB_USER_BAN", tokenOf(boss, QbRoles.ADMIN_SUPER), body("userId", taker, "reason", "Fraud"));
            run("APPROVAL_DECIDE", transferReviewer(), body("requestId", review.get("approval_request_id"),
                "decision", "APPROVE"));
            deliverEvents();
            assertThat(transfer((String) review.get("transfer_id"))).containsEntry("status", "REJECTED")
                .containsEntry("failure_code", "NOT_SENDABLE");
            assertThat(ledger("2120", taker)).isEqualByComparingTo("1500");
            assertThat(StripeSim.TRANSFERS.values()).noneMatch(t -> accountId.equals(t.get("destination")));
        } finally {
            param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        }
    }

    @Test
    void bannedTakersAreSkipped() {
        param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        transfersOn();
        String taker = payee("t-transfer-banned");
        enabledPayee(taker);
        earn(taker, 3000);
        String boss = userWithRole(unique("boss"), QbRoles.ADMIN_SUPER);
        run("QB_USER_BAN", tokenOf(boss, QbRoles.ADMIN_SUPER), body("userId", taker, "reason", "Fraud"));
        runTransfers();
        assertThat(transfersOf(taker)).isEmpty();
        // Even asked directly (as the job would), the process skips them.
        Map<?, ?> output = (Map<?, ?>) toMap(systemRun("QB_TRANSFER_CREATE", body("userId", taker)));
        assertThat(output.get("reason")).isEqualTo("BANNED");
        assertThat(transfersOf(taker)).isEmpty();
        assertThat(ledger("2120", taker)).isEqualByComparingTo(BigDecimal.valueOf(3000));
    }

    @SuppressWarnings("unchecked")
    Object toMap(Object value) {
        return json.convertValue(value, Map.class);
    }
}

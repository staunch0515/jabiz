package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.BankAccounts;
import com.jabiz.quizbuks.wallet.TransferRules;
import com.jabiz.quizbuks.wallet.WalletCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Withdrawals to a bank account (docs/quizbuks/plans/Q13-bank-transfer.md, Q13b): the taker's account (normalised,
 * masked, one user per account, a cooling-off period after each change), how they are paid, the request (always
 * reviewed, never by the payee), finance's payment by hand (2130 to 1120, each statement entry once) or the bank's
 * return (reversed), and that the daily Stripe run and the hourly check leave bank withdrawals alone.
 */
class BankWithdrawalIT extends PayoutItSupport {

    private static final AtomicInteger NUMBERS = new AtomicInteger(1000);
    private static final String TRANSFERS = "urn:jabiz:dataset:default:QbTransfer";

    @BeforeEach
    void bankWithdrawalsOn() {
        param(QbParams.WITHDRAWAL_BANK_ENABLED, "true");
        param(QbParams.WITHDRAWAL_BANK_FEE, "200");
        param(QbParams.TRANSFER_THRESHOLD, "1000");
    }

    String payer() {
        return tokenOf(user("q13b-payer"), QbRoles.ADMIN_FINANCE);
    }

    Map<String, Object> account(String number) {
        return body("bankCode", "０００１", "bankName", "みずほ銀行", "branchCode", "001", "branchName", "東京営業部",
            "accountType", "ORDINARY", "bankAccountNumber", number, "accountHolder", "ﾔﾏﾀﾞ ﾀﾛｳ");
    }

    String number() {
        return String.valueOf(NUMBERS.incrementAndGet());
    }

    /** A taker paid by bank with an account past its cooling-off period and {@code balance} Kudos. */
    String bankTaker(String name, long balance) {
        String taker = payee(name);
        run("QB_BANK_ACCOUNT_SAVE", taker(taker), account(number()));
        run("QB_PAYOUT_METHOD_SET", taker(taker), body("method", "BANK"));
        advance(Duration.ofHours(73));
        earn(taker, balance);
        return taker;
    }

    Map<String, Object> request(String taker, long amount) {
        return run("QB_WITHDRAWAL_REQUEST", taker(taker), body("amount", amount));
    }

    void approve(String transferId) {
        run("APPROVAL_DECIDE", transferReviewer(), body("requestId", transfer(transferId).get("approval_request_id"),
            "decision", "APPROVE"));
        deliverEvents();
    }

    @Test
    void theAccountIsNormalisedMaskedAndOneUsersOnly() {
        String taker = payee("t-q13b-account");
        String number = number();
        Map<String, Object> saved = run("QB_BANK_ACCOUNT_SAVE", taker(taker), account(number));
        assertThat(saved).containsEntry("saved", true);
        Map<String, Object> row = query("SELECT * FROM qb_bank_account WHERE user_id = ?", taker).getFirst();
        assertThat(row).containsEntry("bank_code", "0001").containsEntry("account_number", "000" + number)
            .containsEntry("account_holder", "ヤマダ タロウ").containsEntry("payout_method", "STRIPE");
        // Theirs to read, the number masked; the input never kept in the clear.
        assertThat(String.valueOf(itemsOf(entitiesOf("urn:jabiz:dataset:taker:QbBankAccount", taker(taker)))))
            .contains("***").doesNotContain("000" + number);
        Map<String, Object> app = items("qb.app.bank-account", taker(taker), Map.of()).getFirst();
        assertThat(app).containsEntry("enabled", true).containsEntry("payoutMethod", "STRIPE")
            .containsEntry("accountHolder", "ヤマダ タロウ");
        assertThat(String.valueOf(app.get("accountNumber"))).doesNotContain(number);
        assertThat(query("SELECT input_summary::text AS s FROM op_process WHERE process_name = 'QB_BANK_ACCOUNT_SAVE' "
            + "AND actor_id = ?", taker).toString()).contains("bankAccountNumber").doesNotContain(number);

        // Saved again unchanged: the cooling-off period is not started again.
        Object changedAt = row.get("changed_at");
        advance(Duration.ofHours(1));
        run("QB_BANK_ACCOUNT_SAVE", taker(taker), account(number));
        assertThat(query("SELECT changed_at FROM qb_bank_account WHERE user_id = ?", taker).getFirst())
            .containsEntry("changed_at", changedAt);

        // Not valid: 422 field by field.
        Map<String, Object> bad = account("12345678");
        bad.put("accountHolder", "Yamada Taro");
        assertThat(codes(refused("QB_BANK_ACCOUNT_SAVE", taker(taker), bad, 422)))
            .containsOnly(WalletCodes.BANK_ACCOUNT_INVALID).hasSize(2);

        // Another user's account: not saved, finance told.
        String other = payee("t-q13b-account-other");
        Map<String, Object> taken = run("QB_BANK_ACCOUNT_SAVE", taker(other), account(number));
        assertThat(taken).containsEntry("saved", false).containsEntry("reason", WalletCodes.BANK_ACCOUNT_IN_USE);
        assertThat(query("SELECT * FROM qb_bank_account WHERE user_id = ?", other)).isEmpty();
        assertThat(openTasks("bank-account-in-use:" + other + ":")).singleElement()
            .satisfies(t -> assertThat(t).containsEntry("task_type", "qb.bank-account-in-use"));
    }

    @Test
    void paidByBankOnlyWithAnAccountAndNothingUnderWay() {
        String taker = payee("t-q13b-method");
        assertThat(codes(refused("QB_PAYOUT_METHOD_SET", taker(taker), body("method", "BANK"), 422)))
            .containsExactly(WalletCodes.BANK_ACCOUNT_MISSING);
        run("QB_BANK_ACCOUNT_SAVE", taker(taker), account(number()));
        assertThat(run("QB_PAYOUT_METHOD_SET", taker(taker), body("method", "BANK"))).containsEntry("method", "BANK");
        // A withdrawal under way: the method stays until it is finished.
        advance(Duration.ofHours(73));
        earn(taker, 5000);
        request(taker, 3000);
        assertThat(codes(refused("QB_PAYOUT_METHOD_SET", taker(taker), body("method", "STRIPE"), 422)))
            .containsExactly(WalletCodes.PAYOUT_METHOD_OPEN_TRANSFER);
    }

    @Test
    void theRequestWaitsForTheCoolingOffAndKeepsToTheAmounts() {
        String taker = payee("t-q13b-limits");
        run("QB_BANK_ACCOUNT_SAVE", taker(taker), account(number()));
        earn(taker, 5000);
        // Paid by Stripe: no bank withdrawal.
        assertThat(String.valueOf(refused("QB_WITHDRAWAL_REQUEST", taker(taker), body("amount", 2000), 422)))
            .contains(BankAccounts.NOT_BANK);
        run("QB_PAYOUT_METHOD_SET", taker(taker), body("method", "BANK"));
        Map<String, Object> cooling = refused("QB_WITHDRAWAL_REQUEST", taker(taker), body("amount", 2000), 422);
        assertThat(codes(cooling)).containsExactly(WalletCodes.WITHDRAWAL_UNAVAILABLE);
        assertThat(String.valueOf(cooling)).contains(BankAccounts.COOLDOWN);
        advance(Duration.ofHours(73));
        assertThat(codes(refused("QB_WITHDRAWAL_REQUEST", taker(taker), body("amount", 999), 422)))
            .containsExactly(WalletCodes.WITHDRAWAL_AMOUNT);
        assertThat(codes(refused("QB_WITHDRAWAL_REQUEST", taker(taker), body("amount", 5001), 422)))
            .containsExactly(WalletCodes.WITHDRAWAL_AMOUNT);
        // Off: nothing.
        param(QbParams.WITHDRAWAL_BANK_ENABLED, "false");
        try {
            assertThat(String.valueOf(refused("QB_WITHDRAWAL_REQUEST", taker(taker), body("amount", 2000), 422)))
                .contains(BankAccounts.OFF);
        } finally {
            param(QbParams.WITHDRAWAL_BANK_ENABLED, "true");
        }
        assertThat(transfersOf(taker)).isEmpty();
        assertThat(ledger("2120", taker)).isEqualByComparingTo("5000");
    }

    @Test
    void approvedThenPaidByHand() {
        String taker = bankTaker("t-q13b-paid", 5000);
        Map<String, Object> requested = request(taker, 3000);
        assertThat(requested).containsEntry("status", "REVIEW");
        assertThat(requested.get("approvalRequestId")).isNotNull();
        String transferId = (String) requested.get("transferId");
        assertThat(transfer(transferId)).containsEntry("method", "BANK").containsEntry("fee", new java.math.BigDecimal(
            "200")).containsEntry("account_holder", "ヤマダ タロウ");
        // Nothing posted while it waits; the payee cannot approve it.
        assertThat(ledger("2120", taker)).isEqualByComparingTo("5000");
        call("APPROVAL_DECIDE", tokenOf(taker, QbRoles.ADMIN_FINANCE), body("requestId",
            requested.get("approvalRequestId"), "decision", "APPROVE")).expectStatus().is4xxClientError();

        // The daily run and the hourly check leave it alone.
        transfersOn();
        try {
            runTransfers();
            approve(transferId);
            assertThat(transfer(transferId)).containsEntry("status", "SENDING");
            advance(Duration.ofHours(2));
            runStaleTransfers(now());
            assertThat(transfer(transferId)).containsEntry("status", "SENDING")
                .containsEntry("check_requested_at", null);
        } finally {
            param(QbParams.TRANSFER_ENABLED, "false");
        }
        assertThat(transfersOf(taker)).hasSize(1);
        assertThat(transferCalls(transferId)).isEmpty();
        assertThat(ledger("2120", taker)).isEqualByComparingTo("2000");
        assertThat(ledger("2130", taker)).isEqualByComparingTo("2800");
        assertThat(openTasks("withdrawal:" + transferId + ":pay")).hasSize(1);

        // Finance reveals the number (recorded) and pays; not their own, each statement entry once.
        assertThat(reveal(transferId, payer())).isEqualTo(transfer(transferId).get("account_number"));
        revealRefused(transferId, tokenOf(user("q13b-content"), QbRoles.ADMIN_CONTENT));
        LocalDate today = LocalDate.ofInstant(now(), java.time.ZoneId.of("Asia/Tokyo"));
        Map<String, Object> paid = run("QB_WITHDRAWAL_PAID", payer(), body("transferId", transferId, "paidOn",
            today.toString(), "paidReference", " pay 0001 "));
        assertThat(paid).containsEntry("status", "SENT");
        assertThat(transfer(transferId)).containsEntry("paid_reference", "PAY0001");
        assertThat(ledger("2130", taker)).isEqualByComparingTo("0");
        assertThat(openTasks("withdrawal:" + transferId + ":pay")).isEmpty();
        assertTakerCacheIsTheLedger(taker);
        assertBooksBalance();

        String second = bankTaker("t-q13b-paid-2", 5000);
        String secondId = (String) request(second, 2000).get("transferId");
        approve(secondId);
        assertThat(codes(refused("QB_WITHDRAWAL_PAID", payer(), body("transferId", secondId, "paidOn",
            today.toString(), "paidReference", "PAY-0001".replace("-", "")), 422)))
            .containsExactly(WalletCodes.WITHDRAWAL_REFERENCE_USED);
        assertThat(codes(refused("QB_WITHDRAWAL_PAID", tokenOf(second, QbRoles.ADMIN_FINANCE), body("transferId",
            secondId, "paidOn", today.toString(), "paidReference", "PAY0002"), 422)))
            .containsExactly(WalletCodes.WITHDRAWAL_OWN);
        refused("QB_WITHDRAWAL_PAID", tokenOf(user("q13b-content"), QbRoles.ADMIN_CONTENT), body("transferId",
            secondId, "paidOn", today.toString(), "paidReference", "PAY0002"), 403);
    }

    @Test
    void returnedByTheBank() {
        String taker = bankTaker("t-q13b-returned", 4000);
        String transferId = (String) request(taker, 4000).get("transferId");
        approve(transferId);
        Map<String, Object> returned = run("QB_WITHDRAWAL_RETURNED", payer(), body("transferId", transferId,
            "reason", "Account closed"));
        assertThat(returned).containsEntry("status", "FAILED");
        assertThat(transfer(transferId)).containsEntry("failure_code", "BANK_RETURNED")
            .containsEntry("failure_message", "Account closed");
        assertThat(ledger("2120", taker)).isEqualByComparingTo("4000");
        assertThat(ledger("2130", taker)).isEqualByComparingTo("0");
        assertTakerCacheIsTheLedger(taker);
        assertBooksBalance();
        // Not twice.
        assertThat(codes(refused("QB_WITHDRAWAL_RETURNED", payer(), body("transferId", transferId,
            "reason", "Again"), 422))).containsExactly(WalletCodes.WITHDRAWAL_STATE);
    }

    @Test
    void aRejectionHoldsTheTaker() {
        String taker = bankTaker("t-q13b-rejected", 3000);
        String transferId = (String) request(taker, 2000).get("transferId");
        run("APPROVAL_DECIDE", transferReviewer(), body("requestId", transfer(transferId).get("approval_request_id"),
            "decision", "REJECT", "reason", "Check the account"));
        deliverEvents();
        assertThat(transfer(transferId)).containsEntry("status", "REJECTED");
        assertThat(ledger("2120", taker)).isEqualByComparingTo("3000");
        assertThat(String.valueOf(refused("QB_WITHDRAWAL_REQUEST", taker(taker), body("amount", 2000), 422)))
            .contains(BankAccounts.HELD);
    }

    @Test
    void theDailyRunSkipsATakerPaidByBank() {
        String taker = bankTaker("t-q13b-daily", 3000);
        enabledPayee(taker);
        String finance = tokenOf(user("q13b-finance-preview"), QbRoles.ADMIN_FINANCE);
        assertThat(run("QB_TRANSFER_PREVIEW", finance, body("userId", taker)))
            .containsEntry("skip", TransferRules.BANK_METHOD);
        transfersOn();
        try {
            runTransfers();
        } finally {
            param(QbParams.TRANSFER_ENABLED, "false");
        }
        assertThat(transfersOf(taker)).isEmpty();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private Map<String, Object> entitiesOf(String dataset, String token) {
        return client.post().uri("/api/datasets/" + dataset + "/query")
            .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
    }

    private Object reveal(String transferId, String token) {
        return client.post().uri("/api/datasets/" + TRANSFERS + "/reveal")
            .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("id", transferId, "field", "accountNumber")).exchange().expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody().get("value");
    }

    private void revealRefused(String transferId, String token) {
        client.post().uri("/api/datasets/" + TRANSFERS + "/reveal")
            .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("id", transferId, "field", "accountNumber")).exchange().expectStatus().isForbidden();
    }

    private static List<Map<String, Object>> openTasks(String source) {
        return query("SELECT task_type, source_key FROM (SELECT DISTINCT ON (task_id) * FROM sys_task_version "
            + "ORDER BY task_id, version_no DESC) t WHERE status = 'OPEN' AND NOT is_deleted AND source_key LIKE ?",
            source + "%");
    }
}

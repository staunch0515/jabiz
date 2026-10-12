package com.jabiz.quizbuks.it;

import com.jabiz.inbound.StripeSignature;
import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payout integration tests (docs/quizbuks/plans/Q6-payments.md, Q6b): as the top-up tests, with Stripe's Connect and
 * transfer endpoints simulated by {@link StripeSim}, the Connect endpoint's events signed with its own test secret,
 * and helpers for takers with an enabled payout account and a balance.
 */
abstract class PayoutItSupport extends TopUpItSupport {

    static final String CONNECT_SECRET = "whsec_test_only_quizbuks_connect_0123456789";
    static final String CONNECT_INBOUND = "/api/inbound/stripe-connect";

    @Autowired
    @Qualifier("qbTransferRunJob")
    JobDefinition<?> transferRunJob;

    @Autowired
    @Qualifier("qbTransferStaleJob")
    JobDefinition<?> transferStaleJob;

    // ---- takers and their accounts ---------------------------------------------------------------------------------

    /** A taker of the test's own in Japan, where payout accounts are opened. */
    String payee(String name) {
        return newTaker(name, "JP");
    }

    /** Opens the taker's payout account: the onboarding and its job against the simulation. */
    Map<String, Object> openPayoutAccount(String userId) {
        Map<String, Object> output = run("QB_PAYOUT_ONBOARD", taker(userId), body());
        runJobs();
        return output;
    }

    Map<String, Object> payoutAccount(String userId) {
        List<Map<String, Object>> rows = query("SELECT * FROM qb_payout_account WHERE user_id = ?", userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    String stripeAccountOf(String userId) {
        return (String) payoutAccount(userId).get("external_account_id");
    }

    /** Stripe's account as {@code account.updated} reports it. */
    Map<String, Object> account(String accountId, String userId, boolean enabled) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", accountId);
        account.put("object", "account");
        account.put("country", "JP");
        account.put("payouts_enabled", enabled);
        account.put("details_submitted", true);
        account.put("capabilities", Map.of("transfers", enabled ? "active" : "pending"));
        account.put("requirements", Map.of("currently_due", enabled ? List.of() : List.of("individual.id_number")));
        account.put("metadata", Map.of("userId", userId));
        account.put("livemode", false);
        return account;
    }

    /** A Connect event about an account (the event names the account too). */
    Map<String, Object> connectEvent(String type, String accountId, Map<String, Object> object) {
        Map<String, Object> event = event(eventId(), type, object);
        event.put("account", accountId);
        return event;
    }

    /** Posts a signed event to the Connect endpoint and delivers it. */
    void deliverConnect(Map<String, Object> event) {
        byte[] body = json.writeValueAsBytes(event);
        String signature = new StripeSignature().sign(CONNECT_SECRET, clock.instant().getEpochSecond(), body)
            .get(StripeSignature.HEADER);
        client.post().uri(CONNECT_INBOUND).contentType(MediaType.APPLICATION_JSON)
            .header(StripeSignature.HEADER, signature).bodyValue(body).exchange().expectStatus().isOk();
        deliverEvents();
    }

    /** A taker with an enabled payout account; returns their Stripe account. */
    String enabledPayee(String userId) {
        openPayoutAccount(userId);
        String accountId = stripeAccountOf(userId);
        deliverConnect(connectEvent("account.updated", accountId, account(accountId, userId, true)));
        assertThat(payoutAccount(userId)).containsEntry("status", "ENABLED");
        return accountId;
    }

    /**
     * Gives a taker rewards worth {@code amount} straight in the ledger (debit 1110 / credit 2120 for them), as many
     * paid rewards would, and syncs their wallet as the reward's event would.
     */
    void earn(String userId, long amount) {
        run("LEDGER_POST", TestTokens.bearer(tokens, "ledger-test", "ledger.post"), body(
            "description", "Rewards (test)", "entries", List.of(
                body("accountCode", "1110", "direction", "DEBIT", "amount", amount),
                body("accountCode", "2120", "direction", "CREDIT", "amount", amount, "dimensions",
                    Map.of("party", userId)))));
        systemRun("QB_TAKER_WALLET_SYNC", body("userId", userId));
    }

    // ---- parameters and jobs ---------------------------------------------------------------------------------------

    /** Sets a controlled parameter (four eyes). */
    void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "Test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    void transfersOn() {
        param(QbParams.TRANSFER_ENABLED, "true");
    }

    /** Runs the daily transfer job a minute from now, and the events it causes. */
    JobRunner.JobRun runTransfers() {
        // Each run its own scheduled time (a time runs once): a minute later than the last.
        advance(java.time.Duration.ofMinutes(1));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        JobRunner.JobRun run = jobRunner.execute(transferRunJob, now());
        deliverEvents();
        return run;
    }

    JobRunner.JobRun runStaleTransfers(Instant at) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        JobRunner.JobRun run = jobRunner.execute(transferStaleJob, at);
        deliverEvents();
        return run;
    }

    /**
     * Hands a check job's result to {@code QB_TRANSFER_CHECKED} as the platform would, whenever the test chooses
     * (completions come in any order with the transfer's other news).
     */
    @SuppressWarnings("unchecked")
    Object completeCheck(String transferId, com.jabiz.quizbuks.wallet.PayoutObjects.TransferLookup lookup) {
        var definition = (com.jabiz.process.ProcessDefinition<Object, Object, com.jabiz.process.ProcessContext>)
            processes.findLatest("QB_TRANSFER_CHECKED").orElseThrow();
        Object input = new com.jabiz.external.ExternalJobCompletion("job-" + transferId,
            com.jabiz.quizbuks.wallet.PayoutJobs.TRANSFER_CHECK, "system", transferId, lookup, Map.of(), 1, now());
        Object output = asRequest(com.jabiz.context.RequestContext.system(java.util.Locale.ENGLISH, "qb-it"),
            executor.execute(definition, input)).block();
        deliverEvents();
        return output;
    }

    /** Releases a taker held after a rejected or reversed transfer, as a finance administrator. */
    Map<String, Object> releaseHold(String userId) {
        String finance = userWithRole(unique("finance"), com.jabiz.quizbuks.setup.QbRoles.ADMIN_FINANCE);
        return run("QB_TRANSFER_HOLD_RELEASE", tokenOf(finance, com.jabiz.quizbuks.setup.QbRoles.ADMIN_FINANCE),
            body("userId", userId));
    }

    // ---- reading ---------------------------------------------------------------------------------------------------

    /** The calls about one transfer (its group's lookups and creations), in order: other takers' runs aside. */
    static List<com.jabiz.runtime.test.StubHttpServer.Received> transferCalls(String transferId) {
        String group = "transfer_group=qb-" + transferId;
        return STRIPE.requests("/v1/transfers").stream().filter(r -> ("GET".equals(r.method())
            && r.query() != null && r.query().contains(group)) || ("POST".equals(r.method())
            && r.body().contains(group))).toList();
    }

    List<Map<String, Object>> transfersOf(String userId) {
        return query("SELECT * FROM qb_transfer WHERE user_id = ? ORDER BY created_at, transfer_id", userId);
    }

    Map<String, Object> transfer(String transferId) {
        return query("SELECT * FROM qb_transfer WHERE transfer_id = ?", transferId).getFirst();
    }

    /** The lines of the transactions whose source is the transfer, in order. */
    static List<Map<String, Object>> transferPostings(String transferId) {
        return query("SELECT t.transaction_id, t.description, a.account_code, e.direction, e.amount, e.dimension_1 "
            + "FROM ledger_transaction_version t JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id "
            + "JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE t.source_entity = 'QbTransfer' AND t.source_id = ? ORDER BY t.process_seq_id, e.line_no",
            transferId);
    }

    /** A taker's wallet row, or null. */
    Map<String, Object> takerWallet(String userId) {
        List<Map<String, Object>> rows = query("SELECT * FROM qb_wallet WHERE owner_id = ? AND kind = 'TAKER'",
            userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** The taker's balance (2120) and on its way out (2130) in the ledger, and that the cache agrees. */
    void assertTakerCacheIsTheLedger(String userId) {
        BigDecimal available = ledger("2120", userId);
        BigDecimal out = ledger("2130", userId);
        Map<String, Object> wallet = takerWallet(userId);
        assertThat(wallet == null ? BigDecimal.ZERO : money(wallet.get("balance"))).as("balance of " + userId)
            .isEqualByComparingTo(available);
        assertThat(wallet == null ? BigDecimal.ZERO : money(wallet.get("pending_out"))).as("out of " + userId)
            .isEqualByComparingTo(out);
    }

    /** A finance administrator who reviews transfers. */
    String transferReviewer() {
        return TestTokens.bearer(tokens, "transfer-reviewer", "qb.payout.review", "qb.admin.finance.read",
            "approval.decide", "task.read");
    }
}

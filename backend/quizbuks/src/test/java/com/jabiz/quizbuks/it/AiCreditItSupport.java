package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ai.AiCodes;
import com.jabiz.quizbuks.content.ai.AiProcesses;
import com.jabiz.quizbuks.content.ai.QbAi;
import com.jabiz.quizbuks.fee.AiCredits;
import com.jabiz.quizbuks.fee.AiFeeRules;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.KudosExchanges;
import com.jabiz.runtime.test.StubHttpServer;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M9: what the AI credit's ITs share: the fee on in each test and off after it, money in the account, purchases,
 * the credit and the ledger as they read them.
 */
abstract class AiCreditItSupport extends AiItSupport {

    static final BigDecimal MARKUP = new BigDecimal("1.5");
    static final BigDecimal RATE = new BigDecimal("150");
    static final BigDecimal TAX = new BigDecimal("0.10");

    @BeforeEach
    void freshDay() {
        aiOn();
        // Each test class has a schema of its own: Kudos selling is turned on once in each.
        if (!"true".equals(latest(QbParams.KUDOS_SELL_ENABLED))) {
            param(QbParams.KUDOS_SELL_ENABLED, "true");
        }
        advance(Duration.ofDays(1));
    }

    /** A parameter's latest stored value; null when it has none. */
    static String latest(String key) {
        List<Map<String, Object>> rows = query("SELECT param_value FROM sys_param_version WHERE param_key = ? "
            + "ORDER BY effect_start_time DESC, version_no DESC LIMIT 1", key);
        return rows.isEmpty() ? null : String.valueOf(rows.getFirst().get("param_value"));
    }

    @AfterEach
    void feeOff() {
        if (feeIs("true")) {
            param(QbParams.AI_FEE_ENABLED, "false");
        }
    }

    static boolean feeIs(String value) {
        List<Map<String, Object>> rows = query("SELECT param_value FROM sys_param_version WHERE param_key = ? "
            + "ORDER BY effect_start_time DESC, version_no DESC LIMIT 1", QbParams.AI_FEE_ENABLED);
        return !rows.isEmpty() && value.equals(String.valueOf(rows.getFirst().get("param_value")));
    }

    void feeOn() {
        if (!feeIs("true")) {
            param(QbParams.AI_FEE_ENABLED, "true");
        }
    }

    static String title() {
        return "AI credit " + UUID.randomUUID();
    }

    /** Money in the sponsor's cash account: Kudos credited and sold back. */
    void cash(String sponsor, long amount) {
        credit(sponsor, amount);
        run(KudosExchanges.SELL, sponsorToken(sponsor), body("amount", amount));
    }

    /** Fee credit as F-M2 grants it (5140 / 2190, the lot, the wallet's cache), expiring in a year. */
    String grant(String sponsor, long amount) {
        String grantId = UUID.randomUUID().toString();
        Instant expiresAt = clock.instant().plus(Duration.ofDays(365));
        execute("INSERT INTO qb_fee_credit_grant (grant_id, sponsor_id, source, source_key, amount, remaining, "
            + "voided_amount, granted_at, expires_at, version) VALUES (?, ?, 'MANUAL', ?, ?, ?, 0, ?, ?, 1)", grantId,
            sponsor, "test:" + grantId, amount, amount, Timestamp.from(clock.instant()), Timestamp.from(expiresAt));
        run("LEDGER_POST", TestTokens.bearer(tokens, "ledger-test", "ledger.post"), body("bookingTime",
            clock.instant().toString(), "description", "Granted credit (test)", "sourceEntity", "QbFeeCreditGrant",
            "sourceId", grantId, "entries", List.of(
                body("accountCode", "5140", "direction", "DEBIT", "amount", amount, "dimensions",
                    Map.of("party", sponsor)),
                body("accountCode", "2190", "direction", "CREDIT", "amount", amount, "dimensions",
                    Map.of("party", sponsor)))));
        execute("INSERT INTO qb_wallet (wallet_id, owner_id, kind, balance, pending_count, pending_amount, pending_out, "
            + "version) VALUES (?, ?, 'FEE_CREDIT', ?, 0, 0, 0, 1) ON CONFLICT (owner_id, kind) DO UPDATE SET "
            + "balance = qb_wallet.balance + EXCLUDED.balance, version = qb_wallet.version + 1",
            UUID.randomUUID().toString(), sponsor, amount);
        return grantId;
    }

    /** Sets the least purchase (not created by the setup: the first change gives its kind). */
    void minPurchase(String value) {
        yenParam(QbParams.AI_CREDIT_MIN_PURCHASE, value);
    }

    /** Sets an optional yen parameter of the AI credit (min-purchase, low-amount: the first change gives its kind). */
    void yenParam(String key, String value) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("paramKey", key);
        values.put("value", value);
        if (query("SELECT 1 FROM sys_param_version WHERE param_key = ?", key).isEmpty()) {
            values.put("valueKind", QbParams.AI_CREDIT_MIN_PURCHASE_KIND);
        }
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", values, "reason", "F-M9 test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    /** The sponsor with AI credit enough for a few requests, bought once (cash, then the purchase). */
    String funded(String sponsor) {
        if (aiCredit(sponsor).isEmpty()) {
            cash(sponsor, 100_000);
            buy(sponsor, 100_000);
        }
        return sponsor;
    }

    Map<String, Object> buy(String sponsor, long amount) {
        return run(AiCredits.BUY, sponsorToken(sponsor), body("amount", amount, "requestKey",
            UUID.randomUUID().toString()));
    }

    static Map<String, Object> aiCredit(String sponsor) {
        List<Map<String, Object>> rows = query("SELECT * FROM qb_prepayment WHERE sponsor_id = ? AND purpose = 'AI'",
            sponsor);
        assertThat(rows).as("one AI credit a sponsor").hasSizeLessThanOrEqualTo(1);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    static BigDecimal money(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    /** The ledger's net of an account for the sponsor (debits less credits). */
    static BigDecimal net(String account, String sponsor) {
        Object net = query("SELECT coalesce(sum(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE -e.amount END), 0) "
            + "AS n FROM ledger_entry_version e JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE a.account_code = ? AND e.dimension_1 = ?", account, sponsor).getFirst().get("n");
        return money(net);
    }

    /** Runs the jobs, letting their retries come due, until the job is no longer queued. */
    void runUntilDone(Object aiJobId) {
        for (int i = 0; i < 6 && job(aiJobId).get("finished_at") == null; i++) {
            runJobs();
            advance(Duration.ofMinutes(3));
        }
    }

    /** A sponsor with 5,000 yen of AI credit, their token and a quiz with an article. */
    List<String> charged(String prefix) {
        String sponsor = newSponsor(prefix + UUID.randomUUID());
        cash(sponsor, 5_000);
        buy(sponsor, 5_000);
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material for " + prefix);
        return List.of(sponsor, token, quiz.get(0), quiz.get(1));
    }

    /** The fee of the job's answered dollars at the frozen markup and rate. */
    static BigDecimal feeOf(Map<String, Object> job) {
        return AiFeeRules.fee(money(job.get("billable_usd")), money(job.get("fee_markup")), money(job.get("fee_usd_jpy")));
    }
}

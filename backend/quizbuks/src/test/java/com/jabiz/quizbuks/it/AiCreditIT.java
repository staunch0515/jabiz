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
 * F-M9a-1 (docs/quizbuks/plans/impl/F-M9.md §3, §4): buying AI credit, the reservation of a charged request and the fee
 * its outcome works out. The fee is on in each test and off after it ({@code qb.ai.fee.enabled} is off by default: the
 * requests are then free, as before).
 */
class AiCreditIT extends AiItSupport {

    private static final BigDecimal MARKUP = new BigDecimal("1.5");
    private static final BigDecimal RATE = new BigDecimal("150");
    private static final BigDecimal TAX = new BigDecimal("0.10");

    /** Whether this class's schema has Kudos selling on (each test class has a schema of its own). */
    private static boolean selling;

    @BeforeEach
    void freshDay() {
        aiOn();
        if (!selling) {
            param(QbParams.KUDOS_SELL_ENABLED, "true");
            selling = true;
        }
        advance(Duration.ofDays(1));
    }

    @AfterEach
    void feeOff() {
        if (feeIs("true")) {
            param(QbParams.AI_FEE_ENABLED, "false");
        }
    }

    private static boolean feeIs(String value) {
        List<Map<String, Object>> rows = query("SELECT param_value FROM sys_param_version WHERE param_key = ? "
            + "ORDER BY effect_start_time DESC, version_no DESC LIMIT 1", QbParams.AI_FEE_ENABLED);
        return !rows.isEmpty() && value.equals(String.valueOf(rows.getFirst().get("param_value")));
    }

    private void feeOn() {
        if (!feeIs("true")) {
            param(QbParams.AI_FEE_ENABLED, "true");
        }
    }

    private static String title() {
        return "AI credit " + UUID.randomUUID();
    }

    /** Money in the sponsor's cash account: Kudos credited and sold back. */
    private void cash(String sponsor, long amount) {
        credit(sponsor, amount);
        run(KudosExchanges.SELL, sponsorToken(sponsor), body("amount", amount));
    }

    /** Fee credit as F-M2 grants it (5140 / 2190, the lot, the wallet's cache), expiring in a year. */
    private String grant(String sponsor, long amount) {
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
    private void minPurchase(String value) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("paramKey", QbParams.AI_CREDIT_MIN_PURCHASE);
        values.put("value", value);
        if (query("SELECT 1 FROM sys_param_version WHERE param_key = ?", QbParams.AI_CREDIT_MIN_PURCHASE).isEmpty()) {
            values.put("valueKind", QbParams.AI_CREDIT_MIN_PURCHASE_KIND);
        }
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", values, "reason", "F-M9 test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    private Map<String, Object> buy(String sponsor, long amount) {
        return run(AiCredits.BUY, sponsorToken(sponsor), body("amount", amount, "requestKey",
            UUID.randomUUID().toString()));
    }

    private static Map<String, Object> aiCredit(String sponsor) {
        List<Map<String, Object>> rows = query("SELECT * FROM qb_prepayment WHERE sponsor_id = ? AND purpose = 'AI'",
            sponsor);
        assertThat(rows).as("one AI credit a sponsor").hasSizeLessThanOrEqualTo(1);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private static BigDecimal money(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    /** The ledger's net of an account for the sponsor (debits less credits). */
    private static BigDecimal net(String account, String sponsor) {
        Object net = query("SELECT coalesce(sum(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE -e.amount END), 0) "
            + "AS n FROM ledger_entry_version e JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE a.account_code = ? AND e.dimension_1 = ?", account, sponsor).getFirst().get("n");
        return money(net);
    }

    /** Runs the jobs, letting their retries come due, until the job is no longer queued. */
    private void runUntilDone(Object aiJobId) {
        for (int i = 0; i < 6 && job(aiJobId).get("finished_at") == null; i++) {
            runJobs();
            advance(Duration.ofMinutes(3));
        }
    }

    @Test
    void withTheFeeOffARequestIsFreeAndReservesNothing() {
        String sponsor = newSponsor("ai-free-" + UUID.randomUUID());
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material for a free request.");
        Object aiJobId = request(token, quiz.get(0), List.of(quiz.get(1)), 2).get("aiJobId");
        assertThat(job(aiJobId)).containsEntry("fee_mode", QbAi.FEE_NONE)
            .containsEntry("fee_markup", null).containsEntry("fee_operator_id", null);
        assertThat(money(job(aiJobId).get("reserved_yen"))).isZero();
        runUntilDone(aiJobId);
        Map<String, Object> done = job(aiJobId);
        assertThat(done).containsEntry("status", QbAi.READY).containsEntry("fee_yen", null)
            .containsEntry("fee_period", null);
        // The dollars are still split: all of them answered.
        assertThat(money(done.get("billable_usd"))).isEqualByComparingTo(money(done.get("cost_usd")));
        assertThat(money(done.get("unrecorded_usd"))).isZero();
        // Buying is refused while the fee is off.
        assertThat(codes(refused(AiCredits.BUY, sponsorToken(sponsor), body("amount", 1000, "requestKey", "k-off"),
            422))).containsExactly(FeeCodes.AI_FEE_OFF);
    }

    @Test
    void aPurchaseTakesTheFeeCreditFirstThenTheCashIntoOneCredit() {
        feeOn();
        String sponsor = newSponsor("ai-buy-" + UUID.randomUUID());
        grant(sponsor, 300);
        cash(sponsor, 1_000);
        Map<String, Object> bought = buy(sponsor, 800);
        assertThat(money(bought.get("credit"))).isEqualByComparingTo("300");
        assertThat(money(bought.get("cash"))).isEqualByComparingTo("500");
        assertThat(money(bought.get("balance"))).isEqualByComparingTo("800");
        Map<String, Object> credit = aiCredit(sponsor);
        assertThat(credit).containsEntry("status", "OPEN").containsEntry("publication_id", null)
            .containsEntry("mode", "PREPAID");
        assertThat(money(credit.get("amount"))).isEqualByComparingTo("800");
        assertThat(money(credit.get("remaining"))).isEqualByComparingTo("800");
        // 2190 and 2160 to 2180 for the sponsor: no revenue, no tax.
        assertThat(net("2180", sponsor)).isEqualByComparingTo("-800");
        assertThat(net("4120", sponsor)).isZero();

        // A second purchase raises the same credit (what was bought, what is left).
        Map<String, Object> again = buy(sponsor, 200);
        assertThat(again.get("prepaymentId")).isEqualTo(bought.get("prepaymentId"));
        assertThat(money(again.get("cash"))).isEqualByComparingTo("200");
        credit = aiCredit(sponsor);
        assertThat(money(credit.get("amount"))).isEqualByComparingTo("1000");
        assertThat(money(credit.get("remaining"))).isEqualByComparingTo("1000");
        List<Map<String, Object>> moves = query("SELECT kind, amount, credit, cash, balance, reference FROM "
            + "qb_ai_credit_movement_version WHERE sponsor_id = ? ORDER BY moved_at, row_id", sponsor);
        assertThat(moves).extracting(m -> m.get("kind") + " " + money(m.get("amount")).toPlainString() + " "
                + money(m.get("credit")).toPlainString() + "/" + money(m.get("cash")).toPlainString() + " "
                + money(m.get("balance")).toPlainString())
            .containsExactly("BUY 800 300/500 800", "BUY 200 0/200 1000");

        // The same key again, more than the account holds, less than the least purchase.
        String key = String.valueOf(moves.getFirst().get("reference"));
        assertThat(codes(refused(AiCredits.BUY, sponsorToken(sponsor), body("amount", 100, "requestKey", key), 422)))
            .containsExactly(FeeCodes.AI_CREDIT_REPEATED);
        assertThat(codes(refused(AiCredits.BUY, sponsorToken(sponsor), body("amount", 400, "requestKey", "k-short"),
            422))).containsExactly(FeeCodes.AI_CREDIT_FUNDS_SHORT);
        minPurchase("1000");
        try {
            assertThat(codes(refused(AiCredits.BUY, sponsorToken(sponsor), body("amount", 100, "requestKey", "k-min"),
                422))).containsExactly(FeeCodes.AI_CREDIT_MIN);
        } finally {
            minPurchase("100");
        }
        // Nothing of the refused ones was taken.
        assertThat(money(aiCredit(sponsor).get("remaining"))).isEqualByComparingTo("1000");
    }

    @Test
    void aRequestTheCreditCannotHoldIsRefused() {
        feeOn();
        String sponsor = newSponsor("ai-short-" + UUID.randomUUID());
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material for a request without credit.");
        Map<String, Object> problem = refused(AiProcesses.REQUEST, token, requestInput(quiz.get(0),
            List.of(quiz.get(1)), 2), 422);
        assertThat(codes(problem)).containsExactly(AiCodes.CREDIT_SHORT);
        // What it may cost and what is available fill in the message (the page asks QB_AI_FEE_QUOTE for the numbers).
        assertThat(String.valueOf(problem)).containsPattern("may cost up to [1-9][0-9]* yen and 0 yen is available");
        assertThat(query("SELECT 1 FROM qb_ai_job WHERE sponsor_id = ?", sponsor)).isEmpty();
    }

    @Test
    void aRequestReservesTheMostItMayCostAndItsEndChargesTheAnsweredUsage() {
        feeOn();
        String sponsor = newSponsor("ai-charged-" + UUID.randomUUID());
        cash(sponsor, 5_000);
        buy(sponsor, 5_000);
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material for a charged request.");
        Object aiJobId = request(token, quiz.get(0), List.of(quiz.get(1)), 2).get("aiJobId");
        Map<String, Object> asked = job(aiJobId);
        assertThat(asked).containsEntry("fee_mode", QbAi.FEE_CREDIT);
        assertThat(money(asked.get("fee_markup"))).isEqualByComparingTo(MARKUP);
        assertThat(money(asked.get("fee_usd_jpy"))).isEqualByComparingTo(RATE);
        // The sponsor's operator when they asked (every approved sponsor has one).
        assertThat(asked.get("fee_operator_id")).isNotNull();
        BigDecimal reserved = money(asked.get("reserved_yen"));
        assertThat(reserved).isPositive();
        // A second request may not hold more than what is left of the credit (5,000 less the first's reservation).
        assertThat(reserved).isLessThan(new BigDecimal("5000"));

        runUntilDone(aiJobId);
        Map<String, Object> done = job(aiJobId);
        assertThat(done).containsEntry("status", QbAi.READY);
        BigDecimal billable = money(done.get("billable_usd"));
        assertThat(billable).isEqualByComparingTo(money(done.get("cost_usd")));
        BigDecimal fee = billable.multiply(MARKUP).multiply(RATE).setScale(0, RoundingMode.UP);
        assertThat(money(done.get("fee_yen"))).isEqualByComparingTo(fee).isPositive();
        assertThat(money(done.get("absorbed_yen"))).isZero();
        assertThat(money(done.get("reserved_yen"))).isZero();
        assertThat(done.get("fee_period")).isEqualTo(java.time.YearMonth.from(clock.instant()
            .atZone(java.time.ZoneId.of("Asia/Tokyo"))).toString());
        // Within its reservation, and not charged yet (the month's charge does that).
        assertThat(AiFeeRules.withTax(fee, TAX)).isLessThanOrEqualTo(reserved);
        assertThat(done.get("fee_charge_id")).isNull();
        assertThat(money(aiCredit(sponsor).get("remaining"))).isEqualByComparingTo("5000");
    }

    @Test
    void anUnansweredCallIsThePlatformsAndNotInTheFee() {
        feeOn();
        String sponsor = newSponsor("ai-unanswered-" + UUID.randomUUID());
        cash(sponsor, 5_000);
        buy(sponsor, 5_000);
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material for a slow provider.");
        // The first attempt's answer comes after the client stopped waiting; the next one is answered.
        CLAUDE.script(MESSAGES, answer("claude-opus-5-5", "end_turn", questions(1), 100, 100)
            .after(Duration.ofSeconds(5)));
        Object aiJobId = request(token, quiz.get(0), List.of(quiz.get(1)), 1).get("aiJobId");
        runUntilDone(aiJobId);
        Map<String, Object> done = job(aiJobId);
        assertThat(done).containsEntry("status", QbAi.READY);
        BigDecimal billable = money(done.get("billable_usd"));
        BigDecimal unrecorded = money(done.get("unrecorded_usd"));
        assertThat(unrecorded).isPositive();
        assertThat(billable.add(unrecorded)).isEqualByComparingTo(money(done.get("cost_usd")));
        // The fee is the answered part's only.
        assertThat(money(done.get("fee_yen"))).isEqualByComparingTo(billable.multiply(MARKUP).multiply(RATE)
            .setScale(0, RoundingMode.UP));
    }

    @Test
    void aJobCancelledBeforeItRanCostsNothingAndLetsItsReservationGo() {
        feeOn();
        String sponsor = newSponsor("ai-cancelled-" + UUID.randomUUID());
        cash(sponsor, 5_000);
        buy(sponsor, 5_000);
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material for a cancelled request.");
        Object aiJobId = request(token, quiz.get(0), List.of(quiz.get(1)), 2).get("aiJobId");
        run(AiProcesses.CANCEL, token, body("aiJobId", String.valueOf(aiJobId)));
        // The reservation stays until the platform's outcome of the cancellation.
        assertThat(money(job(aiJobId).get("reserved_yen"))).isPositive();
        runJobs();
        Map<String, Object> done = job(aiJobId);
        assertThat(done.get("finished_at")).isNotNull();
        assertThat(money(done.get("fee_yen"))).isZero();
        assertThat(money(done.get("reserved_yen"))).isZero();
    }

    @Test
    void whatTheCreditCannotHoldAtTheEndIsTakenOnByThePlatform() {
        feeOn();
        String sponsor = newSponsor("ai-absorbed-" + UUID.randomUUID());
        cash(sponsor, 5_000);
        buy(sponsor, 1_000);
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material for a dear request.");
        Object aiJobId = request(token, quiz.get(0), List.of(quiz.get(1)), 2).get("aiJobId");
        // As if the reservation had come out low (a price changed at the provider) and the rest of the credit were
        // held by others: 1 yen is all the job may take.
        execute("UPDATE qb_ai_job SET reserved_yen = 1 WHERE ai_job_id = ?", String.valueOf(aiJobId));
        execute("UPDATE qb_prepayment SET remaining = 1 WHERE sponsor_id = ? AND purpose = 'AI'", sponsor);
        try {
            runUntilDone(aiJobId);
            Map<String, Object> done = job(aiJobId);
            BigDecimal fee = money(done.get("billable_usd")).multiply(MARKUP).multiply(RATE)
                .setScale(0, RoundingMode.UP);
            assertThat(fee).isGreaterThan(BigDecimal.ONE);
            assertThat(money(done.get("fee_yen"))).isEqualByComparingTo("1");
            assertThat(money(done.get("absorbed_yen"))).isEqualByComparingTo(fee.subtract(BigDecimal.ONE));
            assertThat(money(done.get("reserved_yen"))).isZero();
        } finally {
            execute("UPDATE qb_prepayment SET remaining = amount WHERE sponsor_id = ? AND purpose = 'AI'", sponsor);
        }
    }

    /**
     * #692 review M2: a finished charged job with no fee (cancelled before it ran, failed with no answer) holds nothing
     * and goes into no charge, so it leaves the open set: more of them than a sponsor may have open jobs stop neither a
     * request nor an outcome.
     */
    @Test
    void finishedJobsWithoutAFeeNeverPileUpInTheOpenSet() {
        feeOn();
        String sponsor = newSponsor("ai-zero-" + UUID.randomUUID());
        cash(sponsor, 5_000);
        buy(sponsor, 5_000);
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material after many free endings.");
        // Asked and ended days ago: today's requests (the daily limit) are not theirs.
        Timestamp at = Timestamp.from(clock.instant().minus(Duration.ofDays(3)));
        execute("INSERT INTO qb_ai_job (ai_job_id, external_job_id, sponsor_id, quiz_id, kind, params, model, status, "
            + "input_bytes, estimated_tokens, zone, requested_at, finished_at, version, fee_mode, fee_markup, "
            + "fee_usd_jpy, reserved_yen, fee_yen, absorbed_yen, fee_period) "
            + "SELECT gen_random_uuid()::text, gen_random_uuid()::text, ?, ?::uuid, 'QUESTIONS', '{}', "
            + "'claude-opus-5-5', CASE WHEN i % 2 = 0 THEN 'CANCELLED' ELSE 'FAILED' END, 10, 10, 'Asia/Tokyo', ?, ?, 1, "
            + "'CREDIT', 1.5, 150, 0, 0, 0, '2026-11' FROM generate_series(1, ?) AS i",
            sponsor, quiz.get(0), at, at, AiProcesses.FEE_MAX_OPEN + 1);
        Object aiJobId = request(token, quiz.get(0), List.of(quiz.get(1)), 2).get("aiJobId");
        assertThat(money(job(aiJobId).get("reserved_yen"))).isPositive();
        runUntilDone(aiJobId);
        Map<String, Object> done = job(aiJobId);
        assertThat(done).containsEntry("status", QbAi.READY);
        assertThat(money(done.get("fee_yen"))).isPositive();
        assertThat(money(done.get("absorbed_yen"))).isZero();
        // The open set is the one job with a fee.
        assertThat(query("SELECT count(*) AS n FROM qb_ai_job WHERE sponsor_id = ? AND fee_mode = 'CREDIT' AND "
            + "fee_charge_id IS NULL AND (fee_yen IS NULL OR fee_yen > 0)", sponsor).getFirst().get("n"))
            .hasToString("1");
    }

    /** A sponsor with 5,000 yen of AI credit, their token and a quiz with an article. */
    private List<String> charged(String prefix) {
        String sponsor = newSponsor(prefix + UUID.randomUUID());
        cash(sponsor, 5_000);
        buy(sponsor, 5_000);
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material for " + prefix);
        return List.of(sponsor, token, quiz.get(0), quiz.get(1));
    }

    /** The fee of the job's answered dollars at the frozen markup and rate. */
    private static BigDecimal feeOf(Map<String, Object> job) {
        return AiFeeRules.fee(money(job.get("billable_usd")), money(job.get("fee_markup")), money(job.get("fee_usd_jpy")));
    }

    /**
     * #692 review M3: a failed job is charged what the provider answered (here a cut answer, billed in full), and nothing
     * when no call was answered (a key the provider refuses); with no fee it holds nothing more of the credit.
     */
    @Test
    void aFailedJobIsChargedItsAnsweredUsageOnly() {
        feeOn();
        List<String> s = charged("ai-failed-answered-");
        CLAUDE.script(MESSAGES, answer("claude-opus-5-5", "max_tokens", null, 900, 600));
        Object answered = request(s.get(1), s.get(2), List.of(s.get(3)), 1).get("aiJobId");
        runUntilDone(answered);
        Map<String, Object> cut = job(answered);
        assertThat(cut).containsEntry("status", QbAi.FAILED);
        // Opus 5.5 at $4 / $20 a million: 900 in and 600 out are $0.0156, all answered.
        assertThat(money(cut.get("billable_usd"))).isEqualByComparingTo("0.015600");
        assertThat(money(cut.get("unrecorded_usd"))).isZero();
        assertThat(money(cut.get("fee_yen"))).isEqualByComparingTo(feeOf(cut)).isEqualByComparingTo("4");
        assertThat(money(cut.get("reserved_yen"))).isZero();

        List<String> t = charged("ai-failed-unanswered-");
        CLAUDE.script(MESSAGES, StubHttpServer.Answer.json(401, "{\"type\":\"error\",\"error\":"
            + "{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"));
        Object refused = request(t.get(1), t.get(2), List.of(t.get(3)), 1).get("aiJobId");
        runUntilDone(refused);
        Map<String, Object> none = job(refused);
        assertThat(none).containsEntry("status", QbAi.FAILED);
        assertThat(money(none.get("billable_usd"))).isZero();
        assertThat(money(none.get("fee_yen"))).isZero();
        assertThat(money(none.get("reserved_yen"))).isZero();
    }

    /**
     * #692 review M3: an attempt whose instance died (recorded {@code LEASE_EXPIRED}, nothing reported) is the
     * platform's: its estimate goes into the job's cost as {@code unrecordedUsd}, never into the sponsor's fee.
     */
    @Test
    void anAttemptWhoseInstanceDiedIsThePlatformsAndNotInTheFee() {
        feeOn();
        List<String> s = charged("ai-died-");
        Map<String, Object> requested = request(s.get(1), s.get(2), List.of(s.get(3)), 2);
        Object aiJobId = requested.get("aiJobId");
        claimedByADeadInstance(requested.get("externalJobId"), 1, clock.instant().minusSeconds(1));
        runUntilDone(aiJobId);
        assertThat(platformAttempts(requested.get("externalJobId"))).containsExactly("1:LEASE_EXPIRED",
            "2:SUCCEEDED");
        Map<String, Object> done = job(aiJobId);
        assertThat(done).containsEntry("status", QbAi.READY);
        // The stub's answer bills 1,200 in and 800 out: $0.0208 at Opus 5.5's price, the sponsor's.
        assertThat(money(done.get("billable_usd"))).isEqualByComparingTo("0.020800");
        assertThat(money(done.get("unrecorded_usd"))).isPositive();
        assertThat(money(done.get("billable_usd")).add(money(done.get("unrecorded_usd"))))
            .isEqualByComparingTo(money(done.get("cost_usd")));
        assertThat(money(done.get("fee_yen"))).isEqualByComparingTo(feeOf(done)).isEqualByComparingTo("5");
    }

    /** #692 review M3: cancelled while its attempt ran, the job is charged what that attempt was answered. */
    @Test
    void aJobCancelledWhileItRanIsChargedWhatWasAnswered() throws Exception {
        feeOn();
        List<String> s = charged("ai-cancel-running-");
        CLAUDE.script(MESSAGES, answer("claude-opus-5-5", "end_turn", questions(1), 700, 300)
            .after(Duration.ofMillis(1200)));
        Object aiJobId = request(s.get(1), s.get(2), List.of(s.get(3)), 1).get("aiJobId");
        CompletableFuture<Integer> running = runningAttempt();
        assertThat(run(AiProcesses.CANCEL, s.get(1), body("aiJobId", aiJobId))).containsEntry("status",
            QbAi.CANCELLED);
        assertThat(running.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        runJobs();
        Map<String, Object> done = job(aiJobId);
        assertThat(done).containsEntry("status", QbAi.CANCELLED);
        assertThat(done.get("finished_at")).isNotNull();
        // 700 in and 300 out at $4 / $20 a million.
        assertThat(money(done.get("billable_usd"))).isEqualByComparingTo("0.008800");
        assertThat(money(done.get("fee_yen"))).isEqualByComparingTo(feeOf(done)).isEqualByComparingTo("2");
        assertThat(money(done.get("reserved_yen"))).isZero();
    }

    /**
     * #692 review M3: the markup, the rate and the switch are frozen on the job at its request: changed or turned off
     * before it ends, the job is still charged as it was asked.
     */
    @Test
    void aJobIsSettledAtTheValuesFrozenAtItsRequest() {
        feeOn();
        List<String> s = charged("ai-frozen-");
        Object aiJobId = request(s.get(1), s.get(2), List.of(s.get(3)), 2).get("aiJobId");
        BigDecimal reserved = money(job(aiJobId).get("reserved_yen"));
        param(QbParams.AI_FEE_MARKUP, "3");
        param(QbParams.AI_FEE_USD_JPY, "300");
        param(QbParams.AI_FEE_ENABLED, "false");
        try {
            runUntilDone(aiJobId);
            Map<String, Object> done = job(aiJobId);
            assertThat(done).containsEntry("status", QbAi.READY).containsEntry("fee_mode", QbAi.FEE_CREDIT);
            assertThat(money(done.get("fee_markup"))).isEqualByComparingTo(MARKUP);
            assertThat(money(done.get("fee_usd_jpy"))).isEqualByComparingTo(RATE);
            BigDecimal fee = money(done.get("billable_usd")).multiply(MARKUP).multiply(RATE)
                .setScale(0, RoundingMode.UP);
            assertThat(money(done.get("fee_yen"))).isEqualByComparingTo(fee).isPositive();
            assertThat(AiFeeRules.withTax(fee, TAX)).isLessThanOrEqualTo(reserved);
            assertThat(money(done.get("reserved_yen"))).isZero();
        } finally {
            param(QbParams.AI_FEE_MARKUP, "1.5");
            param(QbParams.AI_FEE_USD_JPY, "150");
        }
    }
}

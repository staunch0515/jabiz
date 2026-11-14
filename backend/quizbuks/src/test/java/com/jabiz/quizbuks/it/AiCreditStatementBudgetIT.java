package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.fee.AiCredits;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.KudosExchanges;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M9a-1 (docs/quizbuks/plans/impl/F-M9.md §9): the statements of an AI request with the fee on and off (one more with
 * it on: the credit, the charged jobs and the operator are read in the statement the request already makes, the credit
 * by lot in one more (#699 re-review L2), and the fee is frozen in the job's insert), the AI locks a request takes (no wallet lock), and the statements of a purchase of credit (its
 * wallet lock only). Each counted once before it is counted (warm caches).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class AiCreditStatementBudgetIT extends AiItSupport {

    /** A request of two questions on one article: measured, then fixed here on purpose. */
    static final int REQUEST = 20;
    /** A purchase that raises the credit (the second): the lock, one read, the top-up's sub-process, the movement. */
    static final int BUY = 10;
    /** A return of part of the credit (measured, then fixed here). */
    static final int RETURN = 14;
    /** The daily expiry's item for one sponsor, voiding one expired grant's fee credit (measured, then fixed here). */
    static final int EXPIRE = 15;
    /** A month's charge of one job (measured, then fixed here). */
    static final int SETTLE_ONE = 21;
    /**
     * A month's charge of 200 jobs in two groups (two months): a second charge, and one update a job (the platform
     * updates by the version read, one statement each; the item's batch of 200 bounds them).
     */
    static final int SETTLE_MANY = 235;

    @Test
    void theStatementsOfARequestWithTheFeeOnAndOffAndOfAPurchase() {
        aiOn();
        param(QbParams.KUDOS_SELL_ENABLED, "true");
        advance(Duration.ofDays(1));
        String sponsor = newSponsor("ai-budget-" + UUID.randomUUID());
        credit(sponsor, 50_000);
        run(KudosExchanges.SELL, sponsorToken(sponsor), body("amount", 50_000));
        String token = sponsorFull(sponsor);
        // One quiz a request: a quiz has one job of a kind at a time.
        List<List<String>> quizzes = IntStream.range(0, 4).mapToObj(i -> quizWithArticle(token,
            "AI budget " + UUID.randomUUID(), "Material for the budget.")).toList();

        // The jobs run out between the requests: a sponsor has at most two open at a time.
        counted(() -> request(token, quizzes.get(0).get(0), List.of(quizzes.get(0).get(1)), 2));
        runJobs();
        List<String> off = counted(() -> request(token, quizzes.get(1).get(0), List.of(quizzes.get(1).get(1)), 2));
        List<String> offLocks = SqlStatementLog.locks();
        runJobs();

        param(QbParams.AI_FEE_ENABLED, "true");
        try {
            counted(() -> buy(sponsor));
            List<String> buying = counted(() -> buy(sponsor));
            List<String> buyLocks = SqlStatementLog.locks();
            counted(() -> request(token, quizzes.get(2).get(0), List.of(quizzes.get(2).get(1)), 2));
            runJobs();
            List<String> on = counted(() -> request(token, quizzes.get(3).get(0), List.of(quizzes.get(3).get(1)), 2));
            List<String> onLocks = SqlStatementLog.locks();
            System.out.println("[statement-budget] AI request, fee off: " + off.size() + "\n" + listing(off));
            System.out.println("[statement-budget] AI request, fee on: " + on.size() + "\n" + listing(on));
            System.out.println("[statement-budget] AI credit purchase: " + buying.size() + "\n" + listing(buying));
            System.out.println("[statement-budget] locks: " + offLocks + " / " + onLocks + " / " + buyLocks);

            // The fee adds one statement to a request (#699 re-review L2: the credit read by lot, so that expired fee
            // credit is not counted as available), and no lock: the sponsor's AI requests, then everybody's.
            assertThat(on).as("fee on:\n" + listing(on)).hasSize(off.size() + 1);
            assertThat(onLocks).isEqualTo(offLocks).containsExactly("qb.ai:" + sponsor, "qb.ai:*");
            // A purchase holds the sponsor's wallet only (plan §4 item 7: never an AI lock).
            assertThat(buyLocks).containsExactly("qb.wallet:SPONSOR:" + sponsor);
            assertThat(off).as("request:\n" + listing(off)).hasSize(REQUEST);
            assertThat(buying).as("purchase:\n" + listing(buying)).hasSize(BUY);
        } finally {
            param(QbParams.AI_FEE_ENABLED, "false");
        }
    }

    private Map<String, Object> buy(String sponsor) {
        return run(AiCredits.BUY, sponsorToken(sponsor), body("amount", 1_000, "requestKey",
            UUID.randomUUID().toString()));
    }

    /**
     * F-M9a-2 (plan §4 item 7, §9): a return takes the sponsor's AI lock, then their wallet lock (never everybody's AI
     * lock), and its statements are fixed here: the locks, the parameters, the standing's one read with the request
     * key's, the sub-process (the credit and the wallets in one read, the lots by template, the grants and cash lots in
     * one read, the posting, the parts, the lots' and wallets' writes, the movement).
     */
    @Test
    void theStatementsAndLocksOfAReturn() {
        aiOn();
        param(QbParams.KUDOS_SELL_ENABLED, "true");
        advance(Duration.ofDays(1));
        String sponsor = newSponsor("ai-budget-return-" + UUID.randomUUID());
        credit(sponsor, 50_000);
        run(KudosExchanges.SELL, sponsorToken(sponsor), body("amount", 50_000));
        param(QbParams.AI_FEE_ENABLED, "true");
        try {
            buy(sponsor);
            counted(() -> giveBack(sponsor));
            List<String> returning = counted(() -> giveBack(sponsor));
            List<String> locks = SqlStatementLog.locks();
            System.out.println("[statement-budget] AI credit return: " + returning.size() + "\n" + listing(returning));
            System.out.println("[statement-budget] return locks: " + locks);
            assertThat(locks).containsExactly("qb.ai:" + sponsor, "qb.wallet:SPONSOR:" + sponsor);
            assertThat(returning).as("return:\n" + listing(returning)).hasSize(RETURN);
        } finally {
            param(QbParams.AI_FEE_ENABLED, "false");
        }
    }

    /**
     * #699 review M4/L4: the month's charge item: the sponsor's AI lock, then their wallet lock (never everybody's), and
     * its statements with one job and with 200 jobs of two groups (the most an item takes), fixed here.
     */
    @Test
    void theStatementsAndLocksOfAMonthsCharge() {
        aiOn();
        param(QbParams.KUDOS_SELL_ENABLED, "true");
        advance(Duration.ofDays(1));
        String sponsor = newSponsor("ai-budget-settle-" + UUID.randomUUID());
        credit(sponsor, 50_000);
        run(KudosExchanges.SELL, sponsorToken(sponsor), body("amount", 50_000));
        String token = sponsorFull(sponsor);
        param(QbParams.AI_FEE_ENABLED, "true");
        try {
            run(AiCredits.BUY, sponsorToken(sponsor), body("amount", 40_000, "requestKey", UUID.randomUUID().toString()));
            List<String> quiz = quizWithArticle(token, "AI budget " + UUID.randomUUID(), "Material for the budget.");
            Object aiJobId = request(token, quiz.get(0), List.of(quiz.get(1)), 2).get("aiJobId");
            runJobs();
            Map<String, Object> done = job(aiJobId);
            String period = String.valueOf(done.get("fee_period"));
            String next = java.time.YearMonth.parse(period).plusMonths(1).toString();
            String later = java.time.YearMonth.parse(period).plusMonths(2).toString();
            List<String> one = counted(() -> systemRun(com.jabiz.quizbuks.content.ai.AiFeeSettlements.SETTLE,
                body("sponsorId", sponsor, "month", next)));
            List<String> oneLocks = SqlStatementLog.locks();
            // 200 more of the job, a hundred of each of two months: two groups.
            execute(("DO $$ DECLARE cols text; BEGIN SELECT string_agg(CASE column_name "
                + "WHEN 'ai_job_id' THEN 'gen_random_uuid()::text' WHEN 'external_job_id' THEN 'gen_random_uuid()::text' "
                + "WHEN 'fee_charge_id' THEN 'NULL' WHEN 'fee_period' THEN 'CASE WHEN g %% 2 = 0 THEN ''%s'' ELSE ''%s'' END' "
                + "ELSE quote_ident(column_name) END, ', ' ORDER BY ordinal_position) INTO cols "
                + "FROM information_schema.columns WHERE table_name = 'qb_ai_job' AND table_schema = current_schema(); "
                + "EXECUTE format('INSERT INTO qb_ai_job SELECT %%s FROM qb_ai_job, generate_series(1, 200) g "
                + "WHERE ai_job_id = %%L', cols, '%s'); END $$").formatted(period, next, aiJobId));
            List<String> many = counted(() -> systemRun(com.jabiz.quizbuks.content.ai.AiFeeSettlements.SETTLE,
                body("sponsorId", sponsor, "month", later)));
            System.out.println("[statement-budget] AI month's charge, one job: " + one.size() + "\n" + listing(one));
            System.out.println("[statement-budget] AI month's charge, 200 jobs: " + many.size() + "\n" + listing(many));
            System.out.println("[statement-budget] settle locks: " + oneLocks);
            assertThat(oneLocks).containsExactly("qb.ai:" + sponsor, "qb.wallet:SPONSOR:" + sponsor);
            assertThat(one).as("one job:\n" + listing(one)).hasSize(SETTLE_ONE);
            assertThat(many).as("200 jobs:\n" + listing(many)).hasSize(SETTLE_MANY);
        } finally {
            param(QbParams.AI_FEE_ENABLED, "false");
        }
    }

    /**
     * #699 re-review L5: the daily expiry's item takes the sponsor's AI lock, then their wallet lock (never everybody's
     * AI lock), and its statements are fixed here (a sponsor whose expired grant the credit holds; another sponsor's
     * item counted first, to warm the caches).
     */
    @Test
    void theStatementsAndLocksOfAnExpiry() {
        aiOn();
        param(QbParams.KUDOS_SELL_ENABLED, "true");
        advance(Duration.ofDays(1));
        param(QbParams.AI_FEE_ENABLED, "true");
        try {
            List<String> sponsors = IntStream.range(0, 2).mapToObj(i -> {
                String sponsor = newSponsor("ai-budget-expire-" + UUID.randomUUID());
                String grantId = UUID.randomUUID().toString();
                execute("INSERT INTO qb_fee_credit_grant (grant_id, sponsor_id, source, source_key, amount, remaining, "
                    + "voided_amount, granted_at, expires_at, version) VALUES (?, ?, 'MANUAL', ?, 1000, 1000, 0, ?, ?, 1)",
                    grantId, sponsor, "test:" + grantId, java.sql.Timestamp.from(clock.instant()),
                    java.sql.Timestamp.from(clock.instant().plus(Duration.ofDays(365))));
                execute("INSERT INTO qb_wallet (wallet_id, owner_id, kind, balance, pending_count, pending_amount, "
                    + "pending_out, version) VALUES (?, ?, 'FEE_CREDIT', 1000, 0, 0, 0, 1)",
                    UUID.randomUUID().toString(), sponsor);
                run(AiCredits.BUY, sponsorToken(sponsor), body("amount", 1_000, "requestKey",
                    UUID.randomUUID().toString()));
                execute("UPDATE qb_fee_credit_grant SET expires_at = ? WHERE grant_id = ?",
                    java.sql.Timestamp.from(clock.instant().plusSeconds(60)), grantId);
                return sponsor;
            }).toList();
            advance(Duration.ofMinutes(5));
            counted(() -> systemRun(com.jabiz.quizbuks.content.ai.AiCreditReturns.EXPIRE_ITEM,
                body("sponsorId", sponsors.get(0))));
            List<String> expiring = counted(() -> systemRun(com.jabiz.quizbuks.content.ai.AiCreditReturns.EXPIRE_ITEM,
                body("sponsorId", sponsors.get(1))));
            List<String> locks = SqlStatementLog.locks();
            System.out.println("[statement-budget] AI credit expiry: " + expiring.size() + "\n" + listing(expiring));
            System.out.println("[statement-budget] expiry locks: " + locks);
            assertThat(locks).containsExactly("qb.ai:" + sponsors.get(1), "qb.wallet:SPONSOR:" + sponsors.get(1));
            assertThat(expiring).as("expiry:\n" + listing(expiring)).hasSize(EXPIRE);
        } finally {
            param(QbParams.AI_FEE_ENABLED, "false");
        }
    }

    private Map<String, Object> giveBack(String sponsor) {
        return run(com.jabiz.quizbuks.content.ai.AiCreditReturns.RETURN, sponsorToken(sponsor), body("amount", 100,
            "requestKey", UUID.randomUUID().toString()));
    }

    private List<String> counted(Runnable action) {
        SqlStatementLog.clear();
        action.run();
        List<List<String>> own = SqlStatementLog.transactions().stream()
            .filter(t -> t.stream().anyMatch(sql -> sql.startsWith("INSERT INTO op_process ("))).toList();
        assertThat(own).as("one transaction").hasSize(1);
        return own.getFirst();
    }

    private static String listing(List<String> statements) {
        return IntStream.range(0, statements.size())
            .mapToObj(i -> String.format("%3d  %s", i + 1, statements.get(i).replaceAll("\\s+", " ")))
            .collect(Collectors.joining("\n"));
    }
}

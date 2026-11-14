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
 * F-M9a-1 (docs/quizbuks/plans/impl/F-M9.md §9): the statements of an AI request with the fee on and off (the same: the
 * credit, the charged jobs and the operator are read in the statement the request already makes, and the fee is frozen
 * in the job's insert), the AI locks a request takes (no wallet lock), and the statements of a purchase of credit (its
 * wallet lock only). Each counted once before it is counted (warm caches).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class AiCreditStatementBudgetIT extends AiItSupport {

    /** A request of two questions on one article: measured, then fixed here on purpose. */
    static final int REQUEST = 20;
    /** A purchase that raises the credit (the second): the lock, one read, the top-up's sub-process, the movement. */
    static final int BUY = 10;

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

            // The fee adds no statement to a request, and no lock: the sponsor's AI requests, then everybody's.
            assertThat(on).as("fee on:\n" + listing(on)).hasSize(off.size());
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

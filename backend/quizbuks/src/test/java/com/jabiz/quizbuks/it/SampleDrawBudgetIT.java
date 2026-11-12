package com.jabiz.quizbuks.it;

import com.jabiz.runtime.storage.WriteBatching;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-F11a (docs/quizbuks/plans/impl/F-F11.md section 6.1): a start that draws 20 of a bank of 1000 questions sends the
 * statements {@link AttemptStatementBudgetIT} counts for any start, not one more: the draw is made in the process from
 * the version it already reads. Its own class, since a context with {@code it.sql-log.enabled} keeps every statement
 * of the class in memory (the restore of the samples sends hundreds of thousands of them).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class SampleDrawBudgetIT extends TreeItSupport {

    @Autowired
    WriteBatching batching;

    private int budget(AttemptStatementBudgetIT.Budget budget) {
        return batching.acrossTransaction() ? budget.held() : budget.perCommit();
    }

    @Test
    void aStartDrawingTwentyOfAThousandSendsTheBudgetsStatements() {
        autoApprove(true);
        String sponsor = newSponsor(unique("s-ff11-draw-budget"));
        credit(sponsor, 1_000_000);
        String token = sponsorToken(sponsor);
        String quizId = (String) run("QB_QUIZ_IMPORT", token, body("document", PackageFixtures.bank(1000)))
            .get("quizId");
        String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        SqlStatementLog.clear();
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Draw budget", now, now.plus(Duration.ofDays(7)));
        save.put("regions", List.of("GLOBAL"));
        save.put("maxWinners", 1000);
        save.put("pickCount", 20);
        save.put("passScore", 10);
        save.put("tiers", List.of(body("fromScore", 0, "toScore", 9, "reward", 0),
            body("fromScore", 10, "toScore", 20, "reward", 10)));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        assertThat(submit(token, publicationId).get("status")).isIn("LIVE", "SCHEDULED");

        // Warm (the first start of a publication), then counted; no profile, as the budget's takers.
        start(taker(newTaker(unique("t-ff11-budget-warm"), null)), publicationId);
        String counted = taker(newTaker(unique("t-ff11-budget"), null));
        SqlStatementLog.clear();
        Map<String, Object> started = run("QB_ATTEMPT_START", counted, body("publicationId", publicationId));
        assertThat((List<?>) started.get("questions")).hasSize(20);
        List<List<String>> transactions = SqlStatementLog.transactions();
        List<String> own = transactions.stream().filter(t -> t.stream().anyMatch(sql -> sql.startsWith(
            "INSERT INTO op_process ("))).findFirst().orElseThrow();
        assertThat(own).as("QB_ATTEMPT_START drawing 20 of 1000").hasSize(budget(AttemptStatementBudgetIT.START_TOTAL));
        assertThat(AttemptStatementBudgetIT.underLock(own)).hasSize(budget(AttemptStatementBudgetIT.START_LOCKED));
        SqlStatementLog.clear();
    }
}

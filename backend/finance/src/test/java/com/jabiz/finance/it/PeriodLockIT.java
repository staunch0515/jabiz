package com.jabiz.finance.it;

import com.jabiz.finance.calc.PeriodPolicy;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.PeriodProcesses;
import com.jabiz.finance.setup.FinanceRoles;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.jabiz.finance.it.JournalLifecycleIT.entry;
import static com.jabiz.finance.it.JournalLifecycleIT.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Postings and the closing of their period one after the other (ROADMAP F11b; the platform's named locks,
 * docs/design/06-process.md section 4.1). A transaction of the test's own holds the shared lock of January as a posting
 * in flight does: the controller's soft close of January waits for it; a posting that comes meanwhile waits behind the
 * close and, once the close has committed, finds January soft-closed and is refused. Had it read the period before
 * waiting, it would have posted into a period closed under it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PeriodLockIT extends FinanceItSupport {

    private static int waiting() {
        return ((Number) query("SELECT count(*) AS n FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")
            .getFirst().get("n")).intValue();
    }

    private static void awaitWaiting(int count) throws InterruptedException {
        for (int i = 0; i < 400 && waiting() < count; i++) {
            Thread.sleep(25);
        }
        assertThat(waiting()).as("processes waiting for a period's lock").isEqualTo(count);
    }

    @Test
    void aCloseWaitsForThePostingsAndThePostingsAfterItFindThePeriodClosed() throws Exception {
        openBooks();
        String accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);
        String controller = inRoles("controller", FinanceRoles.CONTROLLER);
        String journalId = (String) ok(JournalProcesses.SAVE, accountant, entry("2026-01-20", "Office supplies",
            List.of(line("6500", "120.00", null, null), line("2100", null, "120.00", null)))).get("journalId");

        try (Connection posting = DB.connect(schema()); Statement statement = posting.createStatement()) {
            posting.setAutoCommit(false);
            statement.execute("SELECT pg_advisory_xact_lock_shared(hashtextextended('jabiz.app-lock:fin.period:2026-01', 0))");

            CompletableFuture<Integer> softClose = CompletableFuture.supplyAsync(() -> run(PeriodProcesses.SET_STATE,
                controller, Map.of("periodKey", "2026-01", "status", "SOFT_CLOSED")).returnResult(Map.class)
                .getStatus().value());
            awaitWaiting(1);
            CompletableFuture<String> submitted = CompletableFuture.supplyAsync(() -> refused(JournalProcesses.SUBMIT,
                accountant, Map.of("journalId", journalId), 422));
            awaitWaiting(2);
            assertThat(softClose).isNotDone();

            posting.commit();
            assertThat(softClose.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(submitted.get(30, TimeUnit.SECONDS)).isEqualTo(PeriodPolicy.PERIOD_SOFT_CLOSED);
        }
        assertThat(read(JournalEntities.JOURNAL_DATASET, journalId).get("status")).isNotEqualTo("POSTED");

        // Open again, the same entry posts: the lock is let go with each transaction.
        ok(PeriodProcesses.SET_STATE, controller, Map.of("periodKey", "2026-01", "status", "OPEN"));
        ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", journalId));
        assertThat(read(JournalEntities.JOURNAL_DATASET, journalId)).containsEntry("status", "POSTED");
    }
}

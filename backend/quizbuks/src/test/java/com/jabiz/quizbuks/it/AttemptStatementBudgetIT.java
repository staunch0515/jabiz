package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.publishing.PublicationMoves;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.storage.WriteBatching;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statement budget of answering one hot publication (Q10a3; platform decision D46 rule 7, as the platform's own
 * {@code StatementBudgetIT}): how many statements a start and a paid submission send, in their transaction and under the
 * publication's lock. A hot publication's capacity is set by the round trips under its lock (Q10a §4.2: about 0.8 ms
 * each), so the counts are exact: a change in either direction is made on purpose, together with the numbers here and
 * in docs/quizbuks/perf/Q10a-load-test.md §9 (§11 since platform phase 16p-2). The failure lists the statements.
 *
 * <p>Counted as the App calls: over HTTP, each call with its own {@code Idempotency-Key} (the lookup for a replay is
 * outside the transaction). "Under the lock" is from the statement after the one taking the transaction's first lock
 * to the {@code COMMIT}, both included, as Q10a §4.2 counted it: for a start the start lock {@code qb.pub-start}, for a
 * completion and a pause the publication's lock {@code qb.pub} (Q10a4, docs/quizbuks/plans/Q10-lock-split.md 6.1;
 * which locks each takes is asserted from the statements' bound names). Each case runs once before it is counted (warm
 * caches, the sponsor's wallet row there), as in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class AttemptStatementBudgetIT extends PlayItSupport {

    // ---- budgets: [Q10a, before platform phase 16p-1 / with 16p-1 merged, before Q10a3's changes] ------------------
    // Each has two numbers (as the platform's StatementBudgetIT): with the transaction's writes held (platform phase
    // 16p-2, decision D46, the default) and with the emergency switch jabiz.storage.write-batching.enabled=false, when
    // each commit writes its rows as it ends (16p-1: Q10a3 and Q10a4's numbers;
    // AttemptStatementBudgetWithoutHeldWritesIT). Held, the platform writes the inserts, the updates at a known version
    // with their audit records, the sub-processes' operation records, the events and the idempotency result with the
    // next platform statement that may read them (a lock carries inserts) or in one statement before the COMMIT.

    /** A budget: with the transaction's writes held (16p-2), and within one commit only (16p-1, the switch off). */
    record Budget(int held, int perCommit) {}

    /**
     * {@code QB_ATTEMPT_START}, a new attempt, in its transaction [— / 16]: the operation (with the lock timeout and
     * its number), banned?, one read of the caller's attempt, the card and the publication, the profile, the version
     * (no country without a profile: no statement), the sponsor's wallet, the start lock, then under it.
     */
    static final Budget START_TOTAL = new Budget(10, 12);
    /**
     * Under the start lock [12 / 8]: the caller's attempt, the card and both sides of the counters again (one
     * statement); the attempt with its audit record (its references to the publication and the version answered by
     * this transaction's reads), the starts' side at the version read with its audit record, the idempotency result
     * (held: one statement; per commit: three); {@code COMMIT}.
     */
    static final Budget START_LOCKED = new Budget(3, 5);
    /**
     * {@code QB_ATTEMPT_SUBMIT}, a paid reward, in its transaction [— / 22]: the operation, banned?, the attempt, the
     * version, the commission rate, the publication's lock, then under it.
     */
    static final Budget SUBMIT_TOTAL = new Budget(13, 20);
    /**
     * Under the lock [48 / 16]: the attempt, the counters and the card again (one statement), the sponsor's wallet lock
     * and the wallet, {@code LEDGER_POST} as a sub-process (held: its operation record carried by its account lock, one
     * read; per commit: its operation, the account lock, one read, the transaction, the entries), the event, the
     * wallet, attempt and counters each updated at the version read with its audit record, the idempotency result
     * (held: the ledger's rows and all these in one statement), {@code COMMIT}.
     */
    static final Budget SUBMIT_LOCKED = new Budget(7, 14);
    /** Outside the transaction, each call: the lookup of a replay of its {@code Idempotency-Key}. */
    static final int OUTSIDE = 1;
    /**
     * {@code QB_ATTEMPT_SUBMIT} of the paid reward that ends the publication (its last winner's place), in its
     * transaction (asserted since Q10a4): the paid submission's {@link #SUBMIT_TOTAL} and then the move, a sub-process
     * ({@code QB_PUBLICATION_COMPLETION_MOVE}): its operation (held: carried by the next statement), the publication's
     * lock again (at once: held; with the writes held it carries the ledger's rows), the publication, its schedule row,
     * the card (three reads), the publication's version written on (its current version read, the new one with its
     * audit record), the schedule row and the card (held: the new version and these with the submission's own writes
     * in one statement). The move takes no start lock (Q10-lock-split.md 2.3, LS-5).
     */
    static final Budget ENDING_TOTAL = new Budget(18, 29);
    /** Under the lock: {@link #SUBMIT_LOCKED} and the move's five (per commit: nine). */
    static final Budget ENDING_LOCKED = new Budget(12, 23);
    /**
     * {@code QB_PUBLICATION_PAUSE} of a live publication, in its transaction: the operation, banned?, the publication's
     * lock, then under it the start lock (Q10a4: one statement more than before), the publication, the schedule row,
     * the card (three reads), the publication's version written on (its current version read, the new one with its
     * audit record), the card (a paused publication has no due time: the schedule row stays), the idempotency result
     * (held: the new version, the card and the result in one statement), {@code COMMIT}.
     */
    static final Budget PAUSE_TOTAL = new Budget(10, 12);
    /** Under the publication's lock (the start lock included). */
    static final Budget PAUSE_LOCKED = new Budget(7, 9);

    @Autowired
    WriteBatching batching;

    private int budget(Budget budget) {
        return batching.acrossTransaction() ? budget.held() : budget.perCommit();
    }

    @Test
    void aStartAndAPaidSubmission() {
        warmUp();
        String sponsor = newSponsor("s-budget");
        credit(sponsor, 100_000);
        String publicationId = livePublication(sponsor, "Budget", Map.of("regions", List.of("GLOBAL"), "maxWinners",
            100_000, "budgetCap", 1_000_000));
        // Warm: the first start and submission of the publication, as counted below.
        startAndSubmit(publicationId, null);

        Counted counted = new Counted();
        startAndSubmit(publicationId, counted);

        report("QB_ATTEMPT_START", counted.start);
        report("QB_ATTEMPT_SUBMIT (paid)", counted.submit);
        assertBudget("QB_ATTEMPT_START", counted.start, budget(START_TOTAL), budget(START_LOCKED));
        assertBudget("QB_ATTEMPT_SUBMIT (paid)", counted.submit, budget(SUBMIT_TOTAL), budget(SUBMIT_LOCKED));
        assertThat(counted.startOutside).as(String.join("\n", counted.startOutside)).hasSize(OUTSIDE);
        assertThat(counted.submitOutside).as(String.join("\n", counted.submitOutside)).hasSize(OUTSIDE);
        assertThat(counted.rewardStatus).isEqualTo("PAID");
        // Q10a4: a start takes the start lock only; a completion the publication's lock and the wallet's, never the
        // start lock.
        assertThat(counted.startLocks).containsExactly(PublicationMoves.startLockName(publicationId));
        assertThat(counted.submitLocks).containsExactly(PublicationMoves.lockName(publicationId),
            QbWallets.lockName(QbWallets.SPONSOR, sponsor));
        assertBooksBalance();
    }

    /**
     * The completion that ends the publication (its last winner's place) moves it under the publication's lock alone;
     * a pause takes the start lock after the publication's (Q10a4, Q10-lock-split.md 6.1).
     */
    @Test
    void theEndingCompletionAndAPause() {
        warmUp();
        String sponsor = newSponsor("s-budget-end");
        credit(sponsor, 100_000);
        String ending = livePublication(sponsor, "Budget end", Map.of("regions", List.of("GLOBAL"), "maxWinners", 2));
        // Warm, and the first of the two winners.
        startAndSubmit(ending, null);
        Counted counted = new Counted();
        startAndSubmit(ending, counted);
        assertThat(status(ending)).isEqualTo("ENDED");
        assertThat(moves(ending)).isEqualTo(1);
        report("QB_ATTEMPT_SUBMIT (paid, ends the publication)", counted.submit);
        assertBudget("QB_ATTEMPT_SUBMIT (ends)", counted.submit, budget(ENDING_TOTAL), budget(ENDING_LOCKED));
        // The move takes the publication's lock again (at once), never the start lock.
        assertThat(counted.submitLocks).containsExactly(PublicationMoves.lockName(ending),
            QbWallets.lockName(QbWallets.SPONSOR, sponsor), PublicationMoves.lockName(ending));

        String paused = livePublication(sponsor, "Budget pause", Map.of("regions", List.of("GLOBAL")));
        // Warm: a pause and a resume of the publication, as counted below.
        keyed("QB_PUBLICATION_PAUSE", sponsorToken(sponsor), body("publicationId", paused));
        keyed("QB_PUBLICATION_RESUME", sponsorToken(sponsor), body("publicationId", paused));
        SqlStatementLog.clear();
        keyed("QB_PUBLICATION_PAUSE", sponsorToken(sponsor), body("publicationId", paused));
        List<List<String>> pause = SqlStatementLog.transactions();
        List<String> pauseLocks = SqlStatementLog.locks();
        report("QB_PUBLICATION_PAUSE", pause);
        assertBudget("QB_PUBLICATION_PAUSE", pause, budget(PAUSE_TOTAL), budget(PAUSE_LOCKED));
        // The lock order of design 4.3: the publication's, then its start lock.
        assertThat(pauseLocks).containsExactly(PublicationMoves.lockName(paused),
            PublicationMoves.startLockName(paused));
        assertThat(status(paused)).isEqualTo("PAUSED");
        assertBooksBalance();
    }

    /** The statements of the counted calls. */
    static final class Counted {
        List<List<String>> start;
        List<List<String>> submit;
        List<String> startOutside;
        List<String> submitOutside;
        List<String> startLocks;
        List<String> submitLocks;
        Object rewardStatus;
    }

    /** A new taker starts and submits (both answers right: a reward of 100, paid); counted into {@code counted}. */
    @SuppressWarnings("unchecked")
    private void startAndSubmit(String publicationId, Counted counted) {
        String token = taker(newTaker("t-budget-" + UUID.randomUUID(), null));
        SqlStatementLog.clear();
        Map<String, Object> started = keyed("QB_ATTEMPT_START", token, body("publicationId", publicationId));
        if (counted != null) {
            counted.start = SqlStatementLog.transactions();
            counted.startOutside = SqlStatementLog.outsideTransactions();
            counted.startLocks = SqlStatementLog.locks();
        }
        Map<String, Object> input = body("attemptId", started.get("attemptId"), "publicationId", publicationId,
            "currentIndex", 1, "answers", shown((String) started.get("attemptId"), answers(true, true)));
        SqlStatementLog.clear();
        Map<String, Object> submitted = keyed("QB_ATTEMPT_SUBMIT", token, input);
        if (counted != null) {
            counted.submit = SqlStatementLog.transactions();
            counted.submitOutside = SqlStatementLog.outsideTransactions();
            counted.submitLocks = SqlStatementLog.locks();
            counted.rewardStatus = submitted.get("rewardStatus");
        }
    }

    /** Runs a process as the App does, with an {@code Idempotency-Key} of its own; must succeed. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> keyed(String process, String token, Map<String, Object> input) {
        var result = client.post().uri("/api/processes/" + process + "/latest").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token).header("Idempotency-Key", "budget-" + UUID.randomUUID())
            .bodyValue(input).exchange().expectBody(MAP).returnResult();
        assertThat(result.getStatus().value()).as(process + " answered " + result.getResponseBody()).isEqualTo(200);
        return (Map<String, Object>) result.getResponseBody().get("output");
    }

    private static void report(String name, List<List<String>> transactions) {
        transactions.forEach(t -> System.out.println("[statement-budget] " + name + ": " + t.size() + " in all, "
            + underLock(t).size() + " under the lock\n" + listing(t)));
    }

    private static void assertBudget(String name, List<List<String>> transactions, int total, int locked) {
        // The process's own transaction: the one recording its operation (a cache of the platform's may read in a
        // transaction of its own meanwhile, such as the separation-of-duties rules; not the process's).
        List<List<String>> own = transactions.stream()
            .filter(t -> t.stream().anyMatch(sql -> sql.startsWith("INSERT INTO op_process ("))).toList();
        assertThat(own).as(name + ": one transaction").hasSize(1);
        List<String> statements = own.getFirst();
        assertThat(underLock(statements)).as(name + " under the lock:\n" + listing(statements)).hasSize(locked);
        assertThat(statements).as(name + " in all:\n" + listing(statements)).hasSize(total);
    }

    /**
     * The statements after the first lock of the transaction (the publication's, which a start or a submission takes
     * first), its {@code COMMIT} included.
     */
    static List<String> underLock(List<String> statements) {
        for (int i = 0; i < statements.size(); i++) {
            String sql = statements.get(i);
            if (sql.contains("pg_advisory_xact_lock") || sql.contains("jabiz_xact_lock")) {
                return statements.subList(i + 1, statements.size());
            }
        }
        return List.of();
    }

    private static String listing(List<String> statements) {
        return IntStream.range(0, statements.size())
            .mapToObj(i -> String.format("%3d  %s", i + 1, statements.get(i).replaceAll("\\s+", " ")))
            .collect(Collectors.joining("\n"));
    }
}

package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.Test;
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
 * in docs/quizbuks/perf/Q10a-load-test.md §9. The failure lists the statements.
 *
 * <p>Counted as the App calls: over HTTP, each call with its own {@code Idempotency-Key} (the lookup for a replay is
 * outside the transaction). "Under the lock" is from the statement after the one taking the publication's lock to the
 * {@code COMMIT}, both included, as Q10a §4.2 counted it. Each case runs once before it is counted (warm caches, the
 * sponsor's wallet row there), as in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class AttemptStatementBudgetIT extends PlayItSupport {

    // ---- budgets: [Q10a, before platform phase 16p-1 / with 16p-1 merged, before Q10a3's changes] ------------------

    /**
     * {@code QB_ATTEMPT_START}, a new attempt, in its transaction [— / 16]: the operation (with the lock timeout and
     * its number), banned?, one read of the caller's attempt, the card and the publication, the profile, the version
     * (no country without a profile: no statement), the sponsor's wallet, the publication's lock, then under it.
     */
    static final int START_TOTAL = 12;
    /**
     * Under the lock [12 / 8]: the caller's attempt, the card and the counters again (one statement), the attempt with
     * its audit record (its references to the publication and the version answered by this transaction's reads), the
     * counters at the version read with their audit record, the idempotency result, {@code COMMIT}.
     */
    static final int START_LOCKED = 5;
    /**
     * {@code QB_ATTEMPT_SUBMIT}, a paid reward, in its transaction [— / 22]: the operation, banned?, the attempt, the
     * version, the commission rate, the publication's lock, then under it.
     */
    static final int SUBMIT_TOTAL = 20;
    /**
     * Under the lock [48 / 16]: the attempt, the counters and the card again (one statement), the sponsor's wallet lock
     * and the wallet, {@code LEDGER_POST} as a sub-process (its operation, the account lock, one read, the transaction,
     * the entries), the event, the wallet, attempt and counters each updated at the version read with its audit record,
     * the idempotency result, {@code COMMIT}.
     */
    static final int SUBMIT_LOCKED = 14;
    /** Outside the transaction, each call: the lookup of a replay of its {@code Idempotency-Key}. */
    static final int OUTSIDE = 1;

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
        assertBudget("QB_ATTEMPT_START", counted.start, START_TOTAL, START_LOCKED);
        assertBudget("QB_ATTEMPT_SUBMIT (paid)", counted.submit, SUBMIT_TOTAL, SUBMIT_LOCKED);
        assertThat(counted.startOutside).as(String.join("\n", counted.startOutside)).hasSize(OUTSIDE);
        assertThat(counted.submitOutside).as(String.join("\n", counted.submitOutside)).hasSize(OUTSIDE);
        assertThat(counted.rewardStatus).isEqualTo("PAID");
        assertBooksBalance();
    }

    /** The statements of the counted calls. */
    static final class Counted {
        List<List<String>> start;
        List<List<String>> submit;
        List<String> startOutside;
        List<String> submitOutside;
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
        }
        Map<String, Object> input = body("attemptId", started.get("attemptId"), "publicationId", publicationId,
            "currentIndex", 1, "answers", answers(true, true));
        SqlStatementLog.clear();
        Map<String, Object> submitted = keyed("QB_ATTEMPT_SUBMIT", token, input);
        if (counted != null) {
            counted.submit = SqlStatementLog.transactions();
            counted.submitOutside = SqlStatementLog.outsideTransactions();
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

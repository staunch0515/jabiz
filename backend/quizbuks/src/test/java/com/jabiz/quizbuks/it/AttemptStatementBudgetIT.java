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
class AttemptStatementBudgetIT extends TreeItSupport {

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
     * {@code QB_ATTEMPT_START}, a new attempt, in its transaction [— / 17]: the operation (with the lock timeout and
     * its number), banned?, an overdue cash reward? (Q22), one read of the caller's attempt, the card and the
     * publication, the profile, the version (no country without a profile: no statement), the sponsor's wallet, the
     * start lock, then under it.
     */
    static final Budget START_TOTAL = new Budget(11, 13);
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
     * in one statement). The move takes no start lock (Q10-lock-split.md 2.3, LS-5). Since Q7a the move also publishes
     * {@code qb.publication.status} for the sponsor's mail (the mail itself is sent by its consumer, outside the lock):
     * held, the event goes with the other writes; per commit, one statement more [29].
     */
    static final Budget ENDING_TOTAL = new Budget(18, 30);
    /** Under the lock: {@link #SUBMIT_LOCKED} and the move's five (per commit: ten, the event's included [9]). */
    static final Budget ENDING_LOCKED = new Budget(12, 24);
    /**
     * {@code QB_ATTEMPT_SUBMIT} whose reward the sponsor's wallet cannot pay (Q7a, D-Q7-2): the reward is pending, the
     * publication moves to {@code FUNDS_SHORT} as a sub-process like {@link #ENDING_TOTAL}'s, and the move publishes
     * {@code qb.publication.status} (held: carried by the read of the publication's current version; per commit: one
     * statement of its own). No ledger posting: the wallet's pending count and amount, the attempt and the counters
     * are updated, {@code qb.reward.pending} published.
     */
    static final Budget SHORT_TOTAL = new Budget(16, 24);
    /** Under the lock: the rereads, the wallet's lock and the wallet, the move's (as the ending's), the writes. */
    static final Budget SHORT_LOCKED = new Budget(10, 18);
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
    /**
     * Q88b: {@code QB_ATTEMPT_START} of a quiz charging an entry fee: an ordinary start's statements (the sponsor's
     * wallet read included) and, before the start lock, the caller's wallet lock, its wallet row with the hold period
     * (one statement) and the transferable balance (one); after the start, the save of the attempt (the fee's posting
     * names it as its source) and {@code LEDGER_POST} (its account lock, one read, the entries). Held: an ordinary
     * start's 11 and six more; per commit, 22. "Under the lock" counts from the first lock, the wallet's: [10 / 15].
     */
    static final Budget FEE_TOTAL = new Budget(17, 22);
    static final Budget FEE_LOCKED = new Budget(10, 15);
    /**
     * F-S4a: {@code QB_SPONSOR_ATTEMPT_START}, a new attempt of a challenge: the operation, banned?, one read of the
     * caller's attempt, the card, the publication, the onboarding and the operator, the version, the start lock, then
     * under it {@link #START_LOCKED}. Three fewer than {@link #START_TOTAL}: no overdue cash reward, profile or wallet.
     */
    static final Budget SPONSOR_START_TOTAL = new Budget(8, 10);
    /**
     * F-S4a: {@code QB_SPONSOR_ATTEMPT_SUBMIT} of a challenge's pass: the operation, banned?, the attempt, the version,
     * the commission rate, the publication's lock, then under it the attempt, the counters and the card again (one
     * statement), the event {@code qb.tree.passed}, the attempt and the counters at the version read, the idempotency
     * result (held: these in one statement), {@code COMMIT}. Nothing paid: no wallet lock and no posting.
     */
    static final Budget CHALLENGE_SUBMIT_TOTAL = new Budget(9, 12);
    static final Budget CHALLENGE_SUBMIT_LOCKED = new Budget(3, 6);

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
     * F-S2b1: a publication bound to a knowledge map node reads the node's row with the caller's lit records and
     * revocations, one statement more outside the start lock; the lock's part is an ordinary start's. Its pass sends
     * {@code qb.tree.passed} for the lighting outside the answering: held, the event goes with the other writes (the
     * paid submission's budget, unchanged); per commit, one statement of its own under the lock.
     */
    @Test
    void aStartAndAPassOfAPublicationBoundToANode() {
        warmUp();
        String sponsor = newSponsor("s-budget-node");
        credit(sponsor, 100_000);
        Map3 map = map(sponsor);
        Map<String, Object> global = Map.of("regions", List.of("GLOBAL"), "maxWinners", 100_000, "budgetCap",
            1_000_000);
        String basics = liveBound(sponsor, "Budget node basics", map.treeId(), map.basics(), global);
        String advanced = liveBound(sponsor, "Budget node advanced", map.treeId(), map.advanced(), global);
        // Warm: a taker who lit the basics starts and passes the advanced one, as counted below for the next.
        String warm = newTaker("t-budget-node-warm-" + UUID.randomUUID(), null);
        play(warm, basics, true, true);
        deliverEvents();
        play(warm, advanced, true, true);
        String userId = newTaker("t-budget-node-" + UUID.randomUUID(), null);
        play(userId, basics, true, true);
        deliverEvents();
        String token = taker(userId);

        SqlStatementLog.clear();
        Map<String, Object> started = keyed("QB_ATTEMPT_START", token, body("publicationId", advanced));
        List<List<String>> start = SqlStatementLog.transactions();
        report("QB_ATTEMPT_START (bound to a node)", start);
        assertBudget("QB_ATTEMPT_START (bound to a node)", start, budget(START_TOTAL) + 1, budget(START_LOCKED));
        assertThat(SqlStatementLog.locks()).containsExactly(PublicationMoves.startLockName(advanced));

        SqlStatementLog.clear();
        Map<String, Object> submitted = keyed("QB_ATTEMPT_SUBMIT", token, body("attemptId", started.get("attemptId"),
            "publicationId", advanced, "currentIndex", 1, "answers", shown((String) started.get("attemptId"),
                answers(true, true))));
        assertThat(submitted).containsEntry("passed", true);
        List<List<String>> submit = SqlStatementLog.transactions();
        report("QB_ATTEMPT_SUBMIT (a node's pass)", submit);
        int event = batching.acrossTransaction() ? 0 : 1;
        assertBudget("QB_ATTEMPT_SUBMIT (a node's pass)", submit, budget(SUBMIT_TOTAL) + event,
            budget(SUBMIT_LOCKED) + event);
    }

    /**
     * F-S4a: a Sponsor answering a challenge ({@code QB_SPONSOR_ATTEMPT_START} / {@code _SUBMIT}). The start reads the
     * caller's onboarding and operator in its first statement and nothing of a Taker's (no overdue cash reward, profile,
     * audience, block or wallet); the lock's part is an ordinary start's. The pass pays nothing (no wallet lock, no
     * posting) and sends {@code qb.tree.passed} for the lighting.
     */
    @Test
    void aSponsorStartsAndPassesAChallenge() {
        warmUp();
        Copy copy = copy();
        String publicationId = liveBound(copy.operator().leadId(), "Budget challenge", copy.treeId(), copy.start(),
            Map.of("regions", List.of("GLOBAL"), "maxWinners", 100_000, "budgetCap", 1_000_000, "tiers",
                List.of(body("fromScore", 0, "toScore", 5, "reward", 0))));
        // Warm: another Sponsor of the operator starts and passes it, as counted below.
        challenge(sponsorOf(copy.operator(), "s-budget-challenge-warm"), publicationId);
        String token = sponsorToken(sponsorOf(copy.operator(), "s-budget-challenge"));

        SqlStatementLog.clear();
        Map<String, Object> started = keyed(com.jabiz.quizbuks.play.SponsorPlay.START, token, body("publicationId",
            publicationId));
        List<List<String>> start = SqlStatementLog.transactions();
        report("QB_SPONSOR_ATTEMPT_START", start);
        assertBudget("QB_SPONSOR_ATTEMPT_START", start, budget(SPONSOR_START_TOTAL), budget(START_LOCKED));
        assertThat(SqlStatementLog.locks()).containsExactly(PublicationMoves.startLockName(publicationId));

        SqlStatementLog.clear();
        Map<String, Object> submitted = keyed(com.jabiz.quizbuks.play.SponsorPlay.SUBMIT, token, body("attemptId",
            started.get("attemptId"), "publicationId", publicationId, "currentIndex", 1, "answers",
            shown((String) started.get("attemptId"), answers(true, true))));
        assertThat(submitted).containsEntry("passed", true);
        List<List<String>> submit = SqlStatementLog.transactions();
        report("QB_SPONSOR_ATTEMPT_SUBMIT (a challenge's pass)", submit);
        assertBudget("QB_SPONSOR_ATTEMPT_SUBMIT (a challenge's pass)", submit, budget(CHALLENGE_SUBMIT_TOTAL),
            budget(CHALLENGE_SUBMIT_LOCKED));
        assertThat(SqlStatementLog.locks()).containsExactly(PublicationMoves.lockName(publicationId));
    }

    private void challenge(String sponsor, String publicationId) {
        String token = sponsorToken(sponsor);
        Map<String, Object> started = keyed(com.jabiz.quizbuks.play.SponsorPlay.START, token, body("publicationId",
            publicationId));
        keyed(com.jabiz.quizbuks.play.SponsorPlay.SUBMIT, token, body("attemptId", started.get("attemptId"),
            "publicationId", publicationId, "currentIndex", 1, "answers", shown((String) started.get("attemptId"),
                answers(true, true))));
    }

    /**
     * F-S8: a caller with blocks (none covering the publication) reads the blocks covering the card, one statement more
     * outside the start lock; the lock's part is an ordinary start's. Whether the caller has any block rides on the
     * start's first read, so a caller without blocks sends what {@link #aStartAndAPaidSubmission} counts, unchanged.
     */
    @Test
    void aStartWithBlocks() {
        warmUp();
        String sponsor = newSponsor("s-budget-blocks");
        credit(sponsor, 100_000);
        String other = newSponsor("s-budget-blocks-other");
        credit(other, 10_000);
        String publicationId = livePublication(sponsor, "Budget blocks", Map.of("regions", List.of("GLOBAL"),
            "maxWinners", 100_000, "budgetCap", 1_000_000));
        String elsewhere = livePublication(other, "Budget blocks elsewhere", Map.of("regions", List.of("GLOBAL")));
        List<List<String>> start = null;
        // The first is warm (as in production: the plans cached), the second counted.
        for (int round = 0; round < 2; round++) {
            String token = taker(newTaker("t-budget-blocks-" + UUID.randomUUID(), null));
            keyed(com.jabiz.quizbuks.block.BlockProcesses.ADD, token, body("publicationId", elsewhere, "kind",
                "SPONSOR"));
            SqlStatementLog.clear();
            keyed("QB_ATTEMPT_START", token, body("publicationId", publicationId));
            start = SqlStatementLog.transactions();
        }
        report("QB_ATTEMPT_START (with blocks)", start);
        assertBudget("QB_ATTEMPT_START (with blocks)", start, budget(START_TOTAL) + 1, budget(START_LOCKED));
        assertThat(SqlStatementLog.locks()).containsExactly(PublicationMoves.startLockName(publicationId));
    }

    /**
     * Q88b (D-Q88-12): a start of a quiz charging an entry fee takes the caller's wallet lock before the start lock,
     * and reads under it the wallet row with the hold period (one statement) and the transferable balance (the
     * transfers' template); it saves the attempt for the fee's posting and posts it ({@code LEDGER_POST}). An ordinary
     * start reads, takes and writes none of it (the tests above, unchanged).
     */
    @Test
    void aStartWithAnEntryFee() {
        warmUp();
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", com.jabiz.quizbuks.setup.QbParams.ENTRY_FEE_ENABLED, "value", "true"),
            "reason", "Q88b"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
        // No hold period: the reward just won pays the fee at once (the rule itself is EntryFeeIT's).
        Map<String, Object> hold = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", com.jabiz.quizbuks.setup.QbParams.TRANSFER_HOLD_DAYS, "value", "0"),
            "reason", "Q88b"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", hold.get("changeId")));
        String sponsor = newSponsor("s-budget-fee");
        credit(sponsor, 100_000);
        String rewarding = livePublication(sponsor, "Budget fee rewarding", Map.of("regions", List.of("GLOBAL")));
        String charging = livePublication(sponsor, "Budget fee", Map.of("regions", List.of("GLOBAL"), "entryFee", 10,
            "maxWinners", 100_000, "budgetCap", 1_000_000));
        String warm = newTaker("t-budget-fee-warm-" + UUID.randomUUID(), null);
        play(warm, rewarding, true, true);
        deliverEvents();
        play(warm, charging, true, true);
        String userId = newTaker("t-budget-fee-" + UUID.randomUUID(), null);
        play(userId, rewarding, true, true);
        deliverEvents();

        SqlStatementLog.clear();
        keyed("QB_ATTEMPT_START", taker(userId), body("publicationId", charging));
        List<List<String>> start = SqlStatementLog.transactions();
        report("QB_ATTEMPT_START (entry fee)", start);
        assertBudget("QB_ATTEMPT_START (entry fee)", start, budget(FEE_TOTAL), budget(FEE_LOCKED));
        assertThat(SqlStatementLog.locks()).containsExactly(QbWallets.lockName(QbWallets.TAKER, userId),
            PublicationMoves.startLockName(charging));
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

    /**
     * A completion whose reward is pending moves the publication to {@code FUNDS_SHORT} under the publication's lock and
     * announces it for the sponsor's mail in the same transaction; nothing is sent there.
     */
    @Test
    void theUnderfundedCompletion() {
        warmUp();
        String sponsor = newSponsor("s-budget-short");
        credit(sponsor, 100);
        String shortOf = livePublication(sponsor, "Budget short", Map.of("regions", List.of("GLOBAL")));
        String first = taker(newTaker("t-budget-" + UUID.randomUUID(), null));
        String second = taker(newTaker("t-budget-" + UUID.randomUUID(), null));
        String a = (String) keyed("QB_ATTEMPT_START", first, body("publicationId", shortOf)).get("attemptId");
        String b = (String) keyed("QB_ATTEMPT_START", second, body("publicationId", shortOf)).get("attemptId");
        keyed("QB_ATTEMPT_SUBMIT", first, body("attemptId", a, "publicationId", shortOf, "currentIndex", 1,
            "answers", shown(a, answers(true, true))));
        SqlStatementLog.clear();
        Map<String, Object> pending = keyed("QB_ATTEMPT_SUBMIT", second, body("attemptId", b, "publicationId", shortOf,
            "currentIndex", 1, "answers", shown(b, answers(true, true))));
        List<List<String>> submit = SqlStatementLog.transactions();
        assertThat(pending).containsEntry("rewardStatus", "PENDING");
        assertThat(status(shortOf)).isEqualTo("FUNDS_SHORT");
        report("QB_ATTEMPT_SUBMIT (pending, underfunds the publication)", submit);
        assertBudget("QB_ATTEMPT_SUBMIT (underfunds)", submit, budget(SHORT_TOTAL), budget(SHORT_LOCKED));
        assertThat(submit.stream().flatMap(List::stream)).noneMatch(sql -> sql.contains("sys_mail_message"));
        assertThat(query("SELECT count(*) AS n FROM sys_outbox_event WHERE event_type = 'qb.publication.status' "
            + "AND payload::jsonb ->> 'publicationId' = ?", shortOf).getFirst()).containsEntry("n", 1L);
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

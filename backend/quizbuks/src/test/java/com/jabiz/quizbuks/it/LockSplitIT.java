package com.jabiz.quizbuks.it;

import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.publishing.PublicationMoves;
import com.jabiz.quizbuks.wallet.QbWallets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lock split (Q10a4, docs/quizbuks/plans/Q10-lock-split.md 6.2): a start holds the publication's start lock
 * {@code qb.pub-start:<p>}, a completion its lock {@code qb.pub:<p>}, and the two run side by side, while every
 * guarantee holds: winners' places and budget used exactly and never exceeded, {@code ENDED} once, the counters' two
 * sides agreeing with the attempts and each other (I1 – I5) and the books balanced; the state processes take both locks
 * in order without deadlocking with the answering; and a start that comes while the last place is being completed is
 * refused even when it reads the card still live.
 */
class LockSplitIT extends PlayItSupport {

    private final List<ExecutorService> pools = new ArrayList<>();

    @AfterEach
    void stopPools() {
        pools.forEach(ExecutorService::shutdownNow);
        pools.clear();
    }

    // ---- 1. the two halves run side by side --------------------------------------------------------------------------

    @Test
    void aStartAndASubmissionRunWhileTheOtherLockIsHeldAndAPauseTakesBothInOrder() throws Exception {
        warmUp();
        String sponsor = newSponsor("s-split-parallel");
        credit(sponsor, 10_000);
        String publication = livePublication(sponsor, "Split parallel", Map.of("regions", List.of("GLOBAL")));
        String lock = PublicationMoves.lockName(publication);
        String startLock = PublicationMoves.startLockName(publication);
        String first = taker(newTaker("t-split-parallel-1", null));
        String firstAttempt = (String) start(first, publication).get("attemptId");

        // The publication's lock held (a long completion): a start goes through; a submission waits for it.
        String second = taker(newTaker("t-split-parallel-2", null));
        String secondAttempt;
        CompletableFuture<EntityExchangeResult<Map<String, Object>>> waitingSubmission;
        try (Connection holder = DB.connect(schema())) {
            holdLock(holder, lock);
            long began = System.nanoTime();
            EntityExchangeResult<Map<String, Object>> started = startCall(second, publication);
            assertThat(started.getStatus().value()).as(String.valueOf(started.getResponseBody())).isEqualTo(200);
            assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(5));
            secondAttempt = attemptIdOf(started);
            assertThat(counter(publication)).containsEntry("starts", new BigDecimal("2"))
                .containsEntry("completions", BigDecimal.ZERO);
            waitingSubmission = background(() -> submitCall(first, firstAttempt, publication, answers(true, true)));
            awaitThat(() -> waiters(lock) == 1, "the submission waits for the publication's lock");
            assertThat(waitingSubmission).isNotDone();
            holder.rollback();
        }
        assertThat(waitingSubmission.get(30, TimeUnit.SECONDS).getStatus().value()).isEqualTo(200);

        // The start lock held (a long start): a submission goes through; a start waits for it.
        String third = taker(newTaker("t-split-parallel-3", null));
        CompletableFuture<EntityExchangeResult<Map<String, Object>>> waitingStart;
        try (Connection holder = DB.connect(schema())) {
            holdLock(holder, startLock);
            long began = System.nanoTime();
            EntityExchangeResult<Map<String, Object>> submitted = submitCall(second, secondAttempt, publication,
                answers(true, true));
            assertThat(submitted.getStatus().value()).as(String.valueOf(submitted.getResponseBody())).isEqualTo(200);
            assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(5));
            assertThat(counter(publication)).containsEntry("completions", new BigDecimal("2"));
            waitingStart = background(() -> startCall(third, publication));
            awaitThat(() -> waiters(startLock) == 1, "the start waits for the start lock");
            assertThat(waitingStart).isNotDone();
            holder.rollback();
        }
        String thirdAttempt = attemptIdOf(waitingStart.get(30, TimeUnit.SECONDS));

        // A pause takes the publication's lock, then the start lock (design 4.3): with the start lock held it waits
        // holding the publication's, so a submission waits behind it; released, both go through.
        CompletableFuture<EntityExchangeResult<Map<String, Object>>> pause;
        CompletableFuture<EntityExchangeResult<Map<String, Object>>> behindThePause;
        try (Connection holder = DB.connect(schema())) {
            holdLock(holder, startLock);
            pause = background(() -> post("QB_PUBLICATION_PAUSE", sponsorToken(sponsor),
                body("publicationId", publication)));
            awaitThat(() -> waiters(startLock) == 1, "the pause waits for the start lock");
            behindThePause = background(() -> submitCall(third, thirdAttempt, publication, answers(true, true)));
            awaitThat(() -> waiters(lock) == 1, "the submission waits for the pause's publication lock");
            assertThat(pause).isNotDone();
            assertThat(behindThePause).isNotDone();
            holder.rollback();
        }
        assertThat(pause.get(30, TimeUnit.SECONDS).getStatus().value()).isEqualTo(200);
        assertThat(behindThePause.get(30, TimeUnit.SECONDS).getStatus().value()).isEqualTo(200);
        assertThat(status(publication)).isEqualTo("PAUSED");
        assertInvariants(publication, null, null);
        assertBooksBalance();
    }

    // ---- 2, 3. the limits used exactly ----------------------------------------------------------------------------

    /**
     * Twenty winners' places, every answer right: ten start first, then their submissions race the others' starts;
     * a start refused for want of a place tries again until the publication ends. Exactly twenty start and win, the
     * publication ends once (by its winners), and every start afterwards is refused.
     */
    @Test
    void theWinnersPlacesAreUsedExactlyWhileStartsAndSubmissionsRace() throws Exception {
        warmUp();
        int places = 20;
        String sponsor = newSponsor("s-split-quota");
        credit(sponsor, 100_000);
        String publication = livePublication(sponsor, "Split quota", Map.of("regions", List.of("GLOBAL"),
            "maxWinners", places));
        race(publication, "t-split-quota-", 64, 10);

        assertThat(counter(publication)).containsEntry("winners", BigDecimal.valueOf(places))
            .containsEntry("starts", BigDecimal.valueOf(places)).containsEntry("completions", BigDecimal.valueOf(places));
        assertThat(attemptCount(publication)).isEqualTo(places);
        assertEndedOnce(publication, "QUOTA");
        assertThat(codes(refused("QB_ATTEMPT_START", taker(newTaker("t-split-quota-late", null)),
            body("publicationId", publication), 422))).containsExactly(PlayCodes.NOT_OPEN);
        assertInvariants(publication, places, null);
        assertBooksBalance();
    }

    /** As above with a budget of twenty highest rewards and no binding winners' limit: spent is the budget exactly. */
    @Test
    void theBudgetIsUsedExactlyWhileStartsAndSubmissionsRace() throws Exception {
        warmUp();
        int rewards = 20;
        BigDecimal cap = BigDecimal.valueOf(rewards * 100L);
        String sponsor = newSponsor("s-split-budget");
        credit(sponsor, 100_000);
        String publication = livePublication(sponsor, "Split budget", Map.of("regions", List.of("GLOBAL"),
            "maxWinners", 1_000, "budgetCap", cap));
        race(publication, "t-split-budget-", 64, 10);

        assertThat((BigDecimal) counter(publication).get("spent")).isEqualByComparingTo(cap);
        assertThat(attemptCount(publication)).isEqualTo(rewards);
        assertEndedOnce(publication, "BUDGET");
        assertInvariants(publication, null, cap);
        assertBooksBalance();
    }

    /**
     * {@code takers} takers; the first {@code first} start one after the other, then the others' starts race those
     * submissions (each admitted taker submits both answers right at once). A start refused for a full publication
     * tries again until the publication ends; a busy one (503) again with its key.
     */
    private void race(String publication, String prefix, int takers, int first) throws Exception {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < takers; i++) {
            tokens.add(taker(newTaker(prefix + i, null)));
        }
        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < first; i++) {
            String token = tokens.get(i);
            String attemptId = attemptIdOf(startCall(token, publication));
            work.add(() -> {
                submitUntilDone(token, attemptId, publication, answers(true, true));
                return null;
            });
        }
        for (int i = first; i < takers; i++) {
            String token = tokens.get(i);
            work.add(() -> {
                String attemptId = startUntilAdmittedOrEnded(token, publication);
                if (attemptId != null) {
                    submitUntilDone(token, attemptId, publication, answers(true, true));
                }
                return null;
            });
        }
        // A thread each: a start retrying for a place must not keep a submission that frees it from running.
        runAll(work, work.size());
    }

    /** Starts until admitted (the attempt) or the publication no longer takes attempts (null). */
    private String startUntilAdmittedOrEnded(String token, String publication) throws InterruptedException {
        String key = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) {
            EntityExchangeResult<Map<String, Object>> result = startCall(token, publication, key);
            int status = result.getStatus().value();
            if (status == 200) {
                return attemptIdOf(result);
            }
            if (busy(result)) {
                continue;
            }
            assertThat(status).as(String.valueOf(result.getResponseBody())).isEqualTo(422);
            List<Object> codes = codes(result.getResponseBody());
            if (codes.contains(PlayCodes.NOT_OPEN)) {
                return null;
            }
            assertThat(codes).containsAnyOf(PlayCodes.QUOTA_FULL, PlayCodes.BUDGET_FULL);
            // A refused start never ran: a new key for the next try (the App's "again"), at a person's pace.
            key = UUID.randomUUID().toString();
            Thread.sleep(200);
        }
        throw new AssertionError("a start neither admitted nor refused for good within 90 s");
    }

    private void submitUntilDone(String token, String attemptId, String publication, List<Object> answers)
        throws InterruptedException {
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 400; i++) {
            EntityExchangeResult<Map<String, Object>> result = submitCall(token, attemptId, publication, answers, key);
            if (busy(result)) {
                continue;
            }
            assertThat(result.getStatus().value()).as(String.valueOf(result.getResponseBody())).isEqualTo(200);
            return;
        }
        throw new AssertionError("a submission stayed busy");
    }

    // ---- 4. mixed rewards, rounds of starts, submissions and abandoned attempts -------------------------------------

    /**
     * Two hundred takers in four rounds: each starts, then submits with random answers (100, 10 or 0 Kudos) or leaves
     * the attempt to the expiry job. After every round (and the job): I1 – I5 and the books.
     */
    @Test
    void mixedRewardsKeepTheInvariantsRoundAfterRound() throws Exception {
        warmUp();
        int winnersCap = 30;
        BigDecimal cap = new BigDecimal("2050");
        String sponsor = newSponsor("s-split-mixed");
        credit(sponsor, 100_000);
        String publication = livePublication(sponsor, "Split mixed", Map.of("regions", List.of("GLOBAL"),
            "maxWinners", winnersCap, "budgetCap", cap));
        Random random = new Random(20261017L);
        for (int round = 0; round < 4; round++) {
            List<Callable<Void>> work = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                String token = taker(newTaker("t-split-mixed-" + round + "-" + i, null));
                int choice = random.nextInt(10);
                work.add(() -> {
                    String attemptId = startOnce(token, publication);
                    if (attemptId != null && choice < 7) {
                        submitUntilDone(token, attemptId, publication, answers(choice % 3 != 2, choice % 3 != 1));
                    }
                    return null;
                });
            }
            runAll(work, 16);
            expireAll();
            assertInvariants(publication, winnersCap, cap);
            assertBooksBalance();
        }
        assertThat(query("SELECT count(*) AS n FROM qb_attempt WHERE publication_id = ?::uuid AND reward = 10",
            publication).getFirst().get("n")).as("some lower rewards").isNotEqualTo(0L);
        assertThat(countEnded(publication)).isLessThanOrEqualTo(1);
    }

    /** One start as the App does it (a busy one again with its key); the attempt, or null when refused (422). */
    private String startOnce(String token, String publication) throws InterruptedException {
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 400; i++) {
            EntityExchangeResult<Map<String, Object>> result = startCall(token, publication, key);
            int status = result.getStatus().value();
            if (status == 200) {
                return attemptIdOf(result);
            }
            if (busy(result)) {
                continue;
            }
            assertThat(status).as(String.valueOf(result.getResponseBody())).isEqualTo(422);
            return null;
        }
        throw new AssertionError("a start stayed busy");
    }

    /** Moves the clock past every deadline and its grace, and runs the expiry job until nothing is due. */
    private void expireAll() {
        clock.advance(Duration.ofMinutes(10));
        for (int i = 0; i < 20 && runExpire(clock.instant()).items().succeeded() > 0; i++) {
            // Until nothing is due.
        }
    }

    // ---- 5. the state processes under load --------------------------------------------------------------------------

    /**
     * Takers start and submit while the sponsor pauses and resumes, the minute's job's item moves the publication on,
     * and credits settle pending rewards and put the underfunded publication back on (each of these takes the
     * publication's lock and then its start lock). No deadlock (409) or failure (500), no start sent after a pause
     * committed and before the next resume began gets in, and the invariants and books hold.
     */
    @Test
    void theStateProcessesTakeBothLocksUnderLoadWithoutDeadlock() throws Exception {
        warmUp();
        String sponsor = newSponsor("s-split-states");
        // A small balance: rewards go pending, the publication underfunded, and the credits below restore it.
        credit(sponsor, 500);
        String publication = livePublication(sponsor, "Split states", Map.of("regions", List.of("GLOBAL"),
            "maxWinners", 1_000, "budgetCap", 1_000_000));
        long deadlocksBefore = deadlocks();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            tokens.add(taker(newTaker("t-split-states-" + i, null)));
        }
        List<String> probeTokens = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            probeTokens.add(taker(newTaker("t-split-states-probe-" + i, null)));
        }
        ConcurrentLinkedQueue<List<Object>> probes = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<long[]> pausedWindows = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<long[]> admittedStarts = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> unexpected = new ConcurrentLinkedQueue<>();
        AtomicBoolean running = new AtomicBoolean(true);
        List<Callable<Void>> work = new ArrayList<>();
        Random random = new Random(4317L);
        for (String token : tokens) {
            boolean submits = random.nextInt(10) < 8;
            work.add(() -> {
                String key = UUID.randomUUID().toString();
                for (int i = 0; i < 400; i++) {
                    long sent = System.nanoTime();
                    EntityExchangeResult<Map<String, Object>> result = startCall(token, publication, key);
                    int status = result.getStatus().value();
                    if (busy(result)) {
                        continue;
                    }
                    if (status == 200) {
                        admittedStarts.add(new long[] {sent, System.nanoTime()});
                        if (submits) {
                            submitUntilDone(token, attemptIdOf(result), publication, answers(true, true));
                        }
                    } else if (status != 422) {
                        unexpected.add("start " + status + " " + result.getResponseBody());
                    }
                    return null;
                }
                return null;
            });
        }
        work.add(() -> {
            String token = sponsorToken(sponsor);
            for (int i = 0; i < 6 && running.get(); i++) {
                EntityExchangeResult<Map<String, Object>> paused = post("QB_PUBLICATION_PAUSE", token,
                    body("publicationId", publication));
                long pausedAt = System.nanoTime();
                record(unexpected, "pause", paused);
                if (paused.getStatus().value() == 200) {
                    // A start of a taker of its own, sent and answered inside the paused window: the window check
                    // below cannot pass for want of starts in it.
                    probes.add(startCodes(probeTokens.get(i), publication));
                }
                Thread.sleep(40);
                long resuming = System.nanoTime();
                if (paused.getStatus().value() == 200) {
                    pausedWindows.add(new long[] {pausedAt, resuming});
                }
                record(unexpected, "resume", post("QB_PUBLICATION_RESUME", token,
                    body("publicationId", publication)));
                Thread.sleep(40);
            }
            return null;
        });
        work.add(() -> {
            for (int i = 0; i < 10 && running.get(); i++) {
                systemRun("QB_PUBLICATION_ADVANCE", body("publicationId", publication));
                // As the event's FUNDS_RESTORED runs it for each underfunded publication (a resume may have put this
                // one back on first: then it leaves it as it is, after taking both locks all the same).
                systemRun("QB_PUBLICATION_FUNDED", body("publicationId", publication));
                Thread.sleep(30);
            }
            return null;
        });
        work.add(() -> {
            for (int i = 0; i < 8 && running.get(); i++) {
                Thread.sleep(60);
                // The credit settles what is pending; its event puts the publication back on (FUNDS_RESTORED, FUNDED).
                credit(sponsor, 400);
            }
            return null;
        });
        try {
            runAll(work, 24);
        } finally {
            running.set(false);
        }
        // The real signal of a deadlock or a lock timeout: a 409 (or a 500) of any call, collected as it happened.
        assertThat(unexpected).as("no deadlock, conflict or failure").isEmpty();
        // A start sent after a pause returned and answered before its resume began would have got in while paused.
        for (long[] window : pausedWindows) {
            for (long[] start : admittedStarts) {
                assertThat(start[0] > window[0] && start[1] < window[1]).as("a start admitted while paused").isFalse();
            }
        }
        assertThat(pausedWindows).as("some pauses went through").isNotEmpty();
        // Not vacuous: every paused window had a start in it, and the pause turned it away.
        assertThat(probes).hasSameSizeAs(pausedWindows)
            .allSatisfy(codes -> assertThat(codes).as("a start inside a paused window").contains(PlayCodes.NOT_OPEN));
        // Only a backup of the above (late and for the whole database): the statistics are flushed first.
        assertThat(deadlocksAfterFlush()).as("deadlocks in the database").isEqualTo(deadlocksBefore);
        for (String process : List.of("QB_PUBLICATION_ADVANCE", "QB_PUBLICATION_FUNDED")) {
            assertThat(((Number) query("SELECT count(*) AS n FROM op_process WHERE process_name = ? "
                + "AND input_summary ->> 'publicationId' = ?", process, publication).getFirst().get("n")).longValue())
                .as(process + " ran on the publication").isPositive();
        }
        credit(sponsor, 100_000);
        expireAll();
        assertInvariants(publication, null, null);
        assertBooksBalance();
    }

    private static void record(ConcurrentLinkedQueue<String> unexpected, String what,
        EntityExchangeResult<Map<String, Object>> result) {
        int status = result.getStatus().value();
        // 422: a pause of an ended publication or a resume of one not paused; 503 never (no queue for these).
        if (status != 200 && status != 422) {
            unexpected.add(what + " " + status + " " + result.getResponseBody());
        }
    }

    /**
     * The database's deadlocks so far (pg_stat_database: the whole database, not only this test's schema). A backup
     * check only: the calls' own 409 / 500 are the signal.
     */
    private static long deadlocks() {
        return ((Number) query("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()")
            .getFirst().get("deadlocks")).longValue();
    }

    /** {@link #deadlocks()} once the backends' pending statistics are flushed (they reach the view late). */
    private static long deadlocksAfterFlush() throws InterruptedException {
        query("SELECT pg_stat_force_next_flush()");
        query("SELECT pg_stat_clear_snapshot()");
        Thread.sleep(1_000);
        return deadlocks();
    }

    /** One start, retried while busy as the App does: its HTTP status and rule codes. */
    private List<Object> startCodes(String token, String publication) throws InterruptedException {
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 400; i++) {
            EntityExchangeResult<Map<String, Object>> result = startCall(token, publication, key);
            if (busy(result)) {
                continue;
            }
            List<Object> codes = new ArrayList<>(codes(result.getResponseBody()));
            codes.add(0, result.getStatus().value());
            return codes;
        }
        throw new AssertionError("a start stayed busy");
    }

    // ---- 6. a start while the last place is being completed ---------------------------------------------------------

    /**
     * A start waiting for the start lock while the last winner's completion ends the publication (that completion takes
     * no start lock) is refused once it gets the lock: the publication no longer takes attempts.
     */
    @Test
    void aStartQueuedDuringTheFinalCompletionIsRefused() throws Exception {
        warmUp();
        String sponsor = newSponsor("s-split-final");
        credit(sponsor, 10_000);
        String publication = livePublication(sponsor, "Split final", Map.of("regions", List.of("GLOBAL"),
            "maxWinners", 2));
        play(newTaker("t-split-final-1", null), publication, true, true);
        String last = taker(newTaker("t-split-final-2", null));
        String lastAttempt = (String) start(last, publication).get("attemptId");
        String late = taker(newTaker("t-split-final-3", null));
        String startLock = PublicationMoves.startLockName(publication);
        CompletableFuture<EntityExchangeResult<Map<String, Object>>> queued;
        try (Connection holder = DB.connect(schema())) {
            holdLock(holder, startLock);
            queued = background(() -> startCall(late, publication));
            awaitThat(() -> waiters(startLock) == 1, "the start waits for the start lock");
            // The last place completes and ends the publication meanwhile: its lock is free.
            EntityExchangeResult<Map<String, Object>> submitted = submitCall(last, lastAttempt, publication,
                answers(true, true));
            assertThat(submitted.getStatus().value()).isEqualTo(200);
            assertThat(status(publication)).isEqualTo("ENDED");
            assertThat(queued).isNotDone();
            holder.rollback();
        }
        EntityExchangeResult<Map<String, Object>> refused = queued.get(30, TimeUnit.SECONDS);
        assertThat(refused.getStatus().value()).isEqualTo(422);
        assertThat(codes(refused.getResponseBody())).containsAnyOf(PlayCodes.NOT_OPEN, PlayCodes.QUOTA_FULL);
        assertThat(attemptCount(publication)).isEqualTo(2);
        assertEndedOnce(publication, "QUOTA");
        assertInvariants(publication, 2, null);
    }

    /**
     * The case the algebra covers (Q10-lock-split.md 2.3): the last winner's completion holds the publication's lock
     * and waits (here for the sponsor's wallet lock, which the test holds) while a start reads the card still live; the
     * limits refuse it, since the completing attempt still holds its place. Then the completion ends the publication.
     */
    @Test
    void aStartReadingTheCardStillLiveDuringTheFinalCompletionIsRefusedByTheLimits() throws Exception {
        warmUp();
        String sponsor = newSponsor("s-split-live");
        credit(sponsor, 10_000);
        String publication = livePublication(sponsor, "Split live", Map.of("regions", List.of("GLOBAL"),
            "maxWinners", 2));
        play(newTaker("t-split-live-1", null), publication, true, true);
        String last = taker(newTaker("t-split-live-2", null));
        String lastAttempt = (String) start(last, publication).get("attemptId");
        String walletLock = QbWallets.lockName(QbWallets.SPONSOR, sponsor);
        CompletableFuture<EntityExchangeResult<Map<String, Object>>> completing;
        try (Connection holder = DB.connect(schema())) {
            holdLock(holder, walletLock);
            completing = background(() -> submitCall(last, lastAttempt, publication, answers(true, true)));
            awaitThat(() -> waiters(walletLock) == 1, "the completion waits for the wallet, holding the publication");
            assertThat(card(publication)).containsEntry("status", "LIVE");
            EntityExchangeResult<Map<String, Object>> refused = startCall(taker(newTaker("t-split-live-3", null)),
                publication);
            assertThat(refused.getStatus().value()).as(String.valueOf(refused.getResponseBody())).isEqualTo(422);
            assertThat(codes(refused.getResponseBody())).containsExactly(PlayCodes.QUOTA_FULL);
            assertThat(card(publication)).containsEntry("status", "LIVE");
            holder.rollback();
        }
        assertThat(completing.get(30, TimeUnit.SECONDS).getStatus().value()).isEqualTo(200);
        assertEndedOnce(publication, "QUOTA");
        assertThat(attemptCount(publication)).isEqualTo(2);
        assertInvariants(publication, 2, null);
        assertBooksBalance();
    }

    // ---- invariants --------------------------------------------------------------------------------------------------

    /**
     * I1 {@code w <= W}; I2 {@code X <= B}; I3 {@code s >= c} (both sides in one statement); I4
     * {@code R - L = (s - c) m}; I5 the counters are the attempts': starts the attempts, completions the finished ones,
     * winners those with a reward, spent the rewards' sum. Run when nothing is in flight.
     */
    void assertInvariants(String publication, Integer winnersCap, BigDecimal budgetCap) {
        Map<String, Object> counter = counter(publication);
        long starts = number(counter.get("starts"));
        long completions = number(counter.get("completions"));
        long winners = number(counter.get("winners"));
        BigDecimal spent = (BigDecimal) counter.get("spent");
        BigDecimal maxReward = (BigDecimal) card(publication).get("max_reward");
        if (winnersCap != null) {
            assertThat(winners).as("I1").isLessThanOrEqualTo(winnersCap);
        }
        if (budgetCap != null) {
            assertThat(spent).as("I2").isLessThanOrEqualTo(budgetCap);
        }
        assertThat(starts).as("I3").isGreaterThanOrEqualTo(completions);
        assertThat((BigDecimal) counter.get("reserved")).as("I4")
            .isEqualByComparingTo(maxReward.multiply(BigDecimal.valueOf(starts - completions)));
        Map<String, Object> actual = query("SELECT count(*) AS starts, count(*) FILTER (WHERE status <> "
            + "'IN_PROGRESS') AS completions, count(*) FILTER (WHERE reward > 0) AS winners, coalesce(sum(reward), 0) "
            + "AS spent FROM qb_attempt WHERE publication_id = ?::uuid", publication).getFirst();
        assertThat(starts).as("I5 starts").isEqualTo(actual.get("starts"));
        assertThat(completions).as("I5 completions").isEqualTo(actual.get("completions"));
        assertThat(winners).as("I5 winners").isEqualTo(actual.get("winners"));
        assertThat(spent).as("I5 spent").isEqualByComparingTo((BigDecimal) actual.get("spent"));
    }

    private void assertEndedOnce(String publication, String reason) {
        assertThat(status(publication)).isEqualTo("ENDED");
        assertThat(row(publication)).containsEntry("end_reason", reason);
        assertThat(countEnded(publication)).as("the publication's versions moving to ENDED").isEqualTo(1);
        assertThat(moves(publication)).as("completions that moved it").isEqualTo(1);
    }

    /** The publication's versions that moved it to {@code ENDED} (from another state). */
    private static long countEnded(String publication) {
        return ((Number) query("SELECT count(*) AS n FROM (SELECT status, lag(status) OVER (ORDER BY version_no) AS "
            + "before FROM qb_publication_version WHERE publication_id = ?::uuid) v WHERE status = 'ENDED' "
            + "AND before IS DISTINCT FROM 'ENDED'", publication).getFirst().get("n")).longValue();
    }

    private static long attemptCount(String publication) {
        return ((Number) query("SELECT count(*) AS n FROM qb_attempt WHERE publication_id = ?::uuid", publication)
            .getFirst().get("n")).longValue();
    }

    private static long number(Object value) {
        return ((BigDecimal) value).longValueExact();
    }

    // ---- calls and locks ---------------------------------------------------------------------------------------------

    private EntityExchangeResult<Map<String, Object>> post(String process, String token, Map<String, Object> body) {
        return post(process, token, body, UUID.randomUUID().toString());
    }

    private EntityExchangeResult<Map<String, Object>> post(String process, String token, Map<String, Object> body,
        String key) {
        return client.post().uri("/api/processes/" + process + "/latest").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token).header("Idempotency-Key", key).bodyValue(body).exchange()
            .expectBody(MAP).returnResult();
    }

    private EntityExchangeResult<Map<String, Object>> startCall(String token, String publication) {
        return startCall(token, publication, UUID.randomUUID().toString());
    }

    private EntityExchangeResult<Map<String, Object>> startCall(String token, String publication, String key) {
        return post("QB_ATTEMPT_START", token, body("publicationId", publication), key);
    }

    private EntityExchangeResult<Map<String, Object>> submitCall(String token, String attemptId, String publication,
        List<Object> answers) {
        return submitCall(token, attemptId, publication, answers, UUID.randomUUID().toString());
    }

    private EntityExchangeResult<Map<String, Object>> submitCall(String token, String attemptId, String publication,
        List<Object> answers, String key) {
        return post("QB_ATTEMPT_SUBMIT", token, body("attemptId", attemptId, "publicationId", publication,
            "currentIndex", 1, "answers", shown(attemptId, answers)), key);
    }

    /**
     * Whether the call was turned away as the App retries it, after waiting as it would: 503 {@code PROCESS_BUSY}
     * (the queue) briefly, 429 {@code RATE_LIMITED} (the player's limit, 60 a minute) for its {@code Retry-After}.
     */
    private static boolean busy(EntityExchangeResult<Map<String, Object>> result) throws InterruptedException {
        int status = result.getStatus().value();
        if (status == 503) {
            assertThat(String.valueOf(result.getResponseBody())).contains(PlatformErrorCodes.PROCESS_BUSY);
            Thread.sleep(20);
            return true;
        }
        if (status == 429) {
            assertThat(String.valueOf(result.getResponseBody())).contains("RATE_LIMITED");
            Thread.sleep(1_000);
            return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static String attemptIdOf(EntityExchangeResult<Map<String, Object>> started) {
        assertThat(started.getStatus().value()).as(String.valueOf(started.getResponseBody())).isEqualTo(200);
        return (String) ((Map<String, Object>) started.getResponseBody().get("output")).get("attemptId");
    }

    /** Runs the work on {@code threads} threads at once; fails with the first failure. */
    private void runAll(List<Callable<Void>> work, int threads) throws Exception {
        List<Callable<Void>> shuffled = new ArrayList<>(work);
        Collections.shuffle(shuffled, new Random(7L));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        pools.add(pool);
        List<Future<Void>> results = new ArrayList<>();
        for (Callable<Void> item : shuffled) {
            results.add(pool.submit(item));
        }
        for (Future<Void> result : results) {
            result.get(5, TimeUnit.MINUTES);
        }
    }

    private <T> CompletableFuture<T> background(Callable<T> work) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pools.add(pool);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, pool);
    }

    /** Holds an application lock in the holder's transaction, as a long transaction under it would. */
    private static void holdLock(Connection holder, String lock) throws Exception {
        holder.setAutoCommit(false);
        try (PreparedStatement statement = holder.prepareStatement(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            statement.setString(1, "jabiz.app-lock:" + lock);
            statement.execute();
        }
    }

    /** Sessions waiting for the named application lock. */
    private static int waiters(String lock) {
        return ((Number) query("SELECT count(*) AS n FROM pg_locks WHERE locktype = 'advisory' AND NOT granted"
            + " AND ((classid::bigint << 32) | objid::bigint) = hashtextextended(?, 0)", "jabiz.app-lock:" + lock)
            .getFirst().get("n")).intValue();
    }

    private static void awaitThat(BooleanSupplier condition, String what) throws InterruptedException {
        for (int i = 0; i < 800 && !condition.getAsBoolean(); i++) {
            Thread.sleep(25);
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }
}

package com.jabiz.quizbuks.it;

import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.play.AttemptFinishing;
import com.jabiz.quizbuks.play.AttemptProcesses;
import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.publishing.PublicationMoves;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.process.admission.AdmissionSettings;
import com.jabiz.runtime.process.admission.ProcessAdmission;
import com.jabiz.runtime.web.ProcessRateLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The queues before a publication's two locks (platform decision D45; docs/quizbuks/plans/Q10-launch.md, Q10a2;
 * Q10a4, docs/quizbuks/plans/Q10-lock-split.md): {@code QB_ATTEMPT_START} queues for the start lock
 * {@code qb.pub-start:<publication>}, {@code QB_ATTEMPT_SUBMIT} with its publication and the expiry for the
 * publication's lock {@code qb.pub:<publication>}, before their transaction, so that a hot publication's waiters hold
 * no database connection and starts never queue behind completions (nor these behind starts); a submission naming
 * another publication than its attempt's is refused before the lock, one naming none is 400 (the publication is
 * required: nothing skips the queue); the home feed keeps its connections while a hot publication overflows its queue;
 * a retry with the same key after a refusal runs once, after a commit replays; and an expiry turned away by a full
 * queue does not let a late submission in.
 * Here each queue waits long (20 s) and holds 6, so that what is queued, waiting and refused is exact.
 */
@SpringBootTest(properties = {
    "jabiz.process.admission.families[qb.pub].max-queue=6",
    "jabiz.process.admission.families[qb.pub].max-wait=20s",
    "jabiz.process.admission.families[qb.pub-start].max-queue=6",
    "jabiz.process.admission.families[qb.pub-start].max-wait=20s"})
class PlayAdmissionIT extends PlayItSupport {

    @Autowired
    ProcessAdmission admission;

    @Autowired
    AdmissionSettings settings;

    @Autowired
    ProcessRateLimits rateLimits;

    private final List<ExecutorService> threads = new ArrayList<>();

    @AfterEach
    void stopThreads() {
        threads.forEach(ExecutorService::shutdownNow);
        threads.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theProcessesQueueUnderTheirLocksNames() {
        UUID publicationId = UUID.randomUUID();
        String lock = PublicationMoves.lockName(publicationId);
        String startLock = PublicationMoves.startLockName(publicationId);
        assertThat(PublicationMoves.LOCK_FAMILY).isEqualTo("qb.pub");
        assertThat(PublicationMoves.START_LOCK_FAMILY).isEqualTo("qb.pub-start");
        assertThat(startLock).isEqualTo("qb.pub-start:" + publicationId)
            // Not a lock of the family qb.pub, as the platform tells them apart (ProcessAdmission.checkLock).
            .doesNotStartWith(PublicationMoves.LOCK_FAMILY + ":");
        // The same text as the lock: no warning from HoldLock (decision D45, implementation note 6).
        var start = (ProcessDefinition<AttemptProcesses.PublicationRef, ?, ProcessContext>) processes
            .findLatest(AttemptProcesses.START).orElseThrow();
        assertThat(start.admission().family()).isEqualTo(PublicationMoves.START_LOCK_FAMILY);
        assertThat(start.admission().keyOf(new AttemptProcesses.PublicationRef(publicationId))).isEqualTo(startLock);
        var submit = (ProcessDefinition<AttemptFinishing.SubmitInput, ?, ProcessContext>) processes
            .findLatest(AttemptFinishing.SUBMIT).orElseThrow();
        assertThat(submit.admission().keyOf(new AttemptFinishing.SubmitInput("a", null, null, publicationId)))
            .isEqualTo(lock);
        var expire = (ProcessDefinition<AttemptFinishing.ExpireInput, ?, ProcessContext>) processes
            .findLatest(AttemptFinishing.EXPIRE).orElseThrow();
        assertThat(expire.admission().keyOf(new AttemptFinishing.ExpireInput("a", publicationId))).isEqualTo(lock);
        // application.yml: the family written out, the players' rate limits bound with their case.
        assertThat(rateLimits.limits(AttemptProcesses.START)).isTrue();
        assertThat(rateLimits.limits(AttemptFinishing.SUBMIT)).isTrue();
        assertThat(settings.limitsOf(PublicationMoves.LOCK_FAMILY).concurrency()).isEqualTo(2);
        assertThat(settings.limitsOf(PublicationMoves.LOCK_FAMILY).maxQueue()).isEqualTo(6);
        // Both families are bound from application.yml (their own entries, not the platform's defaults).
        assertThat(settings.limitsOf(PublicationMoves.START_LOCK_FAMILY).concurrency()).isEqualTo(2);
        assertThat(settings.limitsOf(PublicationMoves.START_LOCK_FAMILY).maxQueue()).isEqualTo(6);
        assertThat(settings.families()).containsKeys(PublicationMoves.LOCK_FAMILY,
            PublicationMoves.START_LOCK_FAMILY);
    }

    @Test
    void startsAndSubmissionsQueueByPublicationAndAWrongOneIsRefusedBeforeTheLock() throws Exception {
        warmUp();
        String sponsor = newSponsor("s-admit");
        String hot = livePublication(sponsor, "Admit hot", Map.of("regions", List.of("GLOBAL")));
        String other = livePublication(sponsor, "Admit other", Map.of("regions", List.of("GLOBAL")));
        credit(sponsor, 10_000);
        // Four attempts of the hot publication, started before anyone holds its lock.
        List<String> tokens = new ArrayList<>();
        List<String> attempts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String token = taker(newTaker("t-admit-" + i, null));
            tokens.add(token);
            attempts.add((String) start(token, hot).get("attemptId"));
        }
        String lock = PublicationMoves.lockName(hot);
        String startLock = PublicationMoves.startLockName(hot);
        List<CompletableFuture<EntityExchangeResult<Map<String, Object>>>> queued = new ArrayList<>();
        try (Connection holder = DB.connect(schema())) {
            holdLock(holder, lock);
            holdLock(holder, startLock);
            // Two submissions, in their transaction waiting for the publication's lock; three new starts, by their own
            // family (Q10a4): two in their transaction waiting for the start lock, one queued.
            queued.add(background(() -> submission(tokens.get(0), attempts.get(0), hot)));
            queued.add(background(() -> submission(tokens.get(1), attempts.get(1), hot)));
            for (int i = 0; i < 3; i++) {
                String token = taker(newTaker("t-admit-start-" + i, null));
                queued.add(background(() -> startCall(token, hot)));
            }
            awaitThat(() -> admission.waiting() == 1 && waiters(lock) == 2 && waiters(startLock) == 2,
                "1 queued, 2 waiting for each lock");

            // Without the publication: 400 at once (required, so that nothing waits for the lock on a connection).
            EntityExchangeResult<Map<String, Object>> missing = submission(tokens.get(2), attempts.get(2), null);
            assertThat(missing.getStatus().value()).isEqualTo(400);
            assertThat(missing.getResponseBody().toString()).contains("publicationId");
            assertThat(admission.waiting()).isEqualTo(1);
            assertThat(waiters(lock)).isEqualTo(2);

            // Another publication than the attempt's: queued there (free), refused before the hot lock.
            long began = System.nanoTime();
            EntityExchangeResult<Map<String, Object>> wrong = submission(tokens.get(3), attempts.get(3), other);
            assertThat(wrong.getStatus().value()).isEqualTo(422);
            assertThat(wrong.getResponseBody().toString()).contains(PlayCodes.PUBLICATION_MISMATCH);
            assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(5));
            assertThat(waiters(lock)).isEqualTo(2);
            // Other publications are not held up by the hot one.
            assertThat(startCall(taker(newTaker("t-admit-elsewhere", null)), other).getStatus().value())
                .isEqualTo(200);
            assertThat(queued).noneMatch(CompletableFuture::isDone);
            holder.rollback();
        }
        for (CompletableFuture<EntityExchangeResult<Map<String, Object>>> call : queued) {
            EntityExchangeResult<Map<String, Object>> result = call.get(30, TimeUnit.SECONDS);
            assertThat(result.getStatus().value()).as(String.valueOf(result.getResponseBody())).isEqualTo(200);
        }
        assertThat(attempt(attempts.get(3))).containsEntry("status", "IN_PROGRESS");
        assertThat(attempt(attempts.get(2))).containsEntry("status", "IN_PROGRESS");
        // The refused ones, sent again with their own publication, go through.
        assertThat(submission(tokens.get(3), attempts.get(3), hot).getStatus().value()).isEqualTo(200);
        assertThat(submission(tokens.get(2), attempts.get(2), hot).getStatus().value()).isEqualTo(200);
        awaitThat(() -> admission.active() == 0 && admission.waiting() == 0 && admission.keys() == 0,
            "nothing held or queued");
        assertBooksBalance();
    }

    /**
     * A hot publication's queue overflows: two runs wait for its lock in their transaction, six wait in the queue
     * holding no connection, the rest are refused at once with 503 {@code PROCESS_BUSY} and {@code Retry-After}; the
     * home feed meanwhile answers as usual (with the waiters on connections, Q10a saw it 30 to 60 times slower).
     */
    @Test
    void aHotPublicationLeavesTheHomeFeedItsConnections() throws Exception {
        warmUp();
        String sponsor = newSponsor("s-hot-feed");
        String hot = livePublication(sponsor, "Hot feed", Map.of("regions", List.of("GLOBAL")));
        credit(sponsor, 10_000);
        String reader = taker(newTaker("t-hot-reader", null));
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tokens.add(taker(newTaker("t-hot-" + i, null)));
        }
        // The starts' lock and queue (Q10a4).
        String lock = PublicationMoves.startLockName(hot);
        List<CompletableFuture<EntityExchangeResult<Map<String, Object>>>> calls = new ArrayList<>();
        List<EntityExchangeResult<Map<String, Object>>> refused = new ArrayList<>();
        try (Connection holder = DB.connect(schema())) {
            holdLock(holder, lock);
            for (String token : tokens) {
                calls.add(background(() -> startCall(token, hot)));
            }
            // Those beyond 2 + 6 are refused at once.
            awaitThat(() -> calls.stream().filter(CompletableFuture::isDone).count() == 12, "12 refused");
            for (CompletableFuture<EntityExchangeResult<Map<String, Object>>> call : calls) {
                if (call.isDone()) {
                    refused.add(call.get());
                }
            }
            assertThat(admission.waiting()).isEqualTo(6);
            assertThat(waiters(lock)).isEqualTo(2);
            for (int i = 0; i < 10; i++) {
                long began = System.nanoTime();
                assertThat(feed(reader).getStatus().value()).isEqualTo(200);
                assertThat(Duration.ofNanos(System.nanoTime() - began)).as("the feed while the queue is full")
                    .isLessThan(Duration.ofSeconds(2));
            }
            assertThat(waiters(lock)).as("still only the two admitted wait on the database").isEqualTo(2);
            holder.rollback();
        }
        for (EntityExchangeResult<Map<String, Object>> busy : refused) {
            assertThat(busy.getStatus().value()).isEqualTo(503);
            assertThat(busy.getResponseBody()).containsEntry("reason", "QUEUE_FULL");
            assertThat(busy.getResponseBody().toString()).contains(PlatformErrorCodes.PROCESS_BUSY);
            assertThat(busy.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        }
        long started = 0;
        for (CompletableFuture<EntityExchangeResult<Map<String, Object>>> call : calls) {
            if (call.get(30, TimeUnit.SECONDS).getStatus().value() == 200) {
                started++;
            }
        }
        assertThat(started).isEqualTo(8);
        awaitThat(() -> admission.active() == 0 && admission.keys() == 0, "nothing held");
    }

    /** The expiry job's items queue under the publication from the selection, and finish as before. */
    @Test
    void theExpiryJobsItemsCarryTheirPublication() throws Exception {
        String sponsor = newSponsor("s-admit-expire");
        String publication = livePublication(sponsor, "Admit expire", Map.of("regions", List.of("GLOBAL"),
            "timeLimitSec", 60));
        credit(sponsor, 1_000);
        String token = taker(newTaker("t-admit-expire", null));
        String attemptId = (String) start(token, publication).get("attemptId");
        clock.advance(Duration.ofMinutes(5));
        JobRunner.JobRun run = runExpire(clock.instant());
        assertThat(run.items().succeeded()).isPositive();
        assertThat(attempt(attemptId)).containsEntry("status", "EXPIRED");
        assertThat(query("SELECT input_summary ->> 'publicationId' AS p FROM op_process WHERE process_name = ? "
            + "AND input_summary ->> 'attemptId' = ?", AttemptFinishing.EXPIRE, attemptId).getFirst())
            .containsEntry("p", publication);
    }

    /** Another taker's attempt is not found, whichever publication the submission names (before any queue's check). */
    @Test
    void anotherTakersAttemptIsNotFoundWhateverThePublication() {
        String sponsor = newSponsor("s-admit-owner");
        String publication = livePublication(sponsor, "Admit owner", Map.of("regions", List.of("GLOBAL")));
        String other = livePublication(sponsor, "Admit owner other", Map.of("regions", List.of("GLOBAL")));
        credit(sponsor, 1_000);
        String attemptId = (String) start(taker(newTaker("t-admit-owner-a", null)), publication).get("attemptId");
        String intruder = taker(newTaker("t-admit-owner-b", null));
        assertThat(submission(intruder, attemptId, publication).getStatus().value()).isEqualTo(404);
        assertThat(submission(intruder, attemptId, other).getStatus().value()).isEqualTo(404);
        assertThat(attempt(attemptId)).containsEntry("status", "IN_PROGRESS");
    }

    /**
     * The App's retries keep their key: a start refused by the full queue never ran, so the same key runs it (one
     * attempt); a submission that committed is answered again from its key (one reward, the wallet debited once).
     */
    @Test
    void theSameKeyRunsARefusedCallOnceAndReplaysACommittedOne() throws Exception {
        warmUp();
        String sponsor = newSponsor("s-admit-key");
        String hot = livePublication(sponsor, "Admit key", Map.of("regions", List.of("GLOBAL")));
        credit(sponsor, 10_000);
        String lock = PublicationMoves.startLockName(hot);
        String userId = newTaker("t-admit-key", null);
        String token = taker(userId);
        String key = "start-" + UUID.randomUUID();
        List<CompletableFuture<EntityExchangeResult<Map<String, Object>>>> filling = new ArrayList<>();
        try (Connection holder = DB.connect(schema())) {
            holdLock(holder, lock);
            for (int i = 0; i < 8; i++) {
                String other = taker(newTaker("t-admit-key-fill-" + i, null));
                filling.add(background(() -> startCall(other, hot)));
            }
            awaitThat(() -> admission.waiting() == 6 && waiters(lock) == 2, "the queue full");
            EntityExchangeResult<Map<String, Object>> refused = startCall(token, hot, key);
            assertThat(refused.getStatus().value()).isEqualTo(503);
            assertThat(refused.getResponseBody().toString()).contains(PlatformErrorCodes.PROCESS_BUSY);
            holder.rollback();
        }
        for (CompletableFuture<EntityExchangeResult<Map<String, Object>>> call : filling) {
            assertThat(call.get(30, TimeUnit.SECONDS).getStatus().value()).isEqualTo(200);
        }
        EntityExchangeResult<Map<String, Object>> retried = startCall(token, hot, key);
        assertThat(retried.getStatus().value()).isEqualTo(200);
        assertThat(query("SELECT count(*) AS n FROM qb_attempt WHERE user_id = ? AND publication_id = ?::uuid",
            userId, hot).getFirst().get("n")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        String attemptId = (String) ((Map<String, Object>) retried.getResponseBody().get("output")).get("attemptId");

        java.math.BigDecimal before = balance(sponsor);
        String submitKey = "submit-" + UUID.randomUUID();
        EntityExchangeResult<Map<String, Object>> first = submission(token, attemptId, hot, submitKey);
        EntityExchangeResult<Map<String, Object>> again = submission(token, attemptId, hot, submitKey);
        assertThat(first.getStatus().value()).isEqualTo(200);
        assertThat(again.getStatus().value()).isEqualTo(200);
        assertThat(again.getResponseBody().get("output")).isEqualTo(first.getResponseBody().get("output"));
        assertThat(postingOf(attemptId).stream().map(row -> row.get("transaction_id")).distinct()).hasSize(1);
        assertThat(before.subtract(balance(sponsor))).isEqualByComparingTo("100");
        assertBooksBalance();
    }

    /**
     * An expiry item turned away by the full queue is busy, and stays to the next minute; a submission arriving late
     * meanwhile is EXPIRED (the deadline and the grace count, not who reaches the lock first), and the next run
     * finishes the attempt with its saved answers.
     */
    @Test
    void anExpiryTurnedAwayByAFullQueueLetsNoLateSubmissionIn() throws Exception {
        warmUp();
        String sponsor = newSponsor("s-admit-late");
        String hot = livePublication(sponsor, "Admit late", Map.of("regions", List.of("GLOBAL"), "timeLimitSec", 60));
        credit(sponsor, 10_000);
        String token = taker(newTaker("t-admit-late", null));
        String attemptId = (String) start(token, hot).get("attemptId");
        run("QB_ATTEMPT_SAVE", token, body("attemptId", attemptId, "currentIndex", 1, "answers",
            shown(attemptId, answers(true, true))));
        clock.advance(Duration.ofMinutes(2));
        // The expiry queues with the submissions (qb.pub; the starts queue apart since Q10a4): the queue is filled by
        // submissions of eight attempts of the hot publication started now, in time.
        List<String> fillers = new ArrayList<>();
        List<String> fillerAttempts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String filler = taker(newTaker("t-admit-late-fill-" + i, null));
            fillers.add(filler);
            fillerAttempts.add((String) start(filler, hot).get("attemptId"));
        }
        String lock = PublicationMoves.lockName(hot);
        List<CompletableFuture<EntityExchangeResult<Map<String, Object>>>> filling = new ArrayList<>();
        try (Connection holder = DB.connect(schema())) {
            holdLock(holder, lock);
            for (int i = 0; i < fillers.size(); i++) {
                String filler = fillers.get(i);
                String fillerAttempt = fillerAttempts.get(i);
                filling.add(background(() -> submission(filler, fillerAttempt, hot)));
            }
            awaitThat(() -> admission.waiting() == 6 && waiters(lock) == 2, "the queue full");
            JobRunner.JobRun run = runExpire(clock.instant());
            assertThat(run.items().busy()).isEqualTo(1);
            assertThat(attempt(attemptId)).containsEntry("status", "IN_PROGRESS");
            holder.rollback();
        }
        for (CompletableFuture<EntityExchangeResult<Map<String, Object>>> call : filling) {
            assertThat(call.get(30, TimeUnit.SECONDS).getStatus().value()).isEqualTo(200);
        }
        EntityExchangeResult<Map<String, Object>> late = submission(token, attemptId, hot);
        assertThat(late.getStatus().value()).isEqualTo(422);
        assertThat(late.getResponseBody().toString()).contains(PlayCodes.EXPIRED);
        clock.advance(Duration.ofMinutes(1));
        assertThat(runExpire(clock.instant()).items().succeeded()).isPositive();
        assertThat(attempt(attemptId)).containsEntry("status", "EXPIRED").containsEntry("reward_status", "PAID");
        assertBooksBalance();
    }

    // ================= helpers =================

    private EntityExchangeResult<Map<String, Object>> submission(String token, String attemptId, String publicationId) {
        return submission(token, attemptId, publicationId, UUID.randomUUID().toString());
    }

    private EntityExchangeResult<Map<String, Object>> submission(String token, String attemptId, String publicationId,
        String key) {
        Map<String, Object> body = body("attemptId", attemptId, "answers", shown(attemptId, answers(true, true)));
        if (publicationId != null) {
            body.put("publicationId", publicationId);
        }
        return client.post().uri("/api/processes/" + AttemptFinishing.SUBMIT + "/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
            .header("Idempotency-Key", key).bodyValue(body).exchange().expectBody(MAP)
            .returnResult();
    }

    private EntityExchangeResult<Map<String, Object>> startCall(String token, String publicationId) {
        return startCall(token, publicationId, UUID.randomUUID().toString());
    }

    private EntityExchangeResult<Map<String, Object>> startCall(String token, String publicationId, String key) {
        return client.post().uri("/api/processes/" + AttemptProcesses.START + "/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
            .header("Idempotency-Key", key)
            .bodyValue(body("publicationId", publicationId)).exchange().expectBody(MAP).returnResult();
    }

    private EntityExchangeResult<Map<String, Object>> feed(String token) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("at", clock.instant().toString());
        return client.post().uri("/api/queries/qb.app.feed").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token).bodyValue(Map.of("params", params, "limit", 20)).exchange()
            .expectBody(MAP).returnResult();
    }

    /** Holds the lock in the holder's transaction, as a long request under it would, until it rolls back. */
    private static void holdLock(Connection holder, String lock) throws Exception {
        holder.setAutoCommit(false);
        try (PreparedStatement statement = holder.prepareStatement(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            statement.setString(1, "jabiz.app-lock:" + lock);
            statement.execute();
        }
    }

    /** Sessions waiting for the named lock. */
    private static int waiters(String lock) {
        return ((Number) query("SELECT count(*) AS n FROM pg_locks WHERE locktype = 'advisory' AND NOT granted"
            + " AND ((classid::bigint << 32) | objid::bigint) = hashtextextended(?, 0)", "jabiz.app-lock:" + lock)
            .getFirst().get("n")).intValue();
    }

    private static void awaitThat(BooleanSupplier condition, String what) throws InterruptedException {
        for (int i = 0; i < 600 && !condition.getAsBoolean(); i++) {
            Thread.sleep(25);
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    private <T> CompletableFuture<T> background(Callable<T> work) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        threads.add(pool);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, pool);
    }
}

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
 * The queue before a publication's lock (platform decision D45; docs/quizbuks/plans/Q10-launch.md, Q10a2):
 * {@code QB_ATTEMPT_START} and {@code QB_ATTEMPT_SUBMIT} with its publication queue for {@code qb.pub:<publication>}
 * before their transaction, so that a hot publication's waiters hold no database connection; a submission naming
 * another publication than its attempt's is refused before the lock; one naming none (an older App) does not queue and
 * waits for the lock as before; and the home feed keeps its connections while a hot publication overflows its queue.
 * Here the queue waits long (20 s) and holds 6, so that what is queued, waiting and refused is exact.
 */
@SpringBootTest(properties = {
    "jabiz.process.admission.families[qb.pub].max-queue=6",
    "jabiz.process.admission.families[qb.pub].max-wait=20s"})
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
    void theProcessesQueueUnderThePublicationsLockName() {
        UUID publicationId = UUID.randomUUID();
        String lock = PublicationMoves.lockName(publicationId);
        assertThat(PublicationMoves.LOCK_FAMILY).isEqualTo("qb.pub");
        // The same text as the lock: no warning from HoldLock (decision D45, implementation note 6).
        assertThat(AttemptProcesses.start().admission().keyOf(new AttemptProcesses.PublicationRef(publicationId)))
            .isEqualTo(lock);
        var submit = (ProcessDefinition<AttemptFinishing.SubmitInput, ?, ProcessContext>) processes
            .findLatest(AttemptFinishing.SUBMIT).orElseThrow();
        assertThat(submit.admission().keyOf(new AttemptFinishing.SubmitInput("a", null, null, publicationId)))
            .isEqualTo(lock);
        assertThat(submit.admission().keyOf(new AttemptFinishing.SubmitInput("a", null, null, null)))
            .as("an older App's submission does not queue").isNull();
        var expire = (ProcessDefinition<AttemptFinishing.ExpireInput, ?, ProcessContext>) processes
            .findLatest(AttemptFinishing.EXPIRE).orElseThrow();
        assertThat(expire.admission().keyOf(new AttemptFinishing.ExpireInput("a", publicationId))).isEqualTo(lock);
        // application.yml: the family written out, the players' rate limits bound with their case.
        assertThat(rateLimits.limits(AttemptProcesses.START)).isTrue();
        assertThat(rateLimits.limits(AttemptFinishing.SUBMIT)).isTrue();
        assertThat(settings.limitsOf(PublicationMoves.LOCK_FAMILY).concurrency()).isEqualTo(2);
        assertThat(settings.limitsOf(PublicationMoves.LOCK_FAMILY).maxQueue()).isEqualTo(6);
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
        List<CompletableFuture<EntityExchangeResult<Map<String, Object>>>> queued = new ArrayList<>();
        try (Connection holder = DB.connect(schema())) {
            holdLock(holder, lock);
            // Two submissions and three new starts: two in their transaction (waiting for the lock), three queued.
            queued.add(background(() -> submission(tokens.get(0), attempts.get(0), hot)));
            queued.add(background(() -> submission(tokens.get(1), attempts.get(1), hot)));
            for (int i = 0; i < 3; i++) {
                String token = taker(newTaker("t-admit-start-" + i, null));
                queued.add(background(() -> startCall(token, hot)));
            }
            awaitThat(() -> admission.waiting() == 3 && waiters(lock) == 2, "3 queued, 2 waiting for the lock");

            // An older App: no publication, no queue; it waits for the lock in its transaction, as before.
            CompletableFuture<EntityExchangeResult<Map<String, Object>>> unqueued = background(() ->
                submission(tokens.get(2), attempts.get(2), null));
            awaitThat(() -> waiters(lock) == 3, "the unqueued submission waits for the lock");
            assertThat(admission.waiting()).isEqualTo(3);

            // Another publication than the attempt's: queued there (free), refused before the hot lock.
            long began = System.nanoTime();
            EntityExchangeResult<Map<String, Object>> wrong = submission(tokens.get(3), attempts.get(3), other);
            assertThat(wrong.getStatus().value()).isEqualTo(422);
            assertThat(wrong.getResponseBody().toString()).contains(PlayCodes.PUBLICATION_MISMATCH);
            assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(5));
            assertThat(waiters(lock)).isEqualTo(3);
            // Other publications are not held up by the hot one.
            assertThat(startCall(taker(newTaker("t-admit-elsewhere", null)), other).getStatus().value())
                .isEqualTo(200);
            assertThat(queued).noneMatch(CompletableFuture::isDone);
            holder.rollback();
            queued.add(unqueued);
        }
        for (CompletableFuture<EntityExchangeResult<Map<String, Object>>> call : queued) {
            EntityExchangeResult<Map<String, Object>> result = call.get(30, TimeUnit.SECONDS);
            assertThat(result.getStatus().value()).as(String.valueOf(result.getResponseBody())).isEqualTo(200);
        }
        assertThat(attempt(attempts.get(3))).containsEntry("status", "IN_PROGRESS");
        // The refused one, sent again with its own publication, goes through.
        assertThat(submission(tokens.get(3), attempts.get(3), hot).getStatus().value()).isEqualTo(200);
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
        String lock = PublicationMoves.lockName(hot);
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
        assertThat(run.items().succeeded()).isEqualTo(1);
        assertThat(attempt(attemptId)).containsEntry("status", "EXPIRED");
        assertThat(query("SELECT input_summary ->> 'publicationId' AS p FROM op_process WHERE process_name = ? "
            + "AND input_summary ->> 'attemptId' = ?", AttemptFinishing.EXPIRE, attemptId).getFirst())
            .containsEntry("p", publication);
    }

    // ================= helpers =================

    private EntityExchangeResult<Map<String, Object>> submission(String token, String attemptId, String publicationId) {
        Map<String, Object> body = body("attemptId", attemptId, "answers", answers(true, true));
        if (publicationId != null) {
            body.put("publicationId", publicationId);
        }
        return client.post().uri("/api/processes/" + AttemptFinishing.SUBMIT + "/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
            .header("Idempotency-Key", UUID.randomUUID().toString()).bodyValue(body).exchange().expectBody(MAP)
            .returnResult();
    }

    private EntityExchangeResult<Map<String, Object>> startCall(String token, String publicationId) {
        return client.post().uri("/api/processes/" + AttemptProcesses.START + "/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
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

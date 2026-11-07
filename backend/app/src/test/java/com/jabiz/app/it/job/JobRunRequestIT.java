package com.jabiz.app.it.job;

import com.jabiz.app.it.fixture.ItEventFixtures;
import com.jabiz.app.it.fixture.ItJobItemFixtures;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.context.RequestContext;
import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.job.JobRegistry;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.InstanceOfAssertFactories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs by hand of a past scheduled time (ROADMAP phase 17zs; decision D77; docs/design/11-ledger-events-jobs.md
 * section 4.2): {@code POST /api/jobs/{job}/runs} answers 202 and runs the time again with the scheduled run's keys, so
 * only what did not commit runs (items in backoff too); a time the schedule never names, one to come and one too old
 * are refused (422), as are an unknown job (404), a caller without {@code job.run} (403) or a recent second factor
 * (403 {@code MFA_REQUIRED}), and a job whose lock is held (409 {@code JOB_BUSY}); the request is an operation with
 * its reason and the run names it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {"jabiz.jobs.lock-at-least-for=0s", "it.sql-log.enabled=true"})
class JobRunRequestIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
        new ParameterizedTypeReference<>() {};
    private static final String ADMIN = "it-job-admin";

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    @Autowired
    JobRunner runner;

    @Autowired
    JobRegistry jobs;

    private WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToApplicationContext(context).configureClient()
            .responseTimeout(Duration.ofSeconds(30)).build();
        // No item of the per-item job is due unless a test makes it so.
        execute("UPDATE it_job_item SET f_due = false WHERE f_batch = ?", ItJobItemFixtures.BEAN_BATCH);
    }

    // ================= per-item job =================

    @Test
    void aRerunOfAPerItemJobRunsOnlyTheItemsThatDidNotCommitBackoffOrNot() {
        SqlStatementLog.clear();
        JobDefinition<?> job = job(ItJobItemFixtures.BEAN_JOB);
        List<String> keys = List.of(item(0, false), item(1, true), item(2, false));
        Instant first = START.minus(Duration.ofMinutes(3));
        Instant second = START.minus(Duration.ofMinutes(2));
        // Two scheduled runs: the second item fails twice and is now in backoff.
        assertThat(runner.execute(job, first).items()).isEqualTo(new JobRunner.ItemCounts(3, 2, 0, 0, 1, 0, false));
        assertThat(runner.execute(job, second).items()).isEqualTo(new JobRunner.ItemCounts(3, 2, 0, 0, 1, 0, false));
        assertThat(runner.execute(job, START.minus(Duration.ofMinutes(1))).items().deferred()).isEqualTo(1);
        execute("UPDATE it_job_item SET f_fail = false WHERE f_id = ?", keys.get(1));

        Map<String, Object> accepted = request(job.name(), second, "the item's data is fixed", admin())
            .expectStatus().isAccepted().expectBody(MAP).returnResult().getResponseBody();
        assertThat(accepted).containsEntry("job", job.name()).containsEntry("scheduledTime", second.toString())
            .containsEntry("replayed", false);
        long seq = ((Number) accepted.get("requestSeqId")).longValue();

        Map<String, Object> run = awaitRun(seq);
        // The two that committed at that time replay; the failing one runs and recovers.
        assertThat(((java.sql.Timestamp) run.get("scheduled_time")).toInstant()).isEqualTo(second);
        assertThat(run).containsEntry("job_name", job.name())
            .containsEntry("outcome", "SUCCEEDED").containsEntry("requested_by", ADMIN)
            .containsEntry("items_selected", 3).containsEntry("items_succeeded", 1)
            .containsEntry("items_replayed", 2).containsEntry("items_failed", 0).containsEntry("items_deferred", 0);
        assertThat(keys).extracting(key -> ((Number) query("SELECT f_runs FROM it_job_item WHERE f_id = ?", key)
            .getFirst().get("f_runs")).intValue()).containsExactly(3, 1, 3);
        assertThat(query("SELECT kind FROM sys_job_item_failure WHERE job_name = ? AND item_key = ?"
            + " ORDER BY entry_no", job.name(), keys.get(1))).extracting(row -> row.get("kind"))
            .containsExactly("FAILED", "FAILED", "RECOVERED");
        // The item ran as the system, with the scheduled run's key.
        assertThat(query("SELECT actor_id FROM op_process WHERE idempotency_key = ?",
            JobDefinition.itemIdempotencyKey(job.name(), second, keys.get(1))))
            .singleElement().satisfies(op -> assertThat(op).containsEntry("actor_id", RequestContext.SYSTEM_ACTOR));

        // The request is an operation of the administrator, with the reason.
        assertThat(query("SELECT process_name, actor_id, input_summary->>'reason' AS reason,"
            + " input_summary->>'job' AS job FROM op_process WHERE process_seq_id = ?", seq)).singleElement()
            .satisfies(op -> assertThat(op).containsEntry("process_name", "JOB_RUN_REQUEST")
                .containsEntry("actor_id", ADMIN).containsEntry("reason", "the item's data is fixed")
                .containsEntry("job", job.name()));
        // The jobs API shows who asked.
        Map<String, Object> listed = listed(job.name());
        assertThat(listed).containsEntry("running", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> runs = (List<Map<String, Object>>) listed.get("recentRuns");
        assertThat(runs).anySatisfy(listedRun -> assertThat(listedRun)
            .containsEntry("requestedBy", ADMIN).containsEntry("requestSeqId", (int) seq)
            .containsEntry("scheduledTime", second.toString()));
        assertThat(listed.get("rerunTimes")).asInstanceOf(InstanceOfAssertFactories.LIST)
            .contains(second.toString(), first.toString());

        // Appended only.
        assertThat(SqlStatementLog.STATEMENTS).noneMatch(sql -> sql.matches(
            "(?is).*(UPDATE|DELETE FROM)\\s+(sys_job_run|sys_job_item_failure|op_process)\\b.*"));
        assertThatThrownBy(() -> execute("UPDATE sys_job_run SET requested_by = 'x' WHERE request_seq_id = ?", seq))
            .hasMessageContaining("append-only");
        execute("UPDATE it_job_item SET f_due = false WHERE f_batch = ?", ItJobItemFixtures.BEAN_BATCH);
    }

    // ================= single-process job =================

    @Test
    void aRerunOfASingleProcessJobReplaysWhatCommittedAndRunsWhatDidNot() {
        JobDefinition<?> job = job(ItEventFixtures.TICK_JOB);
        Instant ran = START.minus(Duration.ofHours(1));
        assertThat(runner.run(job, ran)).isEqualTo(JobRunner.Outcome.SUCCEEDED);

        long replayed = accepted(job.name(), ran);
        assertThat(awaitRun(replayed)).containsEntry("outcome", "REPLAYED").containsEntry("requested_by", ADMIN)
            .containsEntry("items_selected", null);
        assertThat(query("SELECT * FROM it_event_log WHERE f_id = ?", "tick:" + ran)).hasSize(1);
        assertThat(query("SELECT * FROM op_process WHERE idempotency_key = ?",
            JobRunner.idempotencyKey(job.name(), ran))).hasSize(1);

        // A time no instance ran (it was down): it runs now.
        Instant missed = START.minus(Duration.ofHours(3));
        long ranNow = accepted(job.name(), missed);
        assertThat(awaitRun(ranNow)).containsEntry("outcome", "SUCCEEDED");
        assertThat(query("SELECT * FROM it_event_log WHERE f_id = ?", "tick:" + missed)).hasSize(1);
    }

    @Test
    void theSameRequestKeyStartsNothingAgain() {
        JobDefinition<?> job = job(ItEventFixtures.TICK_JOB);
        Instant time = START.minus(Duration.ofHours(4));
        String key = "rerun-" + UUID.randomUUID();
        Map<String, Object> first = requestWithKey(job.name(), time, key).expectStatus().isAccepted()
            .expectBody(MAP).returnResult().getResponseBody();
        long seq = ((Number) first.get("requestSeqId")).longValue();
        awaitRun(seq);
        // Asked again while a run holds the lock (as its own run would): replayed, not 409.
        SimpleLock held = runner.tryLock(job).orElseThrow();
        try {
            Map<String, Object> again = requestWithKey(job.name(), time, key).expectStatus().isAccepted()
                .expectBody(MAP).returnResult().getResponseBody();
            assertThat(again).containsEntry("replayed", true)
                .containsEntry("requestSeqId", first.get("requestSeqId"));
        } finally {
            held.unlock();
        }
        assertThat(query("SELECT * FROM sys_job_run WHERE request_seq_id = ?", seq)).hasSize(1);
    }

    @Test
    void aRerunOfAnOlderTimeIsFoundByItsRequestThoughNotAmongTheLatestRuns() {
        JobDefinition<?> job = job(ItJobItemFixtures.BEAN_JOB);
        for (int minutes = 1; minutes <= 10; minutes++) {
            runner.execute(job, START.minus(Duration.ofMinutes(minutes)));
        }
        Instant older = START.minus(Duration.ofMinutes(15));
        assertThat(listed(job.name()).get("rerunTimes")).asInstanceOf(InstanceOfAssertFactories.LIST)
            .contains(older.toString());
        long seq = accepted(job.name(), older);
        awaitRun(seq);

        assertThat(listed(job.name()).get("recentRuns")).asInstanceOf(InstanceOfAssertFactories.LIST)
            .noneSatisfy(run -> assertThat(run).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("requestSeqId", (int) seq));
        List<Map<String, Object>> found = client.get()
            .uri("/api/jobs/{job}/runs?requestSeqId={seq}", job.name(), seq)
            .header(HttpHeaders.AUTHORIZATION, admin()).exchange()
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
        assertThat(found).singleElement().satisfies(run -> assertThat(run)
            .containsEntry("requestSeqId", (int) seq).containsEntry("scheduledTime", older.toString())
            .containsEntry("requestedBy", ADMIN));
        client.get().uri("/api/jobs/{job}/runs?requestSeqId={seq}", job.name(), seq)
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "it-runner", "job.run")).exchange()
            .expectStatus().isForbidden();
        client.get().uri("/api/jobs/{job}/runs?requestSeqId={seq}", "it.no-such-job", seq)
            .header(HttpHeaders.AUTHORIZATION, admin()).exchange().expectStatus().isNotFound();
    }

    // ================= refusals =================

    @Test
    void timesTheScheduleDoesNotNameOrThatAreToComeOrTooOldAreRefused() {
        String tick = ItEventFixtures.TICK_JOB;
        assertThat(refusal(request(tick, START.minus(Duration.ofMinutes(30)), "why", admin()), 422))
            .isEqualTo("JOB_RUN_NOT_SCHEDULED");
        assertThat(refusal(request(tick, START.minusMillis(3_600_000 - 1), "why", admin()), 422))
            .isEqualTo("JOB_RUN_NOT_SCHEDULED");
        assertThat(refusal(request(tick, START.plus(Duration.ofHours(1)), "why", admin()), 422))
            .isEqualTo("JOB_RUN_NOT_DUE");
        assertThat(refusal(request(tick, START.minus(Duration.ofDays(91)), "why", admin()), 422))
            .isEqualTo("JOB_RUN_TOO_OLD");
        // The lock was released after each refusal: nothing holds it now, and nothing ran.
        assertLockFree(tick);
        assertThat(query("SELECT * FROM op_process WHERE process_name = 'JOB_RUN_REQUEST' AND input_summary->>'reason'"
            + " = 'why'")).isEmpty();
    }

    @Test
    void anUnknownJobAMissingReasonOrPermissionAndAnOldSecondFactorAreRefused() {
        String tick = ItEventFixtures.TICK_JOB;
        Instant time = START.minus(Duration.ofHours(5));
        request("it.no-such-job", time, "why", admin()).expectStatus().isNotFound();
        request(tick, time, " ", admin()).expectStatus().isBadRequest();
        assertThat(refusal(request(tick, time, "why", TestTokens.bearer(tokens, "it-reader", "job.read")), 403))
            .isEqualTo("PERMISSION_DENIED");
        assertThat(refusal(request(tick, time, "why", TestTokens.withoutMfa(tokens, ADMIN, "job.run")), 403))
            .isEqualTo("MFA_REQUIRED");
        client.post().uri("/api/jobs/{job}/runs", tick).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduledTime", time.toString(), "reason", "why")).exchange()
            .expectStatus().isUnauthorized();
        assertLockFree(tick);
        assertThat(query("SELECT * FROM sys_job_run WHERE job_name = ? AND scheduled_time = ?", tick,
            time.atOffset(java.time.ZoneOffset.UTC))).isEmpty();
    }

    @Test
    void aJobWhoseLockIsHeldIsBusy() {
        JobDefinition<?> job = job(ItEventFixtures.TICK_JOB);
        Instant time = START.minus(Duration.ofHours(6));
        SimpleLock held = runner.tryLock(job).orElseThrow();
        try {
            assertThat(listed(job.name())).containsEntry("running", true);
            assertThat(refusal(request(job.name(), time, "why", admin()), 409)).isEqualTo("JOB_BUSY");
            // A time that cannot run is refused before the lock is asked for.
            assertThat(refusal(request(job.name(), START.minus(Duration.ofMinutes(30)), "why", admin()), 422))
                .isEqualTo("JOB_RUN_NOT_SCHEDULED");
        } finally {
            held.unlock();
        }
        // The scheduler finds the lock taken by a run by hand as it finds another instance's.
        assertThat(query("SELECT * FROM op_process WHERE process_name = 'JOB_RUN_REQUEST'"
            + " AND input_summary->>'scheduledTime' = ?", time.toString())).isEmpty();
        long seq = accepted(job.name(), time);
        assertThat(awaitRun(seq)).containsEntry("outcome", "SUCCEEDED");
    }

    // ================= helpers =================

    private String admin() {
        return TestTokens.bearer(tokens, ADMIN, "job.run", "job.read");
    }

    private JobDefinition<?> job(String name) {
        return jobs.find(name).orElseThrow();
    }

    private static String item(int seq, boolean fail) {
        String id = UUID.randomUUID().toString();
        execute("INSERT INTO it_job_item (f_id, f_batch, f_seq, f_due, f_stay_due, f_fail) VALUES (?, ?, ?, true,"
            + " true, ?)", id, ItJobItemFixtures.BEAN_BATCH, seq, fail);
        return id;
    }

    private WebTestClient.ResponseSpec request(String job, Instant time, String reason, String bearer) {
        Map<String, Object> body = new HashMap<>();
        body.put("scheduledTime", time.toString());
        body.put("reason", reason);
        return client.post().uri("/api/jobs/{job}/runs", job).header(HttpHeaders.AUTHORIZATION, bearer)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec requestWithKey(String job, Instant time, String key) {
        return client.post().uri("/api/jobs/{job}/runs", job).header(HttpHeaders.AUTHORIZATION, admin())
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduledTime", time.toString(), "reason", "again")).exchange();
    }

    private long accepted(String job, Instant time) {
        Map<String, Object> accepted = request(job, time, "missed while down", admin()).expectStatus().isAccepted()
            .expectBody(MAP).returnResult().getResponseBody();
        return ((Number) accepted.get("requestSeqId")).longValue();
    }

    private static String refusal(WebTestClient.ResponseSpec response, int status) {
        Map<String, Object> problem = response.expectStatus().isEqualTo(status).expectBody(MAP).returnResult()
            .getResponseBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) problem.get("violations");
        return violations == null || violations.isEmpty() ? null : (String) violations.getFirst().get("ruleCode");
    }

    private Map<String, Object> listed(String job) {
        return client.get().uri("/api/jobs").header(HttpHeaders.AUTHORIZATION, admin()).exchange()
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody().stream()
            .filter(listedJob -> job.equals(listedJob.get("name"))).findFirst().orElseThrow();
    }

    /** The record of the run the request started, once it is over (the run is asynchronous). */
    private static Map<String, Object> awaitRun(long requestSeqId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            List<Map<String, Object>> runs = query("SELECT * FROM sys_job_run WHERE request_seq_id = ?",
                requestSeqId);
            if (!runs.isEmpty()) {
                assertThat(runs).hasSize(1);
                return runs.getFirst();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("The run of request " + requestSeqId + " did not end");
    }

    private void assertLockFree(String job) {
        // Released: no run would find it held (ShedLock's own time, as the lock compares it).
        assertThat(runner.tryLock(job(job))).hasValueSatisfying(SimpleLock::unlock);
    }
}

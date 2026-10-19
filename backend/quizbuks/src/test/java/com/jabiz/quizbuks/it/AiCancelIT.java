package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ai.AiCodes;
import com.jabiz.quizbuks.content.ai.AiLimits;
import com.jabiz.quizbuks.content.ai.AiProcesses;
import com.jabiz.quizbuks.content.ai.AiPrompt;
import com.jabiz.quizbuks.content.ai.ClaudeApi;
import com.jabiz.quizbuks.content.ai.QbAi;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cancelling AI jobs (platform decision D52 item 2): the sponsor's {@value AiProcesses#CANCEL} cancels the platform's
 * job in the same transaction, so a queued job never calls the provider; both job types' {@code onCancelled}
 * ({@value AiProcesses#CANCELLED_JOB}) closes the job once it has ended and charges what its attempts recorded, once,
 * whoever cancelled it (the sponsor, or an operator through {@code EXTERNAL_JOB_CANCEL}); a job no attempt of which
 * ran is charged nothing.
 */
class AiCancelIT extends AiItSupport {

    @BeforeEach
    void freshDay() {
        aiOn();
        advance(Duration.ofDays(1));
    }

    private static String title() {
        return "AI cancel " + UUID.randomUUID();
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    /** The sponsor's usage rows (one a month). */
    private static List<Map<String, Object>> usageRows(String sponsorId) {
        return query("SELECT * FROM qb_ai_usage WHERE sponsor_id = ?", sponsorId);
    }

    private static long countOf(String sql, Object... args) {
        return number(query(sql, args).getFirst().get("n"));
    }

    /** Who cancelled the platform's job, if anybody did. */
    private static List<Object> cancelledBy(Object externalJobId) {
        return query("SELECT cancelled_by FROM sys_external_job_cancel WHERE job_id = ?", String.valueOf(externalJobId))
            .stream().map(row -> row.get("cancelled_by")).toList();
    }

    /** Runs the due jobs in the background until the provider has been asked once more: an attempt is running. */
    private CompletableFuture<Integer> runningAttempt() throws InterruptedException {
        int calls = CLAUDE.requests(MESSAGES).size();
        CompletableFuture<Integer> running = jobExecutor.runPending().toFuture();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (CLAUDE.requests(MESSAGES).size() == calls) {
            assertThat(System.nanoTime()).as("the attempt calls the provider").isLessThan(deadline);
            Thread.sleep(10);
        }
        return running;
    }

    @Test
    void aSponsorsCancellationOfAQueuedJobNeverReachesTheProviderAndCostsNothing() {
        String sponsor = newSponsor("ai-cancel-queued-" + UUID.randomUUID());
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material.");
        Map<String, Object> queued = request(token, quiz.get(0), List.of(quiz.get(1)), 1);
        int calls = CLAUDE.requests(MESSAGES).size();

        assertThat(run(AiProcesses.CANCEL, token, body("aiJobId", queued.get("aiJobId"))))
            .containsEntry("status", QbAi.CANCELLED);
        // The platform's job too, in the same transaction, by the sponsor.
        assertThat(cancelledBy(queued.get("externalJobId"))).containsExactly(sponsor);
        runJobs();
        runJobs();

        assertThat(CLAUDE.requests(MESSAGES)).hasSize(calls);
        Map<String, Object> job = job(queued.get("aiJobId"));
        assertThat(job).containsEntry("status", QbAi.CANCELLED).containsEntry("failure_code", null)
            .containsEntry("candidates", null);
        assertThat(job.get("finished_at")).isNotNull();
        assertThat(number(job.get("input_tokens")) + number(job.get("output_tokens"))).isZero();
        assertThat(number(job.get("attempts"))).isZero();
        assertThat((BigDecimal) job.get("cost_usd")).isEqualByComparingTo(BigDecimal.ZERO);
        // Nothing charged: the sponsor's month has no row, and the job no longer counts as open.
        assertThat(usage(sponsor)).isEmpty();
        request(sponsorFull(sponsor), quiz.get(0), List.of(quiz.get(1)), 1);
        runJobs();
    }

    @Test
    void aSponsorsCancellationOfACoverJobCancelsThePlatformsCoverJob() {
        String sponsor = newSponsor("ai-cancel-cover-" + UUID.randomUUID());
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material.");
        Map<String, Object> queued = requestCovers(token, quiz.get(0));
        int calls = CLAUDE.requests(MESSAGES).size();

        assertThat(run(AiProcesses.CANCEL, token, body("aiJobId", queued.get("aiJobId"))))
            .containsEntry("status", QbAi.CANCELLED);
        assertThat(cancelledBy(queued.get("externalJobId"))).containsExactly(sponsor);
        runJobs();
        assertThat(CLAUDE.requests(MESSAGES)).hasSize(calls);
        Map<String, Object> job = job(queued.get("aiJobId"));
        assertThat(job).containsEntry("status", QbAi.CANCELLED).containsEntry("cover_1", null);
        assertThat(job.get("finished_at")).isNotNull();
        assertThat(usage(sponsor)).isEmpty();
    }

    @Test
    void cancellingARunningJobChargesWhatItsAttemptRecordedOnce() throws Exception {
        String sponsor = newSponsor("ai-cancel-running-" + UUID.randomUUID());
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material.");
        // The provider answers after a while (within the tests' two-second request timeout).
        CLAUDE.script(MESSAGES, answer("claude-opus-5-5", "end_turn", questions(1), 700, 300)
            .after(Duration.ofMillis(1200)));
        Map<String, Object> queued = request(token, quiz.get(0), List.of(quiz.get(1)), 1);
        CompletableFuture<Integer> running = runningAttempt();

        assertThat(run(AiProcesses.CANCEL, token, body("aiJobId", queued.get("aiJobId"))))
            .containsEntry("status", QbAi.CANCELLED);
        assertThat(running.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        runJobs();

        // The attempt's answer is dropped, its usage charged: the provider billed it.
        Map<String, Object> job = job(queued.get("aiJobId"));
        assertThat(job).containsEntry("status", QbAi.CANCELLED).containsEntry("candidates", null)
            .containsEntry("served_model", null).containsEntry("failure_code", null);
        assertThat(job.get("finished_at")).isNotNull();
        assertThat(number(job.get("input_tokens"))).isEqualTo(700);
        assertThat(number(job.get("output_tokens"))).isEqualTo(300);
        assertThat(number(job.get("attempts"))).isEqualTo(1);
        // Opus 5.5 at $4 / $20 a million.
        assertThat((BigDecimal) job.get("cost_usd")).isEqualByComparingTo("0.008800");
        Map<String, Object> month = usage(sponsor);
        assertThat(number(month.get("jobs"))).isEqualTo(1);
        // A cancellation is no failure (the administrators' failure rate).
        assertThat(number(month.get("failed_jobs"))).isZero();
        assertThat(number(month.get("input_tokens")) + number(month.get("output_tokens"))).isEqualTo(1000);

        // Once: nothing more is charged however often the jobs and events run, and nobody can cancel it again.
        runJobs();
        deliverEvents();
        assertThat(usage(sponsor)).isEqualTo(month);
        String operator = TestTokens.bearer(tokens, "ai-operator", "external-job.cancel");
        client.post().uri("/api/external-jobs/" + queued.get("externalJobId") + "/cancel")
            .header("Authorization", operator).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isEqualTo(422);
        assertThat(codes(refused(AiProcesses.CANCEL, token, body("aiJobId", queued.get("aiJobId")), 422)))
            .contains(AiCodes.JOB_NOT_READY);
    }

    @Test
    void anOperatorsCancellationIsChargedThroughTheSameProcess() throws Exception {
        String sponsor = newSponsor("ai-cancel-operator-" + UUID.randomUUID());
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material.");
        CLAUDE.script(MESSAGES, answer("claude-opus-5-5", "end_turn", questions(1), 500, 250)
            .after(Duration.ofMillis(1200)));
        Map<String, Object> queued = request(token, quiz.get(0), List.of(quiz.get(1)), 1);
        CompletableFuture<Integer> running = runningAttempt();

        String operator = TestTokens.bearer(tokens, "ai-operator", "external-job.cancel");
        client.post().uri("/api/external-jobs/" + queued.get("externalJobId") + "/cancel")
            .header("Authorization", operator).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isOk();
        assertThat(running.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        runJobs();

        Map<String, Object> job = job(queued.get("aiJobId"));
        assertThat(job).containsEntry("status", QbAi.CANCELLED).containsEntry("failure_code", AiCodes.STOPPED)
            .containsEntry("candidates", null);
        assertThat(job.get("closed_at")).isNotNull();
        assertThat(number(job.get("input_tokens")) + number(job.get("output_tokens"))).isEqualTo(750);
        Map<String, Object> month = usage(sponsor);
        assertThat(number(month.get("jobs"))).isEqualTo(1);
        assertThat(number(month.get("input_tokens")) + number(month.get("output_tokens"))).isEqualTo(750);
        runJobs();
        deliverEvents();
        assertThat(usage(sponsor)).isEqualTo(month);
    }

    @Test
    void cancellingWhileAnUnansweredAttemptWaitsForItsRetryChargesThatAttemptsEstimateOnly() {
        String sponsor = newSponsor("ai-cancel-retry-" + UUID.randomUUID());
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material for a slow provider.");
        // Attempt 1 gets no answer in time (the tests' request timeout is 2 s): charged its estimate, then a retry waits.
        CLAUDE.script(MESSAGES, answer("claude-opus-5-5", "end_turn", questions(1), 100, 100)
            .after(Duration.ofSeconds(5)));
        Map<String, Object> queued = request(token, quiz.get(0), List.of(quiz.get(1)), 1);
        runJobs();
        String externalJobId = String.valueOf(queued.get("externalJobId"));
        assertThat(countOf("SELECT count(*) AS n FROM sys_external_job_attempt WHERE job_id = ?", externalJobId))
            .isEqualTo(1);
        assertThat(countOf("SELECT count(*) AS n FROM sys_external_job_outcome WHERE job_id = ?", externalJobId))
            .isZero();
        assertThat(job(queued.get("aiJobId")).get("finished_at")).isNull();
        int calls = CLAUDE.requests(MESSAGES).size();

        assertThat(run(AiProcesses.CANCEL, token, body("aiJobId", queued.get("aiJobId"))))
            .containsEntry("status", QbAi.CANCELLED);
        advance(Duration.ofMinutes(3));
        runJobs();

        // No retry, and attempt 1's estimate charged: its whole input and all the output it asked for.
        assertThat(CLAUDE.requests(MESSAGES)).hasSize(calls);
        JsonNode body = sent().getLast();
        long bytes = body.get("messages").get(0).get("content").get(0).get("text").asString()
            .getBytes(StandardCharsets.UTF_8).length + AiPrompt.SYSTEM.getBytes(StandardCharsets.UTF_8).length;
        Map<String, Object> job = job(queued.get("aiJobId"));
        assertThat(job).containsEntry("status", QbAi.CANCELLED).containsEntry("candidates", null);
        assertThat(number(job.get("attempts"))).isEqualTo(1);
        assertThat(number(job.get("input_tokens"))).isEqualTo(AiLimits.inputEstimate(bytes));
        assertThat(number(job.get("output_tokens"))).isEqualTo(ClaudeApi.maxTokens(1));
        List<Map<String, Object>> rows = usageRows(sponsor);
        assertThat(rows).hasSize(1);
        assertThat(number(rows.getFirst().get("jobs"))).isEqualTo(1);
        assertThat(number(rows.getFirst().get("input_tokens")) + number(rows.getFirst().get("output_tokens")))
            .isEqualTo(AiLimits.inputEstimate(bytes) + ClaudeApi.maxTokens(1));
    }

    @Test
    void cancellingAfterThePlatformJobSucceededButBeforeItsOutcomeIsDeliveredChargesItOnceAndKeepsNothing() {
        String sponsor = newSponsor("ai-cancel-late-" + UUID.randomUUID());
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material.");
        Map<String, Object> queued = request(token, quiz.get(0), List.of(quiz.get(1)), 2);
        // The attempt succeeds; its outcome process waits in the outbox.
        assertThat(jobExecutor.runPending().block()).isEqualTo(1);
        assertThat(job(queued.get("aiJobId"))).containsEntry("status", QbAi.QUEUED);

        // The sponsor still sees it queued and cancels: the platform's job is over already, which is no error.
        assertThat(run(AiProcesses.CANCEL, token, body("aiJobId", queued.get("aiJobId"))))
            .containsEntry("status", QbAi.CANCELLED);
        assertThat(cancelledBy(queued.get("externalJobId"))).isEmpty();
        deliverEvents();
        runJobs();

        Map<String, Object> job = job(queued.get("aiJobId"));
        assertThat(job).containsEntry("status", QbAi.CANCELLED).containsEntry("candidates", null)
            .containsEntry("expires_at", null);
        assertThat(number(job.get("input_tokens"))).isEqualTo(1200);
        assertThat(number(job.get("output_tokens"))).isEqualTo(800);
        List<Map<String, Object>> rows = usageRows(sponsor);
        assertThat(rows).hasSize(1);
        assertThat(number(rows.getFirst().get("jobs"))).isEqualTo(1);
        assertThat(number(rows.getFirst().get("input_tokens")) + number(rows.getFirst().get("output_tokens")))
            .isEqualTo(2000);
        deliverEvents();
        assertThat(usageRows(sponsor)).isEqualTo(rows);
    }

    @Test
    void aCoverJobCancelledAfterItsPicturesWereStoredNeverRefersToThem() {
        String sponsor = newSponsor("ai-cancel-late-cover-" + UUID.randomUUID());
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material.");
        Map<String, Object> queued = requestCovers(token, quiz.get(0));
        assertThat(jobExecutor.runPending().block()).isEqualTo(1);
        String externalJobId = String.valueOf(queued.get("externalJobId"));
        // The platform stored the three pictures the attempt drew.
        List<Object> pictures = query("SELECT file_id FROM sys_external_job_file WHERE job_id = ?"
            + " AND direction = 'OUTPUT'", externalJobId).stream().map(row -> row.get("file_id")).toList();
        assertThat(pictures).hasSize(3);

        assertThat(run(AiProcesses.CANCEL, token, body("aiJobId", queued.get("aiJobId"))))
            .containsEntry("status", QbAi.CANCELLED);
        deliverEvents();
        runJobs();

        Map<String, Object> job = job(queued.get("aiJobId"));
        assertThat(job).containsEntry("status", QbAi.CANCELLED).containsEntry("candidates", null)
            .containsEntry("cover_1", null).containsEntry("cover_2", null).containsEntry("cover_3", null);
        assertThat(number(job.get("input_tokens")) + number(job.get("output_tokens"))).isEqualTo(500);
        // Nothing refers to the pictures: the platform's sweep removes them.
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quiz.get(0) + "'").getFirst())
            .containsEntry("cover", null);
        assertThat(countOf("SELECT count(*) AS n FROM qb_ai_job WHERE cover_1 = ANY(?::uuid[]) OR cover_2 = ANY(?::uuid[])"
            + " OR cover_3 = ANY(?::uuid[])", ids(pictures), ids(pictures), ids(pictures))).isZero();
        assertThat(number(usageRows(sponsor).getFirst().get("jobs"))).isEqualTo(1);
    }

    private static String ids(List<Object> values) {
        return "{" + String.join(",", values.stream().map(String::valueOf).toList()) + "}";
    }
}

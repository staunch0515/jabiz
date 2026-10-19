package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ai.AiCodes;
import com.jabiz.quizbuks.content.ai.AiProcesses;
import com.jabiz.quizbuks.content.ai.QbAi;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
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
}

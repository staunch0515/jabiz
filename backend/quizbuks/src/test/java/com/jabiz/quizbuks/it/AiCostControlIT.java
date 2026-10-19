package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.content.ai.AiCodes;
import com.jabiz.quizbuks.content.ai.AiCost;
import com.jabiz.quizbuks.content.ai.AiLimits;
import com.jabiz.quizbuks.content.ai.AiProcesses;
import com.jabiz.quizbuks.content.ai.AiQuestionsJob;
import com.jabiz.quizbuks.content.ai.ClaudeApi;
import com.jabiz.quizbuks.content.ai.QbAi;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.StubHttpServer;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What AI generation may cost, and how it is stopped (docs/quizbuks/plans/Q8-ai.md, Q8a, after the independent
 * review): attempts that got no answer are charged, the estimate covers every attempt, the global limit counts
 * everybody's running jobs, high effort asks for fewer questions, switching off (which holds the jobs not yet
 * running, platform decision D52 item 4) and an operator's stop, attempts that recorded nothing because their instance
 * died (charged the same estimate, platform decision D52 item 5, W-6), and the refusals and checks of the sponsor's own
 * processes.
 */
class AiCostControlIT extends AiItSupport {

    @BeforeEach
    void freshDay() {
        aiOn();
        advance(Duration.ofDays(1));
    }

    private static String title() {
        return "AI cost " + UUID.randomUUID();
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static long tokens(Map<String, Object> usage) {
        return number(usage.get("input_tokens")) + number(usage.get("output_tokens"));
    }

    /** The tokens recorded in the month of the clock (jabiz.fiscal-year-zone is Asia/Tokyo in the tests). */
    private long monthTokens(String who) {
        List<Map<String, Object>> rows = query("SELECT * FROM qb_ai_usage WHERE sponsor_id = ? AND month = ?", who,
            AiLimits.month(clock.instant(), java.time.ZoneId.of("Asia/Tokyo")));
        return rows.isEmpty() ? 0 : tokens(rows.getFirst());
    }

    /** Runs the jobs, letting their retries come due, until the job is no longer queued. */
    private void runUntilDone(Object aiJobId) {
        for (int i = 0; i < 6 && job(aiJobId).get("finished_at") == null; i++) {
            runJobs();
            advance(Duration.ofMinutes(3));
        }
    }

    @Test
    void attemptsWithoutAnAnswerAreChargedTheirEstimateAndHitTheLimit() {
        String sponsor = newSponsor("ai-timeout-" + UUID.randomUUID());
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material for a slow provider.");
        // The provider answers after the client stopped waiting (quizbuks.ai.request-timeout is 2 s here), three times.
        for (int i = 0; i < AiQuestionsJob.ATTEMPTS; i++) {
            CLAUDE.script(MESSAGES, answer("claude-opus-5-5", "end_turn", questions(1), 100, 100)
                .after(Duration.ofSeconds(5)));
        }
        Map<String, Object> requested = request(token, quiz.get(0), List.of(quiz.get(1)), 1);
        Object aiJobId = requested.get("aiJobId");
        Map<String, Object> asked = job(aiJobId);
        // The estimate covers every attempt in full.
        long estimate = number(asked.get("estimated_tokens"));
        assertThat(estimate).isEqualTo(AiLimits.estimate(number(asked.get("input_bytes")), 1))
            .isEqualTo(AiQuestionsJob.ATTEMPTS * AiLimits.attemptEstimate(number(asked.get("input_bytes")), 1));

        runUntilDone(aiJobId);
        Map<String, Object> failed = job(aiJobId);
        assertThat(failed).containsEntry("status", QbAi.FAILED).containsEntry("failure_code", "EXTERNAL_CALL_FAILED");
        assertThat(number(failed.get("attempts"))).isEqualTo(AiQuestionsJob.ATTEMPTS);
        // Every attempt charged what it may have cost: the input the job reserved for an attempt, and all the output
        // it asked for, so that the job is charged exactly its estimate.
        JsonNode body = sent().getLast();
        assertThat(body.get("max_tokens").asInt()).isEqualTo(ClaudeApi.maxTokens(1));
        assertThat(number(failed.get("input_tokens"))).isEqualTo(AiQuestionsJob.ATTEMPTS
            * AiLimits.reservedInput(number(asked.get("input_bytes")), 0));
        assertThat(number(failed.get("output_tokens"))).isEqualTo(AiQuestionsJob.ATTEMPTS * ClaudeApi.maxTokens(1));
        Map<String, Object> usage = usage(sponsor);
        assertThat(tokens(usage)).isEqualTo(number(failed.get("input_tokens")) + number(failed.get("output_tokens")))
            .isEqualTo(estimate);
        assertThat((java.math.BigDecimal) failed.get("cost_usd")).isPositive();

        // With a month's limit that one job fits in, the charged time-outs leave no room for the next.
        param(QbParams.AI_SPONSOR_MONTHLY_TOKENS, String.valueOf(estimate + estimate / 2));
        try {
            String again = sponsorFull(sponsor);
            List<String> other = quizWithArticle(again, title(), "Material for a slow provider.");
            assertThat(codes(refused(AiProcesses.REQUEST, again, requestInput(other.get(0), List.of(other.get(1)), 1),
                422))).containsExactly(AiCodes.MONTHLY_LIMIT);
        } finally {
            param(QbParams.AI_SPONSOR_MONTHLY_TOKENS, "2000000");
        }
    }

    @Test
    void theGlobalLimitCountsOtherSponsorsRunningJobs() {
        String other = newSponsor("ai-global-" + UUID.randomUUID());
        String mine = newSponsor("ai-global-" + UUID.randomUUID());
        List<String> theirs = quizWithArticle(sponsorFull(other), title(), "The same material.");
        List<String> ours = quizWithArticle(sponsorFull(mine), title(), "The same material.");
        Object running = request(sponsorFull(other), theirs.get(0), List.of(theirs.get(1)), 2).get("aiJobId");
        long estimate = number(job(running).get("estimated_tokens"));
        long recorded = monthTokens(QbAi.EVERYBODY);
        long open = number(query("SELECT sum(estimated_tokens) AS n FROM qb_ai_job WHERE finished_at IS NULL")
            .getFirst().get("n"));
        // Room for the running job, not for another of the same size beside it.
        param(QbParams.AI_GLOBAL_MONTHLY_TOKENS, String.valueOf(recorded + open + estimate - 1));
        try {
            assertThat(codes(refused(AiProcesses.REQUEST, sponsorFull(mine),
                requestInput(ours.get(0), List.of(ours.get(1)), 2), 422))).containsExactly(AiCodes.GLOBAL_LIMIT);
            param(QbParams.AI_GLOBAL_MONTHLY_TOKENS, String.valueOf(recorded + open + estimate));
            request(sponsorFull(mine), ours.get(0), List.of(ours.get(1)), 2);
        } finally {
            param(QbParams.AI_GLOBAL_MONTHLY_TOKENS, "50000000");
        }
        runJobs();
    }

    @Test
    void highEffortAsksForFewerQuestionsAndTheCardSaysSo() {
        String token = sponsorFull(SPONSOR_A);
        List<String> quiz = quizWithArticle(token, title(), "Material.");
        param(QbParams.AI_EFFORT, "high");
        try {
            Map<String, Object> problem = refused(AiProcesses.REQUEST, token,
                requestInput(quiz.get(0), List.of(quiz.get(1)), AiQuestionsJob.MAX_COUNT_HIGH_EFFORT + 1), 422);
            assertThat(codes(problem)).containsExactly(AiCodes.COUNT_TOO_HIGH);
            assertThat(number(items("qb.sponsor.ai-usage", token, body("params",
                Map.of("at", clock.instant().toString()))).getFirst().get("maxCount")))
                .isEqualTo(AiQuestionsJob.MAX_COUNT_HIGH_EFFORT);
            request(token, quiz.get(0), List.of(quiz.get(1)), AiQuestionsJob.MAX_COUNT_HIGH_EFFORT);
            runJobs();
            assertThat(sent().getLast().get("output_config").get("effort").asString()).isEqualTo("high");
            assertThat(sent().getLast().get("max_tokens").asInt())
                .isEqualTo(ClaudeApi.maxTokens(AiQuestionsJob.MAX_COUNT_HIGH_EFFORT));
        } finally {
            param(QbParams.AI_EFFORT, "medium");
        }
        assertThat(number(items("qb.sponsor.ai-usage", token, body("params",
            Map.of("at", clock.instant().toString()))).getFirst().get("maxCount"))).isEqualTo(AiQuestionsJob.MAX_COUNT);
    }

    @Test
    void switchingOffStopsNewRequestsAndAnOperatorStopsTheQueuedOnes() {
        String sponsor = newSponsor("ai-stop-" + UUID.randomUUID());
        String token = sponsorFull(sponsor);
        List<String> first = quizWithArticle(token, title(), "Material.");
        List<String> second = quizWithArticle(token, title(), "Material.");
        Map<String, Object> queued = request(token, first.get(0), List.of(first.get(1)), 1);
        param(QbParams.AI_ENABLED, "false");
        try {
            // Off: no new request, whoever asks.
            assertThat(codes(refused(AiProcesses.REQUEST, token, requestInput(second.get(0),
                List.of(second.get(1)), 1), 422))).containsExactly(AiCodes.UNAVAILABLE);
            // The job already queued is held (enabledBy, platform D52 item 4); cancelling it still works and, since no
            // attempt ran, charges nothing (operations.md section 7.8).
            String operator = TestTokens.bearer(tokens, "ai-operator", "external-job.cancel");
            client.post().uri("/api/external-jobs/" + queued.get("externalJobId") + "/cancel")
                .header("Authorization", operator).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isOk();
            int calls = CLAUDE.requests(MESSAGES).size();
            runJobs();
            assertThat(CLAUDE.requests(MESSAGES)).hasSize(calls);
            Map<String, Object> stopped = job(queued.get("aiJobId"));
            assertThat(stopped).containsEntry("status", QbAi.CANCELLED).containsEntry("failure_code", AiCodes.STOPPED);
            assertThat(stopped.get("finished_at")).isNotNull();
            // Closed through the job types' onCancelled: no attempt ran, so nothing was used, charged or reserved.
            assertThat(number(stopped.get("input_tokens")) + number(stopped.get("output_tokens"))).isZero();
            assertThat(usage(sponsor)).isEmpty();
        } finally {
            param(QbParams.AI_ENABLED, "true");
        }
        // The sponsor can ask again: the stopped job is finished.
        request(sponsorFull(sponsor), first.get(0), List.of(first.get(1)), 1);
        runJobs();
    }

    @Test
    void switchingOffHoldsQueuedAndRetryingJobsUntilItIsOnAgain() {
        String sponsor = newSponsor("ai-held-" + UUID.randomUUID());
        String token = sponsorFull(sponsor);
        List<String> questions = quizWithArticle(token, title(), "Material.");
        List<String> covers = quizWithArticle(token, title(), "Material.");
        // A question job whose first attempt met a busy provider: waiting for its retry.
        CLAUDE.script(MESSAGES, StubHttpServer.Answer.json(529,
            "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}"));
        Map<String, Object> retrying = request(token, questions.get(0), List.of(questions.get(1)), 1);
        runJobs();
        assertThat(attempts(retrying)).isEqualTo(1);
        // And a cover job, queued: both job types are held by the same switch.
        Map<String, Object> queued = requestCovers(token, covers.get(0));
        param(QbParams.AI_ENABLED, "false");
        try {
            int calls = CLAUDE.requests(MESSAGES).size() + CLAUDE.requests(COUNT_TOKENS).size();
            for (int round = 0; round < 3; round++) {
                advance(Duration.ofMinutes(3));
                runJobs();
            }
            // Nothing is sent, no attempt is used, and neither job fails: they wait, as the sponsor sees them.
            assertThat(CLAUDE.requests(MESSAGES).size() + CLAUDE.requests(COUNT_TOKENS).size()).isEqualTo(calls);
            assertThat(attempts(retrying)).isEqualTo(1);
            assertThat(attempts(queued)).isZero();
            assertThat(countOf("SELECT count(*) AS n FROM sys_external_job_claim WHERE job_id = ?",
                queued.get("externalJobId"))).isZero();
            for (Map<String, Object> held : List.of(retrying, queued)) {
                assertThat(job(held.get("aiJobId"))).containsEntry("status", QbAi.QUEUED)
                    .containsEntry("finished_at", null);
            }
        } finally {
            param(QbParams.AI_ENABLED, "true");
        }
        // On again: both are claimed at the next poll and complete, the retrying one with its second attempt.
        runUntilDone(retrying.get("aiJobId"));
        runUntilDone(queued.get("aiJobId"));
        assertThat(job(retrying.get("aiJobId"))).containsEntry("status", QbAi.READY);
        assertThat(job(queued.get("aiJobId"))).containsEntry("status", QbAi.READY);
        assertThat(attempts(retrying)).isEqualTo(2);
        assertThat(attempts(queued)).isEqualTo(1);
        // Charged once, exactly what the attempts recorded: the wait added nothing.
        long in = 0;
        long out = 0;
        for (Map<String, Object> done : List.of(retrying, queued)) {
            long jobIn = recorded(done, AiQuestionsJob.INPUT_TOKENS);
            long jobOut = recorded(done, AiQuestionsJob.OUTPUT_TOKENS);
            assertThat(number(job(done.get("aiJobId")).get("input_tokens"))).isEqualTo(jobIn);
            assertThat(number(job(done.get("aiJobId")).get("output_tokens"))).isEqualTo(jobOut);
            in += jobIn;
            out += jobOut;
        }
        assertThat(in + out).isPositive();
        Map<String, Object> charged = usage(sponsor);
        assertThat(number(charged.get("jobs"))).isEqualTo(2);
        assertThat(number(charged.get("input_tokens"))).isEqualTo(in);
        assertThat(number(charged.get("output_tokens"))).isEqualTo(out);
    }

    /** What the platform recorded for the request's job under the metric, over all its attempts. */
    private static long recorded(Map<String, Object> requested, String metric) {
        return number(query("SELECT coalesce(sum(quantity), 0) AS n FROM sys_external_job_usage WHERE job_id = ? "
            + "AND metric = ?", requested.get("externalJobId"), metric).getFirst().get("n"));
    }

    private static long countOf(String sql, Object... args) {
        return number(query(sql, args).getFirst().get("n"));
    }

    /** The platform's attempts of the request's job. */
    private static long attempts(Map<String, Object> requested) {
        return countOf("SELECT count(*) AS n FROM sys_external_job_attempt WHERE job_id = ?",
            requested.get("externalJobId"));
    }

    @Test
    void aRefusalWhoseFallbackWasBusyIsTriedAgainAndAuthIsNot() {
        String token = sponsorFull(SPONSOR_A);
        List<String> quiz = quizWithArticle(token, title(), "Material.");
        String busy = JSON.writeValueAsString(Map.of("id", "msg_stub", "type", "message", "model", "claude-opus-5-5",
            "stop_reason", "refusal", "stop_details", Map.of("type", "refusal", "category", "cyber",
                "recommended_model", "claude-opus-4-8"), "content", List.of(),
            "usage", Map.of("input_tokens", 300, "output_tokens", 0)));
        CLAUDE.script(MESSAGES, StubHttpServer.Answer.json(200, busy));
        Object retried = request(token, quiz.get(0), List.of(quiz.get(1)), 1).get("aiJobId");
        runUntilDone(retried);
        assertThat(job(retried)).containsEntry("status", QbAi.READY);
        assertThat(number(job(retried).get("attempts"))).isEqualTo(2);

        // A key the provider refuses: failed at once, as AUTH.
        List<String> other = quizWithArticle(sponsorFull(SPONSOR_A), title(), "Material.");
        CLAUDE.script(MESSAGES, StubHttpServer.Answer.json(401,
            "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"));
        int calls = CLAUDE.requests(MESSAGES).size();
        Object refusedKey = request(sponsorFull(SPONSOR_A), other.get(0), List.of(other.get(1)), 1).get("aiJobId");
        runUntilDone(refusedKey);
        assertThat(job(refusedKey)).containsEntry("status", QbAi.FAILED).containsEntry("failure_code", AiCodes.AUTH);
        assertThat(number(job(refusedKey).get("attempts"))).isEqualTo(1);
        assertThat(CLAUDE.requests(MESSAGES)).hasSize(calls + 1);
    }

    @Test
    void theSponsorsOwnChecksHold() {
        String token = sponsorFull(SPONSOR_A);
        List<String> quiz = quizWithArticle(token, title(), "Material.");
        List<String> otherQuiz = quizWithArticle(token, title(), "Material.");
        Object queued = request(token, quiz.get(0), List.of(quiz.get(1)), 2).get("aiJobId");
        Map<String, Object> accept = body("aiJobId", queued, "quizId", quiz.get(0), "questions",
            List.of(reviewed(1)));
        // Not ready yet: nothing to accept. Another sponsor cannot cancel it.
        assertThat(codes(refused(AiProcesses.ACCEPT, token, accept, 422))).contains(AiCodes.JOB_NOT_READY);
        refused(AiProcesses.CANCEL, sponsorFull(SPONSOR_B), body("aiJobId", queued), 404);
        runJobs();
        assertThat(job(queued)).containsEntry("status", QbAi.READY);
        // Into another of the sponsor's own quizzes: not this job's quiz.
        refused(AiProcesses.ACCEPT, token, body("aiJobId", queued, "quizId", otherQuiz.get(0), "questions",
            List.of(reviewed(1))), 404);
        // A stale revision.
        long revision = number(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quiz.get(0) + "'").getFirst()
            .get("revision"));
        Map<String, Object> stale = body("aiJobId", queued, "quizId", quiz.get(0), "baseRevision", revision - 1,
            "questions", List.of(reviewed(1)));
        assertThat(codes(refused(AiProcesses.ACCEPT, token, stale, 422))).contains(ContentCodes.QUIZ_CHANGED);
        assertThat(job(queued)).containsEntry("status", QbAi.READY);

        // The sponsor dataset gives every column: it is not readable itself (the templates choose).
        client.post().uri("/api/datasets/" + QbAi.sponsorDataset(QbAi.JOB) + "/query").header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of()).exchange().expectStatus().isForbidden();
        client.post().uri("/api/datasets/" + QbAi.sponsorDataset(QbAi.USAGE) + "/query").header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of()).exchange().expectStatus().isForbidden();
        // The candidates are not repeated in the audit.
        assertThat(query("SELECT changes FROM sys_audit_record WHERE entity_type = ? AND entity_id = ?", QbAi.JOB,
            String.valueOf(queued))).isNotEmpty().allSatisfy(row -> assertThat(String.valueOf(row.get("changes")))
            .doesNotContain("Generated question"));

        // A banned sponsor, and one whose e-mail address is not verified, ask nothing.
        String banned = newSponsor("ai-banned-" + UUID.randomUUID());
        List<String> theirs = quizWithArticle(sponsorFull(banned), title(), "Material.");
        String boss = userWithRole("ai-boss-" + UUID.randomUUID(), QbRoles.ADMIN_SUPER);
        run("QB_USER_BAN", tokenOf(boss, QbRoles.ADMIN_SUPER), body("userId", banned, "reason", "Fraud"));
        assertThat(codes(refused(AiProcesses.REQUEST, sponsorFull(banned), requestInput(theirs.get(0),
            List.of(theirs.get(1)), 1), 422))).contains("QB_USER_BANNED");
        assertThat(refused(AiProcesses.REQUEST, unverifiedTokenOf(SPONSOR_A, QbRoles.SPONSOR),
            requestInput(quiz.get(0), List.of(quiz.get(1)), 1), 403).toString()).contains("EMAIL_NOT_VERIFIED");
    }

    @Test
    void acceptingNeverPassesTheQuestionLimit() {
        String token = sponsorFull(SPONSOR_B);
        List<String> quiz = quizWithArticle(token, title(), "Material.");
        Object ready = request(token, quiz.get(0), List.of(quiz.get(1)), 2).get("aiJobId");
        runJobs();
        for (int i = 0; i < com.jabiz.quizbuks.content.ContentLimits.MAX_QUESTIONS - 1; i++) {
            question(token, quiz.get(0), "Question " + i + "?");
        }
        assertThat(codes(refused(AiProcesses.ACCEPT, token, body("aiJobId", ready, "quizId", quiz.get(0),
            "questions", List.of(reviewed(1), reviewed(2))), 422))).contains(ContentCodes.TOO_MANY_QUESTIONS);
        // One more fits.
        run(AiProcesses.ACCEPT, token, body("aiJobId", ready, "quizId", quiz.get(0), "questions",
            List.of(reviewed(1))));
    }

    @Test
    void theAdministratorsEstimatePricesEachModelsTokens() {
        String token = sponsorFull(SPONSOR_A);
        List<String> quiz = quizWithArticle(token, title(), "Material.");
        // Served by the fallback model after a refusal: 1,000 tokens in at Opus 5.5, 1,000 in and 2,000 out at Opus 5.
        String fellBack = JSON.writeValueAsString(Map.of("id", "msg_stub", "type", "message", "model", "claude-opus-5",
            "stop_reason", "end_turn", "content", List.of(Map.of("type", "text", "text", questions(1))),
            "usage", Map.of("input_tokens", 1_000, "output_tokens", 2_000, "iterations", List.of(
                Map.of("type", "message", "input_tokens", 1_000, "output_tokens", 0),
                Map.of("type", "fallback_message", "input_tokens", 1_000, "output_tokens", 2_000)))));
        CLAUDE.script(MESSAGES, StubHttpServer.Answer.json(200, fellBack));
        Object aiJobId = request(token, quiz.get(0), List.of(quiz.get(1)), 1).get("aiJobId");
        runJobs();
        Map<String, Object> job = job(aiJobId);
        assertThat(job).containsEntry("served_model", "claude-opus-5");
        assertThat(number(job.get("fallback_output_tokens"))).isEqualTo(2_000);
        assertThat((java.math.BigDecimal) job.get("cost_usd")).isEqualByComparingTo("0.059");
        assertThat(items("qb.admin.ai-usage", aiAdmin(), body())).anySatisfy(row -> assertThat(row.get("estimatedUsd"))
            .isNotNull());
    }

    /**
     * W-6: an attempt the platform reports as unrecorded (its instance claimed it and died, so its lease ran out with
     * nothing recorded) is charged what an unanswered one is: the input the job reserved for an attempt and all the
     * output it asked for, from the job's stored parameters, whichever way the job ends. Attempt 1 is the dead one;
     * attempt 2 then succeeds or is refused, or the sponsor's cancellation ends the job while attempt 1 still holds
     * its lease.
     */
    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "FAILED", "CANCELLED"})
    void anAttemptWhoseInstanceDiedIsChargedItsEstimate(String outcome) {
        String sponsor = newSponsor("ai-died-" + outcome.toLowerCase() + "-" + UUID.randomUUID());
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material for an instance that dies.");
        Map<String, Object> requested = request(token, quiz.get(0), List.of(quiz.get(1)), 2);
        Object aiJobId = requested.get("aiJobId");
        Object externalJobId = requested.get("externalJobId");
        Map<String, Object> asked = job(aiJobId);
        // A text-only job reserves the text's estimate for an attempt's input, and stores it with its parameters.
        long reserved = JSON.readTree(String.valueOf(asked.get("params"))).get("reservedInputTokens").asLong();
        assertThat(reserved).isEqualTo(AiLimits.reservedInput(number(asked.get("input_bytes")), 0));
        long estimateIn = reserved;
        long estimateOut = ClaudeApi.maxTokens(2);
        long everybodyBefore = monthTokens(QbAi.EVERYBODY);

        long recordedIn;
        long recordedOut;
        String servedModel;
        if ("CANCELLED".equals(outcome)) {
            // Running on the dead instance when the sponsor cancels: the job ends once its lease is over.
            claimedByADeadInstance(externalJobId, 1, clock.instant().plusSeconds(60));
            assertThat(run(AiProcesses.CANCEL, token, body("aiJobId", aiJobId))).containsEntry("status",
                QbAi.CANCELLED);
            runJobs();
            assertThat(job(aiJobId).get("finished_at")).isNull();
            advance(Duration.ofMinutes(2));
            runJobs();
            assertThat(platformAttempts(externalJobId)).containsExactly("1:LEASE_EXPIRED");
            recordedIn = 0;
            recordedOut = 0;
            servedModel = null;
        } else {
            claimedByADeadInstance(externalJobId, 1, clock.instant().minusSeconds(1));
            if ("FAILED".equals(outcome)) {
                // Attempt 2: a key the provider refuses, unbilled and recorded as nothing.
                CLAUDE.script(MESSAGES, StubHttpServer.Answer.json(401, "{\"type\":\"error\",\"error\":"
                    + "{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"));
            }
            runUntilDone(aiJobId);
            assertThat(platformAttempts(externalJobId)).containsExactly("1:LEASE_EXPIRED",
                "COMPLETED".equals(outcome) ? "2:SUCCEEDED" : "2:FAILED");
            // The stub's own answer bills 1,200 in and 800 out.
            recordedIn = "COMPLETED".equals(outcome) ? 1200 : 0;
            recordedOut = "COMPLETED".equals(outcome) ? 800 : 0;
            servedModel = "COMPLETED".equals(outcome) ? (String) asked.get("model") : null;
        }

        Map<String, Object> job = job(aiJobId);
        assertThat(job).containsEntry("status", switch (outcome) {
            case "COMPLETED" -> QbAi.READY;
            case "FAILED" -> QbAi.FAILED;
            default -> QbAi.CANCELLED;
        });
        assertThat(number(job.get("attempts"))).isEqualTo("CANCELLED".equals(outcome) ? 1 : 2);
        assertThat(number(job.get("input_tokens"))).isEqualTo(recordedIn + estimateIn);
        assertThat(number(job.get("output_tokens"))).isEqualTo(recordedOut + estimateOut);
        assertThat((java.math.BigDecimal) job.get("cost_usd")).isEqualByComparingTo(AiCost.of(
            (String) asked.get("model"), servedModel, new ClaudeApi.Usage(recordedIn + estimateIn,
                recordedOut + estimateOut), null));
        Map<String, Object> month = usage(sponsor);
        assertThat(number(month.get("jobs"))).isEqualTo(1);
        assertThat(number(month.get("failed_jobs"))).isEqualTo("FAILED".equals(outcome) ? 1 : 0);
        assertThat(tokens(month)).isEqualTo(recordedIn + estimateIn + recordedOut + estimateOut);
        // Everybody's month too (the global limit): the same charge, once.
        assertThat(monthTokens(QbAi.EVERYBODY) - everybodyBefore)
            .isEqualTo(recordedIn + estimateIn + recordedOut + estimateOut);
        assertThat((java.math.BigDecimal) month.get("cost_usd")).isEqualByComparingTo(
            (java.math.BigDecimal) job.get("cost_usd"));
        // Once: delivering again charges nothing more.
        runJobs();
        assertThat(usage(sponsor)).isEqualTo(month);
    }

    /**
     * W-6 with the platform's late usage (D52 item 5): an instance whose lease ran out while it waited for the
     * provider comes back with the provider's answer; its usage is recorded on that attempt, which is then no longer
     * unrecorded, so the job is charged what the provider reported for it, not the estimate.
     */
    @Test
    void aLateAttemptsRecordedUsageIsChargedNotTheEstimate() throws Exception {
        String sponsor = newSponsor("ai-late-" + UUID.randomUUID());
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithArticle(token, title(), "Material for a late instance.");
        // Attempt 1's answer comes after a while (within the tests' two-second request timeout).
        CLAUDE.script(MESSAGES, answer("claude-opus-5-5", "end_turn", questions(1), 700, 300)
            .after(Duration.ofMillis(1500)));
        Map<String, Object> requested = request(token, quiz.get(0), List.of(quiz.get(1)), 1);
        Object aiJobId = requested.get("aiJobId");
        Object externalJobId = requested.get("externalJobId");
        CompletableFuture<Integer> late = runningAttempt();

        // Its lease (the timeout, the margin and the time its files may take) runs out on the clock: another instance
        // records it LEASE_EXPIRED.
        long leaseUntil = number(query("SELECT (extract(epoch FROM lease_until) * 1000)::bigint AS ms"
            + " FROM sys_external_job_claim WHERE job_id = ? AND attempt_no = 1", String.valueOf(externalJobId))
            .getFirst().get("ms"));
        clock.set(java.time.Instant.ofEpochMilli(leaseUntil).plusSeconds(1));
        assertThat(otherInstance().runPending().block()).isZero();
        assertThat(platformAttempts(externalJobId)).containsExactly("1:LEASE_EXPIRED");
        // The late instance comes back: its result is dropped, its usage recorded on attempt 1.
        assertThat(late.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(query("SELECT metric, quantity FROM sys_external_job_usage WHERE job_id = ? AND attempt_no = 1"
            + " ORDER BY metric", String.valueOf(externalJobId)).stream()
            .map(row -> row.get("metric") + "=" + row.get("quantity")).toList())
            .containsExactly(AiQuestionsJob.INPUT_TOKENS + "=700", AiQuestionsJob.OUTPUT_TOKENS + "=300");
        deliverEvents();
        assertThat(job(aiJobId)).containsEntry("status", QbAi.QUEUED);

        // Attempt 2 answers (the stub's own: 1,200 in and 800 out).
        runUntilDone(aiJobId);
        assertThat(platformAttempts(externalJobId)).containsExactly("1:LEASE_EXPIRED", "2:SUCCEEDED");
        Map<String, Object> job = job(aiJobId);
        assertThat(job).containsEntry("status", QbAi.READY);
        assertThat(number(job.get("input_tokens"))).isEqualTo(700 + 1200);
        assertThat(number(job.get("output_tokens"))).isEqualTo(300 + 800);
        assertThat((java.math.BigDecimal) job.get("cost_usd")).isEqualByComparingTo(AiCost.of(
            (String) job.get("model"), (String) job.get("served_model"), new ClaudeApi.Usage(1900, 1100), null));
        assertThat(tokens(usage(sponsor))).isEqualTo(3000);
    }

    private static Map<String, Object> reviewed(int candidateNo) {
        return body("candidateNo", candidateNo, "stem", "Reviewed " + candidateNo + "?", "points", 5, "options",
            List.of(body("text", "Yes", "correct", true), body("text", "No", "correct", false)), "reviewed", true);
    }
}

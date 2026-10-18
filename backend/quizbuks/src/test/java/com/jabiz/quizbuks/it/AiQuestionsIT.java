package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ai.AiCodes;
import com.jabiz.quizbuks.content.ai.AiProcesses;
import com.jabiz.quizbuks.content.ai.ClaudeApi;
import com.jabiz.quizbuks.content.ai.QbAi;
import com.jabiz.quizbuks.messaging.QbLegal;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.StubHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI generation of questions end to end against the stub (docs/quizbuks/plans/Q8-ai.md, Q8a): the request, the job,
 * the candidates checked and kept for review, accepting reviewed questions, discarding, cancelling, the expiry, the
 * limits under the sponsor's lock, the AI notice, refusals and retries, the input cap, what is (and is not) sent, and
 * who may do what.
 */
class AiQuestionsIT extends AiItSupport {

    @org.springframework.beans.factory.annotation.Autowired
    com.jabiz.quizbuks.content.ai.AiUserKeys userKeys;

    @BeforeEach
    void freshDay() {
        aiOn();
        // A new day for each test: the day's requests start from none.
        advance(Duration.ofDays(1));
    }

    private String title() {
        return "AI quiz " + UUID.randomUUID();
    }

    @Test
    void candidatesAreReviewedBeforeTheyAreAdded() {
        String token = sponsorFull(SPONSOR_A);
        List<String> quiz = quizWithArticle(token, title(), "Paris is the capital of France. <b>bold</b>");
        // An existing question of the sponsor's: sent so the candidates do not repeat it.
        question(token, quiz.get(0), "What is the capital of Italy?");

        Map<String, Object> requested = request(token, quiz.get(0), List.of(quiz.get(1)), 3);
        assertThat(requested).containsEntry("status", QbAi.QUEUED);
        Object aiJobId = requested.get("aiJobId");
        // The AI notice was accepted with the request.
        assertThat(consentRows(SPONSOR_A)).anySatisfy(row -> assertThat(row)
            .containsEntry("kind", QbLegal.AI_NOTICE).containsEntry("source", QbLegal.SOURCE_AI));

        runJobs();
        Map<String, Object> job = job(aiJobId);
        assertThat(job).containsEntry("status", QbAi.READY).containsEntry("served_model", "claude-opus-5-5");
        assertThat(((Number) job.get("candidate_count")).intValue()).isEqualTo(3);
        assertThat(((Number) job.get("input_tokens")).longValue()).isEqualTo(1200);
        assertThat(((Number) job.get("output_tokens")).longValue()).isEqualTo(800);
        assertThat(job.get("expires_at")).isNotNull();
        assertThat(usage(SPONSOR_A)).containsKey("input_tokens");
        assertThat(((Number) usage(QbAi.EVERYBODY).get("jobs")).intValue()).isPositive();

        // What was sent: the model and effort, adaptive thinking, the answer's schema, the provider's fallback.
        JsonNode body = sent().getLast();
        assertThat(body.get("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(body.get("thinking").get("type").asString()).isEqualTo("adaptive");
        assertThat(body.get("output_config").get("effort").asString()).isEqualTo("medium");
        assertThat(body.get("output_config").get("format").get("type").asString()).isEqualTo("json_schema");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(ClaudeApi.maxTokens(3));
        assertThat(body.get("fallbacks").asString()).isEqualTo("default");
        StubHttpServer.Received received = CLAUDE.requests(MESSAGES).getLast();
        assertThat(received.header("x-api-key")).isEqualTo("test-only-quizbuks-claude-key");
        assertThat(received.header("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(received.header("anthropic-beta")).isEqualTo(ClaudeApi.FALLBACK_BETA);
        // The user id is an opaque HMAC, the same for the sponsor every time, never their id.
        String userKey = body.get("metadata").get("user_id").asString();
        assertThat(userKey).matches("qb-[0-9a-f]{32}").doesNotContain(SPONSOR_A)
            .isEqualTo(userKeys.of(SPONSOR_A)).isNotEqualTo(userKeys.of(SPONSOR_B));
        // The sponsor's content only, as data: the material, the existing question, '<' escaped.
        String text = body.get("messages").get(0).get("content").get(0).get("text").asString();
        assertThat(text).contains("Paris is the capital of France.", "What is the capital of Italy?",
            "\\u003cb>bold\\u003c/b>").doesNotContain("<b>");
        // Nothing of the sponsor's identity or of any taker.
        assertThat(received.body()).doesNotContain(SPONSOR_A, "Company of " + SPONSOR_A, "Owner of")
            .doesNotContainPattern("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

        // The sponsor sees the candidates; another sponsor does not.
        Map<String, Object> request = body("params", Map.of("quizId", quiz.get(0)));
        List<Map<String, Object>> mine = items("qb.sponsor.ai-jobs", token, request);
        assertThat(mine).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("status", QbAi.READY).doesNotContainKey("servedModel");
            assertThat(String.valueOf(row.get("candidates"))).contains("Generated question 1?");
        });
        assertThat(items("qb.sponsor.ai-jobs", sponsorFull(SPONSOR_B), request)).isEmpty();

        // Accept two, the first rewritten; every one reviewed.
        long revision = ((Number) latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quiz.get(0) + "'")
            .getFirst().get("revision")).longValue();
        Map<String, Object> accepted = run(AiProcesses.ACCEPT, token, body("aiJobId", aiJobId, "quizId",
            quiz.get(0), "baseRevision", revision, "questions", List.of(
                accepted(1, "Which city is the capital of France?", true),
                accepted(3, "Generated question 3?", true))));
        assertThat(accepted.get("questionIds")).asList().hasSize(2);
        List<Map<String, Object>> added = latest("qb_question_version", "question_id",
            "quiz_id = '" + quiz.get(0) + "' AND ai_job_id IS NOT NULL AND NOT is_deleted");
        assertThat(added).extracting(row -> row.get("stem")).containsExactlyInAnyOrder(
            "Which city is the capital of France?", "Generated question 3?");
        assertThat(added).allSatisfy(row -> assertThat(row).containsEntry("ai_job_id", String.valueOf(aiJobId)));
        Map<String, Object> quizRow = latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quiz.get(0) + "'")
            .getFirst();
        assertThat(quizRow).containsEntry("ai_generated", true).containsEntry("changed_since_version", true);
        assertThat(((Number) quizRow.get("question_count")).intValue()).isEqualTo(3);
        assertThat(job(aiJobId)).containsEntry("status", QbAi.ACCEPTED).containsEntry("candidates", null);
        // Accepted once only.
        assertThat(codes(refused(AiProcesses.ACCEPT, token, body("aiJobId", aiJobId, "quizId", quiz.get(0),
            "questions", List.of(accepted(2, "Again?", true))), 422))).contains(AiCodes.JOB_NOT_READY);
        // The request's operation record names the materials, never their text.
        assertThat(query("SELECT input_summary::text AS s FROM op_process WHERE process_name = ? ORDER BY "
            + "process_seq_id DESC LIMIT 1", AiProcesses.REQUEST).getFirst().get("s").toString())
            .doesNotContain("Paris is the capital");
        assertContentInsertOnly();
    }

    private static Map<String, Object> accepted(int candidateNo, String stem, Boolean reviewed) {
        Map<String, Object> question = body("candidateNo", candidateNo, "stem", stem, "points", 5, "options",
            List.of(body("text", "Paris", "correct", true), body("text", "Lyon", "correct", false)));
        if (reviewed != null) {
            question.put("reviewed", reviewed);
        }
        return question;
    }

    private void assertContentInsertOnly() {
        assertOnlyInserted();
    }

    @Test
    void everyAcceptedQuestionIsReviewedAndMeetsTheRules() {
        String token = sponsorFull(SPONSOR_A);
        List<String> quiz = quizWithArticle(token, title(), "Material.");
        Object aiJobId = request(token, quiz.get(0), List.of(quiz.get(1)), 2).get("aiJobId");
        runJobs();
        // Not reviewed, or the mark missing: 400.
        refused(AiProcesses.ACCEPT, token, body("aiJobId", aiJobId, "quizId", quiz.get(0), "questions",
            List.of(accepted(1, "Q?", false))), 400);
        refused(AiProcesses.ACCEPT, token, body("aiJobId", aiJobId, "quizId", quiz.get(0), "questions",
            List.of(accepted(1, "Q?", null))), 400);
        // Rewritten into something a quiz cannot hold: the content rules, as for a version.
        Map<String, Object> broken = body("candidateNo", 1, "stem", " ", "points", 5000, "options",
            List.of(body("text", "Only one", "correct", false)), "reviewed", true);
        assertThat(codes(refused(AiProcesses.ACCEPT, token, body("aiJobId", aiJobId, "quizId", quiz.get(0),
            "questions", List.of(broken)), 422))).contains("QB_QUESTION_STEM_REQUIRED", "QB_QUESTION_OPTION_COUNT",
            "QB_QUESTION_NO_CORRECT", "QB_QUESTION_POINTS_RANGE");
        // A candidate that is not one of the job's.
        assertThat(codes(refused(AiProcesses.ACCEPT, token, body("aiJobId", aiJobId, "quizId", quiz.get(0),
            "questions", List.of(accepted(7, "Q?", true))), 422))).contains(AiCodes.CANDIDATE_UNKNOWN);
        // Another sponsor cannot touch it: not found.
        refused(AiProcesses.ACCEPT, sponsorFull(SPONSOR_B), body("aiJobId", aiJobId, "quizId", quiz.get(0),
            "questions", List.of(accepted(1, "Q?", true))), 404);
        refused(AiProcesses.DISCARD, sponsorFull(SPONSOR_B), body("aiJobId", aiJobId), 404);
        assertThat(job(aiJobId)).containsEntry("status", QbAi.READY);
    }

    @Test
    void invalidCandidatesAreDroppedAndCounted() {
        String token = sponsorFull(SPONSOR_A);
        List<String> quiz = quizWithArticle(token, title(), "Material.");
        // Two good, one without a correct option, one with a single option, one with an unknown material only.
        String text = JSON.writeValueAsString(Map.of("questions", List.of(
            Map.of("stem", "Good 1?", "points", 10, "materials", List.of(1, 9),
                "options", List.of(Map.of("text", "a", "correct", true), Map.of("text", "b", "correct", false))),
            Map.of("stem", "No correct?", "points", 10, "materials", List.of(1),
                "options", List.of(Map.of("text", "a", "correct", false), Map.of("text", "b", "correct", false))),
            Map.of("stem", "One option?", "points", 10, "materials", List.of(1),
                "options", List.of(Map.of("text", "a", "correct", true))),
            Map.of("stem", "Good 2?", "points", 2000, "materials", List.of(),
                "options", List.of(Map.of("text", "a", "correct", true), Map.of("text", "b", "correct", false))),
            Map.of("stem", "Good 3?", "points", 10, "materials", List.of(),
                "options", List.of(Map.of("text", "a", "correct", true), Map.of("text", "b", "correct", true))))));
        // Served by the provider's fallback model (A-10): administrators see which.
        CLAUDE.script(MESSAGES, answer("claude-opus-5", "end_turn", text, 100, 100));
        Object aiJobId = request(token, quiz.get(0), List.of(quiz.get(1)), 4).get("aiJobId");
        runJobs();
        Map<String, Object> job = job(aiJobId);
        assertThat(job).containsEntry("status", QbAi.READY).containsEntry("served_model", "claude-opus-5");
        assertThat(((Number) job.get("candidate_count")).intValue()).isEqualTo(2);
        assertThat(((Number) job.get("invalid_count")).intValue()).isEqualTo(3);
        // Material 9 was never sent: dropped from the candidate.
        assertThat(String.valueOf(job.get("candidates"))).contains("\"materials\":[1]").doesNotContain("No correct");
    }

    @Test
    void discardAndCancelEndTheJobButNotItsUsage() {
        String token = sponsorFull(SPONSOR_A);
        List<String> first = quizWithArticle(token, title(), "Material.");
        Object ready = request(token, first.get(0), List.of(first.get(1)), 1).get("aiJobId");
        runJobs();
        assertThat(run(AiProcesses.DISCARD, token, body("aiJobId", ready))).containsEntry("status", QbAi.DISCARDED);
        assertThat(job(ready)).containsEntry("candidates", null);
        assertThat(codes(refused(AiProcesses.CANCEL, token, body("aiJobId", ready), 422)))
            .contains(AiCodes.JOB_NOT_READY);

        List<String> second = quizWithArticle(token, title(), "Material.");
        Object queued = request(token, second.get(0), List.of(second.get(1)), 1).get("aiJobId");
        long before = ((Number) usage(SPONSOR_A).get("jobs")).longValue();
        assertThat(run(AiProcesses.CANCEL, token, body("aiJobId", queued))).containsEntry("status", QbAi.CANCELLED);
        // The provider still answers; the result is not kept, its usage counts.
        runJobs();
        Map<String, Object> job = job(queued);
        assertThat(job).containsEntry("status", QbAi.CANCELLED).containsEntry("candidates", null);
        assertThat(job.get("finished_at")).isNotNull();
        assertThat(((Number) usage(SPONSOR_A).get("jobs")).longValue()).isEqualTo(before + 1);
    }

    @Test
    void candidatesExpireAfterTheirDays() {
        String token = sponsorFull(SPONSOR_A);
        List<String> quiz = quizWithArticle(token, title(), "Material.");
        Object aiJobId = request(token, quiz.get(0), List.of(quiz.get(1)), 1).get("aiJobId");
        runJobs();
        advance(Duration.ofDays(6));
        runExpiry();
        assertThat(job(aiJobId)).containsEntry("status", QbAi.READY);
        advance(Duration.ofDays(2));
        token = sponsorFull(SPONSOR_A);
        // Accepting after the expiry is refused even before the daily job ran.
        assertThat(codes(refused(AiProcesses.ACCEPT, token, body("aiJobId", aiJobId, "quizId", quiz.get(0),
            "questions", List.of(accepted(1, "Q?", true))), 422))).contains(AiCodes.JOB_EXPIRED);
        runExpiry();
        assertThat(job(aiJobId)).containsEntry("status", QbAi.EXPIRED).containsEntry("candidates", null);
    }

    @Test
    void theNoticeIsAcceptedBeforeAnythingIsSent() {
        String token = sponsorFull(SPONSOR_B);
        List<String> quiz = quizWithArticle(token, title(), "Material.");
        Map<String, Object> input = requestInput(quiz.get(0), List.of(quiz.get(1)), 1);
        // The sponsor accepted no version yet (sponsor B's first request in this class asks it).
        if (consentRows(SPONSOR_B).stream().noneMatch(row -> QbLegal.AI_NOTICE.equals(row.get("kind")))) {
            input.remove("aiNoticeVersionNo");
            assertThat(codes(refused(AiProcesses.REQUEST, token, input, 422))).contains(AiCodes.NOTICE_REQUIRED);
            input.put("aiNoticeVersionNo", noticeVersion() + 1);
            assertThat(codes(refused(AiProcesses.REQUEST, token, input, 422))).contains(AiCodes.NOTICE_REQUIRED);
            assertThat(sent()).isEmpty();
            input.put("aiNoticeVersionNo", noticeVersion());
        }
        run(AiProcesses.REQUEST, token, input);
        runJobs();
        // A new version that needs consent: asked again.
        publishNew(QbLegal.AI_NOTICE, "ai-" + UUID.randomUUID(), null, true);
        List<String> other = quizWithArticle(token, title(), "Material.");
        Map<String, Object> again = requestInput(other.get(0), List.of(other.get(1)), 1);
        again.put("aiNoticeVersionNo", noticeVersion() - 1);
        assertThat(codes(refused(AiProcesses.REQUEST, token, again, 422))).contains(AiCodes.NOTICE_REQUIRED);
    }

    @Test
    void theInputIsRefusedWhenTooLargeNeverCut() {
        String token = sponsorFull(SPONSOR_A);
        // Two articles of 20,000 three-byte characters: 120,000 bytes, twice the cap.
        String article = "あ".repeat(20_000);
        List<String> quiz = quizWithArticle(token, title(), article);
        String second = (String) run("QB_MATERIAL_SAVE", token, body("quizId", quiz.get(0), "kind", "ARTICLE",
            "title", "Second", "body", article)).get("itemId");
        long jobs = count("sys_external_job");
        Map<String, Object> problem = refused(AiProcesses.REQUEST, token,
            requestInput(quiz.get(0), List.of(quiz.get(1), second), 5), 422);
        assertThat(codes(problem)).contains(AiCodes.INPUT_TOO_LARGE);
        assertThat(count("sys_external_job")).isEqualTo(jobs);
        assertThat(sent()).isEmpty();
        // One of them alone fits.
        run(AiProcesses.REQUEST, token, requestInput(quiz.get(0), List.of(second), 1));
        runJobs();
        assertThat(sent().getLast().get("messages").get(0).get("content").get(0).get("text").asString())
            .contains(article);
    }

    @Test
    void theLimitsHoldEvenForRequestsAtOnce() throws Exception {
        String token = sponsorFull(SPONSOR_A);
        List<String> a = quizWithArticle(token, title(), "A.");
        List<String> b = quizWithArticle(token, title(), "B.");
        List<String> c = quizWithArticle(token, title(), "C.");
        // One quiz, one job at a time; two jobs per sponsor.
        request(token, a.get(0), List.of(a.get(1)), 1);
        assertThat(codes(refused(AiProcesses.REQUEST, token, requestInput(a.get(0), List.of(a.get(1)), 1), 422)))
            .contains(AiCodes.QUIZ_BUSY);
        request(token, b.get(0), List.of(b.get(1)), 1);
        assertThat(codes(refused(AiProcesses.REQUEST, token, requestInput(c.get(0), List.of(c.get(1)), 1), 422)))
            .contains(AiCodes.TOO_MANY_OPEN);
        runJobs();

        // The day's limit: two requests at once when one is left, only one passes (counted under the lock).
        int today = ((Number) query("SELECT count(*) AS n FROM qb_ai_job WHERE sponsor_id = ? AND requested_at >= ?",
            SPONSOR_A, java.sql.Timestamp.from(com.jabiz.quizbuks.content.ai.AiLimits.dayStart(clock.instant(),
                java.time.ZoneId.of("Asia/Tokyo")))).getFirst().get("n")).intValue();
        // (jabiz.fiscal-year-zone is Asia/Tokyo in application.yml, the tests' too.)
        param(QbParams.AI_SPONSOR_DAILY_JOBS, String.valueOf(today + 1));
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<Map<String, Object>>> calls = new ArrayList<>();
            for (List<String> quiz : List.of(b, c)) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    var result = call(AiProcesses.REQUEST, token, requestInput(quiz.get(0), List.of(quiz.get(1)), 1))
                        .expectBody(MAP).returnResult();
                    Map<String, Object> outcome = new java.util.HashMap<>();
                    outcome.put("status", result.getStatus().value());
                    outcome.put("body", result.getResponseBody());
                    return outcome;
                }));
            }
            go.countDown();
            List<Map<String, Object>> outcomes = calls.stream().map(CompletableFuture::join).toList();
            assertThat(outcomes).extracting(o -> (Integer) o.get("status")).containsExactlyInAnyOrder(200, 422);
            // The one refused is refused for the day's limit, nothing else.
            @SuppressWarnings("unchecked")
            Map<String, Object> problem = (Map<String, Object>) outcomes.stream()
                .filter(o -> Integer.valueOf(422).equals(o.get("status"))).findFirst().orElseThrow().get("body");
            assertThat(codes(problem)).containsExactly(AiCodes.DAILY_LIMIT);
            runJobs();
            assertThat(codes(refused(AiProcesses.REQUEST, token, requestInput(a.get(0), List.of(a.get(1)), 1),
                422))).contains(AiCodes.DAILY_LIMIT);
        } finally {
            param(QbParams.AI_SPONSOR_DAILY_JOBS, "20");
        }

        // The month's tokens: the estimate of a job does not fit what is left.
        advance(Duration.ofDays(1));
        String later = sponsorFull(SPONSOR_A);
        param(QbParams.AI_SPONSOR_MONTHLY_TOKENS, "1000");
        try {
            assertThat(codes(refused(AiProcesses.REQUEST, later, requestInput(a.get(0), List.of(a.get(1)), 1),
                422))).contains(AiCodes.MONTHLY_LIMIT);
        } finally {
            param(QbParams.AI_SPONSOR_MONTHLY_TOKENS, "2000000");
        }
        param(QbParams.AI_GLOBAL_MONTHLY_TOKENS, "1000");
        try {
            assertThat(codes(refused(AiProcesses.REQUEST, later, requestInput(a.get(0), List.of(a.get(1)), 1),
                422))).contains(AiCodes.GLOBAL_LIMIT);
        } finally {
            param(QbParams.AI_GLOBAL_MONTHLY_TOKENS, "50000000");
        }
    }

    @Test
    void busyAnswersAreTriedAgainAndRefusalsEndTheJob() {
        String token = sponsorFull(SPONSOR_A);
        // 429 and 529, then an answer: the third attempt succeeds.
        List<String> busy = quizWithArticle(token, title(), "Material.");
        CLAUDE.script(MESSAGES, StubHttpServer.Answer.json(429,
            "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"secret detail\"}}"));
        CLAUDE.script(MESSAGES, StubHttpServer.Answer.json(529,
            "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}"));
        Object retried = request(token, busy.get(0), List.of(busy.get(1)), 1).get("aiJobId");
        for (int i = 0; i < 4 && QbAi.QUEUED.equals(job(retried).get("status")); i++) {
            runJobs();
            advance(Duration.ofMinutes(3));
        }
        assertThat(job(retried)).containsEntry("status", QbAi.READY);
        assertThat(((Number) job(retried).get("attempts")).intValue()).isEqualTo(3);

        // A refusal, after the provider's own fallback: billed, explained, not tried again.
        token = sponsorFull(SPONSOR_A);
        List<String> refusedQuiz = quizWithArticle(token, title(), "Material.");
        CLAUDE.script(MESSAGES, answer("claude-opus-5", "refusal", null, 500, 0));
        Object refusal = request(token, refusedQuiz.get(0), List.of(refusedQuiz.get(1)), 1).get("aiJobId");
        int calls = CLAUDE.requests(MESSAGES).size();
        runJobs();
        assertThat(job(refusal)).containsEntry("status", QbAi.FAILED).containsEntry("failure_code",
            AiCodes.REFUSED);
        assertThat(((Number) job(refusal).get("input_tokens")).longValue()).isEqualTo(500);
        assertThat(CLAUDE.requests(MESSAGES)).hasSize(calls + 1);

        // A request the provider refuses as it is: failed at once, the provider's message not kept.
        List<String> bad = quizWithArticle(token, title(), "Material.");
        CLAUDE.script(MESSAGES, StubHttpServer.Answer.json(400,
            "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"secret detail\"}}"));
        Object invalid = request(token, bad.get(0), List.of(bad.get(1)), 1).get("aiJobId");
        runJobs();
        Map<String, Object> failed = job(invalid);
        assertThat(failed).containsEntry("status", QbAi.FAILED).containsEntry("failure_code",
            AiCodes.REQUEST_INVALID);
        assertThat(String.valueOf(failed.get("failure_message"))).doesNotContain("secret detail");
        assertThat(((Number) usage(SPONSOR_A).get("failed_jobs")).intValue()).isGreaterThanOrEqualTo(2);
        // Administrators see the failures and the model that answered.
        List<Map<String, Object>> rows = items("qb.admin.ai-jobs", aiAdmin(), body("params",
            Map.of("failedOnly", true)));
        assertThat(rows).anySatisfy(row -> assertThat(row).containsEntry("failureCode", AiCodes.REFUSED));
        assertThat(items("qb.admin.ai-usage", aiAdmin(), body())).isNotEmpty();
        assertThat(items("qb.admin.ai-settings", aiAdmin(), body())).anySatisfy(row -> assertThat(row)
            .containsEntry("paramKey", QbParams.AI_ENABLED).containsEntry("value", "true"));
    }

    @Test
    void onlySponsorsMayAskAndOnlyTheSystemFinishes() {
        String token = sponsorFull(SPONSOR_A);
        List<String> quiz = quizWithArticle(token, title(), "Material.");
        String taker = taker("ai-taker-" + UUID.randomUUID());
        Map<String, Object> input = requestInput(quiz.get(0), List.of(quiz.get(1)), 1);
        UUID someJob = UUID.randomUUID();
        for (String process : List.of(AiProcesses.REQUEST, AiProcesses.ACCEPT, AiProcesses.DISCARD,
            AiProcesses.CANCEL)) {
            Map<String, Object> body = AiProcesses.REQUEST.equals(process) ? input
                : AiProcesses.ACCEPT.equals(process) ? body("aiJobId", someJob, "quizId", quiz.get(0), "questions",
                List.of(accepted(1, "Q?", true))) : body("aiJobId", someJob);
            refused(process, taker, body, 403);
            refused(process, aiAdmin(), body, 403);
        }
        refusedAsInternal(AiProcesses.EXPIRE, token, body("aiJobId", someJob, "sponsorId", SPONSOR_A));
        refusedAsInternal(AiProcesses.COMPLETED, token, body("jobId", "x", "jobType", "qb.ai.questions"));
        refusedAsInternal(AiProcesses.FAILED, token, body("jobId", "x", "jobType", "qb.ai.questions"));
        // Another sponsor's quiz: not found.
        refused(AiProcesses.REQUEST, sponsorFull(SPONSOR_B), input, 404);
        // The templates: the sponsor's for sponsors, the administrators' for administrators.
        client.post().uri("/api/queries/qb.sponsor.ai-jobs").contentType(org.springframework.http.MediaType
                .APPLICATION_JSON).header("Authorization", taker)
            .bodyValue(body("params", Map.of("quizId", quiz.get(0)))).exchange().expectStatus().isForbidden();
        client.post().uri("/api/queries/qb.admin.ai-usage").contentType(org.springframework.http.MediaType
                .APPLICATION_JSON).header("Authorization", token).bodyValue(body()).exchange().expectStatus()
            .isForbidden();
        assertThat(sent()).isEmpty();
    }

    @Test
    void theSponsorsUsageCardReadsTheMonth() {
        String token = sponsorFull(SPONSOR_A);
        List<String> quiz = quizWithArticle(token, title(), "Material.");
        request(token, quiz.get(0), List.of(quiz.get(1)), 1);
        Map<String, Object> open = items("qb.sponsor.ai-usage", token, body("params",
            Map.of("at", clock.instant().toString()))).getFirst();
        assertThat(open).containsEntry("enabled", true);
        assertThat(((Number) open.get("reservedTokens")).longValue()).isPositive();
        assertThat(((Number) open.get("jobsToday")).intValue()).isEqualTo(1);
        runJobs();
        Map<String, Object> done = items("qb.sponsor.ai-usage", token, body("params",
            Map.of("at", clock.instant().toString()))).getFirst();
        assertThat(((Number) done.get("reservedTokens")).longValue()).isZero();
        assertThat(((Number) done.get("inputTokens")).longValue()).isPositive();
        assertThat(((Number) done.get("remainingTokens")).longValue()).isLessThan(2_000_000L);
    }
}

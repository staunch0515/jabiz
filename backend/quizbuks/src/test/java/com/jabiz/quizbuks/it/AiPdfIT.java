package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.ai.AiCodes;
import com.jabiz.quizbuks.content.ai.AiLimits;
import com.jabiz.quizbuks.content.ai.AiProcesses;
import com.jabiz.quizbuks.content.ai.AiQuestionsJob;
import com.jabiz.quizbuks.content.ai.ClaudeApi;
import com.jabiz.quizbuks.content.ai.QbAi;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.FileSamples;
import com.jabiz.runtime.test.StubHttpServer;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF materials sent to the AI (docs/quizbuks/plans/Q8-ai.md, Q8b, A-5; platform decision D50; review H1/H2): the
 * file itself as a document of the request, read by the job through its context and never parsed on the server; the
 * size checked by the request; before the paid call the provider's free count of exactly what is sent, which may not be
 * above what the request reserved (the page cap here is {@value #MAX_PAGES} pages at 5,000 tokens: whatever a file
 * claims of its pages, the count decides); a file the provider cannot read ends the job uncharged; a count that fails on
 * the way is tried again, uncharged.
 */
class AiPdfIT extends AiItSupport {

    static final int MAX_PAGES = AiItSupport.TEST_PDF_MAX_PAGES;
    static final long MAX_BYTES = AiItSupport.TEST_PDF_MAX_BYTES;

    @BeforeEach
    void freshDay() {
        aiOn();
        advance(Duration.ofDays(1));
    }

    /**
     * A PDF of {@code pages} page objects whose pages tree claims {@code claimed} (a file can say what it likes:
     * nothing here believes it).
     */
    static byte[] pdf(int pages, int claimed) {
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n");
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages; i++) {
            kids.append(3 + i).append(" 0 R ");
        }
        pdf.append("2 0 obj << /Type /Pages /Kids [").append(kids).append("] /Count ").append(claimed)
            .append(" >> endobj\n");
        for (int i = 0; i < pages; i++) {
            pdf.append(3 + i).append(" 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >> endobj\n");
        }
        pdf.append("trailer << /Root 1 0 R >>\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** The sponsor's PDF material of a new quiz with an article; returns {quizId, articleId, pdfMaterialId, fileId}. */
    private List<String> quizWithPdf(String token, byte[] pdf) {
        List<String> quiz = quizWithArticle(token, "PDF quiz " + UUID.randomUUID(), "Paris is in France.");
        String fileId = (String) upload(QbContent.PDF_POLICY, pdf, "material.pdf", "application/pdf", token)
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("fileId");
        String material = (String) run("QB_MATERIAL_SAVE", token, body("quizId", quiz.get(0), "kind", "PDF",
            "title", "The paper", "description", "A paper about rivers", "pdf", fileId)).get("itemId");
        return List.of(quiz.get(0), quiz.get(1), material, fileId);
    }

    private void runUntilDone(Object aiJobId) {
        for (int i = 0; i < 6 && job(aiJobId).get("finished_at") == null; i++) {
            runJobs();
            advance(Duration.ofMinutes(3));
        }
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    @Test
    void aPdfMaterialIsCountedThenSentAsTheFileItself() {
        String token = sponsorFull(SPONSOR_A);
        byte[] pdf = pdf(2, 2);
        List<String> quiz = quizWithPdf(token, pdf);
        Map<String, Object> requested = request(token, quiz.get(0), List.of(quiz.get(1), quiz.get(2)), 2);
        Object aiJobId = requested.get("aiJobId");
        Map<String, Object> job = job(aiJobId);
        // The pages are not known: the most a request may send is reserved, every attempt.
        long bytes = number(job.get("input_bytes"));
        assertThat(number(job.get("estimated_tokens"))).isEqualTo(AiLimits.estimate(bytes, ClaudeApi.maxTokens(2),
            MAX_PAGES));
        assertThat(String.valueOf(job.get("params"))).contains("\"pdfMaterialNos\":[2]",
            "\"reservedInputTokens\":" + AiLimits.reservedInput(bytes, MAX_PAGES));
        // The job reads the file (the platform checked the sponsor may), not its content in the input.
        assertThat(query("SELECT file_id FROM sys_external_job_file WHERE job_id = ? AND direction = 'INPUT'",
            String.valueOf(job.get("external_job_id")))).singleElement()
            .satisfies(row -> assertThat(row.get("file_id")).isEqualTo(quiz.get(3)));
        runJobs();

        assertThat(job(aiJobId)).containsEntry("status", QbAi.READY);
        // Counted first, with exactly what is then sent but the output limit, the user id and the fallback.
        JsonNode count = JSON.readTree(CLAUDE.requests(COUNT_TOKENS).getLast().body());
        JsonNode body = sent().getLast();
        assertThat(count.has("max_tokens")).isFalse();
        assertThat(count.has("metadata")).isFalse();
        assertThat(count.has("fallbacks")).isFalse();
        assertThat(count.get("messages")).isEqualTo(body.get("messages"));
        assertThat(count.get("system")).isEqualTo(body.get("system"));
        assertThat(count.get("output_config")).isEqualTo(body.get("output_config"));
        assertThat(CLAUDE.requests(COUNT_TOKENS).getLast().header("x-api-key")).isEqualTo(
            "test-only-quizbuks-claude-key");
        List<JsonNode> documents = documents(body);
        assertThat(documents).singleElement().satisfies(document -> {
            assertThat(document.get("title").asString()).isEqualTo("Material 2");
            assertThat(document.get("source").get("media_type").asString()).isEqualTo("application/pdf");
            assertThat(Base64.getDecoder().decode(document.get("source").get("data").asString())).isEqualTo(pdf);
        });
        // The documents come first; the request's text names the material's document.
        JsonNode content = body.get("messages").get(0).get("content");
        assertThat(content.get(0).get("type").asString()).isEqualTo("document");
        assertThat(content.get(content.size() - 1).get("type").asString()).isEqualTo("text");
        assertThat(userText(body)).contains("\"document\":\"Material 2\"", "Paris is in France.");

        // A request without PDFs is not counted (its text's estimate is a bound).
        int counts = CLAUDE.requests(COUNT_TOKENS).size();
        request(token, quiz.get(0), List.of(quiz.get(1)), 1);
        runJobs();
        assertThat(CLAUDE.requests(COUNT_TOKENS)).hasSize(counts);
    }

    @Test
    void theProvidersCountDecidesWhateverTheFileClaims() {
        String token = sponsorFull(SPONSOR_A);
        // 50 pages that claim to be 1: the count (2,000 a page at the stub) is far above the 5 pages reserved.
        List<String> faked = quizWithPdf(token, pdf(50, 1));
        int calls = CLAUDE.requests(MESSAGES).size();
        Object tooLong = request(token, faked.get(0), List.of(faked.get(2)), 1).get("aiJobId");
        runJobs();
        assertThat(job(tooLong)).containsEntry("status", QbAi.FAILED).containsEntry("failure_code",
            AiCodes.PDF_TOO_LONG);
        // Nothing sent, nothing charged, not tried again.
        assertThat(number(job(tooLong).get("input_tokens"))).isZero();
        assertThat(number(job(tooLong).get("output_tokens"))).isZero();
        assertThat(number(job(tooLong).get("attempts"))).isEqualTo(1);
        assertThat(CLAUDE.requests(MESSAGES)).hasSize(calls);

        // The count, not the file, sets what an unanswered attempt is charged: the provider never answers in time.
        List<String> slow = quizWithPdf(token, pdf(3, 3));
        for (int i = 0; i < AiQuestionsJob.ATTEMPTS; i++) {
            CLAUDE.script(MESSAGES, answer("claude-opus-5-5", "end_turn", questions(1), 100, 100)
                .after(Duration.ofSeconds(5)));
        }
        Object unanswered = request(sponsorFull(SPONSOR_A), slow.get(0), List.of(slow.get(2)), 1).get("aiJobId");
        runUntilDone(unanswered);
        long counted = countOf(JSON.readTree(CLAUDE.requests(COUNT_TOKENS).getLast().body()));
        Map<String, Object> failed = job(unanswered);
        assertThat(failed).containsEntry("status", QbAi.FAILED);
        assertThat(number(failed.get("input_tokens"))).isEqualTo(AiQuestionsJob.ATTEMPTS * counted);
        assertThat(number(failed.get("output_tokens"))).isEqualTo(AiQuestionsJob.ATTEMPTS * ClaudeApi.maxTokens(1));
        // Within the reservation, as every count is.
        assertThat(number(failed.get("input_tokens")) + number(failed.get("output_tokens")))
            .isLessThanOrEqualTo(number(failed.get("estimated_tokens")));
    }

    @Test
    void aPdfTheProviderCannotReadEndsTheJobUncharged() {
        String token = sponsorFull(SPONSOR_A);
        List<String> broken = quizWithPdf(token, FileSamples.pdf());
        CLAUDE.script(COUNT_TOKENS, StubHttpServer.Answer.json(400, "{\"type\":\"error\",\"error\":{\"type\":"
            + "\"invalid_request_error\",\"message\":\"The PDF specified was not valid.\"}}"));
        int calls = CLAUDE.requests(MESSAGES).size();
        Object unreadable = request(token, broken.get(0), List.of(broken.get(2)), 1).get("aiJobId");
        runJobs();
        Map<String, Object> failed = job(unreadable);
        assertThat(failed).containsEntry("status", QbAi.FAILED).containsEntry("failure_code",
            AiCodes.PDF_UNREADABLE);
        // The provider's words are not kept.
        assertThat(String.valueOf(failed.get("failure_message"))).doesNotContain("specified");
        assertThat(number(failed.get("input_tokens"))).isZero();
        assertThat(CLAUDE.requests(MESSAGES)).hasSize(calls);

        // The same refusal from the paid call (a count that passed): unreadable, and a 400 is not billed.
        List<String> encrypted = quizWithPdf(token, pdf(1, 1));
        CLAUDE.script(MESSAGES, StubHttpServer.Answer.json(400, "{\"type\":\"error\",\"error\":{\"type\":"
            + "\"invalid_request_error\",\"message\":\"messages.0.content.0.pdf: the document is encrypted\"}}"));
        Object locked = request(token, encrypted.get(0), List.of(encrypted.get(2)), 1).get("aiJobId");
        runJobs();
        assertThat(job(locked)).containsEntry("status", QbAi.FAILED).containsEntry("failure_code",
            AiCodes.PDF_UNREADABLE);
        assertThat(number(job(locked).get("input_tokens"))).isZero();
        // Another 400 of a request with PDFs stays what it was.
        List<String> other = quizWithPdf(token, pdf(1, 1));
        CLAUDE.script(MESSAGES, StubHttpServer.Answer.json(400, "{\"type\":\"error\",\"error\":{\"type\":"
            + "\"invalid_request_error\",\"message\":\"max_tokens: too large\"}}"));
        Object invalid = request(token, other.get(0), List.of(other.get(2)), 1).get("aiJobId");
        runJobs();
        assertThat(job(invalid)).containsEntry("failure_code", AiCodes.REQUEST_INVALID);
    }

    @Test
    void aCountThatFailsOnTheWayIsTriedAgainUncharged() {
        String token = sponsorFull(SPONSOR_A);
        List<String> quiz = quizWithPdf(token, pdf(2, 2));
        // Busy, then no answer in time, then the count: the third attempt sends the request.
        CLAUDE.script(COUNT_TOKENS, StubHttpServer.Answer.json(529,
            "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}"));
        CLAUDE.script(COUNT_TOKENS, StubHttpServer.Answer.json(200, "{\"input_tokens\":10}")
            .after(Duration.ofSeconds(5)));
        int calls = CLAUDE.requests(MESSAGES).size();
        Object aiJobId = request(token, quiz.get(0), List.of(quiz.get(2)), 1).get("aiJobId");
        runUntilDone(aiJobId);
        Map<String, Object> done = job(aiJobId);
        assertThat(done).containsEntry("status", QbAi.READY);
        assertThat(number(done.get("attempts"))).isEqualTo(3);
        // Only the one paid call is charged: the stub's usage of it.
        assertThat(number(done.get("input_tokens"))).isEqualTo(1200);
        assertThat(number(done.get("output_tokens"))).isEqualTo(800);
        assertThat(CLAUDE.requests(MESSAGES)).hasSize(calls + 1);
    }

    @Test
    void tooLargePdfsAreRefusedAtTheRequest() {
        String token = sponsorFull(SPONSOR_A);
        // Larger than a request may send: refused at once, no job.
        byte[] large = new byte[(int) MAX_BYTES + 1024];
        byte[] head = FileSamples.pdf();
        System.arraycopy(head, 0, large, 0, head.length);
        List<String> big = quizWithPdf(token, large);
        long jobs = count("sys_external_job");
        assertThat(codes(refused(AiProcesses.REQUEST, token, requestInput(big.get(0), List.of(big.get(2)), 1), 422)))
            .contains(AiCodes.PDF_TOO_LARGE);
        assertThat(count("sys_external_job")).isEqualTo(jobs);
        // Without the PDF it is fine.
        request(token, big.get(0), List.of(big.get(1)), 1);
        runJobs();
    }

    @Test
    void anOperatorsStopOfAPdfJobIsChargedItsReservedDocuments() {
        String sponsor = newSponsor("ai-pdf-stop-" + UUID.randomUUID());
        String token = sponsorFull(sponsor);
        List<String> quiz = quizWithPdf(token, pdf(1, 1));
        Map<String, Object> queued = request(token, quiz.get(0), List.of(quiz.get(2)), 1);
        Map<String, Object> asked = job(queued.get("aiJobId"));
        long reserved = AiLimits.reservedInput(number(asked.get("input_bytes")), MAX_PAGES);
        String operator = TestTokens.bearer(tokens, "ai-operator", "external-job.cancel");
        client.post().uri("/api/external-jobs/" + queued.get("externalJobId") + "/cancel")
            .header("Authorization", operator).contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isOk();
        runJobs();
        Map<String, Object> stopped = job(queued.get("aiJobId"));
        assertThat(stopped).containsEntry("failure_code", AiCodes.STOPPED);
        // The input as reserved, documents included, every attempt; the rest is the output asked for.
        assertThat(number(stopped.get("input_tokens"))).isEqualTo(AiQuestionsJob.ATTEMPTS * reserved);
        assertThat(number(stopped.get("output_tokens"))).isEqualTo(AiQuestionsJob.ATTEMPTS
            * (long) ClaudeApi.maxTokens(1));
        // Priced as input at the input price, not at the output's.
        java.math.BigDecimal expected = java.math.BigDecimal.valueOf(4L * AiQuestionsJob.ATTEMPTS * reserved
            + 20L * AiQuestionsJob.ATTEMPTS * ClaudeApi.maxTokens(1)).divide(java.math.BigDecimal.valueOf(1_000_000));
        assertThat((java.math.BigDecimal) stopped.get("cost_usd")).isEqualByComparingTo(expected);
    }

    @Test
    void coversAndQuestionsAtOnceShareTheSponsorsLimits() throws Exception {
        String sponsor = newSponsor("ai-both-" + UUID.randomUUID());
        String token = sponsorFull(sponsor);
        List<String> forQuestions = quizWithArticle(token, "Questions " + UUID.randomUUID(), "A.");
        List<String> forCovers = quizWithArticle(token, "Covers " + UUID.randomUUID(), "B.");
        // One request left today for this sponsor: a cover request and a questions request at once, one passes.
        param(QbParams.AI_SPONSOR_DAILY_JOBS, "1");
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<Integer>> calls = new ArrayList<>();
            List<Map.Entry<String, Map<String, Object>>> asks = List.of(
                Map.entry(AiProcesses.REQUEST, requestInput(forQuestions.get(0), List.of(forQuestions.get(1)), 1)),
                Map.entry(AiProcesses.COVER_REQUEST, body("quizId", forCovers.get(0), "language", "en",
                    "aiNoticeVersionNo", noticeVersion())));
            for (Map.Entry<String, Map<String, Object>> ask : asks) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return call(ask.getKey(), token, ask.getValue()).expectBody(MAP).returnResult().getStatus().value();
                }));
            }
            go.countDown();
            assertThat(calls.stream().map(CompletableFuture::join).toList()).containsExactlyInAnyOrder(200, 422);
            assertThat(((Number) query("SELECT count(*) AS n FROM qb_ai_job WHERE sponsor_id = ?", sponsor)
                .getFirst().get("n")).intValue()).isEqualTo(1);
        } finally {
            param(QbParams.AI_SPONSOR_DAILY_JOBS, "20");
        }
        runJobs();
    }
}

package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ai.AiProcesses;
import com.jabiz.quizbuks.messaging.QbLegal;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.external.ExternalJobExecutor;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.StubHttpServer;
import com.jabiz.runtime.test.TestTokens;
import com.jabiz.job.JobDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI generation tests (docs/quizbuks/plans/Q8-ai.md, Q8a): real PostgreSQL, no network. Anthropic's Messages API is a
 * {@link StubHttpServer} on this machine that, unless a test scripts an answer, writes as many valid questions as the
 * request asks for (so the tests see what QuizBuks sent: every request is recorded with its headers and body). The
 * jobs are run by {@link #runJobs()}; the AI notice is published once per class and the switch turned on
 * ({@link #aiOn()}).
 */
abstract class AiItSupport extends LegalItSupport {

    static final String MESSAGES = "/v1/messages";
    /** The provider's free token count (Q8b review H1/H2: requests with PDFs are counted before they are sent). */
    static final String COUNT_TOKENS = "/v1/messages/count_tokens";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Pattern COUNT = Pattern.compile("Number of questions: (\\d+)");
    /** PDF materials in these tests (Q8b): at most this many pages a request, and bytes each. */
    static final int TEST_PDF_MAX_PAGES = 5;
    static final long TEST_PDF_MAX_BYTES = 200_000;

    /** One stub for every AI class: they share a cached application context, and so its address. */
    static final StubHttpServer CLAUDE = StubHttpServer.start(AiItSupport::claude);

    @DynamicPropertySource
    static void claudeApi(DynamicPropertyRegistry registry) {
        registry.add("jabiz.external-services.anthropic.base-url", () -> CLAUDE.url(""));
        registry.add("jabiz.external-services.anthropic.allowed-hosts", () -> "127.0.0.1");
        // The tests make many requests a minute; the limit itself is the platform's (and configuration).
        registry.add("jabiz.process.rate-limits[QB_AI_QUESTIONS_REQUEST].per-user", () -> "10000");
        registry.add("jabiz.process.rate-limits[QB_AI_COVER_REQUEST].per-user", () -> "10000");
        // A provider that does not answer is found out in two seconds, not the two minutes of production.
        registry.add("quizbuks.ai.request-timeout", () -> "PT2S");
        registry.add("quizbuks.ai.pdf.max-pages", () -> String.valueOf(TEST_PDF_MAX_PAGES));
        registry.add("quizbuks.ai.pdf.max-bytes", () -> String.valueOf(TEST_PDF_MAX_BYTES));
    }

    @Autowired
    ExternalJobExecutor jobExecutor;

    @Autowired
    JobDefinition<AiProcesses.ExpireInput> qbAiExpireJob;

    @Autowired
    JobRunner jobRunner;

    @BeforeEach
    void resetClaude() {
        CLAUDE.reset();
    }

    /**
     * The stub's own answer: {@code n} questions (as the request's "Number of questions: n" says), each resting on
     * material 1, or (Q8b) three cover designs when the request asks for covers, with the provider's usage, as a
     * normal end of turn.
     */
    static StubHttpServer.Answer claude(StubHttpServer.Received request) {
        if ("POST".equals(request.method()) && COUNT_TOKENS.equals(request.path())) {
            return StubHttpServer.Answer.json(200, "{\"input_tokens\":" + countOf(JSON.readTree(request.body())) + "}");
        }
        if (!"POST".equals(request.method()) || !MESSAGES.equals(request.path())) {
            return StubHttpServer.Answer.json(404, "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\"}}");
        }
        JsonNode body = JSON.readTree(request.body());
        String user = userText(body);
        if (user.contains("Number of covers:")) {
            return answer(body.get("model").asString(), "end_turn", covers("Cover"), 300, 200);
        }
        Matcher m = COUNT.matcher(user);
        int count = m.find() ? Integer.parseInt(m.group(1)) : 1;
        // A request with documents is billed what it was counted (Q8b re-review L-f), as the provider's would be.
        long in = documents(body).isEmpty() ? 1200 : countOf(body);
        return answer(body.get("model").asString(), "end_turn", questions(count), in, 800);
    }

    /** The text block of a request's user message (after its documents, when it has any). */
    static String userText(JsonNode body) {
        String text = "";
        for (JsonNode block : body.get("messages").get(0).get("content")) {
            if ("text".equals(block.get("type").asString())) {
                text = block.get("text").asString();
            }
        }
        return text;
    }

    /**
     * The stub's own token count (Q8b): a token per four characters of the text, and for each document 2,000 tokens a
     * page object it really has ({@code /Type /Page}, whatever its {@code /Pages /Count} claims) and at least one.
     */
    static long countOf(JsonNode body) {
        long tokens = userText(body).length() / 4 + 1_000;
        for (JsonNode document : documents(body)) {
            String pdf = new String(java.util.Base64.getDecoder().decode(document.get("source").get("data").asString()),
                java.nio.charset.StandardCharsets.ISO_8859_1);
            long pages = Pattern.compile("/Type\\s*/Page(?!s)").matcher(pdf).results().count();
            tokens += 2_000 * Math.max(1, pages);
        }
        return tokens;
    }

    /** The document blocks of a request's user message (Q8b: PDF materials). */
    static List<JsonNode> documents(JsonNode body) {
        List<JsonNode> documents = new ArrayList<>();
        for (JsonNode block : body.get("messages").get(0).get("content")) {
            if ("document".equals(block.get("type").asString())) {
                documents.add(block);
            }
        }
        return documents;
    }

    /** Three cover designs as the answer's text, their texts {@code <prefix> 1..3}. */
    static String covers(String prefix) {
        return JSON.writeValueAsString(Map.of("covers", List.of(
            Map.of("palette", "ocean", "layout", "center", "motif", "circles", "text", prefix + " 1"),
            Map.of("palette", "sunset", "layout", "band", "motif", "waves", "text", prefix + " 2"),
            Map.of("palette", "citrus", "layout", "corner", "motif", "rays", "text", prefix + " 3"))));
    }

    /** {@code n} questions as the answer's text. */
    static String questions(int count) {
        List<Map<String, Object>> questions = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            questions.add(Map.of("stem", "Generated question " + i + "?", "points", 10, "materials", List.of(1),
                "options", List.of(Map.of("text", "Right " + i, "correct", true),
                    Map.of("text", "Wrong " + i, "correct", false))));
        }
        return JSON.writeValueAsString(Map.of("questions", questions));
    }

    /** A 200 answer of the Messages API. */
    static StubHttpServer.Answer answer(String model, String stopReason, String text, long in, long out) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", "msg_stub");
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", model);
        List<Object> content = new ArrayList<>();
        content.add(Map.of("type", "thinking", "thinking", "", "signature", "stub"));
        if (text != null) {
            content.add(Map.of("type", "text", "text", text));
        }
        message.put("content", content);
        message.put("stop_reason", stopReason);
        message.put("usage", Map.of("input_tokens", in, "output_tokens", out));
        return StubHttpServer.Answer.json(200, JSON.writeValueAsString(message));
    }

    /** The bodies Claude received, oldest first. */
    static List<JsonNode> sent() {
        return CLAUDE.requests(MESSAGES).stream().map(r -> JSON.readTree(r.body())).toList();
    }

    /** Runs the due jobs and delivers the events their results cause, until nothing is left. */
    void runJobs() {
        for (int round = 0; round < 10; round++) {
            int ran = jobExecutor.runPending().block();
            deliverEvents();
            if (ran == 0) {
                return;
            }
        }
    }

    /** Sets a controlled parameter, with four eyes. */
    void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "Test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    /** Publishes the AI notice (once per class) and turns AI on. */
    void aiOn() {
        if (query("SELECT 1 FROM qb_legal_version_version WHERE kind = ?", QbLegal.AI_NOTICE).isEmpty()) {
            publishNew(QbLegal.AI_NOTICE, "ai", null, true);
        }
        if (query("SELECT 1 FROM sys_param_version WHERE param_key = ? AND param_value = 'true'",
            QbParams.AI_ENABLED).isEmpty()) {
            param(QbParams.AI_ENABLED, "true");
        }
    }

    /** The version of the AI notice in effect. */
    static int noticeVersion() {
        return ((Number) query("SELECT max(legal_version_no) AS n FROM qb_legal_version_version WHERE kind = ?",
            QbLegal.AI_NOTICE).getFirst().get("n")).intValue();
    }

    /** A sponsor's full token (QB_SPONSOR's permissions). */
    String sponsorFull(String sponsorId) {
        return sponsorToken(sponsorId);
    }

    /** A quiz of the sponsor's with an introduction and one article; returns {quizId, materialId}. */
    List<String> quizWithArticle(String token, String title, String article) {
        String quizId = newQuiz(token, title);
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", title, "intro", "About " + title));
        String materialId = (String) run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "ARTICLE",
            "title", "Article of " + title, "body", article)).get("itemId");
        return List.of(quizId, materialId);
    }

    Map<String, Object> requestInput(String quizId, List<String> materialIds, int count) {
        Map<String, Object> input = body("quizId", quizId, "materialIds", materialIds, "count", count,
            "difficulty", "MEDIUM", "language", "en", "aiNoticeVersionNo", noticeVersion());
        return input;
    }

    Map<String, Object> request(String token, String quizId, List<String> materialIds, int count) {
        return run(AiProcesses.REQUEST, token, requestInput(quizId, materialIds, count));
    }

    /** Asks for covers of the quiz, in English, the notice accepted (Q8b). */
    Map<String, Object> requestCovers(String token, String quizId) {
        return run(AiProcesses.COVER_REQUEST, token, body("quizId", quizId, "language", "en", "aiNoticeVersionNo",
            noticeVersion()));
    }

    static Map<String, Object> job(Object aiJobId) {
        return query("SELECT * FROM qb_ai_job WHERE ai_job_id = ?", String.valueOf(aiJobId)).getFirst();
    }

    static Map<String, Object> usage(String sponsorId) {
        List<Map<String, Object>> rows = query("SELECT * FROM qb_ai_usage WHERE sponsor_id = ? ORDER BY month DESC",
            sponsorId);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    void advance(Duration duration) {
        clock.set(clock.instant().plus(duration));
    }

    /** Runs the daily expiry at the clock's time. */
    JobRunner.JobRun runExpiry() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        JobRunner.JobRun run = jobRunner.execute(qbAiExpireJob, clock.instant());
        deliverEvents();
        return run;
    }

    /** An administrator of the AI page. */
    String aiAdmin() {
        return TestTokens.bearer(tokens, "ai-admin", com.jabiz.quizbuks.QbPermissions.ADMIN_AI_READ);
    }
}

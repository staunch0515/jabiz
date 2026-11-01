package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentLanguages;
import com.jabiz.quizbuks.content.ai.AiCodes;
import com.jabiz.quizbuks.content.ai.AiProcesses;
import com.jabiz.quizbuks.content.ai.QbAi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI translation end to end against the stub (Q73d-1, docs/quizbuks/plans/Q73-content-languages.md §5, D-Q73-9): the
 * request sends the texts only (never which options are correct), the job keeps candidates for review and writes
 * nothing, only the reviewed ones are written (as the AI's, with the job), the job stays ready until every candidate
 * is accepted, a base changed since the request leaves its translation out of date, and the usage is the sponsor's.
 */
class AiTranslateIT extends AiItSupport {

    @BeforeEach
    void freshDay() {
        aiOn();
        advance(Duration.ofDays(1));
    }

    /** A quiz in English with Chinese as its other language: {quizId, question 1, its options' ids...}. */
    @SuppressWarnings("unchecked")
    private List<String> bilingualQuiz(String token) {
        String quizId = (String) run("QB_QUIZ_SAVE", token, body("title", "Capitals", "language", "en",
            "intro", "Two capitals.", "timeLimitSec", 300)).get("quizId");
        Map<String, Object> q1 = run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?",
            "points", 2, "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        run("QB_QUIZ_LANGUAGES_SET", token, body("quizId", quizId, "languages", List.of("zh")));
        List<String> options = (List<String>) q1.get("partIds");
        return List.of(quizId, String.valueOf(q1.get("itemId")), options.get(0), options.get(1));
    }

    private Map<String, Object> translate(String token, String quizId, Object... more) {
        Map<String, Object> input = body("quizId", quizId, "language", "zh", "aiNoticeVersionNo", noticeVersion());
        input.putAll(body(more));
        return run(AiProcesses.TRANSLATE_REQUEST, token, input);
    }

    private static Map<String, Object> translation(String quizId, String key) {
        List<Map<String, Object>> rows = latest("qb_content_translation_version", "translation_id",
            "quiz_id = '" + quizId + "' AND language = 'zh' AND element_key = '" + key + "'");
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> candidates(Object aiJobId) {
        Object stored = job(aiJobId).get("candidates");
        return stored == null ? List.of()
            : (List<Map<String, Object>>) JSON.readValue(stored.toString(), Map.class).get("translations");
    }

    @Test
    void theTextsAreTranslatedAsCandidatesAndOnlyTheReviewedOnesAreWritten() {
        String sponsor = newSponsor(unique("s-q73d"));
        String token = sponsorFull(sponsor);
        List<String> quiz = bilingualQuiz(token);
        String quizId = quiz.get(0);

        Map<String, Object> requested = translate(token, quizId);
        Object aiJobId = requested.get("aiJobId");
        assertThat(job(aiJobId)).containsEntry("kind", QbAi.KIND_TRANSLATION).containsEntry("status", QbAi.QUEUED);
        // One job of a kind a quiz at a time.
        assertThat(codes(refused(AiProcesses.TRANSLATE_REQUEST, token, body("quizId", quizId, "language", "zh"), 422)))
            .contains(AiCodes.QUIZ_BUSY);
        runJobs();

        // The provider got the texts, and nothing of the answers or of the platform's keys.
        JsonNode sent = sent().getLast();
        String user = userText(sent);
        assertThat(user).contains("Target language: Simplified Chinese", "Capital of France?", "Paris", "Lyon")
            .doesNotContain("correct", "points", quiz.get(2), ContentLanguages.TITLE);
        assertThat(sent.get("output_config") == null ? "" : sent.get("output_config").toString()).doesNotContain("high");

        Map<String, Object> job = job(aiJobId);
        assertThat(job).containsEntry("status", QbAi.READY);
        assertThat(((Number) job.get("candidate_count")).intValue()).isEqualTo(5);
        // Nothing is written by the job itself.
        assertThat(translation(quizId, ContentLanguages.TITLE)).isNull();
        assertThat(((Number) usage(sponsor).get("jobs")).intValue()).isEqualTo(1);

        List<Map<String, Object>> candidates = candidates(aiJobId);
        assertThat(candidates).extracting(c -> c.get("text")).containsExactly("译:Capitals", "译:Two capitals.",
            "译:Capital of France?", "译:Paris", "译:Lyon");
        // Not reviewed: refused, nothing written.
        refused(AiProcesses.TRANSLATION_ACCEPT, token, body("aiJobId", aiJobId, "quizId", quizId, "translations",
            List.of(body("candidateNo", 1, "text", "首都", "reviewed", false))), 400);
        // A candidate the job does not have.
        assertThat(codes(refused(AiProcesses.TRANSLATION_ACCEPT, token, body("aiJobId", aiJobId, "quizId", quizId,
            "translations", List.of(body("candidateNo", 9, "text", "x", "reviewed", true))), 422)))
            .contains(AiCodes.CANDIDATE_UNKNOWN);
        // Another sponsor: not found.
        String other = sponsorFull(newSponsor(unique("s-q73d-other")));
        refused(AiProcesses.TRANSLATION_ACCEPT, other, body("aiJobId", aiJobId, "quizId", quizId, "translations",
            List.of(body("candidateNo", 1, "text", "首都", "reviewed", true))), 404);

        // The title as the sponsor edited it, and the first option as given.
        run(AiProcesses.TRANSLATION_ACCEPT, token, body("aiJobId", aiJobId, "quizId", quizId, "translations",
            List.of(body("candidateNo", 1, "text", "世界的首都", "reviewed", true),
                body("candidateNo", 4, "text", "译:Paris", "reviewed", true))));
        assertThat(translation(quizId, ContentLanguages.TITLE)).containsEntry("text", "世界的首都")
            .containsEntry("translated_by", ContentLanguages.BY_AI).containsEntry("ai_job_id", String.valueOf(aiJobId))
            .containsEntry("source_hash", ContentLanguages.sourceHash("Capitals"));
        // The rest wait; numbered as before.
        assertThat(job(aiJobId)).containsEntry("status", QbAi.READY);
        assertThat(candidates(aiJobId)).extracting(c -> ((Number) c.get("no")).intValue()).containsExactly(2, 3, 5);

        // The base question changes before its translation is accepted: written, but out of date.
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "questionId", quiz.get(1), "stem",
            "What is the capital of France?", "points", 2, "options", List.of(
                body("optionId", quiz.get(2), "text", "Paris", "correct", true),
                body("optionId", quiz.get(3), "text", "Lyon"))));
        run(AiProcesses.TRANSLATION_ACCEPT, token, body("aiJobId", aiJobId, "quizId", quizId, "translations",
            List.of(body("candidateNo", 2, "text", "两个首都。", "reviewed", true),
                body("candidateNo", 3, "text", "法国的首都？", "reviewed", true),
                body("candidateNo", 5, "text", "里昂", "reviewed", true))));
        assertThat(translation(quizId, ContentLanguages.stem(quiz.get(1))).get("source_hash"))
            .isEqualTo(ContentLanguages.sourceHash("Capital of France?"))
            .isNotEqualTo(ContentLanguages.sourceHash("What is the capital of France?"));
        assertThat(job(aiJobId)).containsEntry("status", QbAi.ACCEPTED);
        assertThat(job(aiJobId).get("candidates")).isNull();
        assertThat(((Number) job(aiJobId).get("accepted_count")).intValue()).isEqualTo(5);

        // Next time only the out-of-date question is sent.
        Object second = translate(token, quizId).get("aiJobId");
        runJobs();
        assertThat(candidates(second)).extracting(c -> c.get("text")).containsExactly(
            "译:What is the capital of France?");
        // Unless every text is asked for.
        run(AiProcesses.DISCARD, token, body("aiJobId", second));
        Object third = translate(token, quizId, "all", true).get("aiJobId");
        runJobs();
        assertThat(candidates(third)).hasSize(5);
    }

    @Test
    void aLanguageTheQuizLacksOrNothingLeftToTranslateIsRefused() {
        String token = sponsorFull(newSponsor(unique("s-q73d-none")));
        String quizId = bilingualQuiz(token).get(0);
        assertThat(codes(refused(AiProcesses.TRANSLATE_REQUEST, token, body("quizId", quizId, "language", "ja",
            "aiNoticeVersionNo", noticeVersion()), 422))).contains("QB_TRANSLATION_LANGUAGE");
        Object aiJobId = translate(token, quizId).get("aiJobId");
        runJobs();
        run(AiProcesses.TRANSLATION_ACCEPT, token, body("aiJobId", aiJobId, "quizId", quizId, "translations",
            candidates(aiJobId).stream().map(c -> body("candidateNo", c.get("no"), "text", c.get("text"),
                "reviewed", true)).toList()));
        assertThat(codes(refused(AiProcesses.TRANSLATE_REQUEST, token, body("quizId", quizId, "language", "zh",
            "aiNoticeVersionNo", noticeVersion()), 422))).contains(AiCodes.NOTHING_TO_TRANSLATE);
    }
}

package com.jabiz.quizbuks.content.ai;

import com.jabiz.runtime.EntityInstance;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a finished AI job is charged for the attempts that recorded nothing (platform decision D52 item 5, W-6):
 * {@link AiProcesses#charged} adds, for each, the input the job reserved for an attempt and all the output it asked
 * for, from the job's stored parameters; and the reservation's framing still covers the fixed prompts.
 */
class AiChargingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A job as the request stores it: its kind, model, input bytes and parameters (JSON text). */
    private static EntityInstance job(String kind, String model, long inputBytes, Map<String, Object> params) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", kind);
        values.put("model", model);
        values.put("inputBytes", BigDecimal.valueOf(inputBytes));
        values.put("params", JSON.writeValueAsString(params));
        return new EntityInstance("job", QbAi.JOB, 1, null, values);
    }

    private static Map<String, Object> params(int count, Long reservedInputTokens) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("count", count);
        if (reservedInputTokens != null) {
            params.put("reservedInputTokens", reservedInputTokens);
        }
        return params;
    }

    @Test
    void noUnrecordedAttemptIsChargedWhatWasRecordedOnly() {
        EntityInstance job = job(QbAi.KIND_QUESTIONS, ClaudeService.OPUS, 3_000,
            params(2, AiLimits.reservedInput(3_000, 0)));
        Map<String, Long> recorded = Map.of(AiQuestionsJob.INPUT_TOKENS, 1_200L, AiQuestionsJob.OUTPUT_TOKENS, 800L);
        assertThat(AiProcesses.charged(recorded, 0, job)).isEqualTo(AiProcesses.usage(recorded));
    }

    @Test
    void aCoverAttemptIsChargedItsReservationAndTheCoversOutput() {
        long reserved = AiLimits.reservedInput(900, 0);
        EntityInstance job = job(QbAi.KIND_COVER, ClaudeService.OPUS, 900, params(CoverDesigns.COVERS, reserved));
        ClaudeApi.Usage each = AiProcesses.unrecordedAttempt(job);
        assertThat(each).isEqualTo(new ClaudeApi.Usage(reserved, AiCoverJob.MAX_TOKENS));
        assertThat(each.outputTokens()).isEqualTo(8_000);
        // On top of what the other attempts recorded.
        ClaudeApi.Usage charged = AiProcesses.charged(Map.of(AiQuestionsJob.INPUT_TOKENS, 300L,
            AiQuestionsJob.OUTPUT_TOKENS, 200L), 1, job);
        assertThat(charged).isEqualTo(new ClaudeApi.Usage(300 + reserved, 200 + 8_000));
    }

    @Test
    void aPdfJobsAttemptIsChargedItsPageCap() {
        int pages = 100;
        long reserved = AiLimits.reservedInput(4_000, pages);
        assertThat(reserved).isEqualTo(AiLimits.inputEstimate(4_000) + pages * (long) ClaudeApi.TOKENS_PER_PDF_PAGE);
        EntityInstance job = job(QbAi.KIND_QUESTIONS, ClaudeService.OPUS, 4_000, params(3, reserved));
        assertThat(AiProcesses.unrecordedAttempt(job)).isEqualTo(new ClaudeApi.Usage(reserved,
            ClaudeApi.maxTokens(3)));
    }

    @Test
    void aJobWithoutAReservationIsChargedTheTextsEstimateOfItsInput() {
        EntityInstance job = job(QbAi.KIND_QUESTIONS, ClaudeService.OPUS, 6_000, params(1, null));
        assertThat(AiProcesses.unrecordedAttempt(job)).isEqualTo(new ClaudeApi.Usage(
            AiLimits.reservedInput(6_000, 0), ClaudeApi.maxTokens(1)));
    }

    @Test
    void everyAttemptUnrecordedIsChargedExactlyTheJobsEstimate() {
        long bytes = 12_345;
        int count = 4;
        EntityInstance job = job(QbAi.KIND_QUESTIONS, ClaudeService.OPUS, bytes,
            params(count, AiLimits.reservedInput(bytes, 0)));
        ClaudeApi.Usage charged = AiProcesses.charged(Map.of(), AiQuestionsJob.ATTEMPTS, job);
        assertThat(charged.inputTokens() + charged.outputTokens())
            .isEqualTo(AiLimits.estimate(bytes, ClaudeApi.maxTokens(count), 0));
        // With PDF documents too: the estimate reserves the page cap for every attempt.
        EntityInstance pdf = job(QbAi.KIND_QUESTIONS, ClaudeService.OPUS, bytes,
            params(count, AiLimits.reservedInput(bytes, 50)));
        ClaudeApi.Usage pdfCharged = AiProcesses.charged(Map.of(), AiQuestionsJob.ATTEMPTS, pdf);
        assertThat(pdfCharged.inputTokens() + pdfCharged.outputTokens())
            .isEqualTo(AiLimits.estimate(bytes, ClaudeApi.maxTokens(count), 50));
    }

    @Test
    void anUnrecordedAttemptOfTheSmallestModelAboveTheLongPromptLineIsPricedAtTheLongRate() {
        long reserved = 150_000;
        assertThat(reserved).isGreaterThan(ClaudeCalls.LONG_PROMPT_FROM);
        EntityInstance job = job(QbAi.KIND_QUESTIONS, ClaudeService.HAIKU, 3_000, params(1, reserved));
        ClaudeApi.Usage charged = AiProcesses.charged(Map.of(), 1, job);
        assertThat(charged.longPromptInputTokens()).isEqualTo(reserved);
        assertThat(charged.longPromptOutputTokens()).isEqualTo(ClaudeApi.maxTokens(1));
        // 150,000 x $0.50 and 4,600 x $2.50 a million, not Haiku's ordinary $0.10 / $0.50.
        assertThat(AiCost.of(ClaudeService.HAIKU, null, charged, null)).isEqualByComparingTo("0.086500");
        // Below the line, or on a model with one rate, nothing is at the long rate.
        assertThat(AiProcesses.charged(Map.of(), 1, job(QbAi.KIND_QUESTIONS, ClaudeService.HAIKU, 3_000,
            params(1, 90_000L))).longPromptInputTokens()).isZero();
        assertThat(AiProcesses.charged(Map.of(), 1, job(QbAi.KIND_QUESTIONS, ClaudeService.OPUS, 3_000,
            params(1, reserved))).longPromptInputTokens()).isZero();
    }

    /**
     * The reservation counts the fixed system prompt and the answer's schema in {@link AiLimits#FRAMING_TOKENS} (review
     * L1): it must cover both at a token per three bytes, with room to spare, so that a longer prompt or catalogue
     * cannot quietly use up the margin of what an attempt is charged.
     */
    @Test
    void theFramingCoversTheSystemPromptsAndSchemasWithAMargin() {
        long margin = 200;
        for (String kind : new String[] {"questions", "covers"}) {
            String system = "questions".equals(kind) ? AiPrompt.SYSTEM : AiCoverPrompt.SYSTEM;
            Map<String, Object> schema = "questions".equals(kind) ? AiPrompt.schema() : AiCoverPrompt.schema();
            long bytes = system.getBytes(StandardCharsets.UTF_8).length
                + JSON.writeValueAsString(schema).getBytes(StandardCharsets.UTF_8).length;
            assertThat(AiLimits.FRAMING_TOKENS).as(kind + ": " + bytes + " bytes of system prompt and schema")
                .isGreaterThanOrEqualTo((bytes + 2) / 3 + margin);
        }
    }
}

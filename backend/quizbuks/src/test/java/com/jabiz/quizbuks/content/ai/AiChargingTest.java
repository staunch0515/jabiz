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

    /**
     * F-M9 (plan §2, H1): what a handler estimated for a call that got no answer is kept apart from what the provider's
     * answers reported. The job is charged both (its tokens and dollars, as before), but only the answered part is the
     * sponsor's ({@link AiProcesses#usage}); the estimate and the dead attempts' are the platform's.
     */
    @Test
    void anUnansweredCallsEstimateIsChargedButKeptApartFromTheAnsweredUsage() {
        EntityInstance job = job(QbAi.KIND_QUESTIONS, ClaudeService.HAIKU, 3_000, params(2, 150_000L));
        Map<String, Long> recorded = Map.of(AiQuestionsJob.INPUT_TOKENS, 1_200L, AiQuestionsJob.OUTPUT_TOKENS, 800L,
            AiUsageMetrics.ESTIMATED_INPUT_TOKENS, 150_000L, AiUsageMetrics.ESTIMATED_OUTPUT_TOKENS, 3_000L,
            AiUsageMetrics.ESTIMATED_LONG_INPUT_TOKENS, 150_000L, AiUsageMetrics.ESTIMATED_LONG_OUTPUT_TOKENS, 3_000L);
        assertThat(AiProcesses.usage(recorded)).isEqualTo(new ClaudeApi.Usage(1_200, 800));
        assertThat(AiUsageMetrics.estimated(recorded)).isEqualTo(new ClaudeApi.Usage(150_000, 3_000, 0, 0, 150_000,
            3_000));
        ClaudeApi.Usage charged = AiProcesses.charged(recorded, 0, job);
        assertThat(charged).isEqualTo(new ClaudeApi.Usage(151_200, 3_800, 0, 0, 150_000, 3_000));
        // An attempt that recorded nothing is on top, as before.
        ClaudeApi.Usage each = AiProcesses.unrecordedAttempt(job);
        assertThat(AiProcesses.charged(recorded, 1, job).inputTokens()).isEqualTo(151_200 + each.inputTokens());
        // The other readers of the metrics (the AI help, the prospecting) see the estimate in the totals, as before.
        assertThat(AiUsageMetrics.merged(recorded)).containsEntry(AiQuestionsJob.INPUT_TOKENS, 151_200L)
            .containsEntry(AiQuestionsJob.OUTPUT_TOKENS, 3_800L).containsEntry(AiQuestionsJob.LONG_INPUT_TOKENS, 150_000L)
            .containsEntry(AiQuestionsJob.LONG_OUTPUT_TOKENS, 3_000L)
            .doesNotContainKeys(AiUsageMetrics.ESTIMATED_INPUT_TOKENS, AiUsageMetrics.ESTIMATED_OUTPUT_TOKENS);
        assertThat(AiUsageMetrics.merged(Map.of(AiQuestionsJob.INPUT_TOKENS, 5L))).containsExactly(
            Map.entry(AiQuestionsJob.INPUT_TOKENS, 5L));
    }

    /** F-M9 (H1): a call that may have been billed but got no answer is recorded under the estimate's own metrics. */
    @Test
    void anEstimateIsRecordedUnderItsOwnMetricsPricedAsACallIs() {
        Map<String, Long> metrics = new LinkedHashMap<>();
        com.jabiz.external.ExternalJobContext ctx = new com.jabiz.external.ExternalJobContext() {
            public String jobId() { return "job"; }
            public int attempt() { return 1; }
            public String idempotencyKey() { return "key"; }
            public java.time.Instant deadline() { return java.time.Instant.EPOCH; }
            public com.jabiz.external.ExternalHttp http(String service) { throw new UnsupportedOperationException(); }
            public String secret(String service, String name) { throw new UnsupportedOperationException(); }
            public void recordUsage(String metric, long quantity) { metrics.merge(metric, quantity, Long::sum); }
        };
        ClaudeCalls.recordEstimate(ctx, ClaudeService.HAIKU, new ClaudeApi.Usage(150_000, 3_000));
        ClaudeCalls.recordEstimate(ctx, ClaudeService.OPUS, new ClaudeApi.Usage(1_000, 500));
        assertThat(metrics).containsExactlyInAnyOrderEntriesOf(Map.of(
            AiUsageMetrics.ESTIMATED_INPUT_TOKENS, 151_000L, AiUsageMetrics.ESTIMATED_OUTPUT_TOKENS, 3_500L,
            AiUsageMetrics.ESTIMATED_LONG_INPUT_TOKENS, 150_000L, AiUsageMetrics.ESTIMATED_LONG_OUTPUT_TOKENS, 3_000L));
    }

    @Test
    void aCoverAttemptIsChargedItsReservationAndTheCoversOutput() {
        long reserved = AiLimits.reservedInput(900, 0);
        EntityInstance job = job(QbAi.KIND_COVER, ClaudeService.OPUS, 900, params(1, reserved));
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
        long bytes = AiPrompt.SYSTEM.getBytes(StandardCharsets.UTF_8).length
            + JSON.writeValueAsString(AiPrompt.schema()).getBytes(StandardCharsets.UTF_8).length;
        assertThat(AiLimits.FRAMING_TOKENS).as("questions: " + bytes + " bytes of system prompt and schema")
            .isGreaterThanOrEqualTo((bytes + 2) / 3 + margin);
    }
}

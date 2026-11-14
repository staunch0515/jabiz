package com.jabiz.quizbuks.content.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * F-M9 (docs/quizbuks/plans/impl/F-M9.md §2, review H1): the usage metrics a job's attempts record, in two groups. The
 * provider's answers report what they used ({@link AiQuestionsJob#INPUT_TOKENS} and the rest); a call that may have
 * been billed but got no answer (a timeout) is recorded as an estimate under metrics of its own
 * ({@link ClaudeCalls#recordEstimate}), so that the two can be told apart: the job's tokens and dollars count both
 * (the limits and the platform's books err high, decision D52 item 1), while a sponsor's AI fee is worked out from the
 * answered usage only (the estimate is the platform's).
 */
public final class AiUsageMetrics {

    public static final String ESTIMATED_INPUT_TOKENS = "estimatedInputTokens";
    public static final String ESTIMATED_OUTPUT_TOKENS = "estimatedOutputTokens";
    /** The part of the estimate at the long-prompt rate ({@link ClaudeCalls#priced}). */
    public static final String ESTIMATED_LONG_INPUT_TOKENS = "estimatedLongPromptInputTokens";
    public static final String ESTIMATED_LONG_OUTPUT_TOKENS = "estimatedLongPromptOutputTokens";

    /** The estimates of the calls that got no answer (an estimate has no fallback part). */
    public static ClaudeApi.Usage estimated(Map<String, Long> metrics) {
        return new ClaudeApi.Usage(metrics.getOrDefault(ESTIMATED_INPUT_TOKENS, 0L),
            metrics.getOrDefault(ESTIMATED_OUTPUT_TOKENS, 0L), 0, 0,
            metrics.getOrDefault(ESTIMATED_LONG_INPUT_TOKENS, 0L),
            metrics.getOrDefault(ESTIMATED_LONG_OUTPUT_TOKENS, 0L));
    }

    /**
     * The metrics with the estimates added into the answered ones' names and their own names removed: what the readers
     * that do not tell the two apart (the AI help, the prospecting) read, as they did before the estimates had names of
     * their own.
     */
    public static Map<String, Long> merged(Map<String, Long> metrics) {
        Map<String, Long> merged = new LinkedHashMap<>(metrics);
        move(merged, ESTIMATED_INPUT_TOKENS, AiQuestionsJob.INPUT_TOKENS);
        move(merged, ESTIMATED_OUTPUT_TOKENS, AiQuestionsJob.OUTPUT_TOKENS);
        move(merged, ESTIMATED_LONG_INPUT_TOKENS, AiQuestionsJob.LONG_INPUT_TOKENS);
        move(merged, ESTIMATED_LONG_OUTPUT_TOKENS, AiQuestionsJob.LONG_OUTPUT_TOKENS);
        return merged;
    }

    private static void move(Map<String, Long> metrics, String from, String to) {
        Long value = metrics.remove(from);
        if (value != null) {
            metrics.merge(to, value, Long::sum);
        }
    }

    private AiUsageMetrics() {}
}

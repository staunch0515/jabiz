package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalService;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * One call of Claude during an attempt of an AI job, as every kind of job makes it (questions, Q8a; covers, Q8b): the
 * usage recorded as soon as it is known, an unanswered attempt charged its estimate, and the answer accepted only when
 * it ended its turn.
 * <ul>
 *   <li>a refusal (after the provider's own fallback) ends the job at once with an explanation; a refusal whose
 *       fallback could not run (the provider names a model to try, {@code stop_details.recommended_model}) is tried
 *       again;</li>
 *   <li>an answer cut at the token limit ends the job; any other stop reason is tried again;</li>
 *   <li>no answer at all (a time-out, a lost connection, an answer too large to read) is charged
 *       {@link ClaudeApi#unanswered} before it fails: the provider may have billed an answer nobody read.</li>
 * </ul>
 */
final class ClaudeCalls {

    /** Checks {@code quizbuks.ai.request-timeout}: more than zero and at most the service's two minutes. */
    static Duration requestTimeout(Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(ExternalService.MAX_REQUEST_TIMEOUT) > 0) {
            throw new IllegalArgumentException("quizbuks.ai.request-timeout: more than zero and at most "
                + ExternalService.MAX_REQUEST_TIMEOUT);
        }
        return timeout;
    }

    /** Sends the request and returns an answer that ended its turn; throws the attempt's failure otherwise. */
    static ClaudeApi.Answer ask(ExternalJobContext ctx, JsonMapper json, ClaudeApi.Request request,
        Duration timeout) {
        ClaudeApi.Answer answer;
        try {
            answer = ClaudeApi.of(ctx, json).send(request, timeout);
        } catch (ExternalCallException e) {
            if (ClaudeApi.mayHaveBeenBilled(e)) {
                record(ctx, ClaudeApi.unanswered(request));
            }
            throw e;
        }
        record(ctx, answer.usage());
        if ("refusal".equals(answer.stopReason())) {
            String message = "Claude declined the request"
                + (answer.refusalCategory() == null ? "" : " (" + answer.refusalCategory() + ")");
            // The provider's fallback could not run (rate-limited or overloaded): another attempt may pass.
            throw answer.recommendedModel() != null
                ? ExternalJobException.retryable(AiCodes.REFUSED, message + "; its fallback was busy")
                : ExternalJobException.permanent(AiCodes.REFUSED, message);
        }
        if ("max_tokens".equals(answer.stopReason())) {
            throw ExternalJobException.permanent(AiCodes.TRUNCATED, "Claude's answer reached the token limit");
        }
        if (!"end_turn".equals(answer.stopReason())) {
            throw ClaudeErrors.badResponse("ended with an unexpected reason");
        }
        return answer;
    }

    static void record(ExternalJobContext ctx, ClaudeApi.Usage usage) {
        ctx.recordUsage(AiQuestionsJob.INPUT_TOKENS, usage.inputTokens());
        ctx.recordUsage(AiQuestionsJob.OUTPUT_TOKENS, usage.outputTokens());
        if (usage.fallbackInputTokens() > 0 || usage.fallbackOutputTokens() > 0) {
            ctx.recordUsage(AiQuestionsJob.FALLBACK_INPUT_TOKENS, usage.fallbackInputTokens());
            ctx.recordUsage(AiQuestionsJob.FALLBACK_OUTPUT_TOKENS, usage.fallbackOutputTokens());
        }
    }

    private ClaudeCalls() {}
}

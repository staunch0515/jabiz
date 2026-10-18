package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * One call of Claude during an attempt of an AI job, as every kind of job makes it (questions, Q8a; covers, Q8b): the
 * usage recorded as soon as it is known, an unanswered attempt charged its estimate, and the answer accepted only when
 * it ended its turn.
 * <ul>
 *   <li>a request with PDF documents is counted first ({@link #counted}: the provider's free count of exactly what
 *       is sent); more input than the job reserved for an attempt ends the job, nothing sent or charged
 *       ({@link AiCodes#PDF_TOO_LONG}); a count that fails on the way is tried again, uncharged;</li>
 *   <li>a refusal (after the provider's own fallback) ends the job at once with an explanation; a refusal whose
 *       fallback could not run (the provider names a model to try, {@code stop_details.recommended_model}) is tried
 *       again;</li>
 *   <li>an answer cut at the token limit ends the job; any other stop reason is tried again;</li>
 *   <li>no answer at all (a time-out, a lost connection, an answer too large to read) is charged
 *       {@link ClaudeApi#unanswered} before it fails: the provider may have billed an answer nobody read.</li>
 * </ul>
 * Usage above what the job reserved for an attempt is logged as a warning (job type only: no ids, D16).
 */
final class ClaudeCalls {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCalls.class);

    /** The provider's price changes above this many input tokens a request, for the models that have two rates. */
    static final long LONG_PROMPT_FROM = 100_000;

    /**
     * The configuration both writers use ({@code quizbuks.ai.request-timeout}: more than zero and at most the
     * service's two minutes), one bean.
     */
    record Settings(Duration requestTimeout) {
        Settings {
            if (requestTimeout.isNegative() || requestTimeout.isZero()
                || requestTimeout.compareTo(ExternalService.MAX_REQUEST_TIMEOUT) > 0) {
                throw new IllegalArgumentException("quizbuks.ai.request-timeout: more than zero and at most "
                    + ExternalService.MAX_REQUEST_TIMEOUT);
            }
        }
    }

    /**
     * What an attempt of the job may use, as the request reserved it: its input and its output in all. Null fields
     * are not checked (jobs started before Q8b).
     */
    record Reserved(String jobType, Long inputTokens, long totalTokens) {}

    /**
     * The request with the provider's count of its input when it has documents (the request as it is otherwise);
     * refused, uncharged, when the count is above what the job reserved for an attempt's input.
     */
    static ClaudeApi.Request counted(ExternalJobContext ctx, JsonMapper json, ClaudeApi.Request request,
        Duration timeout, Reserved reserved) {
        if (request.documents().isEmpty()) {
            return request;
        }
        // Nothing is billed for a count: a failure on the way is the platform's to try again, and charged nothing.
        long count = ClaudeApi.of(ctx, json).countTokens(request, timeout);
        if (reserved.inputTokens() != null && count > reserved.inputTokens()) {
            throw ExternalJobException.permanent(AiCodes.PDF_TOO_LONG, "The request is " + count
                + " input tokens, more than the " + reserved.inputTokens() + " reserved");
        }
        return request.counted(count);
    }

    /** Sends the request and returns an answer that ended its turn; throws the attempt's failure otherwise. */
    static ClaudeApi.Answer ask(ExternalJobContext ctx, JsonMapper json, ClaudeApi.Request request,
        Duration timeout, Reserved reserved) {
        ClaudeApi.Answer answer;
        try {
            answer = ClaudeApi.of(ctx, json).send(request, timeout);
        } catch (ExternalCallException e) {
            if (ClaudeApi.mayHaveBeenBilled(e)) {
                record(ctx, request.model(), ClaudeApi.unanswered(request), reserved);
            }
            throw e;
        }
        record(ctx, request.model(), answer.usage(), reserved);
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

    /**
     * Records one call's usage: the totals, the fallback model's part, and (for a model with a long-prompt rate) the
     * part of a call above {@value #LONG_PROMPT_FROM} input tokens, priced per call ({@link AiCost}).
     */
    static void record(ExternalJobContext ctx, String model, ClaudeApi.Usage usage, Reserved reserved) {
        ClaudeApi.Usage priced = AiCost.hasLongPromptRate(model) && usage.inputTokens() > LONG_PROMPT_FROM
            ? usage.asLongPrompt() : usage;
        ctx.recordUsage(AiQuestionsJob.INPUT_TOKENS, priced.inputTokens());
        ctx.recordUsage(AiQuestionsJob.OUTPUT_TOKENS, priced.outputTokens());
        if (priced.fallbackInputTokens() > 0 || priced.fallbackOutputTokens() > 0) {
            ctx.recordUsage(AiQuestionsJob.FALLBACK_INPUT_TOKENS, priced.fallbackInputTokens());
            ctx.recordUsage(AiQuestionsJob.FALLBACK_OUTPUT_TOKENS, priced.fallbackOutputTokens());
        }
        if (priced.longPromptInputTokens() > 0 || priced.longPromptOutputTokens() > 0) {
            ctx.recordUsage(AiQuestionsJob.LONG_INPUT_TOKENS, priced.longPromptInputTokens());
            ctx.recordUsage(AiQuestionsJob.LONG_OUTPUT_TOKENS, priced.longPromptOutputTokens());
        }
        if (reserved != null && usage.inputTokens() + usage.outputTokens() > reserved.totalTokens()) {
            // The limits counted on the reservation (a provider's fallback runs the input again): worth a look.
            log.warn("AI usage of one attempt above its reservation (job type {})", reserved.jobType());
        }
    }

    private ClaudeCalls() {}
}

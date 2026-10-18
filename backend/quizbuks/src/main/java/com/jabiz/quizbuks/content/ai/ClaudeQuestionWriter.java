package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

/**
 * Candidate questions from Claude ({@link ClaudeApi}): the fixed system prompt, the request and the sponsor's content
 * as data ({@link AiPrompt}), the model and effort the request was made with. A refusal (after the provider's own
 * fallback) and an answer cut at the token limit end the job at once with an explanation; a refusal whose fallback could
 * not run (the provider names a model to try, {@code stop_details.recommended_model}) is tried again; an answer that is
 * not the document asked for is tried again; the candidates that break the content rules are dropped and counted.
 *
 * <p>An attempt that got no answer (a time-out, a lost connection, an answer too large to read) is charged its estimate
 * before it fails ({@link ClaudeApi#unanswered}): the provider may have billed an answer nobody read, and the limits
 * count what may have been spent. The request waits {@code quizbuks.ai.request-timeout} at most (two minutes, the
 * service's longest).
 */
@Component
class ClaudeQuestionWriter implements QuestionWriter {

    private final JsonMapper json;
    private final Duration requestTimeout;

    ClaudeQuestionWriter(JsonMapper json,
        @Value("${quizbuks.ai.request-timeout:PT2M}") Duration requestTimeout) {
        this.json = json;
        if (requestTimeout.isNegative() || requestTimeout.isZero()
            || requestTimeout.compareTo(ExternalService.MAX_REQUEST_TIMEOUT) > 0) {
            throw new IllegalArgumentException("quizbuks.ai.request-timeout: more than zero and at most "
                + ExternalService.MAX_REQUEST_TIMEOUT);
        }
        this.requestTimeout = requestTimeout;
    }

    @Override
    public AiQuestionsJob.Result write(AiQuestionsJob.Input input, ExternalJobContext ctx) {
        ClaudeApi.Request request = new ClaudeApi.Request(input.model(), input.effort(),
            ClaudeApi.maxTokens(input.count()), AiPrompt.SYSTEM, AiPrompt.user(input, json), input.userKey());
        ClaudeApi.Answer answer;
        try {
            answer = ClaudeApi.of(ctx, json).send(request, requestTimeout);
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
        List<AiQuestionsJob.Candidate> candidates = ClaudeApi.candidates(answer.text(), json);
        if (candidates == null) {
            throw ClaudeErrors.badResponse("is not the questions' document");
        }
        AiCandidates.Checked checked = AiCandidates.check(candidates, input.count(),
            input.materials().stream().map(AiQuestionsJob.Material::no).toList());
        return new AiQuestionsJob.Result(answer.servedModel(), answer.fellBack(), checked.valid(), checked.invalid());
    }

    private static void record(ExternalJobContext ctx, ClaudeApi.Usage usage) {
        ctx.recordUsage(AiQuestionsJob.INPUT_TOKENS, usage.inputTokens());
        ctx.recordUsage(AiQuestionsJob.OUTPUT_TOKENS, usage.outputTokens());
        if (usage.fallbackInputTokens() > 0 || usage.fallbackOutputTokens() > 0) {
            ctx.recordUsage(AiQuestionsJob.FALLBACK_INPUT_TOKENS, usage.fallbackInputTokens());
            ctx.recordUsage(AiQuestionsJob.FALLBACK_OUTPUT_TOKENS, usage.fallbackOutputTokens());
        }
    }
}

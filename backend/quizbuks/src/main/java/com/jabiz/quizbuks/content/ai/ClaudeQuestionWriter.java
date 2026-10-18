package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Candidate questions from Claude ({@link ClaudeApi}): the fixed system prompt, the request and the sponsor's content
 * as data ({@link AiPrompt}), the model and effort the request was made with. A refusal (after the provider's own
 * fallback) and an answer cut at the token limit end the job at once with an explanation; an answer that is not the
 * document asked for is tried again; the candidates that break the content rules are dropped and counted.
 */
@Component
class ClaudeQuestionWriter implements QuestionWriter {

    private final JsonMapper json;

    ClaudeQuestionWriter(JsonMapper json) {
        this.json = json;
    }

    @Override
    public AiQuestionsJob.Result write(AiQuestionsJob.Input input, ExternalJobContext ctx) {
        ClaudeApi.Answer answer = ClaudeApi.of(ctx, json).send(new ClaudeApi.Request(input.model(), input.effort(),
            AiPrompt.SYSTEM, AiPrompt.user(input, json), input.userKey()));
        ctx.recordUsage(AiQuestionsJob.INPUT_TOKENS, answer.usage().inputTokens());
        ctx.recordUsage(AiQuestionsJob.OUTPUT_TOKENS, answer.usage().outputTokens());
        if ("refusal".equals(answer.stopReason())) {
            throw ExternalJobException.permanent(AiCodes.REFUSED, "Claude declined the request"
                + (answer.refusalCategory() == null ? "" : " (" + answer.refusalCategory() + ")"));
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
}

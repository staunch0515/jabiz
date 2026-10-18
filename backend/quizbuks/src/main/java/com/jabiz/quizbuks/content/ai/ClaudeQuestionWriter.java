package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobFile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Candidate questions from Claude ({@link ClaudeApi}): the fixed system prompt, the request and the sponsor's content
 * as data ({@link AiPrompt}), the model and effort the request was made with, and (Q8b) the chosen PDF materials as
 * documents, read from the job's files ({@link AiPdfs}: counted and checked before anything is sent). The call itself,
 * its usage and its failures are {@link ClaudeCalls}'s; an answer that is not the document asked for is tried again;
 * the candidates that break the content rules are dropped and counted.
 *
 * <p>The request waits {@code quizbuks.ai.request-timeout} at most (two minutes, the service's longest).
 */
@Component
class ClaudeQuestionWriter implements QuestionWriter {

    private final JsonMapper json;
    private final Duration requestTimeout;

    ClaudeQuestionWriter(JsonMapper json, ClaudeCalls.Settings settings) {
        this.json = json;
        this.requestTimeout = settings.requestTimeout();
    }

    @Override
    public AiQuestionsJob.Result write(AiQuestionsJob.Input input, ExternalJobContext ctx) {
        List<ClaudeApi.Document> documents = new ArrayList<>();
        for (AiQuestionsJob.Material material : input.materials()) {
            if (material.fileId() != null) {
                // Encoded once (the count and the call send the same text); the bytes are not kept.
                ExternalJobFile file = ctx.file(material.fileId());
                documents.add(ClaudeApi.Document.of(AiPrompt.documentTitle(material.no()), file.bytes()));
            }
        }
        int maxTokens = ClaudeApi.maxTokens(input.count());
        ClaudeCalls.Reserved reserved = new ClaudeCalls.Reserved(AiQuestionsJob.TYPE, input.reservedInputTokens(),
            input.reservedInputTokens() == null ? Long.MAX_VALUE : input.reservedInputTokens() + maxTokens);
        ClaudeApi.Request request = ClaudeCalls.counted(ctx, json, new ClaudeApi.Request(input.model(),
            input.effort(), maxTokens, AiPrompt.SYSTEM, AiPrompt.user(input, json), input.userKey(),
            AiPrompt.schema(), documents), requestTimeout, reserved);
        ClaudeApi.Answer answer = ClaudeCalls.ask(ctx, json, request, requestTimeout, reserved);
        List<AiQuestionsJob.Candidate> candidates = ClaudeApi.candidates(answer.text(), json);
        if (candidates == null) {
            throw ClaudeErrors.badResponse("is not the questions' document");
        }
        AiCandidates.Checked checked = AiCandidates.check(candidates, input.count(),
            input.materials().stream().map(AiQuestionsJob.Material::no).toList());
        return new AiQuestionsJob.Result(answer.servedModel(), answer.fellBack(), checked.valid(), checked.invalid());
    }
}

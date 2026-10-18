package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

/**
 * Cover designs from Claude ({@link ClaudeApi}, {@link AiCoverPrompt}): the call, its usage and its failures as for
 * questions ({@link ClaudeCalls}); an answer that is not the covers' document is tried again.
 */
@Component
class ClaudeCoverDesigner implements CoverDesigner {

    private final JsonMapper json;
    private final Duration requestTimeout;

    ClaudeCoverDesigner(JsonMapper json, @Value("${quizbuks.ai.request-timeout:PT2M}") Duration requestTimeout) {
        this.json = json;
        this.requestTimeout = ClaudeCalls.requestTimeout(requestTimeout);
    }

    @Override
    public Designed design(AiCoverJob.Input input, ExternalJobContext ctx) {
        ClaudeApi.Request request = new ClaudeApi.Request(input.model(), input.effort(), AiCoverJob.MAX_TOKENS,
            AiCoverPrompt.SYSTEM, AiCoverPrompt.user(input, json), input.userKey(), AiCoverPrompt.schema(), List.of());
        ClaudeApi.Answer answer = ClaudeCalls.ask(ctx, json, request, requestTimeout);
        List<CoverDesigns.Design> designs = AiCoverPrompt.designs(answer.text(), json);
        if (designs == null) {
            throw ClaudeErrors.badResponse("is not the covers' document");
        }
        return new Designed(answer.servedModel(), answer.fellBack(), designs);
    }
}

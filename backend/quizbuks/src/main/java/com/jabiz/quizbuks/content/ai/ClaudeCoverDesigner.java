package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobContext;
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

    ClaudeCoverDesigner(JsonMapper json, ClaudeCalls.Settings settings) {
        this.json = json;
        this.requestTimeout = settings.requestTimeout();
    }

    @Override
    public Designed design(AiCoverJob.Input input, ExternalJobContext ctx) {
        ClaudeApi.Request request = new ClaudeApi.Request(input.model(), input.effort(), AiCoverJob.MAX_TOKENS,
            AiCoverPrompt.SYSTEM, AiCoverPrompt.user(input, json), input.userKey(), AiCoverPrompt.schema(), List.of());
        ClaudeCalls.Reserved reserved = new ClaudeCalls.Reserved(AiCoverJob.TYPE, input.reservedInputTokens(),
            input.reservedInputTokens() == null ? Long.MAX_VALUE : input.reservedInputTokens() + AiCoverJob.MAX_TOKENS);
        ClaudeApi.Answer answer = ClaudeCalls.ask(ctx, json, request, requestTimeout, reserved);
        List<CoverDesigns.Design> designs = AiCoverPrompt.designs(answer.text(), json);
        if (designs == null) {
            throw ClaudeErrors.badResponse("is not the covers' document");
        }
        return new Designed(answer.servedModel(), answer.fellBack(), designs);
    }

    /**
     * An illustration (Q96, D-Q96-6): asked with little thinking ({@link AiCoverJob#ILLUSTRATION_EFFORT}) so that the
     * SVG arrives within the request's time; an answer that is not the document is tried again.
     */
    @Override
    public Illustrated illustrate(AiCoverJob.Input input, ExternalJobContext ctx) {
        ClaudeApi.Request request = new ClaudeApi.Request(input.model(), AiCoverJob.ILLUSTRATION_EFFORT,
            AiCoverJob.ILLUSTRATION_MAX_TOKENS, AiCoverPrompt.ILLUSTRATION_SYSTEM,
            AiCoverPrompt.illustrationUser(input, json), input.userKey(), AiCoverPrompt.illustrationSchema(),
            List.of());
        ClaudeCalls.Reserved reserved = new ClaudeCalls.Reserved(AiCoverJob.TYPE, input.reservedInputTokens(),
            input.reservedInputTokens() == null ? Long.MAX_VALUE
                : input.reservedInputTokens() + AiCoverJob.ILLUSTRATION_MAX_TOKENS);
        ClaudeApi.Answer answer = ClaudeCalls.ask(ctx, json, request, requestTimeout, reserved);
        String[] parts = AiCoverPrompt.illustration(answer.text(), json);
        if (parts == null) {
            throw ClaudeErrors.badResponse("is not the illustration's document");
        }
        return new Illustrated(answer.servedModel(), answer.fellBack(), parts[0], parts[1], parts[2]);
    }
}

package com.jabiz.quizbuks.help;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import com.jabiz.quizbuks.content.ai.ClaudeApi;
import com.jabiz.quizbuks.content.ai.ClaudeCalls;
import com.jabiz.quizbuks.content.ai.ClaudeErrors;
import com.jabiz.quizbuks.content.ai.ClaudeService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

/**
 * The handler of {@value HelpJob#TYPE} (F-C10): one call of Claude as every QuizBuks AI job makes it
 * ({@link ClaudeCalls#ask}: the usage recorded as soon as it is known, an attempt that got no answer charged its
 * estimate). It reads no database: everything is in the job's input. The answer's links and addresses are checked again
 * by the outcome, which keeps only the site's.
 */
@Component
class HelpHandler implements ExternalJobHandler<HelpJob.Input, HelpJob.Result> {

    private final JsonMapper json;
    private final Duration requestTimeout;

    HelpHandler(JsonMapper json, ClaudeCalls.Settings settings) {
        this.json = json;
        this.requestTimeout = settings.requestTimeout();
    }

    @Override
    public ExternalJobResult<HelpJob.Result> run(HelpJob.Input input, ExternalJobContext ctx) {
        if (!ClaudeService.MODELS.contains(input.model())) {
            throw ExternalJobException.permanent(HelpCodes.MODEL, "The model is not one QuizBuks allows");
        }
        ClaudeApi.Request request = new ClaudeApi.Request(input.model(), HelpJob.EFFORT, HelpJob.MAX_TOKENS,
            HelpPrompt.SYSTEM, HelpPrompt.user(input, json), input.userKey(), HelpPrompt.schema(), List.of());
        ClaudeCalls.Reserved reserved = new ClaudeCalls.Reserved(HelpJob.TYPE, input.reservedInputTokens(),
            input.reservedInputTokens() + HelpJob.MAX_TOKENS);
        ClaudeApi.Answer answer = ClaudeCalls.ask(ctx, json, request, requestTimeout, reserved);
        HelpPrompt.Parsed parsed = HelpPrompt.parse(answer.text(), json);
        if (parsed == null) {
            throw ClaudeErrors.badResponse("is not the help's answer document");
        }
        return ExternalJobResult.of(new HelpJob.Result(answer.servedModel(), answer.fellBack(), parsed.answer(),
            parsed.links(), parsed.quizRequest()));
    }
}

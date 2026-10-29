package com.jabiz.quizbuks.prospect;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import com.jabiz.quizbuks.content.ai.ClaudeErrors;
import com.jabiz.quizbuks.content.ai.ClaudeWebResearch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashSet;

/**
 * The handler of {@value ProspectJobs#FIND}: Claude searches the web along the direction's brief
 * ({@link ClaudeWebResearch}, web search only) and names businesses' own sites; the reply's candidates are checked
 * ({@link ProspectAnswers#find}). A reply without a list of candidates is tried again. It reads no database.
 */
@Component
class ProspectFindHandler implements ExternalJobHandler<ProspectJobs.FindInput, ProspectJobs.FindResult> {

    private final JsonMapper json;
    private final Duration timeout;

    ProspectFindHandler(JsonMapper json, @Value("${quizbuks.ai.request-timeout:PT2M}") Duration timeout) {
        this.json = json;
        this.timeout = timeout;
    }

    @Override
    public ExternalJobResult<ProspectJobs.FindResult> run(ProspectJobs.FindInput input, ExternalJobContext ctx) {
        ClaudeWebResearch.Answer answer = ClaudeWebResearch.of(ctx, json).ask(ctx, new ClaudeWebResearch.Request(
            input.model(), input.effort(), ProspectPrompts.FIND_SYSTEM, ProspectPrompts.find(input, json),
            input.userKey(), ProspectJobs.FIND_SEARCHES, 0, input.country()), timeout);
        ProspectJobs.FindResult result = ProspectAnswers.find(answer.servedModel(), answer.text(), input.perSearch(),
            new HashSet<>(input.excludedDomains()), json);
        if (result == null) {
            throw ClaudeErrors.badResponse("is not the candidates' document");
        }
        return ExternalJobResult.of(result);
    }
}

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

/**
 * The handler of {@value ProspectJobs#RESEARCH}: Claude reads the business's site ({@link ClaudeWebResearch}, web
 * fetch with a few searches to find its pages), scores it and, when it may be written to, drafts the trial quiz and
 * the mail; the reply is checked ({@link ProspectAnswers#research}). A reply without the research is tried again. It
 * reads no database: whether the address is blocked is decided by the completion process.
 */
@Component
class ProspectResearchHandler implements ExternalJobHandler<ProspectJobs.ResearchInput, ProspectJobs.ResearchResult> {

    private final JsonMapper json;
    private final Duration timeout;

    ProspectResearchHandler(JsonMapper json, @Value("${quizbuks.ai.request-timeout:PT2M}") Duration timeout) {
        this.json = json;
        this.timeout = timeout;
    }

    @Override
    public ExternalJobResult<ProspectJobs.ResearchResult> run(ProspectJobs.ResearchInput input,
        ExternalJobContext ctx) {
        ClaudeWebResearch.Answer answer = ClaudeWebResearch.of(ctx, json).ask(ctx, new ClaudeWebResearch.Request(
            input.model(), input.effort(), ProspectPrompts.RESEARCH_SYSTEM, ProspectPrompts.research(input, json),
            input.userKey(), ProspectJobs.RESEARCH_SEARCHES, ProspectJobs.RESEARCH_FETCHES, null), timeout);
        ProspectJobs.ResearchResult result = ProspectAnswers.research(answer.servedModel(), answer.text(),
            input.domain(), json);
        if (result == null) {
            throw ClaudeErrors.badResponse("is not the research's document");
        }
        return ExternalJobResult.of(result);
    }
}

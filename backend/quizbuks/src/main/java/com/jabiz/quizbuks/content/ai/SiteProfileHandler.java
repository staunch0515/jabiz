package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * The handler of {@value SiteProfileJob#TYPE} (F-C7): Claude reads the sponsor's website ({@link ClaudeWebResearch}
 * with web fetch limited to the site's own domains and no web search) and answers with the draft; the reply is checked
 * and cleaned ({@link SiteProfileAnswer}). It reads no database and takes no image: images are links, which the
 * sponsor's confirmation downloads once (D75), and the sponsor's identity is not in its input.
 */
@Component
class SiteProfileHandler implements ExternalJobHandler<SiteProfileJob.Input, SiteProfileJob.Result> {

    private final JsonMapper json;
    private final Duration timeout;

    /**
     * @param timeout how long one call to the provider waits ({@code quizbuks.ai.site-profile.request-timeout}, the
     *                service's longest, two minutes, unless set shorter): a read of several pages and a long answer
     *                may need more than a call without streaming can have; see docs/quizbuks/plans/impl/F-C7.md
     */
    SiteProfileHandler(JsonMapper json,
        @Value("${quizbuks.ai.site-profile.request-timeout:${quizbuks.ai.request-timeout:PT2M}}") Duration timeout) {
        // refused at start when it is zero or more than the service allows
        new ClaudeCalls.Settings(timeout);
        this.json = json;
        this.timeout = timeout;
    }

    @Override
    public ExternalJobResult<SiteProfileJob.Result> run(SiteProfileJob.Input input, ExternalJobContext ctx) {
        ClaudeWebResearch.Answer answer;
        try {
            answer = ClaudeWebResearch.of(ctx, json).ask(ctx, new ClaudeWebResearch.Request(
                input.model(), input.effort(), SiteProfilePrompt.SYSTEM, SiteProfilePrompt.user(input, json),
                input.userKey(), 0, input.maxPages(), null, input.domains(), SiteProfileJob.MAX_OUTPUT_TOKENS,
                SiteProfileJob.reservedInput(input.maxPages())), timeout);
        } catch (ExternalCallException e) {
            // A read that timed out is not tried again: the provider may have billed it all, and the next would
            // cost as much (the usage of this attempt is recorded by the reader before it throws).
            if (e.kind() == ExternalCallException.Kind.TIMEOUT) {
                throw ExternalJobException.permanent(AiCodes.SITE_TIMEOUT, "The website took too long to read");
            }
            throw e;
        }
        SiteProfileJob.Result result = SiteProfileAnswer.parse(answer.servedModel(), answer.fellBack(), answer.text(),
            input, json);
        if (result == null) {
            throw ClaudeErrors.badResponse("is not the site's draft");
        }
        return ExternalJobResult.of(result);
    }
}

package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import org.springframework.stereotype.Component;

/**
 * Ends a cover job queued before AI covers were withdrawn (Q98a): failed at once, the provider never called, nothing
 * charged for the attempt.
 */
@Component
public class AiCoverWithdrawnHandler implements ExternalJobHandler<AiCoverJob.Input, AiCoverJob.Result> {

    @Override
    public ExternalJobResult<AiCoverJob.Result> run(AiCoverJob.Input input, ExternalJobContext ctx) {
        throw ExternalJobException.permanent(AiCodes.COVER_WITHDRAWN, "AI covers were withdrawn");
    }
}

package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobContext;

/**
 * Who translates a quiz's text (Q73d-1, docs/quizbuks/plans/Q73-content-languages.md §5.1): the provider behind the
 * job {@value AiTranslateJob#TYPE}, as {@link QuestionWriter} is for questions. The job, the processes and the pages
 * know only this; changing the provider changes its one implementation ({@link ClaudeContentTranslator}).
 */
public interface ContentTranslator {

    /**
     * The candidate translations of every segment of one request, during one attempt of the job (all its batches).
     * Records the provider's usage on {@code ctx} call by call as soon as it is known, or throws the attempt's failure.
     */
    AiTranslateJob.Result translate(AiTranslateJob.Input input, ExternalJobContext ctx);
}

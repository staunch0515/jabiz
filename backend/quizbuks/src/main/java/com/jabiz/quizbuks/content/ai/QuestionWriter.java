package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobContext;

/**
 * Who writes candidate questions (docs/quizbuks/plans/Q8-ai.md, D-Q8-1): the provider behind the job
 * {@value AiQuestionsJob#TYPE}. The job, the processes and the pages know only this; changing the provider changes
 * its one implementation ({@link ClaudeQuestionWriter}) and the external service.
 */
public interface QuestionWriter {

    /**
     * Asks for the candidates of one request, during one attempt of the job. Records the provider's usage on
     * {@code ctx} as soon as it is known (a refused or cut answer is billed too), then returns the candidates that meet
     * the content rules or throws the attempt's failure.
     */
    AiQuestionsJob.Result write(AiQuestionsJob.Input input, ExternalJobContext ctx);
}

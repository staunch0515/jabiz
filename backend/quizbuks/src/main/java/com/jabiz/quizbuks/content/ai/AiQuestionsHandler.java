package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import org.springframework.stereotype.Component;

/**
 * The handler of {@value AiQuestionsJob#TYPE}: hands the request to the {@link QuestionWriter}. It reads no database
 * (the platform's rule for handlers): everything it sends is in the job's input, made by the request process from the
 * sponsor's own quiz.
 */
@Component
public class AiQuestionsHandler implements ExternalJobHandler<AiQuestionsJob.Input, AiQuestionsJob.Result> {

    private final QuestionWriter writer;

    AiQuestionsHandler(QuestionWriter writer) {
        this.writer = writer;
    }

    @Override
    public ExternalJobResult<AiQuestionsJob.Result> run(AiQuestionsJob.Input input, ExternalJobContext ctx) {
        return ExternalJobResult.of(writer.write(input, ctx));
    }
}

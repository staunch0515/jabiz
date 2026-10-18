package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The handler of {@value AiQuestionsJob#TYPE}: hands the request to the {@link QuestionWriter}. It reads no database
 * (the platform's rule for handlers): everything it sends is in the job's input, made by the request process from the
 * sponsor's own quiz.
 *
 * <p>Since it reads no database, it cannot see {@code qb.ai.enabled} (switching that off stops new requests only). The
 * stop it can see is the instance's configuration {@code quizbuks.ai.halted} ({@code QB_AI_HALTED}): while it is true,
 * every attempt on the instance fails for good ({@link AiCodes#HALTED}) without calling the provider, queued and
 * retrying jobs included (operations.md section 7.8; the fastest stop is still revoking the key at the provider).
 */
@Component
public class AiQuestionsHandler implements ExternalJobHandler<AiQuestionsJob.Input, AiQuestionsJob.Result> {

    private final QuestionWriter writer;
    private final boolean halted;

    AiQuestionsHandler(QuestionWriter writer, @Value("${quizbuks.ai.halted:false}") boolean halted) {
        this.writer = writer;
        this.halted = halted;
    }

    @Override
    public ExternalJobResult<AiQuestionsJob.Result> run(AiQuestionsJob.Input input, ExternalJobContext ctx) {
        if (halted) {
            throw ExternalJobException.permanent(AiCodes.HALTED, "AI generation is halted on this server");
        }
        return ExternalJobResult.of(writer.write(input, ctx));
    }
}

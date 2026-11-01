package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import org.springframework.stereotype.Component;

/** The handler of {@value AiCheckJob#TYPE} (Q73d-2): hands the pairs to the {@link TranslationChecker}. */
@Component
public class AiCheckHandler implements ExternalJobHandler<AiCheckJob.Input, AiCheckJob.Result> {

    private final TranslationChecker checker;

    AiCheckHandler(TranslationChecker checker) {
        this.checker = checker;
    }

    @Override
    public ExternalJobResult<AiCheckJob.Result> run(AiCheckJob.Input input, ExternalJobContext ctx) {
        return ExternalJobResult.of(checker.check(input, ctx));
    }
}

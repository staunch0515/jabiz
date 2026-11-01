package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobHandler;
import com.jabiz.external.ExternalJobResult;
import org.springframework.stereotype.Component;

/**
 * The handler of {@value AiTranslateJob#TYPE} (Q73d-1): hands the request to the {@link ContentTranslator}. It reads
 * no database: {@code qb.ai.enabled} holds its jobs before they are claimed ({@link AiTranslateJob#type}), and the
 * completion checks the candidates again as it stores them.
 */
@Component
public class AiTranslateHandler implements ExternalJobHandler<AiTranslateJob.Input, AiTranslateJob.Result> {

    private final ContentTranslator translator;

    AiTranslateHandler(ContentTranslator translator) {
        this.translator = translator;
    }

    @Override
    public ExternalJobResult<AiTranslateJob.Result> run(AiTranslateJob.Input input, ExternalJobContext ctx) {
        return ExternalJobResult.of(translator.translate(input, ctx));
    }
}

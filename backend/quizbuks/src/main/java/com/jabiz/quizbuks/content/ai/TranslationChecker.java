package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobContext;

/**
 * Who compares a publication's translations with their base texts (Q73d-2): the provider behind the job
 * {@value AiCheckJob#TYPE}, as {@link ContentTranslator} is for translations.
 */
public interface TranslationChecker {

    /** The verdicts on every pair, during one attempt of the job; records the usage call by call. */
    AiCheckJob.Result check(AiCheckJob.Input input, ExternalJobContext ctx);
}

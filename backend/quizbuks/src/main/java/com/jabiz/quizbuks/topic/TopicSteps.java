package com.jabiz.quizbuks.topic;

import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The steps the processes choosing topics share (a quiz's, a publication's, a taker's liked ones): the two
 * parameters, read as optional (the declared values before the setup made them), and the words of the list the input
 * names, so that a word chosen anew can be checked.
 */
public final class TopicSteps {

    static final String PARAMS = "topic.params";
    public static final String WORDS = "topic.words";

    private TopicSteps() {
    }

    /** Loads {@code qb.topic.max-per-quiz} and {@code qb.topic.custom-max-length}. */
    public static <I, O> void loadParams(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the limits of topics", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
            QbParams.TOPIC_MAX_PER_QUIZ, QbParams.TOPIC_CUSTOM_MAX_LENGTH));
    }

    /** Loads the words of the list the codes name (none: no statement). */
    public static <I, O> void loadWords(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, List<String>> codes) {
        pb.step("Load the words chosen", QueryEntities.<ProcessContext>of(QbTopics.DEFAULT_DATASET,
            ctx -> TopicProcesses.byCodes(codes.apply(ctx)), WORDS));
    }

    /** The words {@link #loadWords} read, by code. */
    public static Map<String, TopicRules.Word> words(ProcessContext ctx) {
        return TopicProcesses.words(rows(ctx));
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> rows(ProcessContext ctx) {
        return ctx.contains(WORDS) ? (List<EntityInstance>) ctx.get(WORDS) : List.of();
    }

    public static int maxPerQuiz(ProcessContext ctx) {
        return TopicRules.maxPerQuiz(integer(ctx, QbParams.TOPIC_MAX_PER_QUIZ));
    }

    public static int customMaxLength(ProcessContext ctx) {
        return TopicRules.customMaxLength(integer(ctx, QbParams.TOPIC_CUSTOM_MAX_LENGTH));
    }

    private static Integer integer(ProcessContext ctx, String key) {
        if (!ctx.contains(PARAMS)) {
            return null;
        }
        return ctx.get(PARAMS, ParamValues.class).find(key, BigDecimal.class).map(BigDecimal::intValue).orElse(null);
    }
}

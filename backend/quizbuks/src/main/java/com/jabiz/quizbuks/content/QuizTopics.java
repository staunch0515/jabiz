package com.jabiz.quizbuks.content;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.topic.TopicRules;
import com.jabiz.quizbuks.topic.TopicSteps;
import com.jabiz.runtime.EntityInstance;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.quizbuks.content.QuizEditing.INPUT;
import static com.jabiz.quizbuks.content.QuizEditing.OUTPUT;
import static com.jabiz.quizbuks.content.QuizEditing.quiz;

/**
 * A quiz's topics (F-S9, docs/quizbuks/plans/impl/F-S9.md): {@value #SET} replaces them, words of the platform's word
 * list and the sponsor's own custom words, at most {@code qb.topic.max-per-quiz} together. A word chosen anew must be
 * offered (active, not merged); one the quiz already had stays though deactivated since. Like every content process it
 * takes the quiz's lock before reading it, checks the revision the caller saw and gives the quiz a new revision; the
 * next version keeps the topics, and its publications inherit them.
 */
public final class QuizTopics {

    public static final String SET = "QB_QUIZ_TOPICS_SET";

    /**
     * @param topics       the codes of the words, in the order shown; all of them (those left out are removed)
     * @param customTopics the custom words (for search only), all of them
     */
    public record TopicsInput(@NotNull UUID quizId,
        @NotNull @Size(max = TopicRules.HARD_MAX) List<@NotNull @Size(max = TopicRules.CODE_LENGTH) String> topics,
        @Size(max = TopicRules.HARD_MAX)
        List<@NotNull @Size(max = TopicRules.CUSTOM_HARD_MAX_LENGTH * 2) String> customTopics, Long baseRevision) {}

    public static ProcessDefinition<TopicsInput, EditOutput, ProcessContext> set() {
        return ProcessDefinition.define(SET, 1, TopicsInput.class, EditOutput.class, ProcessContext.class, pb -> {
            pb.description("Sets a quiz's topics: words of the word list, and custom words for search.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .actsOn(QbContent.QUIZ, "quizId")
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class));
            // The word list and the limits change nothing of the quiz: read before its lock.
            TopicSteps.loadParams(pb);
            TopicSteps.loadWords(pb, ctx -> ctx.get(INPUT, TopicsInput.class).topics());
            QuizEditing.lockAndLoad(pb);
            pb.compute("Set the topics", (metadata, ctx) -> set(ctx));
        });
    }

    static void set(ProcessContext ctx) {
        TopicsInput input = ctx.get(INPUT, TopicsInput.class);
        EntityInstance quiz = quiz(ctx);
        if (!QuizEditing.current(ctx, quiz, input.baseRevision())) {
            return;
        }
        List<String> custom = input.customTopics() == null ? List.of() : input.customTopics();
        TopicRules.customProblems("customTopics", custom, TopicSteps.customMaxLength(ctx)).forEach(ctx::reject);
        TopicRules.Topics before = TopicRules.Topics.of(quiz.get("topics"), quiz.get("customTopics"));
        TopicRules.Topics wanted = new TopicRules.Topics(TopicRules.codes(String.join(",", trimmed(input.topics()))),
            TopicRules.custom(TopicRules.customJson(custom)));
        TopicRules.choiceProblems("topics", wanted.codes(), before.codes(), TopicSteps.words(ctx))
            .forEach(ctx::reject);
        TopicRules.countProblems("topics", wanted, TopicSteps.maxPerQuiz(ctx)).forEach(ctx::reject);
        if (ctx.hasViolations()) {
            return;
        }
        if (wanted.equals(before)) {
            QuizEditing.unchanged(ctx, quiz, null, List.of());
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("topics", wanted.codesColumn());
        values.put("customTopics", wanted.customColumn());
        QuizEditing.touch(ctx, quiz, values, null, List.of());
    }

    private static List<String> trimmed(List<String> codes) {
        List<String> out = new ArrayList<>();
        codes.forEach(code -> out.add(code.strip()));
        return out;
    }

    private QuizTopics() {
    }
}

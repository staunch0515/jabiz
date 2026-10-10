package com.jabiz.quizbuks.content;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.EntityInstance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.quizbuks.content.QuizEditing.INPUT;
import static com.jabiz.quizbuks.content.QuizEditing.OPTIONS;
import static com.jabiz.quizbuks.content.QuizEditing.OUTPUT;
import static com.jabiz.quizbuks.content.QuizEditing.QUESTIONS;
import static com.jabiz.quizbuks.content.QuizEditing.decimal;
import static com.jabiz.quizbuks.content.QuizEditing.key;
import static com.jabiz.quizbuks.content.QuizEditing.list;
import static com.jabiz.quizbuks.content.QuizEditing.number;
import static com.jabiz.quizbuks.content.QuizEditing.quiz;
import static com.jabiz.quizbuks.content.QuizEditing.text;
import static com.jabiz.quizbuks.content.QuizEditing.uuid;

/**
 * The questions of a quiz and their options (M-24; docs/quizbuks/plans/Q3-content.md, D-Q3-4):
 * {@code QB_QUESTION_SAVE} saves one question with all its options, writing only what changed;
 * {@code QB_QUESTION_DELETE} deletes one; {@code QB_QUESTION_REORDER} puts them in a new order. A draft question may
 * be incomplete; making a version checks it.
 */
public final class QuestionProcesses {

    public static final String SAVE = "QB_QUESTION_SAVE";
    public static final String DELETE = "QB_QUESTION_DELETE";
    public static final String REORDER = "QB_QUESTION_REORDER";

    /** An option: an existing one by {@code optionId}, a new one without. Its place is its place in the list. */
    public record OptionInput(UUID optionId, String text, UUID image, Boolean correct) {}

    /**
     * One question with all its options; a new one (no {@code questionId}) goes to the end. Options left out are
     * deleted.
     *
     * @param points 1 when not given
     */
    public record QuestionInput(@NotNull UUID quizId, UUID questionId, String stem, UUID image, Integer points,
        @Size(max = ContentLimits.MAX_OPTIONS) List<@Valid @NotNull OptionInput> options, Long baseRevision) {}

    public record QuestionRef(@NotNull UUID quizId, @NotNull UUID questionId, Long baseRevision) {}

    /** Every question of the quiz, in the new order. */
    public record ReorderInput(@NotNull UUID quizId,
        @NotNull @Size(max = ContentLimits.MAX_QUESTIONS) List<@NotNull UUID> questionIds, Long baseRevision) {}

    public static ProcessDefinition<QuestionInput, EditOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, QuestionInput.class, EditOutput.class,
            ProcessContext.class, pb -> pb
                .description("Adds a question to a quiz or changes one, with its options.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the questions", QbContent.QUESTION, QUESTIONS))
                .steps(b -> QuizEditing.loadAll(b, "Load the options", QbContent.OPTION, OPTIONS))
                .compute("Save the question", (metadata, ctx) -> save(ctx)));
    }

    static void save(ProcessContext ctx) {
        QuestionInput input = ctx.get(INPUT, QuestionInput.class);
        EntityInstance quiz = quiz(ctx);
        if (!QuizEditing.current(ctx, quiz, input.baseRevision())) {
            return;
        }
        List<EntityInstance> questions = list(ctx, QUESTIONS);
        EntityInstance question = null;
        if (input.questionId() != null) {
            question = QuizEditing.find(questions, input.questionId());
            if (question == null) {
                ctx.reject(QuizEditing.notInQuiz("questionId", input.questionId()));
                return;
            }
        } else if (questions.size() >= ContentLimits.MAX_QUESTIONS) {
            ctx.reject(new Violation("questionId", ContentCodes.TOO_MANY_QUESTIONS, "A quiz has at most "
                + ContentLimits.MAX_QUESTIONS + " questions", Map.of("max", ContentLimits.MAX_QUESTIONS)));
            return;
        }
        List<OptionInput> options = input.options() == null ? List.of() : input.options();
        List<EntityInstance> existing = question == null ? List.of()
            : QuizEditing.groups(list(ctx, OPTIONS), "questionId").getOrDefault(key(question.id()), List.of());
        if (!QuizEditing.knownParts(ctx, options.stream().map(OptionInput::optionId).toList(), existing, "options", "optionId")) {
            return;
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("stem", text(input.stem()));
        values.put("image", input.image());
        values.put("points", decimal(input.points() == null ? 1 : input.points()));
        boolean changed = false;
        Object questionId;
        if (question == null) {
            values.put("quizId", quiz.id());
            values.put("ownerId", quiz.get("ownerId"));
            values.put("seq", decimal(QuizEditing.nextSeq(questions)));
            questionId = ctx.changes().insert(QbContent.QUESTION, values);
            changed = true;
        } else {
            questionId = question.id();
            Map<String, Object> differences = QuizEditing.differences(question, values);
            if (!differences.isEmpty()) {
                ctx.changes().update(QbContent.QUESTION, question.id(), question.version(), differences);
                changed = true;
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            OptionInput option = options.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", decimal(i + 1));
            row.put("text", text(option.text()));
            row.put("image", option.image());
            row.put("correct", Boolean.TRUE.equals(option.correct()));
            rows.add(row);
        }
        List<UUID> optionIds = new ArrayList<>();
        changed |= QuizEditing.saveParts(ctx, QbContent.OPTION, existing,
            options.stream().map(OptionInput::optionId).toList(), rows,
            Map.of("questionId", questionId, "quizId", quiz.id(), "ownerId", quiz.get("ownerId")), optionIds);

        if (!changed) {
            QuizEditing.unchanged(ctx, quiz, uuid(questionId), optionIds);
            return;
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        if (question == null) {
            counts.put("questionCount", decimal(questions.size() + 1));
        }
        QuizEditing.touch(ctx, quiz, counts, uuid(questionId), optionIds);
    }

    public static ProcessDefinition<QuestionRef, EditOutput, ProcessContext> delete() {
        return ProcessDefinition.define(DELETE, 1, QuestionRef.class, EditOutput.class,
            ProcessContext.class, pb -> pb
                .description("Deletes a question of a quiz with its options.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the questions", QbContent.QUESTION, QUESTIONS))
                .steps(b -> QuizEditing.loadAll(b, "Load the options", QbContent.OPTION, OPTIONS))
                .compute("Delete the question", (metadata, ctx) -> {
                    QuestionRef input = ctx.get(INPUT, QuestionRef.class);
                    EntityInstance quiz = quiz(ctx);
                    if (!QuizEditing.current(ctx, quiz, input.baseRevision())) {
                        return;
                    }
                    List<EntityInstance> questions = list(ctx, QUESTIONS);
                    EntityInstance question = QuizEditing.find(questions, input.questionId());
                    if (question == null) {
                        ctx.reject(QuizEditing.notInQuiz("questionId", input.questionId()));
                        return;
                    }
                    // Options first: a question still referred to cannot be deleted.
                    QuizEditing.groups(list(ctx, OPTIONS), "questionId").getOrDefault(key(question.id()), List.of())
                        .forEach(option -> ctx.changes().delete(QbContent.OPTION, option.id(), option.version()));
                    ctx.changes().delete(QbContent.QUESTION, question.id(), question.version());
                    QuizEditing.touch(ctx, quiz, Map.of("questionCount", decimal(questions.size() - 1)),
                        uuid(question.id()), List.of());
                }));
    }

    public static ProcessDefinition<ReorderInput, EditOutput, ProcessContext> reorder() {
        return ProcessDefinition.define(REORDER, 1, ReorderInput.class, EditOutput.class,
            ProcessContext.class, pb -> pb
                .description("Puts the questions of a quiz in a new order.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the questions", QbContent.QUESTION, QUESTIONS))
                .compute("Reorder the questions", (metadata, ctx) -> {
                    ReorderInput input = ctx.get(INPUT, ReorderInput.class);
                    QuizEditing.reorder(ctx, QbContent.QUESTION, list(ctx, QUESTIONS), input.questionIds(),
                        "questionIds", input.baseRevision());
                }));
    }

    private QuestionProcesses() {}
}

package com.jabiz.quizbuks.content;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.asset.AssetUses;
import com.jabiz.quizbuks.asset.QbAssets;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.jabiz.quizbuks.content.QuizEditing.INPUT;
import static com.jabiz.quizbuks.content.QuizEditing.OPTIONS;
import static com.jabiz.quizbuks.content.QuizEditing.OUTPUT;
import static com.jabiz.quizbuks.content.QuizEditing.QUESTIONS;
import static com.jabiz.quizbuks.content.QuizEditing.QUIZ_ID;
import static com.jabiz.quizbuks.content.QuizEditing.decimal;
import static com.jabiz.quizbuks.content.QuizEditing.key;
import static com.jabiz.quizbuks.content.QuizEditing.list;
import static com.jabiz.quizbuks.content.QuizEditing.number;
import static com.jabiz.quizbuks.content.QuizEditing.quiz;
import static com.jabiz.quizbuks.content.QuizEditing.uuid;

/**
 * F-S6 (docs/quizbuks/plans/impl/F-S6.md §3.3): editing a large bank without sending all of it.
 * {@code QB_QUESTION_MOVE} moves one question before another (or to the end), writing only its own place while there
 * is a gap ({@link Reorder#move}); {@code QB_QUESTION_DELETE_MANY} deletes up to {@value #MAX_BATCH} questions with
 * their options, translations and asset records; {@code QB_QUESTION_POINTS_SET} gives up to {@value #MAX_BATCH}
 * questions the same points. Each is one transaction under the quiz's lock, like the one-question processes.
 */
public final class QuestionBatchProcesses {

    public static final String MOVE = "QB_QUESTION_MOVE";
    public static final String DELETE_MANY = "QB_QUESTION_DELETE_MANY";
    public static final String POINTS_SET = "QB_QUESTION_POINTS_SET";

    /** The most questions one batch operation names. */
    public static final int MAX_BATCH = 200;

    /** @param beforeQuestionId the question it goes before; null puts it at the end */
    public record MoveInput(@NotNull UUID quizId, @NotNull UUID questionId, UUID beforeQuestionId,
        Long baseRevision) {}

    public record ManyInput(@NotNull UUID quizId, @NotEmpty @Size(max = MAX_BATCH) List<@NotNull UUID> questionIds,
        Long baseRevision) {}

    /** @param points within the field's range ({@code QB_QUESTION_POINTS_RANGE}, checked as the question is saved) */
    public record PointsInput(@NotNull UUID quizId, @NotEmpty @Size(max = MAX_BATCH) List<@NotNull UUID> questionIds,
        @NotNull Integer points, Long baseRevision) {}

    public static ProcessDefinition<MoveInput, EditOutput, ProcessContext> move() {
        return ProcessDefinition.define(MOVE, 1, MoveInput.class, EditOutput.class, ProcessContext.class, pb -> pb
            .description("Moves one question of a quiz before another, or to the end.")
            .permissions(QbPermissions.CONTENT_WRITE)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
            .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
            .steps(QuizEditing::lockAndLoad)
            .steps(b -> QuizEditing.loadAll(b, "Load the questions", QbContent.QUESTION, QUESTIONS))
            .compute("Move the question", (metadata, ctx) -> move(ctx)));
    }

    static void move(ProcessContext ctx) {
        MoveInput input = ctx.get(INPUT, MoveInput.class);
        EntityInstance quiz = quiz(ctx);
        if (!QuizEditing.current(ctx, quiz, input.baseRevision())) {
            return;
        }
        List<EntityInstance> questions = QuizEditing.ordered(list(ctx, QUESTIONS));
        if (QuizEditing.find(questions, input.questionId()) == null) {
            ctx.reject(QuizEditing.notInQuiz("questionId", input.questionId()));
            return;
        }
        if (input.beforeQuestionId() != null && QuizEditing.find(questions, input.beforeQuestionId()) == null) {
            ctx.reject(QuizEditing.notInQuiz("beforeQuestionId", input.beforeQuestionId()));
            return;
        }
        Map<String, Integer> places = new LinkedHashMap<>();
        questions.forEach(row -> places.put(key(row.id()), (int) number(row.get("seq"))));
        Map<String, Integer> moves = Reorder.move(places, key(input.questionId()),
            input.beforeQuestionId() == null ? null : key(input.beforeQuestionId()), ContentLimits.MAX_SEQ);
        moves.forEach((id, seq) -> {
            EntityInstance row = QuizEditing.find(questions, id);
            ctx.changes().update(QbContent.QUESTION, row.id(), row.version(), Map.of("seq", decimal(seq)));
        });
        if (moves.isEmpty()) {
            QuizEditing.unchanged(ctx, quiz, input.questionId(), List.of());
        } else {
            QuizEditing.touch(ctx, quiz, Map.of(), input.questionId(), List.of());
        }
    }

    public static ProcessDefinition<ManyInput, EditOutput, ProcessContext> deleteMany() {
        return ProcessDefinition.define(DELETE_MANY, 1, ManyInput.class, EditOutput.class, ProcessContext.class,
            pb -> pb
                .description("Deletes several questions of a quiz with their options.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the questions", QbContent.QUESTION, QUESTIONS))
                .steps(QuestionBatchProcesses::loadPartsOfTargets)
                .compute("Delete the questions", (metadata, ctx) -> deleteMany(ctx)));
    }

    static void deleteMany(ProcessContext ctx) {
        ManyInput input = ctx.get(INPUT, ManyInput.class);
        EntityInstance quiz = quiz(ctx);
        if (!QuizEditing.current(ctx, quiz, input.baseRevision())) {
            return;
        }
        List<EntityInstance> questions = list(ctx, QUESTIONS);
        List<EntityInstance> doomed = known(ctx, questions, input.questionIds());
        if (doomed == null) {
            return;
        }
        remove(ctx, doomed, QuizEditing.groups(list(ctx, OPTIONS), "questionId"));
        QuizEditing.touch(ctx, quiz, Map.of("questionCount", decimal(questions.size() - doomed.size())), null,
            List.of());
    }

    public static ProcessDefinition<PointsInput, EditOutput, ProcessContext> pointsSet() {
        return ProcessDefinition.define(POINTS_SET, 1, PointsInput.class, EditOutput.class, ProcessContext.class,
            pb -> pb
                .description("Gives several questions of a quiz the same points.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the questions", QbContent.QUESTION, QUESTIONS))
                .compute("Set the points", (metadata, ctx) -> pointsSet(ctx)));
    }

    static void pointsSet(ProcessContext ctx) {
        PointsInput input = ctx.get(INPUT, PointsInput.class);
        EntityInstance quiz = quiz(ctx);
        if (!QuizEditing.current(ctx, quiz, input.baseRevision())) {
            return;
        }
        if (QbContent.KIND_SURVEY.equals(quiz.get("kind"))) {
            ctx.reject(new Violation("points", ContentCodes.SURVEY_POINTS_FIXED, "A survey's questions are worth 1 "
                + "point each", Map.of()));
            return;
        }
        List<EntityInstance> chosen = known(ctx, list(ctx, QUESTIONS), input.questionIds());
        if (chosen == null) {
            return;
        }
        boolean changed = false;
        for (EntityInstance question : chosen) {
            if (number(question.get("points")) != input.points()) {
                ctx.changes().update(QbContent.QUESTION, question.id(), question.version(),
                    Map.of("points", decimal(input.points())));
                changed = true;
            }
        }
        if (changed) {
            QuizEditing.touch(ctx, quiz, Map.of(), null, List.of());
        } else {
            QuizEditing.unchanged(ctx, quiz, null, List.of());
        }
    }

    /** The named questions, each once; null (and refused) when one is not in this quiz. */
    private static List<EntityInstance> known(ProcessContext ctx, List<EntityInstance> questions, List<UUID> ids) {
        List<EntityInstance> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            EntityInstance question = QuizEditing.find(questions, ids.get(i));
            if (question == null) {
                ctx.reject(QuizEditing.notInQuiz("questionIds[" + i + "]", ids.get(i)));
            } else if (seen.add(key(question.id()))) {
                found.add(question);
            }
        }
        return ctx.hasViolations() ? null : found;
    }

    /**
     * The steps that load what removing the target questions takes: their options, their and their options'
     * translations, and where their images came from (F-C1b). The targets are the input's ({@link #targetIds}).
     */
    static <I, O> void loadPartsOfTargets(com.jabiz.process.ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load their options", QueryEntities.<ProcessContext>of(
            QbContent.defaultDataset(QbContent.OPTION), ctx -> EntityQuery.builder()
                .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("quizId", ctx.get(QUIZ_ID)),
                    new QueryPredicate.In("questionId", new ArrayList<>(targetIds(ctx))))))
                .limit(Integer.MAX_VALUE).build(), OPTIONS));
        TranslationProcesses.loadTranslationsOf(pb, ctx -> translationKeys(ctx, targetsIn(ctx, list(ctx, QUESTIONS))));
        AssetUses.load(pb, ctx -> List.of(), ctx -> places(ctx, targetsIn(ctx, list(ctx, QUESTIONS))));
    }

    /**
     * The questions the input is about, as text: those {@code QB_QUESTION_DELETE_MANY} names (whether or not in the
     * quiz), or the quiz's questions an import batch added ({@code QB_QUESTION_IMPORT_UNDO}).
     */
    static Set<String> targetIds(ProcessContext ctx) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        Object input = ctx.get(INPUT);
        if (input instanceof ManyInput many) {
            many.questionIds().forEach(id -> ids.add(key(id)));
        } else if (input instanceof QuestionImportProcesses.UndoInput undo) {
            String batch = key(undo.importId());
            list(ctx, QUESTIONS).stream().filter(q -> batch.equals(String.valueOf((Object) q.get("importId"))))
                .forEach(q -> ids.add(key(q.id())));
        }
        return ids;
    }

    /** The target questions among the quiz's. */
    static List<EntityInstance> targetsIn(ProcessContext ctx, List<EntityInstance> questions) {
        Set<String> ids = targetIds(ctx);
        return questions.stream().filter(q -> ids.contains(key(q.id()))).toList();
    }

    /** Removes the questions with their options, translations and image records (loaded by the steps above). */
    static void remove(ProcessContext ctx, List<EntityInstance> doomed, Map<String, List<EntityInstance>> options) {
        TranslationProcesses.deleteTranslationsOf(ctx, new HashSet<>(translationKeys(ctx, doomed)));
        List<EntityInstance> theirOptions = new ArrayList<>();
        doomed.forEach(q -> theirOptions.addAll(options.getOrDefault(key(q.id()), List.of())));
        // Options first: a question still referred to cannot be deleted.
        theirOptions.forEach(option -> ctx.changes().delete(QbContent.OPTION, option.id(), option.version()));
        doomed.forEach(question -> ctx.changes().delete(QbContent.QUESTION, question.id(), question.version()));
        AssetUses.forget(ctx, QbAssets.QUESTION_IMAGE, doomed.stream().map(EntityInstance::id).toList());
        AssetUses.forget(ctx, QbAssets.OPTION_IMAGE, theirOptions.stream().map(EntityInstance::id).toList());
    }

    /** The translation keys of the questions and of their options (as loaded). */
    private static List<String> translationKeys(ProcessContext ctx, List<EntityInstance> questions) {
        Map<String, List<EntityInstance>> options = QuizEditing.groups(list(ctx, OPTIONS), "questionId");
        List<String> keys = new ArrayList<>();
        for (EntityInstance question : questions) {
            keys.addAll(ContentLanguages.keysOf(question.id(), options.getOrDefault(key(question.id()), List.of())
                .stream().map(EntityInstance::id).toList()));
        }
        return keys;
    }

    private static List<AssetUses.Place> places(ProcessContext ctx, List<EntityInstance> questions) {
        List<AssetUses.Place> places = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        questions.forEach(q -> {
            ids.add(key(q.id()));
            places.add(new AssetUses.Place(QbAssets.QUESTION_IMAGE, q.id()));
        });
        list(ctx, OPTIONS).stream().filter(o -> ids.contains(key(o.get("questionId"))))
            .forEach(o -> places.add(new AssetUses.Place(QbAssets.OPTION_IMAGE, o.id())));
        return places;
    }

    private QuestionBatchProcesses() {}
}

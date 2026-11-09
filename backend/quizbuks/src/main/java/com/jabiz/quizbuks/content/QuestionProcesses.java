package com.jabiz.quizbuks.content;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.asset.AssetUses;
import com.jabiz.quizbuks.asset.QbAssets;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadParams;
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
import static com.jabiz.quizbuks.content.QuizEditing.list;
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

    /** F-S6: the bank's limits as read by a process ({@link QuizBankLimits}). */
    static final String LIMITS = "bankLimits";

    /**
     * An option: an existing one by {@code optionId}, a new one without. Its place is its place in the list.
     * {@code imageAssetId} (F-C1b): the image asset of the sponsor's library its image was copied from; none leaves
     * the record of an existing option as it is.
     */
    public record OptionInput(UUID optionId, String text, UUID image, Boolean correct, UUID imageAssetId) {

        /** An option as before F-C1b: its image not from the asset library. */
        public OptionInput(UUID optionId, String text, UUID image, Boolean correct) {
            this(optionId, text, image, correct, null);
        }
    }

    /**
     * One question with its options; a new one (no {@code questionId}) goes to the end. The sponsor console saves a
     * question together with its options, so {@code options} is the whole list: options left out of it are deleted,
     * and an empty list deletes them all. Without {@code options} (null) the options stay as they are.
     *
     * @param points    1 when not given; a survey's question is always 1 (Q12, D-Q12-3)
     * @param selection a survey's question only: {@code SINGLE} or {@code MULTIPLE} (may be missing in a draft);
     *                  a survey's options are never correct, whatever {@code correct} says
     * @param imageAssetId F-C1b: the image asset of the sponsor's library the question's image was copied from;
     *                  none leaves the record of an existing question as it is
     */
    public record QuestionInput(@NotNull UUID quizId, UUID questionId, String stem, UUID image, Integer points,
        @Size(max = ContentLimits.MAX_OPTIONS) List<@Valid @NotNull OptionInput> options, Long baseRevision,
        String selection, UUID imageAssetId) {

        /** A question as before F-C1b: its image not from the asset library. */
        public QuestionInput(UUID quizId, UUID questionId, String stem, UUID image, Integer points,
            List<OptionInput> options, Long baseRevision, String selection) {
            this(quizId, questionId, stem, image, points, options, baseRevision, selection, null);
        }
    }

    public record QuestionRef(@NotNull UUID quizId, @NotNull UUID questionId, Long baseRevision) {}

    /** Every question of the quiz, in the new order. */
    public record ReorderInput(@NotNull UUID quizId,
        @NotNull @Size(max = ContentLimits.MAX_QUESTIONS_TECH) List<@NotNull UUID> questionIds, Long baseRevision) {}

    public static ProcessDefinition<QuestionInput, EditOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, QuestionInput.class, EditOutput.class,
            ProcessContext.class, pb -> pb
                .description("Adds a question to a quiz or changes one, with its options.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the questions", QbContent.QUESTION, QUESTIONS))
                // F-S6: the bank's limit, as in effect now.
                .step("Load the bank's limits", LoadParams.<ProcessContext>optional(ProcessContext::opTime, LIMITS,
                    QuizBankLimits.PARAMS))
                .steps(b -> QuizEditing.loadPartsOf(b, "Load its options", QbContent.OPTION, "questionId",
                    ctx -> ctx.get(INPUT, QuestionInput.class).questionId(), OPTIONS))
                // Q73a: an option taken out takes its translations with it.
                .steps(b -> TranslationProcesses.loadTranslationsOf(b,
                    ctx -> keysOf(ctx.get(INPUT, QuestionInput.class).questionId(), ctx)))
                .steps(b -> QuizEditing.loadFiles(b, ctx -> {
                    QuestionInput input = ctx.get(INPUT, QuestionInput.class);
                    List<UUID> files = new ArrayList<>();
                    files.add(input.image());
                    if (input.options() != null) {
                        input.options().forEach(option -> files.add(option.image()));
                    }
                    return files;
                }))
                // F-C1b: the image assets the images were copied from, after the quiz's lock.
                .steps(b -> AssetUses.load(b, QuestionProcesses::assetsPicked, QuestionProcesses::placesTouched))
                .compute("Check the assets picked", (metadata, ctx) -> AssetUses.check(ctx, picks(ctx)))
                .compute("Save the question", (metadata, ctx) -> save(ctx)));
    }

    /** The refusal of a question more than the bank's limit {@code max} (F-S6). */
    static Violation tooMany(int max) {
        return new Violation("questionId", ContentCodes.TOO_MANY_QUESTIONS, "A quiz has at most " + max
            + " questions", Map.of("max", max));
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
        } else if (questions.size() >= QuizBankLimits.maxQuestions(ctx.get(LIMITS, ParamValues.class))) {
            ctx.reject(tooMany(QuizBankLimits.maxQuestions(ctx.get(LIMITS, ParamValues.class))));
            return;
        }
        // Loaded by the input's questionId: only when that question is in this quiz are they its options.
        List<EntityInstance> existing = question == null ? List.of() : list(ctx, OPTIONS);
        List<OptionInput> options = input.options();
        if (options != null && !QuizEditing.knownParts(ctx, options.stream().map(OptionInput::optionId).toList(),
            existing, "options", "optionId")) {
            return;
        }
        Object owner = quiz.get("ownerId");
        QuizEditing.requireOwnFile(ctx, owner, "image", input.image(), question == null ? null : question.get("image"));
        if (options != null) {
            for (int i = 0; i < options.size(); i++) {
                EntityInstance part = options.get(i).optionId() == null ? null
                    : QuizEditing.find(existing, options.get(i).optionId());
                QuizEditing.requireOwnFile(ctx, owner, "options[" + i + "].image", options.get(i).image(),
                    part == null ? null : part.get("image"));
            }
        }
        boolean survey = QbContent.KIND_SURVEY.equals(quiz.get("kind"));
        if (!survey && input.selection() != null) {
            ctx.reject(new Violation("selection", ContentCodes.SELECTION_NOT_ALLOWED, "Only a survey's questions are "
                + "single or multiple choice", Map.of()));
        }
        if (ctx.hasViolations()) {
            return;
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("stem", text(input.stem()));
        values.put("image", input.image());
        values.put("points", decimal(survey || input.points() == null ? 1 : input.points()));
        values.put("selection", survey ? input.selection() : null);
        boolean changed = false;
        Object questionId;
        if (question == null) {
            values.put("quizId", quiz.id());
            values.put("ownerId", quiz.get("ownerId"));
            values.put("seq", decimal(QuizEditing.nextSeq(ctx, QbContent.QUESTION, questions)));
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

        if (options != null) {
            java.util.Set<String> kept = new java.util.HashSet<>();
            options.forEach(option -> kept.add(String.valueOf(option.optionId())));
            java.util.Set<String> gone = new java.util.HashSet<>();
            existing.stream().filter(option -> !kept.contains(QuizEditing.key(option.id()))).forEach(option -> {
                gone.add(ContentLanguages.optionText(option.id()));
                gone.add(ContentLanguages.optionImage(option.id()));
            });
            TranslationProcesses.deleteTranslationsOf(ctx, gone);
        }
        AssetUses.record(ctx, QbAssets.QUESTION_IMAGE, questionId, input.imageAssetId());
        List<UUID> optionIds = new ArrayList<>();
        if (options == null) {
            QuizEditing.ordered(existing).forEach(option -> optionIds.add(uuid(option.id())));
        } else {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < options.size(); i++) {
                OptionInput option = options.get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("seq", decimal(i + 1));
                row.put("text", text(option.text()));
                row.put("image", option.image());
                row.put("correct", !survey && Boolean.TRUE.equals(option.correct()));
                rows.add(row);
            }
            changed |= QuizEditing.saveParts(ctx, QbContent.OPTION, existing,
                options.stream().map(OptionInput::optionId).toList(), rows,
                Map.of("questionId", questionId, "quizId", quiz.id(), "ownerId", owner), optionIds);
            // F-C1b: the options taken out take the records of their images with them; the keys are in the input's
            // order.
            AssetUses.forget(ctx, QbAssets.OPTION_IMAGE, removedOptions(options, existing));
            for (int i = 0; i < options.size(); i++) {
                AssetUses.record(ctx, QbAssets.OPTION_IMAGE, optionIds.get(i), options.get(i).imageAssetId());
            }
        }

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
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .actsOn(QbContent.QUESTION, "questionId")
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the questions", QbContent.QUESTION, QUESTIONS))
                .steps(b -> QuizEditing.loadPartsOf(b, "Load its options", QbContent.OPTION, "questionId",
                    ctx -> ctx.get(INPUT, QuestionRef.class).questionId(), OPTIONS))
                .steps(b -> TranslationProcesses.loadTranslationsOf(b,
                    ctx -> keysOf(ctx.get(INPUT, QuestionRef.class).questionId(), ctx)))
                // F-C1b: where its images were filled from the asset library goes with it.
                .steps(b -> AssetUses.load(b, ctx -> List.of(), ctx -> {
                    UUID id = ctx.get(INPUT, QuestionRef.class).questionId();
                    if (QuizEditing.find(list(ctx, QUESTIONS), id) == null) {
                        return List.of();
                    }
                    List<AssetUses.Place> places = new ArrayList<>();
                    places.add(new AssetUses.Place(QbAssets.QUESTION_IMAGE, id));
                    list(ctx, OPTIONS).forEach(o -> places.add(new AssetUses.Place(QbAssets.OPTION_IMAGE, o.id())));
                    return places;
                }))
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
                    // Q73a: its translations and its options' go with it.
                    TranslationProcesses.deleteTranslationsOf(ctx,
                        new java.util.HashSet<>(keysOf(input.questionId(), ctx)));
                    // Options first: a question still referred to cannot be deleted.
                    list(ctx, OPTIONS).forEach(option -> ctx.changes().delete(QbContent.OPTION, option.id(),
                        option.version()));
                    ctx.changes().delete(QbContent.QUESTION, question.id(), question.version());
                    AssetUses.forget(ctx, QbAssets.QUESTION_IMAGE, List.of(question.id()));
                    AssetUses.forget(ctx, QbAssets.OPTION_IMAGE, list(ctx, OPTIONS).stream().map(EntityInstance::id)
                        .toList());
                    QuizEditing.touch(ctx, quiz, Map.of("questionCount", decimal(questions.size() - 1)),
                        uuid(question.id()), List.of());
                }));
    }

    public static ProcessDefinition<ReorderInput, EditOutput, ProcessContext> reorder() {
        return ProcessDefinition.define(REORDER, 1, ReorderInput.class, EditOutput.class,
            ProcessContext.class, pb -> pb
                .description("Puts the questions of a quiz in a new order.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
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

    /** F-C1b: the image assets the input picks. */
    private static List<UUID> assetsPicked(ProcessContext ctx) {
        return picks(ctx).stream().map(AssetUses.Pick::assetId).filter(java.util.Objects::nonNull).toList();
    }

    private static List<AssetUses.Pick> picks(ProcessContext ctx) {
        QuestionInput input = ctx.get(INPUT, QuestionInput.class);
        List<AssetUses.Pick> picks = new ArrayList<>();
        picks.add(new AssetUses.Pick("imageAssetId", QbAssets.QUESTION_IMAGE, null, input.imageAssetId()));
        if (input.options() != null) {
            for (int i = 0; i < input.options().size(); i++) {
                picks.add(new AssetUses.Pick("options[" + i + "].imageAssetId", QbAssets.OPTION_IMAGE, null,
                    input.options().get(i).imageAssetId()));
            }
        }
        return picks;
    }

    /**
     * F-C1b: the existing places whose records the save may change: the question and the options given an asset, and
     * the options taken out. Only of a question of this quiz (the options are loaded only for one).
     */
    private static List<AssetUses.Place> placesTouched(ProcessContext ctx) {
        QuestionInput input = ctx.get(INPUT, QuestionInput.class);
        if (input.questionId() == null || QuizEditing.find(list(ctx, QUESTIONS), input.questionId()) == null) {
            return List.of();
        }
        List<AssetUses.Place> places = new ArrayList<>();
        if (input.imageAssetId() != null) {
            places.add(new AssetUses.Place(QbAssets.QUESTION_IMAGE, input.questionId()));
        }
        if (input.options() != null) {
            input.options().stream().filter(o -> o.optionId() != null && o.imageAssetId() != null)
                .forEach(o -> places.add(new AssetUses.Place(QbAssets.OPTION_IMAGE, o.optionId())));
            removedOptions(input.options(), list(ctx, OPTIONS))
                .forEach(id -> places.add(new AssetUses.Place(QbAssets.OPTION_IMAGE, id)));
        }
        return places;
    }

    /** The keys of the existing options the list leaves out. */
    private static List<Object> removedOptions(List<OptionInput> options, List<EntityInstance> existing) {
        java.util.Set<String> kept = new java.util.HashSet<>();
        options.forEach(option -> kept.add(String.valueOf(option.optionId())));
        return existing.stream().filter(option -> !kept.contains(QuizEditing.key(option.id())))
            .map(EntityInstance::id).toList();
    }

    /** The translation keys of the question and of its options as loaded (none without a question). */
    private static List<String> keysOf(UUID questionId, ProcessContext ctx) {
        return questionId == null ? List.of()
            : ContentLanguages.keysOf(questionId, list(ctx, OPTIONS).stream().map(EntityInstance::id).toList());
    }

    private QuestionProcesses() {}
}

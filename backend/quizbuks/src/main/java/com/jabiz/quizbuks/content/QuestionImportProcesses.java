package com.jabiz.quizbuks.content;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.transfer.QuizFile;
import com.jabiz.quizbuks.content.transfer.QuizTransferProcesses;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.quizbuks.content.QuizEditing.INPUT;
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
 * F-S6 (docs/quizbuks/plans/impl/F-S6.md §3.4): a large bank imported in batches. {@code QB_QUIZ_IMPORT_QUESTIONS}
 * adds one batch of a quiz file's questions (no materials) to the end of an existing quiz, at most
 * {@code qb.content.import.batch-size} at a time and never past {@code qb.content.max-questions}; each batch is its own
 * transaction, so a batch refused leaves those imported before it. {@code QB_QUESTION_IMPORT_UNDO} removes what a
 * batch added and was not changed since ({@link ImportHash}), once.
 */
public final class QuestionImportProcesses {

    public static final String IMPORT = "QB_QUIZ_IMPORT_QUESTIONS";
    public static final String UNDO = "QB_QUESTION_IMPORT_UNDO";

    private static final String LIMITS = "bankLimits";
    private static final String BATCH = "importBatch";

    /**
     * @param document a quiz file (Q16's format) holding only questions (and their translations); its title and
     *                 other heading are not used
     * @param files    the key of each file its questions use, as uploaded by the caller
     */
    public record ImportInput(@NotNull UUID quizId, @NotNull Map<String, Object> document, Map<String, UUID> files,
        Long baseRevision) {}

    /** @param skippedLanguages languages of the file's translations the quiz has not (not imported) */
    public record ImportOutput(UUID quizId, UUID importId, int questions, long revision,
        List<String> skippedLanguages) {}

    public record UndoInput(@NotNull UUID quizId, @NotNull UUID importId, Long baseRevision) {}

    /** @param kept the batch's questions changed since, which stay */
    public record UndoOutput(UUID quizId, long revision, int removed, List<UUID> kept) {}

    public static ProcessDefinition<ImportInput, ImportOutput, ProcessContext> importQuestions() {
        return ProcessDefinition.define(IMPORT, 1, ImportInput.class, ImportOutput.class, ProcessContext.class,
            pb -> pb
                .description("Imports a batch of questions from a quiz file into an existing quiz.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, ImportOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the questions", QbContent.QUESTION, QUESTIONS))
                .step("Load the bank's limits", LoadParams.<ProcessContext>optional(ProcessContext::opTime, LIMITS,
                    QuizBankLimits.PARAMS))
                .steps(b -> QuizEditing.loadFiles(b, ctx -> {
                    Map<String, UUID> files = ctx.get(INPUT, ImportInput.class).files();
                    return files == null ? List.of() : List.copyOf(files.values());
                }))
                .compute("Import the questions", (metadata, ctx) -> importQuestions(ctx)));
    }

    static void importQuestions(ProcessContext ctx) {
        ImportInput input = ctx.get(INPUT, ImportInput.class);
        EntityInstance quiz = quiz(ctx);
        if (!QuizEditing.current(ctx, quiz, input.baseRevision())) {
            return;
        }
        ParamValues limits = ctx.get(LIMITS, ParamValues.class);
        Object given = input.document().get("questions");
        int batch = QuizBankLimits.importBatch(limits);
        if (given instanceof List<?> questions && questions.size() > batch) {
            ctx.reject(new Violation("document.questions", ContentCodes.IMPORT_BATCH_TOO_LARGE, "One import batch "
                + "has at most " + batch + " questions", Map.of("max", batch)));
            return;
        }
        QuizFile file = QuizTransferProcesses.read(ctx, input.document(), batch);
        if (file == null) {
            return;
        }
        boolean materials = !file.materials().isEmpty()
            || file.translations().values().stream().anyMatch(t -> !t.materials().isEmpty());
        if (materials) {
            ctx.reject(new Violation("document.materials", ContentCodes.IMPORT_QUESTIONS_ONLY, "Only questions are "
                + "imported into a quiz; import a whole quiz as a new one", Map.of()));
        }
        if (!String.valueOf((Object) quiz.get("kind")).equals(file.kind())) {
            ctx.reject(new Violation("document.quiz.kind", ContentCodes.QUIZ_KIND_FIXED, "The file's questions are "
                + "of a " + file.kind() + ", the quiz is a " + quiz.get("kind"), Map.of()));
        }
        Object language = quiz.get("language");
        if (file.language() != null && language != null && !file.language().equals(language)) {
            ctx.reject(new Violation("document.quiz.language", ContentCodes.IMPORT_LANGUAGE, "The file's questions "
                + "are in " + file.language() + ", the quiz is in " + language,
                Map.of("file", file.language(), "quiz", String.valueOf(language))));
        }
        List<EntityInstance> questions = list(ctx, QUESTIONS);
        int max = QuizBankLimits.maxQuestions(limits);
        if (questions.size() + file.questions().size() > max) {
            ctx.reject(QuestionProcesses.tooMany(max));
        }
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, UUID> ids = QuizTransferProcesses.uploaded(ctx, file, input.files());
        if (ids == null) {
            return;
        }
        QuizSnapshot content = QuizTransferProcesses.snapshot(file, ids);
        int offset = endOf(ctx, questions, content.questions().size());

        String owner = ctx.request().actorId();
        Map<String, Object> batchRow = new LinkedHashMap<>();
        batchRow.put("quizId", key(quiz.id()));
        batchRow.put("ownerId", owner);
        batchRow.put("questionCount", decimal(content.questions().size()));
        batchRow.put("importedAt", ctx.opTime());
        Object importId = ctx.changes().insert(QbContent.QUESTION_IMPORT, batchRow);
        ctx.put(BATCH, importId);

        Map<Integer, Object> questionIds = new LinkedHashMap<>();
        Map<String, Object> optionIds = new LinkedHashMap<>();
        QuizProcesses.insertQuestions(ctx, quiz.id(), owner, content.questions(), offset,
            question -> Map.of("importId", key(importId), "importHash", ImportHash.of(question)), questionIds,
            optionIds);
        List<String> languages = ContentLanguages.parseList(quiz.get("translationLanguages"));
        List<String> skipped = new ArrayList<>();
        content.translations().forEach((lang, t) -> {
            if (languages.contains(lang)) {
                QuizProcesses.insertQuestionTranslations(ctx, quiz.id(), owner, lang, content.questions(), t,
                    file.translations().get(lang).hashes(), questionIds, optionIds);
            } else {
                skipped.add(lang);
            }
        });
        QuizEditing.touch(ctx, quiz, Map.of("questionCount", decimal(questions.size() + content.questions().size())),
            null, List.of());
        EditOutput edited = ctx.get(OUTPUT, EditOutput.class);
        ctx.put(OUTPUT, new ImportOutput(uuid(quiz.id()), uuid(importId), content.questions().size(),
            edited.revision(), List.copyOf(skipped)));
    }

    /**
     * The place after which the batch goes (its questions at that plus 1..{@code count}); when they would not fit the
     * column, the quiz's questions are numbered 1..n again first.
     */
    private static int endOf(ProcessContext ctx, List<EntityInstance> questions, int count) {
        List<EntityInstance> ordered = QuizEditing.ordered(questions);
        int last = ordered.isEmpty() ? 0 : (int) number(ordered.getLast().get("seq"));
        if (last + count <= ContentLimits.MAX_SEQ) {
            return last;
        }
        for (int i = 0; i < ordered.size(); i++) {
            EntityInstance row = ordered.get(i);
            if (number(row.get("seq")) != i + 1) {
                ctx.changes().update(QbContent.QUESTION, row.id(), row.version(), Map.of("seq", decimal(i + 1)));
            }
        }
        return ordered.size();
    }

    public static ProcessDefinition<UndoInput, UndoOutput, ProcessContext> undo() {
        return ProcessDefinition.define(UNDO, 1, UndoInput.class, UndoOutput.class, ProcessContext.class, pb -> pb
            .description("Removes the questions an import batch added and that were not changed since.")
            .permissions(QbPermissions.CONTENT_WRITE)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
            .outputMapper(ctx -> ctx.get(OUTPUT, UndoOutput.class))
            .steps(QuizEditing::lockAndLoad)
            .steps(b -> QuizEditing.loadAll(b, "Load the questions", QbContent.QUESTION, QUESTIONS))
            .step("Load the batch", QueryEntities.<ProcessContext>of(
                QbContent.defaultDataset(QbContent.QUESTION_IMPORT), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("importId", key(ctx.get(INPUT, UndoInput.class).importId())),
                        new QueryPredicate.Eq("quizId", key(ctx.get(QUIZ_ID)))))).limit(1).build(), BATCH))
            .steps(QuestionBatchProcesses::loadPartsOfTargets)
            .compute("Undo the batch", (metadata, ctx) -> undo(ctx)));
    }

    static void undo(ProcessContext ctx) {
        UndoInput input = ctx.get(INPUT, UndoInput.class);
        EntityInstance quiz = quiz(ctx);
        if (!QuizEditing.current(ctx, quiz, input.baseRevision())) {
            return;
        }
        List<EntityInstance> batches = list(ctx, BATCH);
        if (batches.isEmpty()) {
            ctx.reject(QuizEditing.notInQuiz("importId", input.importId()));
            return;
        }
        EntityInstance batch = batches.getFirst();
        if (batch.get("undoneAt") != null) {
            ctx.reject(new Violation("importId", ContentCodes.IMPORT_UNDONE, "This import was undone already",
                Map.of()));
            return;
        }
        Map<String, List<EntityInstance>> options = QuizEditing.groups(list(ctx, QuizEditing.OPTIONS), "questionId");
        List<EntityInstance> questions = list(ctx, QUESTIONS);
        List<EntityInstance> unchanged = new ArrayList<>();
        List<UUID> kept = new ArrayList<>();
        for (EntityInstance question : QuizEditing.ordered(QuestionBatchProcesses.targetsIn(ctx, questions))) {
            String now = ImportHash.of(question, options.getOrDefault(key(question.id()), List.of()));
            if (now.equals(question.get("importHash"))) {
                unchanged.add(question);
            } else {
                kept.add(uuid(question.id()));
            }
        }
        QuestionBatchProcesses.remove(ctx, unchanged, options);
        ctx.changes().update(QbContent.QUESTION_IMPORT, batch.id(), batch.version(), Map.of("undoneAt",
            ctx.opTime(), "removedCount", decimal(unchanged.size())));
        QuizEditing.touch(ctx, quiz, Map.of("questionCount", decimal(questions.size() - unchanged.size())), null,
            List.of());
        EditOutput edited = ctx.get(OUTPUT, EditOutput.class);
        ctx.put(OUTPUT, new UndoOutput(uuid(quiz.id()), edited.revision(), unchanged.size(), List.copyOf(kept)));
    }

    private QuestionImportProcesses() {}
}

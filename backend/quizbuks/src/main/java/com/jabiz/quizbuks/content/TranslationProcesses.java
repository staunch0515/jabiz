package com.jabiz.quizbuks.content;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.jabiz.quizbuks.content.QuizEditing.IMAGES;
import static com.jabiz.quizbuks.content.QuizEditing.INPUT;
import static com.jabiz.quizbuks.content.QuizEditing.MATERIALS;
import static com.jabiz.quizbuks.content.QuizEditing.OPTIONS;
import static com.jabiz.quizbuks.content.QuizEditing.OUTPUT;
import static com.jabiz.quizbuks.content.QuizEditing.QUESTIONS;
import static com.jabiz.quizbuks.content.QuizEditing.QUIZ_ID;
import static com.jabiz.quizbuks.content.QuizEditing.list;
import static com.jabiz.quizbuks.content.QuizEditing.quiz;

/**
 * A quiz's other languages (Q73a, docs/quizbuks/plans/Q73-content-languages.md §2.2):
 * <ul>
 *   <li>{@code QB_QUIZ_LANGUAGES_SET}: which of the platform's languages the quiz has besides its base; a language
 *       taken away takes its translations and materials with it;</li>
 *   <li>{@code QB_TRANSLATION_SAVE}: some elements' text or own image in one of them, each recording the hash of the
 *       base text it translates.</li>
 * </ul>
 * Like every content process they take the quiz's lock before reading it, check the revision the caller saw, and
 * give the quiz a new revision. The structure (questions, options, correct answers, points) stays the base's alone.
 */
public final class TranslationProcesses {

    public static final String LANGUAGES_SET = "QB_QUIZ_LANGUAGES_SET";
    public static final String SAVE = "QB_TRANSLATION_SAVE";

    static final String TRANSLATIONS = "translations";

    /** The quiz's other languages, all of them: those left out are removed with what they have. */
    public record LanguagesInput(@NotNull UUID quizId,
        @NotNull @Size(max = ContentLanguages.MAX_LANGUAGES - 1) List<@NotNull @Pattern(regexp = "ja|zh|en") String>
            languages, Long baseRevision) {}

    /**
     * One element's translation: {@code text} for a text, {@code image} for a question's or an option's own image;
     * neither removes the translation (a text then is missing again, an image is the base's again).
     */
    public record Entry(@NotNull @Size(max = ContentLanguages.KEY_LENGTH) String key, String text, UUID image) {}

    /** Some elements of the quiz in one of its other languages. */
    public record SaveInput(@NotNull UUID quizId, @NotNull @Pattern(regexp = "ja|zh|en") String language,
        @NotNull @Size(min = 1, max = ContentLanguages.MAX_TRANSLATION_ROWS / (ContentLanguages.MAX_LANGUAGES - 1))
        List<@Valid @NotNull Entry> entries, Long baseRevision) {}

    // ---- languages ---------------------------------------------------------------------------------------------

    public static ProcessDefinition<LanguagesInput, EditOutput, ProcessContext> setLanguages() {
        return ProcessDefinition.define(LANGUAGES_SET, 1, LanguagesInput.class, EditOutput.class,
            ProcessContext.class, pb -> pb
                .description("Sets the languages a quiz has besides its base language.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .actsOn(QbContent.QUIZ, "quizId")
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the translations", QbContent.TRANSLATION, TRANSLATIONS))
                .steps(b -> QuizEditing.loadAll(b, "Load the materials", QbContent.MATERIAL, MATERIALS))
                .steps(b -> QuizEditing.loadAll(b, "Load the images", QbContent.MATERIAL_IMAGE, IMAGES))
                .compute("Set the languages", (metadata, ctx) -> setLanguages(ctx)));
    }

    static void setLanguages(ProcessContext ctx) {
        LanguagesInput input = ctx.get(INPUT, LanguagesInput.class);
        EntityInstance quiz = quiz(ctx);
        if (!QuizEditing.current(ctx, quiz, input.baseRevision())) {
            return;
        }
        List<String> wanted = new ArrayList<>();
        for (String language : input.languages()) {
            if (language.equals(quiz.get("language"))) {
                ctx.reject(new Violation("languages", ContentCodes.LANGUAGE_IS_BASE, "The base language is not one of "
                    + "the quiz's other languages", Map.of("language", language)));
                return;
            }
            if (!wanted.contains(language)) {
                wanted.add(language);
            }
        }
        // In the platform's order, so the column says one thing for one set.
        List<String> ordered = QbContent.LANGUAGES.stream().filter(wanted::contains).toList();
        List<String> present = ContentLanguages.parseList(quiz.get("translationLanguages"));
        if (ordered.equals(present)) {
            QuizEditing.unchanged(ctx, quiz, null, List.of());
            return;
        }
        Set<String> gone = new HashSet<>(present);
        gone.removeAll(ordered);
        for (EntityInstance row : list(ctx, TRANSLATIONS)) {
            if (gone.contains(row.<String>get("language"))) {
                ctx.changes().delete(QbContent.TRANSLATION, row.id(), row.version());
            }
        }
        deleteVariants(ctx, list(ctx, MATERIALS).stream()
            .filter(m -> m.get("variantOf") != null && gone.contains(m.<String>get("language"))).toList());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("translationLanguages", ContentLanguages.joinList(ordered));
        QuizEditing.touch(ctx, quiz, values, null, List.of());
    }

    /** Deletes materials of other languages with their images (images first: a material referred to stays). */
    static void deleteVariants(ProcessContext ctx, List<EntityInstance> variants) {
        Set<String> ids = new HashSet<>();
        variants.forEach(v -> ids.add(QuizEditing.key(v.id())));
        for (EntityInstance image : list(ctx, IMAGES)) {
            if (ids.contains(QuizEditing.key(image.get("materialId")))) {
                ctx.changes().delete(QbContent.MATERIAL_IMAGE, image.id(), image.version());
            }
        }
        variants.forEach(v -> ctx.changes().delete(QbContent.MATERIAL, v.id(), v.version()));
    }

    // ---- translations ------------------------------------------------------------------------------------------

    public static ProcessDefinition<SaveInput, EditOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, SaveInput.class, EditOutput.class, ProcessContext.class,
            pb -> pb
                .description("Saves the translation of some of a quiz's text into one of its other languages.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .actsOn(QbContent.QUIZ, "quizId")
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the questions", QbContent.QUESTION, QUESTIONS))
                .steps(b -> QuizEditing.loadAll(b, "Load the options", QbContent.OPTION, OPTIONS))
                .step("Load the language's translations", QueryEntities.<ProcessContext>of(
                    QbContent.defaultDataset(QbContent.TRANSLATION), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("quizId", ctx.get(QUIZ_ID)),
                            new QueryPredicate.Eq("language", ctx.get(INPUT, SaveInput.class).language()))))
                        .limit(Integer.MAX_VALUE).build(), TRANSLATIONS))
                .steps(b -> QuizEditing.loadFiles(b, ctx -> ctx.get(INPUT, SaveInput.class).entries().stream()
                    .map(Entry::image).toList()))
                .compute("Save the translations", (metadata, ctx) -> save(ctx)));
    }

    static void save(ProcessContext ctx) {
        SaveInput input = ctx.get(INPUT, SaveInput.class);
        EntityInstance quiz = quiz(ctx);
        if (!QuizEditing.current(ctx, quiz, input.baseRevision())) {
            return;
        }
        if (!ContentLanguages.parseList(quiz.get("translationLanguages")).contains(input.language())) {
            ctx.reject(new Violation("language", ContentCodes.TRANSLATION_LANGUAGE, "The quiz has no language "
                + input.language() + " besides its base", Map.of("language", input.language())));
            return;
        }
        Map<String, String> sources = sources(quiz, list(ctx, QUESTIONS), list(ctx, OPTIONS));
        Object owner = quiz.get("ownerId");
        Map<String, EntityInstance> existing = new LinkedHashMap<>();
        list(ctx, TRANSLATIONS).forEach(row -> existing.put(row.get("elementKey"), row));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < input.entries().size(); i++) {
            Entry entry = input.entries().get(i);
            String path = "entries[" + i + "]";
            var element = ContentLanguages.parse(entry.key());
            if (element.isEmpty() || !sources.containsKey(entry.key()) || !seen.add(entry.key())) {
                ctx.reject(new Violation(path + ".key", ContentCodes.TRANSLATION_KEY, "No element " + entry.key()
                    + " to translate in the quiz", Map.of("key", String.valueOf(entry.key()))));
                continue;
            }
            String text = QuizEditing.text(entry.text());
            if (element.get().text()) {
                if (entry.image() != null) {
                    ctx.reject(new Violation(path + ".image", ContentCodes.TRANSLATION_FIELD, "A text has no image"));
                } else if (text != null && QuizEditing.text(sources.get(entry.key())) == null) {
                    ctx.reject(new Violation(path + ".text", ContentCodes.TRANSLATION_NO_SOURCE, "The base has no "
                        + "text at " + entry.key(), Map.of("key", entry.key())));
                } else if (text != null && text.length() > element.get().maxLength()) {
                    ctx.reject(new Violation(path + ".text", ContentCodes.TRANSLATION_TOO_LONG, "The translation is "
                        + text.length() + " characters, more than " + element.get().maxLength(), Map.of("length",
                        text.length(), "max", element.get().maxLength())));
                }
            } else if (text != null) {
                ctx.reject(new Violation(path + ".text", ContentCodes.TRANSLATION_FIELD, "An image has no text"));
            } else {
                EntityInstance row = existing.get(entry.key());
                QuizEditing.requireOwnFile(ctx, owner, path + ".image", entry.image(),
                    row == null ? null : row.get("image"));
            }
        }
        if (ctx.hasViolations()) {
            return;
        }
        boolean changed = false;
        for (Entry entry : input.entries()) {
            EntityInstance row = existing.get(entry.key());
            boolean text = ContentLanguages.parse(entry.key()).orElseThrow().text();
            String value = QuizEditing.text(entry.text());
            if (value == null && entry.image() == null) {
                if (row != null) {
                    ctx.changes().delete(QbContent.TRANSLATION, row.id(), row.version());
                    changed = true;
                }
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("text", text ? value : null);
            values.put("image", text ? null : entry.image());
            values.put("sourceHash", text ? ContentLanguages.sourceHash(sources.get(entry.key())) : null);
            values.put("translatedBy", ContentLanguages.BY_SPONSOR);
            values.put("aiJobId", null);
            if (row == null) {
                values.put("quizId", quiz.id());
                values.put("ownerId", owner);
                values.put("language", input.language());
                values.put("elementKey", entry.key());
                ctx.changes().insert(QbContent.TRANSLATION, values);
                changed = true;
            } else {
                Map<String, Object> differences = QuizEditing.differences(row, values);
                if (!differences.isEmpty()) {
                    ctx.changes().update(QbContent.TRANSLATION, row.id(), row.version(), differences);
                    changed = true;
                }
            }
        }
        if (changed) {
            QuizEditing.touch(ctx, quiz, Map.of(), null, List.of());
        } else {
            QuizEditing.unchanged(ctx, quiz, null, List.of());
        }
    }

    /**
     * Every element of the draft that can be translated, by its key, with its base text (an image's is null): the
     * quiz's title and introduction, and each question's and option's text and image.
     */
    static Map<String, String> sources(EntityInstance quiz, List<EntityInstance> questions,
        List<EntityInstance> options) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(ContentLanguages.TITLE, quiz.get("title"));
        sources.put(ContentLanguages.INTRO, quiz.get("intro"));
        for (EntityInstance question : questions) {
            sources.put(ContentLanguages.stem(question.id()), question.get("stem"));
            sources.put(ContentLanguages.questionImage(question.id()), null);
        }
        for (EntityInstance option : options) {
            sources.put(ContentLanguages.optionText(option.id()), option.get("text"));
            sources.put(ContentLanguages.optionImage(option.id()), null);
        }
        return sources;
    }

    /**
     * Loads into {@link #TRANSLATIONS} the translations of the elements {@code keys} names, in every language (for a
     * process that deletes those elements, as it must delete their translations first).
     */
    static <I, O> void loadTranslationsOf(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        java.util.function.Function<ProcessContext, List<String>> keys) {
        pb.step("Load their translations", QueryEntities.<ProcessContext>of(
            QbContent.defaultDataset(QbContent.TRANSLATION), ctx -> EntityQuery.builder()
                .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("quizId", ctx.get(QUIZ_ID)),
                    new QueryPredicate.In("elementKey", new ArrayList<>(keys.apply(ctx))))))
                .limit(Integer.MAX_VALUE).build(), TRANSLATIONS));
    }

    /** Deletes the translations of the elements named by {@code keys} (loaded by {@link #loadTranslationsOf}). */
    static void deleteTranslationsOf(ProcessContext ctx, Set<String> keys) {
        for (EntityInstance row : list(ctx, TRANSLATIONS)) {
            if (keys.contains(row.<String>get("elementKey"))) {
                ctx.changes().delete(QbContent.TRANSLATION, row.id(), row.version());
            }
        }
    }

    private TranslationProcesses() {}
}

package com.jabiz.quizbuks.content;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import static com.jabiz.quizbuks.content.QuizEditing.VERSIONS;
import static com.jabiz.quizbuks.content.QuizEditing.VERSION_FILES;
import static com.jabiz.quizbuks.content.QuizEditing.decimal;
import static com.jabiz.quizbuks.content.QuizEditing.list;
import static com.jabiz.quizbuks.content.QuizEditing.number;
import static com.jabiz.quizbuks.content.QuizEditing.quiz;
import static com.jabiz.quizbuks.content.QuizEditing.text;
import static com.jabiz.quizbuks.content.QuizEditing.uuid;

/**
 * The quiz as a whole (M-20 – M-22, M-27, M-28; docs/quizbuks/plans/Q3-content.md):
 * <ul>
 *   <li>{@code QB_QUIZ_SAVE}: a new draft (without {@code quizId}), or its heading changed;</li>
 *   <li>{@code QB_QUIZ_DELETE}: removes the quiz (D-Q3-6): it is marked removed and its parts tombstoned; its
 *       versions stay, since publications will refer to them, and so do the files of versions still needed;</li>
 *   <li>{@code QB_QUIZ_CLONE}: a new draft with the content of the draft or of one of its versions ("restore");</li>
 *   <li>{@code QB_QUIZ_PUBLISH_VERSION}: "save version": checks the draft is complete (D-Q3-5) and writes its
 *       snapshot once, as the next version, with the files it refers to.</li>
 * </ul>
 */
public final class QuizProcesses {

    public static final String SAVE = "QB_QUIZ_SAVE";
    public static final String DELETE = "QB_QUIZ_DELETE";
    public static final String CLONE = "QB_QUIZ_CLONE";
    public static final String PUBLISH_VERSION = "QB_QUIZ_PUBLISH_VERSION";
    /** Internal: tombstones one chunk of a removed quiz's version files (see {@link #releaseFiles()}). */
    public static final String RELEASE_FILES = "QB_QUIZ_RELEASE_FILES";

    /**
     * A new draft (no {@code quizId}) or the heading of one. Every field is written as given: an empty one clears it.
     *
     * @param intro        Markdown
     * @param cover        a file of the policy {@code qb.content.image}
     * @param baseRevision the revision the caller edited; when given and no longer current, 422
     * @param kind         {@code QUIZ} (when not given) or {@code SURVEY}, chosen when the quiz is made (Q12,
     *                     D-Q12-1); a quiz's kind never changes, so a changed quiz may only name its own
     * @param language     the language the quiz is written in (Q59a), one of the platform's; when not given, a new
     *                     quiz takes the caller's interface language and a changed one keeps its own
     */
    public record QuizInput(UUID quizId, @NotNull String title, String intro, UUID cover, Integer timeLimitSec,
        Long baseRevision, String kind, @Pattern(regexp = "ja|zh|en") String language) {

        public QuizInput(UUID quizId, String title, String intro, UUID cover, Integer timeLimitSec, Long baseRevision,
            String kind) {
            this(quizId, title, intro, cover, timeLimitSec, baseRevision, kind, null);
        }
    }

    public record QuizRef(@NotNull UUID quizId, Long baseRevision) {}

    /** One chunk of the version files of a removed quiz of the caller's to release. */
    public record ReleaseInput(@NotNull UUID quizId,
        @NotNull @Size(max = ContentLimits.RELEASE_CHUNK) List<@NotNull UUID> versionFileIds) {}

    public record ReleaseOutput(int released) {}

    /**
     * @param versionNo the version to copy; the draft when not given
     * @param title     the new quiz's title; the source's when not given
     */
    public record CloneInput(@NotNull UUID quizId, Integer versionNo, String title) {}

    public record CloneOutput(UUID quizId, UUID clonedFrom, Integer versionNo, long revision, Instant editedAt) {}

    /**
     * @param languages  Q73a: the version's languages, the base first
     * @param incomplete the quiz's other languages left out of the version, with what each lacks
     */
    public record VersionOutput(UUID quizId, UUID versionId, int versionNo, String label, int questionCount,
        int materialCount, int fullScore, String contentHash, long revision, List<String> languages,
        List<QuizLanguages.Status> incomplete) {}

    static final String FOUND = "found";
    static final String RELEASE = "release";
    static final String PUBLICATIONS = "publications";
    static final String PUBLISHED_VERSIONS = "publishedVersions";
    static final String PAST_VERSIONS = "pastVersions";

    // ---- save ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<QuizInput, EditOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, QuizInput.class, EditOutput.class, ProcessContext.class,
            pb -> pb
                .description("Creates a draft quiz or changes its title, introduction, cover or time limit.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .actsOn(QbContent.QUIZ, "quizId")
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                .step("Lock the quiz", HoldLock.<ProcessContext>exclusive(
                    ctx -> QuizEditing.lockName(ctx.get(QUIZ_ID))))
                // A new quiz has no key: the query then finds nothing, and nothing is expected.
                .step("Load the quiz", QueryEntities.<ProcessContext>of(QbContent.sponsorDataset(QbContent.QUIZ),
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("quizId", ctx.get(QUIZ_ID) == null
                        ? List.of() : List.of(ctx.get(QUIZ_ID)))).limit(1).build(), FOUND))
                .steps(b -> QuizEditing.loadFiles(b, ctx -> {
                    List<UUID> files = new ArrayList<>();
                    files.add(ctx.get(INPUT, QuizInput.class).cover());
                    return files;
                }))
                .compute("Save the quiz", (metadata, ctx) -> save(ctx)));
    }

    static void save(ProcessContext ctx) {
        QuizInput input = ctx.get(INPUT, QuizInput.class);
        Map<String, Object> heading = new LinkedHashMap<>();
        heading.put("title", input.title());
        heading.put("intro", text(input.intro()));
        heading.put("cover", input.cover());
        heading.put("timeLimitSec", input.timeLimitSec() == null ? null : decimal(input.timeLimitSec()));
        if (input.language() != null) {
            heading.put("language", input.language());
        }
        if (input.quizId() == null) {
            QuizEditing.requireOwnFile(ctx, ctx.request().actorId(), "cover", input.cover(), null);
            if (ctx.hasViolations()) {
                return;
            }
            Map<String, Object> values = newQuiz(ctx, heading, input.kind() == null ? QbContent.KIND_QUIZ
                : input.kind(), false, null);
            UUID id = uuid(ctx.changes().insert(QbContent.QUIZ, values));
            ctx.put(OUTPUT, new EditOutput(id, 1, ctx.opTime(), null, List.of()));
            return;
        }
        List<EntityInstance> found = list(ctx, FOUND);
        if (found.isEmpty()) {
            throw new EntityNotFoundException(QbContent.QUIZ + " " + input.quizId() + " not found");
        }
        EntityInstance quiz = found.getFirst();
        if (!QuizEditing.current(ctx, quiz, input.baseRevision())) {
            return;
        }
        // Q73a (D-Q73-6): the base cannot change under its translations.
        if (input.language() != null && !input.language().equals(quiz.get("language"))
            && !ContentLanguages.parseList(quiz.get("translationLanguages")).isEmpty()) {
            ctx.reject(new Violation("language", ContentCodes.BASE_HAS_TRANSLATIONS, "The base language cannot change "
                + "while the quiz has other languages", Map.of("language", input.language())));
        }
        if (input.kind() != null && !input.kind().equals(quiz.get("kind"))) {
            ctx.reject(new Violation("kind", ContentCodes.QUIZ_KIND_FIXED, "A quiz's kind cannot change; copy it "
                + "into a new one instead", Map.of("kind", String.valueOf((Object) quiz.get("kind")))));
        }
        QuizEditing.requireOwnFile(ctx, quiz.get("ownerId"), "cover", input.cover(), quiz.get("cover"));
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> changed = QuizEditing.differences(quiz, heading);
        if (changed.isEmpty()) {
            QuizEditing.unchanged(ctx, quiz, null, List.of());
        } else {
            QuizEditing.touch(ctx, quiz, changed, null, List.of());
        }
    }

    /** The values of a new draft of the caller's. */
    private static Map<String, Object> newQuiz(ProcessContext ctx, Map<String, Object> heading, String kind,
        boolean ai, Object clonedFrom) {
        Map<String, Object> values = new LinkedHashMap<>(heading);
        values.putIfAbsent("language", languageOf(ctx));
        values.put("ownerId", ctx.request().actorId());
        values.put("kind", kind);
        values.put("status", QbContent.DRAFT);
        values.put("removed", false);
        values.put("aiGenerated", ai);
        values.put("latestVersionNo", BigDecimal.ZERO);
        values.put("revision", BigDecimal.ONE);
        values.put("editedAt", ctx.opTime());
        values.put("changedSinceVersion", true);
        values.put("questionCount", BigDecimal.ZERO);
        values.put("materialCount", BigDecimal.ZERO);
        if (clonedFrom != null) {
            values.put("clonedFrom", clonedFrom);
        }
        return values;
    }

    /** The language of a quiz made without one (Q59a): the caller's interface language, else the default. */
    public static String languageOf(ProcessContext ctx) {
        java.util.Locale locale = ctx.request().locale();
        String language = locale == null ? null : locale.getLanguage();
        return QbContent.LANGUAGES.contains(language) ? language : QbContent.DEFAULT_LANGUAGE;
    }

    // ---- delete ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<QuizRef, EditOutput, ProcessContext> delete() {
        return ProcessDefinition.define(DELETE, 1, QuizRef.class, EditOutput.class, ProcessContext.class,
            pb -> pb
                .description("Removes a quiz with its questions and materials; its versions stay.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .actsOn(QbContent.QUIZ, "quizId")
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the materials", QbContent.MATERIAL, MATERIALS))
                .steps(b -> QuizEditing.loadAll(b, "Load the images", QbContent.MATERIAL_IMAGE, IMAGES))
                .steps(b -> QuizEditing.loadAll(b, "Load the questions", QbContent.QUESTION, QUESTIONS))
                .steps(b -> QuizEditing.loadAll(b, "Load the options", QbContent.OPTION, OPTIONS))
                .steps(b -> QuizEditing.loadAll(b, "Load the version files", QbContent.VERSION_FILE, VERSION_FILES))
                .steps(b -> QuizEditing.loadAll(b, "Load the translations", QbContent.TRANSLATION,
                    TranslationProcesses.TRANSLATIONS))
                // Under the quiz's lock: a submission holds it too (shared), so none slips in meanwhile.
                .steps(QuizProcesses::loadSubmitted)
                .compute("Remove the quiz", (metadata, ctx) -> remove(ctx))
                // The release reads the quiz as removed.
                .step("Save", SaveChanges.now())
                .step("Release the files of its versions", CallProcess.forEach(RELEASE_FILES, 1,
                    ctx -> ctx.contains(RELEASE) ? (List<?>) ctx.get(RELEASE) : List.of(), null)));
    }

    /**
     * {@code QB_QUIZ_RELEASE_FILES}: tombstones up to {@link ContentLimits#RELEASE_CHUNK} version files of a quiz the
     * caller owns and has removed, so that a quiz with any number of version files is removed in commits each within
     * the dataset's write limit. Run by {@code QB_QUIZ_DELETE}, one call per chunk, as the sponsor removing the quiz.
     * It runs only as a sub-process (platform decision D41: started on its own, 403 {@code PROCESS_INTERNAL}), and its
     * permission is one no role holds besides; whoever runs it, it keeps the files the quiz's submitted publications
     * still need, as the removal does (Q2 review S1).
     */
    public static ProcessDefinition<ReleaseInput, ReleaseOutput, ProcessContext> releaseFiles() {
        return ProcessDefinition.define(RELEASE_FILES, 1, ReleaseInput.class, ReleaseOutput.class,
            ProcessContext.class, pb -> pb
                .description("Releases the files of the versions of a removed quiz.")
                .permissions(QbPermissions.CONTENT_SYSTEM)
                .subProcessOnly()
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, ReleaseOutput.class))
                .step("Load the removed quiz", QueryEntities.<ProcessContext>of(QbContent.defaultDataset(QbContent.QUIZ),
                    ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("quizId", ctx.get(QUIZ_ID)),
                        new QueryPredicate.Eq("ownerId", ctx.request().actorId()),
                        new QueryPredicate.Eq("removed", true)))).limit(1).build(), FOUND))
                .step("Load the version files", QueryEntities.<ProcessContext>of(
                    QbContent.defaultDataset(QbContent.VERSION_FILE), ctx -> {
                        List<Object> ids = new ArrayList<>(ctx.get(INPUT, ReleaseInput.class).versionFileIds());
                        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("quizId", ctx.get(QUIZ_ID)),
                            new QueryPredicate.In("versionFileId", ids)))).limit(Integer.MAX_VALUE).build();
                    }, VERSION_FILES))
                .steps(QuizProcesses::loadSubmitted)
                .compute("Release them", (metadata, ctx) -> {
                    if (list(ctx, FOUND).isEmpty()) {
                        throw new EntityNotFoundException(QbContent.QUIZ + " " + ctx.get(QUIZ_ID) + " not found");
                    }
                    Set<UUID> needed = filesStillNeeded(ctx);
                    List<EntityInstance> released = list(ctx, VERSION_FILES).stream()
                        .filter(file -> !needed.contains(fileOf(file))).toList();
                    released.forEach(file -> ctx.changes().delete(QbContent.VERSION_FILE, file.id(), file.version()));
                    ctx.put(OUTPUT, new ReleaseOutput(released.size()));
                }));
    }

    /** Loads the quiz's submitted publications (their schedule rows) and the versions they use. */
    private static <I, O> void loadSubmitted(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        // The schedule holds every publication submitted and not back to its draft, by quiz (a plain index).
        pb.step("Load the publications submitted", QueryEntities.<ProcessContext>of(
                QbContent.defaultDataset(QbPublications.SCHEDULE), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.Eq("quizId", ctx.get(QUIZ_ID)))
                    .limit(Integer.MAX_VALUE).build(), PUBLICATIONS))
            // Q51: the versions publications ran on before switching to another, which their attempts still read.
            .step("Load the versions they ran on before", QueryEntities.<ProcessContext>of(
                QbContent.defaultDataset(QbPublications.PAST_VERSION), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.Eq("quizId", ctx.get(QUIZ_ID)))
                    .limit(Integer.MAX_VALUE).build(), PAST_VERSIONS))
            .step("Load their versions", QueryEntities.<ProcessContext>of(
                QbContent.defaultDataset(QbContent.VERSION), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("versionId", publishedVersionIds(ctx)))
                    .limit(Integer.MAX_VALUE).build(), PUBLISHED_VERSIONS));
    }

    static void remove(ProcessContext ctx) {
        EntityInstance quiz = quiz(ctx);
        if (!QuizEditing.current(ctx, quiz, ctx.get(INPUT, QuizRef.class).baseRevision())) {
            return;
        }
        // Parts before what they belong to: an instance still referred to cannot be deleted (a material of another
        // language refers to its base material, Q73a).
        list(ctx, TranslationProcesses.TRANSLATIONS).forEach(row -> ctx.changes().delete(QbContent.TRANSLATION,
            row.id(), row.version()));
        for (String key : List.of(IMAGES, MATERIALS, OPTIONS, QUESTIONS)) {
            String entity = switch (key) {
                case IMAGES -> QbContent.MATERIAL_IMAGE;
                case MATERIALS -> QbContent.MATERIAL;
                case OPTIONS -> QbContent.OPTION;
                default -> QbContent.QUESTION;
            };
            List<EntityInstance> rows = new ArrayList<>(list(ctx, key));
            rows.sort(java.util.Comparator.comparing(row -> row.get("variantOf") == null));
            rows.forEach(row -> ctx.changes().delete(entity, row.id(), row.version()));
        }
        // The version files are released in chunks, each its own commit within the dataset's write limit.
        Set<UUID> needed = filesStillNeeded(ctx);
        List<UUID> released = list(ctx, VERSION_FILES).stream().filter(file -> !needed.contains(fileOf(file)))
            .map(file -> uuid(file.id())).toList();
        UUID quizId = uuid(quiz.id());
        ctx.put(RELEASE, Chunks.of(released, ContentLimits.RELEASE_CHUNK).stream()
            .map(chunk -> new ReleaseInput(quizId, chunk)).toList());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("removed", true);
        values.put("cover", null);
        values.put("questionCount", BigDecimal.ZERO);
        values.put("materialCount", BigDecimal.ZERO);
        QuizEditing.touch(ctx, quiz, values, null, List.of());
    }

    /** The versions the quiz's submitted publications use or used before a switch (Q51), each once. */
    private static List<Object> publishedVersionIds(ProcessContext ctx) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (String key : List.of(PUBLICATIONS, PAST_VERSIONS)) {
            for (EntityInstance row : list(ctx, key)) {
                UUID versionId = uuid(row.get("versionId"));
                if (versionId != null) {
                    ids.add(versionId);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * The files a removed quiz must keep (Q4, D-Q4-5): those in the snapshots of the versions its submitted
     * publications use (in any state but a draft, as their schedule rows say; a draft of a removed quiz cannot be
     * submitted any more, and a publication sent back to its draft loses its row).
     */
    static Set<UUID> filesStillNeeded(ProcessContext ctx) {
        Set<UUID> needed = new HashSet<>();
        list(ctx, PUBLISHED_VERSIONS).forEach(version -> needed.addAll(QuizSnapshots.files(
            QuizSnapshots.fromJson(version.get("content"))).keySet()));
        return needed;
    }

    /** The file of a {@code QbVersionFile}, whichever of its fields holds it. */
    static UUID fileOf(EntityInstance versionFile) {
        for (String field : List.of(QuizSnapshots.IMAGE, QuizSnapshots.PDF, QuizSnapshots.AUDIO)) {
            if (versionFile.get(field) != null) {
                return uuid(versionFile.get(field));
            }
        }
        return null;
    }

    // ---- clone -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<CloneInput, CloneOutput, ProcessContext> cloneQuiz() {
        return ProcessDefinition.define(CLONE, 1, CloneInput.class, CloneOutput.class, ProcessContext.class,
            pb -> pb
                .description("Copies a quiz, as its draft is or as one of its versions was, into a new draft.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .actsOn(QbContent.QUIZ, "quizId")
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, CloneOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the materials", QbContent.MATERIAL, MATERIALS))
                .steps(b -> QuizEditing.loadAll(b, "Load the images", QbContent.MATERIAL_IMAGE, IMAGES))
                .steps(b -> QuizEditing.loadAll(b, "Load the questions", QbContent.QUESTION, QUESTIONS))
                .steps(b -> QuizEditing.loadAll(b, "Load the options", QbContent.OPTION, OPTIONS))
                .steps(b -> QuizEditing.loadAll(b, "Load the translations", QbContent.TRANSLATION,
                    TranslationProcesses.TRANSLATIONS))
                .step("Load the version", QueryEntities.<ProcessContext>of(
                    QbContent.defaultDataset(QbContent.VERSION), ctx -> {
                        Integer versionNo = ctx.get(INPUT, CloneInput.class).versionNo();
                        return versionNo == null ? none() : version(ctx.get(QUIZ_ID), versionNo);
                    }, VERSIONS))
                .compute("Copy the quiz", (metadata, ctx) -> copy(ctx)));
    }

    static void copy(ProcessContext ctx) {
        CloneInput input = ctx.get(INPUT, CloneInput.class);
        EntityInstance source = quiz(ctx);
        QuizSnapshot content;
        Map<String, Map<String, String>> hashes = Map.of();
        if (input.versionNo() == null) {
            // Q73a: the draft's other languages as they are, complete or not, and out of date where they are.
            QuizLanguages.Draft draft = QuizLanguages.of(source, list(ctx, MATERIALS), list(ctx, IMAGES),
                list(ctx, QUESTIONS), list(ctx, OPTIONS), list(ctx, TranslationProcesses.TRANSLATIONS));
            QuizSnapshot base = draft.snapshot();
            content = new QuizSnapshot(base.title(), base.intro(), base.cover(), base.timeLimitSec(),
                base.aiGenerated(), base.materials(), base.questions(), base.survey(), draft.all());
            hashes = draft.hashes();
        } else if (list(ctx, VERSIONS).isEmpty()) {
            ctx.reject(new Violation("versionNo", ContentCodes.VERSION_NOT_FOUND, "The quiz has no version "
                + input.versionNo(), Map.of("versionNo", input.versionNo())));
            return;
        } else {
            content = QuizSnapshots.fromJson(list(ctx, VERSIONS).getFirst().get("content"));
        }
        Object quizId = insertContent(ctx, content, text(input.title()) == null ? content.title() : input.title(),
            content.aiGenerated(), source.id(), source.get("language"), hashes);
        ctx.put(OUTPUT, new CloneOutput(uuid(quizId), uuid(source.id()), input.versionNo(), 1, ctx.opTime()));
    }

    /**
     * Writes a new draft of the caller's with the content: the quiz, its materials and their images, its questions
     * and their options, numbered in their order. For copies and imports (Q16), whose files are the caller's.
     *
     * @param clonedFrom the quiz copied, or null
     * @param language   the quiz's language (Q59a): a copy's source's; null for the caller's interface language
     * @return the new quiz's key
     */
    public static Object insertContent(ProcessContext ctx, QuizSnapshot content, String title, boolean aiGenerated,
        Object clonedFrom, Object language) {
        return insertContent(ctx, content, title, aiGenerated, clonedFrom, language, Map.of());
    }

    /**
     * As {@link #insertContent(ProcessContext, QuizSnapshot, String, boolean, Object, Object)}, with the content's
     * other languages (Q73a): their translations, own images and materials, each translation recording the base hash
     * {@code hashes} gives for it (a copy keeps what was out of date out of date), else the present base's.
     */
    public static Object insertContent(ProcessContext ctx, QuizSnapshot content, String title, boolean aiGenerated,
        Object clonedFrom, Object language, Map<String, Map<String, String>> hashes) {
        Map<String, Object> heading = new LinkedHashMap<>();
        heading.put("title", title);
        if (language != null) {
            heading.put("language", language);
        }
        heading.put("intro", content.intro());
        heading.put("cover", content.cover());
        heading.put("timeLimitSec", content.timeLimitSec() == null ? null : decimal(content.timeLimitSec()));
        // A copy is of the same kind (D-Q12-1: the kind only changes by making a new quiz).
        Map<String, Object> values = newQuiz(ctx, heading, content.kind(), aiGenerated, clonedFrom);
        values.put("questionCount", decimal(content.questions().size()));
        values.put("materialCount", decimal(content.materials().size()));
        List<String> languages = QbContent.LANGUAGES.stream().filter(content.translations()::containsKey)
            .filter(l -> !l.equals(values.get("language"))).toList();
        values.put("translationLanguages", ContentLanguages.joinList(languages));
        Object quizId = ctx.changes().insert(QbContent.QUIZ, values);
        String owner = ctx.request().actorId();
        Map<Integer, Object> materialIds = new LinkedHashMap<>();
        for (QuizSnapshot.Material material : content.materials()) {
            materialIds.put(material.no(), insertMaterial(ctx, quizId, owner, material, null, null));
        }
        Map<Integer, Object> questionIds = new LinkedHashMap<>();
        Map<String, Object> optionIds = new LinkedHashMap<>();
        for (QuizSnapshot.Question question : content.questions()) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("quizId", quizId);
            q.put("ownerId", owner);
            q.put("seq", decimal(question.no()));
            q.put("stem", question.stem());
            q.put("image", question.image());
            q.put("points", decimal(question.points()));
            q.put("selection", question.selection());
            Object questionId = ctx.changes().insert(QbContent.QUESTION, q);
            for (QuizSnapshot.Option option : question.options()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("questionId", questionId);
                o.put("quizId", quizId);
                o.put("ownerId", owner);
                o.put("seq", decimal(option.no()));
                o.put("text", option.text());
                o.put("image", option.image());
                o.put("correct", option.correct());
                optionIds.put(QuizSnapshot.optionTextKey(question.no(), option.no()),
                    ctx.changes().insert(QbContent.OPTION, o));
            }
            questionIds.put(question.no(), questionId);
        }
        for (String translated : languages) {
            insertLanguage(ctx, quizId, owner, translated, content, content.translations().get(translated),
                hashes.getOrDefault(translated, Map.of()), materialIds, questionIds, optionIds);
        }
        return quizId;
    }

    private static Object insertMaterial(ProcessContext ctx, Object quizId, String owner,
        QuizSnapshot.Material material, Object variantOf, String language) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("quizId", quizId);
        m.put("ownerId", owner);
        m.put("seq", decimal(material.no()));
        m.put("kind", material.kind());
        m.put("title", material.title());
        m.put("description", material.description());
        m.put("body", material.body());
        m.put("url", material.url());
        m.put("pdf", material.pdf());
        m.put("audio", material.audio());
        m.put("watchToAnswer", variantOf == null && material.watchToAnswer());
        m.put("durationSec", variantOf == null && material.durationSec() != null ? decimal(material.durationSec()) : null);
        if (variantOf != null) {
            m.put("variantOf", variantOf);
            m.put("language", language);
        }
        Object materialId = ctx.changes().insert(QbContent.MATERIAL, m);
        for (QuizSnapshot.Image image : material.images()) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("materialId", materialId);
            i.put("quizId", quizId);
            i.put("ownerId", owner);
            i.put("seq", decimal(image.no()));
            i.put("image", image.image());
            i.put("caption", image.caption());
            ctx.changes().insert(QbContent.MATERIAL_IMAGE, i);
        }
        return materialId;
    }

    private static final java.util.regex.Pattern SNAPSHOT_KEY =
        java.util.regex.Pattern.compile("q(\\d+)(?:\\.o(\\d+))?\\.(stem|text|image)");

    /** Writes one other language of a new draft: its translations by the new items' keys, and its materials. */
    private static void insertLanguage(ProcessContext ctx, Object quizId, String owner, String language,
        QuizSnapshot content, QuizSnapshot.Language t, Map<String, String> hashes, Map<Integer, Object> materialIds,
        Map<Integer, Object> questionIds, Map<String, Object> optionIds) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(QuizSnapshot.TITLE_KEY, content.title());
        sources.put(QuizSnapshot.INTRO_KEY, content.intro());
        for (QuizSnapshot.Question q : content.questions()) {
            sources.put(QuizSnapshot.stemKey(q.no()), q.stem());
            q.options().forEach(o -> sources.put(QuizSnapshot.optionTextKey(q.no(), o.no()), o.text()));
        }
        Map<String, Object[]> rows = new LinkedHashMap<>();
        t.texts().forEach((key, text) -> rows.put(key, new Object[] {text, null}));
        t.images().forEach((key, image) -> rows.put(key, new Object[] {null, image}));
        rows.forEach((key, value) -> {
            String element = elementKey(key, questionIds, optionIds);
            if (element == null) {
                return;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("quizId", quizId);
            row.put("ownerId", owner);
            row.put("language", language);
            row.put("elementKey", element);
            row.put("text", value[0]);
            row.put("image", value[1]);
            row.put("sourceHash", value[0] == null ? null
                : hashes.getOrDefault(key, ContentLanguages.sourceHash(sources.get(key))));
            row.put("translatedBy", ContentLanguages.BY_SPONSOR);
            ctx.changes().insert(QbContent.TRANSLATION, row);
        });
        for (QuizSnapshot.Material material : t.materials()) {
            Object base = materialIds.get(material.no());
            if (base != null) {
                insertMaterial(ctx, quizId, owner, material, base, language);
            }
        }
    }

    /** The draft's key of an element named by its key in a snapshot; null for none of the content's. */
    private static String elementKey(String key, Map<Integer, Object> questionIds, Map<String, Object> optionIds) {
        if (QuizSnapshot.TITLE_KEY.equals(key)) {
            return ContentLanguages.TITLE;
        }
        if (QuizSnapshot.INTRO_KEY.equals(key)) {
            return ContentLanguages.INTRO;
        }
        java.util.regex.Matcher m = SNAPSHOT_KEY.matcher(key);
        if (!m.matches()) {
            return null;
        }
        int question = Integer.parseInt(m.group(1));
        if (m.group(2) == null) {
            Object id = questionIds.get(question);
            return id == null || "text".equals(m.group(3)) ? null
                : "stem".equals(m.group(3)) ? ContentLanguages.stem(id) : ContentLanguages.questionImage(id);
        }
        Object id = optionIds.get(QuizSnapshot.optionTextKey(question, Integer.parseInt(m.group(2))));
        return id == null || "stem".equals(m.group(3)) ? null
            : "text".equals(m.group(3)) ? ContentLanguages.optionText(id) : ContentLanguages.optionImage(id);
    }

    // ---- versions --------------------------------------------------------------------------------------------------

    public static ProcessDefinition<QuizRef, VersionOutput, ProcessContext> publishVersion() {
        return ProcessDefinition.define(PUBLISH_VERSION, 1, QuizRef.class, VersionOutput.class, ProcessContext.class,
            pb -> pb
                .description("Saves the draft as the next version of the quiz, once it is complete.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .actsOn(QbContent.QUIZ, "quizId")
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, VersionOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the materials", QbContent.MATERIAL, MATERIALS))
                .steps(b -> QuizEditing.loadAll(b, "Load the images", QbContent.MATERIAL_IMAGE, IMAGES))
                .steps(b -> QuizEditing.loadAll(b, "Load the questions", QbContent.QUESTION, QUESTIONS))
                .steps(b -> QuizEditing.loadAll(b, "Load the options", QbContent.OPTION, OPTIONS))
                .steps(b -> QuizEditing.loadAll(b, "Load the version files", QbContent.VERSION_FILE, VERSION_FILES))
                .steps(b -> QuizEditing.loadAll(b, "Load the translations", QbContent.TRANSLATION,
                    TranslationProcesses.TRANSLATIONS))
                .step("Load the latest version", QueryEntities.<ProcessContext>of(
                    QbContent.defaultDataset(QbContent.VERSION),
                    ctx -> version(ctx.get(QUIZ_ID), (int) number(quiz(ctx).get("latestVersionNo"))), VERSIONS))
                .compute("Save the version", (metadata, ctx) -> version(ctx)));
    }

    static void version(ProcessContext ctx) {
        EntityInstance quiz = quiz(ctx);
        if (!QuizEditing.current(ctx, quiz, ctx.get(INPUT, QuizRef.class).baseRevision())) {
            return;
        }
        // Q73a (D-Q73-7): the version keeps the languages that are complete; the others are left out and said so.
        QuizLanguages.Draft draft = QuizLanguages.of(quiz, list(ctx, MATERIALS), list(ctx, IMAGES),
            list(ctx, QUESTIONS), list(ctx, OPTIONS), list(ctx, TranslationProcesses.TRANSLATIONS));
        QuizSnapshot content = draft.snapshot();
        List<Violation> problems = QuizCompleteness.check(content);
        if (!problems.isEmpty()) {
            problems.forEach(ctx::reject);
            return;
        }
        String json = QuizSnapshots.toJson(content);
        int size = QuizSnapshots.size(json);
        if (size > ContentLimits.MAX_SNAPSHOT_BYTES) {
            ctx.reject(new Violation(null, ContentCodes.TOO_LARGE, "The quiz is " + size + " bytes as a version, more "
                + "than " + ContentLimits.MAX_SNAPSHOT_BYTES, Map.of("size", size,
                "max", ContentLimits.MAX_SNAPSHOT_BYTES)));
            return;
        }
        String hash = QuizSnapshots.hash(content);
        List<EntityInstance> latest = list(ctx, VERSIONS);
        // The language is the version's own column, not in the content (Q59a): a new language alone is a change too.
        Object language = quiz.get("language");
        List<String> languages = draft.languages(String.valueOf(language));
        String languagesColumn = content.translations().isEmpty() ? null : ContentLanguages.joinList(languages);
        if (!latest.isEmpty() && hash.equals(latest.getFirst().get("contentHash"))
            && java.util.Objects.equals(language, latest.getFirst().get("language"))
            && java.util.Objects.equals(languagesColumn, latest.getFirst().get("languages"))) {
            ctx.reject(new Violation(null, ContentCodes.VERSION_UNCHANGED, "The quiz is as its version "
                + latest.getFirst().get("label") + " was", Map.of("label", latest.getFirst().get("label"))));
            return;
        }
        int versionNo = (int) number(quiz.get("latestVersionNo")) + 1;
        String label = VersionLabels.label(versionNo);
        Object owner = quiz.get("ownerId");
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("quizId", quiz.id());
        version.put("ownerId", owner);
        version.put("versionNumber", decimal(versionNo));
        version.put("label", label);
        version.put("title", content.title());
        version.put("timeLimitSec", content.timeLimitSec() == null ? null : decimal(content.timeLimitSec()));
        version.put("language", language);
        version.put("languages", languagesColumn);
        version.put("questionCount", decimal(content.questions().size()));
        version.put("materialCount", decimal(content.materials().size()));
        version.put("fullScore", decimal(content.fullScore()));
        version.put("content", json);
        version.put("contentHash", hash);
        version.put("versionedAt", ctx.opTime());
        Object versionId = ctx.changes().insert(QbContent.VERSION, version);

        // The files the platform must keep while the version may be used: those not registered yet.
        Set<UUID> registered = new HashSet<>();
        list(ctx, VERSION_FILES).forEach(file -> registered.add(fileOf(file)));
        QuizSnapshots.files(content).forEach((file, field) -> {
            if (!registered.contains(file)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("quizId", quiz.id());
                row.put("ownerId", owner);
                row.put("firstVersionNo", decimal(versionNo));
                row.put(field, file);
                ctx.changes().insert(QbContent.VERSION_FILE, row);
            }
        });

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", QbContent.VERSIONED);
        values.put("latestVersionNo", decimal(versionNo));
        values.put("versionLabel", label);
        values.put("changedSinceVersion", false);
        ctx.changes().update(QbContent.QUIZ, quiz.id(), quiz.version(), values);
        ctx.put(OUTPUT, new VersionOutput(uuid(quiz.id()), uuid(versionId), versionNo, label,
            content.questions().size(), content.materials().size(), content.fullScore(), hash,
            number(quiz.get("revision")), languages,
            draft.statuses().stream().filter(status -> !status.complete()).toList()));
    }

    private static EntityQuery version(Object quizId, int versionNo) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("quizId", quizId),
            new QueryPredicate.Eq("versionNumber", versionNo)))).limit(1).build();
    }

    private static EntityQuery none() {
        return EntityQuery.builder().where(new QueryPredicate.In("quizId", new ArrayList<>())).limit(1).build();
    }

    private QuizProcesses() {}
}

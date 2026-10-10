package com.jabiz.quizbuks.content;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
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
     */
    public record QuizInput(UUID quizId, @NotNull String title, String intro, UUID cover, Integer timeLimitSec,
        Long baseRevision) {}

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

    public record VersionOutput(UUID quizId, UUID versionId, int versionNo, String label, int questionCount,
        int materialCount, int fullScore, String contentHash, long revision) {}

    static final String FOUND = "found";
    static final String RELEASE = "release";

    // ---- save ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<QuizInput, EditOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, QuizInput.class, EditOutput.class, ProcessContext.class,
            pb -> pb
                .description("Creates a draft quiz or changes its title, introduction, cover or time limit.")
                .permissions(QbPermissions.CONTENT_WRITE)
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
        if (input.quizId() == null) {
            QuizEditing.requireOwnFile(ctx, ctx.request().actorId(), "cover", input.cover(), null);
            if (ctx.hasViolations()) {
                return;
            }
            Map<String, Object> values = newQuiz(ctx, heading, false, null);
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
    private static Map<String, Object> newQuiz(ProcessContext ctx, Map<String, Object> heading, boolean ai,
        Object clonedFrom) {
        Map<String, Object> values = new LinkedHashMap<>(heading);
        values.put("ownerId", ctx.request().actorId());
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

    // ---- delete ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<QuizRef, EditOutput, ProcessContext> delete() {
        return ProcessDefinition.define(DELETE, 1, QuizRef.class, EditOutput.class, ProcessContext.class,
            pb -> pb
                .description("Removes a quiz with its questions and materials; its versions stay.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .actsOn(QbContent.QUIZ, "quizId")
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the materials", QbContent.MATERIAL, MATERIALS))
                .steps(b -> QuizEditing.loadAll(b, "Load the images", QbContent.MATERIAL_IMAGE, IMAGES))
                .steps(b -> QuizEditing.loadAll(b, "Load the questions", QbContent.QUESTION, QUESTIONS))
                .steps(b -> QuizEditing.loadAll(b, "Load the options", QbContent.OPTION, OPTIONS))
                .steps(b -> QuizEditing.loadAll(b, "Load the version files", QbContent.VERSION_FILE, VERSION_FILES))
                .compute("Remove the quiz", (metadata, ctx) -> remove(ctx))
                // The release reads the quiz as removed.
                .step("Save", SaveChanges.now())
                .step("Release the files of its versions", CallProcess.forEach(RELEASE_FILES, 1,
                    ctx -> ctx.contains(RELEASE) ? (List<?>) ctx.get(RELEASE) : List.of(), null)));
    }

    /**
     * {@code QB_QUIZ_RELEASE_FILES}: tombstones up to {@link ContentLimits#RELEASE_CHUNK} version files of a quiz the
     * caller owns and has removed, so that a quiz with any number of version files is removed in commits each within
     * the dataset's write limit. Run by {@code QB_QUIZ_DELETE}, one call per chunk; called directly it can only
     * release files of the caller's own removed quizzes, which nothing needs any more.
     */
    public static ProcessDefinition<ReleaseInput, ReleaseOutput, ProcessContext> releaseFiles() {
        return ProcessDefinition.define(RELEASE_FILES, 1, ReleaseInput.class, ReleaseOutput.class,
            ProcessContext.class, pb -> pb
                .description("Releases the files of the versions of a removed quiz.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .internal()
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
                .compute("Release them", (metadata, ctx) -> {
                    if (list(ctx, FOUND).isEmpty()) {
                        throw new EntityNotFoundException(QbContent.QUIZ + " " + ctx.get(QUIZ_ID) + " not found");
                    }
                    list(ctx, VERSION_FILES).forEach(file -> ctx.changes().delete(QbContent.VERSION_FILE, file.id(),
                        file.version()));
                    ctx.put(OUTPUT, new ReleaseOutput(list(ctx, VERSION_FILES).size()));
                }));
    }

    static void remove(ProcessContext ctx) {
        EntityInstance quiz = quiz(ctx);
        if (!QuizEditing.current(ctx, quiz, ctx.get(INPUT, QuizRef.class).baseRevision())) {
            return;
        }
        // Parts before what they belong to: an instance still referred to cannot be deleted.
        for (String key : List.of(IMAGES, MATERIALS, OPTIONS, QUESTIONS)) {
            String entity = switch (key) {
                case IMAGES -> QbContent.MATERIAL_IMAGE;
                case MATERIALS -> QbContent.MATERIAL;
                case OPTIONS -> QbContent.OPTION;
                default -> QbContent.QUESTION;
            };
            list(ctx, key).forEach(row -> ctx.changes().delete(entity, row.id(), row.version()));
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

    /**
     * The files a removed quiz must keep: those of the versions publications still refer to. There are no
     * publications before Q4, so none; Q4 returns the files of the snapshots of the versions its publications use.
     */
    static Set<UUID> filesStillNeeded(ProcessContext ctx) {
        return Set.of();
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
                .actsOn(QbContent.QUIZ, "quizId")
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, CloneOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the materials", QbContent.MATERIAL, MATERIALS))
                .steps(b -> QuizEditing.loadAll(b, "Load the images", QbContent.MATERIAL_IMAGE, IMAGES))
                .steps(b -> QuizEditing.loadAll(b, "Load the questions", QbContent.QUESTION, QUESTIONS))
                .steps(b -> QuizEditing.loadAll(b, "Load the options", QbContent.OPTION, OPTIONS))
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
        if (input.versionNo() == null) {
            content = QuizEditing.snapshot(source, list(ctx, MATERIALS), list(ctx, IMAGES), list(ctx, QUESTIONS),
                list(ctx, OPTIONS));
        } else if (list(ctx, VERSIONS).isEmpty()) {
            ctx.reject(new Violation("versionNo", ContentCodes.VERSION_NOT_FOUND, "The quiz has no version "
                + input.versionNo(), Map.of("versionNo", input.versionNo())));
            return;
        } else {
            content = QuizSnapshots.fromJson(list(ctx, VERSIONS).getFirst().get("content"));
        }
        Map<String, Object> heading = new LinkedHashMap<>();
        heading.put("title", text(input.title()) == null ? content.title() : input.title());
        heading.put("intro", content.intro());
        heading.put("cover", content.cover());
        heading.put("timeLimitSec", content.timeLimitSec() == null ? null : decimal(content.timeLimitSec()));
        Map<String, Object> values = newQuiz(ctx, heading, content.aiGenerated(), source.id());
        values.put("questionCount", decimal(content.questions().size()));
        values.put("materialCount", decimal(content.materials().size()));
        Object quizId = ctx.changes().insert(QbContent.QUIZ, values);
        String owner = ctx.request().actorId();
        for (QuizSnapshot.Material material : content.materials()) {
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
        }
        for (QuizSnapshot.Question question : content.questions()) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("quizId", quizId);
            q.put("ownerId", owner);
            q.put("seq", decimal(question.no()));
            q.put("stem", question.stem());
            q.put("image", question.image());
            q.put("points", decimal(question.points()));
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
                ctx.changes().insert(QbContent.OPTION, o);
            }
        }
        ctx.put(OUTPUT, new CloneOutput(uuid(quizId), uuid(source.id()), input.versionNo(), 1, ctx.opTime()));
    }

    // ---- versions --------------------------------------------------------------------------------------------------

    public static ProcessDefinition<QuizRef, VersionOutput, ProcessContext> publishVersion() {
        return ProcessDefinition.define(PUBLISH_VERSION, 1, QuizRef.class, VersionOutput.class, ProcessContext.class,
            pb -> pb
                .description("Saves the draft as the next version of the quiz, once it is complete.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .actsOn(QbContent.QUIZ, "quizId")
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, VersionOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the materials", QbContent.MATERIAL, MATERIALS))
                .steps(b -> QuizEditing.loadAll(b, "Load the images", QbContent.MATERIAL_IMAGE, IMAGES))
                .steps(b -> QuizEditing.loadAll(b, "Load the questions", QbContent.QUESTION, QUESTIONS))
                .steps(b -> QuizEditing.loadAll(b, "Load the options", QbContent.OPTION, OPTIONS))
                .steps(b -> QuizEditing.loadAll(b, "Load the version files", QbContent.VERSION_FILE, VERSION_FILES))
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
        QuizSnapshot content = QuizEditing.snapshot(quiz, list(ctx, MATERIALS), list(ctx, IMAGES),
            list(ctx, QUESTIONS), list(ctx, OPTIONS));
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
        if (!latest.isEmpty() && hash.equals(latest.getFirst().get("contentHash"))) {
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
            number(quiz.get("revision"))));
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

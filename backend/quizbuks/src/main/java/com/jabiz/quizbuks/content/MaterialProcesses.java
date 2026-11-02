package com.jabiz.quizbuks.content;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.runtime.EntityInstance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.quizbuks.content.QuizEditing.IMAGES;
import static com.jabiz.quizbuks.content.QuizEditing.INPUT;
import static com.jabiz.quizbuks.content.QuizEditing.MATERIALS;
import static com.jabiz.quizbuks.content.QuizEditing.OUTPUT;
import static com.jabiz.quizbuks.content.QuizEditing.decimal;
import static com.jabiz.quizbuks.content.QuizEditing.list;
import static com.jabiz.quizbuks.content.QuizEditing.quiz;
import static com.jabiz.quizbuks.content.QuizEditing.text;
import static com.jabiz.quizbuks.content.QuizEditing.uuid;

/**
 * The reference materials of a quiz (M-23; docs/quizbuks/plans/Q3-content.md): {@code QB_MATERIAL_SAVE} saves one
 * material with its images, {@code QB_MATERIAL_DELETE} deletes one, {@code QB_MATERIAL_REORDER} puts them in a new
 * order. A material keeps the kind it was made with and fills only that kind's content field (D-Q3-5).
 */
public final class MaterialProcesses {

    public static final String SAVE = "QB_MATERIAL_SAVE";
    public static final String DELETE = "QB_MATERIAL_DELETE";
    public static final String REORDER = "QB_MATERIAL_REORDER";

    /** An image of an image group: an existing one by {@code imageId}, a new one without. */
    public record ImageInput(UUID imageId, @NotNull UUID image, String caption) {}

    /**
     * One material; a new one (no {@code materialId}) goes to the end. Only the content field of its kind may be
     * given: {@code body} (ARTICLE, Markdown), {@code url} (LINK, VIDEO_LINK), {@code pdf}, {@code audio} or
     * {@code images} (IMAGES). {@code images} is the whole list, as the sponsor console saves a material with its
     * images: those left out are deleted, an empty list deletes them all, and without it (null) they stay as they are.
     * {@code watchToAnswer} (Q19, VIDEO_LINK only; null is false): watching this YouTube or Vimeo video is enough to
     * answer, so the App shows its player on the publication's card; one such video per quiz.
     * {@code variantOf} and {@code language} (Q73a, §2.2b): a material of one of the quiz's other languages, standing
     * for the base material {@code variantOf} there, of its kind; one per base material and language, made with both
     * and changed with the same. It follows the base's place and the base's {@code watchToAnswer}.
     * {@code durationSec} (Q75, VIDEO_LINK of the base language only): how long the video is, 1 second to 2 hours;
     * the video to watch needs it before a version is saved (D-Q75-5), so that the start can check the watching.
     */
    public record MaterialInput(@NotNull UUID quizId, UUID materialId,
        @NotNull @Pattern(regexp = "ARTICLE|LINK|VIDEO_LINK|PDF|IMAGES|AUDIO") String kind, String title,
        String description, String body, String url, UUID pdf, UUID audio,
        @Size(max = ContentLimits.MAX_IMAGES) List<@Valid @NotNull ImageInput> images, Long baseRevision,
        Boolean watchToAnswer, UUID variantOf, @Pattern(regexp = "ja|zh|en") String language, Integer durationSec) {

        /** A material as before Q75: without a duration. */
        public MaterialInput(UUID quizId, UUID materialId, String kind, String title, String description, String body,
            String url, UUID pdf, UUID audio, List<ImageInput> images, Long baseRevision, Boolean watchToAnswer,
            UUID variantOf, String language) {
            this(quizId, materialId, kind, title, description, body, url, pdf, audio, images, baseRevision,
                watchToAnswer, variantOf, language, null);
        }

        /** A material of the base language. */
        public MaterialInput(UUID quizId, UUID materialId, String kind, String title, String description, String body,
            String url, UUID pdf, UUID audio, List<ImageInput> images, Long baseRevision, Boolean watchToAnswer) {
            this(quizId, materialId, kind, title, description, body, url, pdf, audio, images, baseRevision,
                watchToAnswer, null, null);
        }

        /** A material that is not the video to watch. */
        public MaterialInput(UUID quizId, UUID materialId, String kind, String title, String description, String body,
            String url, UUID pdf, UUID audio, List<ImageInput> images, Long baseRevision) {
            this(quizId, materialId, kind, title, description, body, url, pdf, audio, images, baseRevision, null, null,
                null);
        }
    }

    public record MaterialRef(@NotNull UUID quizId, @NotNull UUID materialId, Long baseRevision) {}

    /** Every material of the quiz, in the new order. */
    public record ReorderInput(@NotNull UUID quizId,
        @NotNull @Size(max = ContentLimits.MAX_MATERIALS) List<@NotNull UUID> materialIds, Long baseRevision) {}

    public static ProcessDefinition<MaterialInput, EditOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, MaterialInput.class, EditOutput.class,
            ProcessContext.class, pb -> pb
                .description("Adds a reference material to a quiz or changes one, with its images.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the materials", QbContent.MATERIAL, MATERIALS))
                .steps(b -> QuizEditing.loadPartsOf(b, "Load its images", QbContent.MATERIAL_IMAGE, "materialId",
                    ctx -> ctx.get(INPUT, MaterialInput.class).materialId(), IMAGES))
                .steps(b -> QuizEditing.loadFiles(b, ctx -> {
                    MaterialInput input = ctx.get(INPUT, MaterialInput.class);
                    List<UUID> files = new ArrayList<>();
                    files.add(input.pdf());
                    files.add(input.audio());
                    if (input.images() != null) {
                        input.images().forEach(image -> files.add(image.image()));
                    }
                    return files;
                }))
                .compute("Save the material", (metadata, ctx) -> save(ctx)));
    }

    /**
     * Why a material cannot be the video to watch (Q19): only a video link, only one the App can show (YouTube or
     * Vimeo, {@link VideoEmbeds}), and only one per quiz.
     */
    private static void watchProblems(ProcessContext ctx, MaterialKind kind, String url,
        List<EntityInstance> materials, EntityInstance material) {
        if (kind != MaterialKind.VIDEO_LINK) {
            ctx.reject(new Violation("watchToAnswer", ContentCodes.MATERIAL_FIELD_NOT_ALLOWED, "A material of kind "
                + kind + " has no watchToAnswer", Map.of("kind", kind.name(), "field", "watchToAnswer")));
            return;
        }
        if (VideoEmbeds.player(text(url)).isEmpty()) {
            ctx.reject(new Violation("url", ContentCodes.MATERIAL_VIDEO_NOT_EMBEDDABLE,
                "Only a YouTube or Vimeo video can be watched in the App"));
        }
        boolean another = QuizLanguages.bases(materials).stream()
            .anyMatch(other -> Boolean.TRUE.equals(other.get("watchToAnswer"))
            && (material == null || !other.id().equals(material.id())));
        if (another) {
            ctx.reject(new Violation("watchToAnswer", ContentCodes.QUIZ_ONE_WATCH_VIDEO,
                "A quiz has one video to watch"));
        }
    }

    static void save(ProcessContext ctx) {
        MaterialInput input = ctx.get(INPUT, MaterialInput.class);
        EntityInstance quiz = quiz(ctx);
        if (!QuizEditing.current(ctx, quiz, input.baseRevision())) {
            return;
        }
        List<EntityInstance> materials = list(ctx, MATERIALS);
        List<EntityInstance> bases = QuizLanguages.bases(materials);
        EntityInstance material = null;
        // Q73a: the base material a material of another language stands for (null for a base material).
        EntityInstance base = null;
        if (input.materialId() != null) {
            material = QuizEditing.find(materials, input.materialId());
            if (material == null) {
                ctx.reject(QuizEditing.notInQuiz("materialId", input.materialId()));
                return;
            }
            if (!QuizEditing.same(material.get("variantOf"), input.variantOf())
                || !QuizEditing.same(material.get("language"), input.language())) {
                ctx.reject(new Violation("variantOf", ContentCodes.MATERIAL_VARIANT_FIXED, "A material keeps its "
                    + "language and the base material it stands for"));
                return;
            }
            if (!input.kind().equals(material.get("kind"))) {
                ctx.reject(new Violation("kind", ContentCodes.MATERIAL_KIND_FIXED, "A material stays of the kind it "
                    + "was made with: " + material.get("kind"), Map.of("kind", material.get("kind"))));
                return;
            }
            base = material.get("variantOf") == null ? null : QuizEditing.find(bases, material.get("variantOf"));
        } else if (input.variantOf() != null || input.language() != null) {
            base = input.variantOf() == null ? null : QuizEditing.find(bases, input.variantOf());
            if (base == null) {
                ctx.reject(QuizEditing.notInQuiz("variantOf", input.variantOf()));
                return;
            }
            String language = input.language();
            if (language == null
                || !ContentLanguages.parseList(quiz.get("translationLanguages")).contains(language)) {
                ctx.reject(new Violation("language", ContentCodes.TRANSLATION_LANGUAGE, "The quiz has no language "
                    + language + " besides its base", Map.of("language", String.valueOf(language))));
                return;
            }
            if (!input.kind().equals(base.get("kind"))) {
                ctx.reject(new Violation("kind", ContentCodes.MATERIAL_KIND_FIXED, "A material of another language "
                    + "is of its base's kind: " + base.get("kind"), Map.of("kind", base.get("kind"))));
                return;
            }
            Object baseId = base.id();
            if (QuizLanguages.variants(materials, language).stream()
                .anyMatch(v -> QuizEditing.same(v.get("variantOf"), baseId))) {
                ctx.reject(new Violation("variantOf", ContentCodes.MATERIAL_VARIANT_EXISTS, "The material already has "
                    + "one in " + language, Map.of("material", bases.indexOf(base) + 1, "language", language)));
                return;
            }
        } else if (bases.size() >= ContentLimits.MAX_MATERIALS) {
            ctx.reject(new Violation("materialId", ContentCodes.TOO_MANY_MATERIALS, "A quiz has at most "
                + ContentLimits.MAX_MATERIALS + " materials", Map.of("max", ContentLimits.MAX_MATERIALS)));
            return;
        }
        boolean variant = base != null || material != null && material.get("variantOf") != null;
        MaterialKind kind = MaterialKind.valueOf(input.kind());
        List<ImageInput> images = input.images();
        Map<String, Boolean> given = new LinkedHashMap<>();
        given.put("body", text(input.body()) != null);
        given.put("url", text(input.url()) != null);
        given.put("pdf", input.pdf() != null);
        given.put("audio", input.audio() != null);
        given.put("images", images != null && !images.isEmpty());
        given.forEach((field, present) -> {
            if (present && !field.equals(kind.contentField())) {
                ctx.reject(new Violation(field, ContentCodes.MATERIAL_FIELD_NOT_ALLOWED, "A material of kind " + kind
                    + " has no " + field, Map.of("kind", kind.name(), "field", field)));
            }
        });
        boolean watch = !variant && Boolean.TRUE.equals(input.watchToAnswer());
        if (watch) {
            watchProblems(ctx, kind, input.url(), materials, material);
        }
        // Q75: the duration belongs to a video of the base language, within its range; the video to watch is asked
        // for it when a version is saved (QuizCompleteness), not here: a draft may still lack it.
        Integer duration = input.durationSec();
        if (duration != null && (kind != MaterialKind.VIDEO_LINK || variant)) {
            ctx.reject(new Violation("durationSec", ContentCodes.MATERIAL_FIELD_NOT_ALLOWED, "A material of kind " + kind
                + (variant ? " of another language" : "") + " has no durationSec",
                Map.of("kind", kind.name(), "field", "durationSec")));
        } else if (duration != null
            && (duration < ContentLimits.MIN_VIDEO_SECONDS || duration > ContentLimits.MAX_VIDEO_SECONDS)) {
            ctx.reject(new Violation("durationSec", ContentCodes.MATERIAL_DURATION_RANGE, "A video lasts "
                + ContentLimits.MIN_VIDEO_SECONDS + " to " + ContentLimits.MAX_VIDEO_SECONDS + " seconds",
                Map.of("min", ContentLimits.MIN_VIDEO_SECONDS, "max", ContentLimits.MAX_VIDEO_SECONDS)));
        }
        // A video to watch is watched in every language: its other languages' videos must be playable too.
        if (variant && base != null && Boolean.TRUE.equals(base.get("watchToAnswer"))
            && VideoEmbeds.player(text(input.url())).isEmpty() && text(input.url()) != null) {
            ctx.reject(new Violation("url", ContentCodes.MATERIAL_VIDEO_NOT_EMBEDDABLE,
                "Only a YouTube or Vimeo video can be watched in the App"));
        }
        // Loaded by the input's materialId: only when that material is in this quiz are they its images.
        List<EntityInstance> existing = material == null ? List.of() : list(ctx, IMAGES);
        if (ctx.hasViolations() || images != null && !QuizEditing.knownParts(ctx,
            images.stream().map(ImageInput::imageId).toList(), existing, "images", "imageId")) {
            return;
        }
        Object owner = quiz.get("ownerId");
        QuizEditing.requireOwnFile(ctx, owner, "pdf", input.pdf(), material == null ? null : material.get("pdf"));
        QuizEditing.requireOwnFile(ctx, owner, "audio", input.audio(),
            material == null ? null : material.get("audio"));
        if (images != null) {
            for (int i = 0; i < images.size(); i++) {
                EntityInstance part = images.get(i).imageId() == null ? null
                    : QuizEditing.find(existing, images.get(i).imageId());
                QuizEditing.requireOwnFile(ctx, owner, "images[" + i + "].image", images.get(i).image(),
                    part == null ? null : part.get("image"));
            }
        }
        if (ctx.hasViolations()) {
            return;
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", text(input.title()));
        values.put("description", text(input.description()));
        values.put("body", text(input.body()));
        values.put("url", text(input.url()));
        values.put("pdf", input.pdf());
        values.put("audio", input.audio());
        values.put("watchToAnswer", watch);
        values.put("durationSec", kind == MaterialKind.VIDEO_LINK && !variant && duration != null
            ? decimal(duration) : null);
        boolean changed = false;
        Object materialId;
        if (material == null) {
            values.put("quizId", quiz.id());
            values.put("ownerId", quiz.get("ownerId"));
            values.put("kind", kind.name());
            if (base != null) {
                // A material of another language has no place of its own: it stands where its base does.
                values.put("seq", base.get("seq"));
                values.put("variantOf", base.id());
                values.put("language", input.language());
            } else {
                values.put("seq", decimal(QuizEditing.nextSeq(ctx, QbContent.MATERIAL, bases)));
            }
            materialId = ctx.changes().insert(QbContent.MATERIAL, values);
            changed = true;
        } else {
            materialId = material.id();
            Map<String, Object> differences = QuizEditing.differences(material, values);
            if (!differences.isEmpty()) {
                ctx.changes().update(QbContent.MATERIAL, material.id(), material.version(), differences);
                changed = true;
            }
        }

        List<UUID> imageIds = new ArrayList<>();
        if (images == null) {
            QuizEditing.ordered(existing).forEach(image -> imageIds.add(uuid(image.id())));
        } else {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < images.size(); i++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("seq", decimal(i + 1));
                row.put("image", images.get(i).image());
                row.put("caption", text(images.get(i).caption()));
                rows.add(row);
            }
            changed |= QuizEditing.saveParts(ctx, QbContent.MATERIAL_IMAGE, existing,
                images.stream().map(ImageInput::imageId).toList(), rows,
                Map.of("materialId", materialId, "quizId", quiz.id(), "ownerId", owner), imageIds);
        }

        if (!changed) {
            QuizEditing.unchanged(ctx, quiz, uuid(materialId), imageIds);
            return;
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        if (material == null && !variant) {
            counts.put("materialCount", decimal(bases.size() + 1));
        }
        QuizEditing.touch(ctx, quiz, counts, uuid(materialId), imageIds);
    }

    public static ProcessDefinition<MaterialRef, EditOutput, ProcessContext> delete() {
        return ProcessDefinition.define(DELETE, 1, MaterialRef.class, EditOutput.class,
            ProcessContext.class, pb -> pb
                .description("Deletes a reference material of a quiz with its images.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .actsOn(QbContent.MATERIAL, "materialId")
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the materials", QbContent.MATERIAL, MATERIALS))
                // Q73a: its images, and its other languages' materials with theirs.
                .step("Load its images", com.jabiz.runtime.process.steps.QueryEntities.<ProcessContext>of(
                    QbContent.defaultDataset(QbContent.MATERIAL_IMAGE), ctx -> {
                        UUID id = ctx.get(INPUT, MaterialRef.class).materialId();
                        List<Object> ids = new ArrayList<>();
                        for (EntityInstance m : list(ctx, MATERIALS)) {
                            if (QuizEditing.same(m.id(), id) || QuizEditing.same(m.get("variantOf"), id)) {
                                ids.add(m.id());
                            }
                        }
                        return com.jabiz.query.EntityQuery.builder().where(new com.jabiz.query.QueryPredicate.And(
                            List.of(new com.jabiz.query.QueryPredicate.Eq("quizId", ctx.get(QuizEditing.QUIZ_ID)),
                                new com.jabiz.query.QueryPredicate.In("materialId", ids))))
                            .limit(Integer.MAX_VALUE).build();
                    }, IMAGES))
                .compute("Delete the material", (metadata, ctx) -> {
                    MaterialRef input = ctx.get(INPUT, MaterialRef.class);
                    EntityInstance quiz = quiz(ctx);
                    if (!QuizEditing.current(ctx, quiz, input.baseRevision())) {
                        return;
                    }
                    List<EntityInstance> materials = list(ctx, MATERIALS);
                    EntityInstance material = QuizEditing.find(materials, input.materialId());
                    if (material == null) {
                        ctx.reject(QuizEditing.notInQuiz("materialId", input.materialId()));
                        return;
                    }
                    // Images first, then the other languages' materials: a material still referred to cannot be
                    // deleted.
                    TranslationProcesses.deleteVariants(ctx, materials.stream()
                        .filter(m -> QuizEditing.same(m.get("variantOf"), material.id())).toList());
                    list(ctx, IMAGES).stream().filter(image -> QuizEditing.same(image.get("materialId"), material.id()))
                        .forEach(image -> ctx.changes().delete(QbContent.MATERIAL_IMAGE, image.id(),
                            image.version()));
                    ctx.changes().delete(QbContent.MATERIAL, material.id(), material.version());
                    boolean ofBase = material.get("variantOf") == null;
                    QuizEditing.touch(ctx, quiz, ofBase ? Map.of("materialCount",
                        decimal(QuizLanguages.bases(materials).size() - 1)) : Map.of(), uuid(material.id()),
                        List.of());
                }));
    }

    public static ProcessDefinition<ReorderInput, EditOutput, ProcessContext> reorder() {
        return ProcessDefinition.define(REORDER, 1, ReorderInput.class, EditOutput.class,
            ProcessContext.class, pb -> pb
                .description("Puts the reference materials of a quiz in a new order.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the materials", QbContent.MATERIAL, MATERIALS))
                .compute("Reorder the materials", (metadata, ctx) -> {
                    ReorderInput input = ctx.get(INPUT, ReorderInput.class);
                    QuizEditing.reorder(ctx, QbContent.MATERIAL, QuizLanguages.bases(list(ctx, MATERIALS)),
                        input.materialIds(),
                        "materialIds", input.baseRevision());
                }));
    }

    private MaterialProcesses() {}
}

package com.jabiz.quizbuks.content;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
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
     */
    public record MaterialInput(@NotNull UUID quizId, UUID materialId,
        @NotNull @Pattern(regexp = "ARTICLE|LINK|VIDEO_LINK|PDF|IMAGES|AUDIO") String kind, String title,
        String description, String body, String url, UUID pdf, UUID audio,
        @Size(max = ContentLimits.MAX_IMAGES) List<@Valid @NotNull ImageInput> images, Long baseRevision) {}

    public record MaterialRef(@NotNull UUID quizId, @NotNull UUID materialId, Long baseRevision) {}

    /** Every material of the quiz, in the new order. */
    public record ReorderInput(@NotNull UUID quizId,
        @NotNull @Size(max = ContentLimits.MAX_MATERIALS) List<@NotNull UUID> materialIds, Long baseRevision) {}

    public static ProcessDefinition<MaterialInput, EditOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, MaterialInput.class, EditOutput.class,
            ProcessContext.class, pb -> pb
                .description("Adds a reference material to a quiz or changes one, with its images.")
                .permissions(QbPermissions.CONTENT_WRITE)
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

    static void save(ProcessContext ctx) {
        MaterialInput input = ctx.get(INPUT, MaterialInput.class);
        EntityInstance quiz = quiz(ctx);
        if (!QuizEditing.current(ctx, quiz, input.baseRevision())) {
            return;
        }
        List<EntityInstance> materials = list(ctx, MATERIALS);
        EntityInstance material = null;
        if (input.materialId() != null) {
            material = QuizEditing.find(materials, input.materialId());
            if (material == null) {
                ctx.reject(QuizEditing.notInQuiz("materialId", input.materialId()));
                return;
            }
            if (!input.kind().equals(material.get("kind"))) {
                ctx.reject(new Violation("kind", ContentCodes.MATERIAL_KIND_FIXED, "A material stays of the kind it "
                    + "was made with: " + material.get("kind"), Map.of("kind", material.get("kind"))));
                return;
            }
        } else if (materials.size() >= ContentLimits.MAX_MATERIALS) {
            ctx.reject(new Violation("materialId", ContentCodes.TOO_MANY_MATERIALS, "A quiz has at most "
                + ContentLimits.MAX_MATERIALS + " materials", Map.of("max", ContentLimits.MAX_MATERIALS)));
            return;
        }
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
        boolean changed = false;
        Object materialId;
        if (material == null) {
            values.put("quizId", quiz.id());
            values.put("ownerId", quiz.get("ownerId"));
            values.put("seq", decimal(QuizEditing.nextSeq(ctx, QbContent.MATERIAL, materials)));
            values.put("kind", kind.name());
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
        if (material == null) {
            counts.put("materialCount", decimal(materials.size() + 1));
        }
        QuizEditing.touch(ctx, quiz, counts, uuid(materialId), imageIds);
    }

    public static ProcessDefinition<MaterialRef, EditOutput, ProcessContext> delete() {
        return ProcessDefinition.define(DELETE, 1, MaterialRef.class, EditOutput.class,
            ProcessContext.class, pb -> pb
                .description("Deletes a reference material of a quiz with its images.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .actsOn(QbContent.MATERIAL, "materialId")
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the materials", QbContent.MATERIAL, MATERIALS))
                .steps(b -> QuizEditing.loadPartsOf(b, "Load its images", QbContent.MATERIAL_IMAGE, "materialId",
                    ctx -> ctx.get(INPUT, MaterialRef.class).materialId(), IMAGES))
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
                    // Images first: a material still referred to cannot be deleted.
                    list(ctx, IMAGES).forEach(image -> ctx.changes().delete(QbContent.MATERIAL_IMAGE, image.id(),
                        image.version()));
                    ctx.changes().delete(QbContent.MATERIAL, material.id(), material.version());
                    QuizEditing.touch(ctx, quiz, Map.of("materialCount", decimal(materials.size() - 1)),
                        uuid(material.id()), List.of());
                }));
    }

    public static ProcessDefinition<ReorderInput, EditOutput, ProcessContext> reorder() {
        return ProcessDefinition.define(REORDER, 1, ReorderInput.class, EditOutput.class,
            ProcessContext.class, pb -> pb
                .description("Puts the reference materials of a quiz in a new order.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .contextFactory((start, input) -> QuizEditing.start(start, input, input.quizId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, EditOutput.class))
                .steps(QuizEditing::lockAndLoad)
                .steps(b -> QuizEditing.loadAll(b, "Load the materials", QbContent.MATERIAL, MATERIALS))
                .compute("Reorder the materials", (metadata, ctx) -> {
                    ReorderInput input = ctx.get(INPUT, ReorderInput.class);
                    QuizEditing.reorder(ctx, QbContent.MATERIAL, list(ctx, MATERIALS), input.materialIds(),
                        "materialIds", input.baseRevision());
                }));
    }

    private MaterialProcesses() {}
}

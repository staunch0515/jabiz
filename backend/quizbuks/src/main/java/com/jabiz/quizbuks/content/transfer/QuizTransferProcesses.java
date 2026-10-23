package com.jabiz.quizbuks.content.transfer;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.content.QuizEditing;
import com.jabiz.quizbuks.content.QuizProcesses;
import com.jabiz.quizbuks.content.QuizSnapshot;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.file.FileEntities;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Importing a quiz file into a new draft (docs/quizbuks/plans/Q16-quiz-transfer.md). The export is the sponsor
 * console's own (it reads the draft and its files as the editor does); the server never reads a file's data. Both
 * processes read the file with {@link QuizFileFormat} (which version, which readers) and check it with
 * {@link QuizFileChecks}; a refusal lists every problem (422).
 * <ul>
 *   <li>{@value #CHECK}: the file without its files' data, before anything is uploaded: what the import would make
 *       and which files it needs. Writes nothing.</li>
 *   <li>{@value #IMPORT}: the same file with the keys of the files uploaded for it (each by the caller, through the
 *       content's policies): the quiz with all its questions and materials in one transaction, as a copy makes
 *       one ({@link QuizProcesses#insertContent}); nothing at all when anything is wrong.</li>
 * </ul>
 */
public final class QuizTransferProcesses {

    public static final String CHECK = "QB_QUIZ_IMPORT_CHECK";
    public static final String IMPORT = "QB_QUIZ_IMPORT";

    private static final String INPUT = "input";
    private static final String READING = "reading";
    private static final String OUTPUT = "output";

    /** @param document the file's JSON; its files' {@code data} may be left out (they are never read) */
    public record CheckInput(@NotNull Map<String, Object> document) {}

    /** A file the import needs uploaded: its key in the file and the policy to upload it with. */
    public record FileNeed(String key, String policy, String name, String type, long size) {}

    /**
     * @param formatVersion the file's own version
     * @param readers       the versions it is read through, its own first, the current one last
     */
    public record CheckOutput(int formatVersion, int currentVersion, List<Integer> readers, String kind,
        String title, int questions, int materials, List<FileNeed> files) {}

    /** @param files the key of each file the quiz uses (its key in the file) as uploaded by the caller */
    public record ImportInput(@NotNull Map<String, Object> document, Map<String, UUID> files) {}

    public record ImportOutput(UUID quizId, int formatVersion, List<Integer> readers) {}

    private QuizTransferProcesses() {}

    public static ProcessDefinition<CheckInput, CheckOutput, ProcessContext> check() {
        return ProcessDefinition.define(CHECK, 1, CheckInput.class, CheckOutput.class, ProcessContext.class,
            pb -> pb
                .description("Checks a quiz file before importing it: its format version and its content.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, CheckOutput.class))
                .compute("Read the file", (metadata, ctx) -> {
                    QuizFile file = read(ctx, ctx.get(INPUT, CheckInput.class).document());
                    if (file == null) {
                        return;
                    }
                    QuizFileFormat.Reading reading = ctx.get(READING, QuizFileFormat.Reading.class);
                    List<FileNeed> needs = new ArrayList<>();
                    QuizFileChecks.needed(file).forEach((key, field) -> {
                        QuizFile.FileEntry entry = file.files().get(key);
                        needs.add(new FileNeed(key, field.policy(), entry.name(), entry.type(), entry.size()));
                    });
                    ctx.put(OUTPUT, new CheckOutput(reading.version(), QuizFileFormat.CURRENT, reading.readers(),
                        file.kind(), file.title(), file.questions().size(), file.materials().size(), needs));
                }));
    }

    public static ProcessDefinition<ImportInput, ImportOutput, ProcessContext> importQuiz() {
        return ProcessDefinition.define(IMPORT, 1, ImportInput.class, ImportOutput.class, ProcessContext.class,
            pb -> pb
                .description("Imports a quiz file into a new draft, with the files uploaded for it.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, ImportOutput.class))
                .steps(b -> QuizEditing.loadFiles(b, ctx -> {
                    Map<String, UUID> files = ctx.get(INPUT, ImportInput.class).files();
                    return files == null ? List.of() : List.copyOf(files.values());
                }))
                .compute("Import the quiz", (metadata, ctx) -> imported(ctx)));
    }

    /** The file, read and checked; null (with the problems rejected) when it cannot be imported. */
    private static QuizFile read(ProcessContext ctx, Map<String, ?> document) {
        QuizFileFormat.Reading reading = QuizFileFormat.read(document);
        ctx.put(READING, reading);
        if (!reading.supported()) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("status", reading.status().name());
            params.put("version", reading.version() == null ? "" : reading.version());
            params.put("current", QuizFileFormat.CURRENT);
            params.put("oldest", QuizFileFormat.OLDEST);
            ctx.reject(new Violation(null, ContentCodes.QUIZ_FILE_UNSUPPORTED, reading.problem(), params));
            return null;
        }
        List<Violation> problems = QuizFileChecks.check(reading.file());
        problems.forEach(ctx::reject);
        return problems.isEmpty() ? reading.file() : null;
    }

    static void imported(ProcessContext ctx) {
        ImportInput input = ctx.get(INPUT, ImportInput.class);
        QuizFile file = read(ctx, input.document());
        if (file == null) {
            return;
        }
        String actor = ctx.request().actorId();
        Map<String, UUID> given = input.files() == null ? Map.of() : input.files();
        Map<String, UUID> ids = new LinkedHashMap<>();
        for (QuizFileChecks.Use use : QuizFileChecks.uses(file)) {
            UUID fileId = given.get(use.key());
            EntityInstance uploaded = fileId == null ? null : find(QuizEditing.list(ctx, QuizEditing.FILES), fileId);
            if (uploaded == null) {
                ctx.reject(new Violation(use.at(), ContentCodes.QUIZ_FILE_NOT_UPLOADED, "File " + use.key()
                    + " was not uploaded for the import", Map.of("file", use.key())));
            } else if (!actor.equals(uploaded.<Object>get(FileEntities.UPLOADED_BY))) {
                ctx.reject(new Violation(use.at(), ContentCodes.FILE_NOT_OWN, "File " + fileId + " was not uploaded "
                    + "by the caller", Map.of("fileId", fileId.toString())));
            } else if (!use.field().policy().equals(uploaded.<Object>get(FileEntities.POLICY))) {
                ctx.reject(new Violation(use.at(), ContentCodes.QUIZ_FILE_TYPE, "File " + use.key()
                    + " was not uploaded as " + use.field().policy(), Map.of("file", use.key(), "type",
                    String.valueOf(uploaded.<Object>get(FileEntities.CONTENT_TYPE)))));
            } else {
                ids.put(use.key(), fileId);
            }
        }
        if (ctx.hasViolations()) {
            return;
        }
        Object quizId = QuizProcesses.insertContent(ctx, snapshot(file, ids), file.title(), false, null);
        QuizFileFormat.Reading reading = ctx.get(READING, QuizFileFormat.Reading.class);
        ctx.put(OUTPUT, new ImportOutput(QuizEditing.uuid(quizId), reading.version(), reading.readers()));
    }

    /** The file as a draft's content, its files by their uploaded keys, numbered in their order. */
    static QuizSnapshot snapshot(QuizFile file, Map<String, UUID> ids) {
        List<QuizSnapshot.Material> materials = new ArrayList<>();
        for (int m = 0; m < file.materials().size(); m++) {
            QuizFile.Material material = file.materials().get(m);
            List<QuizSnapshot.Image> images = new ArrayList<>();
            for (int i = 0; i < material.images().size(); i++) {
                QuizFile.Image image = material.images().get(i);
                images.add(new QuizSnapshot.Image(i + 1, ids.get(image.image()), image.caption()));
            }
            materials.add(new QuizSnapshot.Material(m + 1, material.kind(), material.title(), material.description(),
                material.body(), material.url(), key(ids, material.pdf()), key(ids, material.audio()), images));
        }
        boolean survey = "SURVEY".equals(file.kind());
        List<QuizSnapshot.Question> questions = new ArrayList<>();
        for (int q = 0; q < file.questions().size(); q++) {
            QuizFile.Question question = file.questions().get(q);
            List<QuizSnapshot.Option> options = new ArrayList<>();
            for (int o = 0; o < question.options().size(); o++) {
                QuizFile.Option option = question.options().get(o);
                options.add(new QuizSnapshot.Option(o + 1, option.text(), key(ids, option.image()), option.correct()));
            }
            questions.add(new QuizSnapshot.Question(q + 1, question.stem(), key(ids, question.image()),
                question.points(), options, survey ? question.selection() : null));
        }
        return new QuizSnapshot(file.title(), file.intro(), key(ids, file.cover()), file.timeLimitSec(), false,
            materials, questions, survey);
    }

    private static UUID key(Map<String, UUID> ids, String key) {
        return key == null ? null : ids.get(key);
    }

    private static EntityInstance find(List<EntityInstance> files, UUID fileId) {
        return files.stream()
            .filter(f -> fileId.toString().equals(String.valueOf(f.<Object>get(FileEntities.FILE_ID))))
            .findFirst().orElse(null);
    }
}

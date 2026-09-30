package com.jabiz.runtime.file;

import com.jabiz.job.JobDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.publicread.FileAccess;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The processes that write {@code SysFile} (docs/design/14-files.md sections 1 and 6):
 * <ul>
 *   <li>{@code FILE_REGISTER} (internal): records an uploaded file once its content is stored; run by the upload
 *       endpoint only;</li>
 *   <li>{@code FILE_DELETE}: deletes a file nothing refers to any more (422 {@code FILE_IN_USE} otherwise); the row
 *       goes with the transaction, the content after it commits. A business process that erases personal data
 *       clears the referring rows, runs {@code SaveChanges.now}, then calls this one as a sub-process;</li>
 *   <li>{@code FILE_PURGE_ORPHANS}: run by the job {@code FILE_SWEEP}; deletes old files nothing refers to, then
 *       stored objects without a row.</li>
 * </ul>
 */
@Configuration
public class FileProcesses {

    public static final String REGISTER = "FILE_REGISTER";
    public static final String DELETE = "FILE_DELETE";
    public static final String PURGE_ORPHANS = "FILE_PURGE_ORPHANS";
    public static final String SWEEP_JOB = "FILE_SWEEP";

    static final String FILE_ID = "fileId";
    static final String FILE = "file";
    static final String ORPHANS = "orphans";
    static final String KEPT = "kept";
    static final String OUTPUT = "output";

    /**
     * A stored upload. Only the file name could hold personal data; it is masked in operation records.
     *
     * @param variants names of the stored image variants, narrowest first
     */
    public record RegisterInput(@NotNull UUID fileId, @NotBlank String policy, @NotBlank String contentType,
        @PositiveOrZero long sizeBytes, @NotBlank String sha256, Integer width, Integer height, List<String> variants,
        @Sensitive @NotBlank String originalName) {

        public RegisterInput {
            variants = variants == null ? List.of() : List.copyOf(variants);
        }

        @Override
        public String toString() {
            return "RegisterInput[fileId=" + fileId + ", policy=" + policy + ", contentType=" + contentType
                + ", sizeBytes=" + sizeBytes + ", originalName=***]";
        }
    }

    /** What the upload endpoint answers with. */
    public record FileInfo(UUID fileId, String policy, String contentType, long sizeBytes, Integer width,
        Integer height, List<String> variants) {}

    public record DeleteInput(@NotNull UUID fileId) {}

    public record DeleteOutput(UUID fileId) {}

    /** @param scheduledTime the scheduled time of the sweep run (unused by the purge itself; identifies the run) */
    public record PurgeInput(Instant scheduledTime) {}

    /**
     * @param fileIds    the files whose rows were deleted; their content goes after the commit
     * @param keptFileIds orphans kept because of their retention or a legal hold (docs/design/21 section 3.2)
     */
    public record PurgeOutput(int deletedFiles, List<UUID> fileIds, List<UUID> keptFileIds) {}

    static ProcessDefinition<RegisterInput, FileInfo, ProcessContext> register() {
        return ProcessDefinition.define(REGISTER, 1, RegisterInput.class, FileInfo.class, ProcessContext.class, pb -> pb
            .description("Records a file whose content has been stored.")
            .permissions(FilePermissions.WRITE)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, FileInfo.class))
            .compute("Record the file", (metadata, ctx) -> {
                RegisterInput input = ctx.get("input", RegisterInput.class);
                Map<String, Object> file = new LinkedHashMap<>();
                file.put(FileEntities.FILE_ID, input.fileId());
                file.put(FileEntities.POLICY, input.policy());
                file.put(FileEntities.CONTENT_TYPE, input.contentType());
                file.put(FileEntities.SIZE_BYTES, input.sizeBytes());
                file.put(FileEntities.SHA256, input.sha256());
                file.put(FileEntities.WIDTH, input.width());
                file.put(FileEntities.HEIGHT, input.height());
                file.put(FileEntities.VARIANTS, input.variants().isEmpty() ? null : String.join(",", input.variants()));
                file.put(FileEntities.ORIGINAL_NAME, input.originalName());
                file.put(FileEntities.UPLOADED_BY, ctx.request().actorId());
                ctx.changes().insert(FileEntities.ENTITY, file);
                ctx.put(OUTPUT, new FileInfo(input.fileId(), input.policy(), input.contentType(), input.sizeBytes(),
                    input.width(), input.height(), input.variants()));
            }));
    }

    static ProcessDefinition<DeleteInput, DeleteOutput, ProcessContext> delete() {
        return ProcessDefinition.define(DELETE, 1, DeleteInput.class, DeleteOutput.class, ProcessContext.class, pb -> pb
            .description("Deletes a file that no current data refers to.")
            .permissions(FilePermissions.DELETE)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(FILE_ID, input.fileId());
                return ctx;
            })
            .outputMapper(ctx -> new DeleteOutput(ctx.get(FILE_ID, UUID.class)))
            .step("Load the file", LoadEntity.by(FileEntities.DATASET, FILE_ID, FILE))
            .step("Check that nothing refers to it", CheckFileUnused.of(FILE_ID))
            .compute("Delete the file", (metadata, ctx) -> {
                EntityInstance file = ctx.get(FILE, EntityInstance.class);
                ctx.changes().delete(FileEntities.ENTITY, file.id(), file.version());
            })
            .afterCommit("Delete the stored content", DeleteStoredContent.of(FILE_ID))
            .afterCommit("Forget whether it was public", FileAccess.invalidate(FileAccess.fromContext(FILE_ID))));
    }

    static ProcessDefinition<PurgeInput, PurgeOutput, ProcessContext> purgeOrphans() {
        return ProcessDefinition.define(PURGE_ORPHANS, 1, PurgeInput.class, PurgeOutput.class, ProcessContext.class,
            pb -> pb
                .description("Deletes old files that no current data refers to, and stored objects without a file.")
                .permissions(FilePermissions.DELETE)
                .contextFactory((start, input) -> new ProcessContext(start))
                .outputMapper(ctx -> ctx.get(OUTPUT, PurgeOutput.class))
                .step("Find orphan files", FindOrphanFiles.into(ORPHANS))
                .step("Keep those to be retained or held", SkipKeptFiles.of(ORPHANS, KEPT))
                .compute("Delete them", (metadata, ctx) -> {
                    @SuppressWarnings("unchecked")
                    List<EntityInstance> orphans = (List<EntityInstance>) ctx.get(ORPHANS);
                    for (EntityInstance orphan : orphans) {
                        ctx.changes().delete(FileEntities.ENTITY, orphan.id(), orphan.version());
                    }
                    @SuppressWarnings("unchecked")
                    List<UUID> kept = (List<UUID>) ctx.get(KEPT);
                    ctx.put(OUTPUT, new PurgeOutput(orphans.size(),
                        orphans.stream().map(orphan -> (UUID) orphan.id()).toList(), kept));
                })
                .afterCommit("Delete their content and orphan objects", PurgeStoredContent.of(ORPHANS)));
    }

    public static final ProcessDefinition<RegisterInput, FileInfo, ProcessContext> REGISTER_PROCESS = register();
    public static final ProcessDefinition<DeleteInput, DeleteOutput, ProcessContext> DELETE_PROCESS = delete();
    public static final ProcessDefinition<PurgeInput, PurgeOutput, ProcessContext> PURGE_ORPHANS_PROCESS =
        purgeOrphans();

    @Bean
    ProcessDefinition<RegisterInput, FileInfo, ProcessContext> fileRegisterProcess() {
        return REGISTER_PROCESS;
    }

    @Bean
    ProcessDefinition<DeleteInput, DeleteOutput, ProcessContext> fileDeleteProcess() {
        return DELETE_PROCESS;
    }

    @Bean
    ProcessDefinition<PurgeInput, PurgeOutput, ProcessContext> filePurgeOrphansProcess() {
        return PURGE_ORPHANS_PROCESS;
    }

    /** The sweep (docs/design/14-files.md section 6): daily by default ({@code jabiz.files.sweep-cron}, UTC). */
    @Bean
    JobDefinition<PurgeInput> fileSweepJob(FileProperties properties) {
        return JobDefinition.cron(SWEEP_JOB, properties.sweepCron(), ZoneOffset.UTC, PURGE_ORPHANS_PROCESS,
            PurgeInput::new);
    }
}

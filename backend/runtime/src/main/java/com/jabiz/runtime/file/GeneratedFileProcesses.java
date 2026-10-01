package com.jabiz.runtime.file;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/**
 * {@code FILE_ARCHIVE} (docs/design/14-files.md section 10, decision D31): keeps a file the server made - a bank's
 * payment file, a tax filing - exactly as made, with its SHA-256, readable by whoever holds the permissions it is kept
 * with. Business processes call it as their sub-process ({@code CallProcess.of("FILE_ARCHIVE", 1, ...)}) after their
 * own checks; the content never appears in the process records.
 */
@Configuration
public class GeneratedFileProcesses {

    public static final String ARCHIVE = "FILE_ARCHIVE";

    /** What a generated file may be: text formats a bank, an authority or a spreadsheet reads. */
    public static final Set<String> MEDIA_TYPES = Set.of("text/plain", "text/csv", "application/xml",
        "application/json");

    static final String INPUT = "input";
    static final String OUTPUT = "output";

    /**
     * @param fileName      the name it is downloaded under: letters, digits, {@code . - _} and spaces
     * @param mediaType     one of {@link #MEDIA_TYPES}
     * @param fileContent   the bytes as made (a distinctive name: {@code @Sensitive} masks the name everywhere)
     * @param permissions   what reading it needs besides {@code file.generated.read}; at least one
     * @param subjectEntity what it was made for, such as a payment run; optional
     * @param subjectId     the id of that, optional
     */
    public record ArchiveInput(@NotBlank @Size(max = 200) String fileName, @NotBlank @Size(max = 100) String mediaType,
        @NotNull @Sensitive byte[] fileContent, @NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 100) String> permissions,
        @Size(max = 100) String subjectEntity, @Size(max = 100) String subjectId) {

        /** Never the content: it may hold account numbers. */
        @Override
        public String toString() {
            return "ArchiveInput[fileName=" + fileName + ", mediaType=" + mediaType + ", fileContent=***, permissions="
                + permissions + ", subjectEntity=" + subjectEntity + ", subjectId=" + subjectId + "]";
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ArchiveInput that && fileName.equals(that.fileName)
                && java.util.Arrays.equals(fileContent, that.fileContent) && mediaType.equals(that.mediaType)
                && permissions.equals(that.permissions) && java.util.Objects.equals(subjectEntity, that.subjectEntity)
                && java.util.Objects.equals(subjectId, that.subjectId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(fileName, mediaType, java.util.Arrays.hashCode(fileContent), permissions,
                subjectEntity, subjectId);
        }
    }

    public record ArchiveOutput(String fileId, String sha256, int size) {}

    @Bean
    ProcessDefinition<ArchiveInput, ArchiveOutput, ProcessContext> fileArchiveProcess() {
        return ProcessDefinition.define(ARCHIVE, 1, ArchiveInput.class, ArchiveOutput.class, ProcessContext.class, pb -> pb
            .description("Keeps a file the server made exactly as made, with its hash.")
            .permissions(FilePermissions.GENERATED_ARCHIVE)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, ArchiveOutput.class))
            .step("Keep the file", ArchiveGeneratedFile.class, NoMetadata.INSTANCE));
    }
}

package com.jabiz.runtime.file;

import com.jabiz.entity.Violation;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** The step of {@code FILE_ARCHIVE} (docs/design/14-files.md section 10). */
@Component
class ArchiveGeneratedFile implements StepHandler<NoMetadata, ProcessContext> {

    static final String NOT_ALLOWED = "GENERATED_FILE_NOT_ALLOWED";
    static final String TOO_LARGE = "GENERATED_FILE_TOO_LARGE";

    // A permission code, never the wildcard: a file kept for "*" would be readable by administrators only.
    private static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,199}");

    private final GeneratedFiles files;
    private final EntityIdGenerator ids;
    private final long maxBytes;

    ArchiveGeneratedFile(GeneratedFiles files, EntityIdGenerator ids,
        @Value("${jabiz.files.generated.max-bytes:10485760}") long maxBytes) {
        this.files = files;
        this.ids = ids;
        this.maxBytes = maxBytes;
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            GeneratedFileProcesses.ArchiveInput input = ctx.get(GeneratedFileProcesses.INPUT,
                GeneratedFileProcesses.ArchiveInput.class);
            // A sub-process's input is not bean-validated (CallProcess), so the step checks everything itself.
            if (input.fileName() == null || input.mediaType() == null || input.fileContent() == null) {
                return refuse("fileName", NOT_ALLOWED, "A generated file has a name, a media type and content",
                    Map.of());
            }
            if (input.permissions() == null || input.permissions().isEmpty() || input.permissions().size() > 20
                || !input.permissions().stream().allMatch(p -> p != null && PERMISSION.matcher(p).matches())) {
                return refuse("permissions", NOT_ALLOWED, "A generated file is kept with 1 to 20 permission codes",
                    Map.of());
            }
            if (tooLong(input.subjectEntity()) || tooLong(input.subjectId())) {
                return refuse("subjectId", NOT_ALLOWED, "A generated file's subject has at most 100 characters",
                    Map.of());
            }
            if (!GeneratedFileProcesses.MEDIA_TYPES.contains(input.mediaType())) {
                return refuse("mediaType", NOT_ALLOWED, "A generated file is one of " + GeneratedFileProcesses.MEDIA_TYPES
                    + ", not " + input.mediaType(), Map.of("mediaType", input.mediaType()));
            }
            if (!NAME.matcher(input.fileName()).matches()) {
                return refuse("fileName", NOT_ALLOWED, "A generated file's name has letters, digits, . - _ and spaces",
                    Map.of("fileName", input.fileName()));
            }
            if (input.fileContent().length == 0 || input.fileContent().length > maxBytes) {
                return refuse("content", TOO_LARGE, "A generated file has 1 to " + maxBytes + " bytes, not "
                    + input.fileContent().length, Map.of("size", input.fileContent().length, "max", maxBytes));
            }
            // Not an entity of the metamodel: the generator is asked for a plain UUIDv7.
            UUID fileId = UUID.fromString(String.valueOf(ids.next(null)));
            String sha256 = sha256(input.fileContent());
            GeneratedFiles.GeneratedFile file = new GeneratedFiles.GeneratedFile(fileId, input.fileName(),
                input.mediaType(), input.fileContent(), sha256, input.fileContent().length, List.copyOf(input.permissions()),
                input.subjectEntity(), input.subjectId(), ctx.request().actorId(), ctx.opTime(), ctx.processSeqId());
            return files.insert(file).then(Mono.fromRunnable(() -> ctx.put(GeneratedFileProcesses.OUTPUT,
                new GeneratedFileProcesses.ArchiveOutput(fileId.toString(), sha256, input.fileContent().length))));
        });
    }

    private static boolean tooLong(String value) {
        return value != null && value.length() > 100;
    }

    private static Mono<Void> refuse(String field, String code, String message, Map<String, Object> params) {
        return Mono.error(new BusinessRuleViolationException(new Violation(field, code, message, params)));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

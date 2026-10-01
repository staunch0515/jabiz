package com.jabiz.runtime.file;

import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.security.Permissions;
import com.jabiz.runtime.security.RevealRecorder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Downloading a file the server made (docs/design/14-files.md section 10): {@code file.generated.read} and every
 * permission it was kept with, or it is not found. The bytes are checked against the kept hash first, and every
 * download is recorded as its values leaving in plain text ({@code sys_reveal_record}, kind FILE) before they leave.
 */
@RestController
class GeneratedFileController {

    static final String HASH_HEADER = "X-Jabiz-Sha256";

    private final GeneratedFiles files;
    private final RevealRecorder reveals;
    private final boolean development;

    GeneratedFileController(GeneratedFiles files, RevealRecorder reveals, Environment environment) {
        this.files = files;
        this.reveals = reveals;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
    }

    @GetMapping("/api/generated-files/{fileId}")
    Mono<ResponseEntity<byte[]>> download(@PathVariable String fileId) {
        return RequestContexts.current().flatMap(context -> {
            Permissions.requireDeclared(context, FilePermissions.GENERATED_READ, development,
                "Downloading generated files");
            UUID id;
            try {
                id = UUID.fromString(fileId);
            } catch (IllegalArgumentException e) {
                return Mono.error(new EntityNotFoundException("Unknown generated file: " + fileId));
            }
            return files.find(id)
                // Kept without permissions is never readable, not even in dev (allowsAll lets an empty list pass there).
                .filter(file -> !file.permissions().isEmpty()
                    && Permissions.allowsAll(context, file.permissions(), development))
                .switchIfEmpty(Mono.error(new EntityNotFoundException("Unknown generated file: " + fileId)))
                .flatMap(file -> {
                    if (!ArchiveGeneratedFile.sha256(file.content()).equals(file.sha256())) {
                        return Mono.error(new IllegalStateException("The kept generated file " + fileId
                            + " does not match its hash"));
                    }
                    return reveals.record(context, RevealRecorder.Kind.FILE, "generated-file",
                            file.subjectEntity() == null ? "GeneratedFile" : file.subjectEntity(), file.fileId().toString(),
                            List.of("content"), null)
                        .thenReturn(ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(file.mediaType()))
                            .header(HASH_HEADER, file.sha256())
                            .header("X-Content-Type-Options", "nosniff")
                            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                                .filename(file.fileName()).build().toString())
                            .body(file.content()));
                });
        });
    }
}

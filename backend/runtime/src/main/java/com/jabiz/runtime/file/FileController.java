package com.jabiz.runtime.file;

import com.jabiz.context.RequestContext;
import com.jabiz.file.FileNames;
import com.jabiz.file.FilePolicy;
import com.jabiz.resource.ResourceNotFoundException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.observability.PlatformObservations;
import io.micrometer.common.KeyValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.PartEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Upload and authenticated reading of files (docs/design/14-files.md section 5). Uploading needs the policy's upload
 * permission; reading needs {@code file.read} or the file's policy's read permission. A caller holding no read
 * permission at all is refused (403) before anything is looked up; a file of a policy the caller may not read is
 * answered like a missing one (404). A policy with {@link FilePolicy#privateMetadata()} keeps the uploaded name, the
 * uploader and the exact upload time from readers who are neither the uploader nor holders of {@code file.read}
 * (section 5.1; decision D47).
 */
@RestController
@RequestMapping("/api/files")
class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    /**
     * Metadata of one file.
     *
     * @param originalName the uploaded name; null for a reader its policy keeps it from (decision D47)
     * @param uploadedBy   who uploaded it; null likewise
     * @param uploadedTime when it was uploaded; for such a reader, the start of that day (UTC)
     */
    record FileMeta(UUID fileId, String policy, String contentType, long sizeBytes, Integer width, Integer height,
        List<String> variants, String originalName, String uploadedBy, Instant uploadedTime) {

        /** What a reader kept from the private metadata sees: no name, no uploader, the day only. */
        FileMeta withoutPrivateMetadata() {
            return new FileMeta(fileId, policy, contentType, sizeBytes, width, height, variants, null, null,
                uploadedTime == null ? null : uploadedTime.truncatedTo(ChronoUnit.DAYS));
        }
    }

    /** A file the caller may read, with its metadata as the caller may see it. */
    private record Readable(EntityInstance file, FileMeta meta) {}

    private static final String NO_STORE = "private, no-store";

    private final FileUploadService uploads;
    private final FilePolicyRegistry policies;
    private final FileStore store;
    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final PlatformObservations observations;

    FileController(FileUploadService uploads, FilePolicyRegistry policies, FileStore store,
        DatasetEntityManager entities, DatasetRegistry datasets, PlatformObservations observations) {
        this.uploads = uploads;
        this.policies = policies;
        this.store = store;
        this.entities = entities;
        this.datasets = datasets;
        this.observations = observations;
    }

    /** Uploads one file ({@code multipart/form-data}, exactly one part named {@code file}); 201 with its metadata. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Mono<ResponseEntity<FileProcesses.FileInfo>> upload(@RequestParam("policy") String policy,
        @RequestBody Flux<PartEvent> parts, ServerHttpRequest request) {
        return uploads.upload(policy, parts, request.getHeaders().getContentLength())
            .map(info -> ResponseEntity.status(HttpStatus.CREATED).body(info));
    }

    @GetMapping("/{fileId}")
    Mono<FileMeta> metadata(@PathVariable("fileId") String fileId) {
        return readable(fileId).map(Readable::meta);
    }

    @GetMapping("/{fileId}/content")
    Mono<ResponseEntity<Flux<DataBuffer>>> content(@PathVariable("fileId") String fileId,
        @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        return content(fileId, FileKeys.ORIGINAL, range);
    }

    @GetMapping("/{fileId}/content/{variant}")
    Mono<ResponseEntity<Flux<DataBuffer>>> content(@PathVariable("fileId") String fileId,
        @PathVariable("variant") String variant,
        @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        return observations.mono(PlatformObservations.FILE_SERVE, "serve file", KeyValues.of("channel", "admin"),
            readable(fileId).flatMap(readable -> {
                EntityInstance file = readable.file();
                FileMeta meta = readable.meta();
                String stored = FileVariants.stored(file, variant, policies).orElse(null);
                if (stored == null) {
                    return Mono.error(new ResourceNotFoundException("File " + fileId + " has no variant " + variant));
                }
                String name = downloadName(meta, variant);
                return FileContent.serve(store, FileKeys.key(meta.fileId(), stored), meta.contentType(), name, range,
                        NO_STORE)
                    .switchIfEmpty(Mono.defer(() -> {
                        log.warn("File {} has a row but no stored object {}", meta.fileId(), variant);
                        return Mono.error(new ResourceNotFoundException("The content of file " + fileId
                            + " is missing"));
                    }));
            }));
    }

    /**
     * The file, if the caller may read it, with its metadata as the caller may see it; 403 for a caller who may read
     * no file at all, 404 otherwise.
     */
    private Mono<Readable> readable(String rawId) {
        return RequestContexts.current().flatMap(request -> {
            if (!mayReadSomeFile(request)) {
                throw new PermissionDeniedException(FilePermissions.READ, "Reading files requires permission "
                    + FilePermissions.READ + " or a policy's read permission");
            }
            UUID id = parseId(rawId);
            return entities.findById(datasets.findById(FileEntities.DATASET).orElseThrow(), FileEntities.SYS_FILE, id)
                .filter(file -> mayRead(request, file.get(FileEntities.POLICY)))
                .switchIfEmpty(Mono.error(() -> new ResourceNotFoundException("No file " + rawId)))
                .map(file -> {
                    FileMeta meta = meta(file);
                    return new Readable(file, seesPrivateMetadata(request, meta) ? meta
                        : meta.withoutPrivateMetadata());
                });
        });
    }

    /**
     * Whether {@code request} sees the uploaded name, the uploader and the exact upload time of the file: always,
     * unless its policy keeps them private (decision D47); then only its uploader and holders of {@code file.read}.
     */
    private boolean seesPrivateMetadata(RequestContext request, FileMeta meta) {
        boolean kept = policies.find(meta.policy()).map(FilePolicy::privateMetadata).orElse(false);
        return !kept || request.hasPermission(FilePermissions.READ)
            || (meta.uploadedBy() != null && meta.uploadedBy().equals(request.actorId()));
    }

    private boolean mayReadSomeFile(RequestContext request) {
        return request.hasPermission(FilePermissions.READ)
            || policies.all().stream().anyMatch(policy -> request.hasPermission(policy.readPermission()));
    }

    private boolean mayRead(RequestContext request, String policyName) {
        return request.hasPermission(FilePermissions.READ)
            || policies.find(policyName).map(FilePolicy::readPermission).map(request::hasPermission).orElse(false);
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("No file " + raw);
        }
    }

    private static FileMeta meta(EntityInstance file) {
        return new FileMeta((UUID) file.id(), file.get(FileEntities.POLICY), file.get(FileEntities.CONTENT_TYPE),
            ((BigDecimal) file.get(FileEntities.SIZE_BYTES)).longValueExact(), intOrNull(file.get(FileEntities.WIDTH)),
            intOrNull(file.get(FileEntities.HEIGHT)), FileVariants.produced(file),
            file.get(FileEntities.ORIGINAL_NAME), file.get(FileEntities.UPLOADED_BY),
            file.get(FileEntities.UPLOADED_TIME));
    }

    private static Integer intOrNull(Object value) {
        return value == null ? null : ((BigDecimal) value).intValueExact();
    }

    /**
     * The original's name, with the variant marked for variants: {@code photo.jpg} → {@code photo-w320.jpg}; the
     * neutral name of the public files ({@code <fileId>[-<variant>].<ext>}) for a caller who may not see it.
     */
    private static String downloadName(FileMeta meta, String variant) {
        if (meta.originalName() == null) {
            return FileContent.neutralName(meta.fileId(), variant, meta.contentType());
        }
        String name = meta.originalName();
        if (FileKeys.ORIGINAL.equals(variant)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        return FileNames.sanitize(dot > 0 ? name.substring(0, dot) + "-" + variant + name.substring(dot)
            : name + "-" + variant);
    }
}

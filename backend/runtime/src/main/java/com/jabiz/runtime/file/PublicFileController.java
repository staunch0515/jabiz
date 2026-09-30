package com.jabiz.runtime.file;

import com.jabiz.file.MediaTypes;
import com.jabiz.resource.ResourceNotFoundException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.publicread.PublicFileAccess;
import com.jabiz.runtime.publicread.PublicProperties;
import io.micrometer.common.KeyValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Anonymous reading of files (docs/design/15-public-access.md section 4; decision D17):
 * {@code GET/HEAD /api/public/files/{fileId}[/{variant}]}. A file is served only while a row visible in a public
 * dataset refers to it through a whitelisted file field ({@link PublicFileAccess}); otherwise, as for a file that does
 * not exist, 404. The response headers are those of the authenticated endpoint, cacheable publicly; the download
 * name is made from the id, never the uploaded name (which may be personal).
 */
@RestController
@RequestMapping("/api/public/files")
class PublicFileController {

    private static final Logger log = LoggerFactory.getLogger(PublicFileController.class);

    private final PublicFileAccess access;
    private final FileStore store;
    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final PublicProperties properties;
    private final PlatformObservations observations;

    PublicFileController(PublicFileAccess access, FileStore store, DatasetEntityManager entities,
        DatasetRegistry datasets, PublicProperties properties, PlatformObservations observations) {
        this.access = access;
        this.store = store;
        this.entities = entities;
        this.datasets = datasets;
        this.properties = properties;
        this.observations = observations;
    }

    @RequestMapping(path = "/{fileId}", method = {RequestMethod.GET, RequestMethod.HEAD})
    Mono<ResponseEntity<Flux<DataBuffer>>> original(@PathVariable("fileId") String fileId,
        @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        return serve(fileId, FileKeys.ORIGINAL, range);
    }

    @RequestMapping(path = "/{fileId}/{variant}", method = {RequestMethod.GET, RequestMethod.HEAD})
    Mono<ResponseEntity<Flux<DataBuffer>>> variant(@PathVariable("fileId") String fileId,
        @PathVariable("variant") String variant,
        @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        return serve(fileId, variant, range);
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> serve(String rawId, String variant, String range) {
        if (!properties.on()) {
            // The filter answers first; this only guards against a path it did not recognise.
            throw notFound(rawId);
        }
        UUID id = parseId(rawId);
        if (!FileKeys.VARIANT.matcher(variant).matches()) {
            throw notFound(rawId);
        }
        return observations.mono(PlatformObservations.FILE_SERVE, "serve public file",
            KeyValues.of("channel", "public"),
            access.isPublic(id)
                .filter(Boolean::booleanValue)
                // The file's row lives in a platform table; anonymous visitors never read it through an API.
                .flatMap(allowed -> entities.findById(datasets.findById(FileEntities.DATASET).orElseThrow(),
                    FileEntities.SYS_FILE, id))
                .filter(file -> FileKeys.ORIGINAL.equals(variant) || variants(file).contains(variant))
                // Import files are never public, whatever a public row refers to (decision D26).
                .filter(file -> !importOnly(file.get(FileEntities.CONTENT_TYPE)))
                .switchIfEmpty(Mono.error(() -> notFound(rawId)))
                .flatMap(file -> {
                    String contentType = file.get(FileEntities.CONTENT_TYPE);
                    return FileContent.serve(store, FileKeys.key(id, variant), contentType,
                            downloadName(id, variant, contentType),
                            range, "public, max-age=" + properties.fileCacheSeconds())
                        .switchIfEmpty(Mono.defer(() -> {
                            log.warn("File {} has a row but no stored object {}", id, variant);
                            return Mono.error(notFound(rawId));
                        }));
                }));
    }

    private static boolean importOnly(String contentType) {
        return Arrays.stream(MediaTypes.values())
            .anyMatch(type -> type.isImportOnly() && type.contentType().equals(contentType));
    }

    private static List<String> variants(EntityInstance file) {
        String variants = file.get(FileEntities.VARIANTS);
        return variants == null || variants.isBlank() ? List.of() : Arrays.asList(variants.split(","));
    }

    private static String downloadName(UUID id, String variant, String contentType) {
        String extension = Arrays.stream(MediaTypes.values()).filter(t -> t.contentType().equals(contentType))
            .map(MediaTypes::extension).findFirst().orElse("bin");
        return id + (FileKeys.ORIGINAL.equals(variant) ? "" : "-" + variant) + "." + extension;
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw notFound(raw);
        }
    }

    private static ResourceNotFoundException notFound(String raw) {
        return new ResourceNotFoundException("No public file " + raw);
    }
}

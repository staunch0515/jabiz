package com.jabiz.runtime.imports;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.imports.ImportCodes;
import com.jabiz.imports.ImportDefinition;
import com.jabiz.resource.ResourceNotFoundException;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.file.FileEntities;
import com.jabiz.runtime.file.FileKeys;
import com.jabiz.runtime.file.FileStore;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The uploaded file of an import, copied from the file store to a local temporary file for the parsers, and checked
 * against the hash recorded when it was uploaded: what is imported is exactly what was uploaded.
 */
@Component
class ImportFiles {

    /** A local copy; the caller deletes it. */
    record Copy(UUID fileId, Path path, String sha256) {}

    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final FileStore store;

    ImportFiles(DatasetEntityManager entities, DatasetRegistry datasets, FileStore store) {
        this.entities = entities;
        this.datasets = datasets;
        this.store = store;
    }

    /** The file, which must have been uploaded under the import's policy; 404 when there is no such file. */
    Mono<Copy> copy(String rawId, ImportDefinition<?> definition) {
        UUID id = parseId(rawId);
        return entities.findById(datasets.findById(FileEntities.DATASET).orElseThrow(), FileEntities.SYS_FILE, id)
            .switchIfEmpty(Mono.error(() -> new ResourceNotFoundException("No file " + rawId)))
            .flatMap(file -> {
                if (!definition.filePolicy().equals(file.get(FileEntities.POLICY))) {
                    return Mono.error(new ValidationException(List.of(new Violation("fileId", ImportCodes.WRONG_FILE,
                        "The file was not uploaded for import " + definition.id(),
                        Map.of("policy", definition.filePolicy())))));
                }
                String sha256 = file.get(FileEntities.SHA256);
                long size = ((BigDecimal) file.get(FileEntities.SIZE_BYTES)).longValueExact();
                return Mono.fromCallable(() -> Files.createTempFile("jabiz-import-", ".bin"))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(path -> DataBufferUtils.write(store.read(FileKeys.key(id, FileKeys.ORIGINAL), 0, size),
                            path)
                        .then(Mono.fromCallable(() -> {
                            if (!sha256(path).equals(sha256)) {
                                throw new IllegalStateException("The stored content of file " + id
                                    + " does not match its recorded hash");
                            }
                            return new Copy(id, path, sha256);
                        }).subscribeOn(Schedulers.boundedElastic()))
                        .onErrorResume(error -> Mono.fromRunnable(() -> delete(path))
                            .subscribeOn(Schedulers.boundedElastic()).then(Mono.error(error))));
            });
    }

    static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(path)) {
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResourceNotFoundException("No file " + raw);
        }
    }
}

package com.jabiz.runtime.file;

import com.jabiz.query.BoundValue;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The store of files the server made for business processes (docs/design/14-files.md section 10, decision D31):
 * {@code sys_generated_file}, append-only. Written by {@code FILE_ARCHIVE} only; read by the download API, which checks
 * permissions and records every download.
 */
@Component
public class GeneratedFiles {

    static final String TABLE = "sys_generated_file";

    /** One kept file; {@code content} is null when read without it. */
    public record GeneratedFile(UUID fileId, String fileName, String mediaType, byte[] content, String sha256, int size,
        List<String> permissions, String subjectEntity, String subjectId, String createdBy, Instant createdTime,
        long processSeqId) {}

    private final StorageAdapterRegistry storages;
    private final String poolRef;

    public GeneratedFiles(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.poolRef = poolRef;
    }

    private StorageEngine engine() {
        return storages.getEngine(poolRef);
    }

    Mono<Void> insert(GeneratedFile file) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("file_id", file.fileId());
        row.put("file_name", file.fileName());
        row.put("media_type", file.mediaType());
        row.put("content", file.content());
        row.put("sha256", file.sha256());
        row.put("size", file.size());
        row.put("permissions", String.join(",", file.permissions()));
        row.put("subject_entity", file.subjectEntity());
        row.put("subject_id", file.subjectId());
        row.put("created_by", file.createdBy());
        row.put("created_time", file.createdTime());
        row.put("process_seq_id", file.processSeqId());
        row.put("version", 1L);
        return engine().insert(TABLE, row);
    }

    /** The file with its content. */
    public Mono<GeneratedFile> find(UUID fileId) {
        return engine().select("SELECT * FROM " + TABLE + " WHERE file_id = :id", Map.of("id", BoundValue.of(fileId)))
            .next()
            .map(GeneratedFiles::file);
    }

    private static GeneratedFile file(Map<String, Object> row) {
        String permissions = (String) row.get("permissions");
        return new GeneratedFile((UUID) row.get("file_id"), (String) row.get("file_name"),
            (String) row.get("media_type"), bytes(row.get("content")), ((String) row.get("sha256")).trim(),
            ((Number) row.get("size")).intValue(),
            permissions == null || permissions.isEmpty() ? List.of() : Arrays.asList(permissions.split(",")),
            (String) row.get("subject_entity"), (String) row.get("subject_id"), (String) row.get("created_by"),
            instant(row.get("created_time")), ((Number) row.get("process_seq_id")).longValue());
    }

    private static byte[] bytes(Object value) {
        return switch (value) {
            case null -> null;
            case byte[] b -> b;
            case ByteBuffer buffer -> {
                byte[] copy = new byte[buffer.remaining()];
                buffer.duplicate().get(copy);
                yield copy;
            }
            default -> throw new IllegalStateException("Unexpected bytes " + value.getClass());
        };
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant i -> i;
            case OffsetDateTime t -> t.toInstant();
            default -> throw new IllegalStateException("Unexpected time " + value.getClass());
        };
    }
}

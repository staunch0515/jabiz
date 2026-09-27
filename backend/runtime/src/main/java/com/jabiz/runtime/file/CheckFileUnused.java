package com.jabiz.runtime.file;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Rejects the deletion of a file that current data still refers to (422 {@code FILE_IN_USE}, naming the entity and
 * field; docs/design/14-files.md section 6). First locks the file's row ({@code FOR UPDATE}): a write that is setting
 * a reference to the file holds the row {@code FOR KEY SHARE} ({@link FileFieldCheck}), so the check waits for it and
 * then sees its reference. Reads the database: changes the process has registered but not saved yet are not seen, so
 * a process that clears the reference first saves it ({@code SaveChanges.now}).
 */
@Component
public class CheckFileUnused<C extends ProcessContext> implements StepHandler<CheckFileUnused.Metadata, C> {

    /** @param idKey context key holding the file's id */
    public record Metadata(String idKey) {
        public Metadata {
            Objects.requireNonNull(idKey, "idKey must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata, C> of(String idKey) {
        return StepSpec.of(CheckFileUnused.class, new Metadata(idKey));
    }

    private final FileReferences references;
    private final StorageAdapterRegistry storages;
    private final String poolRef;

    public CheckFileUnused(FileReferences references, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.references = references;
        this.storages = storages;
        this.poolRef = poolRef;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, C ctx) {
        return Mono.defer(() -> {
            UUID fileId = ctx.get(metadata.idKey(), UUID.class);
            EntityDefinition files = FileEntities.SYS_FILE;
            String id = SqlIdentifiers.require(files.primaryKeyColumn());
            String lock = "SELECT " + id + " FROM " + SqlIdentifiers.require(files.physicalTable) + " WHERE " + id
                + " = :id FOR UPDATE";
            return storages.getEngine(poolRef).select(lock, Map.of("id", BoundValue.of(fileId)))
                .hasElements()
                .flatMap(exists -> exists ? references.firstReference(fileId)
                    : Mono.error(new EntityNotFoundException("File " + fileId + " not found")))
                .doOnNext(found -> found.ifPresent(reference -> ctx.reject(new Violation(null,
                    PlatformErrorCodes.FILE_IN_USE,
                    "File " + fileId + " is still referred to by " + reference.entity() + "." + reference.field(),
                    Map.of("entity", reference.entity(), "field", reference.field())))))
                .then();
        });
    }
}

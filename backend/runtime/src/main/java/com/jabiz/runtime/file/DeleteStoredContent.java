package com.jabiz.runtime.file;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.UUID;

/**
 * Deletes every stored object of a file once its row is gone (after commit; docs/design/14-files.md section 6).
 * Should it fail for good, the objects are orphans and the sweep removes them.
 */
@Component
public class DeleteStoredContent<C extends ProcessContext> implements StepHandler<DeleteStoredContent.Metadata, C> {

    /** @param idKey context key holding the file's id */
    public record Metadata(String idKey) {
        public Metadata {
            Objects.requireNonNull(idKey, "idKey must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata, C> of(String idKey) {
        return StepSpec.of(DeleteStoredContent.class, new Metadata(idKey));
    }

    private final FileStore store;

    public DeleteStoredContent(FileStore store) {
        this.store = store;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, C ctx) {
        return Mono.defer(() -> store.deleteAll(FileKeys.prefix(ctx.get(metadata.idKey(), UUID.class))));
    }
}

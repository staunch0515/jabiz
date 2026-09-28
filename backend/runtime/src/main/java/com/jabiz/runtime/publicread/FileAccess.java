package com.jabiz.runtime.publicread;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Platform step that makes the remembered public-file decisions of some files end at once
 * (docs/design/15-public-access.md section 4), for a process that takes content offline and must not wait for the
 * decision lifetime, for example when consent is withdrawn. Declare it after the commit, so that the next request
 * sees the committed data:
 * {@code .afterCommit("Withdraw the photo", FileAccess.invalidate(ctx -> List.of(photoId)))}.
 * Browsers may still hold the file for {@code jabiz.public.file-cache-seconds}.
 */
@Component
public class FileAccess<C extends ProcessContext> implements StepHandler<FileAccess.Metadata<C>, C> {

    /** @param fileIds the files whose decisions end, read from the context when the step runs (nulls ignored) */
    public record Metadata<C>(Function<C, ? extends Collection<UUID>> fileIds) {
        public Metadata {
            Objects.requireNonNull(fileIds, "fileIds must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> invalidate(
        Function<C, ? extends Collection<UUID>> fileIds) {
        return StepSpec.of(FileAccess.class, new Metadata<>(fileIds));
    }

    private final PublicFileAccess access;

    public FileAccess(PublicFileAccess access) {
        this.access = access;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.fromRunnable(() -> {
            Collection<UUID> ids = metadata.fileIds().apply(ctx);
            if (ids != null) {
                access.invalidate(ids.stream().filter(Objects::nonNull).toList());
            }
        });
    }

    /** Convenience for a single file id held in the context under {@code key}. */
    public static <C extends ProcessContext> Function<C, List<UUID>> fromContext(String key) {
        return ctx -> {
            UUID id = ctx.get(key, UUID.class);
            return id == null ? List.of() : List.of(id);
        };
    }
}

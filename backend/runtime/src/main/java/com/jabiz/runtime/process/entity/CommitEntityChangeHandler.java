package com.jabiz.runtime.process.entity;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Writes the requested change through the {@link DatasetEntityManager}, which validates it
 * against the entity definition and applies dataset policy, lifecycle rules and optimistic
 * locking in one transaction. Inserts and updates leave the stored snapshot in the context.
 */
@Component
public class CommitEntityChangeHandler implements StepHandler<CommitEntityChangeMetadata, EntityChangeContext> {

    private final DatasetEntityManager entityManager;

    public CommitEntityChangeHandler(DatasetEntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Mono<Void> execute(CommitEntityChangeMetadata metadata, EntityChangeContext ctx) {
        return Mono.defer(() -> {
            EntityChange change = new EntityChange(metadata.action(), instanceFor(metadata.action(), ctx));
            // The write joins the operation the executor recorded for this process execution.
            return entityManager.commitBatch(ctx.dataset(), List.of(change))
                .doOnNext(saved -> {
                    if (!saved.isEmpty()) {
                        ctx.setResult(saved.get(0));
                    }
                })
                .then();
        });
    }

    private static EntityInstance instanceFor(EntityAction action, EntityChangeContext ctx) {
        EntityDefinition definition = ctx.definition();
        return switch (action) {
            case INSERT -> new EntityInstance(
                ctx.attributes().get(definition.primaryKey), definition.name, 0L, null, ctx.attributes());
            case UPDATE -> new EntityInstance(
                ctx.id(), definition.name, ctx.version(), null, ctx.attributes());
            case DELETE -> new EntityInstance(
                ctx.id(), definition.name, ctx.version(), null, Map.of());
            case CANCEL_SCHEDULED -> throw new IllegalArgumentException("The generic entity processes cannot cancel "
                + "scheduled versions; use the dataset API");
        };
    }
}

package com.jabiz.runtime.process.entity;

import com.jabiz.process.NoMetadata;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Replaces the snapshot produced by the commit with the stored state, so that values assigned
 * by the database (column defaults) are part of the result. If the row cannot be read back, the
 * commit snapshot is kept: the change has already been committed.
 */
@Component
public class ReloadEntityHandler implements StepHandler<NoMetadata, EntityChangeContext> {

    private final DatasetEntityManager entityManager;

    public ReloadEntityHandler(DatasetEntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, EntityChangeContext ctx) {
        return Mono.defer(() -> entityManager
            .findById(ctx.dataset(), ctx.definition(), ctx.result().id())
            .doOnNext(ctx::setResult)
            .then());
    }
}

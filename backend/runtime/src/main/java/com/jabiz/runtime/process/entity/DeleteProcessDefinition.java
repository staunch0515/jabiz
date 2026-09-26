package com.jabiz.runtime.process.entity;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityAction;

/** Deletes an instance of any registered entity type; the dataset policy decides between hard and soft delete. */
public final class DeleteProcessDefinition {

    public static final ProcessDefinition<DeleteEntityInput, DeleteEntityOutput, EntityChangeContext> DEFINITION =
        ProcessDefinition.define("DELETE_ENTITY", 1,
            DeleteEntityInput.class, DeleteEntityOutput.class, EntityChangeContext.class, pb -> pb
                .description("Deletes an instance of the requested entity type under optimistic locking.")
                .permissions(EntityProcessPermissions.ENTITY_WRITE)
                .internal()
                .contextFactory((start, input) -> new EntityChangeContext(
                    start, input.entityType(), input.id(), input.version(), null))
                .outputMapper(ctx -> new DeleteEntityOutput(ctx.entityType(), ctx.id()))

                .step("Resolve Entity", ResolveEntityHandler.class, NoMetadata.INSTANCE)
                .step("Commit Delete", CommitEntityChangeHandler.class,
                    new CommitEntityChangeMetadata(EntityAction.DELETE)));

    private DeleteProcessDefinition() {}
}

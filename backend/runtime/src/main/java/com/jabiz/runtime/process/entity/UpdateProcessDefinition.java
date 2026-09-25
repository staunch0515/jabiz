package com.jabiz.runtime.process.entity;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityInstance;

/** Changes fields of an instance of any registered entity type. */
public final class UpdateProcessDefinition {

    public static final ProcessDefinition<UpdateEntityInput, EntityInstance, EntityChangeContext> DEFINITION =
        ProcessDefinition.define("UPDATE_ENTITY", 1,
            UpdateEntityInput.class, EntityInstance.class, EntityChangeContext.class, pb -> pb
                .description("Applies the given field changes to an instance of the requested entity type "
                    + "under optimistic locking and returns the resulting state.")
                .contextFactory((processSeqId, input) -> new EntityChangeContext(
                    processSeqId, input.entityType(), input.id(), input.version(), input.attributes()))
                .outputMapper(EntityChangeContext::result)

                .step("Resolve Entity", ResolveEntityHandler.class, NoMetadata.INSTANCE)
                .step("Commit Update", CommitEntityChangeHandler.class,
                    new CommitEntityChangeMetadata(EntityAction.UPDATE)));

    private UpdateProcessDefinition() {}
}

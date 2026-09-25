package com.jabiz.runtime.process.entity;

import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityInstance;

/** Creates an instance of any registered entity type. */
public final class AddProcessDefinition {

    public static final ProcessDefinition<AddEntityInput, EntityInstance, EntityChangeContext> DEFINITION =
        ProcessDefinition.define("ADD_ENTITY", 1,
            AddEntityInput.class, EntityInstance.class, EntityChangeContext.class, pb -> pb
                .description("Creates an instance of the requested entity type: resolves the definition and "
                    + "dataset, assigns a generated identity, commits the insert and returns the stored state.")
                .contextFactory((processSeqId, input) ->
                    new EntityChangeContext(processSeqId, input.entityType(), null, 0L, input.attributes()))
                .outputMapper(EntityChangeContext::result)

                .step("Resolve Entity", ResolveEntityHandler.class, NoMetadata.INSTANCE)
                .step("Assign Identity", AssignIdentityHandler.class, NoMetadata.INSTANCE)
                .step("Commit Insert", CommitEntityChangeHandler.class,
                    new CommitEntityChangeMetadata(EntityAction.INSERT))
                .step("Reload Entity", ReloadEntityHandler.class, NoMetadata.INSTANCE));

    private AddProcessDefinition() {}
}

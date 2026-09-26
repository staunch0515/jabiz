package com.jabiz.app;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Sample of the single-step shorthand (docs/design/06-process.md section 7): marks a to-do entry done. It is a full
 * process all the same: one transaction, an operation record, the dataset's rules and optimistic locking.
 */
public final class TodoCompletion {

    public static final String NAME = "TODO_COMPLETE";

    public record Input(@NotBlank String id, @NotNull Long version) {}

    public record Output(String id, long processSeqId) {}

    public static final ProcessDefinition<Input, Output, ProcessContext> DEFINITION =
        ProcessDefinition.single(NAME, 1, Input.class, Output.class, (in, ctx) -> {
            ctx.changes().update("Todo", in.id(), in.version(), Map.of("done", true));
            return new Output(in.id(), ctx.processSeqId());
        }).withPermissions("todo.write");

    private TodoCompletion() {}
}

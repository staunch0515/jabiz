package com.jabiz.quizbuks.help;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * F-C10: a Sponsor moved to another operator ({@code QB_SPONSOR_OPERATOR_ASSIGN}) takes their AI help conversations
 * with a person along: {@value #PROCESS} sets the operator of each (the value {@code QB_HELP_REPLY} checks), so that
 * the new operator's members answer them and the old one's no longer can. Their open tasks move in the same
 * reassignment through the platform's {@code APPROVAL_SCOPE_MOVE}, named by {@link #taskKeys}. A sub-process of the
 * reassignment only; the conversations headquarters answers stay with it.
 */
public final class HelpOperatorMove {

    public static final String PROCESS = "QB_HELP_OPERATOR_MOVE";

    static final String INPUT = "input";
    static final String THREADS = "threads";

    /** @param threadIds the conversations to hand over (the reassignment read them; read again here) */
    public record Input(@NotBlank String sponsorId, @NotBlank String toOperatorId,
        @NotNull @Size(max = 10_000) List<@NotBlank String> threadIds) {}

    public record Output(int moved) {}

    private HelpOperatorMove() {}

    /** The source keys of a conversation's open tasks: its handing over, and the reminder of its current wait. */
    public static List<String> taskKeys(EntityInstance thread) {
        List<String> keys = new ArrayList<>();
        keys.add(QbHelp.taskSource(thread.id()));
        Instant since = HelpProcesses.instant(thread.get("waitingSince"));
        if (QbHelp.WAIT_STAFF.equals(thread.get("waitingFor")) && since != null) {
            keys.add(QbHelp.remindSource(thread.id(), since.getEpochSecond()));
        }
        return keys;
    }

    public static ProcessDefinition<Input, Output, ProcessContext> move() {
        return ProcessDefinition.define(PROCESS, 1, Input.class, Output.class, ProcessContext.class, pb -> pb
            .description("Hands a reassigned Sponsor's AI help conversations with a person to the new operator.")
            .permissions(QbPermissions.OPERATORS_WRITE)
            .subProcessOnly()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new Output(ctx.contains(HelpProcesses.OUTPUT)
                ? (Integer) ctx.get(HelpProcesses.OUTPUT) : 0))
            .step("Load the conversations", QueryEntities.<ProcessContext>of(QbHelp.defaultDataset(QbHelp.THREAD),
                ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.In("threadId", new ArrayList<>(input(ctx).threadIds())),
                    new QueryPredicate.Eq("userId", input(ctx).sponsorId())))).limit(input(ctx).threadIds().size())
                    .build(), THREADS))
            .compute("Hand them over", (metadata, ctx) -> {
                int moved = 0;
                for (EntityInstance thread : HelpProcesses.list(ctx, THREADS)) {
                    if (thread.get("operatorId") != null
                        && !input(ctx).toOperatorId().equals(String.valueOf((Object) thread.get("operatorId")))) {
                        ctx.changes().update(QbHelp.THREAD, thread.id(), thread.version(),
                            Map.of("operatorId", input(ctx).toOperatorId()));
                        moved++;
                    }
                }
                ctx.put(HelpProcesses.OUTPUT, moved);
            }));
    }

    private static Input input(ProcessContext ctx) {
        return ctx.get(INPUT, Input.class);
    }
}

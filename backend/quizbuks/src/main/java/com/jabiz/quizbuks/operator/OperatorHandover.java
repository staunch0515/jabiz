package com.jabiz.quizbuks.operator;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.report.QbReports;
import com.jabiz.quizbuks.reward.QbDigitalPrizes;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.task.CloseTasks;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskEntities;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F-S1: when a sponsor moves to another operator ({@code QB_SPONSOR_OPERATOR_ASSIGN}), the open tasks about them
 * that the old operator's members were asked to do go to the new operator's: the task of a publication's reports and
 * the dispute over a digital prize. A task's scope cannot change (platform decision D64), so each is cancelled and made
 * again, with the same type, title, subject and source, in the new operator's scope ({@value #MOVE}, one task a call).
 * The reviews (approval requests) do not move this way: a sponsor with one pending is not reassigned until the platform
 * can move it (docs/quizbuks/plans/impl/F-S1.md §0, answer 2). Fee reminders follow the invoice's frozen operator and
 * stay (F-M).
 */
public final class OperatorHandover {

    public static final String PROCESS = "QB_OPERATOR_TASK_HANDOVER";
    public static final String MOVE = "QB_OPERATOR_TASK_MOVE";

    /** At most this many of a sponsor's open tasks move with them (beyond, the reassignment is refused). */
    static final int MAX_TASKS = 200;

    /** The kinds of task that move, by the permission they are assigned to. */
    static final Map<String, String> MOVED = Map.of(
        QbReports.TASK_TYPE, QbPermissions.OPERATOR_USE,
        QbDigitalPrizes.DISPUTE_TASK, QbPermissions.DIGITAL_RESOLVE);

    /** @param fromOperatorId the sponsor's operator before the move */
    public record Input(@NotBlank String sponsorId, @NotBlank String fromOperatorId, @NotBlank String toOperatorId) {}

    /** One task to make again in the new operator's scope (its source's open tasks are cancelled first). */
    public record Move(@NotBlank String sourceKey, @NotBlank String type, @NotBlank String titleKey,
        Map<String, String> titleParams, @NotBlank String permission, String subjectEntity, String subjectId,
        String link, @NotBlank String toOperatorId) {}

    public record Output(int moved) {}

    static final String INPUT = "input";
    static final String TASKS = "tasks";
    static final String CARDS = "cards";
    static final String PRIZES = "prizes";

    public static ProcessDefinition<Input, Output, ProcessContext> handover() {
        return ProcessDefinition.define(PROCESS, 1, Input.class, Output.class, ProcessContext.class, pb -> pb
            .description("Moves the open tasks about a reassigned sponsor to their new operator.")
            .permissions(QbPermissions.OPERATORS_WRITE)
            .subProcessOnly()
            .contextFactory(OperatorHandover::start)
            .outputMapper(ctx -> new Output(moves(ctx).size()))
            .step("Load the old operator's open tasks", QueryEntities.<ProcessContext>of(TaskEntities.TASK_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.In("status", List.of(TaskEntities.OPEN)),
                    new QueryPredicate.In("scopeName", List.of(QbOperators.SCOPE)),
                    new QueryPredicate.In("scopeValue", List.of(input(ctx).fromOperatorId())),
                    new QueryPredicate.In("taskType", List.copyOf(MOVED.keySet())))))
                    .limit(MAX_TASKS).build(), TASKS))
            .step("Load their subjects of the sponsor's", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbPublications.defaultDataset(QbPublications.CARD), ctx -> ofSponsor(ctx,
                    "publicationId", subjects(ctx, QbReports.TASK_TYPE)), CARDS),
                QueryEntities.query(QbDigitalPrizes.defaultDataset(QbDigitalPrizes.DIGITAL_PRIZE), ctx -> ofSponsor(
                    ctx, "digitalPrizeId", subjects(ctx, QbDigitalPrizes.DISPUTE_TASK)), PRIZES)))
            .step("Make each again in the new operator's scope", CallProcess.<ProcessContext>forEach(MOVE, 1,
                OperatorHandover::moves, null)));
    }

    public static ProcessDefinition<Move, Output, ProcessContext> move() {
        return ProcessDefinition.define(MOVE, 1, Move.class, Output.class, ProcessContext.class, pb -> pb
            .description("Makes an open task again in another operator's scope.")
            .permissions(QbPermissions.OPERATORS_WRITE)
            .subProcessOnly()
            .contextFactory(OperatorHandover::start)
            .outputMapper(ctx -> new Output(1))
            .step("Cancel it in the old scope", CloseTasks.<ProcessContext>cancel(
                ctx -> ctx.get(INPUT, Move.class).sourceKey()))
            .step("Make it in the new scope", CreateTask.<ProcessContext>of(ctx -> {
                Move move = ctx.get(INPUT, Move.class);
                TaskSpec task = TaskSpec.forPermission(move.type(), move.titleKey(),
                        move.titleParams() == null ? Map.of() : move.titleParams(), move.permission())
                    .source(move.sourceKey())
                    .scope(QbOperators.SCOPE, move.toOperatorId());
                if (move.subjectEntity() != null) {
                    task = task.about(move.subjectEntity(), move.subjectId());
                }
                return move.link() == null ? task : task.link(move.link());
            })));
    }

    private static ProcessContext start(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private static Input input(ProcessContext ctx) {
        return ctx.get(INPUT, Input.class);
    }

    private static List<Object> subjects(ProcessContext ctx, String type) {
        // The subjects' keys are UUIDs (publications, digital prizes); a task holds them as text.
        return OperatorChecks.list(ctx, TASKS).stream().filter(task -> type.equals(task.get("taskType")))
            .map(task -> String.valueOf((Object) task.get("subjectId"))).distinct()
            .map(id -> (Object) java.util.UUID.fromString(id)).toList();
    }

    private static EntityQuery ofSponsor(ProcessContext ctx, String key, List<Object> ids) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(new QueryPredicate.In(key, ids),
            new QueryPredicate.In("publisherId", List.of(input(ctx).sponsorId())))))
            .limit(MAX_TASKS).build();
    }

    /**
     * The tasks whose subject is the sponsor's, with the title the process that made them gives (its parameters come
     * from the subject, as when it was made).
     */
    static List<Move> moves(ProcessContext ctx) {
        Map<String, EntityInstance> cards = byId(OperatorChecks.list(ctx, CARDS));
        Map<String, EntityInstance> prizes = byId(OperatorChecks.list(ctx, PRIZES));
        List<Move> moves = new ArrayList<>();
        Set<String> sources = new java.util.HashSet<>();
        for (EntityInstance task : OperatorChecks.list(ctx, TASKS)) {
            String type = String.valueOf((Object) task.get("taskType"));
            String subject = String.valueOf((Object) task.get("subjectId"));
            String source = task.get("sourceKey");
            if (source == null || !sources.add(source)) {
                continue;
            }
            Map<String, String> params = new LinkedHashMap<>();
            if (QbReports.TASK_TYPE.equals(type) && cards.containsKey(subject)) {
                params.put("publication", String.valueOf((Object) cards.get(subject).get("name")));
            } else if (QbDigitalPrizes.DISPUTE_TASK.equals(type) && prizes.containsKey(subject)) {
                EntityInstance prize = prizes.get(subject);
                params.put("amount", String.valueOf((Object) prize.get("amount")));
                params.put("hint", prize.get("hint") == null ? "" : String.valueOf((Object) prize.get("hint")));
            } else {
                continue;
            }
            moves.add(new Move(source, type, task.get("titleKey"), Map.copyOf(params), MOVED.get(type),
                task.get("subjectEntity"), subject, task.get("link"), input(ctx).toOperatorId()));
        }
        return List.copyOf(moves);
    }

    private static Map<String, EntityInstance> byId(List<EntityInstance> rows) {
        Map<String, EntityInstance> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.put(String.valueOf(row.id()), row));
        return byId;
    }

    private OperatorHandover() {}
}

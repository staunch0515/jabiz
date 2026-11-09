package com.jabiz.quizbuks.videouse;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * The reminder of a shared video's use waiting for its provider (F-S14, docs/quizbuks/plans/impl/F-S14.md):
 * {@value #REMIND}, an item of the daily job {@value #JOB}, gives both sides a task when a use has waited longer than
 * {@code qb.video-use.remind-days} days ({@value QbVideoUses#REMINDERS_DUE_TEMPLATE}, read again under the use's
 * lock); once per submitted revision (the tasks' source names it), closed when the provider answers.
 */
public final class VideoUseReminders {

    public static final String REMIND = "QB_VIDEO_USE_REMIND";
    public static final String JOB = "qb.video-use-remind";
    /** Every day at 00:47 UTC (09:47 in Tokyo): the sponsors' working morning. */
    public static final String CRON = "0 47 0 * * *";
    public static final String SPONSOR_TASK = "qb.video-use.waiting";
    public static final String SPONSOR_TASK_TITLE = "task.qb.video-use.waiting";
    public static final String PROVIDER_TASK = "qb.video-use.remind";
    public static final String PROVIDER_TASK_TITLE = "task.qb.video-use.remind";

    public record RemindInput(@NotBlank @Size(max = QbVideoUses.ID_LENGTH) String videoUseId) {}

    /** @param reminded whether the two sides were reminded (answered meanwhile, or reminded already: not) */
    public record RemindOutput(boolean reminded) {}

    static final String INPUT = "input";
    static final String FOUND = "found";
    static final String DUE = "due";
    static final String CARD = "card";

    private VideoUseReminders() {}

    public static ProcessDefinition<RemindInput, RemindOutput, ProcessContext> remind() {
        return ProcessDefinition.define(REMIND, 1, RemindInput.class, RemindOutput.class, ProcessContext.class,
            pb -> pb
                .description("Reminds both sides, once, of a shared video's use waiting too long for its provider.")
                .permissions(QbPermissions.PUBLICATION_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new RemindOutput(due(ctx) != null))
                .step("Find the use", QueryEntities.<ProcessContext>of(QbVideoUses.defaultDataset(QbVideoUses.USE),
                    ctx -> VideoUseProcesses.byId("videoUseId", ctx.get(INPUT, RemindInput.class).videoUseId()),
                    FOUND))
                .step("Take the publication's turn", HoldLock.<ProcessContext>exclusive(ctx -> found(ctx) == null
                    ? null : QbVideoUses.lockName(found(ctx).get("publicationId"))))
                .step("Read whether it is due", RunTemplate.<ProcessContext>when(ctx -> found(ctx) != null,
                    QbVideoUses.REMINDERS_DUE_TEMPLATE, ctx -> Map.of("videoUseId",
                        ctx.get(INPUT, RemindInput.class).videoUseId(), "at", ctx.opTime()), DUE))
                .step("Load the publication's name", QueryEntities.<ProcessContext>of(
                    QbPublications.defaultDataset(QbPublications.CARD), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("publicationId", VideoUseProcesses.uuids(found(ctx) == null
                            ? null : found(ctx).get("publicationId")))).limit(1).build(), CARD))
                .step("Remind the user", CreateTask.<ProcessContext>when(ctx -> due(ctx) != null,
                    ctx -> task(ctx, SPONSOR_TASK, SPONSOR_TASK_TITLE, "sponsorId")))
                .step("Remind the provider", CreateTask.<ProcessContext>when(ctx -> due(ctx) != null,
                    ctx -> task(ctx, PROVIDER_TASK, PROVIDER_TASK_TITLE, "providerId"))));
    }

    private static TaskSpec task(ProcessContext ctx, String type, String title, String side) {
        Map<String, Object> due = due(ctx);
        EntityInstance card = VideoUseProcesses.first(ctx, CARD);
        return TaskSpec.forUser(type, title, Map.of("name", card == null ? "" : String.valueOf((Object) card.get("name")),
                "days", String.valueOf(due.get("days"))), String.valueOf(due.get(side)))
            .about(QbVideoUses.USE, found(ctx).id())
            .source(VideoUseRules.reminderSource(found(ctx).id(), due.get("revision")));
    }

    private static EntityInstance found(ProcessContext ctx) {
        return VideoUseProcesses.first(ctx, FOUND);
    }

    /** The due row (the use waiting, unreminded at its revision), or none. */
    private static Map<String, Object> due(ProcessContext ctx) {
        List<Map<String, Object>> rows = VideoUseFigures.rows(ctx, DUE);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}

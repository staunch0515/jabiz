package com.jabiz.quizbuks.videouse;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Both sides' figures of a shared video's use (F-S14, docs/quizbuks/plans/impl/F-S14.md):
 * <ul>
 *   <li>{@value #REFRESH}: one publication using another's shared video, an item of the daily job {@value #JOB}: its
 *       opens, exposure successes, takers who went on to answer and those who passed, counted from the counting tables
 *       ({@value QbVideoUses#COUNT_TEMPLATE}) up to a cut-off: the run's time while it is on, its end once it ended,
 *       when the figures are finalised and never written again;</li>
 *   <li>{@value #CHECK_RUN}: the daily reconciliation (job {@value #CHECK_JOB}): the finalised figures counted again up
 *       to their cut-off ({@value QbVideoUses#DIFFERENCES_TEMPLATE}); with any difference, headquarters gets a task,
 *       one a day.</li>
 * </ul>
 */
public final class VideoUseFigures {

    public static final String REFRESH = "QB_VIDEO_USE_FIGURES_REFRESH";
    public static final String JOB = "qb.video-use-figures";
    /** Every day at 19:53 UTC (04:53 in Tokyo), after the exposures' check. */
    public static final String CRON = "0 53 19 * * *";

    public static final String CHECK_RUN = "QB_VIDEO_USE_CHECK_RUN";
    public static final String CHECK_JOB = "qb.video-use-check";
    /** Every day at 20:07 UTC, after the figures. */
    public static final String CHECK_CRON = "0 7 20 * * *";
    public static final String CHECK_TASK_TYPE = "qb.video-use-check";
    public static final String CHECK_TASK_TITLE = "task.qb.video-use-check";

    public record RefreshInput(@NotNull @Pattern(regexp = "[0-9a-fA-F-]{36}") String publicationId,
        @NotNull Instant scheduledTime) {}

    /**
     * @param written   whether the figures were written (finalised before, or not a use: not)
     * @param finalised whether they are final now
     */
    public record RefreshOutput(boolean written, boolean finalised) {}

    public record CheckInput(@NotNull Instant scheduledTime) {}

    /** @param differences how many finalised publications the check found */
    public record CheckOutput(int differences) {}

    static final String INPUT = "input";
    static final String CARD = "card";
    static final String STORED = "stored";
    static final String COUNT = "count";
    static final String OUTPUT = "output";
    static final String DIFFERENCES = "differences";

    private VideoUseFigures() {}

    // ---- the daily refresh -----------------------------------------------------------------------------------------

    public static ProcessDefinition<RefreshInput, RefreshOutput, ProcessContext> refresh() {
        return ProcessDefinition.define(REFRESH, 1, RefreshInput.class, RefreshOutput.class, ProcessContext.class,
            pb -> pb
                .description("Counts a publication's figures of another Sponsor's shared video from the counting "
                    + "tables, and finalises them once it ended.")
                .permissions(QbPermissions.PUBLICATION_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, RefreshOutput.class)
                    : new RefreshOutput(false, false))
                .step("Take the figures' turn", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbVideoUses.figuresLockName(input(ctx).publicationId())))
                .step("Load the card and the figures", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbPublications.defaultDataset(QbPublications.CARD), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("publicationId",
                            VideoUseProcesses.uuids(input(ctx).publicationId()))).limit(1).build(), CARD),
                    QueryEntities.query(QbVideoUses.defaultDataset(QbVideoUses.FIGURES), ctx -> VideoUseProcesses
                        .byId("publicationId", input(ctx).publicationId()), STORED)))
                .step("Count", RunTemplate.<ProcessContext>when(VideoUseFigures::counts, QbVideoUses.COUNT_TEMPLATE,
                    ctx -> Map.of("publicationId", input(ctx).publicationId(), "cutoff", cutoff(ctx)), COUNT))
                .compute("Write the figures", (metadata, ctx) -> write(ctx)));
    }

    /** Whether the publication is one to count: a use, not finalised yet. */
    static boolean counts(ProcessContext ctx) {
        EntityInstance card = VideoUseProcesses.first(ctx, CARD);
        EntityInstance stored = VideoUseProcesses.first(ctx, STORED);
        return card != null && (stored == null || stored.get("finalisedAt") == null)
            && VideoUseRules.othersVideo(card.get("sharedVideoId"), card.get("videoProviderId"),
                card.get(PublisherKind.ID_FIELD));
    }

    /** Whether the card ended: its figures are then counted up to its end and final. */
    static boolean ended(EntityInstance card) {
        return PublicationStatus.ENDED.equals(card.get("status"));
    }

    /** Up to when: the publication's end once it ended (never after the run), else the run's time. */
    static Instant cutoff(ProcessContext ctx) {
        Instant run = input(ctx).scheduledTime();
        EntityInstance card = VideoUseProcesses.first(ctx, CARD);
        if (card == null || !ended(card)) {
            return run;
        }
        Instant end = instant(card.get("endedAt"));
        if (end == null) {
            end = instant(card.get("endAt"));
        }
        return end == null || end.isAfter(run) ? run : end;
    }

    static void write(ProcessContext ctx) {
        if (!counts(ctx)) {
            return;
        }
        EntityInstance card = VideoUseProcesses.first(ctx, CARD);
        EntityInstance stored = VideoUseProcesses.first(ctx, STORED);
        Map<String, Object> row = rows(ctx, COUNT).stream().findFirst().orElse(Map.of());
        boolean finalised = ended(card);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("opens", number(row.get("opens")));
        values.put("successes", number(row.get("successes")));
        values.put("goes", number(row.get("goes")));
        values.put("qualified", number(row.get("qualified")));
        values.put("cutoffAt", cutoff(ctx));
        values.put("refreshedAt", ctx.opTime());
        values.put("finalisedAt", finalised ? ctx.opTime() : null);
        if (stored == null) {
            values.put("publicationId", input(ctx).publicationId());
            values.put("sharedVideoId", String.valueOf((Object) card.get("sharedVideoId")));
            values.put("sponsorId", String.valueOf((Object) card.get(PublisherKind.ID_FIELD)));
            values.put("providerId", String.valueOf((Object) card.get("videoProviderId")));
            ctx.changes().insert(QbVideoUses.FIGURES, values);
        } else {
            ctx.changes().update(QbVideoUses.FIGURES, stored.id(), stored.version(), values);
        }
        ctx.put(OUTPUT, new RefreshOutput(true, finalised));
    }

    // ---- the daily check -------------------------------------------------------------------------------------------

    /** @param zone the deployment's zone ({@code jabiz.fiscal-year-zone}): the day of the task */
    public static ProcessDefinition<CheckInput, CheckOutput, ProcessContext> check(ZoneId zone) {
        return ProcessDefinition.define(CHECK_RUN, 1, CheckInput.class, CheckOutput.class, ProcessContext.class,
            pb -> pb
                .description("Checks every day that the finalised figures of shared videos' uses are those the "
                    + "counting tables give, and asks headquarters when they are not.")
                .permissions(QbPermissions.PUBLICATION_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new CheckOutput(rows(ctx, DIFFERENCES).size()))
                .step("Count the finalised figures again", RunTemplate.<ProcessContext>of(
                    QbVideoUses.DIFFERENCES_TEMPLATE, ctx -> Map.of(), DIFFERENCES))
                .step("Ask headquarters", CreateTask.<ProcessContext>when(ctx -> !rows(ctx, DIFFERENCES).isEmpty(),
                    ctx -> TaskSpec.forPermission(CHECK_TASK_TYPE, CHECK_TASK_TITLE,
                            Map.of("count", String.valueOf(rows(ctx, DIFFERENCES).size())),
                            QbPermissions.ADMIN_EXPOSURE_MONITOR)
                        .link("/qb/video-uses")
                        .source(checkSource(ctx.get(INPUT, CheckInput.class).scheduledTime(), zone)))));
    }

    /** One task a day: the day of the run in the deployment's zone. */
    public static String checkSource(Instant scheduledTime, ZoneId zone) {
        return CHECK_TASK_TYPE + ":" + scheduledTime.atZone(zone).toLocalDate();
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    private static RefreshInput input(ProcessContext ctx) {
        return ctx.get(INPUT, RefreshInput.class);
    }

    static BigDecimal number(Object value) {
        return value instanceof BigDecimal d ? d : value instanceof Number n ? BigDecimal.valueOf(n.longValue())
            : BigDecimal.ZERO;
    }

    static Instant instant(Object value) {
        return value instanceof Instant i ? i : value instanceof OffsetDateTime o ? o.toInstant()
            : value == null ? null : Instant.parse(value.toString());
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(ProcessContext ctx, String key) {
        Object rows = ctx.contains(key) ? ctx.get(key) : null;
        return rows == null ? List.of() : (List<Map<String, Object>>) rows;
    }
}

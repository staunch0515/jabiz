package com.jabiz.quizbuks.stats;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.play.QbPlay;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The analytics job {@code qb.analytics} (docs/quizbuks/plans/Q9e-analytics.md, D-Q9e-2), every ten minutes, a
 * per-item job (platform decision D42; Q9e1 review M3): the selection {@code qb.system.analytics-items} proposes the
 * run's items, and each runs {@code QB_ANALYTICS_ITEM} ({@code internal()}) in a transaction of its own, so that one
 * that fails or takes too long leaves the others and is retried by the next run:
 * <ul>
 *   <li>{@code CHUNK}: a range of the publications to read. One statement reads their counters (both sides), cursors
 *       and new first views; the increments go to an hourly bucket ({@link AnalyticsBuckets}); the attempts finished
 *       since the watermark (at most the configured number) are added to the question totals and timings; the figures
 *       the sponsor is shown change only after at least the minimum group of new completions ({@link MinGroup});</li>
 *   <li>{@code RECOUNT}: one ended publication, recounted whole and finalized
 *       ({@link AnalyticsLimits#FINALIZE_AFTER} after its end once nothing is in progress, or a day after it whatever is
 *       in progress). A publication of more completions than the recount's bound is finalized with the totals read so
 *       far once they have caught up, without a recount;</li>
 *   <li>{@code RUN}: last, the run's record (its time, which decides the next run's bucket, and the zone its days are
 *       counted in, {@code jabiz.fiscal-year-zone}) and the deletion of hourly buckets older than
 *       {@link AnalyticsLimits#RETENTION}.</li>
 * </ul>
 * No item takes a business lock or touches an answering table: the counters are read as committed, so answering's
 * statements and locks do not change ({@code AttemptStatementBudgetIT}). Items write only rows that changed. An item of
 * a run not after the previous run (a repeat) does nothing; the platform replays an item's result for the same
 * scheduled time anyway, and its cluster lock keeps two runs from overlapping.
 */
public final class AnalyticsJob {

    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsJob.class);

    public static final String ITEM = "QB_ANALYTICS_ITEM";
    public static final String JOB = "qb.analytics";
    public static final String ITEMS_TEMPLATE = "qb.system.analytics-items";
    public static final String STATE_TEMPLATE = "qb.system.analytics-state";
    public static final String ATTEMPTS_TEMPLATE = "qb.system.analytics-attempts";
    public static final String QUESTION_STATS_TEMPLATE = "qb.system.analytics-question-stats";

    public static final String CHUNK = "CHUNK";
    public static final String RECOUNT = "RECOUNT";
    public static final String RUN = "RUN";

    /**
     * One item: a chunk ({@code firstId} to {@code lastId}), a recount ({@code firstId} = {@code lastId}) or the run's
     * record (no publication).
     *
     * @param previousRunAt the previous run's time (null: the job's first run), which decides the hourly bucket
     */
    public record ItemInput(@NotNull Instant at, @NotNull String kind, UUID firstId, UUID lastId,
        Instant previousRunAt) {}

    /**
     * @param publications the publications read; {@code written} the rows inserted, changed or deleted;
     *                     {@code attempts} the finished attempts read; {@code finalized} the publications finalized;
     *                     {@code repeated} the run was not after the previous one and did nothing
     */
    public record ItemOutput(String kind, int publications, int written, int attempts, int finalized,
        boolean repeated) {}

    /** The item's input from a row of the selection. */
    public static ItemInput input(Instant scheduledTime, Map<String, Object> row) {
        return new ItemInput(scheduledTime, text(column(row, "kind")), uuid(column(row, "firstId")),
            uuid(column(row, "lastId")), instant(column(row, "previousRunAt")));
    }

    /** The selection's parameters for a run at {@code at}. */
    public static Map<String, Object> itemParams(Instant at, int chunkSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("recent", at.minus(AnalyticsLimits.RECENT));
        params.put("finalizeBefore", at.minus(AnalyticsLimits.FINALIZE_AFTER));
        params.put("anywayBefore", at.minus(AnalyticsLimits.FINALIZE_ANYWAY_AFTER));
        params.put("chunkSize", chunkSize);
        return params;
    }

    static final String INPUT = "stats.input";
    static final String STATE = "stats.state";
    static final String PLAN = "stats.plan";
    static final String NEW = "stats.new";
    static final String FULL = "stats.full";
    static final String STATS = "stats.stats";
    static final String RUN_ROW = "stats.run";
    static final String OLD = "stats.old";
    static final String OUTPUT = "stats.output";

    /** What the item does this run. */
    private record Plan(boolean repeated, List<UUID> incremental, List<UUID> recount, Set<UUID> withoutRecount) {}

    /**
     * @param zone        the deployment's zone, whose days the templates group the hours by
     * @param maxAttempts most finished attempts one chunk reads, 1 to {@link AnalyticsLimits#ATTEMPTS_MAX}
     * @param maxRecount  most completions a publication is recounted whole for; above it, its totals read so far are
     *                    final once they have caught up
     */
    public static ProcessDefinition<ItemInput, ItemOutput, ProcessContext> item(ZoneId zone, int maxAttempts,
        long maxRecount) {
        if (maxAttempts < 1 || maxAttempts > AnalyticsLimits.ATTEMPTS_MAX) {
            throw new IllegalArgumentException("quizbuks.analytics.max-attempts-per-chunk must be between 1 and "
                + AnalyticsLimits.ATTEMPTS_MAX + ": " + maxAttempts);
        }
        if (maxRecount < 0) {
            throw new IllegalArgumentException("quizbuks.analytics.max-recount must not be negative: " + maxRecount);
        }
        String zoneId = zone.getId();
        return ProcessDefinition.define(ITEM, 1, ItemInput.class, ItemOutput.class, ProcessContext.class, pb -> pb
            .description("Adds publications' latest figures to the sponsors' analytics (one item of the job).")
            .permissions(QbPermissions.STATS_SYSTEM)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, ItemOutput.class))
            .step("Read the counters and cursors", RunTemplate.<ProcessContext>of(STATE_TEMPLATE, ctx -> {
                ItemInput input = input(ctx);
                boolean reads = !repeated(input) && !RUN.equals(input.kind());
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("firstId", reads ? input.firstId() : null);
                params.put("lastId", reads ? input.lastId() : null);
                params.put("recent", input.at().minus(AnalyticsLimits.RECENT));
                params.put("bucket", AnalyticsBuckets.bucket(input.previousRunAt(), input.at()));
                params.put("viewsTo", input.at().minus(AnalyticsLimits.LAG));
                return params;
            }, STATE))
            .compute("Decide what to read", (metadata, ctx) -> ctx.put(PLAN, plan(ctx, maxRecount)))
            .step("Read the attempts finished since", RunTemplate.<ProcessContext>of(ATTEMPTS_TEMPLATE,
                ctx -> attemptParams(ctx, plan(ctx).incremental(), false, maxAttempts), NEW))
            .step("Recount an ended publication", RunTemplate.<ProcessContext>of(ATTEMPTS_TEMPLATE,
                ctx -> attemptParams(ctx, plan(ctx).recount(), true, maxAttempts), FULL))
            .step("Read the shown question statistics", RunTemplate.<ProcessContext>of(QUESTION_STATS_TEMPLATE,
                ctx -> Map.of("ids", withAttempts(ctx)), STATS))
            .step("Load the last run", QueryEntities.<ProcessContext>of(QbPlay.defaultDataset(QbPlay.STATS_RUN),
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("job", records(ctx) ? JOB : "-"))
                    .limit(1).build(), RUN_ROW))
            .step("Find the expired hours", QueryEntities.<ProcessContext>of(
                QbAnalytics.defaultDataset(QbAnalytics.HOUR), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.Lt("hourStart", records(ctx)
                        ? input(ctx).at().minus(AnalyticsLimits.RETENTION) : Instant.EPOCH))
                    .orderBy("hourStart", true).limit(AnalyticsLimits.PURGE_BATCH).build(), OLD))
            .compute("Write what changed", (metadata, ctx) -> write(ctx, zoneId)));
    }

    private static ItemInput input(ProcessContext ctx) {
        return ctx.get(INPUT, ItemInput.class);
    }

    private static Plan plan(ProcessContext ctx) {
        return ctx.get(PLAN, Plan.class);
    }

    /** An item of a run at a time not after the previous run's: a repeat, which must not count anything twice. */
    private static boolean repeated(ItemInput input) {
        return input.previousRunAt() != null && !input.previousRunAt().isBefore(input.at());
    }

    /** Whether this item records the run (the last item, unless the run is a repeat). */
    private static boolean records(ProcessContext ctx) {
        return RUN.equals(input(ctx).kind()) && !plan(ctx).repeated();
    }

    private static Map<String, Object> attemptParams(ProcessContext ctx, List<UUID> ids, boolean full, int cap) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ids", ids);
        params.put("until", input(ctx).at().minus(AnalyticsLimits.LAG));
        params.put("full", full);
        params.put("cap", cap);
        return params;
    }

    /**
     * A chunk reads the attempts of its publications not finalized. A recount reads its publication whole when it is
     * still due (ended long enough ago, nothing in progress or a day after the end), unless it has more completions
     * than the bound: then it is only finalized once the chunks' totals have caught up.
     */
    private static Plan plan(ProcessContext ctx, long maxRecount) {
        ItemInput input = input(ctx);
        if (repeated(input)) {
            return new Plan(true, List.of(), List.of(), Set.of());
        }
        Instant at = input.at();
        List<UUID> incremental = new ArrayList<>();
        List<UUID> recount = new ArrayList<>();
        Set<UUID> withoutRecount = new LinkedHashSet<>();
        for (Map<String, Object> row : rows(ctx, STATE)) {
            if (column(row, "finalizedAt") != null) {
                continue;
            }
            UUID id = uuid(column(row, "publicationId"));
            if (CHUNK.equals(input.kind())) {
                incremental.add(id);
            } else if (RECOUNT.equals(input.kind()) && settled(row, at)) {
                if (number(column(row, "completions")) > maxRecount) {
                    withoutRecount.add(id);
                } else {
                    recount.add(id);
                }
            }
        }
        return new Plan(false, List.copyOf(incremental), List.copyOf(recount), Set.copyOf(withoutRecount));
    }

    private static boolean settled(Map<String, Object> row, Instant at) {
        Instant ended = instant(column(row, "endedAt"));
        if (!"ENDED".equals(column(row, "status")) || ended == null
            || ended.plus(AnalyticsLimits.FINALIZE_AFTER).isAfter(at)) {
            return false;
        }
        boolean idle = number(column(row, "starts")) == number(column(row, "completions"));
        return idle || late(ended, at);
    }

    private static boolean late(Instant ended, Instant at) {
        return ended != null && !ended.plus(AnalyticsLimits.FINALIZE_ANYWAY_AFTER).isAfter(at);
    }

    /** The publications with attempts read this item, whose shown statistics may change. */
    private static List<UUID> withAttempts(ProcessContext ctx) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Map<String, Object> row : rows(ctx, NEW)) {
            ids.add(uuid(column(row, "publicationId")));
        }
        for (Map<String, Object> row : rows(ctx, FULL)) {
            ids.add(uuid(column(row, "publicationId")));
        }
        return List.copyOf(ids);
    }

    /** The attempts read for one publication: per question and in all. */
    private record Read(SortedMap<Integer, QuestionTotals.Count> questions, long attempts, long submitted,
        long expired, BigDecimal durationSec, Instant lastAt, String lastId) {

        static Read none() {
            return new Read(new TreeMap<>(), 0, 0, 0, BigDecimal.ZERO, null, null);
        }
    }

    private static Map<UUID, Read> reads(List<Map<String, Object>> rows) {
        Map<UUID, Read> reads = new HashMap<>();
        for (Map<String, Object> row : rows) {
            UUID id = uuid(column(row, "publicationId"));
            Read read = reads.get(id);
            if (read == null) {
                read = new Read(new TreeMap<>(), number(column(row, "attempts")), number(column(row, "submitted")),
                    number(column(row, "expired")), decimal(column(row, "durationSec")),
                    instant(column(row, "lastAt")), text(column(row, "lastId")));
                reads.put(id, read);
            }
            Object questionNo = column(row, "questionNo");
            if (questionNo != null) {
                read.questions().put((int) number(questionNo), new QuestionTotals.Count(
                    number(column(row, "qCompletions")), number(column(row, "qCorrect"))));
            }
        }
        return reads;
    }

    private static void write(ProcessContext ctx, String zone) {
        ItemInput input = input(ctx);
        Plan plan = plan(ctx);
        if (plan.repeated()) {
            ctx.put(OUTPUT, new ItemOutput(input.kind(), 0, 0, 0, 0, true));
            return;
        }
        if (RUN.equals(input.kind())) {
            ctx.put(OUTPUT, new ItemOutput(RUN, 0, record(ctx, zone), 0, 0, false));
            return;
        }
        Instant at = input.at();
        Instant viewsTo = at.minus(AnalyticsLimits.LAG);
        boolean chunk = CHUNK.equals(input.kind());
        Map<UUID, Read> incremental = reads(rows(ctx, NEW));
        Map<UUID, Read> recounted = reads(rows(ctx, FULL));
        Map<UUID, Map<Integer, Map<String, Object>>> shownRows = new HashMap<>();
        for (Map<String, Object> row : rows(ctx, STATS)) {
            shownRows.computeIfAbsent(uuid(column(row, "publicationId")), key -> new HashMap<>())
                .put((int) number(column(row, "questionNo")), row);
        }
        int written = 0;
        int attempts = 0;
        int finalized = 0;
        List<Map<String, Object>> state = rows(ctx, STATE);
        for (Map<String, Object> row : state) {
            UUID id = uuid(column(row, "publicationId"));
            String publisherKind = text(column(row, "publisherKind"));
            String publisherId = text(column(row, "publisherId"));
            boolean hasCursor = column(row, "cursorVersion") != null;
            Map<String, Object> cursor = new LinkedHashMap<>();

            if (chunk) {
                AnalyticsBuckets.Totals last = hasCursor ? new AnalyticsBuckets.Totals(
                    number(column(row, "lastViews")), number(column(row, "lastStarts")),
                    number(column(row, "lastCompletions")), number(column(row, "lastPasses")),
                    number(column(row, "lastWinners")), decimal(column(row, "lastSpent")))
                    : AnalyticsBuckets.Totals.ZERO;
                AnalyticsBuckets.Totals now = new AnalyticsBuckets.Totals(
                    last.views() + number(column(row, "newViews")), number(column(row, "starts")),
                    number(column(row, "completions")), number(column(row, "passes")),
                    number(column(row, "winners")), decimal(column(row, "spent")));
                AnalyticsBuckets.Totals increments = now.minus(last);
                if (increments.anyNegative()) {
                    // A counter never goes down: something else changed it. Nothing is written for the publication.
                    LOG.warn("Analytics: the counters of publication {} went down since the last run ({}); skipped",
                        id, increments);
                    continue;
                }
                if (!increments.isZero()) {
                    written += addToBucket(ctx, row, id, publisherKind, publisherId, increments, hasCursor, at);
                    cursor.putAll(bucketValues(now));
                    cursor.put("viewsUntil", viewsTo);
                }
            }

            if (column(row, "finalizedAt") == null) {
                Read read = null;
                boolean replace = false;
                long completions = number(column(row, "completions"));
                Instant ended = instant(column(row, "endedAt"));
                if (plan.recount().contains(id)) {
                    Read whole = recounted.getOrDefault(id, Read.none());
                    if (whole.attempts() == completions || late(ended, at)) {
                        if (whole.attempts() != completions) {
                            LOG.warn("Analytics: publication {} recounted {} finished attempts, its counter says {};"
                                + " finalized anyway a day after its end", id, whole.attempts(), completions);
                        }
                        read = whole;
                        replace = true;
                        cursor.put("finalizedAt", at);
                        finalized++;
                    }
                    // Otherwise an attempt finished within the lag: the next run recounts.
                } else if (plan.withoutRecount().contains(id)) {
                    long analysed = hasCursor ? number(column(row, "analysed")) : 0;
                    if (analysed == completions || late(ended, at)) {
                        LOG.info("Analytics: publication {} has {} completions, more than the recount's bound;"
                            + " finalized with the {} read so far", id, completions, analysed);
                        cursor.put("finalizedAt", at);
                        finalized++;
                    }
                } else if (incremental.containsKey(id)) {
                    read = incremental.get(id);
                }
                if (read != null) {
                    attempts += (int) read.attempts();
                    written += addAttempts(ctx, row, id, publisherKind, publisherId, read, replace, hasCursor,
                        shownRows.getOrDefault(id, Map.of()), cursor, at);
                }
            }

            if (!cursor.isEmpty()) {
                if (hasCursor) {
                    ctx.changes().update(QbAnalytics.CURSOR, id, number(column(row, "cursorVersion")), cursor);
                } else {
                    // A first cursor written by a recount counts the buckets from zero: the next chunk adds them all.
                    Map<String, Object> values = newCursor(id, publisherKind, publisherId);
                    values.putAll(cursor);
                    ctx.changes().insert(QbAnalytics.CURSOR, values);
                }
                written++;
            }
        }
        ctx.put(OUTPUT, new ItemOutput(input.kind(), state.size(), written, attempts, finalized, false));
    }

    /**
     * Adds a publication's increments to its hourly bucket; returns the rows written. The first time the job writes
     * for a publication that went on before the run's bucket, its history goes to the hour it went on (a gap), not to
     * the hour of the run (review L1: it would count as that hour's, today's, spending).
     */
    private static int addToBucket(ProcessContext ctx, Map<String, Object> row, UUID id, String publisherKind,
        String publisherId, AnalyticsBuckets.Totals increments, boolean hasCursor, Instant at) {
        Instant bucket = instant(column(row, "bucketStart"));
        Instant wentOn = instant(column(row, "wentOnAt"));
        boolean gap = AnalyticsBuckets.gap(at, bucket, wentOn, !hasCursor);
        Object hourId = column(row, "hourId");
        if (!hasCursor && wentOn != null && AnalyticsBuckets.hourOf(wentOn).isBefore(bucket)) {
            bucket = AnalyticsBuckets.hourOf(wentOn);
            hourId = null;
        }
        if (hourId == null) {
            Map<String, Object> values = bucketValues(increments);
            values.put("publicationId", id);
            values.put("publisherKind", publisherKind);
            values.put("publisherId", publisherId);
            values.put("hourStart", bucket);
            values.put("gap", gap);
            ctx.changes().insert(QbAnalytics.HOUR, values);
        } else {
            AnalyticsBuckets.Totals hour = new AnalyticsBuckets.Totals(number(column(row, "hourViews")),
                number(column(row, "hourStarts")), number(column(row, "hourCompletions")),
                number(column(row, "hourPasses")), number(column(row, "hourWinners")),
                decimal(column(row, "hourSpent")));
            Map<String, Object> values = bucketValues(hour.plus(increments));
            values.put("gap", gap || Boolean.TRUE.equals(column(row, "hourGap")));
            ctx.changes().update(QbAnalytics.HOUR, text(hourId), number(column(row, "hourVersion")), values);
        }
        return 1;
    }

    /** Adds (or, for a recount, replaces) the attempts read in the cursor's totals; returns the stat rows written. */
    private static int addAttempts(ProcessContext ctx, Map<String, Object> row, UUID id, String publisherKind,
        String publisherId, Read read,
        boolean replace, boolean hasCursor, Map<Integer, Map<String, Object>> shownRows, Map<String, Object> cursor,
        Instant at) {
        boolean fresh = replace || !hasCursor;
        long analysed = (fresh ? 0 : number(column(row, "analysed"))) + read.attempts();
        long submitted = (fresh ? 0 : number(column(row, "submitted"))) + read.submitted();
        long expired = (fresh ? 0 : number(column(row, "expired"))) + read.expired();
        BigDecimal duration = (fresh ? BigDecimal.ZERO : decimal(column(row, "durationSec"))).add(read.durationSec());
        SortedMap<Integer, QuestionTotals.Count> questions = replace ? read.questions()
            : QuestionTotals.add(QuestionTotals.parse(text(column(row, "questions"))), read.questions());
        cursor.put("analysed", analysed);
        cursor.put("submitted", submitted);
        cursor.put("expired", expired);
        cursor.put("durationSec", duration);
        cursor.put("questions", QuestionTotals.format(questions));
        if (read.lastAt() != null) {
            cursor.put("watermarkAt", read.lastAt());
            cursor.put("watermarkId", read.lastId());
        }
        int written = 0;
        long shown = hasCursor ? number(column(row, "shownAnalysed")) : 0;
        long shownSubmitted = hasCursor ? number(column(row, "shownSubmitted")) : 0;
        int k = MinGroup.of(column(row, "minGroup"));
        if (MinGroup.publish(analysed, shown, k)) {
            cursor.put("shownAnalysed", analysed);
            cursor.put("shownExpired", expired);
            cursor.put("shownAt", at);
            written += showQuestions(ctx, id, publisherKind, publisherId, questions, shownRows, at);
        }
        // The average time is over the attempts submitted in time only: it changes after k new ones of those, or k
        // new completions of which one was submitted would tell that one's time.
        if (MinGroup.publish(submitted, shownSubmitted, k)) {
            cursor.put("shownSubmitted", submitted);
            cursor.put("shownDurationSec", duration);
        }
        return written;
    }

    /** The run's record and the deletion of expired buckets; returns the rows written. */
    private static int record(ProcessContext ctx, String zone) {
        int written = 0;
        for (EntityInstance hour : instances(ctx, OLD)) {
            ctx.changes().delete(QbAnalytics.HOUR, hour.id(), hour.version());
            written++;
        }
        List<EntityInstance> runs = instances(ctx, RUN_ROW);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("refreshedAt", input(ctx).at());
        values.put("zone", zone);
        if (runs.isEmpty()) {
            values.put("job", JOB);
            ctx.changes().insert(QbPlay.STATS_RUN, values);
        } else {
            ctx.changes().update(QbPlay.STATS_RUN, runs.getFirst().id(), runs.getFirst().version(), values);
        }
        return written + 1;
    }

    /** Writes the question figures the sponsor is shown; returns the rows written. */
    private static int showQuestions(ProcessContext ctx, UUID publicationId, String publisherKind, String publisherId,
        Map<Integer, QuestionTotals.Count> questions, Map<Integer, Map<String, Object>> existing, Instant at) {
        int written = 0;
        for (Map.Entry<Integer, QuestionTotals.Count> question : questions.entrySet()) {
            Map<String, Object> row = existing.get(question.getKey());
            QuestionTotals.Count count = question.getValue();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("completions", count.completions());
            values.put("correct", count.correct());
            values.put("shownAt", at);
            if (row == null) {
                values.put("publicationId", publicationId);
                values.put("publisherKind", publisherKind);
                values.put("publisherId", publisherId);
                values.put("questionNo", question.getKey());
                ctx.changes().insert(QbAnalytics.QUESTION_STAT, values);
                written++;
            } else if (number(column(row, "completions")) != count.completions()
                || number(column(row, "correct")) != count.correct()) {
                ctx.changes().update(QbAnalytics.QUESTION_STAT, text(column(row, "statId")),
                    number(column(row, "version")), values);
                written++;
            }
        }
        return written;
    }

    private static Map<String, Object> bucketValues(AnalyticsBuckets.Totals totals) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("views", totals.views());
        values.put("starts", totals.starts());
        values.put("completions", totals.completions());
        values.put("passes", totals.passes());
        values.put("winners", totals.winners());
        values.put("spent", totals.spent());
        return values;
    }

    private static Map<String, Object> newCursor(UUID publicationId, String publisherKind, String publisherId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("publicationId", publicationId);
        values.put("publisherKind", publisherKind);
        values.put("publisherId", publisherId);
        values.putAll(bucketValues(AnalyticsBuckets.Totals.ZERO));
        values.put("analysed", 0L);
        values.put("submitted", 0L);
        values.put("expired", 0L);
        values.put("durationSec", BigDecimal.ZERO);
        values.put("questions", QuestionTotals.format(Map.of()));
        values.put("shownAnalysed", 0L);
        values.put("shownSubmitted", 0L);
        values.put("shownExpired", 0L);
        values.put("shownDurationSec", BigDecimal.ZERO);
        return values;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(ProcessContext ctx, String key) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(key);
        return rows == null ? List.of() : rows;
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> instances(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    /** A value of a template row by its result name (the keys may come back folded to lower case). */
    static Object column(Map<String, Object> row, String name) {
        return row.containsKey(name) ? row.get(name) : row.get(name.toLowerCase(Locale.ROOT));
    }

    static long number(Object value) {
        return value == null ? 0 : new BigDecimal(value.toString()).longValueExact();
    }

    static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    static Instant instant(Object value) {
        return value == null ? null : value instanceof OffsetDateTime time ? time.toInstant()
            : value instanceof Instant time ? time : Instant.parse(value.toString());
    }

    static UUID uuid(Object value) {
        return value == null ? null : value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private AnalyticsJob() {}
}

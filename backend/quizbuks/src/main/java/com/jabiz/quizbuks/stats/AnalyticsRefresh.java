package com.jabiz.quizbuks.stats;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.play.QbPlay;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
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
 * The analytics job {@code qb.analytics} (docs/quizbuks/plans/Q9e-analytics.md, D-Q9e-2), every ten minutes:
 * <ul>
 *   <li>{@code QB_ANALYTICS_REFRESH} ({@code internal()}): finds the publications running, ended within
 *       {@link AnalyticsLimits#RECENT} or ended and not yet recounted; reads them in chunks, one
 *       {@code QB_ANALYTICS_CHUNK} each; deletes hourly buckets older than {@link AnalyticsLimits#RETENTION}; and
 *       records the run's time and the zone its days are counted in ({@code jabiz.fiscal-year-zone}, D-Q9e-3), which
 *       the templates read;</li>
 *   <li>{@code QB_ANALYTICS_CHUNK} ({@code subProcessOnly()}): reads the chunk's counters (both sides in one
 *       statement), cursors and new first views, adds the increments to an hourly bucket ({@link AnalyticsBuckets}),
 *       reads the attempts finished since the watermark (at most the configured number) into the question totals and
 *       timings, recounts a publication whole {@link AnalyticsLimits#FINALIZE_AFTER} after it ended, and changes the
 *       figures the sponsor is shown only after at least the minimum group of new completions ({@link MinGroup}).</li>
 * </ul>
 * Neither takes a business lock or touches an answering table: the counters are read as committed, so answering's
 * statements and locks do not change ({@code AttemptStatementBudgetIT}). The job writes only rows that changed. A run
 * at a time not after the previous run's (a repeat) does nothing, so the job is idempotent.
 */
public final class AnalyticsRefresh {

    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsRefresh.class);

    public static final String REFRESH = "QB_ANALYTICS_REFRESH";
    public static final String CHUNK = "QB_ANALYTICS_CHUNK";
    public static final String JOB = "qb.analytics";
    public static final String DUE_TEMPLATE = "qb.system.analytics-due";
    public static final String STATE_TEMPLATE = "qb.system.analytics-state";
    public static final String ATTEMPTS_TEMPLATE = "qb.system.analytics-attempts";
    public static final String QUESTION_STATS_TEMPLATE = "qb.system.analytics-question-stats";

    public record RefreshInput(@NotNull Instant scheduledTime) {}

    /**
     * @param publications the publications read; {@code written} the rows inserted or changed; {@code purged} the
     *                     hourly buckets deleted; {@code repeated} the run was not after the previous one and did nothing
     */
    public record RefreshOutput(int publications, int written, int purged, boolean repeated) {}

    /**
     * @param previousRunAt the previous run's time (null: the job's first run), which decides the hourly bucket
     */
    public record ChunkInput(@NotNull Instant at, Instant previousRunAt, @NotEmpty List<@NotNull UUID> publicationIds) {}

    /**
     * @param attempts the finished attempts read; {@code finalized} the publications recounted and finalized
     */
    public record ChunkOutput(int publications, int written, int attempts, int finalized) {}

    static final String INPUT = "stats.input";
    static final String RUN = "stats.run";
    static final String DUE = "stats.due";
    static final String CHUNKS = "stats.chunks";
    static final String OLD = "stats.old";
    static final String STATE = "stats.state";
    static final String NEW = "stats.new";
    static final String FULL = "stats.full";
    static final String STATS = "stats.stats";
    static final String PLAN = "stats.plan";
    static final String OUTPUT = "stats.output";

    /**
     * @param chunkSize publications per chunk, 1 to {@link AnalyticsLimits#CHUNK_MAX}
     * @param zone      the deployment's zone, whose days the templates group the hours by
     */
    public static ProcessDefinition<RefreshInput, RefreshOutput, ProcessContext> refresh(int chunkSize, ZoneId zone) {
        if (chunkSize < 1 || chunkSize > AnalyticsLimits.CHUNK_MAX) {
            throw new IllegalArgumentException("quizbuks.analytics.chunk-size must be between 1 and "
                + AnalyticsLimits.CHUNK_MAX + ": " + chunkSize);
        }
        String zoneId = zone.getId();
        return ProcessDefinition.define(REFRESH, 1, RefreshInput.class, RefreshOutput.class, ProcessContext.class,
            pb -> pb
                .description("Adds the publications' latest figures to the sponsors' analytics.")
                .permissions(QbPermissions.STATS_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, RefreshOutput.class))
                .step("Load the last run", QueryEntities.<ProcessContext>of(QbPlay.defaultDataset(QbPlay.STATS_RUN),
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("job", JOB)).limit(1).build(), RUN))
                .step("Find the publications", RunTemplate.<ProcessContext>of(DUE_TEMPLATE,
                    ctx -> Map.of("recent", at(ctx).minus(AnalyticsLimits.RECENT)), DUE))
                .step("Read them in chunks", CallProcess.<ProcessContext>forEach(CHUNK, 1,
                    ctx -> repeated(ctx) ? List.of() : chunks(at(ctx), previousRun(ctx), ids(ctx), chunkSize),
                    CHUNKS))
                .step("Find the expired hours", QueryEntities.<ProcessContext>of(
                    QbAnalytics.defaultDataset(QbAnalytics.HOUR), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.Lt("hourStart", at(ctx).minus(AnalyticsLimits.RETENTION)))
                        .orderBy("hourStart", true).limit(AnalyticsLimits.PURGE_BATCH).build(), OLD))
                .compute("Delete them and record the run", (metadata, ctx) -> record(ctx, zoneId)));
    }

    private static Instant at(ProcessContext ctx) {
        return ctx.get(INPUT, RefreshInput.class).scheduledTime();
    }

    private static EntityInstance lastRun(ProcessContext ctx) {
        List<EntityInstance> runs = instances(ctx, RUN);
        return runs.isEmpty() ? null : runs.getFirst();
    }

    private static Instant previousRun(ProcessContext ctx) {
        EntityInstance run = lastRun(ctx);
        return run == null ? null : instant(run.attributes().get("refreshedAt"));
    }

    /** A run at a time not after the previous run's: a repeat, which must not count anything twice. */
    private static boolean repeated(ProcessContext ctx) {
        Instant previous = previousRun(ctx);
        return previous != null && !previous.isBefore(at(ctx));
    }

    private static List<UUID> ids(ProcessContext ctx) {
        List<UUID> ids = new ArrayList<>();
        for (Map<String, Object> row : rows(ctx, DUE)) {
            ids.add(uuid(column(row, "publicationId")));
        }
        ids.sort(Comparator.naturalOrder());
        return ids.size() > AnalyticsLimits.BATCH ? List.copyOf(ids.subList(0, AnalyticsLimits.BATCH))
            : List.copyOf(ids);
    }

    /** The publications in key order, cut into chunks of {@code size}. */
    static List<ChunkInput> chunks(Instant at, Instant previousRun, List<UUID> ids, int size) {
        List<ChunkInput> chunks = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += size) {
            chunks.add(new ChunkInput(at, previousRun, List.copyOf(ids.subList(i, Math.min(ids.size(), i + size)))));
        }
        return List.copyOf(chunks);
    }

    private static void record(ProcessContext ctx, String zone) {
        if (repeated(ctx)) {
            ctx.put(OUTPUT, new RefreshOutput(0, 0, 0, true));
            return;
        }
        int purged = 0;
        for (EntityInstance hour : instances(ctx, OLD)) {
            ctx.changes().delete(QbAnalytics.HOUR, hour.id(), hour.version());
            purged++;
        }
        EntityInstance run = lastRun(ctx);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("refreshedAt", at(ctx));
        values.put("zone", zone);
        if (run == null) {
            values.put("job", JOB);
            ctx.changes().insert(QbPlay.STATS_RUN, values);
        } else {
            ctx.changes().update(QbPlay.STATS_RUN, run.id(), run.version(), values);
        }
        @SuppressWarnings("unchecked")
        List<ChunkOutput> chunks = (List<ChunkOutput>) ctx.get(CHUNKS);
        int publications = 0;
        int written = 0;
        if (chunks != null) {
            for (ChunkOutput chunk : chunks) {
                publications += chunk.publications();
                written += chunk.written();
            }
        }
        ctx.put(OUTPUT, new RefreshOutput(publications, written, purged, false));
    }

    // ------------------------------------------------------------------------------------------------ the chunk

    /** What the chunk does with one publication's attempts this run. */
    private record Plan(List<UUID> incremental, List<UUID> recount) {}

    /** @param maxAttempts most finished attempts the chunk reads, 1 to {@link AnalyticsLimits#ATTEMPTS_MAX} */
    public static ProcessDefinition<ChunkInput, ChunkOutput, ProcessContext> chunk(int maxAttempts) {
        if (maxAttempts < 1 || maxAttempts > AnalyticsLimits.ATTEMPTS_MAX) {
            throw new IllegalArgumentException("quizbuks.analytics.max-attempts-per-chunk must be between 1 and "
                + AnalyticsLimits.ATTEMPTS_MAX + ": " + maxAttempts);
        }
        return ProcessDefinition.define(CHUNK, 1, ChunkInput.class, ChunkOutput.class, ProcessContext.class, pb -> pb
            .description("Adds some publications' latest figures to the sponsors' analytics.")
            .permissions(QbPermissions.STATS_SYSTEM)
            .subProcessOnly()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, ChunkOutput.class))
            .step("Read the counters and cursors", RunTemplate.<ProcessContext>of(STATE_TEMPLATE, ctx -> {
                ChunkInput input = input(ctx);
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("ids", input.publicationIds());
                params.put("bucket", AnalyticsBuckets.bucket(input.previousRunAt(), input.at()));
                params.put("viewsTo", input.at().minus(AnalyticsLimits.LAG));
                return params;
            }, STATE))
            .compute("Decide which to recount", (metadata, ctx) -> ctx.put(PLAN, plan(ctx)))
            .step("Read the attempts finished since", RunTemplate.<ProcessContext>of(ATTEMPTS_TEMPLATE,
                ctx -> attemptParams(ctx, ctx.get(PLAN, Plan.class).incremental(), false, maxAttempts), NEW))
            .step("Recount the ended publications", RunTemplate.<ProcessContext>of(ATTEMPTS_TEMPLATE,
                ctx -> attemptParams(ctx, ctx.get(PLAN, Plan.class).recount(), true, maxAttempts), FULL))
            .step("Read the shown question statistics", RunTemplate.<ProcessContext>of(QUESTION_STATS_TEMPLATE,
                ctx -> Map.of("ids", withAttempts(ctx)), STATS))
            .compute("Write what changed", (metadata, ctx) -> write(ctx)));
    }

    private static ChunkInput input(ProcessContext ctx) {
        return ctx.get(INPUT, ChunkInput.class);
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
     * The publications whose attempts are read after the watermark, and those recounted whole: ended at least
     * {@link AnalyticsLimits#FINALIZE_AFTER} ago with no attempt in progress (starts = completions, one statement).
     * Finalized ones are not read at all.
     */
    private static Plan plan(ProcessContext ctx) {
        Instant at = input(ctx).at();
        List<UUID> incremental = new ArrayList<>();
        List<UUID> recount = new ArrayList<>();
        for (Map<String, Object> row : rows(ctx, STATE)) {
            if (column(row, "finalizedAt") != null) {
                continue;
            }
            UUID id = uuid(column(row, "publicationId"));
            Instant ended = instant(column(row, "endedAt"));
            boolean settled = "ENDED".equals(column(row, "status")) && ended != null
                && !ended.plus(AnalyticsLimits.FINALIZE_AFTER).isAfter(at)
                && number(column(row, "starts")) == number(column(row, "completions"));
            (settled ? recount : incremental).add(id);
        }
        return new Plan(List.copyOf(incremental), List.copyOf(recount));
    }

    /** The publications with attempts read this run, whose shown statistics may change. */
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

        static final Read NONE = new Read(new TreeMap<>(), 0, 0, 0, BigDecimal.ZERO, null, null);
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

    private static void write(ProcessContext ctx) {
        ChunkInput input = input(ctx);
        Instant at = input.at();
        Instant viewsTo = at.minus(AnalyticsLimits.LAG);
        Map<UUID, Read> incremental = reads(rows(ctx, NEW));
        Map<UUID, Read> recounted = reads(rows(ctx, FULL));
        Set<UUID> recount = new LinkedHashSet<>(ctx.get(PLAN, Plan.class).recount());
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
            String sponsorId = text(column(row, "sponsorId"));
            boolean hasCursor = column(row, "cursorVersion") != null;
            AnalyticsBuckets.Totals last = hasCursor ? new AnalyticsBuckets.Totals(number(column(row, "lastViews")),
                number(column(row, "lastStarts")), number(column(row, "lastCompletions")),
                number(column(row, "lastPasses")), number(column(row, "lastWinners")),
                decimal(column(row, "lastSpent"))) : AnalyticsBuckets.Totals.ZERO;
            AnalyticsBuckets.Totals now = new AnalyticsBuckets.Totals(last.views() + number(column(row, "newViews")),
                number(column(row, "starts")), number(column(row, "completions")), number(column(row, "passes")),
                number(column(row, "winners")), decimal(column(row, "spent")));
            AnalyticsBuckets.Totals increments = now.minus(last);
            if (increments.anyNegative()) {
                // A counter never goes down: something else changed it. Nothing is written for the publication.
                LOG.warn("Analytics: the counters of publication {} went down since the last run ({}); skipped", id,
                    increments);
                continue;
            }
            Map<String, Object> cursor = new LinkedHashMap<>();

            // The hourly bucket.
            if (!increments.isZero()) {
                Instant bucket = instant(column(row, "bucketStart"));
                boolean gap = AnalyticsBuckets.gap(at, bucket, instant(column(row, "wentOnAt")), !hasCursor);
                Object hourId = column(row, "hourId");
                if (hourId == null) {
                    Map<String, Object> values = bucketValues(increments);
                    values.put("publicationId", id);
                    values.put("sponsorId", sponsorId);
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
                    ctx.changes().update(QbAnalytics.HOUR, text(hourId), number(column(row, "hourVersion")),
                        values);
                }
                written++;
                cursor.putAll(bucketValues(now));
                cursor.put("viewsUntil", viewsTo);
            }

            // The attempts read: added after the watermark, or a whole recount that replaces the totals.
            if (column(row, "finalizedAt") == null) {
                Read read = null;
                boolean replace = false;
                if (recount.contains(id)) {
                    Read whole = recounted.getOrDefault(id, Read.NONE);
                    Instant ended = instant(column(row, "endedAt"));
                    boolean late = ended != null && !ended.plus(AnalyticsLimits.FINALIZE_ANYWAY_AFTER).isAfter(at);
                    if (whole.attempts() == now.completions() || late) {
                        if (whole.attempts() != now.completions()) {
                            LOG.warn("Analytics: publication {} recounted {} finished attempts, its counter says {};"
                                + " finalized anyway a day after its end", id, whole.attempts(), now.completions());
                        }
                        read = whole;
                        replace = true;
                        cursor.put("finalizedAt", at);
                        finalized++;
                    }
                    // Otherwise an attempt finished within the lag: the next run recounts.
                } else if (incremental.containsKey(id)) {
                    read = incremental.get(id);
                }
                if (read != null) {
                    attempts += (int) read.attempts();
                    long analysed = (replace || !hasCursor ? 0 : number(column(row, "analysed"))) + read.attempts();
                    long submitted = (replace || !hasCursor ? 0 : number(column(row, "submitted")))
                        + read.submitted();
                    long expired = (replace || !hasCursor ? 0 : number(column(row, "expired"))) + read.expired();
                    BigDecimal duration = (replace || !hasCursor ? BigDecimal.ZERO
                        : decimal(column(row, "durationSec"))).add(read.durationSec());
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
                    long shown = hasCursor ? number(column(row, "shownAnalysed")) : 0;
                    long shownSubmitted = hasCursor ? number(column(row, "shownSubmitted")) : 0;
                    int k = MinGroup.of(column(row, "minGroup"));
                    if (MinGroup.publish(analysed, shown, k)) {
                        cursor.put("shownAnalysed", analysed);
                        cursor.put("shownExpired", expired);
                        cursor.put("shownAt", at);
                        written += showQuestions(ctx, id, sponsorId, questions,
                            shownRows.getOrDefault(id, Map.of()), at);
                    }
                    // The average time is over the attempts submitted in time only: it changes after k new ones of
                    // those, or k new completions of which one was submitted would tell that one's time.
                    if (MinGroup.publish(submitted, shownSubmitted, k)) {
                        cursor.put("shownSubmitted", submitted);
                        cursor.put("shownDurationSec", duration);
                    }
                }
            }

            // The cursor, when anything changed.
            if (!cursor.isEmpty()) {
                if (!cursor.containsKey("views")) {
                    cursor.putAll(bucketValues(now));
                }
                if (hasCursor) {
                    ctx.changes().update(QbAnalytics.CURSOR, id, number(column(row, "cursorVersion")), cursor);
                } else {
                    Map<String, Object> values = newCursor(id, sponsorId);
                    values.putAll(cursor);
                    if (!values.containsKey("viewsUntil")) {
                        values.put("viewsUntil", viewsTo);
                    }
                    ctx.changes().insert(QbAnalytics.CURSOR, values);
                }
                written++;
            }
        }
        ctx.put(OUTPUT, new ChunkOutput(state.size(), written, attempts, finalized));
    }

    /** Writes the question figures the sponsor is shown; returns the rows written. */
    private static int showQuestions(ProcessContext ctx, UUID publicationId, String sponsorId,
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
                values.put("sponsorId", sponsorId);
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

    private static Map<String, Object> newCursor(UUID publicationId, String sponsorId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("publicationId", publicationId);
        values.put("sponsorId", sponsorId);
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

    private AnalyticsRefresh() {}
}

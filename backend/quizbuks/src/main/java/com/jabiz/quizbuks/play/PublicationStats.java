package com.jabiz.quizbuks.play;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The completion counts of the last hour, day, week and month (U-22; docs/quizbuks/plans/Q5-answering.md, D-Q5-6),
 * refreshed every ten minutes by the job {@code qb.publication-stats} and read by the detail page (authenticated
 * templates have no cache, so counting there would recount a popular publication's month on every open):
 * <ul>
 *   <li>{@code QB_PUBLICATION_STATS_REFRESH}: finds the publications running, or ended (by any reason) within the last
 *       {@link PlayLimits#STATS_DAYS} days, by key; counts them in chunks of the configured size, one
 *       {@code QB_PUBLICATION_STATS_CHUNK} each, so that no write exceeds the dataset's batch; and records the run's
 *       time ({@link QbPlay#STATS_RUN}), which the detail page shows as the time of the counts;</li>
 *   <li>{@code QB_PUBLICATION_STATS_CHUNK}: counts the chunk's publications at the run's time on the attempts' index
 *       and writes the rows that changed.</li>
 * </ul>
 * They take no business lock and write only their own tables, so the run is one transaction.
 */
public final class PublicationStats {

    public static final String REFRESH = "QB_PUBLICATION_STATS_REFRESH";
    public static final String CHUNK = "QB_PUBLICATION_STATS_CHUNK";
    public static final String JOB = "qb.publication-stats";
    public static final String TEMPLATE = "qb.system.publication-stats";

    public record RefreshInput(@NotNull Instant scheduledTime) {}

    /** @param publications the publications counted; {@code written} the rows inserted or changed */
    public record RefreshOutput(int publications, int written) {}

    public record ChunkInput(@NotNull Instant at, @NotEmpty List<@NotNull UUID> publicationIds) {}

    public record ChunkOutput(int publications, int written) {}

    static final String INPUT = "play.input";
    static final String DUE = "play.due";
    static final String CHUNKS = "play.chunks";
    static final String ROWS = "play.rows";
    static final String RUN = "play.run";
    static final String WRITTEN = "play.written";

    static final List<String> WINDOWS = List.of("last1h", "last24h", "last7d", "last30d");

    /** @param chunkSize publications counted and written per chunk; at most {@link PlayLimits#STATS_CHUNK_MAX} */
    public static ProcessDefinition<RefreshInput, RefreshOutput, ProcessContext> refresh(int chunkSize) {
        if (chunkSize < 1 || chunkSize > PlayLimits.STATS_CHUNK_MAX) {
            throw new IllegalArgumentException("quizbuks.stats.chunk-size must be between 1 and "
                + PlayLimits.STATS_CHUNK_MAX + ": " + chunkSize);
        }
        return ProcessDefinition.define(REFRESH, 1, RefreshInput.class, RefreshOutput.class, ProcessContext.class,
            pb -> pb
                .description("Refreshes the completion counts of the last hour, day, week and month.")
                .permissions(QbPermissions.PLAY_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> {
                    @SuppressWarnings("unchecked")
                    List<ChunkOutput> chunks = (List<ChunkOutput>) ctx.get(CHUNKS);
                    int written = chunks == null ? 0 : chunks.stream().mapToInt(ChunkOutput::written).sum();
                    return new RefreshOutput(PlaySteps.list(ctx, DUE).size(), written);
                })
                .step("Find the publications to count", QueryEntities.<ProcessContext>of(
                    QbPublications.defaultDataset(QbPublications.CARD), ctx -> due(at(ctx)), DUE))
                .step("Count them in chunks", CallProcess.<ProcessContext>forEach(CHUNK, 1,
                    ctx -> chunks(at(ctx), PlaySteps.list(ctx, DUE), chunkSize), CHUNKS))
                .step("Load the last run", QueryEntities.<ProcessContext>of(QbPlay.defaultDataset(QbPlay.STATS_RUN),
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("job", JOB)).limit(1).build(), RUN))
                .compute("Record the run", (metadata, ctx) -> {
                    EntityInstance run = PlaySteps.first(ctx, RUN);
                    if (run == null) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("job", JOB);
                        values.put("refreshedAt", at(ctx));
                        ctx.changes().insert(QbPlay.STATS_RUN, values);
                    } else {
                        ctx.changes().update(QbPlay.STATS_RUN, run.id(), run.version(),
                            Map.of("refreshedAt", at(ctx)));
                    }
                }));
    }

    private static Instant at(ProcessContext ctx) {
        return ctx.get(INPUT, RefreshInput.class).scheduledTime();
    }

    /** The publications running, or ended within the last {@link PlayLimits#STATS_DAYS} days before {@code at}. */
    static EntityQuery due(Instant at) {
        List<Object> running = new ArrayList<>(List.of(PublicationStatus.LIVE, PublicationStatus.PAUSED,
            PublicationStatus.FUNDS_SHORT));
        return EntityQuery.builder().where(new QueryPredicate.Or(List.of(
                new QueryPredicate.In("status", running),
                new QueryPredicate.And(List.of(new QueryPredicate.Eq("status", PublicationStatus.ENDED),
                    new QueryPredicate.Gt("endedAt", at.minus(Duration.ofDays(PlayLimits.STATS_DAYS))))))))
            .orderBy("publicationId", true).limit(PlayLimits.STATS_BATCH).build();
    }

    /** The publications in key order, cut into chunks of {@code size}. */
    static List<ChunkInput> chunks(Instant at, List<EntityInstance> due, int size) {
        List<ChunkInput> chunks = new ArrayList<>();
        for (int i = 0; i < due.size(); i += size) {
            chunks.add(new ChunkInput(at, due.subList(i, Math.min(due.size(), i + size)).stream()
                .map(row -> PlaySteps.uuid(row.id())).toList()));
        }
        return List.copyOf(chunks);
    }

    public static ProcessDefinition<ChunkInput, ChunkOutput, ProcessContext> chunk() {
        return ProcessDefinition.define(CHUNK, 1, ChunkInput.class, ChunkOutput.class, ProcessContext.class, pb -> pb
            .description("Counts the recent completions of some publications.")
            .permissions(QbPermissions.PLAY_SYSTEM)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new ChunkOutput(rows(ctx).size(), (Integer) ctx.get(WRITTEN)))
            .step("Count the completions", RunTemplate.<ProcessContext>of(TEMPLATE, ctx -> {
                ChunkInput input = ctx.get(INPUT, ChunkInput.class);
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("at", input.at());
                params.put("ids", input.publicationIds());
                return params;
            }, ROWS))
            .compute("Write the counts", (metadata, ctx) -> write(ctx)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(ProcessContext ctx) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(ROWS);
        return rows == null ? List.of() : rows;
    }

    /** Writes the counts that changed; {@code refreshedAt} of a row is when its counts last changed. */
    static void write(ProcessContext ctx) {
        Instant at = ctx.get(INPUT, ChunkInput.class).at();
        int written = 0;
        for (Map<String, Object> row : rows(ctx)) {
            Map<String, Object> values = new LinkedHashMap<>();
            boolean changed = false;
            for (String window : WINDOWS) {
                BigDecimal count = PlaySteps.money(PlaySteps.column(row, window));
                values.put(window, count);
                Object old = PlaySteps.column(row, "old" + Character.toUpperCase(window.charAt(0))
                    + window.substring(1));
                changed |= old == null || PlaySteps.money(old).compareTo(count) != 0;
            }
            Object version = PlaySteps.column(row, "statsVersion");
            if (version == null) {
                values.put("publicationId", PlaySteps.uuid(PlaySteps.column(row, "publicationId")));
                values.put("refreshedAt", at);
                ctx.changes().insert(QbPlay.STATS, values);
                written++;
            } else if (changed) {
                values.put("refreshedAt", at);
                ctx.changes().update(QbPlay.STATS, PlaySteps.uuid(PlaySteps.column(row, "publicationId")),
                    PlaySteps.number(version), values);
                written++;
            }
        }
        ctx.put(WRITTEN, written);
    }

    private PublicationStats() {}
}

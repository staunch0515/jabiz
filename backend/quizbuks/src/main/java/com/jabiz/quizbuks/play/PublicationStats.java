package com.jabiz.quizbuks.play;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code QB_PUBLICATION_STATS_REFRESH} (U-22; docs/quizbuks/plans/Q5-answering.md, D-Q5-6): every ten minutes, the
 * completions of the last hour, day, week and month of every publication live or ended within the last month, counted
 * at the run's scheduled time on the attempts' index and written to {@code qb_publication_stats}, which the detail
 * page reads (authenticated templates have no cache, so counting there would recount a popular publication's month on
 * every open). Only rows that changed are written. It takes no business lock and writes only its own table, so the
 * run is one transaction.
 */
public final class PublicationStats {

    public static final String REFRESH = "QB_PUBLICATION_STATS_REFRESH";
    public static final String JOB = "qb.publication-stats";
    public static final String TEMPLATE = "qb.system.publication-stats";

    public record RefreshInput(@NotNull Instant scheduledTime) {}

    /** @param publications the publications counted; {@code written} the rows inserted or changed */
    public record RefreshOutput(int publications, int written) {}

    static final String INPUT = "play.input";
    static final String ROWS = "play.rows";
    static final String WRITTEN = "play.written";

    static final List<String> WINDOWS = List.of("last1h", "last24h", "last7d", "last30d");

    public static ProcessDefinition<RefreshInput, RefreshOutput, ProcessContext> refresh() {
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
                .outputMapper(ctx -> new RefreshOutput(rows(ctx).size(), (Integer) ctx.get(WRITTEN)))
                .step("Count the completions", RunTemplate.<ProcessContext>of(TEMPLATE, ctx -> {
                    Instant at = ctx.get(INPUT, RefreshInput.class).scheduledTime();
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("at", at);
                    params.put("since", at.minus(Duration.ofDays(PlayLimits.STATS_DAYS)));
                    return params;
                }, ROWS))
                .compute("Write the counts", (metadata, ctx) -> write(ctx)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(ProcessContext ctx) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(ROWS);
        return rows == null ? List.of() : rows;
    }

    static void write(ProcessContext ctx) {
        Instant at = ctx.get(INPUT, RefreshInput.class).scheduledTime();
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

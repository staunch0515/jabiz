package com.jabiz.quizbuks.tree;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The daily count of the people who answered and have not lit (F-S3f, docs/quizbuks/plans/impl/F-S3.md §1): the job
 * {@value #JOB} takes each graph in effect that Takers light ({@value #ITEMS_TEMPLATE}) as an item, and
 * {@value #PROCESS} counts its nodes ({@value #UNLIT_TEMPLATE}): the attempts submitted before the day of the run
 * began (UTC), the lighting as it is when the item runs. Each node keeps one row, updated in place
 * ({@code QbTreeNodeCount}); the rows of nodes no longer in effect are removed. The pages show a count only from the
 * statistics' minimum group up, with the day it covers. Reading attempts in a template instead (per page view) would
 * scan every attempt of every bound publication; once a day per graph does it off the request path.
 */
public final class TreeNodeCounts {

    public static final String PROCESS = "QB_TREE_NODE_COUNT";
    public static final String JOB = "qb.tree-node-counts";
    public static final String ITEMS_TEMPLATE = "qb.system.tree-count-items";
    public static final String UNLIT_TEMPLATE = "qb.system.tree-node-unlit";
    /** Every day at 00:53 UTC (09:53 in Tokyo): after the day ended, before the reviewers' reminder's next day. */
    public static final String CRON = "0 53 0 * * *";

    /** One graph of one run. */
    public record CountInput(@NotNull Instant scheduledTime, @NotBlank String treeId) {}

    /** @param nodes the nodes counted; {@code written} the rows inserted, changed or removed */
    public record CountOutput(int nodes, int written, LocalDate countedOn) {}

    static final String INPUT = "count.input";
    static final String ROWS = "count.rows";
    static final String EXISTING = "count.existing";
    static final String OUTPUT = "count.output";

    private TreeNodeCounts() {}

    /** The start of the run's day in UTC: the attempts submitted before it count. */
    static Instant before(Instant scheduledTime) {
        return scheduledTime.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** The last day the count covers: the day before the run's. */
    static LocalDate countedOn(Instant scheduledTime) {
        return scheduledTime.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
    }

    public static ProcessDefinition<CountInput, CountOutput, ProcessContext> definition() {
        return ProcessDefinition.define(PROCESS, 1, CountInput.class, CountOutput.class, ProcessContext.class, pb -> pb
            .description("Counts, node by node, the people who answered a knowledge map's publications and have not "
                + "lit the node (one item of the daily job).")
            .permissions(QbPermissions.TREE_SYSTEM)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, CountOutput.class))
            .step("Count the nodes", RunTemplate.<ProcessContext>of(UNLIT_TEMPLATE, ctx -> Map.of(
                "treeId", input(ctx).treeId(), "before", before(input(ctx).scheduledTime())), ROWS))
            .step("Load the counts", QueryEntities.<ProcessContext>of(QbTrees.defaultDataset(QbTrees.NODE_COUNT),
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("treeId", input(ctx).treeId()))
                    .limit(Integer.MAX_VALUE).build(), EXISTING))
            .compute("Write what changed", (metadata, ctx) -> write(ctx)));
    }

    private static CountInput input(ProcessContext ctx) {
        return ctx.get(INPUT, CountInput.class);
    }

    @SuppressWarnings("unchecked")
    static void write(ProcessContext ctx) {
        CountInput input = input(ctx);
        LocalDate day = countedOn(input.scheduledTime());
        List<Map<String, Object>> rows = ctx.contains(ROWS) ? (List<Map<String, Object>>) ctx.get(ROWS) : List.of();
        List<EntityInstance> existing = ctx.contains(EXISTING) ? (List<EntityInstance>) ctx.get(EXISTING) : List.of();
        Map<String, EntityInstance> byNode = new HashMap<>();
        existing.forEach(row -> byNode.put(String.valueOf((Object) row.get("nodeId")), row));
        int written = 0;
        for (Map<String, Object> row : rows) {
            String nodeId = String.valueOf(row.get("nodeId"));
            BigDecimal count = new BigDecimal(String.valueOf(row.get("answeredUnlit")));
            EntityInstance old = byNode.remove(nodeId);
            if (old == null) {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("treeId", input.treeId());
                values.put("nodeId", nodeId);
                values.put("answeredUnlit", count);
                values.put("countedOn", day);
                ctx.changes().insert(QbTrees.NODE_COUNT, values);
                written++;
            } else if (!day.toString().equals(String.valueOf((Object) old.get("countedOn")))
                || count.compareTo(new BigDecimal(String.valueOf((Object) old.get("answeredUnlit")))) != 0) {
                ctx.changes().update(QbTrees.NODE_COUNT, old.id(), old.version(), Map.of("answeredUnlit", count,
                    "countedOn", day));
                written++;
            }
        }
        for (EntityInstance gone : byNode.values()) {
            ctx.changes().delete(QbTrees.NODE_COUNT, gone.id(), gone.version());
            written++;
        }
        ctx.put(OUTPUT, new CountOutput(rows.size(), written, day));
    }
}

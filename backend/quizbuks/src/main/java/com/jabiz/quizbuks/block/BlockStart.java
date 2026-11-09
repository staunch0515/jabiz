package com.jabiz.quizbuks.block;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The start of an attempt and the caller's blocks (F-S8): a new start of a publication the caller blocked is refused
 * (422 {@value BlockCodes#BLOCKED}). Whether the caller has any block at all is read with the start's first read (one
 * row at most, the same statement: a caller without blocks sends nothing more); only then, for a new start, the blocks
 * covering the card ({@link BlockRules#candidates}, the unique index) in one statement before the lock. An attempt in
 * progress continues.
 */
public final class BlockStart {

    static final String ANY = "block.any";
    static final String COVERING = "block.covering";

    private BlockStart() {}

    /** The query, for the start's first {@link QueryEntities#all} step, of one block of the caller's (any). */
    public static QueryEntities.Metadata<ProcessContext> any() {
        return QueryEntities.query(QbBlocks.DEFAULT_DATASET, ctx -> EntityQuery.builder()
            .where(new QueryPredicate.Eq("userId", ctx.request().actorId())).limit(1).build(), ANY);
    }

    /**
     * Loads the caller's blocks covering the card, for a new start of a caller with blocks only (otherwise the query
     * matches nothing and sends nothing).
     */
    public static <I, O> void load(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, EntityInstance> card, Predicate<ProcessContext> starting) {
        pb.step("Load the caller's blocks of the publication", QueryEntities.<ProcessContext>of(
            QbBlocks.DEFAULT_DATASET, ctx -> {
                EntityInstance read = card.apply(ctx);
                boolean ask = read != null && starting.test(ctx) && !BlockProcesses.list(ctx, ANY).isEmpty();
                List<QueryPredicate> objects = new ArrayList<>();
                if (ask) {
                    for (BlockRules.Block block : BlockRules.candidates(BlockProcesses.target(read))) {
                        objects.add(new QueryPredicate.And(List.of(new QueryPredicate.Eq("kind", block.kind()),
                            new QueryPredicate.Eq("objectId", block.objectId()))));
                    }
                }
                List<Object> users = new ArrayList<>();
                if (ask) {
                    users.add(ctx.request().actorId());
                }
                return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("userId", users),
                        objects.isEmpty() ? new QueryPredicate.In("kind", List.of()) : new QueryPredicate.Or(objects))))
                    .limit(BlockRules.KINDS.size()).build();
            }, COVERING));
    }

    /** Refuses a new start of a card the caller blocked (the kinds blocking it in the violation's parameters). */
    public static void refuse(ProcessContext ctx, EntityInstance card, boolean starting) {
        if (card == null || !starting) {
            return;
        }
        List<BlockRules.Block> found = BlockProcesses.list(ctx, COVERING).stream()
            .map(row -> new BlockRules.Block(BlockProcesses.text(row.get("kind")),
                BlockProcesses.text(row.get("objectId")))).toList();
        List<BlockRules.Block> matching = BlockRules.matching(found, BlockProcesses.target(card));
        if (!matching.isEmpty()) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("kinds", matching.stream().map(BlockRules.Block::kind).distinct().toList());
            ctx.reject(new Violation("publicationId", BlockCodes.BLOCKED, "You blocked this publication (or its "
                + "quiz, its sponsor or its video's provider): unblock it to start", params));
        }
    }
}

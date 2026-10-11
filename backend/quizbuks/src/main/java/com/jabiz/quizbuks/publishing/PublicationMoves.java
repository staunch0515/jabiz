package com.jabiz.quizbuks.publishing;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import static com.jabiz.quizbuks.publishing.PublicationEditing.FOUND;
import static com.jabiz.quizbuks.publishing.PublicationEditing.PUB_ID;
import static com.jabiz.quizbuks.publishing.PublicationEditing.first;

/**
 * What answering (Q5, docs/quizbuks/plans/Q5-answering.md, D-Q5-3, D-Q5-5) may do to a publication, under its lock:
 * read it with its schedule row and card, and move it after an attempt completed: to {@code ENDED} when its winners or
 * budget are used up, to {@code FUNDS_SHORT} when its sponsor could not pay a reward. The moves go through the same
 * {@link PublicationEditing#write} as every other, which keeps the schedule row and the card in step.
 *
 * <p>The steps use the context keys of {@link PublicationEditing} ({@code publicationId}, {@code found},
 * {@code schedule}, {@code card}, {@code output}); a process using them keeps its own keys apart.
 */
public final class PublicationMoves {

    /** The name of a publication's lock; null without one. */
    public static String lockName(Object publicationId) {
        return PublicationEditing.lockName(publicationId);
    }

    /**
     * Loads the publication {@code publicationId} names (from the default dataset; none when it names none), its
     * schedule row and its card. Take the publication's lock first.
     */
    public static <I, O> void load(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Object> publicationId) {
        pb.compute("Name the publication", (metadata, ctx) -> ctx.put(PUB_ID, publicationId.apply(ctx)))
            .step("Load the publication", QueryEntities.<ProcessContext>of(
                QbPublications.defaultDataset(QbPublications.PUBLICATION), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("publicationId", PublicationEditing.ids(ctx.get(PUB_ID))))
                    .limit(1).build(), FOUND));
        PublicationEditing.loadSchedule(pb);
    }

    /** The publication loaded by {@link #load}; null when there is none. */
    public static EntityInstance publication(ProcessContext ctx) {
        return first(ctx, FOUND);
    }

    /** The card loaded by {@link #load}; null when the publication never went on. */
    public static EntityInstance card(ProcessContext ctx) {
        return first(ctx, PublicationEditing.CARD);
    }

    /**
     * Moves the publication after an attempt completed, given its counters as they are now: a running one whose
     * winners or budget are used up ends ({@link PublicationRules#endsBy}); otherwise a live one whose sponsor could
     * not pay the reward ({@code pending}) is underfunded. Returns the new state, or null when it stays as it is.
     */
    public static String afterCompletion(ProcessContext ctx, long winners, BigDecimal spent, boolean pending) {
        EntityInstance publication = publication(ctx);
        if (publication == null) {
            return null;
        }
        String status = publication.get("status");
        String reason = PublicationRules.endsBy(PublicationEditing.money(publication.get("budgetCap")),
            PublicationEditing.integer(publication.get("maxWinners")),
            PublicationEditing.money(publication.get("maxReward")), winners, spent);
        Map<String, Object> values = new LinkedHashMap<>();
        if (reason != null && PublicationStatus.RUNNING.contains(status)) {
            values.put("status", PublicationStatus.ENDED);
            values.put("endedAt", ctx.opTime());
            values.put("endReason", reason);
        } else if (pending && PublicationStatus.LIVE.equals(status)) {
            values.put("status", PublicationStatus.FUNDS_SHORT);
        } else {
            return null;
        }
        PublicationEditing.write(ctx, publication, values);
        return (String) values.get("status");
    }

    private PublicationMoves() {}
}

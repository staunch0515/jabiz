package com.jabiz.quizbuks.publishing;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static com.jabiz.quizbuks.publishing.PublicationEditing.FOUND;
import static com.jabiz.quizbuks.publishing.PublicationEditing.PUB_ID;
import static com.jabiz.quizbuks.publishing.PublicationEditing.first;

/**
 * What answering (Q5, docs/quizbuks/plans/Q5-answering.md, D-Q5-3, D-Q5-5) may do to a publication, under its lock:
 * read its card, and move it after an attempt completed: to {@code ENDED} when its winners or budget are used up, to
 * {@code FUNDS_SHORT} when its sponsor could not pay a reward. The moves go through the same
 * {@link PublicationEditing#write} as every other, which keeps the schedule row and the card in step.
 *
 * <p>A completion reads only the card (Q10a, docs/quizbuks/perf/Q10a-load-test.md): its state is the publication's
 * (every move writes both under the lock) and its limits are the publication's frozen ones, so {@link #moveAfter} on
 * the card says whether the publication moves. Only then does the completion call {@link #MOVE}, which reads the
 * publication and its schedule row and moves it: the common completion reads two rows fewer under the lock.
 *
 * <p>The steps use the context keys of {@link PublicationEditing} ({@code publicationId}, {@code found},
 * {@code schedule}, {@code card}, {@code output}); a process using them keeps its own keys apart.
 */
public final class PublicationMoves {

    /** Moves a publication after a completion (a sub-process of finishing an attempt, under the publication's lock). */
    public static final String MOVE = "QB_PUBLICATION_COMPLETION_MOVE";

    /**
     * The publication's counters after a completion, and the move the completion decided on the card.
     *
     * @param pending whether the completion's reward is pending (the sponsor could not pay it)
     * @param status  the state {@link #moveAfter} gave on the card: the publication must move to the same
     */
    public record MoveInput(@NotNull UUID publicationId, long winners, @NotNull BigDecimal spent, boolean pending,
        @NotNull String status) {}

    /** @param status the publication's state afterwards; null when it stayed as it was */
    public record MoveOutput(UUID publicationId, String status) {}

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

    /** Loads the card of the publication {@code publicationId} names (none when it names none). Take its lock first. */
    public static <I, O> void loadCard(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Object> publicationId) {
        pb.step("Load the card", QueryEntities.<ProcessContext>of(
            QbPublications.defaultDataset(QbPublications.CARD), ctx -> EntityQuery.builder()
                .where(new QueryPredicate.In("publicationId", PublicationEditing.ids(publicationId.apply(ctx))))
                .limit(1).build(), PublicationEditing.CARD));
    }

    /** The publication loaded by {@link #load}; null when there is none. */
    public static EntityInstance publication(ProcessContext ctx) {
        return first(ctx, FOUND);
    }

    /** The card loaded by {@link #load} or {@link #loadCard}; null when the publication never went on. */
    public static EntityInstance card(ProcessContext ctx) {
        return first(ctx, PublicationEditing.CARD);
    }

    /**
     * The state a publication (or its card: the same state and limits) moves to after a completion, given its counters
     * as they are now: a running one whose winners or budget are used up ends ({@link PublicationRules#endsBy});
     * otherwise a live one whose sponsor could not pay the reward ({@code pending}) is underfunded. Null when it stays
     * as it is.
     */
    public static String moveAfter(EntityInstance publicationOrCard, long winners, BigDecimal spent,
        boolean pending) {
        String status = publicationOrCard.get("status");
        if (endReason(publicationOrCard, winners, spent) != null && PublicationStatus.RUNNING.contains(status)) {
            return PublicationStatus.ENDED;
        }
        return pending && PublicationStatus.LIVE.equals(status) ? PublicationStatus.FUNDS_SHORT : null;
    }

    private static String endReason(EntityInstance publicationOrCard, long winners, BigDecimal spent) {
        return PublicationRules.endsBy(PublicationEditing.money(publicationOrCard.get("budgetCap")),
            PublicationEditing.integer(publicationOrCard.get("maxWinners")),
            PublicationEditing.money(publicationOrCard.get("maxReward")), winners, spent);
    }

    /**
     * Moves the publication after an attempt completed ({@link #moveAfter}); returns the new state, or null when it
     * stays as it is.
     */
    public static String afterCompletion(ProcessContext ctx, long winners, BigDecimal spent, boolean pending) {
        EntityInstance publication = publication(ctx);
        if (publication == null) {
            return null;
        }
        String status = moveAfter(publication, winners, spent, pending);
        if (status == null) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", status);
        if (PublicationStatus.ENDED.equals(status)) {
            values.put("endedAt", ctx.opTime());
            values.put("endReason", endReason(publication, winners, spent));
        }
        PublicationEditing.write(ctx, publication, values);
        return status;
    }

    /**
     * {@link #MOVE}: only as a sub-process of finishing an attempt (platform decision D41), which holds the
     * publication's lock already; it takes it again (at once: the transaction holds it) rather than rely on that, reads
     * the publication, and moves it as {@link #afterCompletion} decides on the publication itself. A missing
     * publication, or a publication that would not move as its card said, is a defect (the card's state and limits are
     * the publication's): it fails, and the completion with it, rather than leave the two apart.
     */
    public static ProcessDefinition<MoveInput, MoveOutput, ProcessContext> move() {
        return ProcessDefinition.define(MOVE, 1, MoveInput.class, MoveOutput.class, ProcessContext.class, pb -> pb
            .description("Moves a publication after an attempt completed: ends it or marks it underfunded.")
            .permissions(QbPermissions.PUBLICATION_SYSTEM)
            .subProcessOnly()
            .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
            .outputMapper(ctx -> new MoveOutput(ctx.get(PUB_ID, UUID.class), ctx.get(MOVED, String.class)))
            .step("Lock the publication", HoldLock.<ProcessContext>exclusive(ctx -> lockName(ctx.get(PUB_ID))))
            .steps(b -> load(b, ctx -> ctx.get(PUB_ID)))
            .compute("Move the publication", (metadata, ctx) -> {
                MoveInput input = ctx.get(PublicationEditing.INPUT, MoveInput.class);
                EntityInstance publication = publication(ctx);
                if (publication == null) {
                    throw new IllegalStateException("Publication " + input.publicationId() + " to move is missing");
                }
                String expected = moveAfter(publication, input.winners(), input.spent(), input.pending());
                if (!input.status().equals(expected)) {
                    throw new IllegalStateException("Publication " + input.publicationId() + " would move to "
                        + expected + ", its card to " + input.status());
                }
                ctx.put(MOVED, afterCompletion(ctx, input.winners(), input.spent(), input.pending()));
            }));
    }

    private static final String MOVED = "moved";

    private PublicationMoves() {}
}

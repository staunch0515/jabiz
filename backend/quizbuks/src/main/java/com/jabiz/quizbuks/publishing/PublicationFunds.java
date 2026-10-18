package com.jabiz.quizbuks.publishing;

import com.jabiz.event.DomainEvent;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.quizbuks.publishing.PublicationEditing.FOUND;
import static com.jabiz.quizbuks.publishing.PublicationEditing.OUTPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.PUB_ID;
import static com.jabiz.quizbuks.publishing.PublicationEditing.first;
import static com.jabiz.quizbuks.publishing.PublicationEditing.instant;
import static com.jabiz.quizbuks.publishing.PublicationEditing.list;
import static com.jabiz.quizbuks.publishing.PublicationEditing.uuid;

/**
 * Underfunded publications back on (docs/quizbuks/plans/Q5-answering.md, D-Q5-5; design section 5.1): once a sponsor's
 * pending rewards are all paid, {@code QB_PENDING_SETTLE} publishes {@code qb.sponsor.funds-restored};
 * {@code QB_PUBLICATION_FUNDS_RESTORED} (subscribed to it, as the system) finds the sponsor's underfunded publications
 * through their cards and runs {@code QB_PUBLICATION_FUNDED} for each, by key, each under its own lock: live again
 * within its time, ended after its end. It never takes the wallet's lock (publication before wallet, D-Q5-7): it reads
 * the wallet without it and leaves a publication as it is while rewards are pending again.
 */
public final class PublicationFunds {

    public static final String FUNDS_RESTORED = "QB_PUBLICATION_FUNDS_RESTORED";
    public static final String FUNDED = "QB_PUBLICATION_FUNDED";

    /** The consumer of {@code qb.sponsor.funds-restored}. */
    public static final String SUBSCRIPTION = "qb.funds-restored";

    /** Publications of one sponsor one run puts back on; a sponsor has far fewer at a time. */
    public static final int BATCH = 1_000;

    /** @param publications the underfunded publications found (each was looked at) */
    public record RestoredOutput(String sponsorId, int publications) {}

    static final String SHORT = "short";
    static final String WALLET = "fundsWallet";

    public static ProcessDefinition<QbWallets.SponsorRef, RestoredOutput, ProcessContext> fundsRestored() {
        return ProcessDefinition.define(FUNDS_RESTORED, 1, QbWallets.SponsorRef.class, RestoredOutput.class,
            ProcessContext.class, pb -> pb
                .description("Puts a sponsor's underfunded publications back on once no reward is pending.")
                .permissions(QbPermissions.PUBLICATION_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(PublicationEditing.INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new RestoredOutput(sponsorId(ctx), list(ctx, SHORT).size()))
                .step("Find the underfunded publications", QueryEntities.<ProcessContext>of(
                    QbPublications.defaultDataset(QbPublications.CARD), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("sponsorId", sponsorId(ctx)),
                            new QueryPredicate.Eq("status", PublicationStatus.FUNDS_SHORT))))
                        .orderBy("publicationId", true).limit(BATCH).build(), SHORT))
                // By key, as the minute's tick takes them: the same order everywhere.
                .step("Put each back on", CallProcess.<ProcessContext>forEach(FUNDED, 1, ctx -> list(ctx, SHORT)
                    .stream().map(row -> new PublicationLifecycle.PublicationRef(uuid(row.id()))).toList(), null)));
    }

    public static ProcessDefinition<PublicationLifecycle.PublicationRef, PublicationOutput, ProcessContext> funded() {
        return ProcessDefinition.define(FUNDED, 1, PublicationLifecycle.PublicationRef.class, PublicationOutput.class,
            ProcessContext.class, pb -> pb
                .description("Puts one underfunded publication back on when its sponsor has no pending reward.")
                .permissions(QbPermissions.PUBLICATION_SYSTEM)
                .internal()
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, PublicationOutput.class)
                    : new PublicationOutput(ctx.get(PUB_ID, UUID.class), null, 0, null, null))
                .step("Lock the publication", HoldLock.<ProcessContext>exclusive(
                    ctx -> PublicationEditing.lockName(ctx.get(PUB_ID))))
                // It changes the state of a publication that went on: the start lock too (Q10a4, LS-4). Each
                // publication's pair in turn, by key (the run goes through them in order).
                .steps(PublicationEditing::lockStarts)
                .step("Load the publication", QueryEntities.<ProcessContext>of(
                    QbPublications.defaultDataset(QbPublications.PUBLICATION), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("publicationId", PublicationEditing.ids(ctx.get(PUB_ID))))
                        .limit(1).build(), FOUND))
                .steps(PublicationEditing::loadSchedule)
                // Read without the wallet's lock: a reward that becomes pending meanwhile makes it short again.
                .step("Read the sponsor's wallet", QueryEntities.<ProcessContext>of(
                    QbWallets.defaultDataset(QbWallets.WALLET), ctx -> {
                        EntityInstance found = first(ctx, FOUND);
                        return QbWallets.query(QbWallets.SPONSOR, found == null ? null : found.get("sponsorId"));
                    }, WALLET))
                .compute("Put it back on", (metadata, ctx) -> funded(ctx))
                .steps(PublicationNotices::announce));
    }

    static void funded(ProcessContext ctx) {
        EntityInstance publication = first(ctx, FOUND);
        if (publication == null) {
            return;
        }
        if (!PublicationStatus.FUNDS_SHORT.equals(publication.get("status"))
            || QbWallets.pendingCount(first(ctx, WALLET)) > 0) {
            PublicationEditing.unchanged(ctx, publication);
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        if (ctx.opTime().isBefore(instant(publication.get("endAt")))) {
            values.put("status", PublicationStatus.LIVE);
        } else {
            values.put("status", PublicationStatus.ENDED);
            values.put("endedAt", ctx.opTime());
            values.put("endReason", PublicationStatus.EXPIRED);
        }
        PublicationEditing.write(ctx, publication, values);
    }

    /** The input of {@code QB_PUBLICATION_FUNDS_RESTORED} from the event. */
    static QbWallets.SponsorRef input(DomainEvent event) {
        Object sponsorId = event.payload().get("sponsorId");
        return new QbWallets.SponsorRef(sponsorId == null ? null : String.valueOf(sponsorId));
    }

    private static String sponsorId(ProcessContext ctx) {
        return ctx.get(PublicationEditing.INPUT, QbWallets.SponsorRef.class).sponsorId();
    }

    private PublicationFunds() {}
}

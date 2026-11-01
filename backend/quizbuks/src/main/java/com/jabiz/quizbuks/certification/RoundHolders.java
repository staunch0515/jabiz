package com.jabiz.quizbuks.certification;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.certification.Certifications.Certification;
import com.jabiz.quizbuks.play.QbPlay;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Who holds a certification does not answer its rounds (Q70a, D-Q70-1, option B): a publication that is a round of a
 * certification cannot be started by someone holding that certification now; once it ends or is revoked they can.
 * An ordinary publication is not touched: the round is looked up in the start's one statement before the lock, and the
 * rest is read for a round only (an empty list reads nothing), so the start's statement budget stays.
 */
public final class RoundHolders {

    /** Where {@link #round} puts the publication's round (none for an ordinary publication). */
    public static final String ROUND = "certificationRound";
    static final String RECORDS = "certificationRecords";
    static final String ROUNDS = "certificationRounds";
    static final String ATTEMPTS = "certificationAttempts";

    /** The round {@code publicationId} is: a query for the start's statement before the lock. */
    public static <C extends ProcessContext> QueryEntities.Metadata<C> round(Function<C, Object> publicationId) {
        return QueryEntities.query(QbCertifications.defaultDataset(QbCertifications.ROUND), ctx -> {
            Object id = publicationId.apply(ctx);
            return EntityQuery.builder().where(new QueryPredicate.In("publicationId",
                id == null ? List.of() : List.of(String.valueOf(id)))).limit(1).build();
        }, ROUND);
    }

    /**
     * For a new start of a round only: the caller's records of its certification and its rounds (one statement), then
     * the caller's attempts on those rounds, whose passes may not be recorded yet (records are written lazily, by
     * {@link CertificationProcesses#CLAIM}).
     */
    public static <I, O> void load(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Boolean> starting) {
        pb.step("Load the caller's records and the rounds of the round's certification", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbCertifications.defaultDataset(QbCertifications.RECORD), ctx -> {
                    Certification certification = certification(ctx, starting);
                    return certification == null ? nothing("userId")
                        : CertificationProcesses.records(ctx.request().actorId(), certification.key());
                }, RECORDS),
                QueryEntities.query(QbCertifications.defaultDataset(QbCertifications.ROUND), ctx -> {
                    Certification certification = certification(ctx, starting);
                    return certification == null ? nothing("publicationId")
                        : CertificationProcesses.rounds(certification);
                }, ROUNDS)))
            .step("Load the caller's attempts on those rounds", QueryEntities.<ProcessContext>of(
                QbPublications.defaultDataset(QbPlay.ATTEMPT), ctx -> {
                    List<Object> publications = new ArrayList<>();
                    if (certification(ctx, starting) != null) {
                        for (EntityInstance r : CertificationProcesses.list(ctx, ROUNDS)) {
                            publications.add(UUID.fromString(String.valueOf((Object) r.get("publicationId"))));
                        }
                    }
                    if (publications.isEmpty()) {
                        return nothing("publicationId");
                    }
                    return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("userId", ctx.request().actorId()),
                        new QueryPredicate.In("publicationId", publications))))
                        .limit(CertificationProcesses.MAX_ROWS).build();
                }, ATTEMPTS));
    }

    /** Until when the caller holds the certification of the publication's round; empty when not, or not a round. */
    public static Optional<Instant> heldUntil(ProcessContext ctx) {
        Certification certification = certification(ctx, c -> true);
        if (certification == null) {
            return Optional.empty();
        }
        List<EntityInstance> records = CertificationProcesses.list(ctx, RECORDS);
        List<CertificationHolding.Entry> entries = new ArrayList<>(records.stream()
            .map(CertificationProcesses::entry).toList());
        for (Map<String, Object> pass : CertificationProcesses.unrecordedPasses(certification,
            CertificationProcesses.list(ctx, ROUNDS), records, CertificationProcesses.list(ctx, ATTEMPTS),
            ctx.request().actorId())) {
            entries.add(CertificationProcesses.passEntry(pass));
        }
        return CertificationHolding.heldUntil(entries, certification, ctx.opTime());
    }

    /** Refuses the start (422 {@value CertificationCodes#HELD}) when the caller holds the round's certification. */
    public static void refuse(ProcessContext ctx, String field, Object publicationId) {
        heldUntil(ctx).ifPresent(until -> ctx.reject(new Violation(field, CertificationCodes.HELD,
            "The caller holds the certification of this round", Map.of("publicationId", String.valueOf(publicationId),
                "heldUntil", until.toString()))));
    }

    /** The certification of the publication's round, when there is one and {@code starting} holds. */
    private static Certification certification(ProcessContext ctx, Function<ProcessContext, Boolean> starting) {
        if (!Boolean.TRUE.equals(starting.apply(ctx))) {
            return null;
        }
        List<EntityInstance> round = CertificationProcesses.list(ctx, ROUND);
        if (round.isEmpty()) {
            return null;
        }
        // A key no longer in the catalog holds nothing: its round is an ordinary publication now.
        return Certifications.of(String.valueOf((Object) round.get(0).get("certification"))).orElse(null);
    }

    private static EntityQuery nothing(String field) {
        return EntityQuery.builder().where(new QueryPredicate.In(field, List.of())).limit(1).build();
    }

    private RoundHolders() {}
}

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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
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
        loadHolding(pb, ctx -> certification(ctx, starting));
    }

    /**
     * What tells whether the caller holds {@code certification} (none: nothing is read, no statement): their records
     * of it and its rounds (one statement), then their attempts on those rounds, whose passes may not be recorded yet.
     * {@link #heldUntil(ProcessContext, Certification)} answers from them. Used by the start of a round and, Q70b, by
     * the referral link ({@code REFERRER}).
     */
    public static <I, O> void loadHolding(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Certification> certification) {
        pb.step("Load the caller's records and the rounds of the certification", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbCertifications.defaultDataset(QbCertifications.RECORD), ctx -> {
                    Certification c = certification.apply(ctx);
                    return c == null ? nothing("userId")
                        : CertificationProcesses.records(ctx.request().actorId(), c.key());
                }, RECORDS),
                QueryEntities.query(QbCertifications.defaultDataset(QbCertifications.ROUND), ctx -> {
                    Certification c = certification.apply(ctx);
                    return c == null ? nothing("publicationId") : CertificationProcesses.rounds(c);
                }, ROUNDS)))
            .step("Load the caller's attempts on those rounds", QueryEntities.<ProcessContext>of(
                QbPublications.defaultDataset(QbPlay.ATTEMPT), ctx -> {
                    List<Object> publications = new ArrayList<>();
                    if (certification.apply(ctx) != null) {
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

    /**
     * Q86 (D-Q86-5, D-Q86-6): what tells which of {@code certifications} the caller holds, under keys of their own
     * ({@code prefix}, so that a round's own load is untouched): their records of them and the rounds of them (one
     * statement; only rounds open or closed within the longest validity asked, the others cannot confer a holding
     * now), then their attempts on those rounds (one more). None asked: nothing is read, no statement.
     */
    public static <I, O> void loadHoldings(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, List<Certification>> certifications, String prefix) {
        pb.step("Load the caller's records and the rounds of the certifications required", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbCertifications.defaultDataset(QbCertifications.RECORD), ctx -> {
                    List<Object> keys = keys(certifications.apply(ctx));
                    return keys.isEmpty() ? nothing("userId") : EntityQuery.builder().where(new QueryPredicate.And(
                        List.of(new QueryPredicate.Eq("userId", ctx.request().actorId()),
                            new QueryPredicate.In("certification", keys)))).limit(CertificationProcesses.MAX_ROWS).build();
                }, prefix + RECORDS),
                QueryEntities.query(QbCertifications.defaultDataset(QbCertifications.ROUND), ctx -> {
                    List<Certification> asked = certifications.apply(ctx);
                    List<Object> keys = keys(asked);
                    if (keys.isEmpty()) {
                        return nothing("publicationId");
                    }
                    Duration longest = asked.stream().map(Certification::validity).max(Duration::compareTo).orElseThrow();
                    return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("certification", keys),
                            new QueryPredicate.Or(List.of(new QueryPredicate.IsNull("closedAt"),
                                new QueryPredicate.Gt("closedAt", ctx.opTime().minus(longest)))))))
                        .limit(CertificationProcesses.MAX_ROWS).build();
                }, prefix + ROUNDS)))
            .step("Load the caller's attempts on the rounds of the certifications required", QueryEntities.<ProcessContext>of(
                QbPublications.defaultDataset(QbPlay.ATTEMPT), ctx -> {
                    List<Object> publications = new ArrayList<>();
                    for (EntityInstance r : CertificationProcesses.list(ctx, prefix + ROUNDS)) {
                        publications.add(UUID.fromString(String.valueOf((Object) r.get("publicationId"))));
                    }
                    if (publications.isEmpty()) {
                        return nothing("publicationId");
                    }
                    return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("userId", ctx.request().actorId()),
                        new QueryPredicate.In("publicationId", publications))))
                        .limit(CertificationProcesses.MAX_ROWS).build();
                }, prefix + ATTEMPTS));
    }

    /** The keys of those of {@code certifications} the caller holds now, from what {@link #loadHoldings} read. */
    public static Set<String> heldKeys(ProcessContext ctx, List<Certification> certifications, String prefix) {
        Set<String> held = new HashSet<>();
        List<EntityInstance> records = CertificationProcesses.list(ctx, prefix + RECORDS);
        List<EntityInstance> rounds = CertificationProcesses.list(ctx, prefix + ROUNDS);
        List<EntityInstance> attempts = CertificationProcesses.list(ctx, prefix + ATTEMPTS);
        for (Certification certification : certifications) {
            List<EntityInstance> mine = records.stream()
                .filter(r -> certification.key().equals(r.get("certification"))).toList();
            List<CertificationHolding.Entry> entries = new ArrayList<>(mine.stream()
                .map(CertificationProcesses::entry).toList());
            List<EntityInstance> its = rounds.stream()
                .filter(r -> certification.key().equals(r.get("certification"))).toList();
            for (Map<String, Object> pass : CertificationProcesses.unrecordedPasses(certification, its, mine, attempts,
                ctx.request().actorId())) {
                entries.add(CertificationProcesses.passEntry(pass));
            }
            if (CertificationHolding.heldUntil(entries, certification, ctx.opTime()).isPresent()) {
                held.add(certification.key());
            }
        }
        return held;
    }

    private static List<Object> keys(List<Certification> certifications) {
        return certifications == null ? List.of() : certifications.stream().map(c -> (Object) c.key()).toList();
    }

    /** Until when the caller holds the certification of the publication's round; empty when not, or not a round. */
    public static Optional<Instant> heldUntil(ProcessContext ctx) {
        return heldUntil(ctx, certification(ctx, c -> true));
    }

    /** Until when the caller holds {@code certification}, from what {@link #loadHolding} read; empty when not. */
    public static Optional<Instant> heldUntil(ProcessContext ctx, Certification certification) {
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

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
    /**
     * Q88a (D-Q88-2): the sponsors' own certifications a key names ({@code sp:<id>}), read in the same statement as
     * the records and the rounds (none for the catalog's keys): their validity and version make the
     * {@link Certification} the holding rule needs.
     */
    static final String SPONSOR_ROWS = "certificationSponsorRows";

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
        loadHoldingOf(pb, ctx -> key(ctx, starting));
    }

    /**
     * What tells whether the caller holds {@code certification} (none: nothing is read, no statement): their records
     * of it and its rounds (one statement), then their attempts on those rounds, whose passes may not be recorded yet.
     * {@link #heldUntil(ProcessContext, Certification)} answers from them. Used by the start of a round and, Q70b, by
     * the referral link ({@code REFERRER}).
     */
    public static <I, O> void loadHolding(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Certification> certification) {
        loadHoldingOf(pb, ctx -> {
            Certification c = certification.apply(ctx);
            return c == null ? null : c.key();
        });
    }

    /**
     * As {@link #loadHolding}, by the certification's key (the catalog's, or a sponsor's {@code sp:<id>}, Q88a): the
     * sponsor's row is read in the same statement, so the certification is known only after the read
     * ({@link #resolve}); the statement budget is the same.
     */
    public static <I, O> void loadHoldingOf(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, String> key) {
        pb.step("Load the caller's records and the rounds of the certification", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbCertifications.defaultDataset(QbCertifications.RECORD), ctx -> {
                    String k = key.apply(ctx);
                    return k == null ? nothing("userId") : CertificationProcesses.records(ctx.request().actorId(), k);
                }, RECORDS),
                QueryEntities.query(QbCertifications.defaultDataset(QbCertifications.ROUND), ctx -> {
                    String k = key.apply(ctx);
                    return k == null ? nothing("publicationId") : CertificationProcesses.roundsOf(k);
                }, ROUNDS),
                QueryEntities.query(QbSponsorCertifications.DEFAULT_DATASET, ctx -> {
                    String k = key.apply(ctx);
                    return sponsorRows(k == null ? List.of() : List.of(k));
                }, SPONSOR_ROWS)))
            .step("Load the caller's attempts on those rounds", QueryEntities.<ProcessContext>of(
                QbPublications.defaultDataset(QbPlay.ATTEMPT), ctx -> {
                    List<Object> publications = new ArrayList<>();
                    if (key.apply(ctx) != null) {
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
        loadHoldingsOf(pb, ctx -> {
            List<Certification> asked = certifications.apply(ctx);
            return asked == null ? List.of() : asked.stream().map(Certification::key).toList();
        }, prefix);
    }

    /**
     * As {@link #loadHoldings}, by the certifications' keys (Q88a: a sponsor's {@code sp:<id>} among them, whose row
     * is read in the same statement); {@link #heldKeysOf} answers from them.
     */
    public static <I, O> void loadHoldingsOf(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, List<String>> certifications, String prefix) {
        pb.step("Load the caller's records and the rounds of the certifications required", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbCertifications.defaultDataset(QbCertifications.RECORD), ctx -> {
                    List<Object> keys = keys(certifications.apply(ctx));
                    return keys.isEmpty() ? nothing("userId") : EntityQuery.builder().where(new QueryPredicate.And(
                        List.of(new QueryPredicate.Eq("userId", ctx.request().actorId()),
                            new QueryPredicate.In("certification", keys)))).limit(CertificationProcesses.MAX_ROWS).build();
                }, prefix + RECORDS),
                QueryEntities.query(QbCertifications.defaultDataset(QbCertifications.ROUND), ctx -> {
                    List<String> asked = certifications.apply(ctx);
                    List<Object> keys = keys(asked);
                    if (keys.isEmpty()) {
                        return nothing("publicationId");
                    }
                    // Only rounds open or closed within the longest validity asked can confer a holding now; a
                    // sponsor's validity is not known before the read, so its longest possible is taken.
                    Duration longest = asked.stream().map(k -> SponsorCertifications.isSponsor(k)
                            ? Duration.ofDays(SponsorCertifications.MAX_VALIDITY_DAYS)
                            : Certifications.of(k).map(Certification::validity).orElse(Duration.ZERO))
                        .max(Duration::compareTo).orElseThrow();
                    return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("certification", keys),
                            new QueryPredicate.Or(List.of(new QueryPredicate.IsNull("closedAt"),
                                new QueryPredicate.Gt("closedAt", ctx.opTime().minus(longest)))))))
                        .limit(CertificationProcesses.MAX_ROWS).build();
                }, prefix + ROUNDS),
                QueryEntities.query(QbSponsorCertifications.DEFAULT_DATASET,
                    ctx -> sponsorRows(certifications.apply(ctx)), prefix + SPONSOR_ROWS)))
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
        return heldKeysOf(ctx, certifications.stream().map(Certification::key).toList(), prefix);
    }

    /** The keys of those of {@code keys} the caller holds now, from what {@link #loadHoldingsOf} read. */
    public static Set<String> heldKeysOf(ProcessContext ctx, List<String> keys, String prefix) {
        Set<String> held = new HashSet<>();
        List<EntityInstance> records = CertificationProcesses.list(ctx, prefix + RECORDS);
        List<EntityInstance> rounds = CertificationProcesses.list(ctx, prefix + ROUNDS);
        List<EntityInstance> attempts = CertificationProcesses.list(ctx, prefix + ATTEMPTS);
        List<EntityInstance> sponsorRows = CertificationProcesses.list(ctx, prefix + SPONSOR_ROWS);
        for (String key : keys) {
            // A key nobody defines (gone from the catalog, or a sponsor's row missing) is held by nobody.
            Certification certification = SponsorCertifications.resolve(key, sponsorRows).orElse(null);
            if (certification == null) {
                continue;
            }
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

    private static List<Object> keys(List<String> keys) {
        return keys == null ? List.of() : keys.stream().filter(k -> k != null).map(k -> (Object) k).toList();
    }

    /** The sponsors' rows the keys name (none for the catalog's: the query then finds nothing). */
    static EntityQuery sponsorRows(List<String> keys) {
        List<Object> ids = new ArrayList<>();
        if (keys != null) {
            for (String key : keys) {
                String id = SponsorCertifications.idOf(key);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids.isEmpty() ? nothing("certificationId") : EntityQuery.builder()
            .where(new QueryPredicate.In("certificationId", ids)).limit(ids.size()).build();
    }

    /** The certification {@code key} names, from the catalog or the sponsors' rows {@link #loadHoldingOf} read. */
    public static Optional<Certification> resolve(ProcessContext ctx, String key) {
        return key == null ? Optional.empty()
            : SponsorCertifications.resolve(key, CertificationProcesses.list(ctx, SPONSOR_ROWS));
    }

    /** Until when the caller holds the certification of the publication's round; empty when not, or not a round. */
    public static Optional<Instant> heldUntil(ProcessContext ctx) {
        return heldUntil(ctx, resolve(ctx, key(ctx, c -> true)).orElse(null));
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

    /** The key of the publication's round's certification, when there is one and {@code starting} holds. */
    private static String key(ProcessContext ctx, Function<ProcessContext, Boolean> starting) {
        if (!Boolean.TRUE.equals(starting.apply(ctx))) {
            return null;
        }
        List<EntityInstance> round = CertificationProcesses.list(ctx, ROUND);
        // A key nobody defines any more holds nothing (resolve): its round is an ordinary publication now.
        return round.isEmpty() ? null : String.valueOf((Object) round.get(0).get("certification"));
    }

    private static EntityQuery nothing(String field) {
        return EntityQuery.builder().where(new QueryPredicate.In(field, List.of())).limit(1).build();
    }

    private RoundHolders() {}
}

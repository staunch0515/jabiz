package com.jabiz.quizbuks.certification;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.quizbuks.certification.Certifications.Certification;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.play.QbPlay;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A taker's certifications and certificates (Q77, docs/quizbuks/plans/Q77-certificates.md): {@link #MINE} says which
 * of the catalog's certifications the caller holds now, from when and until when, by what and with which certificate;
 * {@link #ISSUE} issued the certificate of one holding as a platform document ({@value QbCertificates#LAYOUT_ID});
 * F-S2c (D8) pauses it until certificates of nodes (F-S5), those issued staying verifiable and downloadable. Holding is {@link CertificationHolding}'s, over the caller's records and
 * their passes not recorded yet, as the gates read it.
 */
public final class CertificateProcesses {

    public static final String MINE = "QB_MY_CERTIFICATIONS";
    public static final String ISSUE = "QB_CERTIFICATE_ISSUE";

    public static final String NOT_HELD = "QB_CERTIFICATE_NOT_HELD";
    /** F-S2c (D8): issuing is paused until certificates of nodes (F-S5). */
    public static final String PAUSED = "QB_CERTIFICATE_PAUSED";

    public record MineInput() {}

    /**
     * One certification held now.
     *
     * @param source        {@code PASSED} (a round passed) or {@code GRANTED} (by hand)
     * @param certificateId the certificate of this holding, once issued; its PDF is the document whose subject it is
     * @param name          Q88a: a sponsor's certification's name by language (null for the catalog's, whose names
     *                      are the App's texts)
     * @param sponsorName   Q88a: the sponsor whose certification it is (null for the catalog's)
     */
    public record Held(String certification, String source, Instant heldFrom, Instant heldUntil,
        String certificateId, String verifyKey, Map<String, String> name, String sponsorName) {
        public Held(String certification, String source, Instant heldFrom, Instant heldUntil, String certificateId,
            String verifyKey) {
            this(certification, source, heldFrom, heldUntil, certificateId, verifyKey, null, null);
        }
    }

    public record MineOutput(List<Held> held) {}

    /** @param language the certificate's language ({@code en}, {@code zh}, {@code ja}); used the first time only */
    public record IssueInput(@NotBlank @Size(max = 40) String certification,
        @NotBlank @Pattern(regexp = "en|zh|ja") String language) {}

    /** @param issued whether this call issued it (false: issued before) */
    public record IssueOutput(String certificateId, String verifyKey, Instant heldUntil, boolean issued) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String RECORDS = "certRecords";
    static final String ROUNDS = "certRounds";
    static final String CERTIFICATES = "certificates";
    /** Q88a: the caller's passed attempts (the rounds are read for their publications), the sponsors' rows, the sponsors. */
    static final String PASSES = "certPasses";
    static final String SPONSOR_ROWS = "certSponsorRows";
    static final String SPONSORS = "certSponsors";

    public static ProcessDefinition<MineInput, MineOutput, ProcessContext> mine() {
        return ProcessDefinition.define(MINE, 1, MineInput.class, MineOutput.class, ProcessContext.class, pb -> {
            pb.description("Lists the certifications the caller holds now, with their certificates.")
                .permissions(QbPermissions.ME)
                .contextFactory((start, input) -> start(start, input))
                .outputMapper(ctx -> ctx.get(OUTPUT, MineOutput.class));
            loadHoldings(pb);
            pb.compute("Find the holdings", (metadata, ctx) -> ctx.put(OUTPUT, new MineOutput(held(ctx))));
        });
    }

    /** F-S2c (D8): kept so that the App's older calls get the reason; it issues nothing until F-S5. */
    public static ProcessDefinition<IssueInput, IssueOutput, ProcessContext> issue() {
        return ProcessDefinition.define(ISSUE, 1, IssueInput.class, IssueOutput.class, ProcessContext.class, pb -> {
            pb.description("Paused (F-S2c): refuses with QB_CERTIFICATE_PAUSED; certificates of nodes come with F-S5.")
                .permissions(QbPermissions.ME)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> start(start, input))
                .outputMapper(ctx -> ctx.get(OUTPUT, IssueOutput.class))
                // F-S2c (D8, the requester's answer of 2026-11-08): no certificate is issued on a certification any
                // more; certificates of nodes come with F-S5. Those issued stay verifiable and downloadable.
                .compute("Refuse: issuing is paused", (metadata, ctx) -> {
                    throw new BusinessRuleViolationException(new Violation("certification", PAUSED,
                        "Certificates are not issued until the knowledge map's certificates come", Map.of()));
                });
        });
    }

    /**
     * What tells which certifications the caller holds (four statements, not on the answering path): their records,
     * certificates and passed attempts; the rounds of the publications they passed (a pass counts only on a round,
     * whichever certification's: the catalog's or, Q88a, a sponsor's); the sponsors' certifications those name and
     * the sponsors' names.
     */
    static <I, O> void loadHoldings(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the caller's records, certificates and passes", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbCertifications.defaultDataset(QbCertifications.RECORD), ctx ->
                    EntityQuery.builder().where(new QueryPredicate.Eq("userId", ctx.request().actorId()))
                        .limit(CertificationProcesses.MAX_ROWS).build(), RECORDS),
                QueryEntities.query(QbCertificates.MY_DATASET, ctx -> EntityQuery.builder()
                    .limit(CertificationProcesses.MAX_ROWS).build(), CERTIFICATES),
                QueryEntities.query(QbPublications.defaultDataset(QbPlay.ATTEMPT), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("userId", ctx.request().actorId()),
                        new QueryPredicate.Eq("passed", true)))).limit(CertificationProcesses.MAX_ROWS).build(),
                    PASSES)))
            .step("Load the rounds of the publications passed", QueryEntities.<ProcessContext>of(
                QbCertifications.defaultDataset(QbCertifications.ROUND), ctx -> {
                    List<Object> publications = new ArrayList<>();
                    CertificationProcesses.list(ctx, PASSES).forEach(a ->
                        publications.add(String.valueOf((Object) a.get("publicationId"))));
                    return EntityQuery.builder().where(new QueryPredicate.In("publicationId", publications))
                        .limit(CertificationProcesses.MAX_ROWS).build();
                }, ROUNDS))
            .step("Load the sponsors' certifications named", QueryEntities.<ProcessContext>of(
                QbSponsorCertifications.DEFAULT_DATASET, ctx -> RoundHolders.sponsorRows(sponsorKeys(ctx)),
                SPONSOR_ROWS))
            .step("Load the sponsors named", QueryEntities.<ProcessContext>of(
                QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> {
                    List<Object> sponsors = CertificationProcesses.list(ctx, SPONSOR_ROWS).stream()
                        .map(r -> (Object) String.valueOf((Object) r.get("sponsorId"))).distinct().toList();
                    return EntityQuery.builder().where(new QueryPredicate.In("userId", sponsors))
                        .limit(CertificationProcesses.MAX_ROWS).build();
                }, SPONSORS));
    }

    /** The keys of the sponsors' certifications among the caller's records and the rounds they passed. */
    private static List<String> sponsorKeys(ProcessContext ctx) {
        List<String> keys = new ArrayList<>();
        for (String key : List.of(RECORDS, ROUNDS)) {
            for (EntityInstance row : CertificationProcesses.list(ctx, key)) {
                String certification = String.valueOf((Object) row.get("certification"));
                if (SponsorCertifications.isSponsor(certification) && !keys.contains(certification)) {
                    keys.add(certification);
                }
            }
        }
        return keys;
    }

    /** The certifications the caller may hold: the catalog's in its order, then (Q88a) the sponsors' named, by key. */
    static List<Certification> candidates(ProcessContext ctx) {
        List<Certification> all = new ArrayList<>(Certifications.ALL);
        CertificationProcesses.list(ctx, SPONSOR_ROWS).stream().map(SponsorCertifications::of)
            .sorted(java.util.Comparator.comparing(Certification::key)).forEach(all::add);
        return all;
    }

    /** The certification {@code key} names, from the catalog or the sponsors' rows read. */
    static Optional<Certification> resolve(ProcessContext ctx, String key) {
        return SponsorCertifications.resolve(key, CertificationProcesses.list(ctx, SPONSOR_ROWS));
    }

    /** Each certification the caller holds now, the catalog's in its order, then the sponsors'. */
    static List<Held> held(ProcessContext ctx) {
        List<Held> held = new ArrayList<>();
        for (Certification certification : candidates(ctx)) {
            holding(ctx, certification).ifPresent(h -> {
                EntityInstance certificate = certificateOf(ctx, certification, h.until());
                EntityInstance row = sponsorRow(ctx, certification.key());
                held.add(new Held(certification.key(), h.kind(), h.from(), h.until(),
                    certificate == null ? null : String.valueOf(certificate.id()),
                    certificate == null ? null : certificate.get("verifyKey"),
                    row == null ? null : SponsorCertifications.texts(row.get("name")),
                    row == null ? null : sponsorName(ctx, row)));
            });
        }
        return List.copyOf(held);
    }

    private static EntityInstance sponsorRow(ProcessContext ctx, String key) {
        return CertificationProcesses.list(ctx, SPONSOR_ROWS).stream()
            .filter(r -> key.equals(SponsorCertifications.keyOf(r.id()))).findFirst().orElse(null);
    }

    /** The company name of the sponsor whose certification {@code row} is; null when the onboarding is gone. */
    private static String sponsorName(ProcessContext ctx, EntityInstance row) {
        return CertificationProcesses.list(ctx, SPONSORS).stream()
            .filter(s -> String.valueOf((Object) s.get("userId")).equals(String.valueOf((Object) row.get("sponsorId"))))
            .map(s -> (String) s.get("company")).findFirst().orElse(null);
    }

    /** The caller's holding of {@code certification} now, from what {@link #loadHoldings} read. */
    static Optional<CertificationHolding.Holding> holding(ProcessContext ctx, Certification certification) {
        List<EntityInstance> records = CertificationProcesses.list(ctx, RECORDS).stream()
            .filter(r -> certification.key().equals(r.get("certification"))).toList();
        List<EntityInstance> rounds = CertificationProcesses.list(ctx, ROUNDS).stream()
            .filter(r -> certification.key().equals(r.get("certification"))).toList();
        List<CertificationHolding.Entry> entries = new ArrayList<>(records.stream()
            .map(CertificationProcesses::entry).toList());
        for (Map<String, Object> pass : CertificationProcesses.unrecordedPasses(certification, rounds, records,
            CertificationProcesses.list(ctx, PASSES), ctx.request().actorId())) {
            entries.add(CertificationProcesses.passEntry(pass));
        }
        return CertificationHolding.holding(entries, certification, ctx.opTime());
    }

    private static EntityInstance certificateOf(ProcessContext ctx, Certification certification, Instant until) {
        return CertificationProcesses.list(ctx, CERTIFICATES).stream()
            .filter(c -> certification.key().equals(c.get("certification"))
                && until.equals(CertificationProcesses.instant(c.get("validUntil"))))
            .findFirst().orElse(null);
    }


    private static ProcessContext start(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private CertificateProcesses() {}
}

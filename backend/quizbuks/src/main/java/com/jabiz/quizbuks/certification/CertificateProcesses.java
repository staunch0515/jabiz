package com.jabiz.quizbuks.certification;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.certification.Certifications.Certification;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.play.QbPlay;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.document.DocumentProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.runtime.security.RandomBytesStep;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A taker's certifications and certificates (Q77, docs/quizbuks/plans/Q77-certificates.md): {@link #MINE} says which
 * of the catalog's certifications the caller holds now, from when and until when, by what and with which certificate;
 * {@link #ISSUE} issues the certificate of one holding as a platform document ({@value QbCertificates#LAYOUT_ID}),
 * once: asking again gives the one issued. Holding is {@link CertificationHolding}'s, over the caller's records and
 * their passes not recorded yet, as the gates read it.
 */
public final class CertificateProcesses {

    public static final String MINE = "QB_MY_CERTIFICATIONS";
    public static final String ISSUE = "QB_CERTIFICATE_ISSUE";

    public static final String NOT_HELD = "QB_CERTIFICATE_NOT_HELD";

    /** The certification's name on a certificate: {@code qb.certification.<key>} in the certificate's language. */
    static final String NAME_KEY = "qb.certification.";

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
    static final String PROFILE = "profile";
    static final String RANDOM = "random";
    static final String NEW_ID = "newCertificateId";
    static final String ISSUED = "issued";

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

    /**
     * @param siteUrl the site's address ({@code jabiz.mail.base-url}); the certificate's verify address is
     *                {@code <siteUrl>/taker/verify/<key>}, a path alone without one (development and tests)
     */
    public static ProcessDefinition<IssueInput, IssueOutput, ProcessContext> issue(MessageCatalog messages,
        String siteUrl) {
        return ProcessDefinition.define(ISSUE, 1, IssueInput.class, IssueOutput.class, ProcessContext.class, pb -> {
            pb.description("Issues the certificate of a certification the caller holds, or gives the one issued.")
                .permissions(QbPermissions.ME)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> start(start, input))
                .outputMapper(ctx -> ctx.get(OUTPUT, IssueOutput.class))
                .step("Draw the verify key", RandomBytesStep.class,
                    new RandomBytesStep.Metadata(RANDOM, QbCertificates.KEY_BYTES))
                // The caller's certifications are written one process at a time (claims, grants and certificates).
                .step("Lock the caller's certifications", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbCertifications.lockName(ctx.request().actorId())));
            loadHoldings(pb);
            pb.step("Load the caller's name", QueryEntities.<ProcessContext>of(QbAccounts.MY_PROFILE_DATASET,
                    ctx -> EntityQuery.builder().limit(1).build(), PROFILE))
                .compute("Check the holding", (metadata, ctx) -> prepare(ctx))
                .step("Write the certificate", SaveChanges.now())
                // At the issue time: the certificate shows the holding as it is now.
                .step("Issue the document", CallProcess.<ProcessContext>when(ctx -> ctx.contains(NEW_ID),
                    DocumentProcesses.ISSUE, 1, ctx -> new DocumentProcesses.IssueInput(QbCertificates.LAYOUT_ID,
                        documentParams(messages, siteUrl, ctx), null, null,
                        ctx.get(INPUT, IssueInput.class).language()), ISSUED));
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
                    row == null ? null : SponsorCertificationProcesses.texts(row.get("name")),
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

    /** The certificate to issue, or the one issued before; 422 when the certification is not held now. */
    static void prepare(ProcessContext ctx) {
        IssueInput input = ctx.get(INPUT, IssueInput.class);
        Certification certification = resolve(ctx, input.certification()).orElse(null);
        Optional<CertificationHolding.Holding> holding = certification == null ? Optional.empty()
            : holding(ctx, certification);
        if (holding.isEmpty()) {
            ctx.reject(new Violation("certification", NOT_HELD, "The caller does not hold " + input.certification()));
            return;
        }
        CertificationHolding.Holding h = holding.get();
        EntityInstance existing = certificateOf(ctx, certification, h.until());
        if (existing != null) {
            ctx.put(OUTPUT, new IssueOutput(String.valueOf(existing.id()), existing.get("verifyKey"), h.until(),
                false));
            return;
        }
        EntityInstance profile = CertificationProcesses.first(ctx, PROFILE);
        String name = profile == null ? null : profile.get("displayName");
        String key = HexFormat.of().formatHex(ctx.get(RANDOM, byte[].class));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("userId", ctx.request().actorId());
        values.put("certification", certification.key());
        values.put("certificationVersion", BigDecimal.valueOf(certification.version()));
        values.put("heldFrom", h.from());
        values.put("validUntil", h.until());
        values.put("holderName", name == null || name.isBlank() ? "-" : name.strip());
        values.put("verifyKey", key);
        values.put("language", input.language());
        values.put("issuedAt", ctx.opTime());
        Object id = ctx.changes().insert(QbCertificates.CERTIFICATE, values);
        ctx.put(NEW_ID, id);
        ctx.put(OUTPUT, new IssueOutput(String.valueOf(id), key, h.until(), true));
    }

    /** Where the certificate is verified: the App's verify page of its key. */
    static String verifyAddress(String siteUrl, String key) {
        String base = siteUrl == null ? "" : siteUrl.strip().replaceAll("/+$", "");
        return base + VERIFY_PATH + key;
    }

    /** The App's verify page (the entry {@code app}'s path and the route). */
    public static final String VERIFY_PATH = "/taker/verify/";

    /**
     * What the certificate's templates take: the row, the certification's name in the certificate's language and the
     * verify address; for a sponsor's certification (Q88a, D-Q88-6) the name from its row and the sponsor as the
     * issuer (the platform's issuer otherwise, qb.me.certificate-issuer).
     */
    static Map<String, Object> documentParams(MessageCatalog messages, String siteUrl, ProcessContext ctx) {
        IssueInput input = ctx.get(INPUT, IssueInput.class);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("certificateId", String.valueOf(ctx.get(NEW_ID)));
        params.put("verifyAddress", verifyAddress(siteUrl, ctx.get(OUTPUT, IssueOutput.class).verifyKey()));
        EntityInstance row = sponsorRow(ctx, input.certification());
        if (row == null) {
            params.put("certificationName", name(messages, input));
        } else {
            String name = SponsorCertifications.text(row.get("name"), input.language());
            params.put("certificationName", name == null ? input.certification() : name);
            String issuer = sponsorName(ctx, row);
            if (issuer != null && !issuer.isBlank()) {
                params.put("issuerName", issuer);
            }
        }
        return params;
    }

    /** The certification's name in the certificate's language (the catalog's: a message). */
    static String name(MessageCatalog messages, IssueInput input) {
        return messages.find(NAME_KEY + input.certification(), Locale.forLanguageTag(input.language()))
            .orElse(input.certification());
    }

    private static ProcessContext start(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private CertificateProcesses() {}
}

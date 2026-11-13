package com.jabiz.quizbuks.certification;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformLanguages;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.tree.QbTrees;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.document.DocumentProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A person's certificates (Q77, docs/quizbuks/plans/Q77-certificates.md; F-S5a, docs/quizbuks/plans/impl/F-S5.md):
 * {@link #MINE} lists the caller's (a Taker's; {@link #SPONSOR_MINE} a Sponsor's) certificates of lit nodes, revoked ones
 * marked, and those of Q77 still valid (F-S5b: the App reads this one list; the earlier {@code held} output is gone). F-S2c (D8) paused issuing the Q77 kind and F-S2c2b (D1) removed the certifications
 * themselves: what was issued stays valid, verifiable and downloadable until its end, unless a revocation recorded before
 * ends it. A node's certificate is written by the lighting ({@link NodeCertificates}); here its holder issues its PDF on
 * their first download ({@link #ISSUE}, {@link #SPONSOR_ISSUE}: the document is the holder's alone to read, so the holder
 * issues it and every download after is the stored one) and turns the sharing of its verify page off or on
 * ({@link #SHARE}, {@link #SPONSOR_SHARE}).
 */
public final class CertificateProcesses {

    public static final String MINE = "QB_MY_CERTIFICATIONS";
    public static final String SPONSOR_MINE = "QB_SPONSOR_MY_CERTIFICATES";
    public static final String ISSUE = "QB_CERTIFICATE_ISSUE";
    public static final String SPONSOR_ISSUE = "QB_SPONSOR_CERTIFICATE_ISSUE";
    public static final String SHARE = "QB_CERTIFICATE_SHARE_SET";
    public static final String SPONSOR_SHARE = "QB_SPONSOR_CERTIFICATE_SHARE_SET";

    /** A certificate of Q77's kind has no PDF to issue and no sharing to set (F-S2c, D8: they are no longer issued). */
    public static final String NOT_NODE = "QB_CERTIFICATE_NOT_NODE";
    /** A revoked certificate is issued no PDF (one issued before stays as it was). */
    public static final String REVOKED = "QB_CERTIFICATE_REVOKED";

    /** The App's verify page (the entry {@code app}'s path and the route). */
    public static final String VERIFY_PATH = "/taker/verify/";

    /** The most certificates, revocations or Sponsors' certifications read for one person. */
    static final int MAX_ROWS = 500;

    public record MineInput() {}

    /**
     * One certificate, of either source.
     *
     * @param source        {@code NODE} (a lit node's) or {@code CERTIFICATION} (Q77's)
     * @param names         the certificate's name by language (a Q77 Sponsor's certification's; none for the catalog's,
     *                      whose names are the App's texts)
     * @param issuerName    who vouches for it (a Q77 Sponsor's certification: their company; none for the catalog's)
     * @param litAt         when the node was lit (Q77: when the holding began)
     * @param revokedAt     when the lighting was revoked (a node's; a revoked one is no longer valid)
     * @param shared        whether its verify page shows (always true for Q77's)
     * @param runId         the PDF once issued (a node's; Q77's PDF is found by its subject)
     * @param heldUntil     Q77: the end of the holding (a node's has none)
     * @param certification Q77: the certification
     */
    public record Certificate(String source, String certificateId, String verifyKey, Map<String, String> names,
        String issuerName, Instant litAt, Instant revokedAt, boolean shared, String runId, Instant heldUntil,
        String certification) {}

    public record MineOutput(List<Certificate> certificates) {}

    /** @param certificateId the node's certificate; @param language the PDF's language, used the first time only */
    public record IssueInput(@NotBlank @Pattern(regexp = ID) String certificateId,
        @NotBlank @Pattern(regexp = "en|zh|ja") String language) {}

    /** @param issued whether this call issued it (false: issued before) */
    public record IssueOutput(String certificateId, String verifyKey, String runId, boolean issued) {}

    public record ShareInput(@NotBlank @Pattern(regexp = ID) String certificateId, boolean shared) {}

    public record ShareOutput(String certificateId, boolean shared) {}

    /** A certificate's key (anything else is refused before it reaches a lock name or a query). */
    static final String ID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String CERT_ID = "certificateId";
    static final String CERTIFICATE = "certificate";
    static final String CERTIFICATES = "certificates";
    static final String REVOCATIONS = "certRevocations";
    static final String NODE_REVOCATIONS = "nodeRevocations";
    static final String SPONSOR_ROWS = "certSponsorRows";
    static final String SPONSORS = "certSponsors";
    static final String ISSUED = "issued";

    // ---- the lists -------------------------------------------------------------------------------------------------

    public static ProcessDefinition<MineInput, MineOutput, ProcessContext> mine() {
        return ProcessDefinition.define(MINE, 1, MineInput.class, MineOutput.class, ProcessContext.class, pb -> {
            pb.description("Lists the caller's certificates: those of lit nodes and those of Q77 still valid.")
                .permissions(QbPermissions.ME)
                .contextFactory((start, input) -> start(start, input))
                .outputMapper(ctx -> ctx.get(OUTPUT, MineOutput.class));
            load(pb);
            pb.compute("Find the certificates", (metadata, ctx) -> ctx.put(OUTPUT,
                new MineOutput(certificates(ctx))));
        });
    }

    /** F-S5a: a Sponsor's own certificates of the nodes they lit (a Sponsor has none of Q77's). */
    public static ProcessDefinition<MineInput, MineOutput, ProcessContext> sponsorMine() {
        return ProcessDefinition.define(SPONSOR_MINE, 1, MineInput.class, MineOutput.class, ProcessContext.class,
            pb -> pb
                .description("Lists the certificates of the nodes the caller lit as a Sponsor.")
                .permissions(QbPermissions.SPONSOR_ME)
                .contextFactory((start, input) -> start(start, input))
                .outputMapper(ctx -> ctx.get(OUTPUT, MineOutput.class))
                .step("Load the certificates", QueryEntities.<ProcessContext>of(QbCertificates.MY_SPONSOR_DATASET,
                    ctx -> EntityQuery.builder().limit(MAX_ROWS).build(), CERTIFICATES))
                .step("Load their revocations", QueryEntities.<ProcessContext>of(
                    QbTrees.defaultDataset(QbTrees.REVOCATION), CertificateProcesses::nodeRevocations,
                    NODE_REVOCATIONS))
                .compute("Find the certificates", (metadata, ctx) -> ctx.put(OUTPUT,
                    new MineOutput(certificates(ctx)))));
    }

    /**
     * The caller's certificates and their Q77 revocations, then the Sponsors' certifications those name and the
     * revocations of the lit records the nodes' ones came from, then the Sponsors' names (three statements; nothing
     * when the caller has none).
     */
    private static <I, O> void load(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the caller's certificates and revocations", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbCertificates.MY_DATASET, ctx -> EntityQuery.builder().limit(MAX_ROWS).build(),
                    CERTIFICATES),
                QueryEntities.query(QbCertifications.defaultDataset(QbCertifications.RECORD), ctx ->
                    EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("userId", ctx.request().actorId()),
                        new QueryPredicate.Eq("kind", QbCertifications.REVOKED)))).limit(MAX_ROWS).build(),
                    REVOCATIONS)))
            .step("Load the Sponsors' certifications named and the nodes' revocations", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbSponsorCertifications.DEFAULT_DATASET, ctx -> {
                    List<Object> ids = list(ctx, CERTIFICATES).stream()
                        .map(c -> SponsorCertifications.idOf(String.valueOf((Object) c.get("certification"))))
                        .filter(java.util.Objects::nonNull).distinct().map(id -> (Object) id).toList();
                    return EntityQuery.builder().where(new QueryPredicate.In("certificationId", ids))
                        .limit(MAX_ROWS).build();
                }, SPONSOR_ROWS),
                QueryEntities.query(QbTrees.defaultDataset(QbTrees.REVOCATION), CertificateProcesses::nodeRevocations,
                    NODE_REVOCATIONS)))
            .step("Load the Sponsors named", QueryEntities.<ProcessContext>of(
                QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> {
                    List<Object> sponsors = list(ctx, SPONSOR_ROWS).stream()
                        .map(r -> (Object) String.valueOf((Object) r.get("sponsorId"))).distinct().toList();
                    return EntityQuery.builder().where(new QueryPredicate.In("userId", sponsors))
                        .limit(MAX_ROWS).build();
                }, SPONSORS));
    }

    /** The revocations of the lit records the caller's node certificates came from. */
    private static EntityQuery nodeRevocations(ProcessContext ctx) {
        List<Object> lits = new ArrayList<>();
        list(ctx, CERTIFICATES).stream().filter(c -> QbCertificates.NODE.equals(c.get("source")))
            .forEach(c -> lits.add(String.valueOf((Object) c.get("litId"))));
        return EntityQuery.builder().where(new QueryPredicate.In("litId", lits)).limit(Math.max(1, lits.size()))
            .build();
    }

    /**
     * The Q77 certificates still valid now, the latest of each certification, in the order they were issued: not past
     * their end, and no revocation of the certification after the holding began (as the verify page reads it).
     */
    private static List<EntityInstance> valid(ProcessContext ctx) {
        Instant now = ctx.opTime();
        Map<String, EntityInstance> latest = new LinkedHashMap<>();
        list(ctx, CERTIFICATES).stream()
            .filter(c -> QbCertificates.CERTIFICATION.equals(c.get("source")))
            .sorted(Comparator.comparing((EntityInstance c) -> instant(c.get("issuedAt"))))
            .filter(c -> instant(c.get("validUntil")).isAfter(now) && !revoked(ctx, c))
            .forEach(c -> latest.merge(String.valueOf((Object) c.get("certification")), c,
                (a, b) -> instant(b.get("validUntil")).isAfter(instant(a.get("validUntil"))) ? b : a));
        return List.copyOf(latest.values());
    }

    /**
     * Every certificate to show, newest first: a node's with its revocation if any (a revoked one stays in the list,
     * marked), then the Q77 ones still valid.
     */
    static List<Certificate> certificates(ProcessContext ctx) {
        Map<String, Instant> revokedAt = new LinkedHashMap<>();
        list(ctx, NODE_REVOCATIONS).forEach(row -> revokedAt.merge(String.valueOf((Object) row.get("litId")),
            instant(row.get("revokedAt")), (a, b) -> a.isAfter(b) ? a : b));
        List<Certificate> all = new ArrayList<>();
        list(ctx, CERTIFICATES).stream().filter(c -> QbCertificates.NODE.equals(c.get("source"))).forEach(c ->
            all.add(new Certificate(QbCertificates.NODE, String.valueOf(c.id()), c.get("verifyKey"),
                SponsorCertifications.texts(c.get("certificateNames")), c.get("issuerName"),
                instant(c.get("heldFrom")), revokedAt.get(String.valueOf((Object) c.get("litId"))),
                !Boolean.FALSE.equals(c.get("shared")), c.get("runId"), null, null)));
        for (EntityInstance certificate : valid(ctx)) {
            String key = String.valueOf((Object) certificate.get("certification"));
            EntityInstance row = sponsorRow(ctx, key);
            all.add(new Certificate(QbCertificates.CERTIFICATION, String.valueOf(certificate.id()),
                certificate.get("verifyKey"), row == null ? null : SponsorCertifications.texts(row.get("name")),
                row == null ? null : sponsorName(ctx, row), instant(certificate.get("heldFrom")), null, true, null,
                instant(certificate.get("validUntil")), key));
        }
        all.sort(Comparator.comparing(Certificate::litAt).reversed());
        return List.copyOf(all);
    }

    private static boolean revoked(ProcessContext ctx, EntityInstance certificate) {
        Instant from = instant(certificate.get("heldFrom"));
        Object certification = certificate.get("certification");
        return list(ctx, REVOCATIONS).stream().anyMatch(r -> String.valueOf(certification)
            .equals(String.valueOf((Object) r.get("certification"))) && instant(r.get("at")).isAfter(from));
    }

    private static EntityInstance sponsorRow(ProcessContext ctx, String key) {
        String id = SponsorCertifications.idOf(key);
        return id == null ? null : list(ctx, SPONSOR_ROWS).stream()
            .filter(r -> id.equals(String.valueOf(r.id()))).findFirst().orElse(null);
    }

    /** The company name of the Sponsor whose certification {@code row} is; null when the onboarding is gone. */
    private static String sponsorName(ProcessContext ctx, EntityInstance row) {
        return list(ctx, SPONSORS).stream()
            .filter(s -> String.valueOf((Object) s.get("userId")).equals(String.valueOf((Object) row.get("sponsorId"))))
            .map(s -> (String) s.get("company")).findFirst().orElse(null);
    }

    // ---- the PDF ---------------------------------------------------------------------------------------------------

    /**
     * @param siteUrl the site's address ({@code jabiz.mail.base-url}); the certificate's verify address is
     *                {@code <siteUrl>/taker/verify/<key>}, a path alone without one (development and tests)
     */
    public static ProcessDefinition<IssueInput, IssueOutput, ProcessContext> issue(String siteUrl) {
        return issueProcess(ISSUE, QbPermissions.ME, QbCertificates.MY_DATASET, QbCertificates.NODE_LAYOUT_ID, siteUrl,
            "Issues the PDF of one of the caller's certificates of a lit node on their first download, or gives the "
                + "one issued.");
    }

    public static ProcessDefinition<IssueInput, IssueOutput, ProcessContext> sponsorIssue(String siteUrl) {
        return issueProcess(SPONSOR_ISSUE, QbPermissions.SPONSOR_ME, QbCertificates.MY_SPONSOR_DATASET,
            QbCertificates.NODE_SPONSOR_LAYOUT_ID, siteUrl, "Issues the PDF of one of the caller's certificates of a "
                + "node lit as a Sponsor on their first download, or gives the one issued.");
    }

    private static ProcessDefinition<IssueInput, IssueOutput, ProcessContext> issueProcess(String code,
        String permission, String dataset, String layoutId, String siteUrl, String description) {
        return ProcessDefinition.define(code, 1, IssueInput.class, IssueOutput.class, ProcessContext.class, pb -> pb
            .description(description)
            .permissions(permission)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                ctx.put(CERT_ID, input.certificateId().toLowerCase(Locale.ROOT));
                return ctx;
            })
            .outputMapper(CertificateProcesses::issueOutput)
            // Two requests at once issue one document: the second waits and finds the first's.
            .step("Take the certificate's lock", HoldLock.<ProcessContext>exclusive(
                ctx -> "qb.certificate-doc:" + ctx.get(CERT_ID)))
            // Through the caller's own dataset: another person's certificate is not found (404).
            .step("Load the certificate", LoadEntity.by(dataset, CERT_ID, CERTIFICATE))
            .step("Load its revocation", QueryEntities.<ProcessContext>of(QbTrees.defaultDataset(QbTrees.REVOCATION),
                ctx -> {
                    // Only a PDF not issued yet needs to know (one issued before is given whatever became of the lighting).
                    Object lit = issuedBefore(ctx) ? null : ctx.get(CERTIFICATE, EntityInstance.class).get("litId");
                    return EntityQuery.builder().where(new QueryPredicate.In("litId",
                        lit == null ? List.of() : List.of(String.valueOf(lit)))).limit(1).build();
                }, REVOCATIONS))
            .compute("Check the certificate", (metadata, ctx) -> check(ctx))
            // The document shows the certificate as lit: its name in the language, the issuer and holder as frozen.
            .step("Issue the document", CallProcess.<ProcessContext>when(ctx -> !issuedBefore(ctx),
                DocumentProcesses.ISSUE, 1, ctx -> new DocumentProcesses.IssueInput(layoutId,
                    documentParams(siteUrl, ctx), null, null, ctx.get(INPUT, IssueInput.class).language()), ISSUED))
            .compute("Record it", (metadata, ctx) -> record(ctx)));
    }

    private static boolean issuedBefore(ProcessContext ctx) {
        return ctx.get(CERTIFICATE, EntityInstance.class).get("runId") != null;
    }

    static void check(ProcessContext ctx) {
        EntityInstance certificate = ctx.get(CERTIFICATE, EntityInstance.class);
        if (!QbCertificates.NODE.equals(certificate.get("source"))) {
            throw new BusinessRuleViolationException(new Violation("certificateId", NOT_NODE,
                "Only a certificate of a lit node has a PDF to issue", Map.of()));
        }
        if (!issuedBefore(ctx) && !list(ctx, REVOCATIONS).isEmpty()) {
            throw new BusinessRuleViolationException(new Violation("certificateId", REVOKED,
                "The lighting this certificate came from was revoked", Map.of()));
        }
    }

    /** What the document's templates take: the row, its name in the document's language and the verify address. */
    static Map<String, Object> documentParams(String siteUrl, ProcessContext ctx) {
        EntityInstance certificate = ctx.get(CERTIFICATE, EntityInstance.class);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("certificateId", String.valueOf(certificate.id()));
        params.put("certificateName", nameIn(SponsorCertifications.texts(certificate.get("certificateNames")),
            ctx.get(INPUT, IssueInput.class).language()));
        params.put("verifyAddress", verifyAddress(siteUrl, certificate.get("verifyKey")));
        return params;
    }

    /** The name in {@code language}, else in English, else in any language (the names are the node's, by language). */
    static String nameIn(Map<String, String> names, String language) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        String found = names.get(language);
        if (found == null || found.isBlank()) {
            found = names.get("en");
        }
        if (found == null || found.isBlank()) {
            found = PlatformLanguages.CODES.stream().map(names::get).filter(t -> t != null && !t.isBlank()).findFirst()
                .orElse(names.values().stream().filter(t -> t != null && !t.isBlank()).findFirst().orElse(""));
        }
        return found;
    }

    /** Where the certificate is verified: the App's verify page of its key. */
    static String verifyAddress(String siteUrl, Object key) {
        String base = siteUrl == null ? "" : siteUrl.strip().replaceAll("/+$", "");
        return base + VERIFY_PATH + key;
    }

    static void record(ProcessContext ctx) {
        if (issuedBefore(ctx)) {
            return;
        }
        EntityInstance certificate = ctx.get(CERTIFICATE, EntityInstance.class);
        DocumentProcesses.IssueOutput issued = ctx.get(ISSUED, DocumentProcesses.IssueOutput.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("runId", issued.runId());
        ctx.changes().update(QbCertificates.CERTIFICATE, certificate.id(), certificate.version(), values);
    }

    static IssueOutput issueOutput(ProcessContext ctx) {
        EntityInstance certificate = ctx.get(CERTIFICATE, EntityInstance.class);
        if (issuedBefore(ctx)) {
            return new IssueOutput(String.valueOf(certificate.id()), certificate.get("verifyKey"),
                certificate.get("runId"), false);
        }
        return new IssueOutput(String.valueOf(certificate.id()), certificate.get("verifyKey"),
            ctx.get(ISSUED, DocumentProcesses.IssueOutput.class).runId(), true);
    }

    // ---- sharing ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ShareInput, ShareOutput, ProcessContext> share() {
        return shareProcess(SHARE, QbPermissions.ME, QbCertificates.MY_DATASET,
            "Turns the sharing of the verify page of one of the caller's certificates off or on.");
    }

    public static ProcessDefinition<ShareInput, ShareOutput, ProcessContext> sponsorShare() {
        return shareProcess(SPONSOR_SHARE, QbPermissions.SPONSOR_ME, QbCertificates.MY_SPONSOR_DATASET,
            "Turns the sharing of the verify page of one of the caller's certificates as a Sponsor off or on.");
    }

    private static ProcessDefinition<ShareInput, ShareOutput, ProcessContext> shareProcess(String code,
        String permission, String dataset, String description) {
        return ProcessDefinition.define(code, 1, ShareInput.class, ShareOutput.class, ProcessContext.class, pb -> pb
            .description(description)
            .permissions(permission)
            .requiresVerifiedEmail()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                ctx.put(CERT_ID, input.certificateId().toLowerCase(Locale.ROOT));
                return ctx;
            })
            .outputMapper(ctx -> new ShareOutput(ctx.get(CERT_ID, String.class),
                ctx.get(INPUT, ShareInput.class).shared()))
            // F-ban-coverage: turning sharing on opens a public verify page, so a banned caller's still valid token
            // is refused before anything else (off as well: one rule, and a ban already hides what it should).
            .steps(CallerChecks::refuseBanned)
            .step("Take the certificate's lock", HoldLock.<ProcessContext>exclusive(
                ctx -> "qb.certificate-doc:" + ctx.get(CERT_ID)))
            // Through the caller's own dataset: another person's certificate is not found (404).
            .step("Load the certificate", LoadEntity.by(dataset, CERT_ID, CERTIFICATE))
            .compute("Set the sharing", (metadata, ctx) -> {
                EntityInstance certificate = ctx.get(CERTIFICATE, EntityInstance.class);
                if (!QbCertificates.NODE.equals(certificate.get("source"))) {
                    throw new BusinessRuleViolationException(new Violation("certificateId", NOT_NODE,
                        "Only a certificate of a lit node has a sharing to set", Map.of()));
                }
                boolean shared = ctx.get(INPUT, ShareInput.class).shared();
                if (shared != !Boolean.FALSE.equals(certificate.get("shared"))) {
                    ctx.changes().update(QbCertificates.CERTIFICATE, certificate.id(), certificate.version(),
                        Map.of("shared", shared));
                }
            }));
    }

    static Instant instant(Object value) {
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        return Instant.parse(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object value = ctx.contains(key) ? ctx.get(key, Object.class) : null;
        return value instanceof List<?> rows ? (List<EntityInstance>) rows : List.of();
    }

    private static ProcessContext start(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private CertificateProcesses() {}
}

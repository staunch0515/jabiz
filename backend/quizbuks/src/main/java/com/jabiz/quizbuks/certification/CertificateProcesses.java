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
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Taker's certificates (Q77, docs/quizbuks/plans/Q77-certificates.md): {@link #MINE} lists the certificates the
 * caller was issued that are still valid, the latest of each certification; {@link #ISSUE} issued one. F-S2c (D8)
 * paused issuing until certificates of nodes (F-S5), and F-S2c2b (D1) removed the certifications themselves: what
 * was issued stays valid, verifiable and downloadable until its end, unless a revocation recorded before ends it.
 */
public final class CertificateProcesses {

    public static final String MINE = "QB_MY_CERTIFICATIONS";
    public static final String ISSUE = "QB_CERTIFICATE_ISSUE";

    /** F-S2c (D8): issuing is paused until certificates of nodes (F-S5). */
    public static final String PAUSED = "QB_CERTIFICATE_PAUSED";

    /** The most certificates, revocations or Sponsors' certifications read for one person. */
    static final int MAX_ROWS = 100;

    public record MineInput() {}

    /**
     * One certificate still valid.
     *
     * @param certificateId the certificate; its PDF is the document whose subject it is
     * @param name          Q88a: a Sponsor's certification's name by language (null for the catalog's, whose names
     *                      are the App's texts)
     * @param sponsorName   Q88a: the Sponsor whose certification it is (null for the catalog's)
     */
    public record Held(String certification, Instant heldFrom, Instant heldUntil, String certificateId,
        String verifyKey, Map<String, String> name, String sponsorName) {}

    public record MineOutput(List<Held> held) {}

    /** @param language the certificate's language ({@code en}, {@code zh}, {@code ja}) */
    public record IssueInput(@NotBlank @Size(max = 40) String certification,
        @NotBlank @Pattern(regexp = "en|zh|ja") String language) {}

    /** @param issued whether this call issued it (false: issued before) */
    public record IssueOutput(String certificateId, String verifyKey, Instant heldUntil, boolean issued) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String CERTIFICATES = "certificates";
    static final String REVOCATIONS = "certRevocations";
    static final String SPONSOR_ROWS = "certSponsorRows";
    static final String SPONSORS = "certSponsors";

    public static ProcessDefinition<MineInput, MineOutput, ProcessContext> mine() {
        return ProcessDefinition.define(MINE, 1, MineInput.class, MineOutput.class, ProcessContext.class, pb -> {
            pb.description("Lists the caller's certificates still valid.")
                .permissions(QbPermissions.ME)
                .contextFactory((start, input) -> start(start, input))
                .outputMapper(ctx -> ctx.get(OUTPUT, MineOutput.class));
            load(pb);
            pb.compute("Find the certificates still valid", (metadata, ctx) -> ctx.put(OUTPUT,
                new MineOutput(held(ctx))));
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
     * The caller's certificates and revocations, then the Sponsors' certifications those name and the Sponsors' names
     * (three statements; nothing when the caller was issued none).
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
            .step("Load the Sponsors' certifications named", QueryEntities.<ProcessContext>of(
                QbSponsorCertifications.DEFAULT_DATASET, ctx -> {
                    List<Object> ids = list(ctx, CERTIFICATES).stream()
                        .map(c -> SponsorCertifications.idOf(String.valueOf((Object) c.get("certification"))))
                        .filter(java.util.Objects::nonNull).distinct().map(id -> (Object) id).toList();
                    return EntityQuery.builder().where(new QueryPredicate.In("certificationId", ids))
                        .limit(MAX_ROWS).build();
                }, SPONSOR_ROWS))
            .step("Load the Sponsors named", QueryEntities.<ProcessContext>of(
                QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> {
                    List<Object> sponsors = list(ctx, SPONSOR_ROWS).stream()
                        .map(r -> (Object) String.valueOf((Object) r.get("sponsorId"))).distinct().toList();
                    return EntityQuery.builder().where(new QueryPredicate.In("userId", sponsors))
                        .limit(MAX_ROWS).build();
                }, SPONSORS));
    }

    /**
     * The certificates still valid now, the latest of each certification, in the order they were issued: not past
     * their end, and no revocation of the certification after the holding began (as the verify page reads it).
     */
    static List<Held> held(ProcessContext ctx) {
        Instant now = ctx.opTime();
        Map<String, EntityInstance> latest = new LinkedHashMap<>();
        list(ctx, CERTIFICATES).stream()
            .sorted(Comparator.comparing((EntityInstance c) -> instant(c.get("issuedAt"))))
            .filter(c -> instant(c.get("validUntil")).isAfter(now) && !revoked(ctx, c))
            .forEach(c -> latest.merge(String.valueOf((Object) c.get("certification")), c,
                (a, b) -> instant(b.get("validUntil")).isAfter(instant(a.get("validUntil"))) ? b : a));
        List<Held> held = new ArrayList<>();
        for (EntityInstance certificate : latest.values()) {
            String key = String.valueOf((Object) certificate.get("certification"));
            EntityInstance row = sponsorRow(ctx, key);
            held.add(new Held(key, instant(certificate.get("heldFrom")), instant(certificate.get("validUntil")),
                String.valueOf(certificate.id()), certificate.get("verifyKey"),
                row == null ? null : SponsorCertifications.texts(row.get("name")),
                row == null ? null : sponsorName(ctx, row)));
        }
        return List.copyOf(held);
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
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object value = ctx.get(key, Object.class);
        return value instanceof List<?> rows ? (List<EntityInstance>) rows : List.of();
    }

    private static ProcessContext start(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private CertificateProcesses() {}
}

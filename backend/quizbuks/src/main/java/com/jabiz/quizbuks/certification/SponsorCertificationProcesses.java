package com.jabiz.quizbuks.certification;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.publishing.SponsorChecks;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A sponsor's own certifications (Q88a, docs/quizbuks/plans/Q88-sponsor-certifications-entry-fee.md, D-Q88-1): the
 * sponsor defines one ({@value #CREATE}), changes its texts and validity or raises its version so that holders must
 * pass again ({@value #UPDATE}), and retires it ({@value #RETIRE}: its open rounds close, no new round or
 * prerequisite names it, what is held runs out by itself). Behind the switch {@code
 * qb.certification.sponsor-rounds.enabled} (D-Q88-7); an approved, unbanned sponsor only. Which publication is a round
 * of one is {@code PublicationRounds} (publishing). None of this touches the answering path.
 */
public final class SponsorCertificationProcesses {

    public static final String CREATE = "QB_SPONSOR_CERTIFICATION_CREATE";
    public static final String UPDATE = "QB_SPONSOR_CERTIFICATION_UPDATE";
    public static final String RETIRE = "QB_SPONSOR_CERTIFICATION_RETIRE";

    /** @param name the name by language ({@code {en: ..., ja: ...}}), at least one; the texts the sponsor's own */
    public record CreateInput(@NotNull Map<String, String> name, Map<String, String> description,
        Integer validityDays) {}

    /** @param raiseVersion true when the rules changed: what was held under the old version counts no more */
    public record UpdateInput(@NotNull UUID certificationId, @NotNull Map<String, String> name,
        Map<String, String> description, Integer validityDays, Boolean raiseVersion) {}

    public record Ref(@NotNull UUID certificationId) {}

    /** One certification as its sponsor sees it; {@code key} is what rounds and prerequisites name it by. */
    public record Output(String certificationId, String key, Map<String, String> name,
        Map<String, String> description, int validityDays, int certificationVersion, String status,
        Instant updatedAt) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String MINE = "sponsorCertifications";
    static final String PARAMS = "sponsorCertificationParams";
    static final String ROUNDS = "sponsorCertificationRounds";

    public static ProcessDefinition<CreateInput, Output, ProcessContext> create() {
        return ProcessDefinition.define(CREATE, 1, CreateInput.class, Output.class, ProcessContext.class, pb -> {
            open(pb, "Defines a certification of the sponsor's own, which their publications may be rounds of.");
            pb.compute("Create it", (metadata, ctx) -> {
                CreateInput input = ctx.get(INPUT, CreateInput.class);
                if (refused(ctx)) {
                    return;
                }
                int validity = input.validityDays() == null ? SponsorCertifications.DEFAULT_VALIDITY_DAYS
                    : input.validityDays();
                SponsorCertifications.problems(input.name(), input.description(), validity).forEach(ctx::reject);
                if (list(ctx, MINE).size() >= SponsorCertifications.MAX_PER_SPONSOR) {
                    ctx.reject(new Violation("name", CertificationCodes.SPONSOR_LIMIT, "A sponsor defines at most "
                        + SponsorCertifications.MAX_PER_SPONSOR + " certifications",
                        Map.of("max", SponsorCertifications.MAX_PER_SPONSOR)));
                }
                if (ctx.hasViolations()) {
                    return;
                }
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("sponsorId", ctx.request().actorId());
                values.put("name", texts(input.name()));
                values.put("description", texts(input.description()));
                values.put("validityDays", validity);
                values.put("certificationVersion", 1);
                values.put("status", QbSponsorCertifications.ACTIVE);
                values.put("createdAt", ctx.opTime());
                values.put("updatedAt", ctx.opTime());
                Object id = ctx.changes().insert(QbSponsorCertifications.CERTIFICATION, values);
                ctx.put(OUTPUT, new Output(String.valueOf(id), SponsorCertifications.keyOf(id), texts(input.name()),
                    texts(input.description()), validity, 1, QbSponsorCertifications.ACTIVE, ctx.opTime()));
            });
        });
    }

    public static ProcessDefinition<UpdateInput, Output, ProcessContext> update() {
        return ProcessDefinition.define(UPDATE, 1, UpdateInput.class, Output.class, ProcessContext.class, pb -> {
            open(pb, "Changes a certification's texts or validity, or raises its version when its rules changed "
                + "(what was held under the old version counts no more).");
            pb.compute("Update it", (metadata, ctx) -> {
                UpdateInput input = ctx.get(INPUT, UpdateInput.class);
                EntityInstance row = own(ctx, input.certificationId());
                if (refused(ctx)) {
                    return;
                }
                if (!QbSponsorCertifications.ACTIVE.equals(row.get("status"))) {
                    ctx.reject(retired(input.certificationId()));
                    return;
                }
                int validity = input.validityDays() == null ? number(row.get("validityDays")) : input.validityDays();
                SponsorCertifications.problems(input.name(), input.description(), validity).forEach(ctx::reject);
                if (ctx.hasViolations()) {
                    return;
                }
                int version = number(row.get("certificationVersion")) + (Boolean.TRUE.equals(input.raiseVersion()) ? 1 : 0);
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("name", texts(input.name()));
                values.put("description", texts(input.description()));
                values.put("validityDays", validity);
                values.put("certificationVersion", version);
                values.put("updatedAt", ctx.opTime());
                ctx.changes().update(QbSponsorCertifications.CERTIFICATION, row.id(), row.version(), values);
                ctx.put(OUTPUT, new Output(String.valueOf(row.id()), SponsorCertifications.keyOf(row.id()),
                    texts(input.name()), texts(input.description()), validity, version,
                    QbSponsorCertifications.ACTIVE, ctx.opTime()));
            });
        });
    }

    public static ProcessDefinition<Ref, Output, ProcessContext> retire() {
        return ProcessDefinition.define(RETIRE, 1, Ref.class, Output.class, ProcessContext.class, pb -> {
            open(pb, "Retires a certification: its open rounds close, no new round or prerequisite names it; what "
                + "is held runs out by itself.");
            // Its open rounds, to close them with it (D-Q88-1).
            pb.step("Load its open rounds", QueryEntities.<ProcessContext>of(
                    QbCertifications.defaultDataset(QbCertifications.ROUND), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("certification",
                                SponsorCertifications.keyOf(ctx.get(INPUT, Ref.class).certificationId())),
                            new QueryPredicate.IsNull("closedAt"))))
                        .limit(CertificationProcesses.MAX_ROWS).build(), ROUNDS))
                .compute("Retire it", (metadata, ctx) -> {
                    Ref input = ctx.get(INPUT, Ref.class);
                    EntityInstance row = own(ctx, input.certificationId());
                    if (refused(ctx)) {
                        return;
                    }
                    if (!QbSponsorCertifications.ACTIVE.equals(row.get("status"))) {
                        ctx.reject(retired(input.certificationId()));
                        return;
                    }
                    for (EntityInstance round : list(ctx, ROUNDS)) {
                        ctx.changes().update(QbCertifications.ROUND, round.id(), round.version(),
                            Map.of("closedAt", ctx.opTime()));
                    }
                    ctx.changes().update(QbSponsorCertifications.CERTIFICATION, row.id(), row.version(),
                        Map.of("status", QbSponsorCertifications.RETIRED, "updatedAt", ctx.opTime()));
                    ctx.put(OUTPUT, new Output(String.valueOf(row.id()), SponsorCertifications.keyOf(row.id()),
                        texts(row.get("name")), texts(row.get("description")), number(row.get("validityDays")),
                        number(row.get("certificationVersion")), QbSponsorCertifications.RETIRED, ctx.opTime()));
                });
        });
    }

    /** What the three share: the sponsor's standing, the switch, the lock and their own rows. */
    private static <I> void open(ProcessDefinitionBuilder<I, Output, ProcessContext> pb, String description) {
        pb.description(description)
            .permissions(QbPermissions.PUBLICATION_WRITE)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, Output.class))
            // Not published yet (before QB_SETUP's proposal) reads as off.
            .step("Load the switch", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                QbParams.SPONSOR_ROUNDS_ENABLED));
        SponsorChecks.load(pb);
        pb.step("Lock the sponsor's certifications", HoldLock.<ProcessContext>exclusive(
                ctx -> QbSponsorCertifications.lockName(ctx.request().actorId())))
            .step("Load the sponsor's certifications", QueryEntities.<ProcessContext>of(
                QbSponsorCertifications.SPONSOR_DATASET,
                ctx -> EntityQuery.builder().limit(SponsorCertifications.MAX_PER_SPONSOR + 1).build(), MINE));
    }

    /** Rejects when the switch is off or the caller may not act as a sponsor; true when rejected. */
    static boolean refused(ProcessContext ctx) {
        if (!enabled(ctx.get(PARAMS, ParamValues.class))) {
            ctx.reject(off());
            return true;
        }
        SponsorChecks.problems(ctx).forEach(ctx::reject);
        return ctx.hasViolations();
    }

    /** Whether the sponsors' certifications are on (D-Q88-7): the switch is published and true. */
    public static boolean enabled(ParamValues params) {
        return params != null
            && Boolean.TRUE.equals(params.find(QbParams.SPONSOR_ROUNDS_ENABLED, Boolean.class).orElse(null));
    }

    public static Violation off() {
        return new Violation("certificationId", CertificationCodes.SPONSOR_OFF, "Sponsors' certifications are not "
            + "offered", Map.of());
    }

    public static Violation retired(Object certificationId) {
        return new Violation("certificationId", CertificationCodes.SPONSOR_RETIRED, "The certification is retired",
            Map.of("certificationId", String.valueOf(certificationId)));
    }

    /** The caller's own row {@code certificationId} names, from what {@link #open} read; 404 for any other. */
    private static EntityInstance own(ProcessContext ctx, UUID certificationId) {
        return list(ctx, MINE).stream().filter(r -> String.valueOf(r.id()).equals(certificationId.toString()))
            .findFirst().orElseThrow(() -> new EntityNotFoundException(QbSponsorCertifications.CERTIFICATION + " "
                + certificationId + " not found"));
    }

    /** The texts without blank ones; null when none is left (the column is then null). */
    static Map<String, String> texts(Object given) {
        if (!(given instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, String> texts = new LinkedHashMap<>();
        map.forEach((language, text) -> {
            if (text != null && !text.toString().isBlank()) {
                texts.put(String.valueOf(language), text.toString().strip());
            }
        });
        return texts.isEmpty() ? null : Map.copyOf(texts);
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    static int number(Object value) {
        return SponsorCertifications.number(value);
    }

    private SponsorCertificationProcesses() {}
}

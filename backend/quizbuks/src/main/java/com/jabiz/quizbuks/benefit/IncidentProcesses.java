package com.jabiz.quizbuks.benefit;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.publishing.PublicationLifecycle;
import com.jabiz.quizbuks.reward.QbRewards;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.jabiz.quizbuks.benefit.BenefitSteps.INPUT;
import static com.jabiz.quizbuks.benefit.BenefitSteps.OUTPUT;
import static com.jabiz.quizbuks.benefit.BenefitSteps.first;
import static com.jabiz.quizbuks.benefit.BenefitSteps.list;

/**
 * When a purchase goes wrong (Q89d-1, docs/quizbuks/plans/Q89-sponsored-benefits.md, D-Q89-12). Either side of a
 * purchase may:
 * <ul>
 *   <li>{@value #SUSPEND} a confirmed purchase, with a reason: in the same transaction every running publication
 *       drawing on it is paused ({@code QB_PUBLICATION_BENEFIT_PAUSE}, one by one in the order of their ids, each under
 *       its publication's locks, before the purchase's lock: the order of a submission), and no publication takes its
 *       share any more (it is not confirmed). With {@code voidUnused}, the prizes not collected yet go void, each in a
 *       transaction of its own (the job {@value QbBenefits#VOID_JOB}); their takers are told as for any void;</li>
 *   <li>{@value #RESUME} it: confirmed again; the publications stay paused until their sponsors resume them (the
 *       sponsor's resume refuses while the purchase is suspended);</li>
 *   <li>{@value #RECORD} an incident about the purchase or one of its prizes, and {@value #RESOLVE} it with how it
 *       ended. The platform records; settling between the two sponsors is theirs (no refund through it).</li>
 * </ul>
 * Suspending and resuming change the status only, not the purchase's revision: drafts drawing on it need no new save.
 */
public final class IncidentProcesses {

    public static final String SUSPEND = "QB_BENEFIT_PURCHASE_SUSPEND";
    public static final String RESUME = "QB_BENEFIT_PURCHASE_RESUME";
    public static final String RECORD = "QB_BENEFIT_INCIDENT_RECORD";
    public static final String RESOLVE = "QB_BENEFIT_INCIDENT_RESOLVE";

    /** @param voidUnused whether the prizes not collected yet go void too */
    public record SuspendInput(@NotBlank @Size(max = 36) String purchaseId,
        @NotBlank @Size(max = QbBenefits.REASON_LENGTH) String reason, Boolean voidUnused) {}

    public record ResumeInput(@NotBlank @Size(max = 36) String purchaseId) {}

    /** @param redemptionId one of the purchase's prizes, when the incident is about it */
    public record RecordInput(@NotBlank @Size(max = 36) String purchaseId, @Size(max = 36) String redemptionId,
        @NotBlank @Size(max = 20) String kind, @NotBlank @Size(max = QbBenefits.INCIDENT_TEXT_LENGTH) String note) {}

    public record ResolveInput(@NotBlank @Size(max = 36) String incidentId,
        @NotBlank @Size(max = QbBenefits.INCIDENT_TEXT_LENGTH) String resolution) {}

    /** @param paused how many publications this suspension paused */
    public record PurchaseState(String purchaseId, String status, int paused) {}

    public record IncidentOutput(String incidentId, boolean resolved) {}

    static final String FOUND = "found";
    static final String PURCHASE = "purchase";
    static final String OPEN = "openAllocations";
    static final String PAUSES = "pauses";
    static final String INCIDENT = "incident";
    static final String ALLOCATIONS = "allocations";
    static final String PRIZE = "prize";

    private IncidentProcesses() {}

    // ---- suspend and resume ----------------------------------------------------------------------------------------

    public static ProcessDefinition<SuspendInput, PurchaseState, ProcessContext> suspend() {
        return ProcessDefinition.define(SUSPEND, 1, SuspendInput.class, PurchaseState.class, ProcessContext.class,
            pb -> {
                BenefitSteps.common(pb, "Suspends a confirmed purchase: the publications drawing on it are paused, "
                    + "and its unused prizes go void when asked.");
                pb.outputMapper(ctx -> ctx.get(OUTPUT, PurchaseState.class));
                findAsEitherSide(pb, ctx -> ctx.get(INPUT, SuspendInput.class).purchaseId());
                pb.compute("Check it is confirmed", (metadata, ctx) -> requireStatus(first(ctx, FOUND),
                        PurchaseStatus.CONFIRMED))
                    .step("Load its open shares", QueryEntities.<ProcessContext>of(
                        QbBenefits.defaultDataset(QbBenefits.ALLOCATION), ctx -> openShares(ctx), OPEN))
                    .compute("The publications to pause", (metadata, ctx) -> ctx.put(PAUSES, publicationIds(ctx, OPEN)))
                    // Lock order: each publication's locks (in the order of their ids) before the purchase's.
                    .step("Pause them", CallProcess.forEach(PublicationLifecycle.BENEFIT_PAUSE, 1,
                        IncidentProcesses::pauses, null))
                    .step("Take the purchase's turn", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbBenefits.purchaseLock(first(ctx, FOUND).id())))
                    .step("Load it and its shares again", QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbBenefits.defaultDataset(QbBenefits.PURCHASE),
                            ctx -> BenefitSteps.byId("purchaseId", first(ctx, FOUND).id()), PURCHASE),
                        QueryEntities.query(QbBenefits.defaultDataset(QbBenefits.ALLOCATION),
                            ctx -> openShares(ctx), ALLOCATIONS)))
                    .compute("Suspend it", (metadata, ctx) -> suspended(ctx));
            });
    }

    private static EntityQuery openShares(ProcessContext ctx) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.In("purchaseId", List.of(String.valueOf(first(ctx, FOUND).id()))),
                new QueryPredicate.Eq("status", QbBenefits.OPEN))))
            .limit(com.jabiz.quizbuks.publishing.PublishingLimits.MAX_BENEFIT_ALLOCATIONS).build();
    }

    private static List<String> publicationIds(ProcessContext ctx, String key) {
        return list(ctx, key).stream().map(row -> String.valueOf((Object) row.get("publicationId"))).sorted()
            .distinct().toList();
    }

    @SuppressWarnings("unchecked")
    static List<PublicationLifecycle.BenefitPauseInput> pauses(ProcessContext ctx) {
        return ((List<String>) ctx.get(PAUSES)).stream()
            .map(id -> new PublicationLifecycle.BenefitPauseInput(UUID.fromString(id))).toList();
    }

    @SuppressWarnings("unchecked")
    static void suspended(ProcessContext ctx) {
        EntityInstance purchase = first(ctx, PURCHASE);
        requireStatus(purchase, PurchaseStatus.CONFIRMED);
        // A publication that took its share between the first read and the purchase's lock was not paused: again.
        Set<String> paused = new LinkedHashSet<>((List<String>) ctx.get(PAUSES));
        if (!paused.containsAll(publicationIds(ctx, ALLOCATIONS))) {
            ctx.reject(new Violation("purchaseId", BenefitCodes.PURCHASE_CHANGED, "A publication took a share "
                + "meanwhile: suspend again", Map.of()));
            return;
        }
        SuspendInput input = ctx.get(INPUT, SuspendInput.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", PurchaseStatus.SUSPENDED);
        values.put("suspendedAt", ctx.opTime());
        values.put("suspendedBy", ctx.request().actorId());
        values.put("suspendReason", input.reason().strip());
        values.put("voidUnused", Boolean.TRUE.equals(input.voidUnused()));
        values.put("editedAt", ctx.opTime());
        ctx.changes().update(QbBenefits.PURCHASE, purchase.id(), purchase.version(), values);
        ctx.put(OUTPUT, new PurchaseState(String.valueOf(purchase.id()), PurchaseStatus.SUSPENDED, paused.size()));
    }

    public static ProcessDefinition<ResumeInput, PurchaseState, ProcessContext> resume() {
        return ProcessDefinition.define(RESUME, 1, ResumeInput.class, PurchaseState.class, ProcessContext.class,
            pb -> {
                BenefitSteps.common(pb, "Resumes a suspended purchase; its publications' sponsors resume them.");
                pb.outputMapper(ctx -> ctx.get(OUTPUT, PurchaseState.class));
                findAsEitherSide(pb, ctx -> ctx.get(INPUT, ResumeInput.class).purchaseId());
                pb.step("Take the purchase's turn", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbBenefits.purchaseLock(first(ctx, FOUND).id())))
                    .step("Load it again", QueryEntities.<ProcessContext>of(
                        QbBenefits.defaultDataset(QbBenefits.PURCHASE),
                        ctx -> BenefitSteps.byId("purchaseId", first(ctx, FOUND).id()), PURCHASE))
                    .compute("Resume it", (metadata, ctx) -> {
                        EntityInstance purchase = first(ctx, PURCHASE);
                        requireStatus(purchase, PurchaseStatus.SUSPENDED);
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("status", PurchaseStatus.CONFIRMED);
                        values.put("resumedAt", ctx.opTime());
                        // The voiding stops: the prizes not voided yet stand.
                        values.put("voidUnused", false);
                        values.put("editedAt", ctx.opTime());
                        ctx.changes().update(QbBenefits.PURCHASE, purchase.id(), purchase.version(), values);
                        ctx.put(OUTPUT, new PurchaseState(String.valueOf(purchase.id()), PurchaseStatus.CONFIRMED, 0));
                    });
            });
    }

    // ---- incidents -------------------------------------------------------------------------------------------------

    public static ProcessDefinition<RecordInput, IncidentOutput, ProcessContext> record() {
        return ProcessDefinition.define(RECORD, 1, RecordInput.class, IncidentOutput.class, ProcessContext.class,
            pb -> {
                BenefitSteps.common(pb, "Records an incident about a purchase or one of its prizes.");
                pb.outputMapper(ctx -> ctx.get(OUTPUT, IncidentOutput.class));
                findAsEitherSide(pb, ctx -> ctx.get(INPUT, RecordInput.class).purchaseId());
                // The prize named, and the purchase's shares (its publications): one statement.
                pb.step("Load the prize named", QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbRewards.defaultDataset(QbRewards.REDEMPTION), ctx -> {
                            String prize = ctx.get(INPUT, RecordInput.class).redemptionId();
                            return EntityQuery.builder().where(new QueryPredicate.In("redemptionId",
                                prize == null || prize.isBlank() ? List.of() : List.of(uuidOrNone(prize)))).limit(1)
                                .build();
                        }, PRIZE),
                        QueryEntities.query(QbBenefits.defaultDataset(QbBenefits.ALLOCATION), ctx -> EntityQuery
                            .builder().where(new QueryPredicate.In("purchaseId", List.of(String.valueOf(
                                first(ctx, FOUND).id())))).limit(com.jabiz.quizbuks.publishing.PublishingLimits
                                .MAX_BENEFIT_ALLOCATIONS).build(), ALLOCATIONS)))
                    .compute("Record it", (metadata, ctx) -> recorded(ctx));
            });
    }

    static Object uuidOrNone(String id) {
        try {
            return UUID.fromString(id.strip());
        } catch (IllegalArgumentException e) {
            return UUID.fromString("00000000-0000-0000-0000-000000000000");
        }
    }

    static void recorded(ProcessContext ctx) {
        RecordInput input = ctx.get(INPUT, RecordInput.class);
        EntityInstance purchase = first(ctx, FOUND);
        if (!QbBenefits.INCIDENT_KINDS.contains(input.kind())) {
            ctx.reject(new Violation("kind", BenefitCodes.INCIDENT_TEXT, "Unknown kind " + input.kind(),
                Map.of("kind", input.kind())));
            return;
        }
        String redemptionId = null;
        if (input.redemptionId() != null && !input.redemptionId().isBlank()) {
            EntityInstance prize = first(ctx, PRIZE);
            if (prize == null || !publicationIds(ctx, ALLOCATIONS).contains(String.valueOf(
                (Object) prize.get("publicationId")))) {
                ctx.reject(new Violation("redemptionId", BenefitCodes.INCIDENT_PRIZE, "The prize is not one of the "
                    + "purchase's", Map.of()));
                return;
            }
            redemptionId = String.valueOf(prize.id());
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("purchaseId", String.valueOf(purchase.id()));
        values.put("redemptionId", redemptionId);
        values.put("sponsorId", purchase.get("sponsorId"));
        values.put("providerId", purchase.get("providerId"));
        values.put("kind", input.kind());
        values.put("note", input.note().strip());
        values.put("recordedBy", ctx.request().actorId());
        values.put("recordedAt", ctx.opTime());
        Object id = ctx.changes().insert(QbBenefits.INCIDENT, values);
        ctx.put(OUTPUT, new IncidentOutput(String.valueOf(id), false));
    }

    public static ProcessDefinition<ResolveInput, IncidentOutput, ProcessContext> resolve() {
        return ProcessDefinition.define(RESOLVE, 1, ResolveInput.class, IncidentOutput.class, ProcessContext.class,
            pb -> {
                BenefitSteps.common(pb, "Records how an incident of a purchase ended.");
                pb.outputMapper(ctx -> ctx.get(OUTPUT, IncidentOutput.class));
                pb.step("Find the incident", QueryEntities.<ProcessContext>of(
                        QbBenefits.defaultDataset(QbBenefits.INCIDENT),
                        ctx -> BenefitSteps.byId("incidentId", ctx.get(INPUT, ResolveInput.class).incidentId()), FOUND))
                    .compute("Refuse an incident not the caller's", (metadata, ctx) -> {
                        EntityInstance found = first(ctx, FOUND);
                        if (found == null || BenefitRules.sideOf(ctx.request().actorId(), found.get("sponsorId"),
                            found.get("providerId")) == null) {
                            throw new EntityNotFoundException(QbBenefits.INCIDENT + " "
                                + ctx.get(INPUT, ResolveInput.class).incidentId() + " not found");
                        }
                    })
                    .step("Take the incident's turn", HoldLock.<ProcessContext>exclusive(
                        ctx -> "qb.benefit-incident:" + first(ctx, FOUND).id()))
                    .step("Load it again", QueryEntities.<ProcessContext>of(
                        QbBenefits.defaultDataset(QbBenefits.INCIDENT),
                        ctx -> BenefitSteps.byId("incidentId", first(ctx, FOUND).id()), INCIDENT))
                    .compute("Resolve it", (metadata, ctx) -> {
                        EntityInstance incident = first(ctx, INCIDENT);
                        if (incident.get("resolvedAt") != null) {
                            ctx.reject(new Violation("incidentId", BenefitCodes.INCIDENT_RESOLVED, "The incident "
                                + "was resolved already", Map.of()));
                            return;
                        }
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("resolution", ctx.get(INPUT, ResolveInput.class).resolution().strip());
                        values.put("resolvedBy", ctx.request().actorId());
                        values.put("resolvedAt", ctx.opTime());
                        ctx.changes().update(QbBenefits.INCIDENT, incident.id(), incident.version(), values);
                        ctx.put(OUTPUT, new IncidentOutput(String.valueOf(incident.id()), true));
                    });
            });
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    /** The purchase under {@link #FOUND}, the caller being either side of it; anybody else finds none (404). */
    private static <I, O> void findAsEitherSide(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, String> purchaseId) {
        pb.step("Find the purchase", QueryEntities.<ProcessContext>of(QbBenefits.defaultDataset(QbBenefits.PURCHASE),
                ctx -> BenefitSteps.byId("purchaseId", purchaseId.apply(ctx)), FOUND))
            .compute("Refuse a purchase not the caller's", (metadata, ctx) -> {
                EntityInstance found = first(ctx, FOUND);
                if (found == null || BenefitRules.sideOf(ctx.request().actorId(), found.get("sponsorId"),
                    found.get("providerId")) == null) {
                    throw new EntityNotFoundException(QbBenefits.PURCHASE + " " + purchaseId.apply(ctx)
                        + " not found");
                }
            });
    }

    private static void requireStatus(EntityInstance purchase, String status) {
        if (!status.equals(purchase.get("status"))) {
            String now = purchase.get("status");
            throw BenefitSteps.refusal(BenefitCodes.PURCHASE_STATE, "The purchase is " + now, Map.of("status", now));
        }
    }
}

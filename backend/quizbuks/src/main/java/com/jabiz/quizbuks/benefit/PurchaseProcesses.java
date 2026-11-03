package com.jabiz.quizbuks.benefit;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.quizbuks.certification.Certifications;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.task.CloseTasks;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static com.jabiz.quizbuks.benefit.BenefitSteps.INPUT;
import static com.jabiz.quizbuks.benefit.BenefitSteps.OUTPUT;
import static com.jabiz.quizbuks.benefit.BenefitSteps.first;

/**
 * Purchase orders (Q89a, D-Q89-5): what a sponsor says it bought from a benefit's provider, and the provider's word
 * that it was paid and will deliver. The platform records both and judges neither: no money moves on it.
 * <ul>
 *   <li>{@value #SAVE}: the sponsor (holding {@code BENEFIT_SPONSOR} for a new one) writes the quantity, the amount
 *       paid and when, the proofs and a note; the benefit as it is now is copied in. A change of one waiting for the
 *       provider or confirmed sends it to the provider again;</li>
 *   <li>{@value #SUBMIT}: a draft with at least one proof goes to the provider, who gets a task;</li>
 *   <li>{@value #CONFIRM} / {@value #DECLINE}: the provider confirms the revision it read, or sends it back with a
 *       reason (the sponsor gets a task);</li>
 *   <li>{@value #CANCEL}: the sponsor withdraws it (Q89b: not while publications draw on it).</li>
 * </ul>
 * A purchase's writes take its turn ({@link QbBenefits#purchaseLock}); a new one has nothing to take turns with.
 */
public final class PurchaseProcesses {

    public static final String SAVE = "QB_BENEFIT_PURCHASE_SAVE";
    public static final String SUBMIT = "QB_BENEFIT_PURCHASE_SUBMIT";
    public static final String CONFIRM = "QB_BENEFIT_PURCHASE_CONFIRM";
    public static final String DECLINE = "QB_BENEFIT_PURCHASE_DECLINE";
    public static final String CANCEL = "QB_BENEFIT_PURCHASE_CANCEL";

    /** The provider's task to answer a purchase, and the sponsor's to change one sent back. */
    public static final String CONFIRM_TASK = "qb.benefit.confirm";
    public static final String CONFIRM_TASK_TITLE = "task.qb.benefit.confirm";
    public static final String RESUBMIT_TASK = "qb.benefit.resubmit";
    public static final String RESUBMIT_TASK_TITLE = "task.qb.benefit.resubmit";

    static final String FOUND = "found";
    static final String PURCHASE = "purchase";
    static final String OFFER = "offer";
    static final String ASKED = "askProvider";
    static final String SENT_BACK = "sentBack";

    /**
     * @param purchaseId   the purchase to change; none for a new one
     * @param offerId      the benefit bought (a new purchase only; a change keeps its benefit)
     * @param quantity     how many were bought
     * @param paidAmount   what the sponsor paid the provider, whole, in the system's currency
     * @param paidOn       the day it paid
     * @param proofs       files of the policy {@code qb.benefit-proof} the caller uploaded (receipts, contracts)
     * @param baseRevision the revision the caller edited; when given and no longer current, 422
     */
    public record SaveInput(String purchaseId, String offerId, @NotNull BigDecimal quantity,
        @NotNull BigDecimal paidAmount, @NotNull LocalDate paidOn, @Size(max = 10) List<UUID> proofs, String note,
        Long baseRevision) {}

    /** @param baseRevision the revision the caller read (required for the provider's answer) */
    public record PurchaseRef(@NotBlank @Size(max = 36) String purchaseId, Long baseRevision) {}

    public record DeclineInput(@NotBlank @Size(max = 36) String purchaseId,
        @NotBlank @Size(max = QbBenefits.REASON_LENGTH) String reason, @NotNull Long baseRevision) {}

    public record PurchaseOutput(String purchaseId, String status, long revision) {}

    private PurchaseProcesses() {}

    // ---- save ------------------------------------------------------------------------------------------------------

    /** @param zone the deployment's zone ({@code jabiz.fiscal-year-zone}): a payment is not dated after its today */
    public static ProcessDefinition<SaveInput, PurchaseOutput, ProcessContext> save(ZoneId zone) {
        return ProcessDefinition.define(SAVE, 1, SaveInput.class, PurchaseOutput.class, ProcessContext.class, pb -> {
            BenefitSteps.common(pb, "Writes a purchase of another sponsor's benefit: how many, what was paid and when, "
                + "with proofs; a change of one sent to the provider sends it again.");
            pb.outputMapper(ctx -> ctx.get(OUTPUT, PurchaseOutput.class));
            BenefitSteps.loadHolding(pb, Certifications.BENEFIT_SPONSOR);
            lockAndLoad(pb, ctx -> saveInput(ctx).purchaseId(), QbBenefits.SPONSOR);
            pb.step("Load the benefit, the provider's ban and the proofs", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbBenefits.defaultDataset(QbBenefits.OFFER), ctx -> BenefitSteps.byId("offerId",
                        first(ctx, PURCHASE) != null ? first(ctx, PURCHASE).get("offerId") : saveInput(ctx).offerId()),
                        OFFER),
                    BenefitSteps.ban(ctx -> first(ctx, PURCHASE) != null ? first(ctx, PURCHASE).get("providerId")
                        : first(ctx, OFFER) == null ? null : first(ctx, OFFER).get("providerId")),
                    BenefitSteps.files(ctx -> BenefitSteps.distinct(saveInput(ctx).proofs()))))
                .compute("Write the purchase", (metadata, ctx) -> saved(ctx, LocalDate.ofInstant(ctx.opTime(), zone)));
            askProvider(pb);
            pb.step("Close the request to change it", CloseTasks.<ProcessContext>done(
                ctx -> resubmitSource(saveInput(ctx).purchaseId() == null ? "new" : saveInput(ctx).purchaseId())));
        });
    }

    static void saved(ProcessContext ctx, LocalDate today) {
        SaveInput input = saveInput(ctx);
        EntityInstance purchase = first(ctx, PURCHASE);
        EntityInstance offer = first(ctx, OFFER);
        if (purchase == null) {
            if (input.offerId() == null || offer == null) {
                throw new EntityNotFoundException(QbBenefits.OFFER + " " + input.offerId());
            }
            if (ctx.request().actorId().equals(offer.get("providerId"))) {
                throw BenefitSteps.refusal(BenefitCodes.OWN_OFFER, "This is your own benefit", Map.of());
            }
            BenefitSteps.requireHolding(ctx, Certifications.BENEFIT_SPONSOR);
            if (!QbBenefits.LISTED.equals(offer.get("status"))) {
                throw BenefitSteps.refusal(BenefitCodes.OFFER_UNAVAILABLE, "The benefit is not offered now", Map.of());
            }
        } else {
            requireStatus(purchase, PurchaseStatus.editable((String) purchase.get("status")));
            AccountEditing.current(ctx, purchase, input.baseRevision(), BenefitCodes.PURCHASE_CHANGED);
        }
        BenefitSteps.refuseBannedOther(ctx);
        List<UUID> proofs = BenefitSteps.distinct(input.proofs());
        BenefitRules.purchaseProblems(new BenefitRules.Purchase(input.quantity(), input.paidAmount(), input.paidOn(),
            proofs.size()), today).forEach(ctx::reject);
        BenefitSteps.requireOwnFiles(ctx, "proofs", proofs, BenefitSteps.stored(purchase, QbBenefits.proofFields()));
        if (ctx.hasViolations()) {
            return;
        }
        String before = purchase == null ? null : (String) purchase.get("status");
        String status = purchase == null ? PurchaseStatus.DRAFT : PurchaseStatus.afterSave(before);
        Map<String, Object> values = new LinkedHashMap<>();
        // The benefit as the provider shows it now: what the provider confirms (a change of it reaches a purchase when
        // the sponsor saves it again, and is confirmed again).
        if (offer != null) {
            values.put("kind", offer.get("kind"));
            values.put("title", offer.get("name"));
            values.put("unitValue", offer.get("unitValue"));
            values.put("pickupAddress", offer.get("pickupAddress"));
            values.put("pickupHours", offer.get("pickupHours"));
            values.put("terms", offer.get("terms"));
        }
        values.put("quantity", input.quantity().setScale(0));
        values.put("paidAmount", input.paidAmount().setScale(0));
        values.put("paidOn", input.paidOn());
        BenefitSteps.putFiles(values, QbBenefits.proofFields(), proofs);
        values.put("note", BenefitSteps.blankToNull(input.note()));
        values.put("status", status);
        if (purchase == null) {
            values.put("offerId", String.valueOf(offer.id()));
            values.put("sponsorId", ctx.request().actorId());
            values.put("providerId", offer.get("providerId"));
        } else if (!PurchaseStatus.PENDING_PROVIDER.equals(before) && PurchaseStatus.PENDING_PROVIDER.equals(status)) {
            values.put("submittedAt", ctx.opTime());
            ctx.put(ASKED, true);
        }
        write(ctx, purchase, values);
    }

    // ---- submit ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<PurchaseRef, PurchaseOutput, ProcessContext> submit() {
        return ProcessDefinition.define(SUBMIT, 1, PurchaseRef.class, PurchaseOutput.class, ProcessContext.class,
            pb -> {
                BenefitSteps.common(pb, "Sends a purchase to its provider to confirm: it was paid, and how many it "
                    + "will provide.");
                pb.outputMapper(ctx -> ctx.get(OUTPUT, PurchaseOutput.class));
                lockAndLoad(pb, ctx -> ref(ctx).purchaseId(), QbBenefits.SPONSOR);
                pb.step("Load the provider's ban", QueryEntities.<ProcessContext>all(
                        BenefitSteps.ban(ctx -> first(ctx, PURCHASE).get("providerId"))))
                    .compute("Send it", (metadata, ctx) -> {
                        EntityInstance purchase = first(ctx, PURCHASE);
                        requireStatus(purchase, PurchaseStatus.submittable((String) purchase.get("status")));
                        AccountEditing.current(ctx, purchase, ref(ctx).baseRevision(), BenefitCodes.PURCHASE_CHANGED);
                        BenefitSteps.refuseBannedOther(ctx);
                        if (BenefitSteps.stored(purchase, QbBenefits.proofFields()).stream()
                            .allMatch(java.util.Objects::isNull)) {
                            ctx.reject(new Violation("proofs", BenefitCodes.PROOF_REQUIRED, "Add a proof of the "
                                + "payment first", Map.of()));
                        }
                        if (ctx.hasViolations()) {
                            return;
                        }
                        ctx.put(ASKED, true);
                        write(ctx, purchase, Map.of("status", PurchaseStatus.PENDING_PROVIDER,
                            "submittedAt", ctx.opTime()));
                    });
                askProvider(pb);
                pb.step("Close the request to change it", CloseTasks.<ProcessContext>done(
                    ctx -> resubmitSource(ref(ctx).purchaseId())));
            });
    }

    // ---- the provider's answer -------------------------------------------------------------------------------------

    public static ProcessDefinition<PurchaseRef, PurchaseOutput, ProcessContext> confirm() {
        return ProcessDefinition.define(CONFIRM, 1, PurchaseRef.class, PurchaseOutput.class, ProcessContext.class,
            pb -> {
                BenefitSteps.common(pb, "Confirms a purchase of the caller's benefit as read: the amount was received "
                    + "and the quantity will be provided.");
                pb.outputMapper(ctx -> ctx.get(OUTPUT, PurchaseOutput.class));
                lockAndLoad(pb, ctx -> ref(ctx).purchaseId(), QbBenefits.PROVIDER);
                pb.compute("Confirm it", (metadata, ctx) -> {
                    EntityInstance purchase = first(ctx, PURCHASE);
                    requireStatus(purchase, PurchaseStatus.answerable((String) purchase.get("status")));
                    if (ref(ctx).baseRevision() == null) {
                        ctx.reject(new Violation("baseRevision", BenefitCodes.PURCHASE_CHANGED, "Confirm the "
                            + "revision you read", Map.of("revision", BenefitSteps.number(purchase.get("revision")))));
                        return;
                    }
                    if (!AccountEditing.current(ctx, purchase, ref(ctx).baseRevision(), BenefitCodes.PURCHASE_CHANGED)) {
                        return;
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("status", PurchaseStatus.CONFIRMED);
                    values.put("confirmedAt", ctx.opTime());
                    values.put("confirmedBy", ctx.request().actorId());
                    values.put("confirmedRevision", purchase.get("revision"));
                    write(ctx, purchase, values);
                });
                pb.step("Close the provider's task", CloseTasks.<ProcessContext>done(
                    ctx -> confirmSource(ref(ctx).purchaseId())));
            });
    }

    public static ProcessDefinition<DeclineInput, PurchaseOutput, ProcessContext> decline() {
        return ProcessDefinition.define(DECLINE, 1, DeclineInput.class, PurchaseOutput.class, ProcessContext.class,
            pb -> {
                BenefitSteps.common(pb, "Sends a purchase of the caller's benefit back to its sponsor, with a reason.");
                pb.outputMapper(ctx -> ctx.get(OUTPUT, PurchaseOutput.class));
                lockAndLoad(pb, ctx -> ctx.get(INPUT, DeclineInput.class).purchaseId(), QbBenefits.PROVIDER);
                pb.compute("Send it back", (metadata, ctx) -> {
                    DeclineInput input = ctx.get(INPUT, DeclineInput.class);
                    EntityInstance purchase = first(ctx, PURCHASE);
                    requireStatus(purchase, PurchaseStatus.answerable((String) purchase.get("status")));
                    if (!AccountEditing.current(ctx, purchase, input.baseRevision(), BenefitCodes.PURCHASE_CHANGED)) {
                        return;
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("status", PurchaseStatus.DRAFT);
                    values.put("declinedAt", ctx.opTime());
                    values.put("declineReason", input.reason().strip());
                    ctx.put(SENT_BACK, true);
                    write(ctx, purchase, values);
                });
                pb.step("Close the provider's task", CloseTasks.<ProcessContext>done(
                    ctx -> confirmSource(ctx.get(INPUT, DeclineInput.class).purchaseId())))
                    .step("Ask the sponsor to change it", CreateTask.<ProcessContext>when(
                        ctx -> ctx.contains(SENT_BACK), ctx -> {
                            EntityInstance purchase = first(ctx, PURCHASE);
                            return TaskSpec.forUser(RESUBMIT_TASK, RESUBMIT_TASK_TITLE,
                                    Map.of("title", String.valueOf((Object) purchase.get("title"))),
                                    String.valueOf((Object) purchase.get("sponsorId")))
                                .about(QbBenefits.PURCHASE, purchase.id())
                                .source(resubmitSource(purchase.id()));
                        }));
            });
    }

    public static ProcessDefinition<PurchaseRef, PurchaseOutput, ProcessContext> cancel() {
        return ProcessDefinition.define(CANCEL, 1, PurchaseRef.class, PurchaseOutput.class, ProcessContext.class,
            pb -> {
                BenefitSteps.common(pb, "Withdraws a purchase of the caller's: it can no longer be used.");
                pb.outputMapper(ctx -> ctx.get(OUTPUT, PurchaseOutput.class));
                lockAndLoad(pb, ctx -> ref(ctx).purchaseId(), QbBenefits.SPONSOR);
                pb.compute("Withdraw it", (metadata, ctx) -> {
                    EntityInstance purchase = first(ctx, PURCHASE);
                    requireStatus(purchase, PurchaseStatus.cancellable((String) purchase.get("status")));
                    if (!AccountEditing.current(ctx, purchase, ref(ctx).baseRevision(), BenefitCodes.PURCHASE_CHANGED)) {
                        return;
                    }
                    write(ctx, purchase, Map.of("status", PurchaseStatus.CANCELLED, "cancelledAt", ctx.opTime()));
                });
                pb.step("Close the provider's task", CloseTasks.<ProcessContext>done(
                        ctx -> confirmSource(ref(ctx).purchaseId())))
                    .step("Close the request to change it", CloseTasks.<ProcessContext>done(
                        ctx -> resubmitSource(ref(ctx).purchaseId())));
            });
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    /**
     * Finds the purchase (none asked: a new one, nothing to read), refuses one the caller is not {@code side} of as
     * not found, takes its turn and reads it again.
     */
    private static <I, O> void lockAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, String> purchaseId, String side) {
        pb.step("Find the purchase", QueryEntities.<ProcessContext>of(QbBenefits.defaultDataset(QbBenefits.PURCHASE),
                ctx -> BenefitSteps.byId("purchaseId", purchaseId.apply(ctx)), FOUND))
            .compute("Refuse a purchase not the caller's", (metadata, ctx) -> {
                if (purchaseId.apply(ctx) == null) {
                    return;
                }
                EntityInstance found = first(ctx, FOUND);
                if (found == null || !side.equals(BenefitRules.sideOf(ctx.request().actorId(), found.get("sponsorId"),
                    found.get("providerId")))) {
                    throw new EntityNotFoundException(QbBenefits.PURCHASE + " " + purchaseId.apply(ctx) + " not found");
                }
            })
            .step("Take the purchase's turn", HoldLock.<ProcessContext>exclusive(
                ctx -> purchaseId.apply(ctx) == null ? null : QbBenefits.purchaseLock(purchaseId.apply(ctx))))
            .step("Load it again", QueryEntities.<ProcessContext>of(QbBenefits.defaultDataset(QbBenefits.PURCHASE),
                ctx -> BenefitSteps.byId("purchaseId", purchaseId.apply(ctx)), PURCHASE));
    }

    /** The provider's task, when the purchase went to it now. */
    private static <I, O> void askProvider(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Ask the provider to confirm", CreateTask.<ProcessContext>when(ctx -> ctx.contains(ASKED), ctx -> {
            PurchaseOutput output = ctx.get(OUTPUT, PurchaseOutput.class);
            EntityInstance purchase = first(ctx, PURCHASE);
            return TaskSpec.forUser(CONFIRM_TASK, CONFIRM_TASK_TITLE,
                    Map.of("title", String.valueOf((Object) purchase.get("title"))),
                    String.valueOf((Object) purchase.get("providerId")))
                .about(QbBenefits.PURCHASE, UUID.fromString(output.purchaseId()))
                .source(confirmSource(output.purchaseId()));
        }));
    }

    static String confirmSource(Object purchaseId) {
        return "qb.benefit-confirm:" + purchaseId;
    }

    static String resubmitSource(Object purchaseId) {
        return "qb.benefit-resubmit:" + purchaseId;
    }

    private static void requireStatus(EntityInstance purchase, boolean allowed) {
        if (!allowed) {
            String status = purchase.get("status");
            throw BenefitSteps.refusal(BenefitCodes.PURCHASE_STATE, "The purchase is " + status,
                Map.of("status", status));
        }
    }

    private static void write(ProcessContext ctx, EntityInstance purchase, Map<String, Object> values) {
        long revision = purchase == null ? 1 : BenefitSteps.number(purchase.get("revision")) + 1;
        Map<String, Object> next = new LinkedHashMap<>(values);
        next.put("revision", BigDecimal.valueOf(revision));
        next.put("editedAt", ctx.opTime());
        Object id;
        if (purchase == null) {
            id = ctx.changes().insert(QbBenefits.PURCHASE, next);
        } else {
            id = purchase.id();
            ctx.changes().update(QbBenefits.PURCHASE, purchase.id(), purchase.version(), next);
        }
        String status = next.containsKey("status") ? (String) next.get("status") : purchase.get("status");
        ctx.put(OUTPUT, new PurchaseOutput(String.valueOf(id), status, revision));
    }

    private static SaveInput saveInput(ProcessContext ctx) {
        return ctx.get(INPUT, SaveInput.class);
    }

    private static PurchaseRef ref(ProcessContext ctx) {
        return ctx.get(INPUT, PurchaseRef.class);
    }
}

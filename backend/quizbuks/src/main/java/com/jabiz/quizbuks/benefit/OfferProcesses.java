package com.jabiz.quizbuks.benefit;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.quizbuks.benefit.BenefitSteps.INPUT;
import static com.jabiz.quizbuks.benefit.BenefitSteps.OUTPUT;

/**
 * A provider's benefits (Q89a, D-Q89-2): {@value #SAVE} writes one, shown in the catalog of every sponsor holding a
 * benefit certification at once (no review: the purchase that uses it is reviewed with the publication, D-Q89-8).
 * Listing a new benefit, or showing one again, needs {@code BENEFIT_PROVIDER}; hiding or correcting a listed one does
 * not, so that a certification running out never keeps a provider from taking its benefit off the catalog. Images the
 * provider no longer uses are left to the platform's sweep.
 */
public final class OfferProcesses {

    public static final String SAVE = "QB_BENEFIT_OFFER_SAVE";

    static final String OFFERS = "offers";

    /**
     * @param offerId      the benefit to change; none for a new one
     * @param kind         {@code GOODS}, {@code SERVICE} or {@code DIGITAL}
     * @param images       files of the policy {@code qb.benefit-image} the caller uploaded, in order; none: none
     * @param unitValue    the market value of one, whole, in the system's currency
     * @param listed       whether the catalog shows it (one taken down stays hidden, whatever this says)
     * @param baseRevision the revision the caller edited; when given and no longer current, 422
     */
    public record SaveInput(String offerId, @NotBlank String kind, @NotBlank String name, @NotBlank String description,
        @Size(max = 10) List<UUID> images, @NotNull BigDecimal unitValue, String pickupAddress, String pickupHours,
        String terms, boolean listed, Long baseRevision) {}

    public record OfferOutput(String offerId, String status, long revision) {}

    private OfferProcesses() {}

    public static ProcessDefinition<SaveInput, OfferOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, SaveInput.class, OfferOutput.class, ProcessContext.class, pb -> {
            BenefitSteps.common(pb, "Saves one of the caller's benefits: what it is, what one is worth, where and when "
                + "it is collected; shown to the sponsors holding a benefit certification at once.");
            pb.outputMapper(ctx -> ctx.get(OUTPUT, OfferOutput.class));
            BenefitSteps.loadGate(pb, QbParams.TREE_GATE_BENEFIT_PROVIDER);
            pb.step("Take the provider's turn", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbBenefits.offerLock(ctx.request().actorId())))
                .step("Load the provider's benefits and the images", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbBenefits.defaultDataset(QbBenefits.OFFER), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.Eq("providerId", ctx.request().actorId()))
                        .limit(QbBenefits.MAX_OFFERS + 1).build(), OFFERS),
                    BenefitSteps.files(ctx -> BenefitSteps.distinct(input(ctx).images()))))
                .compute("Write the benefit", (metadata, ctx) -> saved(ctx));
        });
    }

    static void saved(ProcessContext ctx) {
        SaveInput input = input(ctx);
        List<EntityInstance> offers = BenefitSteps.list(ctx, OFFERS);
        EntityInstance offer = null;
        if (input.offerId() != null) {
            offer = offers.stream().filter(row -> input.offerId().equals(String.valueOf(row.id()))).findFirst()
                .orElseThrow(() -> new EntityNotFoundException(QbBenefits.OFFER + " " + input.offerId()));
            AccountEditing.current(ctx, offer, input.baseRevision(), BenefitCodes.OFFER_CHANGED);
        } else if (offers.size() >= QbBenefits.MAX_OFFERS) {
            ctx.reject(new Violation(null, BenefitCodes.OFFER_LIMIT, "A provider lists at most "
                + QbBenefits.MAX_OFFERS + " benefits", Map.of("max", QbBenefits.MAX_OFFERS)));
        }
        // An unknown kind is the platform's to refuse (the field is a code of the dictionary): 400.
        boolean takenDown = offer != null && QbBenefits.TAKEN_DOWN.equals(offer.get("status"));
        String status = takenDown ? QbBenefits.TAKEN_DOWN : input.listed() ? QbBenefits.LISTED : QbBenefits.UNLISTED;
        // A new benefit, and one shown again, need the certification; hiding or correcting one does not.
        boolean showing = QbBenefits.LISTED.equals(status)
            && (offer == null || !QbBenefits.LISTED.equals(offer.get("status")));
        if (offer == null || showing) {
            BenefitSteps.requireGate(ctx, QbParams.TREE_GATE_BENEFIT_PROVIDER);
        }
        List<UUID> images = BenefitSteps.distinct(input.images());
        String address = BenefitSteps.blankToNull(input.pickupAddress());
        String hours = BenefitSteps.blankToNull(input.pickupHours());
        BenefitRules.offerProblems(new BenefitRules.Offer(input.kind(), input.unitValue(), address, hours,
            images.size())).forEach(ctx::reject);
        BenefitSteps.requireOwnFiles(ctx, "images", images, BenefitSteps.stored(offer, QbBenefits.imageFields()));
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", input.kind());
        values.put("name", input.name().strip());
        values.put("description", input.description().strip());
        BenefitSteps.putFiles(values, QbBenefits.imageFields(), images);
        values.put("unitValue", input.unitValue().setScale(0));
        // Where and when only goods and services are collected.
        boolean digital = QbBenefits.DIGITAL.equals(input.kind());
        values.put("pickupAddress", digital ? null : address);
        values.put("pickupHours", digital ? null : hours);
        values.put("terms", BenefitSteps.blankToNull(input.terms()));
        values.put("status", status);
        long revision = offer == null ? 1 : BenefitSteps.number(offer.get("revision")) + 1;
        values.put("revision", BigDecimal.valueOf(revision));
        values.put("editedAt", ctx.opTime());
        Object id;
        if (offer == null) {
            values.put("providerId", ctx.request().actorId());
            id = ctx.changes().insert(QbBenefits.OFFER, values);
        } else {
            id = offer.id();
            ctx.changes().update(QbBenefits.OFFER, offer.id(), offer.version(), values);
        }
        ctx.put(OUTPUT, new OfferOutput(String.valueOf(id), status, revision));
    }

    private static SaveInput input(ProcessContext ctx) {
        return ctx.get(INPUT, SaveInput.class);
    }
}

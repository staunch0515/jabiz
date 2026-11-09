package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.exposure.ExposureCodes;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.jabiz.quizbuks.publishing.PublicationEditing.OUTPUT;

/**
 * A publication's purchased exposures (F-S13, docs/quizbuks/plans/impl/F-S13.md; decision A of 2026-11-09):
 * <ul>
 *   <li>{@value #PURCHASED_SET}: the only way to write {@code exposurePurchased}, a sub-process for F-M8's purchase and
 *       top-up flows (none of the sponsors' pages or the administrators' calls it): the total bought, or none for
 *       unlimited, on the publication and its card when it went on. A draft (F-M8 buys at the submission) or a
 *       publication on; an ended one is refused. Under the publication's lock only: it changes no state, so no start
 *       waits on it;</li>
 *   <li>{@value #END}: one item of the minute's job {@code qb.exposure-ends}, as the system: ends a publication on whose
 *       successes reached its purchased exposures, under its locks as every move of a publication on, with the end
 *       reason {@value PublicationStatus#EXPOSURE}. Attempts in progress complete as after any end; the sponsor is
 *       mailed as for any end. One that ended meanwhile, or whose purchase was raised since the job read it, is left as
 *       it is (the job's template is read again under the locks).</li>
 * </ul>
 */
public final class PublicationExposure {

    public static final String PURCHASED_SET = "QB_EXPOSURE_PURCHASED_SET";
    public static final String END = "QB_PUBLICATION_EXPOSURE_END";
    /** The items of the job: publications on whose successes reached their purchase (also read under the locks). */
    public static final String ENDS_TEMPLATE = "qb.system.exposure-ends";
    /** The most exposures one publication buys. */
    public static final long MAX_PURCHASED = 999_999_999L;

    static final String USED_UP = "usedUp";

    /**
     * @param publicationId the publication
     * @param purchased     the exposures bought in all; none: unlimited
     */
    public record PurchasedInput(@NotNull UUID publicationId, @Min(1) @Max(MAX_PURCHASED) Long purchased) {}

    public record EndInput(@NotNull UUID publicationId) {}

    private PublicationExposure() {}

    // ---- the purchase ------------------------------------------------------------------------------------------------

    public static ProcessDefinition<PurchasedInput, PublicationOutput, ProcessContext> purchasedSet() {
        return ProcessDefinition.define(PURCHASED_SET, 1, PurchasedInput.class, PublicationOutput.class,
            ProcessContext.class, pb -> pb
                .description("Sets the exposures bought for a publication using another's shared video (part of a "
                    + "purchase only).")
                .permissions(QbPermissions.PUBLICATION_SYSTEM)
                .subProcessOnly()
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class))
                .steps(PublicationEditing::lockAndLoadAny)
                .compute("Set the purchased exposures", (metadata, ctx) -> setPurchased(ctx)));
    }

    static void setPurchased(ProcessContext ctx) {
        PurchasedInput input = ctx.get(PublicationEditing.INPUT, PurchasedInput.class);
        EntityInstance publication = PublicationEditing.publication(ctx);
        if (PublicationStatus.ENDED.equals(publication.get("status"))) {
            ctx.reject(new Violation("publicationId", ExposureCodes.PUBLICATION_ENDED, "The publication ended: its "
                + "exposures are no longer bought", Map.of()));
            return;
        }
        BigDecimal wanted = input.purchased() == null ? null : BigDecimal.valueOf(input.purchased());
        if (PublicationEditing.same(publication.get("exposurePurchased"), wanted)) {
            PublicationEditing.unchanged(ctx, publication);
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("exposurePurchased", wanted);
        PublicationEditing.write(ctx, publication, values);
        EntityInstance card = PublicationEditing.first(ctx, PublicationEditing.CARD);
        if (card != null && !PublicationEditing.same(card.get("exposurePurchased"), wanted)) {
            Map<String, Object> cardValues = new LinkedHashMap<>();
            cardValues.put("exposurePurchased", wanted);
            ctx.changes().update(QbPublications.CARD, card.id(), card.version(), cardValues);
        }
    }

    // ---- the end -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<EndInput, PublicationOutput, ProcessContext> end() {
        return ProcessDefinition.define(END, 1, EndInput.class, PublicationOutput.class, ProcessContext.class,
            pb -> pb
                .description("Ends a publication whose purchased exposures are used up.")
                .permissions(QbPermissions.PUBLICATION_SYSTEM)
                .internal()
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, PublicationOutput.class)
                    : new PublicationOutput(ctx.get(PublicationEditing.INPUT, EndInput.class).publicationId(), null, 0,
                        null, null))
                .steps(PublicationEditing::lockStatesAndLoadAny)
                .step("Count its successes again", RunTemplate.<ProcessContext>of(ENDS_TEMPLATE,
                    ctx -> Map.of("publicationId", String.valueOf(ctx.get(PublicationEditing.PUB_ID))), USED_UP))
                .compute("End", (metadata, ctx) -> ended(ctx))
                .steps(PublicationNotices::announce));
    }

    @SuppressWarnings("unchecked")
    static void ended(ProcessContext ctx) {
        EntityInstance publication = PublicationEditing.publication(ctx);
        List<Map<String, Object>> rows = ctx.contains(USED_UP) ? (List<Map<String, Object>>) ctx.get(USED_UP)
            : List.of();
        String id = String.valueOf(publication.id());
        boolean usedUp = rows.stream().anyMatch(row -> Objects.equals(id, String.valueOf(row.get("publicationId"))));
        if (!usedUp || !PublicationAdmin.ENDABLE.contains(String.valueOf((Object) publication.get("status")))) {
            PublicationEditing.unchanged(ctx, publication);
            return;
        }
        // F-M8: what goes beyond the purchase (exposures counted while this ran) is recorded but not charged.
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", PublicationStatus.ENDED);
        values.put("endedAt", ctx.opTime());
        values.put("endReason", PublicationStatus.EXPOSURE);
        PublicationEditing.write(ctx, publication, values);
    }
}

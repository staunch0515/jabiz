package com.jabiz.quizbuks.benefit;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.quizbuks.benefit.BenefitSteps.INPUT;
import static com.jabiz.quizbuks.benefit.BenefitSteps.first;

/**
 * {@value #RELEASE}: an open allocation whose publication is back to its draft (withdrawn, sent back, its review run
 * out) or ended gives its share back to the purchase (D-Q89-7): a draft's is removed (its next submission takes it
 * anew), an ended one's keeps how many it gave ({@code used} = its winners). The item of the job
 * {@value QbBenefits#RELEASE_JOB}: one transaction each, under the purchase's lock, the publication read after it (a
 * submission holds the purchase's lock while it takes its share), so a publication submitted meanwhile keeps its own.
 */
public final class AllocationProcesses {

    public static final String RELEASE = "QB_BENEFIT_ALLOCATION_RELEASE";

    static final String FOUND = "found";
    static final String ALLOCATION = "allocation";
    static final String PUBLICATION = "publication";
    static final String COUNTER = "counter";
    static final String OUTPUT = "output";

    public record AllocationRef(@NotBlank @Size(max = 36) String allocationId) {}

    /** @param status the allocation's status after the process; null when it was removed or not found */
    public record ReleaseOutput(String allocationId, String status) {}

    private AllocationProcesses() {}

    public static ProcessDefinition<AllocationRef, ReleaseOutput, ProcessContext> release() {
        return ProcessDefinition.define(RELEASE, 1, AllocationRef.class, ReleaseOutput.class, ProcessContext.class,
            pb -> pb
                .description("Gives a publication's share of its purchase back when the publication is back to its "
                    + "draft or ended (the item of the job " + QbBenefits.RELEASE_JOB + ").")
                .permissions(QbPermissions.BENEFIT_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, ReleaseOutput.class)
                    : new ReleaseOutput(ctx.get(INPUT, AllocationRef.class).allocationId(), null))
                .step("Find the allocation", QueryEntities.<ProcessContext>of(
                    QbBenefits.defaultDataset(QbBenefits.ALLOCATION), ctx -> byId(ctx), FOUND))
                .step("Take the purchase's turn", HoldLock.<ProcessContext>exclusive(ctx -> first(ctx, FOUND) == null
                    ? null : QbBenefits.purchaseLock(first(ctx, FOUND).get("purchaseId"))))
                .step("Load the allocation, its publication and counters", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbBenefits.defaultDataset(QbBenefits.ALLOCATION), ctx -> byId(ctx), ALLOCATION),
                    QueryEntities.query(QbPublications.defaultDataset(QbPublications.PUBLICATION),
                        ctx -> byPublication(ctx, "publicationId"), PUBLICATION),
                    QueryEntities.query(QbPublications.defaultDataset(QbPublications.COUNTER),
                        ctx -> byPublication(ctx, "publicationId"), COUNTER)))
                .compute("Give the share back", (metadata, ctx) -> released(ctx)));
    }

    static void released(ProcessContext ctx) {
        EntityInstance allocation = first(ctx, ALLOCATION);
        EntityInstance publication = first(ctx, PUBLICATION);
        if (allocation == null || publication == null || !QbBenefits.OPEN.equals(allocation.get("status"))) {
            return;
        }
        String status = publication.get("status");
        if (PublicationStatus.DRAFT.equals(status)) {
            ctx.changes().delete(QbBenefits.ALLOCATION, allocation.id(), allocation.version());
            ctx.put(OUTPUT, new ReleaseOutput(String.valueOf(allocation.id()), null));
        } else if (PublicationStatus.ENDED.equals(status)) {
            EntityInstance counter = first(ctx, COUNTER);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("status", QbBenefits.RELEASED);
            values.put("used", counter == null ? BigDecimal.ZERO
                : new BigDecimal(String.valueOf((Object) counter.get("winners"))));
            values.put("releasedAt", ctx.opTime());
            ctx.changes().update(QbBenefits.ALLOCATION, allocation.id(), allocation.version(), values);
            ctx.put(OUTPUT, new ReleaseOutput(String.valueOf(allocation.id()), QbBenefits.RELEASED));
        }
    }

    private static com.jabiz.query.EntityQuery byId(ProcessContext ctx) {
        return BenefitSteps.byId("allocationId", ctx.get(INPUT, AllocationRef.class).allocationId());
    }

    private static com.jabiz.query.EntityQuery byPublication(ProcessContext ctx, String field) {
        EntityInstance found = first(ctx, FOUND);
        return com.jabiz.query.EntityQuery.builder().where(new com.jabiz.query.QueryPredicate.In(field,
            found == null ? List.of() : List.of(found.get("publicationId")))).limit(1).build();
    }
}

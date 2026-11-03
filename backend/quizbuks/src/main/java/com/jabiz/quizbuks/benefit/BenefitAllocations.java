package com.jabiz.quizbuks.benefit;

import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.EntityInstance;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The publications' shares of a purchase (Q89b-1, D-Q89-7), written under the purchase's lock: a publication takes
 * its {@code maxWinners} when it is submitted (again after a withdrawal, the same row), and gives it back when it is
 * back to its draft or ended ({@link AllocationProcesses}). The quota itself stays the publication's: the answering
 * path reads nothing of it.
 */
public final class BenefitAllocations {

    private BenefitAllocations() {}

    public static BenefitPublishing.Allocation of(EntityInstance row) {
        return new BenefitPublishing.Allocation(String.valueOf((Object) row.get("publicationId")),
            QbBenefits.OPEN.equals(row.get("status")), whole(row.get("quantity")), whole(row.get("used")));
    }

    /**
     * The submitted {@code publication}'s share of its purchase: open, holding its quota. The submission checked that
     * enough is left (BenefitPublishing.problems); nothing when it draws on none or was refused.
     */
    public static void take(ProcessContext ctx, EntityInstance publication, EntityInstance purchase,
        List<EntityInstance> allocations) {
        if (publication.get("benefitPurchaseId") == null || purchase == null || ctx.hasViolations()) {
            return;
        }
        BigDecimal quantity = new BigDecimal(String.valueOf((Object) publication.get("maxWinners")));
        EntityInstance own = allocations.stream()
            .filter(a -> String.valueOf((Object) a.get("publicationId")).equals(String.valueOf(publication.id())))
            .findFirst().orElse(null);
        if (own != null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("quantity", quantity);
            values.put("status", QbBenefits.OPEN);
            values.put("used", null);
            values.put("releasedAt", null);
            values.put("allocatedAt", ctx.opTime());
            ctx.changes().update(QbBenefits.ALLOCATION, own.id(), own.version(), values);
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("purchaseId", String.valueOf(purchase.id()));
        values.put("publicationId", publication.id());
        values.put("sponsorId", purchase.get("sponsorId"));
        values.put("providerId", purchase.get("providerId"));
        values.put("quantity", quantity);
        values.put("status", QbBenefits.OPEN);
        values.put("allocatedAt", ctx.opTime());
        ctx.changes().insert(QbBenefits.ALLOCATION, values);
    }

    static long whole(Object value) {
        return value == null ? 0 : new BigDecimal(value.toString()).longValue();
    }
}

package com.jabiz.quizbuks.fee;

import com.jabiz.process.ProcessContext;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The cash lots (F-M1, {@link QbFeeAccount#CASH_LOT}): every process that moves account 2160 for a sponsor moves the
 * lots with it in the same transaction, under the sponsor's wallet lock it already holds, so that 2160 for the sponsor
 * is always the sum of their lots' remaining amounts (the daily check {@code CASH_LOTS}). Money in is a new lot
 * ({@link #add}); money out other than a fee takes from the lots in the order they arrived ({@link #take}); a fee is
 * paid by {@code QB_FEE_SETTLE} ({@link FeeSettlement}).
 */
public final class CashLots {

    /** How many open lots one payment reads: more than any sponsor has open; the oldest are spent first. */
    public static final int MAX_OPEN = 1000;

    private CashLots() {}

    /** The query of a sponsor's lots with money left, the oldest first. */
    public static EntityQuery open(Object sponsorId) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.In("sponsorId", FeeGate.ids(sponsorId)),
                new QueryPredicate.Gt("remaining", BigDecimal.ZERO))))
            .orderBy("arrivedAt", true).limit(MAX_OPEN).build();
    }

    /** The dataset the lots are read from (the processes' own, not readable by any role). */
    public static String dataset() {
        return QbFeeAccount.defaultDataset(QbFeeAccount.CASH_LOT);
    }

    /** Money that arrived in the cash account: a lot of its own, arrived now, with the record it came with. */
    public static Object add(ProcessContext ctx, Object sponsorId, String source, BigDecimal amount,
        String sourceEntity, Object sourceId) {
        if (amount == null || amount.signum() <= 0) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sponsorId", String.valueOf(sponsorId));
        values.put("source", source);
        values.put("amount", amount);
        values.put("remaining", amount);
        values.put("arrivedAt", ctx.opTime());
        values.put("sourceEntity", sourceEntity);
        values.put("sourceId", sourceId == null ? null : String.valueOf(sourceId));
        return ctx.changes().insert(QbFeeAccount.CASH_LOT, values);
    }

    /**
     * Money taken out of the cash account other than by a fee: from {@code lots} (as {@link #open} read them) in the
     * order they arrived, the lot that came with {@code firstSourceId} first (a top-up taken back takes its own money
     * first).
     */
    public static void take(ProcessContext ctx, List<EntityInstance> lots, BigDecimal amount, Object firstSourceId) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        String first = null;
        for (EntityInstance lot : lots) {
            if (firstSourceId != null && String.valueOf(firstSourceId).equals(lot.get("sourceId"))) {
                first = String.valueOf(lot.id());
                break;
            }
        }
        List<FeeAllocation.Part> parts = FeeAllocation.takeCash(cash(lots), amount, first);
        Map<String, EntityInstance> byId = new LinkedHashMap<>();
        lots.forEach(lot -> byId.put(String.valueOf(lot.id()), lot));
        for (FeeAllocation.Part part : parts) {
            changeRemaining(ctx, byId.get(part.lotId()), part.amount().negate());
        }
    }

    /**
     * F-M4: {@link #take} from the lots that did not come from {@code source} only (buying Kudos never takes what an
     * operator moved to its direct Sponsor: that money pays the platform's fees only).
     */
    public static void takeExcept(ProcessContext ctx, List<EntityInstance> lots, BigDecimal amount, String source) {
        take(ctx, lots.stream().filter(lot -> !source.equals(lot.get("source"))).toList(), amount, null);
    }

    /** What is left of the lots that came from {@code source}. */
    public static BigDecimal remainingOf(List<EntityInstance> lots, String source) {
        return lots.stream().filter(lot -> source.equals(lot.get("source")))
            .map(lot -> FeeGate.money(lot.get("remaining"))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The lots as the order sees them. */
    public static List<FeeAllocation.Cash> cash(List<EntityInstance> lots) {
        return lots.stream().map(lot -> new FeeAllocation.Cash(String.valueOf(lot.id()), FeeGate.money(lot.get("remaining")),
            (Instant) lot.get("arrivedAt"))).toList();
    }

    /** Adds {@code delta} to a lot's remaining amount (negative: taken, positive: given back). */
    static void changeRemaining(ProcessContext ctx, EntityInstance lot, BigDecimal delta) {
        if (lot == null || delta.signum() == 0) {
            return;
        }
        ctx.changes().update(QbFeeAccount.CASH_LOT, lot.id(), lot.version(),
            Map.of("remaining", FeeGate.money(lot.get("remaining")).add(delta)));
    }
}

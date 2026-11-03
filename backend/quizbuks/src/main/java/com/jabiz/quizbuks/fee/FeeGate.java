package com.jabiz.quizbuks.fee;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Whether unpaid platform fees stop a sponsor (Q87, D-Q87-5, D-Q87-7): what converting cash to Kudos and submitting a
 * publication check, with the controlled switch {@code qb.fee.overdue.block-submit}. One statement reads the sponsor's
 * row (their billing mode and credit limit) and their open invoices; {@link FeeRules.Standing} decides.
 */
public final class FeeGate {

    static final String SPONSOR = "feeGate.sponsor";
    static final String UNPAID = "feeGate.unpaid";
    /** How many unpaid invoices are read: more than any sponsor has (one a month, or one a charge). */
    static final int MAX_UNPAID = 200;

    private FeeGate() {}

    /** Reads the sponsor's billing and their unpaid invoices (one statement). */
    public static <I, O> void load(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Object> sponsorId) {
        pb.step("Load the sponsor's billing and unpaid fees", QueryEntities.<ProcessContext>all(
            QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> EntityQuery.builder()
                .where(new QueryPredicate.In("userId", ids(sponsorId.apply(ctx)))).limit(1).build(), SPONSOR),
            QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE), ctx -> EntityQuery.builder()
                .where(new QueryPredicate.And(List.of(
                    new QueryPredicate.In("sponsorId", ids(sponsorId.apply(ctx))),
                    new QueryPredicate.In("status", new ArrayList<Object>(QbFees.UNPAID)))))
                .limit(MAX_UNPAID).build(), UNPAID)));
    }

    /** Where the sponsor stands, from what {@link #load} read and the default billing mode. */
    public static FeeRules.Standing standing(ProcessContext ctx, String defaultBillingMode) {
        EntityInstance sponsor = first(ctx, SPONSOR);
        BigDecimal unpaid = BigDecimal.ZERO;
        boolean overdue = false;
        for (EntityInstance invoice : list(ctx, UNPAID)) {
            unpaid = unpaid.add(money(invoice.get("total")));
            overdue |= QbFees.OVERDUE.equals(invoice.get("status"));
        }
        return new FeeRules.Standing(FeeRules.billingModeOf(sponsor == null ? null : sponsor.get("billingMode"),
            defaultBillingMode), sponsor == null ? null : money(sponsor.get("creditLimit")), unpaid, overdue,
            list(ctx, UNPAID).size() >= MAX_UNPAID);
    }

    /**
     * Why the sponsor is stopped ({@code 422 QB_FEE_UNPAID}), or null: nothing when the switch is off, or when what
     * is unpaid is within what their billing allows.
     */
    public static Violation refusal(ProcessContext ctx, Boolean blockSwitch, String defaultBillingMode) {
        if (!Boolean.TRUE.equals(blockSwitch)) {
            return null;
        }
        FeeRules.Standing standing = standing(ctx, defaultBillingMode);
        if (!standing.blocked()) {
            return null;
        }
        return new Violation(null, FeeCodes.FEE_UNPAID, "Platform fees are unpaid: pay the invoices first",
            Map.of("unpaid", standing.unpaid().toPlainString(), "billingMode", standing.billingMode(),
                "overdue", String.valueOf(standing.overdue())));
    }

    /** The parameters the gate reads, for a process's {@code LoadParams}. */
    public static List<String> params() {
        return List.of(QbParams.FEE_OVERDUE_BLOCK, QbParams.FEE_DEFAULT_BILLING_MODE);
    }

    static List<Object> ids(Object id) {
        List<Object> ids = new ArrayList<>();
        if (id != null) {
            ids.add(String.valueOf(id));
        }
        return ids;
    }

    static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(key);
        return rows == null ? List.of() : rows;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> rows = list(ctx, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}

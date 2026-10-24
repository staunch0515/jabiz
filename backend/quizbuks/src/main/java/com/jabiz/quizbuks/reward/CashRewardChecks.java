package com.jabiz.quizbuks.reward;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.util.List;
import java.util.Map;

/**
 * The check on a taker before a new attempt (Q22, docs/quizbuks/plans/Q22-reward-methods.md §2): one with a cash
 * reward they neither confirmed nor disputed in time ({@link CashRewardStatus#OVERDUE}) starts no new attempt until
 * they do (422 {@code QB_CASH_CONFIRM_DUE}); attempts in progress and everything else go on. Read through the taker's
 * own dataset, without a lock: confirming lifts it at once.
 */
public final class CashRewardChecks {

    static final String OVERDUE = "overdueCashRewards";

    /** Adds the steps that refuse a caller with an overdue cash reward. */
    public static <I, O> void refuseOverdue(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the caller's overdue cash rewards", QueryEntities.<ProcessContext>of(
                // (The caller's own view already narrows to them; the IN on the immutable user lets the current
                // version be found among their records alone, not the whole table's.)
                QbRewards.takerDataset(QbRewards.CASH_REWARD), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("userId", List.of(ctx.request().actorId())),
                        new QueryPredicate.Eq("status", CashRewardStatus.OVERDUE)))).limit(1).build(), OVERDUE))
            .compute("Refuse a caller with an overdue cash reward", (metadata, ctx) -> {
                @SuppressWarnings("unchecked")
                List<EntityInstance> overdue = (List<EntityInstance>) ctx.get(OVERDUE);
                if (overdue != null && !overdue.isEmpty()) {
                    throw new BusinessRuleViolationException(overdue(overdue.getFirst()));
                }
            });
    }

    /** The refusal, naming the reward to settle. */
    public static Violation overdue(EntityInstance reward) {
        return new Violation(null, RewardCodes.CASH_CONFIRM_DUE, "Confirm or dispute the cash reward "
            + reward.get("code") + " before starting another quiz",
            Map.of("cashRewardId", String.valueOf(reward.id()), "code", String.valueOf((Object) reward.get("code"))));
    }

    private CashRewardChecks() {}
}

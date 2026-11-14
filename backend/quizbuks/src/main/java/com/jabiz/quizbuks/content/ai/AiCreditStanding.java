package com.jabiz.quizbuks.content.ai;

import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.AiFeeRules;
import com.jabiz.quizbuks.fee.Prepayments;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * F-M9b (docs/quizbuks/plans/impl/F-M9.md §7): where the caller's AI credit stands, as the requests and the return read
 * it, for the AI credit page, the AI panels and the "AI credit short" note: what a new request can use now, what may be
 * given back, and whether it is below {@code qb.ai.credit.low-amount}. Read only: no lock, no write; the page shows these
 * numbers and works out none of its own (coordinator 2026-11-14).
 */
public final class AiCreditStanding {

    public static final String STANDING = "QB_AI_CREDIT_STANDING";

    static final String PARAMS = "params";
    static final String OUTPUT = "output";

    private AiCreditStanding() {
    }

    /** No input: the caller's own. */
    public record StandingInput() {}

    /**
     * @param feeOn           whether requests are charged now (qb.ai.fee.enabled)
     * @param remaining       what the AI credit holds
     * @param reserved        what the requests still running hold
     * @param unbilledWithTax the fees of finished requests not charged yet, with their tax (null when the tax rate is not
     *                        set and there are some)
     * @param available       what a new request can use now (as {@link AiFees#quote} reads it: the expired or voided
     *                        granted credit left out); null as {@code unbilledWithTax}
     * @param returnable      what may be given back now (as the return reads it); null as {@code unbilledWithTax}
     * @param minPurchase     the least purchase, null when none is set
     * @param lowAmount       qb.ai.credit.low-amount, null when not set (then never low)
     * @param low             the fee on, the amount set, and what is available below it
     */
    public record StandingOutput(boolean feeOn, BigDecimal remaining, BigDecimal reserved,
        BigDecimal unbilledWithTax, BigDecimal available, BigDecimal returnable, BigDecimal minPurchase,
        BigDecimal lowAmount, boolean low) {}

    public static ProcessDefinition<StandingInput, StandingOutput, ProcessContext> standing() {
        return ProcessDefinition.define(STANDING, 1, StandingInput.class, StandingOutput.class, ProcessContext.class,
            pb -> pb
                .description("Where the caller's AI credit stands: what a request can use, what may be given back, and "
                    + "whether it is low; writing nothing.")
                .permissions(QbPermissions.AI_USE)
                .contextFactory((start, input) -> new ProcessContext(start))
                .outputMapper(ctx -> ctx.get(OUTPUT, StandingOutput.class))
                .step("Load the parameters", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    QbParams.FEE_TAX_RATE, QbParams.AI_FEE_ENABLED, QbParams.AI_CREDIT_MIN_PURCHASE,
                    QbParams.AI_CREDIT_LOW_AMOUNT))
                .step("Read where the sponsor stands", QueryEntities.<ProcessContext>all(
                    AiFees.queries(ctx -> ctx.request().actorId(), false).toArray(QueryEntities.Metadata[]::new)))
                .step("Read what the AI credit holds by lot", RunTemplate.<ProcessContext>when(AiFees::holdsCredit,
                    Prepayments.HELD_TEMPLATE, AiFees::lotsParams, Prepayments.HELD_LOTS))
                .compute("Answer", (metadata, ctx) -> ctx.put(OUTPUT, answer(ctx))));
    }

    static StandingOutput answer(ProcessContext ctx) {
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        boolean feeOn = Boolean.TRUE.equals(params.find(QbParams.AI_FEE_ENABLED, Boolean.class).orElse(false));
        Optional<BigDecimal> taxRate = params.find(QbParams.FEE_TAX_RATE, BigDecimal.class);
        BigDecimal minPurchase = params.find(QbParams.AI_CREDIT_MIN_PURCHASE, BigDecimal.class).orElse(null);
        BigDecimal lowAmount = params.find(QbParams.AI_CREDIT_LOW_AMOUNT, BigDecimal.class).orElse(null);
        AiFees.Standing standing = AiFees.standing(ctx, null);
        if (taxRate.isEmpty() && !standing.unbilled().isEmpty()) {
            // What the waiting fees hold with tax is not known (the return is refused for it too).
            return new StandingOutput(feeOn, standing.remaining(), standing.reserved(), null, null, null, minPurchase,
                lowAmount, false);
        }
        BigDecimal rate = taxRate.orElse(BigDecimal.ZERO);
        BigDecimal occupied = standing.occupied(rate);
        BigDecimal usable = AiFeeRules.usable(Prepayments.aiLots(ctx), occupied, ctx.opTime());
        BigDecimal available = standing.available(rate).min(usable);
        boolean low = feeOn && lowAmount != null && available.compareTo(lowAmount) < 0;
        return new StandingOutput(feeOn, standing.remaining(), standing.reserved(), occupied.subtract(
            standing.reserved()), available, usable, minPurchase, lowAmount, low);
    }
}

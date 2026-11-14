package com.jabiz.quizbuks.content.ai;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.AiCreditGiveBack;
import com.jabiz.quizbuks.fee.AiCredits;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * F-M9a-2 (docs/quizbuks/plans/impl/F-M9.md §4 items 4 and 5): what leaves a sponsor's AI credit other than a charge.
 *
 * <ul>
 *   <li>{@value #RETURN}: the sponsor gives back part of their credit (an amount, or all that may be given back) to
 *       the account it was bought from. Allowed while the AI fee is off, refused while banned; a verified email
 *       is needed, as for every write of a sponsor (a buyer had one). A request key used before is refused ({@link FeeCodes#AI_CREDIT_REPEATED}).</li>
 *   <li>{@value #EXPIRE_ITEM}: an item of the daily job {@value #EXPIRE_JOB} (03:47 in the fiscal year's zone), for a
 *       sponsor whose credit holds expired fee credit ({@value #EXPIRE_DUE_TEMPLATE}): it is voided, but for what the
 *       open jobs hold (the month's charge spends it first; a later run voids what is left).</li>
 * </ul>
 *
 * <p>Both under the sponsor's AI lock and then their wallet lock (plan §4 item 7: never everybody's AI lock), so what
 * the open jobs hold, read under the AI lock, cannot grow meanwhile; the work is {@link AiCreditGiveBack}'s.
 */
public final class AiCreditReturns {

    public static final String RETURN = "QB_AI_CREDIT_RETURN";
    public static final String EXPIRE_ITEM = "QB_AI_CREDIT_EXPIRE_ITEM";
    public static final String EXPIRE_JOB = "qb.ai-credit-expire";
    public static final String EXPIRE_DUE_TEMPLATE = "qb.system.ai-credit-expire-due";
    /** The cron of {@value #EXPIRE_JOB}: every day at 03:47 (the property {@code quizbuks.ai-credit-expire.cron}). */
    public static final String EXPIRE_CRON = "0 47 3 * * *";

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String SAME_REQUEST = "sameRequest";
    static final String GIVE_BACK = "giveBack";
    static final String GIVEN_BACK = "givenBack";

    private AiCreditReturns() {}

    /**
     * @param amount     whole yen; none: all that may be given back now
     * @param requestKey the page's key for this return, as for a purchase
     */
    public record ReturnInput(@DecimalMin("1") @DecimalMax(FeeProcesses.MAX_AMOUNT) @Digits(integer = 13,
        fraction = 0) BigDecimal amount, @NotBlank @Size(max = AiCredits.REQUEST_KEY_LENGTH) String requestKey) {}

    /** @param sponsorId the sponsor whose credit holds expired fee credit */
    public record ExpireRef(@NotBlank @Size(max = 128) String sponsorId) {}

    public static ProcessDefinition<ReturnInput, AiCreditGiveBack.GiveBackOutput, ProcessContext> giveBack() {
        return ProcessDefinition.define(RETURN, 1, ReturnInput.class, AiCreditGiveBack.GiveBackOutput.class,
            ProcessContext.class, pb -> pb
                .description("The sponsor gives back AI credit to the account it was bought from (the cash first, "
                    + "then the fee credit); what requests still running and fees not charged yet hold stays.")
                .permissions(QbPermissions.AI_USE)
                // Every write of a sponsor needs a verified address (VerifiedEmailWritesIT); one who bought credit
                // had one (QB_AI_CREDIT_BUY requires it).
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(GIVEN_BACK, AiCreditGiveBack.GiveBackOutput.class))
                .step("Load the tax rate", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    QbParams.FEE_TAX_RATE))
                .step("Lock the sponsor's AI", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbAi.lockName(ctx.request().actorId())))
                .step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.SPONSOR, ctx.request().actorId())))
                .step("Read where the sponsor stands", QueryEntities.<ProcessContext>all(withSameRequest(
                    AiFees.queries(ctx -> ctx.request().actorId(), false))))
                .compute("Check the return", (metadata, ctx) -> {
                    if (FeeGateRows.first(ctx, SAME_REQUEST) != null) {
                        throw new BusinessRuleViolationException(new Violation("requestKey",
                            FeeCodes.AI_CREDIT_REPEATED, "This return was already made", Map.of()));
                    }
                    ReturnInput input = ctx.get(INPUT, ReturnInput.class);
                    ctx.put(GIVE_BACK, new AiCreditGiveBack.GiveBackInput(ctx.request().actorId(), "RETURN",
                        input.amount(), occupied(ctx, true), input.requestKey()));
                })
                .step("Give it back", CallProcess.<ProcessContext>of(AiCreditGiveBack.GIVE_BACK, 1,
                    ctx -> ctx.get(GIVE_BACK), GIVEN_BACK)));
    }

    public static ProcessDefinition<ExpireRef, FeeProcesses.ItemOutput, ProcessContext> expireItem() {
        return ProcessDefinition.define(EXPIRE_ITEM, 1, ExpireRef.class, FeeProcesses.ItemOutput.class,
            ProcessContext.class, pb -> pb
                .description("Voids the expired fee credit a sponsor's AI credit holds, but for what their open AI "
                    + "jobs hold.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> {
                    AiCreditGiveBack.GiveBackOutput out = ctx.get(GIVEN_BACK, AiCreditGiveBack.GiveBackOutput.class);
                    return new FeeProcesses.ItemOutput(sponsor(ctx), out.amount().signum() > 0 ? "VOIDED"
                        : "SKIPPED", null);
                })
                .step("Load the tax rate", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    QbParams.FEE_TAX_RATE))
                .step("Lock the sponsor's AI", HoldLock.<ProcessContext>exclusive(ctx -> QbAi.lockName(sponsor(ctx))))
                .step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.SPONSOR, sponsor(ctx))))
                .step("Read where the sponsor stands", QueryEntities.<ProcessContext>all(
                    array(AiFees.queries(AiCreditReturns::sponsor, false))))
                .compute("Work out what the jobs hold", (metadata, ctx) -> ctx.put(GIVE_BACK,
                    new AiCreditGiveBack.GiveBackInput(sponsor(ctx), "EXPIRE", null, occupied(ctx, false), null)))
                .step("Void the expired fee credit", CallProcess.<ProcessContext>of(AiCreditGiveBack.GIVE_BACK, 1,
                    ctx -> ctx.get(GIVE_BACK), GIVEN_BACK)));
    }

    /**
     * What the sponsor's open jobs hold of the credit with tax. With fees waiting and no tax rate (never published),
     * nothing is given back: what they hold with tax is not known. The sponsor's return is refused for it
     * ({@link FeeCodes#AI_FEE_TAX_UNSET}, #699 re-review L3); the daily expiry's item fails (its run alerts).
     */
    static BigDecimal occupied(ProcessContext ctx, boolean sponsorsReturn) {
        AiFees.Standing standing = AiFees.standing(ctx, null);
        Optional<BigDecimal> taxRate = ctx.get(PARAMS, ParamValues.class).find(QbParams.FEE_TAX_RATE,
            BigDecimal.class);
        if (taxRate.isEmpty() && !standing.unbilled().isEmpty()) {
            if (sponsorsReturn) {
                throw new BusinessRuleViolationException(new Violation(null, FeeCodes.AI_FEE_TAX_UNSET,
                    "The AI credit cannot be given back now: the fee tax rate is not set", Map.of()));
            }
            throw new IllegalStateException("AI fees wait to be charged and the fee tax rate is not set");
        }
        return standing.occupied(taxRate.orElse(BigDecimal.ZERO));
    }

    @SuppressWarnings("unchecked")
    private static QueryEntities.Metadata<ProcessContext>[] array(List<QueryEntities.Metadata<ProcessContext>> list) {
        return list.toArray(new QueryEntities.Metadata[0]);
    }

    private static QueryEntities.Metadata<ProcessContext>[] withSameRequest(
        List<QueryEntities.Metadata<ProcessContext>> queries) {
        List<QueryEntities.Metadata<ProcessContext>> all = new ArrayList<>(queries);
        all.add(QueryEntities.query(AiCredits.defaultDataset(AiCredits.MOVEMENT), ctx -> EntityQuery.builder()
            .where(new QueryPredicate.And(List.of(
                new QueryPredicate.Eq("sponsorId", ctx.request().actorId()),
                new QueryPredicate.Eq("kind", AiCredits.RETURN_KIND),
                new QueryPredicate.Eq("reference", ctx.get(INPUT, ReturnInput.class).requestKey()))))
            .limit(1).build(), SAME_REQUEST));
        return array(all);
    }

    private static String sponsor(ProcessContext ctx) {
        return ctx.get(INPUT, ExpireRef.class).sponsorId();
    }

    /** The first row a query read, or none. */
    private static final class FeeGateRows {
        @SuppressWarnings("unchecked")
        static EntityInstance first(ProcessContext ctx, String key) {
            Object found = ctx.contains(key) ? ctx.get(key) : null;
            List<EntityInstance> rows = found == null ? List.of() : (List<EntityInstance>) found;
            return rows.isEmpty() ? null : rows.getFirst();
        }
    }
}

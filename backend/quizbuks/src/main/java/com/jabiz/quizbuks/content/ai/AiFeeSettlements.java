package com.jabiz.quizbuks.content.ai;

import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.AiCredits;
import com.jabiz.quizbuks.fee.AiFeeRules;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.fee.QbPrepayments;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * F-M9a-2 (docs/quizbuks/plans/impl/F-M9.md §4 item 3): the month's AI fees, charged from the sponsor's AI credit the
 * month after their jobs ended. The monthly job ({@value #JOB}, the 1st at 00:27 in the fiscal year's zone) gives one
 * item a sponsor with charged jobs of an earlier month not charged yet ({@value #DUE_TEMPLATE}); the item
 * ({@value #SETTLE}) takes at most {@value #BATCH} of them, earliest ended first (a sponsor with more gets more items),
 * under the sponsor's AI lock and then their wallet lock (never everybody's AI lock: plan §4 item 7).
 *
 * <p>The jobs are charged a group (operator, month) at a time: one {@code QB_FEE_CHARGE} of the kind {@code AI}, the
 * month the jobs ended in (not the month it is charged in), the operator frozen on them ({@link FeeProcesses#NO_OPERATOR}
 * when they had none), paid from the AI credit (fee credit first) and never from the account: a group whose total
 * with tax the credit cannot hold is lowered first ({@link AiFeeRules#charges}), the rest the platform's. Its
 * {@code shareExcluded} is the jobs' cost part (F-M12 §3.2). A job charged gets the charge's id (run again, nothing is
 * charged twice); one whose fee was taken on whole is left with no fee (it is no longer open, V124).
 */
public final class AiFeeSettlements {

    public static final String SETTLE = "QB_AI_FEE_SETTLE";
    public static final String JOB = "qb.ai-fee-monthly";
    public static final String DUE_TEMPLATE = "qb.system.ai-fee-due";
    /** The cron of {@value #JOB}: the 1st at 00:27 (the property {@code quizbuks.ai-fee.monthly-cron}). */
    public static final String CRON = "0 27 0 1 * *";
    /** The most jobs an item charges (it writes each): a sponsor with more is given more items. */
    public static final int BATCH = 200;
    /** What a charge's source key starts with: {@code ai:<month>:<operator or ->} (the sponsor is the fee's own). */
    static final String SOURCE_PREFIX = "ai:";

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String JOBS = "jobs";
    static final String CREDIT = "credit";
    static final String PLAN = "plan";
    static final String CHARGE_INPUTS = "chargeInputs";
    static final String CHARGED = "charged";

    private AiFeeSettlements() {}

    /**
     * @param sponsorId the sponsor
     * @param month     the month the job runs in (yyyy-MM, the fiscal year's zone): the jobs of the months before it
     */
    public record SettleRef(@NotBlank @Size(max = 128) String sponsorId,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String month) {}

    public static ProcessDefinition<SettleRef, FeeProcesses.ItemOutput, ProcessContext> settle(java.time.ZoneId zone) {
        return ProcessDefinition.define(SETTLE, 1, SettleRef.class, FeeProcesses.ItemOutput.class, ProcessContext.class,
            pb -> pb
                .description("Charges a sponsor's AI fees of the months before this one from their AI credit: one "
                    + "fee a group (operator, month), never more than the credit holds.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new FeeProcesses.ItemOutput(ref(ctx).sponsorId(),
                    ctx.contains(CHARGE_INPUTS) && !((List<?>) ctx.get(CHARGE_INPUTS)).isEmpty() ? "CHARGED" : "SKIPPED",
                    ref(ctx).month()))
                .step("Load the tax rate", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    QbParams.FEE_TAX_RATE))
                // Plan §4 item 7: the sponsor's AI lock (their jobs' outcomes take it), then their wallet lock.
                .step("Lock the sponsor's AI", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbAi.lockName(ref(ctx).sponsorId())))
                .step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.SPONSOR, ref(ctx).sponsorId())))
                .step("Read the jobs to charge and the credit", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbAi.defaultDataset(QbAi.JOB), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("sponsorId", ref(ctx).sponsorId()),
                            new QueryPredicate.Eq("feeMode", QbAi.FEE_CREDIT),
                            new QueryPredicate.IsNull("feeChargeId"),
                            new QueryPredicate.Gt("feeYen", BigDecimal.ZERO),
                            // An earlier month: its month (feePeriod) is the one it finished in, in this zone.
                            new QueryPredicate.Lt("finishedAt", FeeRules.periodStart(ref(ctx).month(), zone)))))
                        .orderBy("finishedAt", true).limit(BATCH).build(), JOBS),
                    QueryEntities.query(QbFees.defaultDataset(QbPrepayments.PREPAYMENT), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("sponsorId", ref(ctx).sponsorId()),
                            new QueryPredicate.Eq("purpose", QbPrepayments.AI),
                            new QueryPredicate.Eq("status", QbPrepayments.OPEN)))).limit(1).build(), CREDIT)))
                .compute("Work out the charges", (metadata, ctx) -> plan(ctx))
                .step("Charge them", CallProcess.<ProcessContext>forEach(FeeProcesses.CHARGE, 1,
                    AiFeeSettlements::chargeInputs, CHARGED))
                .compute("Record the charges", (metadata, ctx) -> record(ctx)));
    }

    /** The source key of a group's charge: the month and the operator ({@code -} for none), under 100 characters. */
    static String sourceKey(String period, String operatorId) {
        return SOURCE_PREFIX + period + ":" + (operatorId == null ? FeeProcesses.NO_OPERATOR : operatorId);
    }

    static void plan(ProcessContext ctx) {
        List<EntityInstance> jobs = rows(ctx, JOBS);
        EntityInstance credit = rows(ctx, CREDIT).isEmpty() ? null : rows(ctx, CREDIT).getFirst();
        Optional<BigDecimal> taxRate = ctx.get(PARAMS, ParamValues.class).find(QbParams.FEE_TAX_RATE,
            BigDecimal.class);
        if (jobs.isEmpty() || taxRate.isEmpty()) {
            // No tax rate (not published yet): nothing is charged; the jobs wait for the next run.
            return;
        }
        List<AiFeeRules.Job> due = new ArrayList<>();
        for (EntityInstance job : jobs) {
            due.add(new AiFeeRules.Job(String.valueOf(job.id()), (String) job.get("feeOperatorId"),
                (String) job.get("feePeriod"), money(job.get("feeYen")),
                AiFeeRules.costPart(money(job.get("billableUsd")), money(job.get("feeUsdJpy")))));
        }
        BigDecimal remaining = credit == null ? BigDecimal.ZERO : money(credit.get("remaining"));
        List<AiFeeRules.Charge> charges = AiFeeRules.charges(due, taxRate.get(), remaining);
        ctx.put(PLAN, charges);
        List<FeeProcesses.ChargeInput> inputs = new ArrayList<>();
        for (AiFeeRules.Charge charge : charges) {
            if (charge.amount().signum() > 0) {
                inputs.add(new FeeProcesses.ChargeInput(ref(ctx).sponsorId(), FeeRules.AI, charge.period(),
                    charge.amount(), null, sourceKey(charge.period(), charge.operatorId()), null,
                    charge.operatorId() == null ? FeeProcesses.NO_OPERATOR : charge.operatorId(),
                    String.valueOf(credit.id()), charge.tax(), charge.shareExcluded()));
            }
        }
        ctx.put(CHARGE_INPUTS, List.copyOf(inputs));
    }

    @SuppressWarnings("unchecked")
    static void record(ProcessContext ctx) {
        if (!ctx.contains(PLAN)) {
            return;
        }
        List<AiFeeRules.Charge> charges = (List<AiFeeRules.Charge>) ctx.get(PLAN);
        List<FeeProcesses.ChargeOutput> charged = ctx.contains(CHARGED)
            ? (List<FeeProcesses.ChargeOutput>) ctx.get(CHARGED) : List.of();
        Map<String, EntityInstance> jobs = new LinkedHashMap<>();
        rows(ctx, JOBS).forEach(job -> jobs.put(String.valueOf(job.id()), job));
        EntityInstance credit = rows(ctx, CREDIT).getFirst();
        BigDecimal balance = money(credit.get("remaining"));
        int next = 0;
        for (AiFeeRules.Charge charge : charges) {
            FeeProcesses.ChargeOutput output = charge.amount().signum() > 0 ? charged.get(next++) : null;
            for (Map.Entry<String, BigDecimal> fee : charge.fees().entrySet()) {
                EntityInstance job = jobs.get(fee.getKey());
                BigDecimal read = money(job.get("feeYen"));
                Map<String, Object> values = new LinkedHashMap<>();
                if (fee.getValue().compareTo(read) != 0) {
                    // What the credit could not hold is the platform's (plan H2 (b)).
                    values.put("feeYen", fee.getValue());
                    values.put("absorbedYen", money(job.get("absorbedYen")).add(read.subtract(fee.getValue())));
                }
                if (output != null && fee.getValue().signum() > 0) {
                    values.put("feeChargeId", output.chargeId());
                }
                if (!values.isEmpty()) {
                    ctx.changes().update(QbAi.JOB, job.id(), job.version(), values);
                }
            }
            if (output == null) {
                continue;
            }
            balance = balance.subtract(output.total());
            Map<String, Object> movement = new LinkedHashMap<>();
            movement.put("sponsorId", ref(ctx).sponsorId());
            movement.put("prepaymentId", String.valueOf(credit.id()));
            movement.put("kind", AiCredits.CHARGE_KIND);
            movement.put("amount", output.total().negate());
            movement.put("credit", output.fromCredit().negate());
            movement.put("cash", output.fromCash().negate());
            movement.put("balance", balance);
            movement.put("operatorId", charge.operatorId());
            movement.put("reference", output.chargeId());
            movement.put("movedAt", ctx.opTime());
            ctx.changes().insert(AiCredits.MOVEMENT, movement);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<FeeProcesses.ChargeInput> chargeInputs(ProcessContext ctx) {
        return ctx.contains(CHARGE_INPUTS) ? (List<FeeProcesses.ChargeInput>) ctx.get(CHARGE_INPUTS) : List.of();
    }

    private static SettleRef ref(ProcessContext ctx) {
        return ctx.get(INPUT, SettleRef.class);
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> rows(ProcessContext ctx, String key) {
        Object found = ctx.contains(key) ? ctx.get(key) : null;
        return found == null ? List.of() : (List<EntityInstance>) found;
    }

    private static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }
}

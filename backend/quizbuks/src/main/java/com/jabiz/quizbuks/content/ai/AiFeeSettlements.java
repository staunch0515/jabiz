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
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F-M9a-2 (docs/quizbuks/plans/impl/F-M9.md §4 item 3): the month's AI fees, charged from the sponsor's AI credit the
 * month after their jobs' month. The monthly job ({@value #JOB}, the 1st at 00:27 in the fiscal year's zone) gives one
 * item a batch of {@value #BATCH_SIZE} of a sponsor's charged jobs of an earlier month not charged yet
 * ({@value #DUE_TEMPLATE}), its key the batch's earliest job (#699 review M1: a job recorded after the run gives a new
 * key, so the run requested again for the same time charges it). The item ({@value #SETTLE}) takes the sponsor's AI
 * lock and then their wallet lock (never everybody's AI lock: plan §4 item 7), finds the earliest
 * {@value #BATCH_SIZE} jobs still waiting by their own month ({@code feePeriod < month}, {@value #BATCH_TEMPLATE};
 * #699 review M3) and reads them with the credit.
 *
 * <p>The jobs are charged a group (operator, month) at a time ({@link AiFeeRules#charges}): one {@code QB_FEE_CHARGE}
 * of the kind {@code AI}, the month the jobs belong to (not the month it is charged in), the operator frozen on them
 * ({@link FeeProcesses#NO_OPERATOR} when they had none), paid from the AI credit (fee credit first) and never from the
 * account: a group whose total with tax the credit cannot hold is lowered first, the rest the platform's (also when
 * there is no open credit: #699 review L1). Its {@code shareExcluded} is the jobs' cost part in proportion to what of
 * each job's whole fee is charged (#699 review M2, F-M12 §3.2). A job charged gets the charge's id (run again, nothing
 * is charged twice); one whose fee was lowered to nothing is left with no fee (it is no longer open, V124). Without the
 * fee tax rate the item fails (#699 review M1/L2). Its outcome: {@code CHARGED}, {@code SETTLED_ABSORBED} (all of it
 * the platform's) or {@code SKIPPED} (nothing waited).
 */
public final class AiFeeSettlements {

    public static final String SETTLE = "QB_AI_FEE_SETTLE";
    public static final String JOB = "qb.ai-fee-monthly";
    public static final String DUE_TEMPLATE = "qb.system.ai-fee-due";
    /** The jobs an item charges, by their own month ({@code feePeriod}, #699 review M3). */
    public static final String BATCH_TEMPLATE = "qb.system.ai-fee-batch";
    /** The cron of {@value #JOB}: the 1st at 00:27 (the property {@code quizbuks.ai-fee.monthly-cron}). */
    public static final String CRON = "0 27 0 1 * *";
    /** The most jobs an item charges (it writes each): a sponsor with more is given more items. */
    public static final int BATCH_SIZE = 200;
    /** What a charge's source key starts with: {@code ai:<month>:<operator or ->} (the sponsor is the fee's own). */
    static final String SOURCE_PREFIX = "ai:";

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String BATCH = "batch";
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

    public static ProcessDefinition<SettleRef, FeeProcesses.ItemOutput, ProcessContext> settle() {
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
                .outputMapper(ctx -> new FeeProcesses.ItemOutput(ref(ctx).sponsorId(), outcome(ctx),
                    ref(ctx).month()))
                .step("Load the tax rate", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    QbParams.FEE_TAX_RATE))
                // Plan §4 item 7: the sponsor's AI lock (their jobs' outcomes take it), then their wallet lock.
                .step("Lock the sponsor's AI", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbAi.lockName(ref(ctx).sponsorId())))
                .step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.SPONSOR, ref(ctx).sponsorId())))
                // #699 review M3: the jobs of the months before by their own month (feePeriod), as the due template.
                .step("Find the jobs to charge", RunTemplate.<ProcessContext>of(BATCH_TEMPLATE,
                    ctx -> Map.of("sponsorId", ref(ctx).sponsorId(), "month", ref(ctx).month()), BATCH))
                .step("Read the jobs to charge and the credit", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbAi.defaultDataset(QbAi.JOB), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("aiJobId", batchIds(ctx)))
                        .orderBy("finishedAt", true).limit(BATCH_SIZE).build(), JOBS),
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
        if (jobs.isEmpty()) {
            return;
        }
        // #699 review M1/L2: without the tax rate the item fails (no result kept: the job's run alerts and a run
        // requested again for the same time retries it), never succeeds having charged nothing.
        BigDecimal taxRate = ctx.get(PARAMS, ParamValues.class).find(QbParams.FEE_TAX_RATE, BigDecimal.class)
            .orElseThrow(() -> new IllegalStateException("The AI fees of " + ref(ctx).sponsorId()
                + " cannot be charged: the fee tax rate (" + QbParams.FEE_TAX_RATE + ") is not set"));
        List<AiFeeRules.Job> due = new ArrayList<>();
        for (EntityInstance job : jobs) {
            due.add(new AiFeeRules.Job(String.valueOf(job.id()), (String) job.get("feeOperatorId"),
                (String) job.get("feePeriod"), money(job.get("feeYen")),
                AiFeeRules.costPart(money(job.get("billableUsd")), money(job.get("feeUsdJpy"))),
                money(job.get("absorbedYen"))));
        }
        // #699 review L1: no open credit (closed by F-M15, say): it holds nothing, all is the platform's.
        BigDecimal remaining = credit == null ? BigDecimal.ZERO : money(credit.get("remaining"));
        List<AiFeeRules.Charge> charges = AiFeeRules.charges(due, taxRate, remaining);
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
        EntityInstance credit = rows(ctx, CREDIT).isEmpty() ? null : rows(ctx, CREDIT).getFirst();
        BigDecimal balance = credit == null ? BigDecimal.ZERO : money(credit.get("remaining"));
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

    /**
     * The item's outcome: CHARGED (a fee charged), SETTLED_ABSORBED (the jobs settled, all of it the platform's: the
     * credit held nothing; #699 review L5) or SKIPPED (no job waited).
     */
    static String outcome(ProcessContext ctx) {
        if (ctx.contains(CHARGE_INPUTS) && !((List<?>) ctx.get(CHARGE_INPUTS)).isEmpty()) {
            return "CHARGED";
        }
        return ctx.contains(PLAN) ? "SETTLED_ABSORBED" : "SKIPPED";
    }

    @SuppressWarnings("unchecked")
    private static List<Object> batchIds(ProcessContext ctx) {
        List<Object> ids = new ArrayList<>();
        if (ctx.contains(BATCH)) {
            for (Map<String, Object> row : (List<Map<String, Object>>) ctx.get(BATCH)) {
                ids.add(String.valueOf(row.get("aiJobId")));
            }
        }
        return ids;
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

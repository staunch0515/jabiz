package com.jabiz.quizbuks.content.ai;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.quizbuks.fee.AiFeeRules;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.fee.Prepayments;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.fee.QbPrepayments;
import com.jabiz.quizbuks.operator.QbOperators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * F-M9 (docs/quizbuks/plans/impl/F-M9.md §3, §4): the AI fee as the AI processes see it. A sponsor's request, while
 * {@code qb.ai.fee.enabled} is on, is charged ({@code CREDIT}): it reserves of the sponsor's AI credit the most it may
 * cost with its tax, and is refused ({@link AiCodes#CREDIT_SHORT}) when the credit cannot hold it; the switch, the
 * operator, the markup and the rate are frozen on the job. The job's outcome (completed, failed or cancelled, under the
 * same locks) works out its fee from the calls the provider answered and lets the reservation go; the month's fees are
 * charged from the credit the month after (a-2). A reviewer's check, a cover of before Q98a and the AI help are never
 * charged ({@code NONE}).
 *
 * <p>What a sponsor stands at is read in the statements the processes already make: the open AI credit, the sponsor's
 * charged jobs not yet in a charge and, at a request, their operator.
 */
final class AiFees {

    private static final Logger log = LoggerFactory.getLogger(AiFees.class);

    static final String CREDIT_ROWS = "aiFeeCredit";
    static final String FEE_JOBS = "aiFeeJobs";
    static final String OPERATOR_ROWS = "aiFeeOperator";
    /** Set by {@link #reserve}: the fee's values {@link #recordValues} writes on the job. */
    static final String FROZEN = "aiFeeFrozen";

    /**
     * The most charged jobs a sponsor may have outside a charge (reserved, or finished and waiting for the month's
     * charge): the daily requests over two months stay far below it; more is an inconsistency, refused rather than
     * counted short.
     */
    static final int MAX_OPEN = 2_000;

    /** The kinds charged to the sponsor (plan §3): questions, translations and the website's draft. */
    static final Set<String> CHARGED_KINDS = Set.of(QbAi.KIND_QUESTIONS, QbAi.KIND_TRANSLATION,
        QbAi.KIND_PUBLICATION_TEXTS, QbAi.KIND_SITE_PROFILE);

    /** The searches one attempt of a website's draft may make: none (it reads the pages it is given, plan §3). */
    static final long SITE_SEARCHES = 0;

    /** What the frozen fee writes on a new job. */
    record Frozen(String mode, String operatorId, BigDecimal markup, BigDecimal usdJpy, BigDecimal reserved) {}

    /**
     * Where a sponsor stands: the credit's remaining, the fees finished and not charged by (operator, month), and what
     * the requests still running hold.
     */
    record Standing(BigDecimal remaining, Map<String, BigDecimal> unbilled, BigDecimal reserved) {

        BigDecimal group(String operatorId, String period) {
            return unbilled.getOrDefault(groupKey(operatorId, period), BigDecimal.ZERO);
        }

        BigDecimal available(BigDecimal taxRate) {
            return AiFeeRules.available(remaining, AiFeeRules.unbilledWithTax(unbilled.values(), taxRate), reserved);
        }

        /** What the open jobs hold of the credit: the unbilled fees with their tax, and the reservations (F-M9a-2). */
        BigDecimal occupied(BigDecimal taxRate) {
            return AiFeeRules.unbilledWithTax(unbilled.values(), taxRate).add(reserved);
        }
    }

    /** The reads of a sponsor's standing, for the statement of a request or an outcome. */
    static List<QueryEntities.Metadata<ProcessContext>> queries(Function<ProcessContext, String> sponsorId,
        boolean withOperator) {
        List<QueryEntities.Metadata<ProcessContext>> queries = new ArrayList<>(List.of(
            QueryEntities.query(QbFees.defaultDataset(QbPrepayments.PREPAYMENT), ctx -> EntityQuery.builder()
                .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("sponsorId", sponsorId.apply(ctx)),
                    new QueryPredicate.Eq("purpose", QbPrepayments.AI),
                    new QueryPredicate.Eq("status", QbPrepayments.OPEN)))).limit(1).build(), CREDIT_ROWS),
            QueryEntities.query(QbAi.defaultDataset(QbAi.JOB), ctx -> EntityQuery.builder()
                .where(openFees(sponsorId.apply(ctx))).limit(MAX_OPEN + 1).build(), FEE_JOBS)));
        if (withOperator) {
            queries.add(QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx ->
                EntityQuery.builder().where(new QueryPredicate.Eq("userId", sponsorId.apply(ctx))).limit(1).build(),
                OPERATOR_ROWS));
        }
        return queries;
    }

    /**
     * The sponsor's charged jobs that still hold part of the credit: reserved (no fee yet), or finished with a fee not in
     * a charge. A finished job with no fee holds nothing and is never charged (#692 review M2), as the partial index
     * {@code qb_ai_job_fee_open_idx} (V124) has it.
     */
    static QueryPredicate openFees(String sponsorId) {
        return new QueryPredicate.And(List.of(new QueryPredicate.Eq("sponsorId", sponsorId),
            new QueryPredicate.Eq("feeMode", QbAi.FEE_CREDIT), new QueryPredicate.IsNull("feeChargeId"),
            new QueryPredicate.Or(List.of(new QueryPredicate.IsNull("feeYen"),
                new QueryPredicate.Gt("feeYen", BigDecimal.ZERO)))));
    }

    /** The sponsor's standing as {@link #queries} read it, without the job {@code except} (an outcome's own). */
    static Standing standing(ProcessContext ctx, Object except) {
        List<EntityInstance> jobs = rows(ctx, FEE_JOBS);
        if (jobs.size() > MAX_OPEN) {
            throw new IllegalStateException("More than " + MAX_OPEN + " charged AI jobs outside a charge");
        }
        List<EntityInstance> credit = rows(ctx, CREDIT_ROWS);
        BigDecimal remaining = credit.isEmpty() ? BigDecimal.ZERO : money(credit.getFirst().get("remaining"));
        Map<String, BigDecimal> unbilled = new LinkedHashMap<>();
        BigDecimal reserved = BigDecimal.ZERO;
        for (EntityInstance job : jobs) {
            if (Objects.equals(String.valueOf(job.id()), String.valueOf(except))) {
                continue;
            }
            if (job.get("finishedAt") == null) {
                reserved = reserved.add(money(job.get("reservedYen")));
            } else {
                unbilled.merge(groupKey((String) job.get("feeOperatorId"), (String) job.get("feePeriod")),
                    money(job.get("feeYen")), BigDecimal::add);
            }
        }
        return new Standing(remaining, unbilled, reserved);
    }

    /**
     * F-M9b: what a request may cost and what the credit can hold for it, as the request reads them (its quote and its
     * reservation are this one figure, coordinator 2026-11-14).
     *
     * @param charged      whether the request is charged (the fee on and a charged kind); the rest null when not
     * @param maxYen       the most it may cost, with its tax rounded up: what it reserves
     * @param available    what the credit can hold now (expired or voided fee credit left out)
     * @param minPurchase  the least purchase (qb.ai.credit.min-purchase), null when not set
     */
    record Quote(boolean charged, BigDecimal maxYen, BigDecimal available, BigDecimal minPurchase) {

        static final Quote FREE = new Quote(false, null, null, null);

        boolean enough() {
            return !charged || available.compareTo(maxYen) >= 0;
        }

        /** What the credit lacks for the request: 0 when it is enough. */
        BigDecimal shortfall() {
            return enough() ? BigDecimal.ZERO : maxYen.subtract(available);
        }
    }

    /**
     * The quote of a request about to be recorded: {@link Quote#FREE} for a kind the platform pays for or with the switch
     * off; otherwise the most it may cost and what the credit can hold, refused only when the price is not known (or a
     * fee parameter is not usable). It writes nothing and takes no lock: a request reads it again under its locks.
     *
     * @param attempt the most one attempt may use ({@link AiProcesses#unrecordedAttempt} of the job to come), read only
     *                for a charged request
     */
    static Quote quote(ProcessContext ctx, AiSettings settings, String kind,
        java.util.function.Supplier<ClaudeApi.Usage> attempt, int attempts, long searches, boolean mayFallBack) {
        AiSettings.Fee fee = settings.fee();
        if (!fee.enabled() || !CHARGED_KINDS.contains(kind)) {
            return Quote.FREE;
        }
        List<Violation> problems = new ArrayList<>(settings.feeProblems());
        BigDecimal maxUsd = problems.isEmpty() ? AiCost.maxOf(settings.model(), attempt.get(), attempts, searches,
            settings.price(), mayFallBack) : null;
        if (problems.isEmpty() && maxUsd == null) {
            problems.add(new Violation(null, AiCodes.UNAVAILABLE, "AI generation is unavailable: the model's price is "
                + "not known", Map.of("enabled", settings.enabled())));
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        BigDecimal reservation = AiFeeRules.reservation(maxUsd, fee.markup(), fee.usdJpy(), fee.taxRate());
        Standing standing = standing(ctx, null);
        BigDecimal available = standing.available(fee.taxRate());
        if (readsLots(ctx)) {
            // #699 re-review L2: expired fee credit, or a voided grant's, beyond what the open jobs hold is the next
            // expiry's to void: never reserved by a new request (no day's window before the daily job).
            available = available.min(AiFeeRules.usable(Prepayments.aiLots(ctx), standing.occupied(fee.taxRate()),
                ctx.opTime()));
        }
        return new Quote(true, reservation, available, fee.minPurchase());
    }

    /**
     * At a request, under the sponsor's AI lock and everybody's: freezes the fee on the job to come, from its
     * {@link #quote}. Not charged is {@code NONE}; otherwise the request reserves the most it may cost, refused when the
     * credit cannot hold it.
     */
    static void reserve(ProcessContext ctx, AiSettings settings, Quote quote) {
        if (!quote.charged()) {
            ctx.put(FROZEN, new Frozen(QbAi.FEE_NONE, null, null, null, BigDecimal.ZERO));
            return;
        }
        AiSettings.Fee fee = settings.fee();
        if (!quote.enough()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("needed", quote.maxYen());
            details.put("available", quote.available());
            if (fee.minPurchase() != null) {
                // Not set (an optional parameter): no detail; a null one would fail the refusal itself (a 500).
                details.put("minPurchase", fee.minPurchase());
            }
            throw new BusinessRuleViolationException(List.of(new Violation(null, AiCodes.CREDIT_SHORT,
                "The AI credit holds " + quote.available() + " yen; this request may cost up to " + quote.maxYen(),
                details)));
        }
        List<EntityInstance> operator = rows(ctx, OPERATOR_ROWS);
        ctx.put(FROZEN, new Frozen(QbAi.FEE_CREDIT,
            operator.isEmpty() ? null : (String) operator.getFirst().get("operatorId"), fee.markup(), fee.usdJpy(),
            quote.maxYen()));
    }

    /**
     * Whether a request reads its credit by lot ({@link Prepayments#HELD_TEMPLATE}, #699 re-review L2): a charged kind
     * with the fee on and an open credit holding something.
     */
    static boolean readsLots(ProcessContext ctx) {
        if (!ctx.contains(AiProcesses.SETTINGS) || !ctx.contains(AiProcesses.KIND)
            || !ctx.get(AiProcesses.SETTINGS, AiSettings.class).fee().enabled()
            || !CHARGED_KINDS.contains((String) ctx.get(AiProcesses.KIND))) {
            return false;
        }
        return holdsCredit(ctx);
    }

    /** Whether the sponsor's open AI credit holds something ({@link #queries} read it). */
    static boolean holdsCredit(ProcessContext ctx) {
        List<EntityInstance> credit = rows(ctx, CREDIT_ROWS);
        return !credit.isEmpty() && money(credit.getFirst().get("remaining")).signum() > 0;
    }

    static Map<String, Object> lotsParams(ProcessContext ctx) {
        return Map.of("prepaymentId", String.valueOf(rows(ctx, CREDIT_ROWS).getFirst().id()));
    }

    /** The fee's values of a new job, as {@link #reserve} froze them ({@code NONE} when it did not run). */
    static Map<String, Object> recordValues(ProcessContext ctx) {
        Frozen frozen = ctx.contains(FROZEN) ? ctx.get(FROZEN, Frozen.class)
            : new Frozen(QbAi.FEE_NONE, null, null, null, BigDecimal.ZERO);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("feeMode", frozen.mode());
        values.put("feeOperatorId", frozen.operatorId());
        values.put("feeMarkup", frozen.markup());
        values.put("feeUsdJpy", frozen.usdJpy());
        values.put("reservedYen", frozen.reserved());
        return values;
    }

    /**
     * At a job's outcome, under the sponsor's AI lock and everybody's: splits its dollars into the sponsor's (the calls
     * the provider answered) and the platform's (the estimates), and for a charged job works out its fee from the
     * sponsor's part, takes it within its reservation and what the credit holds besides (the rest taken on by the
     * platform), and lets the reservation go. Adds to {@code values}, which the outcome writes on the job.
     *
     * @param answered the usage the provider's answers reported ({@link AiProcesses#usage}; none when no attempt ran)
     * @param cost     the job's dollars, all of its usage ({@code costUsd}); null when its price is not known
     * @param searches the dollars of its searches (in {@code cost} too)
     */
    static void settle(ProcessContext ctx, EntityInstance job, Map<String, Object> values, ClaudeApi.Usage answered,
        String servedModel, BigDecimal cost, BigDecimal searches, Instant finished) {
        AiSettings settings = AiProcesses.settings(ctx);
        BigDecimal billable = null;
        if (cost != null) {
            BigDecimal answeredUsd = AiCost.of(job.get("model"), servedModel, answered, settings.price());
            billable = (answeredUsd == null ? BigDecimal.ZERO : answeredUsd).add(searches).min(cost);
            values.put("billableUsd", billable);
            values.put("unrecordedUsd", cost.subtract(billable));
        }
        if (!QbAi.FEE_CREDIT.equals(job.get("feeMode"))) {
            return;
        }
        if (cost == null) {
            // #692 review L1: the request knew the price (it refuses one it does not); unknown at the end (the model's
            // price parameters removed since), the job is charged nothing: no sponsor in the log (D16).
            log.warn("AI fee of job {} settled at 0: the price of model {} is not known", job.id(), job.get("model"));
        }
        BigDecimal taxRate = settings.fee().taxRate();
        if (taxRate == null) {
            // Removed since the request (which checked it): the month's charge works the tax out again.
            log.warn("AI fee settled without a tax rate (qb.fee.tax-rate is not usable)");
            taxRate = BigDecimal.ZERO;
        }
        BigDecimal fee = AiFeeRules.fee(billable, money(job.get("feeMarkup")), money(job.get("feeUsdJpy")));
        // The month in the deployment's zone, as the job's other months (its zone is the fiscal year's, kept on it).
        String period = FeeRules.period(finished, ZoneId.of((String) job.get("zone")));
        // What the job may take: at most its own reservation (#692 re-review, the coordinator's decision: a job that
        // overruns never takes what the sponsor's other requests hold or may still use; the rest is the platform's and
        // reported by the daily reconciliation, AI_RESERVATION_SHORT), and no more than the credit less everything
        // else outstanding (its reservation is part of the credit's remaining, so the rest's availability is its room;
        // adding the reservation would count it twice).
        Standing standing = standing(ctx, job.id());
        BigDecimal room = standing.available(taxRate).min(money(job.get("reservedYen")));
        AiFeeRules.Settled settled = AiFeeRules.settle(standing.group((String) job.get("feeOperatorId"), period),
            fee, room, taxRate);
        values.put("feeYen", settled.fee());
        values.put("absorbedYen", settled.absorbed());
        values.put("reservedYen", BigDecimal.ZERO);
        values.put("feePeriod", period);
        if (settled.absorbed().signum() > 0) {
            // No sponsor in the log (D16): the job is enough to find it.
            log.warn("AI fee above its reservation or what the credit holds: {} yen of job {} taken on by the platform",
                settled.absorbed(), job.id());
        }
    }

    private static String groupKey(String operatorId, String period) {
        return (operatorId == null ? "" : operatorId) + "|" + period;
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> rows(ProcessContext ctx, String key) {
        Object found = ctx.contains(key) ? ctx.get(key) : null;
        return found == null ? List.of() : (List<EntityInstance>) found;
    }

    private static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private AiFees() {}
}

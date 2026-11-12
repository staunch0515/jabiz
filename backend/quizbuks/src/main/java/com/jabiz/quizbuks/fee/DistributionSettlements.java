package com.jabiz.quizbuks.fee;

import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The distribution fee's settlement (F-M6, docs/quizbuks/plans/impl/F-M.md §9.5): {@value #SETTLE} ({@code internal()},
 * the item of both jobs) settles one publication for one month, under the sponsor's wallet lock only (not the
 * publication's or its start lock: the start counter only grows, so a value read a little old charges less now and the
 * next settlement makes it up; the final one waits {@code qb.distribution.final.end-grace-sec} after the end, so every
 * start before the end is committed by then).
 *
 * <ul>
 *   <li>The monthly job {@value #MONTHLY_JOB} (the 2nd, 00:37 in the fiscal year's zone) settles the month that ended
 *       for every publication due ({@value #DUE_TEMPLATE}); a publication ended past the grace is settled final there.</li>
 *   <li>The daily job {@value #FINAL_JOB} (03:07, before the prepayments' release at 03:23) settles final the
 *       publications ended past the grace without a final row ({@value #FINAL_DUE_TEMPLATE}): its month is the one the
 *       publication ended in, or the first after it without a row.</li>
 * </ul>
 *
 * <p>The amounts are cumulative ({@link DistributionRules#settle}): every start up to the row rounded up at once, less
 * what the rows before it charged. The fee is charged as a fee of its kind ({@code QB_FEE_CHARGE}, {@code DISTRIBUTION},
 * to the operator frozen at the submission) from the deposit's prepayment first and the account after (a postpaid
 * sponsor's: owed, the prepayment stops counting by as much); an amount of 0 is not charged. The row, written once,
 * names the charge: it is written after the charge, and saved before the final release reads it.
 */
public final class DistributionSettlements {

    public static final String SETTLE = "QB_DISTRIBUTION_SETTLE";
    public static final String MONTHLY_JOB = "qb.distribution-fee";
    public static final String FINAL_JOB = "qb.distribution-final";
    public static final String DUE_TEMPLATE = "qb.system.distribution-due";
    public static final String FINAL_DUE_TEMPLATE = "qb.system.distribution-final-due";

    /** How many settlement rows a publication has at most (one a month): more is a defect, refused. */
    static final int MAX_ROWS = 600;

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String PUBLICATION = "publication";
    static final String CARD = "card";
    static final String COUNTER = "counter";
    static final String ROWS = "rows";
    static final String PREPAYMENT = "prepayment";
    static final String PLAN = "plan";
    static final String CHARGE_INPUT = "chargeInput";
    static final String CHARGED = "charged";
    static final String RELEASE_INPUT = "releaseInput";
    static final String RELEASED = "released";

    private DistributionSettlements() {}

    /**
     * @param publicationId the publication
     * @param sponsorId     its sponsor (the wallet lock)
     * @param period        the monthly job's month (yyyy-MM); none for the final job
     * @param last          the final job's item: settle final, or nothing
     */
    public record SettleRef(@NotBlank @Size(max = 36) String publicationId, @NotBlank @Size(max = 128) String sponsorId,
        @Pattern(regexp = "\\d{4}-\\d{2}") String period, @NotNull Boolean last) {}

    /** What one settlement wrote (the job's outcome). */
    record Plan(String period, long fromStarts, long toStarts, BigDecimal unit, boolean atFloor,
        DistributionRules.Settlement figures, boolean last, String operatorId) {}

    public static ProcessDefinition<SettleRef, FeeProcesses.ItemOutput, ProcessContext> settle(ZoneId zone) {
        return ProcessDefinition.define(SETTLE, 1, SettleRef.class, FeeProcesses.ItemOutput.class, ProcessContext.class,
            pb -> pb
                .description("Settles a publication's distribution fee for a month: the starts since its last settlement "
                    + "at the price frozen at its submission, charged from its deposit and then the account.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new FeeProcesses.ItemOutput(ref(ctx).sponsorId(),
                    ctx.contains(PLAN) ? (ctx.get(PLAN, Plan.class).last() ? "FINAL" : "SETTLED") : "SKIPPED",
                    ref(ctx).publicationId()))
                .step("Load the parameters", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    QbParams.FEE_TAX_RATE, QbParams.DISTRIBUTION_FINAL_END_GRACE_SEC))
                .step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.SPONSOR, ref(ctx).sponsorId())))
                .step("Read the publication, its starts, settlements and deposit", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbPublications.defaultDataset(QbPublications.PUBLICATION),
                        ctx -> byPublication("publicationId", ref(ctx).publicationId(), 1), PUBLICATION),
                    QueryEntities.query(QbPublications.defaultDataset(QbPublications.CARD),
                        ctx -> byPublication("publicationId", ref(ctx).publicationId(), 1), CARD),
                    QueryEntities.query(QbPublications.defaultDataset(QbPublications.START_COUNTER),
                        ctx -> byPublication("publicationId", ref(ctx).publicationId(), 1), COUNTER),
                    QueryEntities.query(QbDistributions.defaultDataset(QbDistributions.SETTLEMENT),
                        ctx -> byPublication("publicationId", ref(ctx).publicationId(), MAX_ROWS + 1), ROWS),
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbPrepayments.PREPAYMENT), ctx -> EntityQuery
                        .builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("publicationId", List.of(ref(ctx).publicationId())),
                            new QueryPredicate.Eq("purpose", QbPrepayments.DISTRIBUTION),
                            new QueryPredicate.Eq("status", QbPrepayments.OPEN)))).limit(1).build(), PREPAYMENT)))
                .compute("Work out the settlement", (metadata, ctx) -> plan(ctx, zone))
                .step("Charge it", CallProcess.<ProcessContext>when(ctx -> ctx.contains(CHARGE_INPUT),
                    FeeProcesses.CHARGE, 1, ctx -> ctx.get(CHARGE_INPUT), CHARGED))
                .compute("Record the settlement", (metadata, ctx) -> record(ctx))
                // The final release reads the final row again under the lock: written out first.
                .step("Save the settlement", SaveChanges.when(ctx -> ctx.contains(RELEASE_INPUT)))
                .step("Give the rest of the deposit back", CallProcess.<ProcessContext>when(
                    ctx -> ctx.contains(RELEASE_INPUT), Prepayments.RELEASE, 1, ctx -> ctx.get(RELEASE_INPUT),
                    RELEASED)));
    }

    static void plan(ProcessContext ctx, ZoneId zone) {
        SettleRef ref = ref(ctx);
        EntityInstance publication = FeeGate.first(ctx, PUBLICATION);
        EntityInstance card = FeeGate.first(ctx, CARD);
        List<EntityInstance> rows = FeeGate.list(ctx, ROWS);
        if (rows.size() > MAX_ROWS) {
            throw new IllegalStateException("More than " + MAX_ROWS + " distribution settlements of " + ref.publicationId());
        }
        if (publication == null || card == null || publication.get("distributionUnit") == null
            || !PublisherKind.SPONSOR.equals(publication.get("publisherKind"))
            || !ref.sponsorId().equals(String.valueOf((Object) publication.get("publisherId")))
            || rows.stream().anyMatch(row -> Boolean.TRUE.equals(row.get("final")))) {
            return;
        }
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        Instant endedAt = PublicationStatus.ENDED.equals(card.get("status")) ? instant(card.get("endedAt")) : null;
        long grace = Math.clamp(params.find(QbParams.DISTRIBUTION_FINAL_END_GRACE_SEC, BigDecimal.class)
            .orElse(new BigDecimal(QbParams.defaultOf(QbParams.DISTRIBUTION_FINAL_END_GRACE_SEC))).longValue(), 0, 86_400);
        boolean ended = endedAt != null && !endedAt.plus(Duration.ofSeconds(grace)).isAfter(ctx.opTime());
        Set<String> periods = new HashSet<>();
        rows.forEach(row -> periods.add(String.valueOf((Object) row.get("period"))));
        String period;
        if (Boolean.TRUE.equals(ref.last())) {
            if (!ended) {
                return;
            }
            // N1: the month it ended in; when that month has a row already, the first month after it without one.
            YearMonth month = YearMonth.parse(FeeRules.period(endedAt, zone));
            while (periods.contains(month.toString())) {
                month = month.plusMonths(1);
            }
            period = month.toString();
        } else {
            if (ref.period() == null || periods.contains(ref.period())) {
                return;
            }
            period = ref.period();
        }
        BigDecimal unit = FeeGate.money(publication.get("distributionUnit"));
        boolean atFloor = Boolean.TRUE.equals(publication.get("distributionAtFloor"));
        EntityInstance counter = FeeGate.first(ctx, COUNTER);
        long toStarts = counter == null ? 0 : FeeGate.money(counter.get("starts")).longValue();
        long fromStarts = rows.stream().mapToLong(row -> FeeGate.money(row.get("toStarts")).longValue()).max()
            .orElse(0);
        // The counter only grows: a smaller value than a row already counted would be a defect.
        if (toStarts < fromStarts) {
            throw new IllegalStateException("The start counter of " + ref.publicationId() + " went back");
        }
        BigDecimal charged = rows.stream().map(row -> FeeGate.money(row.get("amount")))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rate = params.find(QbParams.FEE_TAX_RATE, BigDecimal.class).orElse(BigDecimal.ZERO);
        DistributionRules.Settlement figures = DistributionRules.settle(toStarts, unit, charged, rate);
        String operatorId = publication.get("billingOperatorId") == null ? null
            : String.valueOf((Object) publication.get("billingOperatorId"));
        Plan plan = new Plan(period, fromStarts, toStarts, unit, atFloor, figures, ended, operatorId);
        ctx.put(PLAN, plan);
        if (figures.amount().signum() > 0) {
            EntityInstance prepayment = FeeGate.first(ctx, PREPAYMENT);
            ctx.put(CHARGE_INPUT, new FeeProcesses.ChargeInput(ref.sponsorId(), FeeRules.DISTRIBUTION, period,
                figures.amount(), null, "distribution:" + ref.publicationId() + ":" + period, null, operatorId,
                prepayment == null ? null : String.valueOf(prepayment.id())));
        }
    }

    static void record(ProcessContext ctx) {
        if (!ctx.contains(PLAN)) {
            return;
        }
        SettleRef ref = ref(ctx);
        Plan plan = ctx.get(PLAN, Plan.class);
        FeeProcesses.ChargeOutput charged = ctx.contains(CHARGED) ? ctx.get(CHARGED, FeeProcesses.ChargeOutput.class)
            : null;
        // The tax as charged (the fee's own rule, the same rate read in the same transaction).
        BigDecimal tax = charged == null ? BigDecimal.ZERO : charged.total().subtract(plan.figures().amount());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("publicationId", ref.publicationId());
        values.put("sponsorId", ref.sponsorId());
        values.put("operatorId", plan.operatorId());
        values.put("period", plan.period());
        values.put("fromStarts", BigDecimal.valueOf(plan.fromStarts()));
        values.put("toStarts", BigDecimal.valueOf(plan.toStarts()));
        values.put("starts", BigDecimal.valueOf(plan.toStarts() - plan.fromStarts()));
        values.put("unit", plan.unit());
        values.put("atFloor", plan.atFloor());
        values.put("cumulativeAmount", plan.figures().cumulative());
        values.put("amount", plan.figures().amount());
        values.put("tax", tax);
        values.put("total", plan.figures().amount().add(tax));
        values.put("final", plan.last());
        values.put("chargeId", charged == null ? null : charged.chargeId());
        values.put("settledAt", ctx.opTime());
        ctx.changes().insert(QbDistributions.SETTLEMENT, values);
        if (plan.last()) {
            ctx.put(RELEASE_INPUT, new Prepayments.ReleaseInput(ref.sponsorId(), ref.publicationId(),
                QbPrepayments.ENDED, QbPrepayments.DISTRIBUTION));
        }
    }

    private static EntityQuery byPublication(String field, String publicationId, int limit) {
        return EntityQuery.builder().where(new QueryPredicate.In(field, List.of(publicationId))).limit(limit).build();
    }

    static Instant instant(Object value) {
        return value == null ? null : value instanceof java.time.OffsetDateTime time ? time.toInstant()
            : value instanceof Instant time ? time : Instant.parse(value.toString());
    }

    static SettleRef ref(ProcessContext ctx) {
        return ctx.get(INPUT, SettleRef.class);
    }
}

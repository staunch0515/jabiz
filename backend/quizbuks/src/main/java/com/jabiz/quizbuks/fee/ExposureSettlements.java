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
import com.jabiz.runtime.process.steps.RunTemplate;
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
import java.util.Optional;
import java.util.Set;

/**
 * The exposure fee's settlement (F-M8, docs/quizbuks/plans/impl/F-M8.md §3.5): {@value #SETTLE} ({@code internal()}, the
 * item of both jobs) settles one publication for one month, under the sponsor's wallet lock only (not the publication's
 * or its start lock: the exposures counted only grow, so a count read a little old charges less now and the next
 * settlement makes it up; the final one waits {@code qb.exposure.final.end-grace-sec} after the end, so the reports on
 * their way are counted by then).
 *
 * <ul>
 *   <li>The monthly job {@value #MONTHLY_JOB} (the 2nd, 00:47 in the fiscal year's zone, after the distribution fee's
 *       00:37 and before the invoices' 01:17) settles the month that ended for every publication due
 *       ({@value #DUE_TEMPLATE}); a publication ended past the grace is settled final there.</li>
 *   <li>The daily job {@value #FINAL_JOB} (03:12, after the distribution fee's final settlements at 03:07 and before
 *       the prepayments' release at 03:23) settles final the publications ended past the grace without a final row
 *       ({@value #FINAL_DUE_TEMPLATE}): its month is the one the publication ended in, or the first after it without a row.</li>
 * </ul>
 *
 * <p>The exposures billed are {@code min(successful, bought)} ({@link ExposureFeeRules#toCount}); the amounts are
 * cumulative ({@link ExposureFeeRules#settle}): every exposure up to the row rounded up at once, less what the rows
 * before it charged, and the tax the same way (so that what is bought in full is charged exactly its deposit). The fee
 * is charged as a fee of its kind ({@code QB_FEE_CHARGE}, {@code EXPOSURE}, to the operator frozen at the submission)
 * from the deposit's prepayment first and the account after; an amount of 0 is not charged. The row, written once,
 * names the charge: it is written after the charge, and saved before the final release reads it. Order does not rely on
 * the time: each job reads what it needs (the final row, the later rows) under the lock.
 */
public final class ExposureSettlements {

    public static final String SETTLE = "QB_EXPOSURE_SETTLE";
    public static final String MONTHLY_JOB = "qb.exposure-fee";
    public static final String FINAL_JOB = "qb.exposure-final";
    public static final String DUE_TEMPLATE = "qb.system.exposure-due";
    public static final String FINAL_DUE_TEMPLATE = "qb.system.exposure-final-due";
    public static final String COUNT_TEMPLATE = "qb.system.exposure-count";

    /** Bounds of qb.exposure.final.end-grace-sec: at least a minute, at most a day (as the distribution fee's). */
    static final long MIN_END_GRACE_SEC = 60;
    static final long MAX_END_GRACE_SEC = 86_400;

    /** How many settlement rows a publication has at most (one a month): more is a defect, refused. */
    static final int MAX_ROWS = 600;

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String PUBLICATION = "publication";
    static final String CARD = "card";
    static final String ROWS = "rows";
    static final String PREPAYMENT = "prepayment";
    static final String COUNTS = "counts";
    static final String PLAN = "plan";
    static final String CHARGE_INPUT = "chargeInput";
    static final String CHARGED = "charged";
    static final String RELEASE_INPUT = "releaseInput";
    static final String RELEASED = "released";

    private ExposureSettlements() {}

    /**
     * @param publicationId the publication
     * @param sponsorId     its sponsor (the wallet lock)
     * @param period        the monthly job's month (yyyy-MM); none for the final job
     * @param last          the final job's item: settle final, or nothing
     */
    public record SettleRef(@NotBlank @Size(max = 36) String publicationId, @NotBlank @Size(max = 128) String sponsorId,
        @Pattern(regexp = "\\d{4}-\\d{2}") String period, @NotNull Boolean last) {}

    /** What one settlement wrote (the job's outcome). */
    record Plan(String period, long fromCount, long toCount, BigDecimal unit,
        ExposureFeeRules.Settlement figures, boolean last, String operatorId) {}

    public static ProcessDefinition<SettleRef, FeeProcesses.ItemOutput, ProcessContext> settle(ZoneId zone) {
        return ProcessDefinition.define(SETTLE, 1, SettleRef.class, FeeProcesses.ItemOutput.class, ProcessContext.class,
            pb -> pb
                .description("Settles a publication's exposure fee for a month: the exposures counted since its last "
                    + "settlement (no more than were bought) at the price frozen at its submission, charged from its "
                    + "deposit and then the account.")
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
                    QbParams.FEE_TAX_RATE, QbParams.EXPOSURE_FINAL_END_GRACE_SEC))
                .step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.SPONSOR, ref(ctx).sponsorId())))
                .step("Read the publication, its settlements and deposit", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbPublications.defaultDataset(QbPublications.PUBLICATION),
                        ctx -> byPublication("publicationId", ref(ctx).publicationId(), 1), PUBLICATION),
                    QueryEntities.query(QbPublications.defaultDataset(QbPublications.CARD),
                        ctx -> byPublication("publicationId", ref(ctx).publicationId(), 1), CARD),
                    QueryEntities.query(QbExposureFees.defaultDataset(QbExposureFees.SETTLEMENT),
                        ctx -> byPublication("publicationId", ref(ctx).publicationId(), MAX_ROWS + 1), ROWS),
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbPrepayments.PREPAYMENT), ctx -> EntityQuery
                        .builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("publicationId", List.of(ref(ctx).publicationId())),
                            new QueryPredicate.Eq("purpose", QbPrepayments.EXPOSURE),
                            new QueryPredicate.Eq("status", QbPrepayments.OPEN)))).limit(1).build(), PREPAYMENT)))
                // The exposures counted before the month's end and in all (the end of the settled month: the final one
                // counts them all).
                .step("Count its exposures", RunTemplate.<ProcessContext>of(COUNT_TEMPLATE, ctx -> Map.of(
                    "publicationId", ref(ctx).publicationId(), "before", countedBefore(ctx, zone)), COUNTS))
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

    /** The first instant after the month the monthly item settles; the final item has no month yet: the far future. */
    private static Instant countedBefore(ProcessContext ctx, ZoneId zone) {
        String period = ref(ctx).period();
        return period == null ? Instant.parse("9999-12-31T00:00:00Z")
            : YearMonth.parse(period).plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
    }

    static void plan(ProcessContext ctx, ZoneId zone) {
        SettleRef ref = ref(ctx);
        EntityInstance publication = FeeGate.first(ctx, PUBLICATION);
        EntityInstance card = FeeGate.first(ctx, CARD);
        List<EntityInstance> rows = FeeGate.list(ctx, ROWS);
        if (rows.size() > MAX_ROWS) {
            throw new IllegalStateException("More than " + MAX_ROWS + " exposure settlements of " + ref.publicationId());
        }
        if (publication == null || card == null || publication.get("exposureUnit") == null
            || publication.get("exposurePurchased") == null
            || !PublisherKind.SPONSOR.equals(publication.get("publisherKind"))
            || !ref.sponsorId().equals(String.valueOf((Object) publication.get("publisherId")))
            || rows.stream().anyMatch(row -> Boolean.TRUE.equals(row.get("final")))) {
            return;
        }
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        Instant endedAt = PublicationStatus.ENDED.equals(card.get("status")) ? instant(card.get("endedAt")) : null;
        // No grace parameter (QB_SETUP's proposal not published yet; SetupParamsCheck warns): not settled as ended,
        // the monthly job still settles its months and a later run its end.
        Optional<BigDecimal> graceParam = params.find(QbParams.EXPOSURE_FINAL_END_GRACE_SEC, BigDecimal.class);
        boolean ended = endedAt != null && graceParam.isPresent() && !endedAt.plus(Duration.ofSeconds(
            Math.clamp(graceParam.get().longValue(), MIN_END_GRACE_SEC, MAX_END_GRACE_SEC))).isAfter(ctx.opTime());
        Set<String> periods = new HashSet<>();
        rows.forEach(row -> periods.add(String.valueOf((Object) row.get("period"))));
        String period;
        if (Boolean.TRUE.equals(ref.last())) {
            if (!ended) {
                return;
            }
            // The month it ended in; when that month has a row already, the first month after it without one.
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
        long purchased = FeeGate.money(card.get("exposurePurchased") != null ? card.get("exposurePurchased")
            : publication.get("exposurePurchased")).longValue();
        // The template's one row (a map, not an entity).
        Map<?, ?> counts = ctx.get(COUNTS) instanceof List<?> found && !found.isEmpty()
            && found.getFirst() instanceof Map<?, ?> row ? row : Map.of();
        long byEnd = counts.get("byEnd") == null ? 0 : FeeGate.money(counts.get("byEnd")).longValue();
        long total = counts.get("total") == null ? 0 : FeeGate.money(counts.get("total")).longValue();
        // A final row (and a monthly row of a publication already ended past the grace) counts all of them.
        long successful = ended ? total : byEnd;
        long highest = rows.stream().mapToLong(row -> FeeGate.money(row.get("toCount")).longValue()).max().orElse(0);
        String thisPeriod = period;
        boolean later = rows.stream().anyMatch(row -> String.valueOf((Object) row.get("period")).compareTo(thisPeriod) > 0);
        long toCount = ExposureFeeRules.toCount(highest, later, successful, purchased);
        BigDecimal chargedAmount = rows.stream().map(row -> FeeGate.money(row.get("amount")))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal chargedTax = rows.stream().map(row -> FeeGate.money(row.get("tax")))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rate = params.find(QbParams.FEE_TAX_RATE, BigDecimal.class).orElse(BigDecimal.ZERO);
        BigDecimal unit = FeeGate.money(publication.get("exposureUnit"));
        ExposureFeeRules.Settlement figures = ExposureFeeRules.settle(toCount, unit, chargedAmount, chargedTax, rate);
        String operatorId = publication.get("billingOperatorId") == null ? null
            : String.valueOf((Object) publication.get("billingOperatorId"));
        ctx.put(PLAN, new Plan(period, highest, toCount, unit, figures, ended, operatorId));
        if (figures.amount().signum() > 0) {
            EntityInstance prepayment = FeeGate.first(ctx, PREPAYMENT);
            ctx.put(CHARGE_INPUT, new FeeProcesses.ChargeInput(ref.sponsorId(), FeeRules.EXPOSURE, period,
                figures.amount(), null, "exposure:" + ref.publicationId() + ":" + period, null, operatorId,
                prepayment == null ? null : String.valueOf(prepayment.id()), figures.tax()));
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
        BigDecimal tax = charged == null ? BigDecimal.ZERO : charged.total().subtract(plan.figures().amount());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("publicationId", ref.publicationId());
        values.put("sponsorId", ref.sponsorId());
        values.put("operatorId", plan.operatorId());
        values.put("period", plan.period());
        values.put("fromCount", BigDecimal.valueOf(plan.fromCount()));
        values.put("toCount", BigDecimal.valueOf(plan.toCount()));
        values.put("counted", BigDecimal.valueOf(plan.toCount() - plan.fromCount()));
        values.put("unit", plan.unit());
        values.put("cumulativeAmount", plan.figures().cumulative());
        values.put("amount", plan.figures().amount());
        values.put("tax", tax);
        values.put("total", plan.figures().amount().add(tax));
        values.put("final", plan.last());
        values.put("chargeId", charged == null ? null : charged.chargeId());
        values.put("settledAt", ctx.opTime());
        ctx.changes().insert(QbExposureFees.SETTLEMENT, values);
        if (plan.last()) {
            ctx.put(RELEASE_INPUT, new Prepayments.ReleaseInput(ref.sponsorId(), ref.publicationId(),
                QbPrepayments.ENDED, QbPrepayments.EXPOSURE));
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

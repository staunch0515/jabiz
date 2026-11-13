package com.jabiz.quizbuks.fee;

import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F-M7 (docs/quizbuks/plans/impl/F-M.md §10.3-1, -6): the unused days of an audience fee given back after its
 * publication ended early. {@value #ITEM} ({@code internal()}, the item of the daily job {@value #JOB}) takes the
 * sponsor's wallet lock, reads the prepayment, the publication and the fee again, and decides once
 * ({@link AudienceRefunds}): with the switch {@code qb.audience.fee.refund-unused-days} as it was when the publication
 * ended, the end reason, and the period the fee was billed for (frozen with it; for a prepayment frozen before F-M7,
 * the publication's at the time it was frozen, else none: {@code NONE_NO_PERIOD}). What is given back is a credit note
 * ({@code QB_FEE_CREDIT_NOTE_ISSUE}, cut to what the fee may still be credited after notes by hand). The decision is
 * written on the prepayment, so a decided fee is never taken again.
 */
public final class AudienceRefundJob {

    public static final String ITEM = "QB_AUDIENCE_REFUND_ITEM";
    public static final String JOB = "qb.audience-refund";
    public static final String DUE_TEMPLATE = "qb.system.audience-refund-due";
    public static final String PERIOD_TEMPLATE = "qb.system.publication-period-at";

    /** One item: an audience fee's prepayment, its publication, the sponsor and when the publication ended. */
    public record Ref(@NotBlank @Size(max = 36) String prepaymentId, @NotBlank @Size(max = 36) String publicationId,
        @NotBlank @Size(max = 128) String sponsorId, @NotNull Instant endedAt) {}

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String PREPAYMENT = "prepayment";
    static final String PUBLICATION = "publication";
    static final String CHARGE = "charge";
    static final String PERIOD = "period";
    static final String ISSUE_INPUT = "issueInput";
    static final String ISSUED = "issued";
    static final String OUTCOME = "outcome";
    static final String SKIPPED = "SKIPPED";

    private AudienceRefundJob() {}

    public static ProcessDefinition<Ref, FeeProcesses.ItemOutput, ProcessContext> item() {
        return ProcessDefinition.define(ITEM, 1, Ref.class, FeeProcesses.ItemOutput.class, ProcessContext.class,
            pb -> pb
                .description("Decides once whether an audience fee gives back the unused days of a publication that "
                    + "ended early, and issues the credit note.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new FeeProcesses.ItemOutput(ref(ctx).sponsorId(),
                    ctx.contains(OUTCOME) ? (String) ctx.get(OUTCOME) : SKIPPED, ref(ctx).prepaymentId()))
                .step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.SPONSOR, ref(ctx).sponsorId())))
                // The switch as it was when the publication ended: turned on later, it gives nothing back. M1 of the
                // review: a controlled value not yet published then is off (NONE_SWITCH_OFF), never a failing item.
                .step("Load the switch at the end", LoadParams.<ProcessContext>optional(ctx -> ref(ctx).endedAt(),
                    PARAMS, QbParams.AUDIENCE_REFUND_UNUSED_DAYS))
                .step("Read the prepayment, the publication and the fee", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbPrepayments.PREPAYMENT), ctx -> EntityQuery
                        .builder().where(new QueryPredicate.In("prepaymentId", FeeGate.ids(ref(ctx).prepaymentId())))
                        .limit(1).build(), PREPAYMENT),
                    QueryEntities.query(QbPublications.defaultDataset(QbPublications.PUBLICATION), ctx -> EntityQuery
                        .builder().where(new QueryPredicate.In("publicationId",
                            FeeGate.ids(ref(ctx).publicationId()))).limit(1).build(), PUBLICATION),
                    // N6: a publication that went on never returns to a draft, so it has one audience fee at most.
                    QueryEntities.query(QbFees.defaultDataset(QbFees.CHARGE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("sponsorId", FeeGate.ids(ref(ctx).sponsorId())),
                            new QueryPredicate.Eq("kind", FeeRules.AUDIENCE),
                            new QueryPredicate.Eq("sourceKey", "publication:" + ref(ctx).publicationId()))))
                        .limit(2).build(), CHARGE)))
                // N5: a prepayment frozen before F-M7 has no period of its own: the publication's when it was frozen.
                .step("Read the period it was billed for", RunTemplate.<ProcessContext>when(
                    AudienceRefundJob::needsPeriod, PERIOD_TEMPLATE, ctx -> {
                        Map<String, Object> params = new LinkedHashMap<>();
                        params.put("publicationId", ref(ctx).publicationId());
                        params.put("at", FeeGate.first(ctx, PREPAYMENT).get("frozenAt"));
                        return params;
                    }, PERIOD))
                .compute("Decide", (metadata, ctx) -> decide(ctx))
                .step("Issue the credit note", CallProcess.<ProcessContext>when(ctx -> ctx.contains(ISSUE_INPUT),
                    CreditNotes.ISSUE, 1, ctx -> ctx.get(ISSUE_INPUT), ISSUED))
                .compute("Record the decision", (metadata, ctx) -> record(ctx)));
    }

    static boolean needsPeriod(ProcessContext ctx) {
        EntityInstance prepayment = FeeGate.first(ctx, PREPAYMENT);
        return prepayment != null && prepayment.get("frozenAt") != null
            && !AudienceRefunds.hasPeriod((String) prepayment.get("detail"));
    }

    static void decide(ProcessContext ctx) {
        Ref ref = ref(ctx);
        EntityInstance prepayment = FeeGate.first(ctx, PREPAYMENT);
        EntityInstance publication = FeeGate.first(ctx, PUBLICATION);
        if (prepayment == null || prepayment.get("refundDecidedAt") != null || prepayment.get("chargedAt") == null
            || !ref.sponsorId().equals(String.valueOf((Object) prepayment.get("sponsorId")))
            || !QbPrepayments.AUDIENCE.equals(prepayment.get("purpose"))) {
            return;
        }
        if (publication == null || !PublicationStatus.ENDED.equals(publication.get("status"))) {
            return;
        }
        List<EntityInstance> charges = FeeGate.list(ctx, CHARGE);
        if (charges.size() > 1) {
            throw new IllegalStateException("More than one audience fee for publication " + ref.publicationId());
        }
        EntityInstance charge = charges.isEmpty() ? null : charges.getFirst();
        if (charge == null) {
            // Charged from the prepayment, but no fee: nothing to give back (the check AUDIENCE_CHARGE_STATE sees it).
            ctx.put(OUTCOME, AudienceRefunds.NONE_ZERO);
            return;
        }
        Map<String, Object> period = rows(ctx).isEmpty() ? Map.of() : rows(ctx).getFirst();
        AudienceRefunds.Quote quote = AudienceRefunds.quote((String) prepayment.get("detail"),
            instant(period.get("startAt")), instant(period.get("endAt")));
        Boolean on = ctx.get(PARAMS, ParamValues.class).find(QbParams.AUDIENCE_REFUND_UNUSED_DAYS, Boolean.class)
            .orElse(Boolean.FALSE);
        Instant endedAt = instant(publication.get("endedAt"));
        String endReason = publication.get("endReason") == null ? null
            : String.valueOf((Object) publication.get("endReason"));
        AudienceRefunds.Decision decision = AudienceRefunds.decide(quote, FeeGate.money(charge.get("amount")),
            endedAt != null ? endedAt : ref.endedAt(), endReason, Boolean.TRUE.equals(on));
        if (!decision.refunds()) {
            ctx.put(OUTCOME, decision.outcome());
            return;
        }
        String chargeId = String.valueOf(charge.id());
        ctx.put(ISSUE_INPUT, new CreditNotes.IssueInput(ref.sponsorId(), chargeId, decision.amount(),
            QbCreditNotes.AUDIENCE_UNUSED, endReason, BigDecimal.valueOf(decision.usedDays()), null,
            QbCreditNotes.audienceSource(chargeId), null, false));
    }

    static void record(ProcessContext ctx) {
        EntityInstance prepayment = FeeGate.first(ctx, PREPAYMENT);
        if (ctx.contains(ISSUED)) {
            CreditNotes.IssueOutput issued = ctx.get(ISSUED, CreditNotes.IssueOutput.class);
            // Cut to nothing by notes by hand before it; else given back (a prepaid fee's part paid), or credited.
            ctx.put(OUTCOME, issued.creditNoteId() == null ? AudienceRefunds.NONE_ZERO
                : issued.refundedCash().add(issued.refundedCredit()).signum() > 0 ? AudienceRefunds.REFUNDED
                : AudienceRefunds.CREDITED);
        }
        if (!ctx.contains(OUTCOME)) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("refundDecidedAt", ctx.opTime());
        values.put("refundOutcome", ctx.get(OUTCOME));
        ctx.changes().update(QbPrepayments.PREPAYMENT, prepayment.id(), prepayment.version(), values);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(ProcessContext ctx) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(PERIOD);
        return rows == null ? List.of() : rows;
    }

    static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime time) {
            return time.toInstant();
        }
        return Instant.parse(String.valueOf(value));
    }

    static Ref ref(ProcessContext ctx) {
        return ctx.get(INPUT, Ref.class);
    }
}

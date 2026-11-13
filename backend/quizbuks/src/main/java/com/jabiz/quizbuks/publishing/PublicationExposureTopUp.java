package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.ExposureFeeRules;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.fee.FeeGate;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.fee.PrepaymentRules;
import com.jabiz.quizbuks.fee.Prepayments;
import com.jabiz.quizbuks.fee.QbFeeAccount;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.fee.QbPrepayments;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.messaging.ConsentChecks;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F-M8: buying more exposures for a publication that bought some ({@value #TOP_UP}, docs/quizbuks/plans/impl/F-M8.md
 * §3.4). Under the publication's lock and then the sponsor's wallet lock (the order of the submission): the account is
 * checked for what the top-up freezes more (the deposit of the new total less what the earlier purchases froze, so that
 * however many times the same total is bought in, the deposit is the same), {@value Prepayments#TOP_UP} adds it to the
 * publication's open prepayment, and {@value PublicationExposure#PURCHASED_SET} sets the new total. Not reviewed: the
 * shared video was reviewed already, only the number is bigger.
 */
public final class PublicationExposureTopUp {

    public static final String TOP_UP = "QB_EXPOSURE_TOP_UP";

    private static final String TOP_UP_INPUT = "topUp.input";
    private static final String PREPAYMENT = "topUp.prepayment";
    private static final String PARTS = "topUp.parts";
    private static final String MODE = "topUp.mode";
    private static final String DEPOSIT = "topUp.deposit";
    private static final String TOPPED = "topUp.topped";
    private static final String BOUGHT = "topUp.bought";
    private static final String OUTPUT = "topUp.output";

    /**
     * @param expectedPurchased the total the page showed: a request for another total (someone else, or this one sent
     *                          twice, changed it) is refused, so that repeating a request buys once
     * @param additional        the exposures to add
     */
    public record TopUpInput(@NotNull UUID publicationId,
        @NotNull @Min(0) @Max(PublicationExposure.MAX_PURCHASED) Long expectedPurchased,
        @NotNull @Min(1) @Max(PublicationExposure.MAX_PURCHASED) Long additional) {}

    /**
     * @param purchased the total bought now
     * @param frozen    what the top-up set aside more, with its tax
     */
    public record TopUpOutput(UUID publicationId, long purchased, BigDecimal frozen, long revision) {}

    private PublicationExposureTopUp() {}

    public static ProcessDefinition<TopUpInput, TopUpOutput, ProcessContext> topUp() {
        return ProcessDefinition.define(TOP_UP, 1, TopUpInput.class, TopUpOutput.class, ProcessContext.class, pb -> {
            pb.description("Buys more exposures for the caller's publication that bought some, setting their fee aside.")
                .permissions(QbPermissions.PUBLICATION_WRITE)
                .requiresVerifiedEmail()
                .actsOn(QbPublications.PUBLICATION, "publicationId")
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, TopUpOutput.class));
            CallerChecks.refuseBanned(pb);
            pb.steps(ConsentChecks.sponsor())
                .steps(PublicationEditing::lockAndLoad)
                .step("Load the fee parameters", LoadParams.<ProcessContext>optional(ProcessContext::opTime,
                    PublicationLifecycle.FEE_PARAMS, QbParams.FEE_TAX_RATE, QbParams.FEE_DEFAULT_BILLING_MODE,
                    QbParams.FEE_OVERDUE_BLOCK, QbParams.EXPOSURE_TOP_UP_MAX))
                // Fees overdue beyond what the sponsor's billing allows stop a top-up as they stop a submission.
                .steps(b -> FeeGate.load(b, ctx -> ctx.request().actorId()))
                .step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(ctx -> QbWallets.lockName(
                    QbWallets.SPONSOR, PublicationPrepayments.sponsorOf(PublicationEditing.publication(ctx)))))
                .step("Read the account and the exposures' prepayment", QueryEntities.<ProcessContext>all(
                    reads()))
                // Its parts so far: every top-up adds some, and a prepayment of more parts than MAX_PARTS cannot be given back.
                .step("Count its parts", QueryEntities.<ProcessContext>of(QbFeeAccount.defaultDataset(QbFeeAccount.PAYMENT),
                    ctx -> {
                        EntityInstance prepayment = PublicationEditing.first(ctx, PREPAYMENT);
                        return EntityQuery.builder().where(new QueryPredicate.In("targetId", prepayment == null
                            ? List.of() : List.of(String.valueOf(prepayment.id())))).limit(Prepayments.MAX_PARTS + 1)
                            .build();
                    }, PARTS))
                .compute("Work out the top-up", (metadata, ctx) -> work(ctx))
                .step("Read what a postpaid sponsor owes", RunTemplate.<ProcessContext>when(
                    ctx -> !ctx.hasViolations() && ctx.contains(PublicationPrepayments.ITEMS)
                        && FeeRules.POSTPAID.equals(ctx.get(MODE)) && PublicationPrepayments.total(ctx).signum() > 0,
                    "qb.system.fee-receivable", ctx -> Map.of("sponsorId",
                        PublicationPrepayments.sponsorOf(PublicationEditing.publication(ctx))), PublicationPrepayments.OWED))
                .compute("Check the funds", (metadata, ctx) -> checkFunds(ctx))
                .step("Add to the prepayment", CallProcess.<ProcessContext>when(
                    ctx -> !ctx.hasViolations() && ctx.contains(DEPOSIT) && deposit(ctx).signum() > 0,
                    Prepayments.TOP_UP, 1, ctx -> new Prepayments.TopUpInput(
                        PublicationPrepayments.sponsorOf(PublicationEditing.publication(ctx)),
                        String.valueOf(PublicationEditing.publication(ctx).id()), QbPrepayments.EXPOSURE,
                        deposit(ctx)), TOPPED))
                .step("Set the exposures bought", CallProcess.<ProcessContext>when(
                    ctx -> !ctx.hasViolations() && ctx.contains(DEPOSIT), PublicationExposure.PURCHASED_SET, 1,
                    ctx -> new PublicationExposure.PurchasedInput(UUID.fromString(String.valueOf(
                        PublicationEditing.publication(ctx).id())), bought(ctx)), BOUGHT))
                .compute("Answer", (metadata, ctx) -> {
                    if (ctx.contains(BOUGHT) && !ctx.hasViolations()) {
                        PublicationOutput written = ctx.get(BOUGHT, PublicationOutput.class);
                        ctx.put(OUTPUT, new TopUpOutput(written.publicationId(), bought(ctx), deposit(ctx),
                            written.revision()));
                    }
                });
        });
    }

    @SuppressWarnings("unchecked")
    private static QueryEntities.Metadata<ProcessContext>[] reads() {
        List<QueryEntities.Metadata<ProcessContext>> reads = new ArrayList<>(PublicationPrepayments.fundsReads(
            ctx -> PublicationPrepayments.sponsorOf(PublicationEditing.publication(ctx))));
        // The publication's exposure prepayment, whatever its state (a settled or given-back one is told apart below).
        reads.add(QueryEntities.query(QbFees.defaultDataset(QbPrepayments.PREPAYMENT), ctx -> EntityQuery.builder()
            .where(new QueryPredicate.And(List.of(
                new QueryPredicate.Eq("publicationId", String.valueOf(PublicationEditing.publication(ctx).id())),
                new QueryPredicate.Eq("purpose", QbPrepayments.EXPOSURE)))).limit(1).build(), PREPAYMENT));
        return reads.toArray(QueryEntities.Metadata[]::new);
    }

    private static TopUpInput input(ProcessContext ctx) {
        return ctx.get(PublicationEditing.INPUT, TopUpInput.class);
    }

    private static BigDecimal deposit(ProcessContext ctx) {
        return ctx.get(DEPOSIT, BigDecimal.class);
    }

    private static long bought(ProcessContext ctx) {
        return ((Number) PublicationEditing.publication(ctx).get("exposurePurchased")).longValue() + input(ctx).additional();
    }

    /** The checks that need no account, then the top-up's figures (what it freezes more) as the one item to set aside. */
    static void work(ProcessContext ctx) {
        EntityInstance publication = PublicationEditing.publication(ctx);
        TopUpInput input = input(ctx);
        ParamValues params = ctx.get(PublicationLifecycle.FEE_PARAMS, ParamValues.class);
        Object purchased = publication.get("exposurePurchased");
        Object unit = publication.get("exposureUnit");
        if (purchased == null || unit == null || !PublicationStatus.RUNNING.contains(
            String.valueOf((Object) publication.get("status")))) {
            ctx.reject(new Violation("publicationId", FeeCodes.EXPOSURE_NOT_BOUGHT, "The publication has bought no "
                + "exposures, or is not running (a publication in review is withdrawn first; an ended one buys no more)",
                Map.of("status", String.valueOf((Object) publication.get("status")))));
            return;
        }
        Violation unpaidFees = FeeGate.refusal(ctx, params.find(QbParams.FEE_OVERDUE_BLOCK, Boolean.class).orElse(null),
            params.find(QbParams.FEE_DEFAULT_BILLING_MODE, String.class).orElse(null));
        if (unpaidFees != null) {
            ctx.reject(unpaidFees);
            return;
        }
        long before = ((Number) purchased).longValue();
        if (before != input.expectedPurchased()) {
            ctx.reject(new Violation("expectedPurchased", FeeCodes.EXPOSURE_PURCHASED_CHANGED, "The exposures bought "
                + "changed since the page showed them", Map.of("purchased", String.valueOf(before))));
            return;
        }
        BigDecimal topUpMax = params.find(QbParams.EXPOSURE_TOP_UP_MAX, BigDecimal.class).orElse(null);
        BigDecimal rate = params.find(QbParams.FEE_TAX_RATE, BigDecimal.class).orElse(null);
        if (topUpMax == null || rate == null) {
            ctx.reject(new Violation(null, FeeCodes.FEE_PARAMS, "The tax rate or the top-up limit cannot be used",
                Map.of()));
            return;
        }
        if (input.additional() > topUpMax.longValue() || before + input.additional() > PublicationExposure.MAX_PURCHASED) {
            ctx.reject(new Violation("additional", FeeCodes.EXPOSURE_TOP_UP_RANGE, "More exposures at once than allowed",
                Map.of("max", String.valueOf(Math.min(topUpMax.longValue(),
                    PublicationExposure.MAX_PURCHASED - before)))));
            return;
        }
        EntityInstance prepayment = PublicationEditing.first(ctx, PREPAYMENT);
        if (prepayment == null || !QbPrepayments.OPEN.equals(prepayment.get("status"))) {
            ctx.reject(new Violation("publicationId", FeeCodes.EXPOSURE_PREPAYMENT_CLOSED, "The exposures' prepayment "
                + "is settled or given back: no more can be added to it", Map.of()));
            return;
        }
        // The funds are checked as the prepayment was set aside (prepaid money or postpaid credit), not as the sponsor's
        // billing is now: else a changed billing would add without freezing or fail after the check.
        ctx.put(MODE, String.valueOf((Object) prepayment.get("mode")));
        // The parts of the prepayment so far plus the most this top-up adds (a part for each lot it may take from).
        int mostNew = FeeGate.list(ctx, PublicationPrepayments.CREDITS).size() + FeeGate.list(ctx,
            PublicationPrepayments.LOTS).size();
        // Only money set aside has parts (a postpaid one counts against the credit limit and has none to give back).
        if (QbPrepayments.PREPAID.equals(ctx.get(MODE)) && FeeGate.list(ctx, PARTS).size() + mostNew > Prepayments.MAX_PARTS) {
            ctx.reject(new Violation("additional", FeeCodes.EXPOSURE_PARTS_LIMIT, "The parts of this prepayment have "
                + "reached their limit; no more can be added to it",
                Map.of("max", String.valueOf(Prepayments.MAX_PARTS))));
            return;
        }
        BigDecimal frozenUnit = FeeGate.money(unit);
        BigDecimal frozenBefore = FeeGate.money(prepayment.get("amount"));
        BigDecimal topUp = ExposureFeeRules.topUpDeposit(before, input.additional(), frozenUnit, rate, frozenBefore);
        BigDecimal total = ExposureFeeRules.deposit(before + input.additional(), frozenUnit, rate);
        if (total.compareTo(new BigDecimal(FeeProcesses.MAX_AMOUNT)) > 0) {
            ctx.reject(new Violation("additional", FeeCodes.EXPOSURE_DEPOSIT_RANGE, "The exposures bought come to more "
                + "than a fee may be", Map.of("purchased", String.valueOf(before + input.additional()), "unit",
                frozenUnit.toPlainString(), "max", FeeProcesses.MAX_AMOUNT)));
            return;
        }
        ctx.put(DEPOSIT, topUp);
        // The one item the funds check counts; none when the new total's deposit is the same (a unit below 1 yen).
        List<PrepaymentRules.Item> items = topUp.signum() > 0 ? List.of(new PrepaymentRules.Item(QbPrepayments.EXPOSURE,
            topUp, BigDecimal.ZERO, null)) : List.of();
        ctx.put(PublicationPrepayments.ITEMS, items);
    }

    static void checkFunds(ProcessContext ctx) {
        if (ctx.hasViolations() || !ctx.contains(DEPOSIT) || deposit(ctx).signum() <= 0) {
            return;
        }
        Violation refusal = PrepaymentRules.refusal(PublicationPrepayments.funds(ctx, ctx.get(MODE, String.class)));
        if (refusal != null) {
            ctx.reject(refusal);
        }
    }
}

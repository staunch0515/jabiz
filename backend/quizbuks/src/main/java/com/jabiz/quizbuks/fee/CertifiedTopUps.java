package com.jabiz.quizbuks.fee;

import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.quizbuks.identity.BanRules;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.identity.SponsorStatus;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.verification.QbVerifications;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The top-up of the onboarding platform fee Kudos when a Sponsor's company is verified (F-M3, docs/quizbuks/plans/
 * impl/F-M.md §6.3, §6.6; D-Q105-6): a Sponsor granted the uncertified onboarding amount who is verified afterwards is
 * granted the difference to the certified amount, once in their life (source {@code CERTIFIED_TOP_UP}, key
 * {@value #KEY}). Nothing for a Sponsor not granted the onboarding yet: confirming them later gives the certified amount
 * at once ({@code QB_FEE_CREDIT_ONBOARDING}).
 *
 * <p>{@value #ITEM} ({@code internal()}, the Sponsor's wallet lock) reads everything again and grants only while the
 * grants' switch {@code qb.fee-credit.grant.enabled} is on (the controller, 2026-11-10: no source goes round the
 * switch), to a Sponsor approved, not banned and verified; otherwise nothing, and the daily job {@value #DUE_JOB}
 * (template {@value #DUE_TEMPLATE}) tries again until it is granted. A verification's approval runs it in the same
 * transaction. A verification withdrawn afterwards keeps what was granted; a second verification grants nothing more.
 */
public final class CertifiedTopUps {

    public static final String ITEM = "QB_FEE_CREDIT_CERTIFIED_TOP_UP";
    /** Every day at 03:41 (in {@code jabiz.fiscal-year-zone}): the verified Sponsors with a top-up due. */
    public static final String DUE_JOB = "qb.fee-credit-certified-due";
    public static final String DUE_CRON = "0 41 3 * * *";
    public static final String DUE_TEMPLATE = "qb.system.fee-credit-certified-due";
    /** The source key of the top-up: one per Sponsor. */
    public static final String KEY = "certified";

    static final String INPUT = FeeCredits.INPUT;
    static final String PARAMS = "params";
    static final String LOTS = "lots";
    static final String ONBOARDING = "onboarding";
    static final String BAN = "ban";
    static final String VERIFIED = "verified";
    static final String DUE = "due";
    static final String DONE = "done";
    static final String OUTCOME = "outcome";

    private CertifiedTopUps() {}

    public static ProcessDefinition<FeeCredits.SponsorRef, FeeProcesses.ItemOutput, ProcessContext> item() {
        return ProcessDefinition.define(ITEM, 1, FeeCredits.SponsorRef.class, FeeProcesses.ItemOutput.class,
            ProcessContext.class, pb -> pb
                .description("Tops up a verified Sponsor's onboarding platform fee Kudos to the certified amount "
                    + "(once), while the grants' switch is on.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory(FeeProcesses::context)
                .outputMapper(ctx -> new FeeProcesses.ItemOutput(sponsorId(ctx),
                    ctx.contains(OUTCOME) ? (String) ctx.get(OUTCOME) : "NONE", null))
                .step("Load the grants' switch and amounts", LoadParams.<ProcessContext>optional(ProcessContext::opTime,
                    PARAMS, QbParams.FEE_CREDIT_GRANT_ENABLED, QbParams.FEE_CREDIT_GRANT_CERTIFIED))
                .step("Lock the Sponsor's wallet", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.SPONSOR, sponsorId(ctx))))
                .step("Read the Sponsor's onboarding and top-up lots, onboarding, ban and verification",
                    QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT), ctx -> EntityQuery
                            .builder().where(new QueryPredicate.And(List.of(
                                new QueryPredicate.In("sponsorId", FeeGate.ids(sponsorId(ctx))),
                                new QueryPredicate.In("sourceKey", new ArrayList<Object>(List.of(
                                    FeeCreditRules.ONBOARDING_KEY, KEY)))))).limit(2).build(), LOTS),
                        QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> EntityQuery
                            .builder().where(new QueryPredicate.Eq("userId", sponsorId(ctx))).limit(1).build(),
                            ONBOARDING),
                        QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.BAN), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.In("userId", AccountEditing.uuids(sponsorId(ctx)))).limit(1)
                            .build(), BAN),
                        QueryEntities.query(QbVerifications.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.And(List.of(
                                new QueryPredicate.Eq("sponsorId", sponsorId(ctx)),
                                new QueryPredicate.Eq("status", QbVerifications.APPROVED)))).limit(1).build(),
                            VERIFIED)))
                .compute("Know what is due", (metadata, ctx) -> due(ctx))
                .step("Grant the top-up", CallProcess.<ProcessContext>when(ctx -> ctx.contains(DUE), FeeCredits.GRANT,
                    1, ctx -> ctx.get(DUE, FeeCredits.GrantInput.class), DONE)));
    }

    private static String sponsorId(ProcessContext ctx) {
        return ctx.get(INPUT, FeeCredits.SponsorRef.class).sponsorId();
    }

    static void due(ProcessContext ctx) {
        ParamValues params = ctx.contains(PARAMS) ? ctx.get(PARAMS, ParamValues.class) : null;
        boolean enabled = params != null && Boolean.TRUE.equals(params.find(QbParams.FEE_CREDIT_GRANT_ENABLED,
            Boolean.class).orElse(null));
        BigDecimal certified = params == null ? null
            : params.find(QbParams.FEE_CREDIT_GRANT_CERTIFIED, BigDecimal.class).orElse(null);
        EntityInstance onboardingLot = null;
        EntityInstance topUpLot = null;
        for (EntityInstance lot : FeeGate.list(ctx, LOTS)) {
            if (FeeCreditRules.ONBOARDING_KEY.equals(lot.get("sourceKey"))) {
                onboardingLot = lot;
            } else if (KEY.equals(lot.get("sourceKey"))) {
                topUpLot = lot;
            }
        }
        EntityInstance onboarding = FeeGate.first(ctx, ONBOARDING);
        EntityInstance ban = FeeGate.first(ctx, BAN);
        Optional<BigDecimal> amount = FeeCreditRules.certifiedTopUp(enabled,
            onboarding != null && SponsorStatus.APPROVED.equals(onboarding.get("reviewStatus")),
            BanRules.banned(ban == null ? null : ban.attributes()), FeeGate.first(ctx, VERIFIED) != null,
            onboardingLot == null ? null : FeeGate.money(onboardingLot.get("amount")), topUpLot != null, certified);
        if (amount.isPresent()) {
            ctx.put(DUE, new FeeCredits.GrantInput(sponsorId(ctx), QbFeeAccount.CERTIFIED_TOP_UP, KEY, amount.get(),
                null));
            ctx.put(OUTCOME, "GRANTED");
        } else {
            ctx.put(OUTCOME, topUpLot != null ? "DONE" : "NONE");
        }
    }
}

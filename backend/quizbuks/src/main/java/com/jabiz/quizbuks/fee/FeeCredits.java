package com.jabiz.quizbuks.fee;

import com.jabiz.entity.Violation;
import com.jabiz.ledger.Direction;
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
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The platform fee Kudos granted to sponsors (F-M2, docs/quizbuks/first-launch-spec.md F-M2): a lot of fee credit
 * (account 2190, wallet {@code FEE_CREDIT}, {@code QbFeeCreditGrant}) that the platform pays for as promotion expenses
 * (account 5140), spent on the platform's fees before the cash (F-M1), voided when it expires.
 *
 * <ul>
 *   <li>{@value #GRANT} ({@code subProcessOnly}, under the sponsor's wallet lock its caller holds): the only way a lot is
 *       written. Once per sponsor and source key (a second call gives the first lot back, granting nothing); the
 *       sponsor's operator at the time is recorded on it (A1-4); debit 5140 / credit 2190, the source the lot.</li>
 *   <li>{@value #ONBOARDING}: a lead of the sponsor's operator confirms that an approved sponsor's details are complete,
 *       which grants the onboarding amount (the basic one; the certified one once F-M3 certifies sponsors). Refused
 *       (422) while {@code qb.fee-credit.grant.enabled} is off, for a sponsor not approved or banned, and once given.</li>
 *   <li>{@value #EXPIRE_ITEM} ({@code internal()}, the items of the daily job {@value #EXPIRE_JOB}): an expired lot's
 *       remaining amount voided, debit 2190 / credit 5140; what was spent stays spent.</li>
 *   <li>{@value #REMIND_ITEM} ({@code internal()}, the items of the daily job {@value #REMIND_JOB}): the sponsor told
 *       which lots expire within {@code qb.fee-credit.expiry-notice-days} (event {@value #EXPIRING_EVENT}, mailed by
 *       {@code QB_MAIL_FEE_CREDIT}): each lot once, a sponsor at most once in 24 hours.</li>
 * </ul>
 * Grants by hand are fee requests of the kind {@code CREDIT} ({@link FeeRequests}): four eyes, within
 * {@code qb.fee-credit.manual.max}.
 */
public final class FeeCredits {

    public static final String GRANT = "QB_FEE_CREDIT_GRANT";
    public static final String ONBOARDING = "QB_FEE_CREDIT_ONBOARDING";
    public static final String EXPIRE_ITEM = "QB_FEE_CREDIT_EXPIRE";
    public static final String REMIND_ITEM = "QB_FEE_CREDIT_REMIND";

    /** Every day at 02:47 (in {@code jabiz.fiscal-year-zone}): the lots expired with something left. */
    public static final String EXPIRE_JOB = "qb.fee-credit-expire";
    public static final String EXPIRE_DUE_TEMPLATE = "qb.system.fee-credit-expire-due";
    /** Every day at 10:13 (in {@code jabiz.fiscal-year-zone}, when people read mail): the sponsors to remind. */
    public static final String REMIND_JOB = "qb.fee-credit-remind";
    public static final String REMIND_DUE_TEMPLATE = "qb.system.fee-credit-remind-due";
    /** A sponsor's lots expire soon: {@code sponsorId}, {@code amount}, {@code count}, {@code expiresAt} (the first). */
    public static final String EXPIRING_EVENT = "qb.fee-credit.expiring";

    /** How many of a sponsor's open lots the reminder reads: more than any sponsor has. */
    static final int MAX_LOTS = FeeSettlement.MAX_CREDITS;

    /** The posting's descriptions (the monthly report tells the two apart by their accounts). */
    static final String GRANTED = "Platform fee Kudos granted";
    static final String VOIDED = "Platform fee Kudos expired";

    /**
     * @param source    where it came from: {@code ONBOARDING}, {@code MANUAL}, {@code CERTIFIED_TOP_UP} ({@code LIGHT}
     *                  with F-S4)
     * @param sourceKey what it stands for, once per sponsor
     * @param amount    in yen
     * @param validDays for how many days; none: {@code qb.fee-credit.valid-days}
     */
    public record GrantInput(@NotBlank @Size(max = 128) String sponsorId,
        @NotBlank @Pattern(regexp = "ONBOARDING|LIGHT|MANUAL|CERTIFIED_TOP_UP") String source,
        @NotBlank @Size(max = QbFeeAccount.SOURCE_KEY_LENGTH) String sourceKey,
        @NotNull @DecimalMin("1") @DecimalMax(FeeProcesses.MAX_AMOUNT) @Digits(integer = 13, fraction = 0)
        BigDecimal amount,
        @Min(1) @Max(FeeCreditRules.MAX_VALID_DAYS) Integer validDays) {}

    /**
     * @param granted       whether this call granted it (false: the sponsor had it for this key already, given back)
     * @param transactionId the grant's posting; none when nothing was granted now
     */
    public record GrantOutput(String grantId, boolean granted, BigDecimal amount, Instant expiresAt,
        String operatorId, String transactionId) {}

    /** @param sponsorId the sponsor confirmed */
    public record OnboardingInput(@NotBlank @Size(max = 128) String sponsorId) {}

    public record GrantRef(@NotBlank @Size(max = 36) String grantId) {}

    public record SponsorRef(@NotBlank @Size(max = 128) String sponsorId) {}

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String EXISTING = "existing";
    static final String VERIFIED = "verified";
    static final String ASSIGNMENT = "assignment";
    static final String CREDIT_WALLET = "creditWallet";
    static final String NEW_ID = "newId";
    static final String NEW_VALUES = "newValues";
    static final String POST_INPUT = "postInput";
    static final String POSTING = "posting";
    static final String SPONSOR = "sponsor";
    static final String BAN = "ban";
    static final String AMOUNT = "amount";
    static final String GRANTED_NOW = "grantedNow";
    static final String FOUND = "found";
    static final String LOT = "lot";
    static final String LOTS = "lots";
    static final String LAST_REMINDED = "lastReminded";
    static final String OUTCOME = "outcome";
    static final String PAYLOAD = "payload";

    private FeeCredits() {}

    // ---- the grant ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<GrantInput, GrantOutput, ProcessContext> grant() {
        return ProcessDefinition.define(GRANT, 1, GrantInput.class, GrantOutput.class, ProcessContext.class, pb -> pb
            .description("Grants a sponsor a lot of platform fee Kudos, once per source key, as the platform's promotion "
                + "expenses (under the wallet lock the caller holds).")
            .permissions(QbPermissions.FEE_SYSTEM)
            .subProcessOnly()
            .contextFactory(FeeProcesses::context)
            .outputMapper(FeeCredits::grantOutput)
            .step("Load the validity", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                QbParams.FEE_CREDIT_VALID_DAYS))
            .step("Read the lot, the sponsor's operator and the wallet", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("sponsorId", FeeGate.ids(grantInput(ctx).sponsorId())),
                        new QueryPredicate.Eq("sourceKey", grantInput(ctx).sourceKey())))).limit(1).build(), EXISTING),
                QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT),
                    ctx -> FeeProcesses.bySponsor("userId", grantInput(ctx).sponsorId()), ASSIGNMENT),
                QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                    ctx -> QbWallets.query(QbWallets.FEE_CREDIT, grantInput(ctx).sponsorId()), CREDIT_WALLET)))
            .compute("Record the lot", (metadata, ctx) -> recordGrant(ctx))
            // Written first: the posting names it as its source.
            .step("Save the lot", SaveChanges.now())
            .step("Post the grant", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT),
                LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING)));
    }

    static void recordGrant(ProcessContext ctx) {
        if (FeeGate.first(ctx, EXISTING) != null) {
            return;
        }
        GrantInput input = grantInput(ctx);
        BigDecimal days = input.validDays() != null ? BigDecimal.valueOf(input.validDays())
            : ctx.get(PARAMS, ParamValues.class).get(QbParams.FEE_CREDIT_VALID_DAYS, BigDecimal.class);
        Instant expiresAt = FeeCreditRules.expiresAt(ctx.opTime(), days).orElseThrow(() ->
            new BusinessRuleViolationException(new Violation(null, FeeCodes.FEE_PARAMS,
                "qb.fee-credit.valid-days is not a number of days from 1 to 999", Map.of())));
        EntityInstance assignment = FeeGate.first(ctx, ASSIGNMENT);
        BigDecimal amount = input.amount().setScale(0);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sponsorId", input.sponsorId());
        values.put("source", input.source());
        values.put("sourceKey", input.sourceKey());
        values.put("operatorId", assignment == null ? null : String.valueOf((Object) assignment.get("operatorId")));
        values.put("amount", amount);
        values.put("remaining", amount);
        values.put("voidedAmount", BigDecimal.ZERO);
        values.put("grantedAt", ctx.opTime());
        values.put("expiresAt", expiresAt);
        String grantId = String.valueOf(ctx.changes().insert(QbFeeAccount.GRANT, values));
        ctx.put(NEW_ID, grantId);
        ctx.put(NEW_VALUES, values);
        QbWallets.change(ctx, FeeGate.first(ctx, CREDIT_WALLET), QbWallets.FEE_CREDIT, input.sponsorId(), amount, 0,
            BigDecimal.ZERO);
        Map<String, String> party = Map.of(QbLedger.PARTY, input.sponsorId());
        ctx.put(POST_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), GRANTED, input.source(), List.of(
            new LedgerProcesses.Line(QbLedger.FEE_CREDIT_PROMOTION, Direction.DEBIT, amount, null, party),
            new LedgerProcesses.Line(QbLedger.FEE_CREDIT, Direction.CREDIT, amount, null, party)),
            QbFeeAccount.GRANT, grantId));
    }

    @SuppressWarnings("unchecked")
    static GrantOutput grantOutput(ProcessContext ctx) {
        if (ctx.contains(NEW_ID)) {
            Map<String, Object> values = (Map<String, Object>) ctx.get(NEW_VALUES);
            return new GrantOutput((String) ctx.get(NEW_ID), true, (BigDecimal) values.get("amount"),
                (Instant) values.get("expiresAt"), (String) values.get("operatorId"),
                ctx.contains(POSTING) ? ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId() : null);
        }
        EntityInstance existing = FeeGate.first(ctx, EXISTING);
        return existing == null ? new GrantOutput(null, false, null, null, null, null)
            : new GrantOutput(String.valueOf(existing.id()), false, FeeGate.money(existing.get("amount")),
                (Instant) existing.get("expiresAt"), existing.get("operatorId"), null);
    }

    // ---- the onboarding, confirmed by a lead of the sponsor's operator ------------------------------------------------

    public static ProcessDefinition<OnboardingInput, GrantOutput, ProcessContext> onboarding() {
        return ProcessDefinition.define(ONBOARDING, 1, OnboardingInput.class, GrantOutput.class, ProcessContext.class,
            pb -> {
                pb.description("A lead of the sponsor's operator confirms an approved sponsor's details are complete, "
                        + "which grants them the onboarding platform fee Kudos (once).")
                    .permissions(QbPermissions.FEE_CREDIT_ONBOARDING_CONFIRM)
                    .contextFactory(FeeProcesses::context)
                    .outputMapper(ctx -> ctx.get(GRANTED_NOW, GrantOutput.class));
                OperatorChecks.member(pb);
                OperatorChecks.ownSponsor(pb, ctx -> onboardingInput(ctx).sponsorId());
                pb.step("Load the switch and the amounts", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                        QbParams.FEE_CREDIT_GRANT_ENABLED, QbParams.FEE_CREDIT_GRANT_BASIC,
                        QbParams.FEE_CREDIT_GRANT_CERTIFIED))
                    .compute("Check the lead and the switch", (metadata, ctx) -> checkConfirmation(ctx))
                    .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbWallets.lockName(QbWallets.SPONSOR, onboardingInput(ctx).sponsorId())))
                    .step("Read the sponsor, their ban and their onboarding lot", QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR),
                            ctx -> FeeProcesses.bySponsor("userId", onboardingInput(ctx).sponsorId()), SPONSOR),
                        QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.BAN), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.In("userId", AccountEditing.uuids(onboardingInput(ctx).sponsorId())))
                            .limit(1).build(), BAN),
                        QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.And(List.of(
                                new QueryPredicate.In("sponsorId", FeeGate.ids(onboardingInput(ctx).sponsorId())),
                                new QueryPredicate.Eq("sourceKey", FeeCreditRules.ONBOARDING_KEY)))).limit(1).build(),
                            EXISTING),
                        // F-M3: verified now (read under the wallet lock, which a verification's top-up takes too).
                        QueryEntities.query(com.jabiz.quizbuks.verification.QbVerifications.DEFAULT_DATASET,
                            ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                                new QueryPredicate.Eq("sponsorId", onboardingInput(ctx).sponsorId()),
                                new QueryPredicate.Eq("status", com.jabiz.quizbuks.verification.QbVerifications.APPROVED))))
                                .limit(1).build(), VERIFIED)))
                    .compute("Check the sponsor", (metadata, ctx) -> checkSponsor(ctx))
                    .step("Grant the onboarding", CallProcess.<ProcessContext>of(GRANT, 1,
                        ctx -> new GrantInput(onboardingInput(ctx).sponsorId(), QbFeeAccount.ONBOARDING,
                            FeeCreditRules.ONBOARDING_KEY, ctx.get(AMOUNT, BigDecimal.class), null), GRANTED_NOW));
            });
    }

    static void checkConfirmation(ProcessContext ctx) {
        if (!Boolean.TRUE.equals(ctx.get(OperatorChecks.LEAD))) {
            throw new BusinessRuleViolationException(new Violation(null, FeeCodes.FEE_CREDIT_LEAD,
                "Only a lead of the sponsor's operator confirms the sponsor", Map.of()));
        }
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        if (!Boolean.TRUE.equals(params.get(QbParams.FEE_CREDIT_GRANT_ENABLED, Boolean.class))) {
            throw new BusinessRuleViolationException(new Violation(null, FeeCodes.FEE_CREDIT_OFF,
                "The onboarding platform fee Kudos are switched off", Map.of()));
        }
    }

    /**
     * The onboarding amount (F-M3): the certified one for a Sponsor whose company is verified now, otherwise the basic
     * one (a verification afterwards tops it up, {@link CertifiedTopUps}).
     */
    static void onboardingAmount(ProcessContext ctx) {
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        boolean certified = FeeGate.first(ctx, VERIFIED) != null;
        BigDecimal amount = FeeCreditRules.onboardingAmount(certified,
                params.get(QbParams.FEE_CREDIT_GRANT_BASIC, BigDecimal.class),
                params.get(QbParams.FEE_CREDIT_GRANT_CERTIFIED, BigDecimal.class))
            .orElseThrow(() -> new BusinessRuleViolationException(new Violation(null, FeeCodes.FEE_PARAMS,
                "The onboarding amount is not a whole positive number of yen", Map.of())));
        ctx.put(AMOUNT, amount);
    }

    static void checkSponsor(ProcessContext ctx) {
        EntityInstance sponsor = FeeGate.first(ctx, SPONSOR);
        EntityInstance ban = FeeGate.first(ctx, BAN);
        if (sponsor == null || !SponsorStatus.APPROVED.equals(sponsor.get("reviewStatus"))
            || BanRules.banned(ban == null ? null : ban.attributes())) {
            throw new BusinessRuleViolationException(new Violation("sponsorId", FeeCodes.FEE_CREDIT_SPONSOR,
                "Only an approved sponsor who is not banned is granted platform fee Kudos", Map.of()));
        }
        if (FeeGate.first(ctx, EXISTING) != null) {
            throw new BusinessRuleViolationException(new Violation("sponsorId", FeeCodes.FEE_CREDIT_GIVEN,
                "The sponsor received their onboarding platform fee Kudos already", Map.of()));
        }
        onboardingAmount(ctx);
    }

    // ---- the expiry -------------------------------------------------------------------------------------------------

    public static ProcessDefinition<GrantRef, FeeProcesses.ItemOutput, ProcessContext> expireItem() {
        return ProcessDefinition.define(EXPIRE_ITEM, 1, GrantRef.class, FeeProcesses.ItemOutput.class,
            ProcessContext.class, pb -> pb
                .description("Voids what is left of an expired lot of platform fee Kudos (the promotion expenses "
                    + "taken back).")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory(FeeProcesses::context)
                .outputMapper(ctx -> new FeeProcesses.ItemOutput(FeeGate.first(ctx, LOT) == null ? null
                    : FeeGate.first(ctx, LOT).get("sponsorId"), (String) ctx.get(OUTCOME),
                    ctx.get(INPUT, GrantRef.class).grantId()))
                .step("Find the lot", QueryEntities.<ProcessContext>of(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT),
                    ctx -> FeeProcesses.byId("grantId", ctx.get(INPUT, GrantRef.class).grantId()), FOUND))
                .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(ctx -> found(ctx) == null ? null
                    : QbWallets.lockName(QbWallets.SPONSOR, found(ctx).get("sponsorId"))))
                .step("Read the lot and the wallet again", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT), ctx -> FeeProcesses.byId(
                        "grantId", found(ctx) == null ? null : String.valueOf(found(ctx).id())), LOT),
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET), ctx -> QbWallets.query(
                        QbWallets.FEE_CREDIT, found(ctx) == null ? null : found(ctx).get("sponsorId")), CREDIT_WALLET)))
                .compute("Void what is left", (metadata, ctx) -> voidLot(ctx))
                .step("Post the expiry", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT),
                    LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING)));
    }

    static void voidLot(ProcessContext ctx) {
        EntityInstance lot = FeeGate.first(ctx, LOT);
        ctx.put(OUTCOME, "NONE");
        if (lot == null) {
            return;
        }
        BigDecimal voided = FeeCreditRules.voidable(FeeGate.money(lot.get("remaining")), (Instant) lot.get("expiresAt"),
            ctx.opTime());
        if (voided.signum() == 0) {
            return;
        }
        String sponsorId = lot.get("sponsorId");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("remaining", BigDecimal.ZERO);
        // Added to what was voided before: a lot a voided invoice gave back to after it expired is voided again
        // (F-S4b review M3: the promotion's check compares the amount less everything voided).
        values.put("voidedAmount", FeeGate.money(lot.get("voidedAmount")).add(voided));
        values.put("voidedAt", ctx.opTime());
        ctx.changes().update(QbFeeAccount.GRANT, lot.id(), lot.version(), values);
        QbWallets.change(ctx, FeeGate.first(ctx, CREDIT_WALLET), QbWallets.FEE_CREDIT, sponsorId, voided.negate(), 0,
            BigDecimal.ZERO);
        Map<String, String> party = Map.of(QbLedger.PARTY, sponsorId);
        ctx.put(POST_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), VOIDED, lot.get("source"), List.of(
            new LedgerProcesses.Line(QbLedger.FEE_CREDIT, Direction.DEBIT, voided, null, party),
            new LedgerProcesses.Line(QbLedger.FEE_CREDIT_PROMOTION, Direction.CREDIT, voided, null, party)),
            QbFeeAccount.GRANT, String.valueOf(lot.id())));
        ctx.put(OUTCOME, "VOIDED");
    }

    // ---- the reminder -----------------------------------------------------------------------------------------------

    public static ProcessDefinition<SponsorRef, FeeProcesses.ItemOutput, ProcessContext> remindItem() {
        return ProcessDefinition.define(REMIND_ITEM, 1, SponsorRef.class, FeeProcesses.ItemOutput.class,
            ProcessContext.class, pb -> pb
                .description("Tells a sponsor which lots of their platform fee Kudos expire soon: each lot once, a "
                    + "sponsor at most once in 24 hours.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory(FeeProcesses::context)
                .outputMapper(ctx -> new FeeProcesses.ItemOutput(sponsorRef(ctx).sponsorId(), (String) ctx.get(OUTCOME),
                    null))
                .step("Load the notice", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.FEE_CREDIT_EXPIRY_NOTICE_DAYS))
                .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.SPONSOR, sponsorRef(ctx).sponsorId())))
                .step("Read the open lots and the last reminder", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("sponsorId", FeeGate.ids(sponsorRef(ctx).sponsorId())),
                            new QueryPredicate.Gt("remaining", BigDecimal.ZERO),
                            new QueryPredicate.Gt("expiresAt", ctx.opTime()))))
                        .orderBy("expiresAt", true).limit(MAX_LOTS).build(), LOTS),
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("sponsorId", FeeGate.ids(sponsorRef(ctx).sponsorId())),
                            new QueryPredicate.IsNotNull("remindedAt"))))
                        .orderBy("remindedAt", false).limit(1).build(), LAST_REMINDED)))
                .compute("Decide", (metadata, ctx) -> remind(ctx))
                .step("Ask the mail to tell the sponsor", PublishEvent.<ProcessContext>when(
                    ctx -> "REMINDED".equals(ctx.get(OUTCOME)), EXPIRING_EVENT, ctx -> ctx.get(PAYLOAD))));
    }

    static void remind(ProcessContext ctx) {
        ctx.put(OUTCOME, "NONE");
        EntityInstance last = FeeGate.first(ctx, LAST_REMINDED);
        if (!FeeCreditRules.reminderAllowed(last == null ? null : (Instant) last.get("remindedAt"), ctx.opTime())) {
            return;
        }
        BigDecimal noticeDays = ctx.get(PARAMS, ParamValues.class).get(QbParams.FEE_CREDIT_EXPIRY_NOTICE_DAYS,
            BigDecimal.class);
        List<EntityInstance> due = new ArrayList<>();
        for (EntityInstance lot : FeeGate.list(ctx, LOTS)) {
            if (FeeCreditRules.remindable(FeeGate.money(lot.get("remaining")), (Instant) lot.get("expiresAt"),
                (Instant) lot.get("remindedAt"), ctx.opTime(), noticeDays)) {
                due.add(lot);
            }
        }
        if (due.isEmpty()) {
            return;
        }
        BigDecimal amount = BigDecimal.ZERO;
        for (EntityInstance lot : due) {
            amount = amount.add(FeeGate.money(lot.get("remaining")));
            ctx.changes().update(QbFeeAccount.GRANT, lot.id(), lot.version(), Map.of("remindedAt", ctx.opTime()));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sponsorId", sponsorRef(ctx).sponsorId());
        payload.put("amount", amount.setScale(0).toPlainString());
        payload.put("count", due.size());
        // The lots are read the earliest to expire first.
        payload.put("expiresAt", String.valueOf((Object) due.getFirst().get("expiresAt")));
        ctx.put(PAYLOAD, payload);
        ctx.put(OUTCOME, "REMINDED");
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    private static EntityInstance found(ProcessContext ctx) {
        return FeeGate.first(ctx, FOUND);
    }

    static GrantInput grantInput(ProcessContext ctx) {
        return ctx.get(INPUT, GrantInput.class);
    }

    static OnboardingInput onboardingInput(ProcessContext ctx) {
        return ctx.get(INPUT, OnboardingInput.class);
    }

    static SponsorRef sponsorRef(ProcessContext ctx) {
        return ctx.get(INPUT, SponsorRef.class);
    }
}

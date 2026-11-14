package com.jabiz.quizbuks.fee;

import com.jabiz.event.DomainEvent;
import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F-M15 b (docs/quizbuks/plans/impl/F-M15.md §4.1, §5): what becomes of an erased sponsor's platform fee account.
 *
 * <p>{@value #REGISTER} answers the platform's {@code jabiz.account.erased} on a subscription of its own (not in
 * {@code QB_SPONSOR_ERASED}, whose sub-processes may fail and exhaust the event's retries): a sponsor who had any of the
 * account (a cash or granted credit wallet row, a lot of granted credit, an invoice, a prepayment) gets one
 * {@link QbSponsorClosures#CLOSURE} row, waiting; nobody else gets one. It takes no lock and calls no sub-process.
 */
public final class MoneyClose {

    public static final String REGISTER = "QB_SPONSOR_CLOSURE_REGISTER";
    /** The wind-up's last step for one lot of granted credit (sub-process; the caller holds the wallet lock). */
    public static final String VOID_LOT = "QB_FEE_CREDIT_VOID_LOT";
    /** The posting's description (only a description: the monthly report's kind is the expiry's). */
    static final String VOIDED_ON_ERASE = "Platform fee Kudos voided on the account's deletion";
    /** The platform's event that an account was erased (its consumers run after the erasure committed). */
    public static final String ACCOUNT_ERASED_EVENT = "jabiz.account.erased";
    public static final String REGISTER_SUBSCRIPTION = "qb.account-erased-money";
    /** A closure was registered: the wind-up runs ({@code sponsorId}). */
    public static final String DUE_EVENT = "qb.sponsor.money-close-due";

    static final String INPUT = "input";
    static final String MONEY_WALLET = "moneyWallet";
    static final String MONEY_GRANT = "moneyGrant";
    static final String MONEY_INVOICE = "moneyInvoice";
    static final String MONEY_PREPAYMENT = "moneyPrepayment";
    static final String CLOSURE = "closure";
    static final String REGISTERED = "registered";
    static final String LOT = "lot";
    static final String CREDIT_WALLET = "creditWallet";
    static final String POST_INPUT = "postInput";
    static final String POSTING = "posting";
    static final String VOIDED = "voided";

    /** The wallet rows that are the platform fee account (a zero balance counts: the account was there). */
    static final List<String> ACCOUNT_WALLETS = List.of(QbWallets.CASH, QbWallets.FEE_CREDIT);

    /** @param userId the erased user */
    public record RegisterInput(@NotBlank @Size(max = 128) String userId) {}

    /** @param registered whether a closure was written now (false: nothing of the account, or registered before) */
    public record RegisterOutput(String userId, boolean registered) {}

    /**
     * @param sponsorId the sponsor whose wallet lock the caller holds (the lot must be theirs)
     * @param grantId   the lot to void
     */
    public record VoidLotInput(@NotBlank @Size(max = 128) String sponsorId, @NotBlank @Size(max = 36) String grantId) {}

    /**
     * @param marked whether the lot was marked voided now (false: voided before, expired, or not found)
     * @param voided what was left of it, voided
     */
    public record VoidLotOutput(String grantId, boolean marked, BigDecimal voided) {}

    private MoneyClose() {}

    // ---- the registration -------------------------------------------------------------------------------------------

    public static ProcessDefinition<RegisterInput, RegisterOutput, ProcessContext> register() {
        return ProcessDefinition.define(REGISTER, 1, RegisterInput.class, RegisterOutput.class, ProcessContext.class,
            pb -> pb
                .description("After an account was erased: registers the wind-up of the sponsor's platform fee account "
                    + "when they had one.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory(FeeProcesses::context)
                .outputMapper(ctx -> new RegisterOutput(userId(ctx), Boolean.TRUE.equals(ctx.get(REGISTERED))))
                // One statement, one row of each at most: whether the user had the account, and a closure already.
                .step("Read what the user had of the account", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET), ctx -> one(
                        new QueryPredicate.Eq("ownerId", userId(ctx)),
                        new QueryPredicate.In("kind", List.copyOf(ACCOUNT_WALLETS))), MONEY_WALLET),
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT),
                        ctx -> one(new QueryPredicate.Eq("sponsorId", userId(ctx))), MONEY_GRANT),
                    QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE),
                        ctx -> one(new QueryPredicate.Eq("sponsorId", userId(ctx))), MONEY_INVOICE),
                    QueryEntities.query(QbFees.defaultDataset(QbPrepayments.PREPAYMENT),
                        ctx -> one(new QueryPredicate.Eq("sponsorId", userId(ctx))), MONEY_PREPAYMENT),
                    QueryEntities.query(QbFees.defaultDataset(QbSponsorClosures.CLOSURE),
                        ctx -> one(new QueryPredicate.Eq("sponsorId", userId(ctx))), CLOSURE)))
                .compute("Register the wind-up", (metadata, ctx) -> registerClosure(ctx))
                .step("Ask for the wind-up", PublishEvent.<ProcessContext>when(
                    ctx -> Boolean.TRUE.equals(ctx.get(REGISTERED)), DUE_EVENT,
                    ctx -> Map.of("sponsorId", userId(ctx)))));
    }

    /** Whether a user had the platform fee account: any one row of the five (the definition ERASED_NO_CLOSURE uses). */
    static boolean hadAccount(ProcessContext ctx) {
        return FeeGate.first(ctx, MONEY_WALLET) != null || FeeGate.first(ctx, MONEY_GRANT) != null
            || FeeGate.first(ctx, MONEY_INVOICE) != null || FeeGate.first(ctx, MONEY_PREPAYMENT) != null;
    }

    static void registerClosure(ProcessContext ctx) {
        if (FeeGate.first(ctx, CLOSURE) != null || !hadAccount(ctx)) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sponsorId", userId(ctx));
        values.put("status", QbSponsorClosures.WAITING);
        values.put("erasedAt", ctx.opTime());
        ctx.changes().insert(QbSponsorClosures.CLOSURE, values);
        ctx.put(REGISTERED, true);
    }

    // ---- voiding one lot --------------------------------------------------------------------------------------------

    /**
     * Voids what is left of a lot of granted credit on an erased sponsor's wind-up (§5.3), under the wallet lock the
     * caller holds: the remaining amount to 0, added to what was voided, {@code voidedAt} written even when nothing was
     * left (what a prepayment gives back to the lot later is voided on arrival), the wallet's cache, and the posting
     * (debit 2190, credit 5140, the lot as its source, as an expiry). Only the wind-up's last step calls it.
     */
    public static ProcessDefinition<VoidLotInput, VoidLotOutput, ProcessContext> voidLot() {
        return ProcessDefinition.define(VOID_LOT, 1, VoidLotInput.class, VoidLotOutput.class, ProcessContext.class,
            pb -> pb
                .description("Voids what is left of a lot of platform fee Kudos when the sponsor's account was deleted "
                    + "(a part of the wind-up).")
                .permissions(QbPermissions.FEE_SYSTEM)
                .subProcessOnly()
                .contextFactory(FeeProcesses::context)
                .outputMapper(MoneyClose::voidLotOutput)
                .step("Read the lot and the wallet", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT),
                        ctx -> FeeProcesses.byId("grantId", grantId(ctx)), LOT),
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET), ctx -> QbWallets.query(
                        QbWallets.FEE_CREDIT, ctx.get(INPUT, VoidLotInput.class).sponsorId()), CREDIT_WALLET)))
                .compute("Void what is left", (metadata, ctx) -> markVoided(ctx))
                .step("Post it", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT),
                    LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING)));
    }

    static void markVoided(ProcessContext ctx) {
        EntityInstance lot = FeeGate.first(ctx, LOT);
        if (lot == null || !ctx.get(INPUT, VoidLotInput.class).sponsorId().equals(lot.get("sponsorId"))
            || !FeeCreditRules.voidableOnErase((Instant) lot.get("expiresAt"), (Instant) lot.get("voidedAt"),
            ctx.opTime())) {
            return;
        }
        BigDecimal voided = FeeCreditRules.voidedOnErase(FeeGate.money(lot.get("remaining")));
        String sponsorId = lot.get("sponsorId");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("remaining", BigDecimal.ZERO);
        values.put("voidedAmount", FeeGate.money(lot.get("voidedAmount")).add(voided));
        values.put("voidedAt", ctx.opTime());
        ctx.changes().update(QbFeeAccount.GRANT, lot.id(), lot.version(), values);
        ctx.put(VOIDED, voided);
        if (voided.signum() == 0) {
            return;
        }
        QbWallets.change(ctx, FeeGate.first(ctx, CREDIT_WALLET), QbWallets.FEE_CREDIT, sponsorId, voided.negate(), 0,
            BigDecimal.ZERO);
        Map<String, String> party = Map.of(QbLedger.PARTY, sponsorId);
        ctx.put(POST_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), VOIDED_ON_ERASE, lot.get("source"), List.of(
            new LedgerProcesses.Line(QbLedger.FEE_CREDIT, Direction.DEBIT, voided, null, party),
            new LedgerProcesses.Line(QbLedger.FEE_CREDIT_PROMOTION, Direction.CREDIT, voided, null, party)),
            QbFeeAccount.GRANT, String.valueOf(lot.id())));
    }

    private static String grantId(ProcessContext ctx) {
        return ctx.get(INPUT, VoidLotInput.class).grantId();
    }

    static VoidLotOutput voidLotOutput(ProcessContext ctx) {
        BigDecimal voided = ctx.get(VOIDED) instanceof BigDecimal amount ? amount : null;
        return new VoidLotOutput(grantId(ctx), voided != null, voided == null ? BigDecimal.ZERO : voided);
    }

    private static EntityQuery one(QueryPredicate... predicates) {
        return EntityQuery.builder().where(predicates.length == 1 ? predicates[0]
            : new QueryPredicate.And(List.of(predicates))).limit(1).build();
    }

    private static String userId(ProcessContext ctx) {
        return ctx.get(INPUT, RegisterInput.class).userId();
    }

    /** The input of the registration: the user the platform's event names. */
    static RegisterInput registerInput(DomainEvent event) {
        return new RegisterInput(String.valueOf(event.payload().get("userId")));
    }
}

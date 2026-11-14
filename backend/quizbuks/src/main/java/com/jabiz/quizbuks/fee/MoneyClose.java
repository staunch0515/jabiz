package com.jabiz.quizbuks.fee;

import com.jabiz.event.DomainEvent;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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

    /** The wallet rows that are the platform fee account (a zero balance counts: the account was there). */
    static final List<String> ACCOUNT_WALLETS = List.of(QbWallets.CASH, QbWallets.FEE_CREDIT);

    /** @param userId the erased user */
    public record RegisterInput(@NotBlank @Size(max = 128) String userId) {}

    /** @param registered whether a closure was written now (false: nothing of the account, or registered before) */
    public record RegisterOutput(String userId, boolean registered) {}

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

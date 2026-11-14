package com.jabiz.quizbuks.fee;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.Violation;
import com.jabiz.runtime.param.ParamEntities;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A sponsor's AI credit (F-M9, docs/quizbuks/plans/impl/F-M9.md §2, §4): one long-lived prepayment a sponsor
 * ({@code QbPrepayment}, purpose {@code AI}, no publication, account 2180), bought from the account that pays the
 * platform's fees (fee credit first, then cash) and used by the AI fees of the months. Buying it is not a fee: no revenue
 * and no tax. Every movement of it is kept, written once ({@value #MOVEMENT}): the purchases, the monthly charges, the
 * returns and the expiries of fee credit in it.
 *
 * <p>The purchase ({@value #BUY}) holds only the sponsor's wallet lock (the lock order, plan §4 item 7:
 * {@code qb.ai:<sponsor>} before {@code qb.wallet}; a purchase takes no AI lock). The first sets the credit aside
 * ({@code QB_PREPAYMENT_FREEZE}), the next ones raise it ({@code QB_PREPAYMENT_TOP_UP}): its {@code amount} is what was
 * ever bought, its {@code remaining} what is left.
 */
public final class AiCredits {

    public static final String MOVEMENT = "QbAiCreditMovement";
    /** F-M9b: the sponsor's own movements (read only, scoped to the caller), for the AI credit page. */
    public static final String SPONSOR_MOVEMENTS = "urn:jabiz:dataset:sponsor:" + MOVEMENT;
    public static final String MOVEMENT_KIND_DICTIONARY = "urn:jabiz:dict:quizbuks:ai-credit-movement-kind";
    public static final String BUY_KIND = "BUY";
    public static final String RETURN_KIND = "RETURN";
    public static final String CHARGE_KIND = "CHARGE";
    public static final String EXPIRE_KIND = "EXPIRE";
    public static final List<String> MOVEMENT_KINDS = List.of(BUY_KIND, RETURN_KIND, CHARGE_KIND, EXPIRE_KIND);

    public static final String BUY = "QB_AI_CREDIT_BUY";

    /** Longest request key, as the platform's idempotency keys. */
    public static final int REQUEST_KEY_LENGTH = 100;

    /**
     * @param amount     whole yen, at least {@code qb.ai.credit.min-purchase} when it is set
     * @param requestKey the page's key for this purchase, required: the page sends the same value as the request's
     *                   Idempotency-Key, so a retried submission replays the first answer; a second purchase with a key
     *                   the sponsor already used is refused
     */
    public record BuyInput(@NotNull @DecimalMin("1") @DecimalMax(FeeProcesses.MAX_AMOUNT) @Digits(integer = 13,
        fraction = 0) BigDecimal amount, @NotBlank @Size(max = REQUEST_KEY_LENGTH) String requestKey) {}

    /**
     * @param credit  of the amount, from the fee credit
     * @param cash    of it, from the cash
     * @param balance the credit's remaining after the purchase
     */
    public record BuyOutput(String prepaymentId, BigDecimal amount, BigDecimal credit, BigDecimal cash,
        BigDecimal balance) {}

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String CREDITS = "credits";
    static final String LOTS = "lots";
    static final String CASH_WALLET = "cashWallet";
    static final String OPEN_CREDIT = "openCredit";
    static final String SAME_REQUEST = "sameRequest";
    static final String FROZEN = "frozen";
    static final String OUTPUT = "output";

    private AiCredits() {}

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    public static final EntityDefinition MOVEMENT_ENTITY = EntityDefinition.define(MOVEMENT, eb -> {
        eb.physicalTable("qb_ai_credit_movement_version");
        eb.primaryKey("movementId");
        eb.field("movementId", f -> f.physicalColumn("movement_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:ai-credit-movement"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        eb.field("prepaymentId", f -> f.physicalColumn("prepayment_id").immutable(true).required(true).asText(36));
        eb.field("kind", f -> f.physicalColumn("kind").immutable(true).required(true)
            .asCode(MOVEMENT_KIND_DICTIONARY, MOVEMENT_KINDS.toArray(String[]::new)));
        // Signed: a purchase is in (positive), the rest out (negative); credit + cash = amount.
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("credit", f -> f.physicalColumn("credit").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("cash", f -> f.physicalColumn("cash").immutable(true).required(true).asMonetary("JPY", 0));
        // The credit's remaining after the movement.
        eb.field("balance", f -> f.physicalColumn("balance").immutable(true).required(true).asMonetary("JPY", 0));
        // A charge's operator (the fee's).
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).asText(36));
        // A purchase's request key, a charge's id, a fee credit grant's id.
        eb.field("reference", f -> f.physicalColumn("reference").immutable(true).asText(REQUEST_KEY_LENGTH));
        eb.field("movedAt", f -> f.physicalColumn("moved_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("sponsorId", "kind", "amount", "credit", "cash", "balance", "operatorId", "movedAt")
            .filters("sponsorId", "kind", "operatorId", "movedAt")
            .sorts("movedAt")
            .defaultSort("movedAt", false));
    });

    // ---- buy --------------------------------------------------------------------------------------------------------

    /**
     * {@value #BUY}: the sponsor buys AI credit from their account (fee credit first, then cash), under their wallet
     * lock. Refused while the AI fee is off ({@link FeeCodes#AI_FEE_OFF}), below the least purchase
     * ({@link FeeCodes#AI_CREDIT_MIN}), with a key already used ({@link FeeCodes#AI_CREDIT_REPEATED}) and when the
     * account cannot pay it ({@link FeeCodes#AI_CREDIT_FUNDS_SHORT}). The sponsor's onboarding need not be approved
     * (question 15): drafting from a website comes before it.
     */
    public static ProcessDefinition<BuyInput, BuyOutput, ProcessContext> buy() {
        return ProcessDefinition.define(BUY, 1, BuyInput.class, BuyOutput.class, ProcessContext.class, pb -> pb
            .description("The sponsor buys AI credit from the account that pays the platform's fees (fee credit "
                + "first, then cash).")
            .permissions(QbPermissions.AI_USE)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, BuyOutput.class))
            .step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(
                ctx -> QbWallets.lockName(QbWallets.SPONSOR, ctx.request().actorId())))
            .step("Read the account, the credit and the parameters", QueryEntities.<ProcessContext>all(
                QueryEntities.query(ParamEntities.DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In(ParamEntities.KEY, List.of(QbParams.AI_FEE_ENABLED,
                        QbParams.AI_CREDIT_MIN_PURCHASE))).limit(4).build(), PARAMS),
                QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT),
                    ctx -> FeeSettlement.credits(ctx.request().actorId(), ctx.opTime()), CREDITS),
                QueryEntities.query(CashLots.dataset(), ctx -> CashLots.open(ctx.request().actorId()), LOTS),
                QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                    ctx -> QbWallets.query(QbWallets.CASH, ctx.request().actorId()), CASH_WALLET),
                QueryEntities.query(QbFees.defaultDataset(QbPrepayments.PREPAYMENT), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("sponsorId", ctx.request().actorId()),
                        new QueryPredicate.Eq("purpose", QbPrepayments.AI),
                        new QueryPredicate.Eq("status", QbPrepayments.OPEN)))).limit(1).build(), OPEN_CREDIT),
                QueryEntities.query(defaultDataset(MOVEMENT), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("sponsorId", ctx.request().actorId()),
                        new QueryPredicate.Eq("kind", BUY_KIND),
                        new QueryPredicate.Eq("reference", input(ctx).requestKey())))).limit(1).build(),
                    SAME_REQUEST)))
            .compute("Check the purchase", (metadata, ctx) -> check(ctx))
            .step("Set the credit aside", CallProcess.<ProcessContext>when(ctx -> FeeGate.first(ctx, OPEN_CREDIT) == null,
                Prepayments.FREEZE, 1, ctx -> new Prepayments.FreezeInput(ctx.request().actorId(), null,
                    QbPrepayments.AI, QbPrepayments.PREPAID, input(ctx).amount(), BigDecimal.ZERO, null, null), FROZEN))
            .step("Raise the credit", CallProcess.<ProcessContext>when(ctx -> FeeGate.first(ctx, OPEN_CREDIT) != null,
                Prepayments.TOP_UP, 1, ctx -> new Prepayments.TopUpInput(ctx.request().actorId(), null,
                    QbPrepayments.AI, input(ctx).amount()), FROZEN))
            .compute("Record the purchase", (metadata, ctx) -> recordBuy(ctx)));
    }

    static void check(ProcessContext ctx) {
        BuyInput input = input(ctx);
        Map<String, String> params = params(ctx);
        List<Violation> problems = new ArrayList<>();
        if (!"true".equals(params.get(QbParams.AI_FEE_ENABLED))) {
            throw new BusinessRuleViolationException(new Violation(null, FeeCodes.AI_FEE_OFF,
                "AI credit cannot be bought while the AI fee is off", Map.of()));
        }
        if (FeeGate.first(ctx, SAME_REQUEST) != null) {
            throw new BusinessRuleViolationException(new Violation("requestKey", FeeCodes.AI_CREDIT_REPEATED,
                "This purchase was already made", Map.of()));
        }
        String min = params.get(QbParams.AI_CREDIT_MIN_PURCHASE);
        if (min != null && min.strip().matches("[0-9]{1,13}") && input.amount().compareTo(new BigDecimal(min.strip())) < 0) {
            problems.add(new Violation("amount", FeeCodes.AI_CREDIT_MIN, "The least purchase is " + min.strip()
                + " yen", Map.of("minPurchase", new BigDecimal(min.strip()))));
        }
        if (problems.isEmpty()) {
            FeeAllocation.Result result = FeeAllocation.allocate(Prepayments.credits(FeeGate.list(ctx, CREDITS)),
                CashLots.cash(FeeGate.list(ctx, LOTS)), QbWallets.available(FeeGate.first(ctx, CASH_WALLET)),
                input.amount(), ctx.opTime());
            if (result.paid().compareTo(input.amount()) < 0) {
                problems.add(new Violation("amount", FeeCodes.AI_CREDIT_FUNDS_SHORT, "The account holds "
                    + result.paid() + " yen of the " + input.amount(), Map.of("available", result.paid())));
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
    }

    static void recordBuy(ProcessContext ctx) {
        BuyInput input = input(ctx);
        Prepayments.FreezeOutput frozen = ctx.get(FROZEN, Prepayments.FreezeOutput.class);
        EntityInstance open = FeeGate.first(ctx, OPEN_CREDIT);
        BigDecimal balance = (open == null ? BigDecimal.ZERO : FeeGate.money(open.get("remaining")))
            .add(input.amount());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sponsorId", ctx.request().actorId());
        values.put("prepaymentId", frozen.prepaymentId());
        values.put("kind", BUY_KIND);
        values.put("amount", input.amount());
        values.put("credit", frozen.credit());
        values.put("cash", frozen.cash());
        values.put("balance", balance);
        values.put("reference", input.requestKey());
        values.put("movedAt", ctx.opTime());
        ctx.changes().insert(MOVEMENT, values);
        ctx.put(OUTPUT, new BuyOutput(frozen.prepaymentId(), input.amount(), frozen.credit(), frozen.cash(), balance));
    }

    private static BuyInput input(ProcessContext ctx) {
        return ctx.get(INPUT, BuyInput.class);
    }

    private static Map<String, String> params(ProcessContext ctx) {
        Map<String, String> values = new LinkedHashMap<>();
        for (EntityInstance row : FeeGate.list(ctx, PARAMS)) {
            Object value = row.get(ParamEntities.VALUE);
            values.put(String.valueOf((Object) row.get(ParamEntities.KEY)), value == null ? null : value.toString());
        }
        return values;
    }
}

package com.jabiz.quizbuks.fee;

import com.jabiz.ledger.Direction;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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
 * F-M9a-2 (docs/quizbuks/plans/impl/F-M9.md §4 items 4 and 5): {@value #GIVE_BACK} takes part of a sponsor's AI credit
 * out of it, back to the lots it was bought from, under the sponsor's AI lock and then their wallet lock the caller
 * holds (the return {@code QB_AI_CREDIT_RETURN}, the daily expiry item, and later F-M15's closing of an account).
 *
 * <ul>
 *   <li>{@code RETURN}: the amount asked (none: all that may be given back), at most what may be given back: the credit
 *       less what the open jobs hold ({@code occupied}, the caller's reading under the AI lock) less the expired fee
 *       credit not held by them ({@link FeeCodes#AI_CREDIT_RETURN_ABOVE}). The cash first, then the fee credit, the
 *       latest to expire first ({@link AiFeeRules#giveBack}).</li>
 *   <li>{@code EXPIRE}: the expired fee credit the open jobs do not hold ({@link AiFeeRules#expired}); what they hold
 *       stays to be charged (the month's charge spends it first) and is voided by a later run.</li>
 * </ul>
 *
 * <p>Either way one posting (debit 2180, credit 2160 / 2190) and a negative part a lot, then
 * {@link Prepayments#toLots}, the same as a publication's release: fee credit whose grant expired or was voided goes
 * back and is voided again at once (debit 2160, credit 5140, its source the grant), so an expiry ends on 5140 with the
 * grant as the source, and the credit's parts still add up to what it holds (plan H3). One movement.
 */
public final class AiCreditGiveBack {

    public static final String GIVE_BACK = "QB_AI_CREDIT_GIVE_BACK";
    /** The posting of a return (the monthly report's category {@code AI_CREDIT_RETURNED}). */
    public static final String RETURNED = "AI credit given back";
    /** The posting of an expiry (the monthly report's category {@code AI_CREDIT_VOIDED}). */
    public static final String EXPIRED = "AI credit's fee credit expired";

    /** An expiry's reference (a return's is the credit's own, {@code prepay_ai}): the monthly report tells them apart. */
    public static final String EXPIRED_REFERENCE = "prepay_ai_expired";

    static final String INPUT = "input";
    static final String CREDIT = "credit";
    static final String PLAN = "plan";
    static final String POSTED = "posted";
    static final String OUTPUT = "output";

    private AiCreditGiveBack() {}

    /**
     * @param kind      {@code RETURN} or {@code EXPIRE}
     * @param amount    a return's amount; none: all that may be given back (an expiry's is what expired)
     * @param occupied  what the sponsor's open jobs hold of the credit with tax (unbilled fees and reservations), read
     *                  under the AI lock
     * @param reference a return's request key (a repeated one is the caller's to refuse); an expiry's none
     */
    public record GiveBackInput(@NotBlank @Size(max = 128) String sponsorId,
        @NotBlank @Pattern(regexp = "RETURN|EXPIRE") String kind,
        @DecimalMin("1") @DecimalMax(FeeProcesses.MAX_AMOUNT) @Digits(integer = 13, fraction = 0) BigDecimal amount,
        @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 0) BigDecimal occupied,
        @Size(max = AiCredits.REQUEST_KEY_LENGTH) String reference) {}

    /**
     * @param prepaymentId the AI credit (none: the sponsor has none)
     * @param amount       what left the credit (0: nothing)
     * @param credit       of it, fee credit back to its grants
     * @param voided       of it, fee credit voided (its grant expired or voided)
     * @param cash         of it, cash
     * @param balance      the credit's remaining after it
     */
    public record GiveBackOutput(String prepaymentId, BigDecimal amount, BigDecimal credit, BigDecimal voided,
        BigDecimal cash, BigDecimal balance) {}

    public static ProcessDefinition<GiveBackInput, GiveBackOutput, ProcessContext> giveBack() {
        return ProcessDefinition.define(GIVE_BACK, 1, GiveBackInput.class, GiveBackOutput.class, ProcessContext.class,
            pb -> pb
                .description("Takes part of a sponsor's AI credit out of it, back to the fee credit and cash it was "
                    + "bought from (a return, or the expired fee credit); under the AI and wallet locks the caller "
                    + "holds.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .subProcessOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, GiveBackOutput.class))
                .step("Read the credit and the wallets", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbPrepayments.PREPAYMENT), ctx -> EntityQuery
                        .builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("sponsorId", input(ctx).sponsorId()),
                            new QueryPredicate.Eq("purpose", QbPrepayments.AI),
                            new QueryPredicate.Eq("status", QbPrepayments.OPEN)))).limit(1).build(), CREDIT),
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                        ctx -> QbWallets.query(QbWallets.CASH, input(ctx).sponsorId()), Prepayments.CASH_WALLET),
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                        ctx -> QbWallets.query(QbWallets.FEE_CREDIT, input(ctx).sponsorId()),
                        Prepayments.CREDIT_WALLET)))
                // Plan H4: by lot, never part by part (the credit is never closed).
                .step("Read what it holds by lot", RunTemplate.<ProcessContext>when(
                    ctx -> FeeGate.first(ctx, CREDIT) != null, Prepayments.HELD_TEMPLATE,
                    ctx -> Map.of("prepaymentId", String.valueOf(FeeGate.first(ctx, CREDIT).id())),
                    Prepayments.HELD_LOTS))
                .step("Read the lots", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT),
                        ctx -> FeeSettlement.byIds("grantId", lotIds(ctx, QbFeeAccount.FEE_CREDIT)),
                        Prepayments.GRANTS),
                    QueryEntities.query(CashLots.dataset(),
                        ctx -> FeeSettlement.byIds("lotId", lotIds(ctx, QbFeeAccount.CASH)), Prepayments.LOTS)))
                .compute("Work out what leaves the credit", (metadata, ctx) -> plan(ctx))
                .step("Post it", CallProcess.<ProcessContext>forEach(LedgerProcesses.POST, 1,
                    ctx -> ctx.contains(PLAN) ? List.of(((Plan) ctx.get(PLAN)).post()) : List.of(), POSTED))
                .compute("Give it back", (metadata, ctx) -> record(ctx))
                .step("Void what went back to expired fee credit", CallProcess.<ProcessContext>forEach(
                    LedgerProcesses.POST, 1, ctx -> ctx.contains(Prepayments.VOIDS)
                        ? (List<?>) ctx.get(Prepayments.VOIDS) : List.of(), Prepayments.VOIDED)));
    }

    /** What leaves the credit, lot by lot, and its posting. */
    record Plan(EntityInstance credit, List<Prepayments.Held> back, BigDecimal total,
        LedgerProcesses.PostInput post) {}

    /**
     * What may be given back: what the credit holds by lot, less what the open jobs hold (the front of the order it is
     * spent in) and the expired fee credit beyond that (voided by the next expiry, never returned).
     */
    static BigDecimal returnable(List<AiFeeRules.Lot> lots, BigDecimal occupied, Instant now) {
        BigDecimal held = lots.stream().map(AiFeeRules.Lot::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expired = AiFeeRules.expired(lots, occupied, now).stream().map(AiFeeRules.Lot::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return held.subtract(occupied).subtract(expired).max(BigDecimal.ZERO);
    }

    static void plan(ProcessContext ctx) {
        GiveBackInput input = input(ctx);
        EntityInstance credit = FeeGate.first(ctx, CREDIT);
        if (credit == null) {
            if ("RETURN".equals(input.kind()) && input.amount() != null) {
                refuse(BigDecimal.ZERO);
            }
            return;
        }
        List<AiFeeRules.Lot> lots = lots(ctx);
        BigDecimal held = lots.stream().map(AiFeeRules.Lot::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remaining = FeeGate.money(credit.get("remaining"));
        if (held.compareTo(remaining) != 0) {
            // L3 of the F-M5 review: its parts hold other than it says it holds: a defect.
            throw new IllegalStateException("AI credit " + credit.id() + " holds " + remaining + " but its parts "
                + held);
        }
        List<AiFeeRules.Lot> out;
        if ("RETURN".equals(input.kind())) {
            BigDecimal returnable = returnable(lots, input.occupied(), ctx.opTime());
            BigDecimal amount = input.amount() == null ? returnable : input.amount();
            if (amount.compareTo(returnable) > 0) {
                refuse(returnable);
            }
            out = AiFeeRules.giveBack(lots, amount);
        } else {
            out = AiFeeRules.expired(lots, input.occupied(), ctx.opTime());
        }
        List<Prepayments.Held> back = out.stream().map(lot -> new Prepayments.Held(
            lot.credit() ? QbFeeAccount.FEE_CREDIT : QbFeeAccount.CASH, lot.lotId(), lot.amount())).toList();
        BigDecimal total = back.stream().map(Prepayments.Held::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            return;
        }
        String sponsorId = input.sponsorId();
        Map<String, String> party = Map.of(QbLedger.PARTY, sponsorId);
        List<LedgerProcesses.Line> lines = new ArrayList<>();
        lines.add(new LedgerProcesses.Line(QbLedger.FEE_PREPAYMENTS, Direction.DEBIT, total, null,
            Prepayments.dimensions(sponsorId, null, QbPrepayments.AI)));
        BigDecimal fromCredit = sum(back, QbFeeAccount.FEE_CREDIT);
        BigDecimal cash = sum(back, QbFeeAccount.CASH);
        if (fromCredit.signum() > 0) {
            lines.add(new LedgerProcesses.Line(QbLedger.FEE_CREDIT, Direction.CREDIT, fromCredit, null, party));
        }
        if (cash.signum() > 0) {
            lines.add(new LedgerProcesses.Line(QbLedger.SPONSOR_CASH, Direction.CREDIT, cash, null, party));
        }
        ctx.put(PLAN, new Plan(credit, back, total, new LedgerProcesses.PostInput(ctx.opTime(),
            "RETURN".equals(input.kind()) ? RETURNED : EXPIRED, "RETURN".equals(input.kind())
                ? Prepayments.reference(QbPrepayments.AI) : EXPIRED_REFERENCE, lines,
            QbPrepayments.PREPAYMENT, String.valueOf(credit.id()))));
    }

    @SuppressWarnings("unchecked")
    static void record(ProcessContext ctx) {
        GiveBackInput input = input(ctx);
        EntityInstance found = FeeGate.first(ctx, CREDIT);
        if (!ctx.contains(PLAN)) {
            BigDecimal balance = found == null ? BigDecimal.ZERO : FeeGate.money(found.get("remaining"));
            ctx.put(OUTPUT, new GiveBackOutput(found == null ? null : String.valueOf(found.id()), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, balance));
            return;
        }
        Plan plan = (Plan) ctx.get(PLAN);
        String prepaymentId = String.valueOf(plan.credit().id());
        String transactionId = ((List<LedgerProcesses.PostOutput>) ctx.get(POSTED)).getFirst().transactionId();
        Prepayments.ReleaseOutput moved = Prepayments.toLots(ctx, input.sponsorId(),
            List.of(new Prepayments.Returned(prepaymentId, plan.back(), transactionId)));
        BigDecimal balance = FeeGate.money(plan.credit().get("remaining")).subtract(plan.total());
        // The credit stays open at 0 (one a sponsor, never closed but by F-M15); its amount is what was bought.
        ctx.changes().update(QbPrepayments.PREPAYMENT, plan.credit().id(), plan.credit().version(),
            Map.of("remaining", balance));
        Map<String, Object> movement = new LinkedHashMap<>();
        movement.put("sponsorId", input.sponsorId());
        movement.put("prepaymentId", prepaymentId);
        movement.put("kind", "RETURN".equals(input.kind()) ? AiCredits.RETURN_KIND : AiCredits.EXPIRE_KIND);
        movement.put("amount", plan.total().negate());
        movement.put("credit", moved.credit().add(moved.voided()).negate());
        movement.put("cash", moved.cash().negate());
        movement.put("balance", balance);
        movement.put("reference", input.reference());
        movement.put("movedAt", ctx.opTime());
        ctx.changes().insert(AiCredits.MOVEMENT, movement);
        ctx.put(OUTPUT, new GiveBackOutput(prepaymentId, plan.total(), moved.credit(), moved.voided(), moved.cash(),
            balance));
    }

    /** The lots the credit holds, in the order it is spent, with each grant's expiry. */
    static List<AiFeeRules.Lot> lots(ProcessContext ctx) {
        Map<String, Instant> expiries = Prepayments.expiries(ctx);
        List<AiFeeRules.Lot> lots = new ArrayList<>();
        for (Prepayments.Held held : Prepayments.heldLots(ctx)) {
            boolean credit = QbFeeAccount.FEE_CREDIT.equals(held.subAccount());
            lots.add(new AiFeeRules.Lot(credit, held.lotId(), held.amount(),
                credit ? expiries.get(held.lotId()) : null));
        }
        return List.copyOf(lots);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> lotIds(ProcessContext ctx, String subAccount) {
        List<Object> ids = new ArrayList<>();
        if (ctx.contains(Prepayments.HELD_LOTS)) {
            for (Map<String, Object> row : (List<Map<String, Object>>) ctx.get(Prepayments.HELD_LOTS)) {
                if (subAccount.equals(String.valueOf(row.get("subAccount")))) {
                    ids.add(String.valueOf(row.get("lotId")));
                }
            }
        }
        return ids;
    }

    private static void refuse(BigDecimal returnable) {
        throw new BusinessRuleViolationException(new Violation("amount", FeeCodes.AI_CREDIT_RETURN_ABOVE,
            "At most " + returnable + " yen of the AI credit may be given back now",
            Map.of("returnable", returnable)));
    }

    private static BigDecimal sum(List<Prepayments.Held> parts, String subAccount) {
        return parts.stream().filter(p -> subAccount.equals(p.subAccount())).map(Prepayments.Held::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static GiveBackInput input(ProcessContext ctx) {
        return ctx.get(INPUT, GiveBackInput.class);
    }
}

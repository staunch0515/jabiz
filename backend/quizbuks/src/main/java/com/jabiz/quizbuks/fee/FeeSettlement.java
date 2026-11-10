package com.jabiz.quizbuks.fee;

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
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.Valid;
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
 * {@value #SETTLE} (F-M1): pays a fee, or what is left of an invoice, from the sponsor's account that pays the platform's
 * fees, in its one order ({@link FeeAllocation}): the fee credit (2190) first, then the cash (2160), as much as there
 * is. One posting, debit 2190 and 2160 / credit 1130, its source the charge or the invoice paid (the monthly report's
 * {@code FEE_PAID}); one {@code QbFeePayment} a lot taken; the lots and the two wallets changed in the same transaction.
 *
 * <p>{@code subProcessOnly}: its callers ({@code QB_FEE_CHARGE}, {@code QB_FEE_INVOICE_PAY}, {@code QB_FEE_SETTLE_ITEM})
 * hold the sponsor's wallet lock ({@code qb.wallet:SPONSOR:<sponsor>}), under which every lot and both wallets change;
 * it reads them itself, after whatever its caller wrote in the transaction.
 */
public final class FeeSettlement {

    public static final String SETTLE = "QB_FEE_SETTLE";

    /** How many open fee credit lots one payment reads: more than any sponsor has. */
    public static final int MAX_CREDITS = 500;

    /**
     * @param sponsorId    the sponsor paying
     * @param targetEntity what is paid: {@code QbFeeCharge} or {@code QbFeeInvoice} (the posting's source)
     * @param targetId     its id
     * @param amount       what is owed now: paid in full, in part, or not at all
     * @param reference    the posting's reference (the monthly report tells the fee kinds apart by it)
     */
    public record SettleInput(@NotBlank @Size(max = 128) String sponsorId,
        @NotBlank @Pattern(regexp = "QbFeeCharge|QbFeeInvoice") String targetEntity,
        @NotBlank @Size(max = 36) String targetId,
        @NotNull @DecimalMin("0") @DecimalMax(FeeProcesses.MAX_AMOUNT) @Digits(integer = 13, fraction = 0)
        BigDecimal amount,
        @Size(max = 100) String reference) {}

    /**
     * @param paid          what was paid now (0: nothing, the account was empty)
     * @param credit        of it, from the fee credit
     * @param cash          of it, from the cash
     * @param transactionId the payment's posting; none when nothing was paid
     * @param cashBalance   the cash account's balance after
     * @param creditBalance the fee credit's balance after
     */
    public record SettleOutput(BigDecimal paid, BigDecimal credit, BigDecimal cash, String transactionId,
        BigDecimal cashBalance, BigDecimal creditBalance) {}

    public static final String REFUND = "QB_FEE_REFUND";

    /** How many payment parts one invoice voided gives back: more than any invoice was paid in. */
    public static final int MAX_PARTS = 1000;

    /** A payment posting and the reversal that undid it. */
    public record Reversal(@NotBlank @Size(max = 64) String transactionId,
        @NotBlank @Size(max = 64) String reversalId) {}

    /**
     * @param sponsorId  the invoice's sponsor
     * @param invoiceId  the invoice voided
     * @param chargeId   the charge it is of own (a prepaid charge's, or one paid early); none for a month's
     * @param paidAmount what had been paid of it
     * @param reversals  the payment postings reversed, each with its reversal
     */
    public record RefundInput(@NotBlank @Size(max = 128) String sponsorId, @NotBlank @Size(max = 36) String invoiceId,
        @Size(max = 36) String chargeId,
        @NotNull @DecimalMin("0") @DecimalMax(FeeProcesses.MAX_AMOUNT) @Digits(integer = 13, fraction = 0)
        BigDecimal paidAmount,
        @NotNull @Size(max = MAX_PARTS) List<@Valid @NotNull Reversal> reversals) {}

    /** @param credit given back to the fee credit; {@code cash} to the cash ({@code legacy}: of it, paid before F-M1) */
    public record RefundOutput(BigDecimal credit, BigDecimal cash, BigDecimal legacy) {}

    static final String INPUT = "input";
    static final String PAYMENTS = "payments";
    static final String PAID_GRANTS = "paidGrants";
    static final String PAID_LOTS = "paidLots";
    static final String REFUNDED = "refunded";
    static final String CREDITS = "credits";
    static final String LOTS = "lots";
    static final String CASH_WALLET = "cashWallet";
    static final String CREDIT_WALLET = "creditWallet";
    static final String ALLOCATION = "allocation";
    static final String POST_INPUT = "postInput";
    static final String POSTING = "posting";
    static final String VOIDED_BACK = "voidedBack";
    static final String VOIDED_POSTED = "voidedPosted";
    /** The posting's description when what a voided invoice gives back to a voided lot is voided again. */
    static final String VOIDED_AGAIN = "Platform fee Kudos given back to a voided lot, voided again";

    private FeeSettlement() {}

    public static ProcessDefinition<SettleInput, SettleOutput, ProcessContext> process() {
        return ProcessDefinition.define(SETTLE, 1, SettleInput.class, SettleOutput.class, ProcessContext.class, pb -> pb
            .description("Pays a platform fee or invoice from the sponsor's fee credit, then their cash, as far as they "
                + "go (under the wallet lock the caller holds).")
            .permissions(QbPermissions.FEE_SYSTEM)
            .subProcessOnly()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(FeeSettlement::output)
            .step("Read the account", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT), ctx -> credits(input(ctx).sponsorId(),
                    ctx.opTime()), CREDITS),
                QueryEntities.query(CashLots.dataset(), ctx -> CashLots.open(input(ctx).sponsorId()), LOTS),
                QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                    ctx -> QbWallets.query(QbWallets.CASH, input(ctx).sponsorId()), CASH_WALLET),
                QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                    ctx -> QbWallets.query(QbWallets.FEE_CREDIT, input(ctx).sponsorId()), CREDIT_WALLET)))
            .compute("Allocate the payment", (metadata, ctx) -> allocate(ctx))
            .step("Post the payment", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT),
                LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING))
            .compute("Record the payment", (metadata, ctx) -> record(ctx)));
    }

    /**
     * {@value #REFUND} ({@code subProcessOnly}, under the sponsor's wallet lock the caller holds; the caller reversed
     * the payment postings): an invoice voided gives what paid it back where it came from. Each part a part of its own
     * with the amount negated (the reversal its posting), each lot's remaining amount and each wallet back by its part;
     * what was paid of the invoice without parts (paid before F-M1, from the cash only) back to the cash as a lot of
     * its own ({@value QbFeeAccount#OTHER}, the invoice its record). A lot of fee credit given back after it expired
     * stays expired (F-M2's daily job voids it). A lot voided already (expired and voided, or F-S4b: its node's lighting
     * revoked) is voided again at once by what it is given back: its remaining amount stays 0, what it is given back
     * adds to what was voided, the wallet does not grow by it, and it is posted as the lot's other voids
     * (2190 / 5140, the lot its record).
     */
    public static ProcessDefinition<RefundInput, RefundOutput, ProcessContext> refund() {
        return ProcessDefinition.define(REFUND, 1, RefundInput.class, RefundOutput.class, ProcessContext.class, pb -> pb
            .description("Gives what paid a voided platform fee invoice back to the fee credit and the cash it came "
                + "from (under the wallet lock the caller holds).")
            .permissions(QbPermissions.FEE_SYSTEM)
            .subProcessOnly()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(REFUNDED, RefundOutput.class))
            .step("Read the payments and the wallets", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.PAYMENT), ctx -> {
                    RefundInput input = refundInput(ctx);
                    List<Object> targets = new ArrayList<>(List.of(input.invoiceId()));
                    if (input.chargeId() != null) {
                        targets.add(input.chargeId());
                    }
                    return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("sponsorId", FeeGate.ids(input.sponsorId())),
                            new QueryPredicate.In("targetId", targets))))
                        .limit(MAX_PARTS + 1).build();
                }, PAYMENTS),
                QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                    ctx -> QbWallets.query(QbWallets.CASH, refundInput(ctx).sponsorId()), CASH_WALLET),
                QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                    ctx -> QbWallets.query(QbWallets.FEE_CREDIT, refundInput(ctx).sponsorId()), CREDIT_WALLET)))
            .step("Read the lots they were taken from", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT),
                    ctx -> byIds("grantId", lotIds(ctx, "grantId")), PAID_GRANTS),
                QueryEntities.query(CashLots.dataset(), ctx -> byIds("lotId", lotIds(ctx, "cashLotId")), PAID_LOTS)))
            .compute("Give it back", (metadata, ctx) -> giveBack(ctx))
            .step("Post what voided lots were given back", CallProcess.<ProcessContext>forEach(LedgerProcesses.POST, 1,
                ctx -> ctx.contains(VOIDED_BACK) ? (List<?>) ctx.get(VOIDED_BACK) : List.of(), VOIDED_POSTED)));
    }

    static List<Object> lotIds(ProcessContext ctx, String field) {
        List<Object> ids = new ArrayList<>();
        for (EntityInstance payment : FeeGate.list(ctx, PAYMENTS)) {
            Object id = payment.get(field);
            if (id != null && !ids.contains(String.valueOf(id))) {
                ids.add(String.valueOf(id));
            }
        }
        return ids;
    }

    static EntityQuery byIds(String field, List<Object> ids) {
        return EntityQuery.builder().where(new QueryPredicate.In(field, ids)).limit(MAX_PARTS).build();
    }

    static void giveBack(ProcessContext ctx) {
        RefundInput input = refundInput(ctx);
        List<EntityInstance> payments = FeeGate.list(ctx, PAYMENTS);
        if (payments.size() > MAX_PARTS) {
            throw new IllegalStateException("More payment parts than " + MAX_PARTS + " for invoice " + input.invoiceId());
        }
        Map<String, String> reversalOf = new LinkedHashMap<>();
        input.reversals().forEach(r -> reversalOf.put(r.transactionId(), r.reversalId()));
        Map<String, BigDecimal> creditBack = new LinkedHashMap<>();
        Map<String, BigDecimal> cashBack = new LinkedHashMap<>();
        BigDecimal parts = BigDecimal.ZERO;
        for (EntityInstance payment : payments) {
            BigDecimal amount = FeeGate.money(payment.get("amount"));
            parts = parts.add(amount);
            if (amount.signum() <= 0) {
                continue;
            }
            String transactionId = String.valueOf((Object) payment.get("transactionId"));
            boolean credit = QbFeeAccount.FEE_CREDIT.equals(payment.get("subAccount"));
            insertPayment(ctx, input.sponsorId(), payment.get("targetEntity"), payment.get("targetId"),
                payment.get("subAccount"), payment.get("grantId"), payment.get("cashLotId"), amount.negate(),
                reversalOf.getOrDefault(transactionId, transactionId));
            String lotId = String.valueOf((Object) payment.get(credit ? "grantId" : "cashLotId"));
            (credit ? creditBack : cashBack).merge(lotId, amount, BigDecimal::add);
        }
        BigDecimal credit = creditBack(ctx, input.sponsorId(), byId(FeeGate.list(ctx, PAID_GRANTS)), creditBack);
        BigDecimal cash = backTo(ctx, QbFeeAccount.CASH_LOT, byId(FeeGate.list(ctx, PAID_LOTS)), cashBack);
        // Paid before F-M1 (no parts): from the cash only, given back as cash.
        BigDecimal legacy = input.paidAmount().subtract(parts).max(BigDecimal.ZERO);
        if (legacy.signum() > 0) {
            CashLots.add(ctx, input.sponsorId(), QbFeeAccount.OTHER, legacy, QbFees.INVOICE, input.invoiceId());
        }
        if (credit.signum() > 0) {
            QbWallets.change(ctx, FeeGate.first(ctx, CREDIT_WALLET), QbWallets.FEE_CREDIT, input.sponsorId(), credit, 0,
                BigDecimal.ZERO);
        }
        if (cash.add(legacy).signum() > 0) {
            QbWallets.change(ctx, FeeGate.first(ctx, CASH_WALLET), QbWallets.CASH, input.sponsorId(), cash.add(legacy),
                0, BigDecimal.ZERO);
        }
        ctx.put(REFUNDED, new RefundOutput(credit, cash.add(legacy), legacy));
    }

    /** {@link #backTo} for the lots of fee credit: what goes back to a voided lot is voided again (not counted). */
    private static BigDecimal creditBack(ProcessContext ctx, String sponsorId, Map<String, EntityInstance> lots,
        Map<String, BigDecimal> back) {
        Map<String, BigDecimal> open = new LinkedHashMap<>();
        List<LedgerProcesses.PostInput> posts = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : back.entrySet()) {
            EntityInstance lot = lots.get(entry.getKey());
            if (lot == null || lot.get("voidedAt") == null) {
                open.put(entry.getKey(), entry.getValue());
                continue;
            }
            ctx.changes().update(QbFeeAccount.GRANT, lot.id(), lot.version(), Map.of("voidedAmount",
                FeeGate.money(lot.get("voidedAmount")).add(entry.getValue())));
            Map<String, String> party = Map.of(QbLedger.PARTY, String.valueOf(sponsorId));
            posts.add(new LedgerProcesses.PostInput(ctx.opTime(), VOIDED_AGAIN, lot.get("source"), List.of(
                new LedgerProcesses.Line(QbLedger.FEE_CREDIT, Direction.DEBIT, entry.getValue(), null, party),
                new LedgerProcesses.Line(QbLedger.FEE_CREDIT_PROMOTION, Direction.CREDIT, entry.getValue(), null,
                    party)), QbFeeAccount.GRANT, String.valueOf(lot.id())));
        }
        ctx.put(VOIDED_BACK, List.copyOf(posts));
        return backTo(ctx, QbFeeAccount.GRANT, lots, open);
    }

    private static BigDecimal backTo(ProcessContext ctx, String entity, Map<String, EntityInstance> lots,
        Map<String, BigDecimal> back) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : back.entrySet()) {
            EntityInstance lot = lots.get(entry.getKey());
            if (lot == null) {
                throw new IllegalStateException(entity + " " + entry.getKey() + " a payment was taken from is gone");
            }
            ctx.changes().update(entity, lot.id(), lot.version(),
                Map.of("remaining", FeeGate.money(lot.get("remaining")).add(entry.getValue())));
            total = total.add(entry.getValue());
        }
        return total;
    }

    static RefundInput refundInput(ProcessContext ctx) {
        return ctx.get(INPUT, RefundInput.class);
    }

    /** What the account may pay a fee with now: the fee credit not expired, and the cash available. */
    public static BigDecimal spendable(List<EntityInstance> credits, EntityInstance cashWallet, Instant now) {
        BigDecimal credit = credits.stream()
            .filter(grant -> ((Instant) grant.get("expiresAt")).isAfter(now))
            .map(grant -> FeeGate.money(grant.get("remaining"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        return credit.add(QbWallets.available(cashWallet).max(BigDecimal.ZERO));
    }

    /** The open fee credit not expired at {@code now}: the earliest to expire first. */
    static EntityQuery credits(Object sponsorId, Instant now) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.In("sponsorId", FeeGate.ids(sponsorId)),
                new QueryPredicate.Gt("remaining", BigDecimal.ZERO),
                new QueryPredicate.Gt("expiresAt", now))))
            .orderBy("expiresAt", true).limit(MAX_CREDITS).build();
    }

    static void allocate(ProcessContext ctx) {
        SettleInput input = input(ctx);
        List<FeeAllocation.Credit> credits = FeeGate.list(ctx, CREDITS).stream().map(grant -> new FeeAllocation.Credit(
            String.valueOf(grant.id()), FeeGate.money(grant.get("remaining")), (Instant) grant.get("grantedAt"),
            (Instant) grant.get("expiresAt"))).toList();
        FeeAllocation.Result result = FeeAllocation.allocate(credits, CashLots.cash(FeeGate.list(ctx, LOTS)),
            QbWallets.available(FeeGate.first(ctx, CASH_WALLET)), input.amount(), ctx.opTime());
        ctx.put(ALLOCATION, result);
        if (result.paid().signum() == 0) {
            return;
        }
        Map<String, String> party = Map.of(QbLedger.PARTY, input.sponsorId());
        List<LedgerProcesses.Line> lines = new ArrayList<>();
        if (result.credit().signum() > 0) {
            lines.add(new LedgerProcesses.Line(QbLedger.FEE_CREDIT, Direction.DEBIT, result.credit(), null, party));
        }
        if (result.cash().signum() > 0) {
            lines.add(new LedgerProcesses.Line(QbLedger.SPONSOR_CASH, Direction.DEBIT, result.cash(), null, party));
        }
        lines.add(new LedgerProcesses.Line(QbLedger.FEES_RECEIVABLE, Direction.CREDIT, result.paid(), null, party));
        ctx.put(POST_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), "Platform fee paid", input.reference(), lines,
            input.targetEntity(), input.targetId()));
    }

    static void record(ProcessContext ctx) {
        FeeAllocation.Result result = ctx.get(ALLOCATION, FeeAllocation.Result.class);
        if (result.paid().signum() == 0) {
            return;
        }
        SettleInput input = input(ctx);
        String transactionId = ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId();
        Map<String, EntityInstance> grants = byId(FeeGate.list(ctx, CREDITS));
        Map<String, EntityInstance> lots = byId(FeeGate.list(ctx, LOTS));
        for (FeeAllocation.Part part : result.parts()) {
            boolean credit = QbFeeAccount.FEE_CREDIT.equals(part.subAccount());
            insertPayment(ctx, input.sponsorId(), input.targetEntity(), input.targetId(), part.subAccount(),
                credit ? part.lotId() : null, credit ? null : part.lotId(), part.amount(), transactionId);
            EntityInstance lot = credit ? grants.get(part.lotId()) : lots.get(part.lotId());
            ctx.changes().update(credit ? QbFeeAccount.GRANT : QbFeeAccount.CASH_LOT, lot.id(), lot.version(),
                Map.of("remaining", FeeGate.money(lot.get("remaining")).subtract(part.amount())));
        }
        if (result.credit().signum() > 0) {
            QbWallets.change(ctx, FeeGate.first(ctx, CREDIT_WALLET), QbWallets.FEE_CREDIT, input.sponsorId(),
                result.credit().negate(), 0, BigDecimal.ZERO);
        }
        if (result.cash().signum() > 0) {
            QbWallets.change(ctx, FeeGate.first(ctx, CASH_WALLET), QbWallets.CASH, input.sponsorId(),
                result.cash().negate(), 0, BigDecimal.ZERO);
        }
    }

    /** One part of a payment (negative: given back), written once. */
    static void insertPayment(ProcessContext ctx, Object sponsorId, Object targetEntity, Object targetId,
        Object subAccount, Object grantId, Object cashLotId, BigDecimal amount, String transactionId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sponsorId", sponsorId);
        values.put("targetEntity", targetEntity);
        values.put("targetId", targetId);
        values.put("subAccount", subAccount);
        values.put("grantId", grantId);
        values.put("cashLotId", cashLotId);
        values.put("amount", amount);
        values.put("paidAt", ctx.opTime());
        values.put("transactionId", transactionId);
        ctx.changes().insert(QbFeeAccount.PAYMENT, values);
    }

    static Map<String, EntityInstance> byId(List<EntityInstance> rows) {
        Map<String, EntityInstance> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.put(String.valueOf(row.id()), row));
        return byId;
    }

    static SettleInput input(ProcessContext ctx) {
        return ctx.get(INPUT, SettleInput.class);
    }

    static SettleOutput output(ProcessContext ctx) {
        FeeAllocation.Result result = ctx.get(ALLOCATION, FeeAllocation.Result.class);
        BigDecimal cashBefore = QbWallets.balance(FeeGate.first(ctx, CASH_WALLET));
        BigDecimal creditBefore = QbWallets.balance(FeeGate.first(ctx, CREDIT_WALLET));
        return new SettleOutput(result.paid(), result.credit(), result.cash(),
            ctx.contains(POSTING) ? ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId() : null,
            cashBefore.subtract(result.cash()), creditBefore.subtract(result.credit()));
    }
}

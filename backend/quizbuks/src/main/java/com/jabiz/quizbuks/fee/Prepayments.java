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
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The prepayment processes (F-M5, docs/quizbuks/plans/impl/F-M.md §8), all {@code subProcessOnly} under the sponsor's
 * wallet lock their caller holds ({@code qb.wallet:SPONSOR:<sponsor>}), except the daily job's item, which takes it.
 *
 * <ul>
 *   <li>{@value #FREEZE}: sets aside what a submission's fee needs. A prepaid sponsor's is taken from the account in its
 *       one order ({@link FeeAllocation}: the fee credit, then the cash), in full (the caller checked the account),
 *       posted debit 2190 / 2160, credit 2180 (the sponsor, the publication, the purpose), one {@code QbFeePayment} a
 *       lot taken aimed at the prepayment; a postpaid sponsor's holds no money and is not posted.</li>
 *   <li>{@value #USE} (by {@code QB_FEE_CHARGE}): pays a charge from the prepayment, as far as it goes: from the parts it
 *       was taken in, the fee credit first; posted debit 2180, credit 1130; one part a lot naming the prepayment.
 *       A postpaid one only stops counting by as much.</li>
 *   <li>{@value #CHARGE}: charges a publication's fee frozen at its submission ({@code AUDIENCE}: the fee before tax)
 *       from its open prepayment, to the operator frozen with it (F-M11).</li>
 *   <li>{@value #RELEASE}: gives what is left of a publication's open prepayments back where it came from, lot by lot:
 *       the fee credit to the grants it was taken from (a grant expired or voided meanwhile is voided again at once,
 *       2190 / 5140, its record the grant), the cash to its lots; posted debit 2180, credit 2190 / 2160, one part a lot
 *       negated. A postpaid one is closed.</li>
 *   <li>{@value #RELEASE_ITEM} ({@code internal()}, the item of the daily job {@value #RELEASE_JOB}): what an ended
 *       publication's prepayments still hold goes back (within a day of its end).</li>
 * </ul>
 */
public final class Prepayments {

    public static final String FREEZE = "QB_PREPAYMENT_FREEZE";
    public static final String USE = "QB_PREPAYMENT_USE";
    public static final String CHARGE = "QB_PREPAYMENT_CHARGE";
    public static final String RELEASE = "QB_PREPAYMENT_RELEASE";
    public static final String RELEASE_ITEM = "QB_PREPAYMENT_RELEASE_ITEM";
    public static final String RELEASE_JOB = "qb.prepayment-release";
    public static final String RELEASE_DUE_TEMPLATE = "qb.system.prepayments-due";

    /** The posting descriptions (the monthly report tells them apart by their accounts and source). */
    static final String FROZEN = "Platform fee prepayment set aside";
    static final String USED = "Platform fee paid from its prepayment";
    static final String RELEASED = "Platform fee prepayment given back";

    /** How many prepayments one publication has at most (one a purpose), and how many parts one has. */
    public static final int MAX_PER_PUBLICATION = 20;
    public static final int MAX_PARTS = 1000;

    /**
     * @param sponsorId     the sponsor
     * @param publicationId the publication it is set aside for
     * @param purpose       what for ({@link QbPrepayments#PURPOSES})
     * @param mode          {@code PREPAID} (money set aside) or {@code POSTPAID} (counts against the credit limit)
     * @param amount        what is set aside: the fee and its tax
     * @param chargeAmount  the fee before tax charged from it later
     * @param operatorId    F-M11: the operator its fees belong to (frozen at the submission)
     * @param detail        what it was worked out from, JSON
     */
    public record FreezeInput(@NotBlank @Size(max = 128) String sponsorId, @Size(max = 36) String publicationId,
        @NotBlank @Pattern(regexp = "DISTRIBUTION|EXPOSURE|AUDIENCE|AI") String purpose,
        @NotBlank @Pattern(regexp = "PREPAID|POSTPAID") String mode,
        @NotNull @DecimalMin("1") @DecimalMax(FeeProcesses.MAX_AMOUNT) @Digits(integer = 13, fraction = 0)
        BigDecimal amount,
        @NotNull @DecimalMin("0") @DecimalMax(FeeProcesses.MAX_AMOUNT) @Digits(integer = 13, fraction = 0)
        BigDecimal chargeAmount,
        @Size(max = 36) String operatorId, @Size(max = QbPrepayments.DETAIL_LENGTH) String detail) {}

    /** @param credit of it, from the fee credit; {@code cash} from the cash (both 0 for a postpaid one) */
    public record FreezeOutput(String prepaymentId, BigDecimal credit, BigDecimal cash) {}

    /** @param amount what the charge owes (the fee and its tax) */
    public record UseInput(@NotBlank @Size(max = 36) String prepaymentId, @NotBlank @Size(max = 36) String chargeId,
        @NotNull @DecimalMin("0") @DecimalMax(FeeProcesses.MAX_AMOUNT) @Digits(integer = 13, fraction = 0)
        BigDecimal amount,
        @Size(max = 100) String reference) {}

    public record ChargeInput(@NotBlank @Size(max = 128) String sponsorId,
        @NotBlank @Size(max = 36) String publicationId,
        @NotBlank @Pattern(regexp = "AUDIENCE") String purpose) {}

    /** @param chargeId the fee charged; none when nothing was set aside for it */
    public record ChargeOutput(String chargeId) {}

    public record ReleaseInput(@NotBlank @Size(max = 128) String sponsorId,
        @NotBlank @Size(max = 36) String publicationId,
        @NotBlank @Pattern(regexp = "WITHDRAWN|REJECTED|REVIEW_EXPIRED|ENDED") String reason) {}

    /**
     * @param released what went back (credit and cash)
     * @param credit   of it, to the fee credit still to be spent
     * @param voided   of it, to fee credit that expired meanwhile (voided again at once)
     * @param cash     of it, to the cash
     */
    public record ReleaseOutput(BigDecimal released, BigDecimal credit, BigDecimal voided, BigDecimal cash) {}

    public record ReleaseRef(@NotBlank @Size(max = 36) String publicationId,
        @NotBlank @Size(max = 128) String sponsorId) {}

    static final String INPUT = "input";
    static final String CREDITS = "credits";
    static final String LOTS = "lots";
    static final String CASH_WALLET = "cashWallet";
    static final String CREDIT_WALLET = "creditWallet";
    static final String PREPAYMENT_ID = "prepaymentId";
    static final String ALLOCATION = "allocation";
    static final String POST_INPUT = "postInput";
    static final String POSTING = "posting";
    static final String PREPAYMENT = "prepayment";
    static final String PREPAYMENTS = "prepayments";
    static final String PARTS = "parts";
    static final String USED_PARTS = "usedParts";
    static final String GRANTS = "grants";
    static final String TAKEN = "taken";
    static final String SETTLED = "settled";
    static final String CHARGE_INPUT = "chargeInput";
    static final String CHARGED = "charged";
    static final String PLAN = "plan";
    static final String POSTS = "posts";
    static final String POSTED = "posted";
    static final String VOIDS = "voids";
    static final String VOIDED = "voided";
    static final String RELEASE_OUTPUT = "releaseOutput";
    static final String PUBLICATION = "publication";
    static final String RELEASE_CALL = "releaseCall";

    private Prepayments() {}

    // ---- freeze -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<FreezeInput, FreezeOutput, ProcessContext> freeze() {
        return ProcessDefinition.define(FREEZE, 1, FreezeInput.class, FreezeOutput.class, ProcessContext.class, pb -> pb
            .description("Sets aside a publication's fee from the sponsor's fee credit and cash as a prepayment (under "
                + "the wallet lock the caller holds).")
            .permissions(QbPermissions.FEE_SYSTEM)
            .subProcessOnly()
            .contextFactory(Prepayments::context)
            .outputMapper(ctx -> {
                FeeAllocation.Result result = ctx.contains(ALLOCATION)
                    ? ctx.get(ALLOCATION, FeeAllocation.Result.class) : null;
                return new FreezeOutput((String) ctx.get(PREPAYMENT_ID),
                    result == null ? BigDecimal.ZERO : result.credit(),
                    result == null ? BigDecimal.ZERO : result.cash());
            })
            .step("Read the account", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT),
                    ctx -> FeeSettlement.credits(freezeInput(ctx).sponsorId(), ctx.opTime()), CREDITS),
                QueryEntities.query(CashLots.dataset(), ctx -> CashLots.open(freezeInput(ctx).sponsorId()), LOTS),
                QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                    ctx -> QbWallets.query(QbWallets.CASH, freezeInput(ctx).sponsorId()), CASH_WALLET),
                QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                    ctx -> QbWallets.query(QbWallets.FEE_CREDIT, freezeInput(ctx).sponsorId()), CREDIT_WALLET)))
            .compute("Set it aside", (metadata, ctx) -> setAside(ctx))
            // The posting names the prepayment as its source: it is written first.
            .step("Save the prepayment", SaveChanges.now())
            .step("Post it", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT), LedgerProcesses.POST, 1,
                ctx -> ctx.get(POST_INPUT), POSTING))
            .compute("Record its parts", (metadata, ctx) -> recordFrozen(ctx)));
    }

    static void setAside(ProcessContext ctx) {
        FreezeInput input = freezeInput(ctx);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sponsorId", input.sponsorId());
        values.put("publicationId", input.publicationId());
        values.put("purpose", input.purpose());
        values.put("mode", input.mode());
        values.put("amount", input.amount());
        values.put("remaining", input.amount());
        values.put("chargeAmount", input.chargeAmount());
        values.put("operatorId", input.operatorId());
        values.put("detail", input.detail());
        values.put("status", QbPrepayments.OPEN);
        values.put("frozenAt", ctx.opTime());
        String id = String.valueOf(ctx.changes().insert(QbPrepayments.PREPAYMENT, values));
        ctx.put(PREPAYMENT_ID, id);
        if (!QbPrepayments.PREPAID.equals(input.mode())) {
            return;
        }
        FeeAllocation.Result result = FeeAllocation.allocate(credits(FeeGate.list(ctx, CREDITS)),
            CashLots.cash(FeeGate.list(ctx, LOTS)), QbWallets.available(FeeGate.first(ctx, CASH_WALLET)),
            input.amount(), ctx.opTime());
        if (result.paid().compareTo(input.amount()) < 0) {
            // The caller checked the account under the same lock: a shortfall here is a defect, not the sponsor's.
            throw new IllegalStateException("The account covers " + result.paid() + " of the prepayment "
                + input.amount() + " the submission checked");
        }
        ctx.put(ALLOCATION, result);
        Map<String, String> party = Map.of(QbLedger.PARTY, input.sponsorId());
        List<LedgerProcesses.Line> lines = new ArrayList<>();
        if (result.credit().signum() > 0) {
            lines.add(new LedgerProcesses.Line(QbLedger.FEE_CREDIT, Direction.DEBIT, result.credit(), null, party));
        }
        if (result.cash().signum() > 0) {
            lines.add(new LedgerProcesses.Line(QbLedger.SPONSOR_CASH, Direction.DEBIT, result.cash(), null, party));
        }
        lines.add(new LedgerProcesses.Line(QbLedger.FEE_PREPAYMENTS, Direction.CREDIT, input.amount(), null,
            dimensions(input.sponsorId(), input.publicationId(), input.purpose())));
        ctx.put(POST_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), FROZEN, reference(input.purpose()), lines,
            QbPrepayments.PREPAYMENT, id));
    }

    static void recordFrozen(ProcessContext ctx) {
        if (!ctx.contains(ALLOCATION)) {
            return;
        }
        FreezeInput input = freezeInput(ctx);
        FeeAllocation.Result result = ctx.get(ALLOCATION, FeeAllocation.Result.class);
        String transactionId = ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId();
        String id = (String) ctx.get(PREPAYMENT_ID);
        Map<String, EntityInstance> grants = FeeSettlement.byId(FeeGate.list(ctx, CREDITS));
        Map<String, EntityInstance> lots = FeeSettlement.byId(FeeGate.list(ctx, LOTS));
        for (FeeAllocation.Part part : result.parts()) {
            boolean credit = QbFeeAccount.FEE_CREDIT.equals(part.subAccount());
            insertPart(ctx, input.sponsorId(), QbPrepayments.PREPAYMENT, id, part.subAccount(),
                credit ? part.lotId() : null, credit ? null : part.lotId(), part.amount(), transactionId, null);
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

    // ---- use --------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<UseInput, FeeSettlement.SettleOutput, ProcessContext> use() {
        return ProcessDefinition.define(USE, 1, UseInput.class, FeeSettlement.SettleOutput.class, ProcessContext.class,
            pb -> pb
                .description("Pays a platform fee from the prepayment set aside for it (under the wallet lock the "
                    + "caller holds).")
                .permissions(QbPermissions.FEE_SYSTEM)
                .subProcessOnly()
                .contextFactory(Prepayments::context)
                .outputMapper(ctx -> ctx.get(SETTLED, FeeSettlement.SettleOutput.class))
                .step("Read the prepayment and its parts", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbPrepayments.PREPAYMENT), ctx -> EntityQuery
                        .builder().where(new QueryPredicate.In("prepaymentId", List.of(useInput(ctx).prepaymentId())))
                        .limit(1).build(), PREPAYMENT),
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.PAYMENT), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("targetId", List.of(useInput(ctx).prepaymentId())))
                        .limit(MAX_PARTS + 1).build(), PARTS),
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.PAYMENT), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("prepaymentId", List.of(useInput(ctx).prepaymentId())))
                        .limit(MAX_PARTS + 1).build(), USED_PARTS)))
                .compute("Take the fee from it", (metadata, ctx) -> take(ctx))
                .step("Post the payment", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT),
                    LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING))
                .compute("Record the payment", (metadata, ctx) -> recordUse(ctx)));
    }

    /** A part of a prepayment still there: the lot it came from and what is left of it. */
    record Held(String subAccount, String lotId, BigDecimal amount) {}

    static void take(ProcessContext ctx) {
        UseInput input = useInput(ctx);
        EntityInstance prepayment = FeeGate.first(ctx, PREPAYMENT);
        if (prepayment == null || !QbPrepayments.OPEN.equals(prepayment.get("status"))) {
            throw new IllegalStateException("Prepayment " + input.prepaymentId() + " is not open");
        }
        BigDecimal remaining = FeeGate.money(prepayment.get("remaining"));
        BigDecimal paid = input.amount().min(remaining);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("remaining", remaining.subtract(paid));
        values.put("chargedAt", ctx.opTime());
        ctx.changes().update(QbPrepayments.PREPAYMENT, prepayment.id(), prepayment.version(), values);
        if (!QbPrepayments.PREPAID.equals(prepayment.get("mode")) || paid.signum() == 0) {
            // A postpaid one holds no money: the fee is owed as their other fees, and it stops counting by as much.
            ctx.put(SETTLED, new FeeSettlement.SettleOutput(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null,
                null, null));
            return;
        }
        List<Held> taken = new ArrayList<>();
        BigDecimal left = paid;
        for (Held held : held(FeeGate.list(ctx, PARTS), FeeGate.list(ctx, USED_PARTS))) {
            if (left.signum() == 0) {
                break;
            }
            BigDecimal part = held.amount().min(left);
            if (part.signum() > 0) {
                taken.add(new Held(held.subAccount(), held.lotId(), part));
                left = left.subtract(part);
            }
        }
        ctx.put(TAKEN, List.copyOf(taken));
        BigDecimal credit = sum(taken, QbFeeAccount.FEE_CREDIT);
        BigDecimal cash = sum(taken, QbFeeAccount.CASH);
        String sponsorId = String.valueOf((Object) prepayment.get("sponsorId"));
        ctx.put(POST_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), USED, input.reference(), List.of(
                new LedgerProcesses.Line(QbLedger.FEE_PREPAYMENTS, Direction.DEBIT, paid, null,
                    dimensions(sponsorId, prepayment.get("publicationId"), prepayment.get("purpose"))),
                new LedgerProcesses.Line(QbLedger.FEES_RECEIVABLE, Direction.CREDIT, paid, null,
                    Map.of(QbLedger.PARTY, sponsorId))),
            QbFees.CHARGE, input.chargeId()));
        ctx.put(SETTLED, new FeeSettlement.SettleOutput(paid, credit, cash, null, null, null));
    }

    @SuppressWarnings("unchecked")
    static void recordUse(ProcessContext ctx) {
        if (!ctx.contains(POSTING)) {
            return;
        }
        UseInput input = useInput(ctx);
        EntityInstance prepayment = FeeGate.first(ctx, PREPAYMENT);
        String transactionId = ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId();
        for (Held part : (List<Held>) ctx.get(TAKEN)) {
            boolean credit = QbFeeAccount.FEE_CREDIT.equals(part.subAccount());
            insertPart(ctx, prepayment.get("sponsorId"), QbFees.CHARGE, input.chargeId(), part.subAccount(),
                credit ? part.lotId() : null, credit ? null : part.lotId(), part.amount(), transactionId,
                input.prepaymentId());
        }
        FeeSettlement.SettleOutput settled = ctx.get(SETTLED, FeeSettlement.SettleOutput.class);
        ctx.put(SETTLED, new FeeSettlement.SettleOutput(settled.paid(), settled.credit(), settled.cash(),
            transactionId, null, null));
    }

    /**
     * What a prepayment still holds, lot by lot, in the order it is spent: the fee credit first, then the cash, each
     * in the order the lots were taken (their ids are time-ordered). Its own parts (positive: set aside; negative:
     * given back) less what fees took of each lot ({@code used}).
     */
    static List<Held> held(List<EntityInstance> parts, List<EntityInstance> used) {
        Map<String, Held> byLot = new LinkedHashMap<>();
        for (EntityInstance part : parts) {
            add(byLot, part, FeeGate.money(part.get("amount")));
        }
        for (EntityInstance part : used) {
            add(byLot, part, FeeGate.money(part.get("amount")).negate());
        }
        return byLot.values().stream().filter(h -> h.amount().signum() > 0)
            .sorted(Comparator.comparing((Held h) -> QbFeeAccount.FEE_CREDIT.equals(h.subAccount()) ? 0 : 1)
                .thenComparing(Held::lotId))
            .toList();
    }

    private static void add(Map<String, Held> byLot, EntityInstance part, BigDecimal amount) {
        boolean credit = QbFeeAccount.FEE_CREDIT.equals(part.get("subAccount"));
        String lotId = String.valueOf((Object) part.get(credit ? "grantId" : "cashLotId"));
        String subAccount = credit ? QbFeeAccount.FEE_CREDIT : QbFeeAccount.CASH;
        byLot.merge(subAccount + ":" + lotId, new Held(subAccount, lotId, amount),
            (a, b) -> new Held(a.subAccount(), a.lotId(), a.amount().add(b.amount())));
    }

    private static BigDecimal sum(List<Held> parts, String subAccount) {
        return parts.stream().filter(p -> subAccount.equals(p.subAccount())).map(Held::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---- charge a publication's fee from its prepayment ---------------------------------------------------------------

    public static ProcessDefinition<ChargeInput, ChargeOutput, ProcessContext> charge(ZoneId zone) {
        return ProcessDefinition.define(CHARGE, 1, ChargeInput.class, ChargeOutput.class, ProcessContext.class,
            pb -> pb
                .description("Charges a publication's fee frozen at its submission from the prepayment set aside for "
                    + "it (under the wallet lock the caller holds).")
                .permissions(QbPermissions.FEE_SYSTEM)
                .subProcessOnly()
                .contextFactory(Prepayments::context)
                .outputMapper(ctx -> new ChargeOutput(ctx.contains(CHARGED)
                    ? ctx.get(CHARGED, FeeProcesses.ChargeOutput.class).chargeId() : null))
                .step("Read the prepayment", QueryEntities.<ProcessContext>of(
                    QbFeeAccount.defaultDataset(QbPrepayments.PREPAYMENT), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("publicationId", List.of(chargeInput(ctx).publicationId())),
                            new QueryPredicate.Eq("purpose", chargeInput(ctx).purpose()),
                            new QueryPredicate.Eq("status", QbPrepayments.OPEN))))
                        .limit(1).build(), PREPAYMENT))
                .compute("Work out the charge", (metadata, ctx) -> chargeOf(ctx, zone))
                .step("Charge it", CallProcess.<ProcessContext>when(ctx -> ctx.contains(CHARGE_INPUT),
                    FeeProcesses.CHARGE, 1, ctx -> ctx.get(CHARGE_INPUT), CHARGED)));
    }

    static void chargeOf(ProcessContext ctx, ZoneId zone) {
        ChargeInput input = chargeInput(ctx);
        EntityInstance prepayment = FeeGate.first(ctx, PREPAYMENT);
        if (prepayment == null || prepayment.get("chargedAt") != null) {
            return;
        }
        BigDecimal amount = FeeGate.money(prepayment.get("chargeAmount"));
        if (amount.signum() <= 0) {
            return;
        }
        ctx.put(CHARGE_INPUT, new FeeProcesses.ChargeInput(input.sponsorId(), input.purpose(),
            FeeRules.period(ctx.opTime(), zone), amount, null, "publication:" + input.publicationId(), null,
            (String) prepayment.get("operatorId"), String.valueOf(prepayment.id())));
    }

    // ---- release ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ReleaseInput, ReleaseOutput, ProcessContext> release() {
        return ProcessDefinition.define(RELEASE, 1, ReleaseInput.class, ReleaseOutput.class, ProcessContext.class,
            pb -> pb
                .description("Gives what a publication's prepayments still hold back to the fee credit and cash it "
                    + "came from (under the wallet lock the caller holds).")
                .permissions(QbPermissions.FEE_SYSTEM)
                .subProcessOnly()
                .contextFactory(Prepayments::context)
                .outputMapper(ctx -> ctx.get(RELEASE_OUTPUT, ReleaseOutput.class))
                .step("Read the prepayments and the wallets", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbPrepayments.PREPAYMENT), ctx -> EntityQuery
                        .builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("publicationId", List.of(releaseInput(ctx).publicationId())),
                            new QueryPredicate.Eq("status", QbPrepayments.OPEN))))
                        .limit(MAX_PER_PUBLICATION).build(), PREPAYMENTS),
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                        ctx -> QbWallets.query(QbWallets.CASH, releaseInput(ctx).sponsorId()), CASH_WALLET),
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                        ctx -> QbWallets.query(QbWallets.FEE_CREDIT, releaseInput(ctx).sponsorId()), CREDIT_WALLET)))
                .step("Read their parts", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.PAYMENT), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("targetId", prepaymentIds(ctx)))
                        .limit(MAX_PARTS + 1).build(), PARTS),
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.PAYMENT), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("prepaymentId", prepaymentIds(ctx)))
                        .limit(MAX_PARTS + 1).build(), USED_PARTS)))
                .step("Read the lots they were taken from", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT),
                        ctx -> FeeSettlement.byIds("grantId", lotIds(ctx, "grantId")), GRANTS),
                    QueryEntities.query(CashLots.dataset(),
                        ctx -> FeeSettlement.byIds("lotId", lotIds(ctx, "cashLotId")), LOTS)))
                .compute("Work out what goes back", (metadata, ctx) -> planRelease(ctx))
                .step("Post what goes back", CallProcess.<ProcessContext>forEach(LedgerProcesses.POST, 1,
                    ctx -> ctx.contains(POSTS) ? (List<?>) ctx.get(POSTS) : List.of(), POSTED))
                .compute("Give it back", (metadata, ctx) -> giveBack(ctx))
                .step("Void what went back to expired fee credit", CallProcess.<ProcessContext>forEach(
                    LedgerProcesses.POST, 1, ctx -> ctx.contains(VOIDS) ? (List<?>) ctx.get(VOIDS) : List.of(),
                    VOIDED)));
    }

    static List<Object> prepaymentIds(ProcessContext ctx) {
        return FeeGate.list(ctx, PREPAYMENTS).stream()
            .filter(row -> QbPrepayments.PREPAID.equals(row.get("mode")))
            .map(row -> (Object) String.valueOf(row.id())).toList();
    }

    static List<Object> lotIds(ProcessContext ctx, String field) {
        List<Object> ids = new ArrayList<>();
        for (EntityInstance part : FeeGate.list(ctx, PARTS)) {
            Object id = part.get(field);
            if (id != null && !ids.contains(String.valueOf(id))) {
                ids.add(String.valueOf(id));
            }
        }
        return ids;
    }

    /** One prepayment's release: what goes back of each lot (in the order of {@link #held}). */
    record Release(EntityInstance prepayment, List<Held> back) {}

    static void planRelease(ProcessContext ctx) {
        if (FeeGate.list(ctx, PARTS).size() > MAX_PARTS || FeeGate.list(ctx, USED_PARTS).size() > MAX_PARTS) {
            throw new IllegalStateException("More prepayment parts than " + MAX_PARTS);
        }
        List<Release> plan = new ArrayList<>();
        List<LedgerProcesses.PostInput> posts = new ArrayList<>();
        for (EntityInstance prepayment : FeeGate.list(ctx, PREPAYMENTS)) {
            List<Held> back = new ArrayList<>();
            if (QbPrepayments.PREPAID.equals(prepayment.get("mode"))) {
                String id = String.valueOf(prepayment.id());
                List<Held> held = held(
                    FeeGate.list(ctx, PARTS).stream()
                        .filter(p -> id.equals(String.valueOf((Object) p.get("targetId")))).toList(),
                    FeeGate.list(ctx, USED_PARTS).stream()
                        .filter(p -> id.equals(String.valueOf((Object) p.get("prepaymentId")))).toList());
                BigDecimal left = FeeGate.money(prepayment.get("remaining"));
                for (Held h : held) {
                    BigDecimal part = h.amount().min(left);
                    if (part.signum() > 0) {
                        back.add(new Held(h.subAccount(), h.lotId(), part));
                        left = left.subtract(part);
                    }
                }
                BigDecimal total = back.stream().map(Held::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (total.signum() > 0) {
                    String sponsorId = String.valueOf((Object) prepayment.get("sponsorId"));
                    Map<String, String> party = Map.of(QbLedger.PARTY, sponsorId);
                    List<LedgerProcesses.Line> lines = new ArrayList<>();
                    lines.add(new LedgerProcesses.Line(QbLedger.FEE_PREPAYMENTS, Direction.DEBIT, total, null,
                        dimensions(sponsorId, prepayment.get("publicationId"), prepayment.get("purpose"))));
                    BigDecimal credit = sum(back, QbFeeAccount.FEE_CREDIT);
                    BigDecimal cash = sum(back, QbFeeAccount.CASH);
                    if (credit.signum() > 0) {
                        lines.add(new LedgerProcesses.Line(QbLedger.FEE_CREDIT, Direction.CREDIT, credit, null, party));
                    }
                    if (cash.signum() > 0) {
                        lines.add(new LedgerProcesses.Line(QbLedger.SPONSOR_CASH, Direction.CREDIT, cash, null, party));
                    }
                    posts.add(new LedgerProcesses.PostInput(ctx.opTime(), RELEASED,
                        reference(String.valueOf((Object) prepayment.get("purpose"))), lines,
                        QbPrepayments.PREPAYMENT, id));
                }
            }
            plan.add(new Release(prepayment, List.copyOf(back)));
        }
        ctx.put(PLAN, List.copyOf(plan));
        ctx.put(POSTS, List.copyOf(posts));
    }

    @SuppressWarnings("unchecked")
    static void giveBack(ProcessContext ctx) {
        ReleaseInput input = releaseInput(ctx);
        List<Release> plan = (List<Release>) ctx.get(PLAN);
        List<LedgerProcesses.PostOutput> posted = ctx.contains(POSTED)
            ? (List<LedgerProcesses.PostOutput>) ctx.get(POSTED) : List.of();
        Map<String, EntityInstance> grants = FeeSettlement.byId(FeeGate.list(ctx, GRANTS));
        Map<String, EntityInstance> lots = FeeSettlement.byId(FeeGate.list(ctx, LOTS));
        Map<String, BigDecimal> grantBack = new LinkedHashMap<>();
        Map<String, BigDecimal> lotBack = new LinkedHashMap<>();
        int post = 0;
        for (Release release : plan) {
            EntityInstance prepayment = release.prepayment();
            String transactionId = release.back().isEmpty() ? null : posted.get(post++).transactionId();
            for (Held part : release.back()) {
                boolean credit = QbFeeAccount.FEE_CREDIT.equals(part.subAccount());
                insertPart(ctx, input.sponsorId(), QbPrepayments.PREPAYMENT, String.valueOf(prepayment.id()),
                    part.subAccount(), credit ? part.lotId() : null, credit ? null : part.lotId(),
                    part.amount().negate(), transactionId, null);
                (credit ? grantBack : lotBack).merge(part.lotId(), part.amount(), BigDecimal::add);
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("remaining", BigDecimal.ZERO);
            values.put("status", QbPrepayments.RELEASED);
            values.put("releasedAt", ctx.opTime());
            values.put("releaseReason", input.reason());
            ctx.changes().update(QbPrepayments.PREPAYMENT, prepayment.id(), prepayment.version(), values);
        }
        // The fee credit: back to the grants still to be spent; to those expired or voided meanwhile, voided again.
        BigDecimal credit = BigDecimal.ZERO;
        BigDecimal voided = BigDecimal.ZERO;
        List<LedgerProcesses.PostInput> voids = new ArrayList<>();
        Map<String, String> party = Map.of(QbLedger.PARTY, input.sponsorId());
        for (Map.Entry<String, BigDecimal> entry : grantBack.entrySet()) {
            EntityInstance grant = grants.get(entry.getKey());
            if (grant == null) {
                throw new IllegalStateException("Fee credit lot " + entry.getKey() + " a prepayment came from is gone");
            }
            boolean expired = grant.get("voidedAt") != null || !((Instant) grant.get("expiresAt")).isAfter(ctx.opTime());
            if (expired) {
                ctx.changes().update(QbFeeAccount.GRANT, grant.id(), grant.version(), Map.of("voidedAmount",
                    FeeGate.money(grant.get("voidedAmount")).add(entry.getValue())));
                voids.add(new LedgerProcesses.PostInput(ctx.opTime(), FeeSettlement.VOIDED_AGAIN, grant.get("source"),
                    List.of(
                        new LedgerProcesses.Line(QbLedger.FEE_CREDIT, Direction.DEBIT, entry.getValue(), null, party),
                        new LedgerProcesses.Line(QbLedger.FEE_CREDIT_PROMOTION, Direction.CREDIT, entry.getValue(), null,
                            party)), QbFeeAccount.GRANT, String.valueOf(grant.id())));
                voided = voided.add(entry.getValue());
            } else {
                ctx.changes().update(QbFeeAccount.GRANT, grant.id(), grant.version(),
                    Map.of("remaining", FeeGate.money(grant.get("remaining")).add(entry.getValue())));
                credit = credit.add(entry.getValue());
            }
        }
        BigDecimal cash = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : lotBack.entrySet()) {
            EntityInstance lot = lots.get(entry.getKey());
            if (lot == null) {
                throw new IllegalStateException("Cash lot " + entry.getKey() + " a prepayment came from is gone");
            }
            ctx.changes().update(QbFeeAccount.CASH_LOT, lot.id(), lot.version(),
                Map.of("remaining", FeeGate.money(lot.get("remaining")).add(entry.getValue())));
            cash = cash.add(entry.getValue());
        }
        if (credit.signum() > 0) {
            QbWallets.change(ctx, FeeGate.first(ctx, CREDIT_WALLET), QbWallets.FEE_CREDIT, input.sponsorId(), credit, 0,
                BigDecimal.ZERO);
        }
        if (cash.signum() > 0) {
            QbWallets.change(ctx, FeeGate.first(ctx, CASH_WALLET), QbWallets.CASH, input.sponsorId(), cash, 0,
                BigDecimal.ZERO);
        }
        ctx.put(VOIDS, List.copyOf(voids));
        ctx.put(RELEASE_OUTPUT, new ReleaseOutput(credit.add(voided).add(cash), credit, voided, cash));
    }

    // ---- the daily job's item ----------------------------------------------------------------------------------------

    /**
     * {@value #RELEASE_ITEM}: an ended publication's open prepayments, released under the sponsor's wallet lock (the
     * publication is not locked: an ended one does not change). Not ended, or nothing open: nothing happens.
     */
    public static ProcessDefinition<ReleaseRef, FeeProcesses.ItemOutput, ProcessContext> releaseItem() {
        return ProcessDefinition.define(RELEASE_ITEM, 1, ReleaseRef.class, FeeProcesses.ItemOutput.class,
            ProcessContext.class, pb -> pb
                .description("Gives back what an ended publication's prepayments still hold.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory(Prepayments::context)
                .outputMapper(ctx -> new FeeProcesses.ItemOutput(releaseRef(ctx).sponsorId(),
                    ctx.contains(RELEASE_CALL) ? "RELEASED" : "SKIPPED", releaseRef(ctx).publicationId()))
                .step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.SPONSOR, releaseRef(ctx).sponsorId())))
                .step("Read the publication", QueryEntities.<ProcessContext>of(
                    com.jabiz.quizbuks.publishing.QbPublications.defaultDataset(
                        com.jabiz.quizbuks.publishing.QbPublications.PUBLICATION), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("publicationId", List.of(releaseRef(ctx).publicationId())))
                        .limit(1).build(), PUBLICATION))
                .compute("Decide", (metadata, ctx) -> {
                    EntityInstance publication = FeeGate.first(ctx, PUBLICATION);
                    if (publication != null && com.jabiz.quizbuks.publishing.PublicationStatus.ENDED.equals(
                            publication.get("status"))
                        && releaseRef(ctx).sponsorId().equals(String.valueOf((Object) publication.get("publisherId")))) {
                        ctx.put(RELEASE_CALL, new ReleaseInput(releaseRef(ctx).sponsorId(),
                            releaseRef(ctx).publicationId(), QbPrepayments.ENDED));
                    }
                })
                .step("Release", CallProcess.<ProcessContext>when(ctx -> ctx.contains(RELEASE_CALL), RELEASE, 1,
                    ctx -> ctx.get(RELEASE_CALL), RELEASE_OUTPUT)));
    }

    // ---- shared -------------------------------------------------------------------------------------------------------

    /** 2180's dimensions: the sponsor, the publication (none for an AI credit), the purpose. */
    static Map<String, String> dimensions(Object sponsorId, Object publicationId, Object purpose) {
        Map<String, String> dims = new LinkedHashMap<>();
        dims.put(QbLedger.PARTY, String.valueOf(sponsorId));
        if (publicationId != null) {
            dims.put(QbWallets.PUBLICATION, String.valueOf(publicationId));
        }
        dims.put(QbPrepayments.PURPOSE, String.valueOf(purpose));
        return dims;
    }

    /** The postings' reference: {@code prepay_<purpose>} (the monthly report tells the purposes apart by it). */
    static String reference(String purpose) {
        return "prepay_" + purpose.toLowerCase(java.util.Locale.ROOT);
    }

    static List<FeeAllocation.Credit> credits(List<EntityInstance> rows) {
        return rows.stream().map(grant -> new FeeAllocation.Credit(String.valueOf(grant.id()),
            FeeGate.money(grant.get("remaining")), (Instant) grant.get("grantedAt"), (Instant) grant.get("expiresAt")))
            .toList();
    }

    /** One part of a payment (negative: given back), written once; {@code prepaymentId}: paid from that prepayment. */
    static void insertPart(ProcessContext ctx, Object sponsorId, Object targetEntity, Object targetId,
        Object subAccount, Object grantId, Object cashLotId, BigDecimal amount, String transactionId,
        String prepaymentId) {
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
        values.put("prepaymentId", prepaymentId);
        ctx.changes().insert(QbFeeAccount.PAYMENT, values);
    }

    private static ProcessContext context(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    static FreezeInput freezeInput(ProcessContext ctx) {
        return ctx.get(INPUT, FreezeInput.class);
    }

    static UseInput useInput(ProcessContext ctx) {
        return ctx.get(INPUT, UseInput.class);
    }

    static ChargeInput chargeInput(ProcessContext ctx) {
        return ctx.get(INPUT, ChargeInput.class);
    }

    static ReleaseInput releaseInput(ProcessContext ctx) {
        return ctx.get(INPUT, ReleaseInput.class);
    }

    static ReleaseRef releaseRef(ProcessContext ctx) {
        return ctx.get(INPUT, ReleaseRef.class);
    }
}

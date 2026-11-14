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
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F-M15c (docs/quizbuks/plans/impl/F-M15.md §5.2 step 9, 总控 2026-11-14 M2): {@value #GIVE_BACK} gives what a credit
 * note's credit has left at the wind-up of an erased sponsor's account back to what paid its fee, as F-M7 gives back a
 * prepaid fee: the parts that paid the fee and its invoice (a month's invoice for a postpaid one), less what notes on
 * them gave back before, the cash first (the latest paid first), then the fee credit (the latest paid first). Fee
 * credit given back to its grant is voided again at once when the grant expired or was voided (the wind-up voided every
 * grant left: 2190 → 5140, the grant the source), so only the cash part stays, in the cash lots it came from. What the
 * parts cannot take (credit left beyond what money paid: the invoice was paid by other notes' credit) goes to the cash
 * as a lot of its own (the note its record).
 *
 * <p>One posting: debit 1130 (the sponsor), credit 2160 and 2190 (the note the source; the monthly report's FEE_PAID
 * taken back), a negative part a lot aimed at the note, and a use of the note's credit naming the posting. Run under the
 * sponsor's wallet lock the caller holds ({@code subProcessOnly}).
 */
public final class CreditNoteGiveBack {

    public static final String GIVE_BACK = "QB_FEE_CREDIT_NOTE_GIVE_BACK";
    /** The posting's description. */
    static final String GIVEN_BACK = "Credit note's credit left given back on the account's deletion";

    static final String INPUT = "input";
    static final String NOTE = "note";
    static final String RELATED = "related";
    static final String PARTS = "parts";
    static final String BACK = "back";
    static final String EXTRA = "extra";
    static final String POST = "post";
    static final String POSTED = "posted";

    private CreditNoteGiveBack() {}

    /** @param amount what the note's credit has left (the caller's reading under the wallet lock) */
    public record GiveBackInput(@NotBlank @Size(max = 128) String sponsorId, @NotBlank @Size(max = 36) String creditNoteId,
        @NotNull @DecimalMin("1") @DecimalMax(FeeProcesses.MAX_AMOUNT) @Digits(integer = 13, fraction = 0)
        BigDecimal amount) {}

    /**
     * @param cash   given back to cash (its lots, and a lot of its own for what the parts could not take)
     * @param credit given back to fee credit (voided again at once when its grant expired or was voided)
     */
    public record GiveBackOutput(String creditNoteId, BigDecimal cash, BigDecimal credit) {}

    public static ProcessDefinition<GiveBackInput, GiveBackOutput, ProcessContext> giveBack() {
        return ProcessDefinition.define(GIVE_BACK, 1, GiveBackInput.class, GiveBackOutput.class, ProcessContext.class,
            pb -> pb
                .description("Gives what a credit note's credit has left back to what paid its fee (an erased "
                    + "sponsor's wind-up), under the wallet lock the caller holds.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .subProcessOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(CreditNoteGiveBack::output)
                .step("Read the note and the wallets", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFees.defaultDataset(QbCreditNotes.CREDIT_NOTE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("creditNoteId", List.of(input(ctx).creditNoteId())),
                            new QueryPredicate.Eq("sponsorId", input(ctx).sponsorId())))).limit(1).build(), NOTE),
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                        ctx -> QbWallets.query(QbWallets.CASH, input(ctx).sponsorId()), Prepayments.CASH_WALLET),
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                        ctx -> QbWallets.query(QbWallets.FEE_CREDIT, input(ctx).sponsorId()),
                        Prepayments.CREDIT_WALLET)))
                .step("Read the notes on the same fee or invoice", QueryEntities.<ProcessContext>of(
                    QbFees.defaultDataset(QbCreditNotes.CREDIT_NOTE), CreditNoteGiveBack::related, RELATED))
                .step("Read what paid them", QueryEntities.<ProcessContext>of(
                    QbFeeAccount.defaultDataset(QbFeeAccount.PAYMENT), CreditNoteGiveBack::parts, PARTS))
                .compute("Work out what goes back where", (metadata, ctx) -> plan(ctx))
                .step("Read the lots", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT),
                        ctx -> FeeSettlement.byIds("grantId", lotIds(ctx, QbFeeAccount.FEE_CREDIT)),
                        Prepayments.GRANTS),
                    QueryEntities.query(CashLots.dataset(),
                        ctx -> FeeSettlement.byIds("lotId", lotIds(ctx, QbFeeAccount.CASH)), Prepayments.LOTS)))
                .step("Post it", CallProcess.<ProcessContext>forEach(LedgerProcesses.POST, 1,
                    ctx -> ctx.contains(POST) ? List.of(ctx.get(POST)) : List.of(), POSTED))
                .compute("Give it back", (metadata, ctx) -> record(ctx))
                .step("Void what went back to voided fee credit", CallProcess.<ProcessContext>forEach(
                    LedgerProcesses.POST, 1, ctx -> ctx.contains(Prepayments.VOIDS)
                        ? (List<?>) ctx.get(Prepayments.VOIDS) : List.of(), Prepayments.VOIDED)));
    }

    /** The notes on the note's fee, or on its invoice (whose parts they share): what they gave back is not left. */
    static EntityQuery related(ProcessContext ctx) {
        EntityInstance note = note(ctx);
        List<QueryPredicate> either = new ArrayList<>();
        either.add(new QueryPredicate.Eq("chargeId", note.get("chargeId")));
        if (note.get("invoiceId") != null) {
            either.add(new QueryPredicate.Eq("invoiceId", note.get("invoiceId")));
        }
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.Eq("sponsorId", input(ctx).sponsorId()),
                new QueryPredicate.Or(either))))
            .limit(FeeSettlement.MAX_PARTS).build();
    }

    /** The parts aimed at the fee, its invoice and the notes on them. */
    static EntityQuery parts(ProcessContext ctx) {
        EntityInstance note = note(ctx);
        List<Object> targets = new ArrayList<>();
        targets.add(String.valueOf((Object) note.get("chargeId")));
        if (note.get("invoiceId") != null) {
            targets.add(String.valueOf((Object) note.get("invoiceId")));
        }
        FeeGate.list(ctx, RELATED).forEach(related -> targets.add(String.valueOf(related.id())));
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.In("sponsorId", FeeGate.ids(input(ctx).sponsorId())),
                new QueryPredicate.In("targetId", targets))))
            .orderBy("paidAt", true).limit(FeeSettlement.MAX_PARTS + 1).build();
    }

    /** A lot and what it still has of the payments, with when it was last paid from. */
    record Paid(String subAccount, String lotId, BigDecimal amount, Instant paidAt) {}

    /**
     * What goes back to which lot: the net of each lot's parts (what paid, less what was given back), the cash first and
     * the latest paid first; what they cannot take is extra cash.
     */
    static void plan(ProcessContext ctx) {
        List<EntityInstance> parts = FeeGate.list(ctx, PARTS);
        if (parts.size() > FeeSettlement.MAX_PARTS) {
            throw new IllegalStateException("More payment parts than are read for credit note "
                + input(ctx).creditNoteId());
        }
        Map<String, Paid> byLot = new LinkedHashMap<>();
        for (EntityInstance part : parts) {
            boolean credit = QbFeeAccount.FEE_CREDIT.equals(part.get("subAccount"));
            Object lot = part.get(credit ? "grantId" : "cashLotId");
            if (lot == null) {
                continue;
            }
            BigDecimal amount = FeeGate.money(part.get("amount"));
            Instant at = (Instant) part.get("paidAt");
            byLot.merge(credit + ":" + lot, new Paid(String.valueOf((Object) part.get("subAccount")), String.valueOf(lot),
                amount, amount.signum() > 0 ? at : Instant.EPOCH), (a, b) -> new Paid(a.subAccount(), a.lotId(),
                a.amount().add(b.amount()), a.paidAt().isAfter(b.paidAt()) ? a.paidAt() : b.paidAt()));
        }
        List<Paid> order = new ArrayList<>(byLot.values().stream().filter(p -> p.amount().signum() > 0).toList());
        order.sort(Comparator.comparing((Paid p) -> QbFeeAccount.FEE_CREDIT.equals(p.subAccount()))
            .thenComparing(Paid::paidAt, Comparator.reverseOrder()).thenComparing(Paid::lotId));
        List<Prepayments.Held> back = new ArrayList<>();
        BigDecimal left = input(ctx).amount();
        for (Paid paid : order) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal part = paid.amount().min(left);
            back.add(new Prepayments.Held(paid.subAccount(), paid.lotId(), part));
            left = left.subtract(part);
        }
        ctx.put(BACK, List.copyOf(back));
        ctx.put(EXTRA, left);
        BigDecimal credit = sum(back, QbFeeAccount.FEE_CREDIT);
        BigDecimal cash = sum(back, QbFeeAccount.CASH).add(left);
        Map<String, String> party = Map.of(QbLedger.PARTY, input(ctx).sponsorId());
        List<LedgerProcesses.Line> lines = new ArrayList<>();
        lines.add(new LedgerProcesses.Line(QbLedger.FEES_RECEIVABLE, Direction.DEBIT, input(ctx).amount(), null,
            party));
        if (cash.signum() > 0) {
            lines.add(new LedgerProcesses.Line(QbLedger.SPONSOR_CASH, Direction.CREDIT, cash, null, party));
        }
        if (credit.signum() > 0) {
            lines.add(new LedgerProcesses.Line(QbLedger.FEE_CREDIT, Direction.CREDIT, credit, null, party));
        }
        ctx.put(POST, new LedgerProcesses.PostInput(ctx.opTime(), GIVEN_BACK,
            String.valueOf((Object) note(ctx).get("creditNoteNo")), List.copyOf(lines), QbCreditNotes.CREDIT_NOTE,
            input(ctx).creditNoteId()));
    }

    /** The lots of one kind the note gives back to. */
    @SuppressWarnings("unchecked")
    private static List<Object> lotIds(ProcessContext ctx, String subAccount) {
        List<Object> ids = new ArrayList<>();
        for (Prepayments.Held held : (List<Prepayments.Held>) ctx.get(BACK)) {
            if (subAccount.equals(held.subAccount())) {
                ids.add(held.lotId());
            }
        }
        return ids;
    }

    /**
     * The negative parts, the lots and the wallets (grants voided meanwhile voided again: F-M5's way), the extra cash as
     * a lot of its own with its part, and the use of the note's credit naming the posting.
     */
    @SuppressWarnings("unchecked")
    static void record(ProcessContext ctx) {
        GiveBackInput input = input(ctx);
        String transactionId = ((List<LedgerProcesses.PostOutput>) ctx.get(POSTED)).getFirst().transactionId();
        BigDecimal extra = (BigDecimal) ctx.get(EXTRA);
        Prepayments.ReleaseOutput moved = Prepayments.toLots(ctx, input.sponsorId(), QbCreditNotes.CREDIT_NOTE,
            List.of(new Prepayments.Returned(input.creditNoteId(), (List<Prepayments.Held>) ctx.get(BACK),
                transactionId)), extra);
        if (extra.signum() > 0) {
            Object lotId = CashLots.add(ctx, input.sponsorId(), QbFeeAccount.OTHER, extra, QbCreditNotes.CREDIT_NOTE,
                input.creditNoteId());
            FeeSettlement.insertPayment(ctx, input.sponsorId(), QbCreditNotes.CREDIT_NOTE, input.creditNoteId(),
                QbFeeAccount.CASH, null, lotId, extra.negate(), transactionId);
        }
        Map<String, Object> use = new LinkedHashMap<>();
        use.put("creditNoteId", input.creditNoteId());
        use.put("sponsorId", input.sponsorId());
        use.put("backTransactionId", transactionId);
        use.put("amount", input.amount());
        use.put("usedAt", ctx.opTime());
        ctx.changes().insert(QbCreditNotes.USE, use);
        ctx.put("given", new GiveBackOutput(input.creditNoteId(), moved.cash().add(extra),
            moved.credit().add(moved.voided())));
    }

    private static GiveBackOutput output(ProcessContext ctx) {
        return ctx.get("given", GiveBackOutput.class);
    }

    private static BigDecimal sum(List<Prepayments.Held> parts, String subAccount) {
        return parts.stream().filter(p -> subAccount.equals(p.subAccount())).map(Prepayments.Held::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static EntityInstance note(ProcessContext ctx) {
        EntityInstance note = FeeGate.first(ctx, NOTE);
        if (note == null) {
            throw new IllegalStateException("Credit note " + input(ctx).creditNoteId() + " is not "
                + input(ctx).sponsorId() + "'s");
        }
        return note;
    }

    private static GiveBackInput input(ProcessContext ctx) {
        return ctx.get(INPUT, GiveBackInput.class);
    }
}

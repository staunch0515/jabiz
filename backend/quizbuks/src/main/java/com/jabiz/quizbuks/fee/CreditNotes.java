package com.jabiz.quizbuks.fee;

import com.jabiz.entity.Violation;
import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.numbering.AssignNumber;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.runtime.task.CloseTasks;
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
 * F-M7 (docs/quizbuks/plans/impl/F-M.md §10.3-2, -4, -5): issuing a credit note, and the credit it leaves.
 *
 * <ul>
 *   <li>{@value #ISSUE} ({@code subProcessOnly}, under the sponsor's wallet lock {@code qb.wallet:SPONSOR:<sponsor>} the
 *       caller holds): a note on one fee, within what the fee's notes may still come to ({@link CreditNoteRules}),
 *       written once and posted debit 4120 and 2150 / credit 1130 (the fee and its invoice unchanged); its total goes
 *       where {@link CreditNoteAllocation} says: the fee's own invoice, back where a prepaid fee was paid from (a part
 *       paid from a prepayment through it, as an invoice voided gives it back: F-M5's path), the sponsor's other
 *       unpaid invoices, the earliest first; the rest is credit. One note a source ({@code (sponsorId, sourceKey)}):
 *       asked again, the note written is returned. A note on a fee whose invoice is voided is refused
 *       ({@code 422 QB_FEE_CREDIT_NOTE_VOIDED}); one more than the fee may still be credited is refused when it must be
 *       exact (by hand, N8: {@code 422 QB_FEE_CREDIT_NOTE_EXCEEDS}), else cut to it (the refund job).</li>
 *   <li>{@value #TASK_CLOSE} ({@code subProcessOnly}): an overdue invoice paid by a note no longer needs its reminder.</li>
 * </ul>
 * What is left of a note's credit is worked out, never kept: its total less what it gave back and what its credit paid
 * ({@code QbFeeCreditNoteUse}, the fee's own invoice included). The invoices issued later take it first
 * ({@link #unused}, {@link #take}, {@link #recordUses}).
 */
public final class CreditNotes {

    public static final String ISSUE = "QB_FEE_CREDIT_NOTE_ISSUE";
    public static final String TASK_CLOSE = "QB_FEE_CREDIT_NOTE_TASK_CLOSE";
    /** The sponsor: a note's PDF, issued the first time it is asked for, given again after. */
    public static final String DOCUMENT = "QB_FEE_CREDIT_NOTE_DOCUMENT";
    public static final String DOCUMENT_TEMPLATE = "qb.sponsor.fee-credit-note-doc";
    public static final String LINES_TEMPLATE = "qb.sponsor.fee-credit-note-lines";

    /**
     * H1 of the review: a sponsor's notes with credit left, summed in SQL (never all their notes and uses), and the
     * notes so far on one fee.
     */
    public static final String LEFT_TEMPLATE = "qb.system.credit-notes-left";
    public static final String CHARGE_NOTES_TEMPLATE = "qb.system.fee-charge-notes";

    /** The postings' descriptions (the monthly report tells them apart by their accounts and source). */
    static final String ISSUED = "Platform fee credit note";
    static final String GIVEN_BACK = "Platform fee given back (credit note)";
    static final String PREPAYMENT_GIVEN_BACK = "Platform fee paid from its prepayment given back (credit note)";

    /**
     * @param sponsorId the fee's sponsor (the caller holds their wallet lock)
     * @param chargeId  the fee credited
     * @param amount    what the note is for, before tax
     * @param reason    {@code AUDIENCE_UNUSED} or {@code MANUAL}
     * @param endReason an automatic note's: why the publication ended
     * @param usedDays  an automatic note's: the days it was on
     * @param note      a note by hand's: finance's reason, printed on it
     * @param sourceKey what it stands for: one note a source
     * @param issuedBy  who issued it (none: the system)
     * @param exact     refuse rather than cut a note that is more than the fee may still be credited (by hand)
     */
    public record IssueInput(@NotBlank @Size(max = 128) String sponsorId, @NotBlank @Size(max = 36) String chargeId,
        @NotNull @DecimalMin("1") @DecimalMax(FeeProcesses.MAX_AMOUNT) @Digits(integer = 13, fraction = 0)
        BigDecimal amount,
        @NotBlank @Pattern(regexp = "AUDIENCE_UNUSED|MANUAL") String reason, @Size(max = 20) String endReason,
        @DecimalMin("0") @DecimalMax("99999") @Digits(integer = 5, fraction = 0) BigDecimal usedDays,
        @Size(max = QbFees.REASON_LENGTH) String note, @NotBlank @Size(max = QbFees.SOURCE_LENGTH) String sourceKey,
        @Size(max = 128) String issuedBy, boolean exact) {}

    /**
     * @param creditNoteId   the note; none when nothing was left to credit
     * @param appliedOwn     paid of the fee's own invoice
     * @param refundedCash   given back to the cash
     * @param refundedCredit given back to the fee credit ({@code voidedCredit} of it voided at once, past its date)
     * @param appliedOthers  paid of the sponsor's other unpaid invoices
     * @param unused         left as credit for the next invoice
     */
    public record IssueOutput(String creditNoteId, String creditNoteNo, String billingMode, BigDecimal amount,
        BigDecimal tax, BigDecimal total, BigDecimal appliedOwn, BigDecimal refundedCash, BigDecimal refundedCredit,
        BigDecimal voidedCredit, BigDecimal appliedOthers, BigDecimal unused) {

        static IssueOutput none() {
            BigDecimal zero = BigDecimal.ZERO;
            return new IssueOutput(null, null, null, zero, zero, zero, zero, zero, zero, zero, zero, zero);
        }
    }

    public record TaskCloseInput(@NotBlank @Size(max = 36) String invoiceId) {}

    /** @param language the document's language (none: the sponsor's) */
    public record DocumentInput(@NotBlank @Size(max = 36) String creditNoteId, @Size(max = 10) String language) {}

    public record DocumentOutput(String creditNoteId, String creditNoteNo, String documentRunId, String documentNo) {}

    public record TaskCloseOutput(String invoiceId) {}

    static final String INPUT = "input";
    static final String CHARGE = "charge";
    static final String OWN_INVOICE = "ownInvoice";
    static final String MONTH_INVOICE = "monthInvoice";
    static final String UNPAID = "unpaid";
    static final String FEE_NOTES = "feeNotes";
    static final String SOURCE_NOTE = "sourceNote";
    static final String DECIDED = "decided";
    static final String EXISTING = "existing";
    static final String NOTE_NO = "noteNo";
    static final String POSTS = "posts";
    static final String POSTED = "posted";
    static final String BACK_INDEX = "backIndex";
    static final String PREPAYMENT_INDEX = "prepaymentIndex";
    static final String OUTPUT = "output";
    static final String PAID_OVERDUE = "paidOverdue";
    static final String NOTE = "note";
    static final String NOTE_DOCUMENT = "noteDocument";

    /** What was decided before the note is written. */
    record Decided(EntityInstance charge, EntityInstance invoice, BigDecimal amount, BigDecimal tax, BigDecimal total,
        CreditNoteAllocation.Result allocation) {}

    private CreditNotes() {}

    public static ProcessDefinition<IssueInput, IssueOutput, ProcessContext> issue(ZoneId zone) {
        return ProcessDefinition.define(ISSUE, 1, IssueInput.class, IssueOutput.class, ProcessContext.class, pb -> pb
            .description("Issues a credit note on a platform fee: pays its invoice, gives back what a prepaid fee was "
                + "paid with, and leaves the rest as credit for the sponsor's invoices (under the wallet lock the "
                + "caller holds).")
            .permissions(QbPermissions.FEE_SYSTEM)
            .subProcessOnly()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, IssueOutput.class) : IssueOutput.none())
            .step("Read the fee, its invoice, the sponsor's unpaid invoices, the note of this source and wallets",
                QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFees.defaultDataset(QbFees.CHARGE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("chargeId", FeeGate.ids(input(ctx).chargeId()))).limit(1)
                        .build(), CHARGE),
                    QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("chargeId", FeeGate.ids(input(ctx).chargeId()))).limit(1)
                        .build(), OWN_INVOICE),
                    QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE), ctx -> unpaid(input(ctx).sponsorId()),
                        UNPAID),
                    // A note is issued once for its source (the unique (sponsorId, sourceKey)).
                    QueryEntities.query(QbFees.defaultDataset(QbCreditNotes.CREDIT_NOTE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("sponsorId", FeeGate.ids(input(ctx).sponsorId())),
                            new QueryPredicate.Eq("sourceKey", input(ctx).sourceKey())))).limit(1).build(),
                        SOURCE_NOTE),
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                        ctx -> QbWallets.query(QbWallets.CASH, input(ctx).sponsorId()), FeeSettlement.CASH_WALLET),
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                        ctx -> QbWallets.query(QbWallets.FEE_CREDIT, input(ctx).sponsorId()),
                        FeeSettlement.CREDIT_WALLET)))
            // The fee's limits, and the notes on it before whose parts given back are read with its own.
            .step("Read the notes on the fee", RunTemplate.<ProcessContext>of(CHARGE_NOTES_TEMPLATE,
                ctx -> Map.of("chargeId", input(ctx).chargeId()), FEE_NOTES))
            // A postpaid fee not paid early is on its month's invoice; a prepaid fee's parts are read to give back.
            .step("Read the month's invoice and what paid the fee", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE), CreditNotes::monthInvoice, MONTH_INVOICE),
                QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.PAYMENT), CreditNotes::payments,
                    FeeSettlement.PAYMENTS)))
            .step("Read the lots it was paid from", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT),
                    ctx -> FeeSettlement.byIds("grantId", FeeSettlement.lotIds(ctx, "grantId")),
                    FeeSettlement.PAID_GRANTS),
                QueryEntities.query(CashLots.dataset(),
                    ctx -> FeeSettlement.byIds("lotId", FeeSettlement.lotIds(ctx, "cashLotId")),
                    FeeSettlement.PAID_LOTS),
                QueryEntities.query(QbFeeAccount.defaultDataset(QbPrepayments.PREPAYMENT),
                    ctx -> FeeSettlement.byIds("prepaymentId", FeeSettlement.lotIds(ctx, "prepaymentId")),
                    FeeSettlement.PAID_PREPAYMENTS)))
            .compute("Decide the note", (metadata, ctx) -> decide(ctx))
            .step("Number the note", AssignNumber.<ProcessContext>when(ctx -> ctx.contains(DECIDED),
                QbCreditNotes.SEQUENCE, ctx -> FeeRules.period(ctx.opTime(), zone).substring(0, 4), NOTE_NO))
            .compute("Write the note and where it goes", (metadata, ctx) -> record(ctx, zone))
            // Written first: the postings name it as their source.
            .step("Save the note", SaveChanges.now())
            .step("Post the note and what it gives back", CallProcess.<ProcessContext>forEach(LedgerProcesses.POST, 1,
                ctx -> ctx.contains(POSTS) ? (List<?>) ctx.get(POSTS) : List.of(), POSTED))
            .compute("Record what it gave back", (metadata, ctx) -> recordBack(ctx))
            .step("Close the reminders of the invoices it paid", CallProcess.<ProcessContext>forEach(TASK_CLOSE, 1,
                ctx -> ctx.contains(PAID_OVERDUE) ? (List<?>) ctx.get(PAID_OVERDUE) : List.of(), "closed")));
    }

    public static ProcessDefinition<TaskCloseInput, TaskCloseOutput, ProcessContext> taskClose() {
        return ProcessDefinition.define(TASK_CLOSE, 1, TaskCloseInput.class, TaskCloseOutput.class,
            ProcessContext.class, pb -> pb
                .description("Closes the reminder of an overdue platform fee invoice a credit note paid.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .subProcessOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new TaskCloseOutput(ctx.get(INPUT, TaskCloseInput.class).invoiceId()))
                .step("Close the reminder", CloseTasks.<ProcessContext>done(
                    ctx -> QbFees.overdueTaskSource(ctx.get(INPUT, TaskCloseInput.class).invoiceId()))));
    }

    /**
     * {@value #DOCUMENT}: a note of one's own as a PDF ({@code qb.fee_credit_note}), issued once (the first time it is
     * asked for: a job holds no permission to issue a document) and given again after; through the sponsor's own
     * dataset, another sponsor's note is not found (404).
     */
    public static ProcessDefinition<DocumentInput, DocumentOutput, ProcessContext> document() {
        return ProcessDefinition.define(DOCUMENT, 1, DocumentInput.class, DocumentOutput.class, ProcessContext.class,
            pb -> pb
                .description("Issues one's own credit note as a PDF the first time it is asked for, and gives the "
                    + "same document after.")
                .permissions(QbPermissions.TOPUP)
                .requiresVerifiedEmail()
                .actsOn(QbCreditNotes.CREDIT_NOTE, "creditNoteId")
                .steps(com.jabiz.quizbuks.identity.CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put("creditNoteId", input.creditNoteId());
                    return ctx;
                })
                .outputMapper(CreditNotes::documentOutput)
                .step("Take the note's lock", com.jabiz.runtime.process.steps.HoldLock.<ProcessContext>exclusive(
                    ctx -> "qb.fee-credit-note:" + ctx.get(INPUT, DocumentInput.class).creditNoteId()))
                .step("Load the note", com.jabiz.runtime.process.steps.LoadEntity.by(
                    QbCreditNotes.SPONSOR_CREDIT_NOTES, "creditNoteId", NOTE))
                .step("Load its document and the issuer", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbCreditNotes.SPONSOR_DOCUMENTS, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("creditNoteId",
                            FeeGate.ids(ctx.get(INPUT, DocumentInput.class).creditNoteId()))).limit(1).build(),
                        NOTE_DOCUMENT),
                    QueryEntities.query(com.jabiz.runtime.param.ParamEntities.DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In(com.jabiz.runtime.param.ParamEntities.KEY,
                            new ArrayList<>(com.jabiz.quizbuks.setup.QbParams.ISSUER)))
                        .limit(com.jabiz.quizbuks.setup.QbParams.ISSUER.size()).build(), FeeProcesses.ISSUER)))
                .compute("Check the issuer", (metadata, ctx) -> {
                    boolean named = FeeGate.list(ctx, FeeProcesses.ISSUER).stream().anyMatch(p ->
                        com.jabiz.quizbuks.setup.QbParams.ISSUER_NAME.equals(p.get(
                            com.jabiz.runtime.param.ParamEntities.KEY)) && p.get("value") != null
                            && !String.valueOf((Object) p.get("value")).isBlank());
                    if (!named) {
                        throw new BusinessRuleViolationException(new Violation(null, FeeCodes.FEE_PARAMS,
                            "The issuer's name is not set", Map.of("param",
                                com.jabiz.quizbuks.setup.QbParams.ISSUER_NAME)));
                    }
                })
                .step("Issue the document", CallProcess.<ProcessContext>when(
                    ctx -> FeeGate.first(ctx, NOTE_DOCUMENT) == null,
                    com.jabiz.runtime.document.DocumentProcesses.ISSUE, 1,
                    ctx -> new com.jabiz.runtime.document.DocumentProcesses.IssueInput(QbCreditNotes.LAYOUT_ID,
                        Map.of("creditNoteId", ctx.get(INPUT, DocumentInput.class).creditNoteId()), null, null,
                        ctx.get(INPUT, DocumentInput.class).language()), FeeProcesses.ISSUED))
                .compute("Record the document", (metadata, ctx) -> {
                    if (!ctx.contains(FeeProcesses.ISSUED)) {
                        return;
                    }
                    com.jabiz.runtime.document.DocumentProcesses.IssueOutput issued = ctx.get(FeeProcesses.ISSUED,
                        com.jabiz.runtime.document.DocumentProcesses.IssueOutput.class);
                    EntityInstance note = ctx.get(NOTE, EntityInstance.class);
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("creditNoteId", String.valueOf(note.id()));
                    values.put("sponsorId", note.get("sponsorId"));
                    values.put("documentRunId", issued.runId());
                    values.put("documentNo", issued.documentNo());
                    values.put("issuedAt", ctx.opTime());
                    ctx.changes().insert(QbCreditNotes.DOCUMENT, values);
                }));
    }

    static DocumentOutput documentOutput(ProcessContext ctx) {
        EntityInstance note = ctx.get(NOTE, EntityInstance.class);
        EntityInstance document = FeeGate.first(ctx, NOTE_DOCUMENT);
        com.jabiz.runtime.document.DocumentProcesses.IssueOutput issued = ctx.contains(FeeProcesses.ISSUED)
            ? ctx.get(FeeProcesses.ISSUED, com.jabiz.runtime.document.DocumentProcesses.IssueOutput.class) : null;
        return new DocumentOutput(note == null ? null : String.valueOf(note.id()),
            note == null ? null : note.get("creditNoteNo"),
            issued != null ? issued.runId() : document == null ? null : document.get("documentRunId"),
            issued != null ? issued.documentNo() : document == null ? null : document.get("documentNo"));
    }

    // ---- reads ---------------------------------------------------------------------------------------------------------

    /** A sponsor's unpaid invoices (OVERDUE included, C1), the earliest first. */
    public static EntityQuery unpaid(Object sponsorId) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.In("sponsorId", FeeGate.ids(sponsorId)),
                new QueryPredicate.In("status", new ArrayList<Object>(QbFees.UNPAID)))))
            .orderBy("issuedAt", true).limit(FeeGate.MAX_UNPAID).build();
    }

    /** The rows of {@value #CHARGE_NOTES_TEMPLATE} (or {@value #LEFT_TEMPLATE}) read under {@code key}. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> rows(ProcessContext ctx, String key) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(key);
        return rows == null ? List.of() : rows;
    }

    static EntityQuery monthInvoice(ProcessContext ctx) {
        EntityInstance charge = FeeGate.first(ctx, CHARGE);
        boolean month = charge != null && FeeRules.POSTPAID.equals(charge.get("billingMode"))
            && FeeGate.first(ctx, OWN_INVOICE) == null;
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.In("sponsorId", month ? FeeGate.ids(input(ctx).sponsorId()) : List.of()),
                new QueryPredicate.Eq("period", month ? charge.get("period") : ""),
                new QueryPredicate.IsNull("chargeId"))))
            .limit(1).build();
    }

    /**
     * What paid a prepaid fee and what came back of it: the parts aimed at the fee, its own invoice, and the notes on
     * it before (their parts given back). None for a postpaid fee: it is never given cash (C2).
     */
    static EntityQuery payments(ProcessContext ctx) {
        EntityInstance charge = FeeGate.first(ctx, CHARGE);
        List<Object> targets = new ArrayList<>();
        if (charge != null && FeeRules.PREPAID.equals(charge.get("billingMode"))) {
            targets.add(String.valueOf(charge.id()));
            EntityInstance own = FeeGate.first(ctx, OWN_INVOICE);
            if (own != null) {
                targets.add(String.valueOf(own.id()));
            }
            for (Map<String, Object> note : rows(ctx, FEE_NOTES)) {
                targets.add(String.valueOf(note.get("creditNoteId")));
            }
        }
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.In("sponsorId", targets.isEmpty() ? List.of()
                    : FeeGate.ids(input(ctx).sponsorId())),
                new QueryPredicate.In("targetId", targets))))
            .orderBy("paidAt", true).limit(FeeSettlement.MAX_PARTS + 1).build();
    }

    // ---- the decision ------------------------------------------------------------------------------------------------

    static void decide(ProcessContext ctx) {
        IssueInput input = input(ctx);
        EntityInstance charge = FeeGate.first(ctx, CHARGE);
        if (charge == null || !input.sponsorId().equals(String.valueOf((Object) charge.get("sponsorId")))) {
            // The callers read the fee of the sponsor whose lock they hold.
            throw new IllegalStateException("Fee " + input.chargeId() + " is not " + input.sponsorId() + "'s");
        }
        if (FeeGate.list(ctx, FeeSettlement.PAYMENTS).size() > FeeSettlement.MAX_PARTS) {
            throw new IllegalStateException("More payment parts than are read for fee " + input.chargeId());
        }
        EntityInstance existing = FeeGate.first(ctx, SOURCE_NOTE);
        if (existing != null) {
            ctx.put(EXISTING, existing);
            ctx.put(OUTPUT, output(existing, null, BigDecimal.ZERO));
            return;
        }
        EntityInstance invoice = FeeGate.first(ctx, OWN_INVOICE) != null ? FeeGate.first(ctx, OWN_INVOICE)
            : FeeGate.first(ctx, MONTH_INVOICE);
        if (invoice != null && QbFees.VOID.equals(invoice.get("status"))) {
            throw new BusinessRuleViolationException(new Violation("chargeId", FeeCodes.CREDIT_NOTE_VOIDED,
                "The fee's invoice is voided: what it was paid went back with it", Map.of(
                    "invoiceNo", String.valueOf((Object) invoice.get("invoiceNo")))));
        }
        BigDecimal feeAmount = FeeGate.money(charge.get("amount"));
        BigDecimal credited = BigDecimal.ZERO;
        BigDecimal creditedTax = BigDecimal.ZERO;
        for (Map<String, Object> note : rows(ctx, FEE_NOTES)) {
            credited = credited.add(FeeGate.money(note.get("amount")));
            creditedTax = creditedTax.add(FeeGate.money(note.get("tax")));
        }
        BigDecimal creditable = CreditNoteRules.creditable(feeAmount, credited);
        BigDecimal amount = input.amount();
        if (amount.compareTo(creditable) > 0) {
            if (input.exact()) {
                throw new BusinessRuleViolationException(new Violation("amount", FeeCodes.CREDIT_NOTE_EXCEEDS,
                    "The fee may still be credited " + creditable, Map.of("creditable", creditable.toPlainString(),
                        "amount", amount.toPlainString())));
            }
            amount = creditable;
        }
        if (amount.signum() <= 0) {
            return;
        }
        BigDecimal tax = CreditNoteRules.tax(amount, feeAmount, FeeGate.money(charge.get("tax")),
            FeeGate.money(charge.get("taxRate")), credited, creditedTax);
        BigDecimal total = amount.add(tax);
        boolean prepaid = FeeRules.PREPAID.equals(charge.get("billingMode"));
        BigDecimal ownOwed = invoice != null && QbFees.UNPAID.contains(String.valueOf((Object) invoice.get("status")))
            ? QbFees.owed(invoice) : BigDecimal.ZERO;
        List<CreditNoteAllocation.Part> parts = new ArrayList<>();
        for (EntityInstance payment : FeeGate.list(ctx, FeeSettlement.PAYMENTS)) {
            boolean credit = QbFeeAccount.FEE_CREDIT.equals(payment.get("subAccount"));
            Object lot = payment.get(credit ? "grantId" : "cashLotId");
            if (lot == null) {
                continue;
            }
            Object prepayment = payment.get("prepaymentId");
            // At one time, a fee is paid from its prepayment before the account (QB_FEE_CHARGE), and within each the
            // fee credit before the cash: the account's cash goes back first, the prepayment's fee credit last.
            int order = (prepayment == null ? 2 : 0) + (credit ? 0 : 1);
            parts.add(new CreditNoteAllocation.Part(String.valueOf((Object) payment.get("subAccount")),
                String.valueOf(lot), prepayment == null ? null : String.valueOf(prepayment),
                FeeGate.money(payment.get("amount")), (Instant) payment.get("paidAt"), order));
        }
        List<CreditNoteAllocation.Invoice> others = new ArrayList<>();
        for (EntityInstance other : FeeGate.list(ctx, UNPAID)) {
            if (invoice != null && String.valueOf(invoice.id()).equals(String.valueOf(other.id()))) {
                continue;
            }
            others.add(new CreditNoteAllocation.Invoice(String.valueOf(other.id()),
                String.valueOf((Object) other.get("invoiceNo")), (Instant) other.get("issuedAt"), QbFees.owed(other)));
        }
        ctx.put(DECIDED, new Decided(charge, invoice, amount, tax, total,
            CreditNoteAllocation.allocate(total, prepaid, ownOwed, parts, others)));
    }

    // ---- writing it --------------------------------------------------------------------------------------------------

    static void record(ProcessContext ctx, ZoneId zone) {
        if (!ctx.contains(DECIDED)) {
            return;
        }
        IssueInput input = input(ctx);
        Decided decided = ctx.get(DECIDED, Decided.class);
        CreditNoteAllocation.Result allocation = decided.allocation();
        EntityInstance charge = decided.charge();
        String sponsorId = input.sponsorId();
        // The lots given back, and what of the fee credit is voided at once (a lot voided meanwhile).
        Map<String, BigDecimal> creditBack = new LinkedHashMap<>();
        Map<String, BigDecimal> cashBack = new LinkedHashMap<>();
        Map<String, BigDecimal[]> byPrepayment = new LinkedHashMap<>();
        BigDecimal directCredit = BigDecimal.ZERO;
        BigDecimal directCash = BigDecimal.ZERO;
        for (CreditNoteAllocation.Back back : allocation.back()) {
            boolean credit = QbFeeAccount.FEE_CREDIT.equals(back.subAccount());
            (credit ? creditBack : cashBack).merge(back.lotId(), back.amount(), BigDecimal::add);
            if (back.prepaymentId() == null) {
                if (credit) {
                    directCredit = directCredit.add(back.amount());
                } else {
                    directCash = directCash.add(back.amount());
                }
            } else {
                BigDecimal[] paid = byPrepayment.computeIfAbsent(back.prepaymentId(),
                    id -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                paid[credit ? 0 : 1] = paid[credit ? 0 : 1].add(back.amount());
            }
        }
        BigDecimal refundedCredit = allocation.backCredit();
        BigDecimal refundedCash = allocation.backTotal().subtract(refundedCredit);
        BigDecimal openCredit = FeeSettlement.creditBack(ctx, sponsorId,
            FeeSettlement.byId(FeeGate.list(ctx, FeeSettlement.PAID_GRANTS)), creditBack);
        BigDecimal cash = FeeSettlement.backTo(ctx, QbFeeAccount.CASH_LOT,
            FeeSettlement.byId(FeeGate.list(ctx, FeeSettlement.PAID_LOTS)), cashBack);
        BigDecimal voided = refundedCredit.subtract(openCredit);
        EntityInstance invoice = decided.invoice();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("creditNoteNo", ctx.get(NOTE_NO));
        values.put("sponsorId", sponsorId);
        values.put("operatorId", charge.get("operatorId"));
        values.put("chargeId", String.valueOf(charge.id()));
        values.put("invoiceId", invoice == null ? null : String.valueOf(invoice.id()));
        values.put("invoiceNo", invoice == null ? null : invoice.get("invoiceNo"));
        values.put("publicationId", publicationOf(charge));
        values.put("billingMode", charge.get("billingMode"));
        values.put("reason", input.reason());
        values.put("endReason", input.endReason());
        values.put("usedDays", input.usedDays());
        values.put("note", input.note() == null ? null : input.note().strip());
        values.put("amount", decided.amount());
        values.put("taxRate", charge.get("taxRate"));
        values.put("tax", decided.tax());
        values.put("total", decided.total());
        values.put("appliedOwn", allocation.ownInvoice());
        values.put("refundedCash", refundedCash);
        values.put("refundedCredit", refundedCredit);
        values.put("voidedCredit", voided);
        values.put("sourceKey", input.sourceKey());
        values.put("issuedAt", ctx.opTime());
        values.put("issuedOn", java.time.LocalDate.ofInstant(ctx.opTime(), zone));
        values.put("issuedBy", input.issuedBy());
        String noteId = String.valueOf(ctx.changes().insert(QbCreditNotes.CREDIT_NOTE, values));
        // Its credit pays the fee's own invoice, then the sponsor's other unpaid ones, each a use.
        Map<String, EntityInstance> unpaid = FeeSettlement.byId(FeeGate.list(ctx, UNPAID));
        if (invoice != null) {
            unpaid.put(String.valueOf(invoice.id()), invoice);
        }
        List<CreditNoteAllocation.Applied> applied = new ArrayList<>();
        if (allocation.ownInvoice().signum() > 0) {
            applied.add(new CreditNoteAllocation.Applied(String.valueOf(invoice.id()), allocation.ownInvoice()));
        }
        applied.addAll(allocation.others());
        List<Object> paidOverdue = new ArrayList<>();
        for (CreditNoteAllocation.Applied use : applied) {
            insertUse(ctx, noteId, sponsorId, use.invoiceId(), use.amount());
            EntityInstance paid = unpaid.get(use.invoiceId());
            if (applyCredit(ctx, paid, use.amount()) && QbFees.OVERDUE.equals(paid.get("status"))) {
                paidOverdue.add(new TaskCloseInput(use.invoiceId()));
            }
        }
        ctx.put(PAID_OVERDUE, List.copyOf(paidOverdue));
        if (openCredit.signum() > 0) {
            QbWallets.change(ctx, FeeGate.first(ctx, FeeSettlement.CREDIT_WALLET), QbWallets.FEE_CREDIT, sponsorId,
                openCredit, 0, BigDecimal.ZERO);
        }
        if (cash.signum() > 0) {
            QbWallets.change(ctx, FeeGate.first(ctx, FeeSettlement.CASH_WALLET), QbWallets.CASH, sponsorId, cash, 0,
                BigDecimal.ZERO);
        }
        // The postings: the note; what it gives back (to the account, and into the prepayments it was paid from);
        // out of the prepayments to the account (F-M5's path); the fee credit voided again.
        Map<String, String> party = Map.of(QbLedger.PARTY, sponsorId);
        String reference = String.valueOf(ctx.get(NOTE_NO));
        List<LedgerProcesses.PostInput> posts = new ArrayList<>();
        List<LedgerProcesses.Line> lines = new ArrayList<>();
        lines.add(new LedgerProcesses.Line(QbLedger.PLATFORM_FEE_REVENUE, Direction.DEBIT, decided.amount()));
        if (decided.tax().signum() > 0) {
            lines.add(new LedgerProcesses.Line(QbLedger.TAX_PAYABLE, Direction.DEBIT, decided.tax()));
        }
        lines.add(new LedgerProcesses.Line(QbLedger.FEES_RECEIVABLE, Direction.CREDIT, decided.total(), null, party));
        posts.add(new LedgerProcesses.PostInput(ctx.opTime(), ISSUED, reference, List.copyOf(lines),
            QbCreditNotes.CREDIT_NOTE, noteId));
        if (allocation.backTotal().signum() > 0) {
            Map<String, EntityInstance> prepayments = FeeSettlement.byId(FeeGate.list(ctx,
                FeeSettlement.PAID_PREPAYMENTS));
            List<LedgerProcesses.Line> back = new ArrayList<>();
            back.add(new LedgerProcesses.Line(QbLedger.FEES_RECEIVABLE, Direction.DEBIT, allocation.backTotal(), null,
                party));
            if (directCredit.signum() > 0) {
                back.add(new LedgerProcesses.Line(QbLedger.FEE_CREDIT, Direction.CREDIT, directCredit, null, party));
            }
            if (directCash.signum() > 0) {
                back.add(new LedgerProcesses.Line(QbLedger.SPONSOR_CASH, Direction.CREDIT, directCash, null, party));
            }
            for (Map.Entry<String, BigDecimal[]> entry : byPrepayment.entrySet()) {
                EntityInstance prepayment = prepayments.get(entry.getKey());
                if (prepayment == null) {
                    throw new IllegalStateException("Prepayment " + entry.getKey() + " a fee was paid from is gone");
                }
                back.add(new LedgerProcesses.Line(QbLedger.FEE_PREPAYMENTS, Direction.CREDIT,
                    entry.getValue()[0].add(entry.getValue()[1]), null, Prepayments.dimensions(sponsorId,
                        prepayment.get("publicationId"), prepayment.get("purpose"))));
            }
            ctx.put(BACK_INDEX, posts.size());
            posts.add(new LedgerProcesses.PostInput(ctx.opTime(), GIVEN_BACK, reference, List.copyOf(back),
                QbCreditNotes.CREDIT_NOTE, noteId));
            // A part paid from a prepayment is recorded against the posting out of it, as a voided invoice's is (the
            // daily check FEE_PAYMENTS matches the parts with the postings of 2160 and 2190 they name).
            Map<String, Integer> byPosting = new LinkedHashMap<>();
            for (String prepaymentId : byPrepayment.keySet()) {
                byPosting.put(prepaymentId, posts.size() + byPosting.size());
            }
            ctx.put(PREPAYMENT_INDEX, byPosting);
            posts.addAll(FeeSettlement.prepaymentBack(ctx, sponsorId, byPrepayment, PREPAYMENT_GIVEN_BACK));
        }
        @SuppressWarnings("unchecked")
        List<LedgerProcesses.PostInput> voidedAgain = ctx.contains(FeeSettlement.VOIDED_BACK)
            ? (List<LedgerProcesses.PostInput>) ctx.get(FeeSettlement.VOIDED_BACK) : List.of();
        posts.addAll(voidedAgain);
        ctx.put(POSTS, List.copyOf(posts));
        BigDecimal others = allocation.others().stream().map(CreditNoteAllocation.Applied::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        ctx.put(OUTPUT, new IssueOutput(noteId, String.valueOf(ctx.get(NOTE_NO)),
            String.valueOf((Object) charge.get("billingMode")), decided.amount(), decided.tax(), decided.total(),
            allocation.ownInvoice(), refundedCash, refundedCredit, voided, others, allocation.unused()));
    }

    /** The parts given back, each a part of its own aimed at the note, its posting the note's giving back. */
    static void recordBack(ProcessContext ctx) {
        if (!ctx.contains(BACK_INDEX)) {
            return;
        }
        Decided decided = ctx.get(DECIDED, Decided.class);
        IssueOutput output = ctx.get(OUTPUT, IssueOutput.class);
        @SuppressWarnings("unchecked")
        List<Object> posted = (List<Object>) ctx.get(POSTED);
        String transactionId = ((LedgerProcesses.PostOutput) posted.get((Integer) ctx.get(BACK_INDEX)))
            .transactionId();
        @SuppressWarnings("unchecked")
        Map<String, Integer> byPosting = (Map<String, Integer>) ctx.get(PREPAYMENT_INDEX);
        for (CreditNoteAllocation.Back back : decided.allocation().back()) {
            boolean credit = QbFeeAccount.FEE_CREDIT.equals(back.subAccount());
            String posting = back.prepaymentId() == null ? transactionId
                : ((LedgerProcesses.PostOutput) posted.get(byPosting.get(back.prepaymentId()))).transactionId();
            FeeSettlement.insertPayment(ctx, input(ctx).sponsorId(), QbCreditNotes.CREDIT_NOTE,
                output.creditNoteId(), back.subAccount(), credit ? back.lotId() : null, credit ? null : back.lotId(),
                back.amount().negate(), posting, back.prepaymentId());
        }
    }

    // ---- the credit left -----------------------------------------------------------------------------------------------

    /** What is left of one note's credit. */
    public record Unused(String creditNoteId, String creditNoteNo, Instant issuedAt, BigDecimal amount) {}

    /** What a new invoice takes of one note's credit. */
    public record Take(String creditNoteId, BigDecimal amount) {}

    /**
     * What is left of each note's credit ({@value #LEFT_TEMPLATE}: its total less what it gave back and what its credit
     * paid, its own invoice included; only the notes with some left), the earliest issued first.
     */
    public static List<Unused> left(List<Map<String, Object>> rows) {
        List<Unused> unused = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            BigDecimal left = FeeGate.money(row.get("creditLeft"));
            if (left.signum() > 0) {
                unused.add(new Unused(String.valueOf(row.get("creditNoteId")), String.valueOf(row.get("creditNoteNo")),
                    AudienceRefundJob.instant(row.get("issuedAt")), left));
            }
        }
        unused.sort(Comparator.comparing(Unused::issuedAt).thenComparing(Unused::creditNoteNo));
        return List.copyOf(unused);
    }

    /** {@value #LEFT_TEMPLATE} for one sponsor. */
    public static Map<String, Object> leftParams(String sponsorId) {
        return Map.of("sponsorId", sponsorId);
    }

    public static BigDecimal total(List<Unused> unused) {
        return unused.stream().map(Unused::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** What pays {@code amount} of an invoice from the credit left, the earliest note first, as far as it goes. */
    public static List<Take> take(List<Unused> unused, BigDecimal amount) {
        List<Take> takes = new ArrayList<>();
        BigDecimal left = amount.max(BigDecimal.ZERO);
        for (Unused note : unused) {
            if (left.signum() == 0) {
                break;
            }
            BigDecimal take = note.amount().min(left);
            takes.add(new Take(note.creditNoteId(), take));
            left = left.subtract(take);
        }
        return List.copyOf(takes);
    }

    /** The credit left once {@code takes} are taken from it (for the next invoice in the same transaction). */
    public static List<Unused> after(List<Unused> unused, List<Take> takes) {
        Map<String, BigDecimal> taken = new LinkedHashMap<>();
        takes.forEach(t -> taken.merge(t.creditNoteId(), t.amount(), BigDecimal::add));
        List<Unused> left = new ArrayList<>();
        for (Unused note : unused) {
            BigDecimal rest = note.amount().subtract(taken.getOrDefault(note.creditNoteId(), BigDecimal.ZERO));
            if (rest.signum() > 0) {
                left.add(new Unused(note.creditNoteId(), note.creditNoteNo(), note.issuedAt(), rest));
            }
        }
        return List.copyOf(left);
    }

    public static BigDecimal takenTotal(List<Take> takes) {
        return takes.stream().map(Take::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Writes what an invoice took of the notes' credit (the invoice's {@code creditApplied} is its caller's). */
    public static void recordUses(ProcessContext ctx, String sponsorId, String invoiceId, List<Take> takes) {
        for (Take take : takes) {
            insertUse(ctx, take.creditNoteId(), sponsorId, invoiceId, take.amount());
        }
    }

    static void insertUse(ProcessContext ctx, String noteId, String sponsorId, String invoiceId, BigDecimal amount) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("creditNoteId", noteId);
        values.put("sponsorId", sponsorId);
        values.put("invoiceId", invoiceId);
        values.put("amount", amount);
        values.put("usedAt", ctx.opTime());
        ctx.changes().insert(QbCreditNotes.USE, values);
    }

    /**
     * Pays {@code amount} of an unpaid invoice by credit: its {@code creditApplied} grows; owing nothing, it is
     * {@code PAID} (no receipt: nothing was received); in part, an open one is {@code PARTIAL}. Whether it is paid now.
     */
    static boolean applyCredit(ProcessContext ctx, EntityInstance invoice, BigDecimal amount) {
        if (invoice == null) {
            throw new IllegalStateException("An invoice a credit note paid is gone");
        }
        BigDecimal creditApplied = FeeGate.money(invoice.get("creditApplied")).add(amount);
        BigDecimal owed = FeeGate.money(invoice.get("total")).subtract(creditApplied)
            .subtract(FeeGate.money(invoice.get("paidAmount")));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("creditApplied", creditApplied);
        String status = String.valueOf((Object) invoice.get("status"));
        boolean paid = owed.signum() <= 0;
        if (paid) {
            values.put("status", QbFees.PAID);
            values.put("paidAt", ctx.opTime());
        } else if (QbFees.OPEN.equals(status)) {
            values.put("status", QbFees.PARTIAL);
        }
        ctx.changes().update(QbFees.INVOICE, invoice.id(), invoice.version(), values);
        return paid;
    }

    /** The publication an audience fee was for ({@code publication:<id>}); none for others. */
    static String publicationOf(EntityInstance charge) {
        Object source = charge.get("sourceKey");
        String key = source == null ? "" : String.valueOf(source);
        return key.startsWith("publication:") ? key.substring("publication:".length()) : null;
    }

    static IssueOutput output(EntityInstance note, BigDecimal others, BigDecimal unused) {
        return new IssueOutput(String.valueOf(note.id()), String.valueOf((Object) note.get("creditNoteNo")),
            String.valueOf((Object) note.get("billingMode")), FeeGate.money(note.get("amount")),
            FeeGate.money(note.get("tax")), FeeGate.money(note.get("total")), FeeGate.money(note.get("appliedOwn")),
            FeeGate.money(note.get("refundedCash")), FeeGate.money(note.get("refundedCredit")),
            FeeGate.money(note.get("voidedCredit")), others == null ? BigDecimal.ZERO : others, unused);
    }

    static IssueInput input(ProcessContext ctx) {
        return ctx.get(INPUT, IssueInput.class);
    }
}

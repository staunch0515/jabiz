package com.jabiz.quizbuks.fee;

import com.jabiz.approval.ContentHash;
import com.jabiz.entity.Violation;
import com.jabiz.ledger.Direction;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.identity.SponsorStatus;
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.quizbuks.wallet.TransferProcesses;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.document.DocumentProcesses;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.numbering.AssignNumber;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The platform fees' processes (Q87b, D-Q87-4 to D-Q87-8, docs/quizbuks/plans/Q87-fee-account.md §11):
 * <ul>
 *   <li>{@value #CHARGE}: one charge (a sub-process only, under the sponsor's wallet lock): paid from the cash account
 *       at once when the sponsor is prepaid and the cash covers it (debit 2160), else owed (debit 1130); credit 4120 the
 *       amount and 2150 the provisional tax. With {@code mustCover} it is refused, nothing written, when it cannot be
 *       covered (Q47c-2);</li>
 *   <li>{@value #MONTHLY_ITEM} (the job {@value #MONTHLY_JOB}): the month's fee of one approved sponsor;</li>
 *   <li>{@value #INVOICE_ITEM} (the job {@value #INVOICE_JOB}): the invoice of one sponsor's month, with the tax
 *       rounded once per rate and the difference posted;</li>
 *   <li>{@value #PAY} (the sponsor): pays an invoice, or what is owed of months not invoiced yet, from the cash account
 *       (debit 2160 / credit 1130);</li>
 *   <li>{@value #DOCUMENT} (the sponsor): the invoice's PDF, issued the first time (a person issues documents: the
 *       job, running as the system, holds no layout permission);</li>
 *   <li>{@value #BILLING_SET} (an operator for its own sponsors, or headquarters' finance): the billing mode and
 *       credit;</li>
 *   <li>{@value #OVERDUE_ITEM} (the job {@value #OVERDUE_JOB}): an unpaid invoice past its due date is overdue,
 *       reminded once a week three times at most, with a task for finance and the sponsor's operator;</li>
 *   <li>{@value #REQUEST} (finance) and {@value #REQUEST_RESULT} (the approval's events): a charge by hand, or an
 *       invoice's voiding (its month's postings reversed, the cash taken given back), once another administrator
 *       approves.</li>
 * </ul>
 * What unpaid fees stop is read by {@link #standing} and decided by {@link FeeRules#refusals}.
 */
public final class FeeProcesses {

    public static final String CHARGE = "QB_FEE_CHARGE";
    public static final String MONTHLY_ITEM = "QB_FEE_MONTHLY_ITEM";
    public static final String INVOICE_ITEM = "QB_FEE_INVOICE_ISSUE_ITEM";
    public static final String PAY = "QB_FEE_PAY";
    public static final String DOCUMENT = "QB_FEE_INVOICE_DOCUMENT";
    public static final String BILLING_SET = "QB_SPONSOR_BILLING_SET";
    public static final String OVERDUE_ITEM = "QB_FEE_OVERDUE_ITEM";
    public static final String REQUEST = "QB_FEE_REQUEST";
    public static final String REQUEST_RESULT = "QB_FEE_REQUEST_RESULT";

    public static final String MONTHLY_JOB = "qb.fee-monthly";
    public static final String INVOICE_JOB = "qb.fee-invoice";
    public static final String OVERDUE_JOB = "qb.fee-overdue";

    public static final String STANDING_TEMPLATE = "qb.system.fee-standing";
    public static final String MONTHLY_TEMPLATE = "qb.system.fee-monthly-items";
    public static final String INVOICES_DUE_TEMPLATE = "qb.system.fee-invoices-due";
    public static final String OVERDUE_TEMPLATE = "qb.system.fee-overdue-items";
    public static final String UNINVOICED_TEMPLATE = "qb.system.fee-uninvoiced";
    public static final String VOID_TEMPLATE = "qb.system.fee-void-transactions";
    public static final String DOCUMENT_TEMPLATE = "qb.sponsor.fee-invoice-doc";
    public static final String LINES_TEMPLATE = "qb.sponsor.fee-invoice-lines";
    public static final String ISSUER_TEMPLATE = "qb.sponsor.receipt-issuer";

    public static final String LAYOUT_ID = "qb.fee_invoice";
    public static final String SEQUENCE = "qb.fee-invoice";
    public static final String APPROVAL_SUBJECT = "qb.fee";
    public static final String REVIEW_RULE = "qb.fee.review";

    /** An invoice was issued ({@code invoiceId}, {@code sponsorId}): the sponsor is mailed. */
    public static final String INVOICE_EVENT = "qb.fee.invoice-issued";
    /** An invoice is overdue, or reminded again ({@code invoiceId}, {@code sponsorId}, {@code reminder}). */
    public static final String OVERDUE_EVENT = "qb.fee.invoice-overdue";

    /** The sponsors' datasets of their own invoices and charges. */
    public static final String SPONSOR_INVOICES = "urn:jabiz:dataset:sponsor:" + QbFees.INVOICE;
    public static final String SPONSOR_CHARGES = "urn:jabiz:dataset:sponsor:" + QbFees.CHARGE;
    public static final String SPONSOR_PAYMENTS = "urn:jabiz:dataset:sponsor:" + QbFees.PAYMENT;

    /** The fees' months and dates are Japan's (the invoices are Japanese qualified invoices). */
    public static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
    /** The tax rate before {@code qb.fee.tax-rate} is published. */
    public static final BigDecimal DEFAULT_TAX_RATE = new BigDecimal("0.10");
    /** The days to pay before {@code qb.fee.due-days} is set. */
    public static final int DEFAULT_DUE_DAYS = 30;
    /** The most charges of one sponsor's month an invoice reads. */
    static final int MAX_LINES = 1_000;

    static final String TASK_TYPE = "qb.fee-overdue";
    static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    static final String NO_ID = "00000000-0000-0000-0000-000000000000";

    // ---- inputs and outputs -----------------------------------------------------------------------------------------

    /**
     * @param period    the charge's month ({@code YYYY-MM}, Japan); null: the month of the operation
     * @param mustCover refuse it (writing nothing) when the cash account (prepaid) or the credit (postpaid) does not
     *                  cover it, instead of recording it as owed
     */
    public record ChargeInput(@NotBlank @Size(max = 128) String sponsorId, @NotBlank @Size(max = 20) String kind,
        @NotNull @DecimalMin("1") @DecimalMax("10000000") @Digits(integer = 13, fraction = 0) BigDecimal amount,
        @Pattern(regexp = "[0-9]{4}-[0-9]{2}") String period, @Size(max = QbFees.REASON_LENGTH) String note,
        @Size(max = 64) String sourceEntity, @Size(max = 64) String sourceId, boolean mustCover) {}

    public record ChargeOutput(String chargeId, String settlement, BigDecimal amount, BigDecimal tax) {}

    public record MonthlyInput(@NotBlank @Size(max = 128) String sponsorId,
        @NotBlank @Pattern(regexp = "[0-9]{4}-[0-9]{2}") String period) {}

    /** @param chargeId the charge made; null when there was none to make */
    public record MonthlyOutput(String chargeId) {}

    public record InvoiceItemInput(@NotBlank @Size(max = 128) String sponsorId,
        @NotBlank @Pattern(regexp = "[0-9]{4}-[0-9]{2}") String period) {}

    /** @param invoiceId the invoice issued; null when there was none to issue */
    public record InvoiceOutput(String invoiceId, String invoiceNo, String status, BigDecimal due) {}

    /** @param invoiceId the invoice to pay; null: what is owed of the months not invoiced yet */
    public record PayInput(@Pattern(regexp = UUID) String invoiceId) {}

    public record PayOutput(BigDecimal amount, BigDecimal cashBalance) {}

    public record DocumentInput(@NotBlank @Pattern(regexp = UUID) String invoiceId,
        @NotBlank @Pattern(regexp = "en|zh|ja") String language) {}

    /** @param issued whether this call issued it (false: it was issued before) */
    public record DocumentOutput(String runId, String invoiceNo, boolean issued) {}

    public record BillingInput(@NotBlank @Size(max = 128) String sponsorId,
        @NotBlank @Pattern(regexp = "PREPAID|POSTPAID") String billingMode,
        @NotNull @DecimalMin("0") @DecimalMax("10000000") @Digits(integer = 13, fraction = 0) BigDecimal creditLimit,
        @NotBlank @Size(max = QbFees.REASON_LENGTH) String reason) {}

    public record BillingOutput(String sponsorId, String billingMode, BigDecimal creditLimit) {}

    public record OverdueInput(@NotBlank @Size(max = 128) String sponsorId,
        @NotBlank @Pattern(regexp = UUID) String invoiceId) {}

    /** @param reminder the reminder sent (1 to 3); 0 when none was due */
    public record OverdueOutput(String invoiceId, String status, int reminder) {}

    /**
     * @param kind      {@code CHARGE} (a charge by hand of {@code amount}) or {@code VOID} (the invoice's voiding)
     * @param invoiceId the invoice voided
     */
    public record RequestInput(@NotBlank @Pattern(regexp = "CHARGE|VOID") String kind,
        @NotBlank @Size(max = 128) String sponsorId, @Pattern(regexp = UUID) String invoiceId,
        @DecimalMin("1") @DecimalMax("10000000") @Digits(integer = 13, fraction = 0) BigDecimal amount,
        @NotBlank @Size(max = QbFees.REASON_LENGTH) String reason) {}

    /** @param approvalRequestId the request another administrator decides */
    public record RequestOutput(String requestId, String status, String approvalRequestId) {}

    // ---- context keys -----------------------------------------------------------------------------------------------

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String STANDING = "feeStanding";
    static final String CASH_WALLET = "cashWallet";
    static final String ASSIGNMENT = "assignment";
    static final String NEW_ID = "newId";
    static final String SETTLEMENT = "settlement";
    static final String TAX = "tax";
    static final String POST_INPUT = "postInput";
    static final String POSTING = "posting";
    static final String POSTINGS = "postings";
    static final String CASH_DELTA = "cashDelta";
    static final String EXISTING = "existing";
    static final String CHARGED = "charged";
    static final String CHARGES = "charges";
    static final String EARLY = "early";
    static final String INVOICE = "invoice";
    static final String PLAN = "plan";
    static final String NUMBER = "number";
    static final String UNINVOICED = "uninvoiced";
    static final String AMOUNT = "amount";
    static final String ISSUER = "issuer";
    static final String ISSUED = "issued";
    static final String BILLING = "billing";
    static final String SPONSOR = "sponsor";
    static final String REMINDER = "reminder";
    static final String RULES = "rules";
    static final String OPEN_VOID = "openVoid";
    static final String FEE_REQUEST = "feeRequest";
    static final String APPROVAL = "approval";
    static final String APPROVAL_REQUEST = "approvalRequest";
    static final String STATUS_AFTER = "statusAfter";
    static final String APPLY_CHARGE = "applyCharge";
    static final String APPLY_VOID = "applyVoid";
    static final String VOID_ROWS = "voidRows";
    static final String REVERSED = "reversed";

    /** The month ({@code YYYY-MM}) of an instant, in Japan. */
    public static String period(Instant at) {
        return YearMonth.from(at.atZone(ZONE)).toString();
    }

    /** The day of an instant, in Japan. */
    public static LocalDate day(Instant at) {
        return at.atZone(ZONE).toLocalDate();
    }

    // ---- what unpaid fees stop --------------------------------------------------------------------------------------

    /** Adds the step that reads a sponsor's fee standing (one statement); {@link #refusals} decides from it. */
    public static <I, O> void standing(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Object> sponsorId) {
        pb.step("Read the sponsor's fees", RunTemplate.<ProcessContext>of(STANDING_TEMPLATE,
            ctx -> Map.of("sponsorId", String.valueOf(sponsorId.apply(ctx))), STANDING));
    }

    /** The standing {@link #standing} read; a sponsor without fees is prepaid and owes nothing. */
    public static FeeRules.Standing standing(ProcessContext ctx) {
        Map<String, Object> row = standingRow(ctx);
        if (row == null) {
            return new FeeRules.Standing(FeeRules.PREPAID, BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
        return new FeeRules.Standing(String.valueOf(row.get("billingMode")), money(row.get("creditLimit")),
            money(row.get("unpaid")), Boolean.TRUE.equals(row.get("overdue")));
    }

    /** Why the sponsor {@link #standing} read may not buy Kudos or submit a publication now; none when they may. */
    public static List<Violation> refusals(ProcessContext ctx) {
        Map<String, Object> row = standingRow(ctx);
        boolean blockOverdue = row == null || !Boolean.FALSE.equals(row.get("blockOverdue"));
        return FeeRules.refusals(standing(ctx), blockOverdue);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> standingRow(ProcessContext ctx) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(STANDING);
        return rows == null || rows.isEmpty() ? null : rows.getFirst();
    }

    // ---- a charge ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ChargeInput, ChargeOutput, ProcessContext> charge() {
        return ProcessDefinition.define(CHARGE, 1, ChargeInput.class, ChargeOutput.class, ProcessContext.class, pb -> {
            pb.description("Records one platform fee of a sponsor, paid from the cash account or owed.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .subProcessOnly()
                .contextFactory(FeeProcesses::context)
                .outputMapper(ctx -> new ChargeOutput((String) ctx.get(NEW_ID), (String) ctx.get(SETTLEMENT),
                    ctx.get(INPUT, ChargeInput.class).amount(), (BigDecimal) ctx.get(TAX)))
                .step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbFees.lockName(chargeInput(ctx).sponsorId())))
                .step("Load the tax rate", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    QbParams.FEE_TAX_RATE));
            standing(pb, ctx -> chargeInput(ctx).sponsorId());
            pb.step("Read the cash account and the operator", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                        ctx -> QbWallets.query(QbWallets.CASH, chargeInput(ctx).sponsorId()), CASH_WALLET),
                    QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT),
                        ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("userId",
                            chargeInput(ctx).sponsorId())).limit(1).build(), ASSIGNMENT)))
                .compute("Record the charge", (metadata, ctx) -> recordCharge(ctx))
                // The posting checks that its source document exists: the charge is written first.
                .step("Save the charge", SaveChanges.now())
                .step("Post the charge", CallProcess.<ProcessContext>of(LedgerProcesses.POST, 1,
                    ctx -> ctx.get(POST_INPUT), POSTING))
                .compute("Change the cash account", (metadata, ctx) -> changeCash(ctx, sponsorOf(ctx)));
        });
    }

    static void recordCharge(ProcessContext ctx) {
        ChargeInput input = chargeInput(ctx);
        BigDecimal rate = taxRate(ctx);
        BigDecimal amount = input.amount();
        BigDecimal tax = FeeRules.provisionalTax(amount, rate);
        BigDecimal gross = amount.add(tax);
        EntityInstance cash = first(ctx, CASH_WALLET);
        BigDecimal available = QbWallets.available(cash);
        FeeRules.Standing standing = standing(ctx);
        if (input.mustCover()) {
            Violation uncovered = FeeRules.uncovered(standing, available, gross);
            if (uncovered != null) {
                throw new BusinessRuleViolationException(List.of(uncovered));
            }
        }
        boolean fromCash = FeeRules.paysFromCash(standing.mode(), available, gross);
        EntityInstance assignment = first(ctx, ASSIGNMENT);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sponsorId", input.sponsorId());
        values.put("kind", input.kind());
        values.put("period", input.period() == null ? period(ctx.opTime()) : input.period());
        values.put("amount", amount);
        values.put("taxRate", rate);
        values.put("tax", tax);
        values.put("settlement", fromCash ? QbFees.CASH : QbFees.RECEIVABLE);
        // The sponsor's operator now: the settlement counts the fee under it even if the sponsor moves later.
        values.put("operatorId", assignment == null ? null : String.valueOf((Object) assignment.get("operatorId")));
        values.put("sourceEntity", input.sourceEntity());
        values.put("sourceId", input.sourceId());
        values.put("note", input.note() == null || input.note().isBlank() ? null : input.note().strip());
        values.put("chargedAt", ctx.opTime());
        String chargeId = String.valueOf(ctx.changes().insert(QbFees.CHARGE, values));
        ctx.put(NEW_ID, chargeId);
        ctx.put(SETTLEMENT, values.get("settlement"));
        ctx.put(TAX, tax);
        ctx.put(CASH_DELTA, fromCash ? gross.negate() : BigDecimal.ZERO);
        Map<String, String> party = Map.of(QbLedger.PARTY, input.sponsorId());
        List<LedgerProcesses.Line> lines = new ArrayList<>();
        lines.add(new LedgerProcesses.Line(fromCash ? QbLedger.SPONSOR_CASH : QbLedger.FEES_RECEIVABLE,
            Direction.DEBIT, gross, null, party));
        lines.add(new LedgerProcesses.Line(QbLedger.PLATFORM_FEE_REVENUE, Direction.CREDIT, amount, null, party));
        if (tax.signum() > 0) {
            lines.add(new LedgerProcesses.Line(QbLedger.CONSUMPTION_TAX_PAYABLE, Direction.CREDIT, tax, null, party));
        }
        ctx.put(POST_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), "Platform fee (" + input.kind() + ")", null,
            lines, QbFees.CHARGE, chargeId));
    }

    private static BigDecimal taxRate(ProcessContext ctx) {
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        BigDecimal rate = params == null ? null : params.find(QbParams.FEE_TAX_RATE, BigDecimal.class).orElse(null);
        return rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0 ? DEFAULT_TAX_RATE : rate;
    }

    /** Adds {@link #CASH_DELTA} to the sponsor's cash account (nothing when 0). */
    static void changeCash(ProcessContext ctx, String sponsorId) {
        BigDecimal delta = ctx.contains(CASH_DELTA) ? (BigDecimal) ctx.get(CASH_DELTA) : BigDecimal.ZERO;
        if (delta.signum() != 0) {
            QbWallets.change(ctx, first(ctx, CASH_WALLET), QbWallets.CASH, sponsorId, delta, 0, BigDecimal.ZERO);
        }
    }

    private static String sponsorOf(ProcessContext ctx) {
        return chargeInput(ctx).sponsorId();
    }

    // ---- the monthly fee --------------------------------------------------------------------------------------------

    public static ProcessDefinition<MonthlyInput, MonthlyOutput, ProcessContext> monthlyItem() {
        return ProcessDefinition.define(MONTHLY_ITEM, 1, MonthlyInput.class, MonthlyOutput.class,
            ProcessContext.class, pb -> pb
                .description("Charges one sponsor the month's platform fee, once.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory(FeeProcesses::context)
                .outputMapper(ctx -> new MonthlyOutput(ctx.contains(CHARGED)
                    ? ctx.get(CHARGED, ChargeOutput.class).chargeId() : null))
                .step("Load the monthly fee", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    QbParams.FEE_MONTHLY))
                .step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbFees.lockName(ctx.get(INPUT, MonthlyInput.class).sponsorId())))
                .step("Look for the month's charge", QueryEntities.<ProcessContext>of(
                    QbFees.defaultDataset(QbFees.CHARGE), ctx -> {
                        MonthlyInput input = ctx.get(INPUT, MonthlyInput.class);
                        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("sponsorId", input.sponsorId()),
                            new QueryPredicate.Eq("kind", QbFees.MONTHLY),
                            new QueryPredicate.Eq("period", input.period())))).limit(1).build();
                    }, EXISTING))
                .step("Charge the fee", CallProcess.<ProcessContext>when(
                    ctx -> first(ctx, EXISTING) == null && monthlyFee(ctx).signum() > 0, CHARGE, 1, ctx -> {
                        MonthlyInput input = ctx.get(INPUT, MonthlyInput.class);
                        return new ChargeInput(input.sponsorId(), QbFees.MONTHLY, monthlyFee(ctx), input.period(),
                            null, null, null, false);
                    }, CHARGED)));
    }

    static BigDecimal monthlyFee(ProcessContext ctx) {
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        BigDecimal fee = params == null ? null : params.find(QbParams.FEE_MONTHLY, BigDecimal.class).orElse(null);
        return fee == null || fee.signum() < 0 ? BigDecimal.ZERO : fee;
    }

    // ---- invoices ---------------------------------------------------------------------------------------------------

    /** What an invoice item decided: the invoice's figures and status, or nothing to issue. */
    record Plan(FeeRules.Invoice invoice, String billingMode, String status, LocalDate dueOn) {}

    public static ProcessDefinition<InvoiceItemInput, InvoiceOutput, ProcessContext> invoiceItem() {
        return ProcessDefinition.define(INVOICE_ITEM, 1, InvoiceItemInput.class, InvoiceOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Issues one sponsor's invoice of a month, once.")
                    .permissions(QbPermissions.FEE_SYSTEM)
                    .internal()
                    .contextFactory(FeeProcesses::context)
                    .outputMapper(FeeProcesses::invoiceOutput)
                    .step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbFees.lockName(invoiceInput(ctx).sponsorId())))
                    .step("Load the days to pay", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                        QbParams.FEE_DUE_DAYS));
                standing(pb, ctx -> invoiceInput(ctx).sponsorId());
                pb.step("Read the month's charges, payments and invoice", QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbFees.defaultDataset(QbFees.CHARGE), ctx -> month(ctx, null)
                            .limit(MAX_LINES).build(), CHARGES),
                        QueryEntities.query(QbFees.defaultDataset(QbFees.PAYMENT), ctx -> month(ctx,
                            new QueryPredicate.IsNull("invoiceId")).limit(MAX_LINES).build(), EARLY),
                        QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE), ctx -> month(ctx, null)
                            .limit(1).build(), EXISTING),
                        QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                            ctx -> QbWallets.query(QbWallets.CASH, invoiceInput(ctx).sponsorId()), CASH_WALLET)))
                    .compute("Work out the invoice", (metadata, ctx) -> planInvoice(ctx))
                    // After every check: a number drawn is kept (the count is rolled back with a failure).
                    .step("Number the invoice", AssignNumber.<ProcessContext>when(ctx -> ctx.contains(PLAN), SEQUENCE,
                        ctx -> invoiceInput(ctx).period().substring(0, 4), NUMBER))
                    .compute("Record the invoice", (metadata, ctx) -> recordInvoice(ctx))
                    .step("Save the invoice", SaveChanges.now())
                    .step("Post the tax's rounding", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT),
                        LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING))
                    .compute("Change the cash account", (metadata, ctx) -> changeCash(ctx,
                        invoiceInput(ctx).sponsorId()))
                    .step("Announce the invoice", PublishEvent.<ProcessContext>when(ctx -> ctx.contains(PLAN),
                        INVOICE_EVENT, ctx -> Map.of("invoiceId", ctx.get(NEW_ID), "sponsorId",
                            invoiceInput(ctx).sponsorId())));
            });
    }

    private static EntityQuery.Builder month(ProcessContext ctx, QueryPredicate more) {
        InvoiceItemInput input = invoiceInput(ctx);
        List<QueryPredicate> where = new ArrayList<>(List.of(new QueryPredicate.Eq("sponsorId", input.sponsorId()),
            new QueryPredicate.Eq("period", input.period())));
        if (more != null) {
            where.add(more);
        }
        return EntityQuery.builder().where(new QueryPredicate.And(where));
    }

    static void planInvoice(ProcessContext ctx) {
        List<EntityInstance> charges = list(ctx, CHARGES);
        if (first(ctx, EXISTING) != null || charges.isEmpty()) {
            return;
        }
        if (charges.size() >= MAX_LINES) {
            // Never an invoice of part of the month: finance is told by the job's failure.
            throw new IllegalStateException("More than " + MAX_LINES + " charges in one month of one sponsor");
        }
        List<FeeRules.Line> lines = charges.stream().map(charge -> new FeeRules.Line(money(charge.get("amount")),
            money(charge.get("taxRate")), money(charge.get("tax")),
            QbFees.RECEIVABLE.equals(charge.get("settlement")))).toList();
        BigDecimal early = list(ctx, EARLY).stream().map(payment -> money(payment.get("amount")))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        FeeRules.Invoice invoice = FeeRules.invoice(lines, early, QbWallets.available(first(ctx, CASH_WALLET)));
        boolean due = invoice.due().signum() > 0;
        LocalDate dueOn = due ? day(ctx.opTime()).plusDays(dueDays(ctx)) : null;
        ctx.put(PLAN, new Plan(invoice, standing(ctx).mode(), due ? QbFees.ISSUED : QbFees.PAID, dueOn));
    }

    private static long dueDays(ProcessContext ctx) {
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        BigDecimal days = params == null ? null : params.find(QbParams.FEE_DUE_DAYS, BigDecimal.class).orElse(null);
        return days == null || days.signum() < 0 ? DEFAULT_DUE_DAYS : days.longValue();
    }

    static void recordInvoice(ProcessContext ctx) {
        if (!ctx.contains(PLAN)) {
            return;
        }
        Plan plan = ctx.get(PLAN, Plan.class);
        FeeRules.Invoice invoice = plan.invoice();
        InvoiceItemInput input = invoiceInput(ctx);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("invoiceNo", ctx.get(NUMBER));
        values.put("sponsorId", input.sponsorId());
        values.put("period", input.period());
        values.put("billingMode", plan.billingMode());
        values.put("amount", invoice.amount());
        values.put("tax", invoice.tax());
        values.put("total", invoice.total());
        values.put("paidAtCharge", invoice.paid());
        values.put("due", invoice.due());
        values.put("adjustment", invoice.adjustment());
        values.put("status", plan.status());
        values.put("issuedAt", ctx.opTime());
        values.put("dueOn", plan.dueOn());
        values.put("paidAt", QbFees.PAID.equals(plan.status()) ? ctx.opTime() : null);
        values.put("reminders", BigDecimal.ZERO);
        String invoiceId = String.valueOf(ctx.changes().insert(QbFees.INVOICE, values));
        ctx.put(NEW_ID, invoiceId);
        BigDecimal adjustment = invoice.adjustment();
        if (adjustment.signum() == 0) {
            return;
        }
        // More tax: debit what is owed (1130) or the cash account (2160), credit 2150; less: the reverse.
        String side = invoice.adjustDue() ? QbLedger.FEES_RECEIVABLE : QbLedger.SPONSOR_CASH;
        boolean more = adjustment.signum() > 0;
        BigDecimal size = adjustment.abs();
        Map<String, String> party = Map.of(QbLedger.PARTY, input.sponsorId());
        ctx.put(POST_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), "Platform fee tax rounding", null, List.of(
            new LedgerProcesses.Line(more ? side : QbLedger.CONSUMPTION_TAX_PAYABLE, Direction.DEBIT, size, null,
                party),
            new LedgerProcesses.Line(more ? QbLedger.CONSUMPTION_TAX_PAYABLE : side, Direction.CREDIT, size, null,
                party)), QbFees.INVOICE, invoiceId));
        if (!invoice.adjustDue()) {
            ctx.put(CASH_DELTA, adjustment.negate());
        }
    }

    private static InvoiceOutput invoiceOutput(ProcessContext ctx) {
        if (!ctx.contains(PLAN)) {
            return new InvoiceOutput(null, null, null, null);
        }
        Plan plan = ctx.get(PLAN, Plan.class);
        return new InvoiceOutput((String) ctx.get(NEW_ID), (String) ctx.get(NUMBER), plan.status(),
            plan.invoice().due());
    }

    // ---- paying -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<PayInput, PayOutput, ProcessContext> pay() {
        return ProcessDefinition.define(PAY, 1, PayInput.class, PayOutput.class, ProcessContext.class, pb -> pb
            .description("Pays an invoice, or what is owed of the months not invoiced yet, from the cash account.")
            // The top-ups' permission: moving the sponsor's own money in the platform, as a top-up does.
            .permissions(QbPermissions.TOPUP)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .contextFactory(FeeProcesses::context)
            .outputMapper(ctx -> new PayOutput((BigDecimal) ctx.get(AMOUNT),
                QbWallets.balance(first(ctx, CASH_WALLET)).add((BigDecimal) ctx.get(CASH_DELTA))))
            .step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(
                ctx -> QbFees.lockName(ctx.request().actorId())))
            // Through the sponsor's own dataset: another sponsor's invoice is not found.
            .step("Read the invoice and the cash account", QueryEntities.<ProcessContext>all(
                QueryEntities.query(SPONSOR_INVOICES, ctx -> byIds("invoiceId",
                    ctx.get(INPUT, PayInput.class).invoiceId()), INVOICE),
                QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                    ctx -> QbWallets.query(QbWallets.CASH, ctx.request().actorId()), CASH_WALLET)))
            .step("Read what is owed of the months not invoiced", RunTemplate.<ProcessContext>of(UNINVOICED_TEMPLATE,
                ctx -> Map.of("sponsorId", ctx.request().actorId()), UNINVOICED))
            .compute("Record the payment", (metadata, ctx) -> recordPayment(ctx))
            .step("Save the payment", SaveChanges.now())
            .step("Post the payment", CallProcess.forEach(LedgerProcesses.POST, 1,
                ctx -> postInputs(ctx), POSTINGS))
            .compute("Change the cash account", (metadata, ctx) -> changeCash(ctx, ctx.request().actorId())));
    }

    static void recordPayment(ProcessContext ctx) {
        PayInput input = ctx.get(INPUT, PayInput.class);
        String sponsorId = ctx.request().actorId();
        Map<String, BigDecimal> byPeriod = new LinkedHashMap<>();
        EntityInstance invoice = first(ctx, INVOICE);
        if (input.invoiceId() != null) {
            if (invoice == null || !QbFees.UNPAID_STATUSES.contains(String.valueOf((Object) invoice.get("status")))) {
                throw new BusinessRuleViolationException(List.of(new Violation("invoiceId", FeeCodes.INVOICE_STATE,
                    "The invoice is not one of yours that is unpaid", Map.of())));
            }
            byPeriod.put(invoice.get("period"), money(invoice.get("due")));
        } else {
            for (Map<String, Object> row : rows(ctx, UNINVOICED)) {
                byPeriod.put(String.valueOf(row.get("period")), money(row.get("owed")));
            }
        }
        BigDecimal amount = byPeriod.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (amount.signum() <= 0) {
            throw new BusinessRuleViolationException(List.of(new Violation(null, FeeCodes.INVOICE_STATE,
                "Nothing is owed", Map.of())));
        }
        BigDecimal available = QbWallets.available(first(ctx, CASH_WALLET));
        if (available.compareTo(amount) < 0) {
            throw new BusinessRuleViolationException(List.of(new Violation(null, FeeCodes.INVOICE_FUNDS,
                "The cash account does not cover it", Map.of("amount", amount.toPlainString(), "available",
                    available.max(BigDecimal.ZERO).toPlainString()))));
        }
        List<LedgerProcesses.PostInput> postings = new ArrayList<>();
        Map<String, String> party = Map.of(QbLedger.PARTY, sponsorId);
        for (Map.Entry<String, BigDecimal> entry : byPeriod.entrySet()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("sponsorId", sponsorId);
            values.put("period", entry.getKey());
            values.put("invoiceId", invoice == null ? null : String.valueOf(invoice.id()));
            values.put("amount", entry.getValue());
            values.put("paidAt", ctx.opTime());
            String paymentId = String.valueOf(ctx.changes().insert(QbFees.PAYMENT, values));
            postings.add(new LedgerProcesses.PostInput(ctx.opTime(), "Platform fees paid", null, List.of(
                new LedgerProcesses.Line(QbLedger.SPONSOR_CASH, Direction.DEBIT, entry.getValue(), null, party),
                new LedgerProcesses.Line(QbLedger.FEES_RECEIVABLE, Direction.CREDIT, entry.getValue(), null, party)),
                QbFees.PAYMENT, paymentId));
        }
        if (invoice != null) {
            ctx.changes().update(QbFees.INVOICE, invoice.id(), invoice.version(), Map.of("status", QbFees.PAID,
                "paidAt", ctx.opTime()));
        }
        ctx.put(POSTINGS + "In", postings);
        ctx.put(AMOUNT, amount);
        ctx.put(CASH_DELTA, amount.negate());
    }

    @SuppressWarnings("unchecked")
    private static List<LedgerProcesses.PostInput> postInputs(ProcessContext ctx) {
        List<LedgerProcesses.PostInput> inputs = (List<LedgerProcesses.PostInput>) ctx.get(POSTINGS + "In");
        return inputs == null ? List.of() : inputs;
    }

    // ---- the invoice's PDF ------------------------------------------------------------------------------------------

    public static ProcessDefinition<DocumentInput, DocumentOutput, ProcessContext> document() {
        return ProcessDefinition.define(DOCUMENT, 1, DocumentInput.class, DocumentOutput.class, ProcessContext.class,
            pb -> pb
                .description("Issues one's own invoice's PDF, or gives the one already issued.")
                .permissions(QbPermissions.TOPUP)
                .requiresVerifiedEmail()
                .actsOn(QbFees.INVOICE, "invoiceId")
                .steps(CallerChecks::refuseBanned)
                .contextFactory(FeeProcesses::context)
                .outputMapper(FeeProcesses::documentOutput)
                .step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbFees.lockName(ctx.request().actorId())))
                // Through the sponsor's own dataset: another sponsor's invoice is not found (404).
                .step("Read the invoice", QueryEntities.<ProcessContext>of(SPONSOR_INVOICES,
                    ctx -> byIds("invoiceId", ctx.get(INPUT, DocumentInput.class).invoiceId()), INVOICE))
                .compute("Check the invoice", (metadata, ctx) -> {
                    if (first(ctx, INVOICE) == null) {
                        throw new BusinessRuleViolationException(List.of(new Violation("invoiceId",
                            FeeCodes.INVOICE_STATE, "The invoice is not one of yours", Map.of())));
                    }
                })
                .step("Issue the invoice", CallProcess.<ProcessContext>when(
                    ctx -> first(ctx, INVOICE).get("documentRunId") == null, DocumentProcesses.ISSUE, 1,
                    ctx -> new DocumentProcesses.IssueInput(LAYOUT_ID, Map.of("invoiceId",
                        String.valueOf(first(ctx, INVOICE).id())), null, null,
                        ctx.get(INPUT, DocumentInput.class).language()), ISSUED))
                .compute("Record it", (metadata, ctx) -> {
                    if (!ctx.contains(ISSUED)) {
                        return;
                    }
                    EntityInstance invoice = first(ctx, INVOICE);
                    ctx.changes().update(QbFees.INVOICE, invoice.id(), invoice.version(), Map.of("documentRunId",
                        ctx.get(ISSUED, DocumentProcesses.IssueOutput.class).runId()));
                }));
    }

    private static DocumentOutput documentOutput(ProcessContext ctx) {
        EntityInstance invoice = first(ctx, INVOICE);
        if (ctx.contains(ISSUED)) {
            return new DocumentOutput(ctx.get(ISSUED, DocumentProcesses.IssueOutput.class).runId(),
                invoice.get("invoiceNo"), true);
        }
        return new DocumentOutput(invoice.get("documentRunId"), invoice.get("invoiceNo"), false);
    }

    // ---- billing mode -----------------------------------------------------------------------------------------------

    public static ProcessDefinition<BillingInput, BillingOutput, ProcessContext> billingSet() {
        return ProcessDefinition.define(BILLING_SET, 1, BillingInput.class, BillingOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Sets a sponsor's billing mode (prepaid or postpaid) and credit, with the reason.")
                    .permissions(QbPermissions.BILLING_WRITE)
                    .requiresVerifiedEmail()
                    .actsOn(QbAccounts.SPONSOR, "sponsorId")
                    .contextFactory(FeeProcesses::context)
                    .outputMapper(ctx -> {
                        BillingInput input = ctx.get(INPUT, BillingInput.class);
                        return new BillingOutput(input.sponsorId(), input.billingMode(), creditOf(input));
                    });
                // An operator's member for its own sponsors only (any other is not found); headquarters any.
                OperatorChecks.memberWhenScoped(pb);
                OperatorChecks.ownSponsor(pb, ctx -> ctx.get(INPUT, BillingInput.class).sponsorId());
                pb.step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbFees.lockName(ctx.get(INPUT, BillingInput.class).sponsorId())))
                    .step("Read the sponsor and the billing", QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> byIds("userId",
                            ctx.get(INPUT, BillingInput.class).sponsorId()), SPONSOR),
                        QueryEntities.query(QbFees.defaultDataset(QbFees.BILLING), ctx -> byIds("sponsorId",
                            ctx.get(INPUT, BillingInput.class).sponsorId()), BILLING)))
                    .compute("Set the billing", (metadata, ctx) -> setBilling(ctx));
            });
    }

    /** A prepaid sponsor has no credit. */
    private static BigDecimal creditOf(BillingInput input) {
        return FeeRules.POSTPAID.equals(input.billingMode()) ? input.creditLimit() : BigDecimal.ZERO;
    }

    static void setBilling(ProcessContext ctx) {
        BillingInput input = ctx.get(INPUT, BillingInput.class);
        if (first(ctx, SPONSOR) == null) {
            throw new BusinessRuleViolationException(List.of(new Violation("sponsorId", FeeCodes.BILLING,
                "No such sponsor", Map.of())));
        }
        if (FeeRules.PREPAID.equals(input.billingMode()) && input.creditLimit().signum() != 0) {
            throw new BusinessRuleViolationException(List.of(new Violation("creditLimit", FeeCodes.BILLING,
                "A prepaid sponsor has no credit", Map.of())));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("billingMode", input.billingMode());
        values.put("creditLimit", creditOf(input));
        values.put("reason", input.reason().strip());
        values.put("updatedAt", ctx.opTime());
        values.put("updatedBy", ctx.request().actorId());
        EntityInstance billing = first(ctx, BILLING);
        if (billing == null) {
            values.put("sponsorId", input.sponsorId());
            ctx.changes().insert(QbFees.BILLING, values);
        } else {
            ctx.changes().update(QbFees.BILLING, billing.id(), billing.version(), values);
        }
    }

    // ---- overdue ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<OverdueInput, OverdueOutput, ProcessContext> overdueItem() {
        return ProcessDefinition.define(OVERDUE_ITEM, 1, OverdueInput.class, OverdueOutput.class,
            ProcessContext.class, pb -> pb
                .description("Marks an unpaid invoice past its due date overdue, and reminds the sponsor weekly.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory(FeeProcesses::context)
                .outputMapper(ctx -> {
                    EntityInstance invoice = first(ctx, INVOICE);
                    return new OverdueOutput(ctx.get(INPUT, OverdueInput.class).invoiceId(),
                        ctx.contains(REMINDER) ? QbFees.OVERDUE : invoice == null ? null : invoice.get("status"),
                        ctx.contains(REMINDER) ? (Integer) ctx.get(REMINDER) : 0);
                })
                .step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbFees.lockName(ctx.get(INPUT, OverdueInput.class).sponsorId())))
                .step("Read the invoice and the sponsor's operator", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE), ctx -> byIds("invoiceId",
                        ctx.get(INPUT, OverdueInput.class).invoiceId()), INVOICE),
                    QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> byIds("userId",
                        ctx.get(INPUT, OverdueInput.class).sponsorId()), ASSIGNMENT)))
                .compute("Mark or remind", (metadata, ctx) -> markOverdue(ctx))
                .step("Remind the sponsor", PublishEvent.<ProcessContext>when(ctx -> ctx.contains(REMINDER),
                    OVERDUE_EVENT, ctx -> Map.of("invoiceId", String.valueOf(first(ctx, INVOICE).id()),
                        "sponsorId", ctx.get(INPUT, OverdueInput.class).sponsorId(),
                        "reminder", ctx.get(REMINDER))))
                .step("Tell finance", CreateTask.<ProcessContext>when(
                    ctx -> Integer.valueOf(1).equals(ctx.get(REMINDER)), ctx -> overdueTask(ctx, false)))
                .step("Tell the sponsor's operator", CreateTask.<ProcessContext>when(
                    ctx -> Integer.valueOf(1).equals(ctx.get(REMINDER)) && first(ctx, ASSIGNMENT) != null,
                    ctx -> overdueTask(ctx, true))));
    }

    static void markOverdue(ProcessContext ctx) {
        EntityInstance invoice = first(ctx, INVOICE);
        if (invoice == null || !ctx.get(INPUT, OverdueInput.class).sponsorId().equals(invoice.get("sponsorId"))) {
            return;
        }
        String status = invoice.get("status");
        int reminders = money(invoice.get("reminders")).intValue();
        Object dueOn = invoice.get("dueOn");
        Map<String, Object> values = new LinkedHashMap<>();
        if (QbFees.ISSUED.equals(status) && dueOn != null
            && LocalDate.parse(dueOn.toString()).isBefore(day(ctx.opTime()))) {
            values.put("status", QbFees.OVERDUE);
        } else if (!(QbFees.OVERDUE.equals(status) && reminders < QbFees.MAX_REMINDERS
            && reminded(invoice).plus(Duration.ofDays(QbFees.REMINDER_DAYS)).compareTo(ctx.opTime()) <= 0)) {
            return;
        }
        values.put("reminders", BigDecimal.valueOf(reminders + 1L));
        values.put("remindedAt", ctx.opTime());
        ctx.changes().update(QbFees.INVOICE, invoice.id(), invoice.version(), values);
        ctx.put(REMINDER, reminders + 1);
    }

    private static Instant reminded(EntityInstance invoice) {
        Object at = invoice.get("remindedAt");
        return at == null ? Instant.EPOCH : at instanceof Instant instant ? instant
            : at instanceof java.time.OffsetDateTime odt ? odt.toInstant() : Instant.parse(at.toString());
    }

    static TaskSpec overdueTask(ProcessContext ctx, boolean operator) {
        EntityInstance invoice = first(ctx, INVOICE);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("invoiceNo", invoice.get("invoiceNo"));
        params.put("due", money(invoice.get("due")).toPlainString());
        String id = String.valueOf(invoice.id());
        if (operator) {
            EntityInstance assignment = first(ctx, ASSIGNMENT);
            return TaskSpec.forPermission(TASK_TYPE, "task.qb.fee-overdue", params, QbPermissions.OPERATOR_USE)
                .source("fee-overdue-operator:" + id).about(QbFees.INVOICE, id)
                .scope(QbOperators.SCOPE, String.valueOf((Object) assignment.get("operatorId")));
        }
        return TaskSpec.forPermission(TASK_TYPE, "task.qb.fee-overdue", params, QbPermissions.FEE_RECORD)
            .source("fee-overdue:" + id).about(QbFees.INVOICE, id);
    }

    // ---- requests: a charge by hand, a voiding ----------------------------------------------------------------------

    public static ProcessDefinition<RequestInput, RequestOutput, ProcessContext> request() {
        return ProcessDefinition.define(REQUEST, 1, RequestInput.class, RequestOutput.class, ProcessContext.class,
            pb -> pb
                .description("Asks for a platform fee charged by hand, or an invoice voided, for another "
                    + "administrator's approval; nothing changes before it.")
                .permissions(QbPermissions.FEE_RECORD)
                .requiresVerifiedEmail()
                .contextFactory(FeeProcesses::context)
                .outputMapper(FeeProcesses::requestOutput)
                .step("Read the sponsor, the invoice and the rule", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR),
                        ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("userId", requestInput(ctx).sponsorId()),
                            new QueryPredicate.Eq("reviewStatus", SponsorStatus.APPROVED)))).limit(1).build(),
                        SPONSOR),
                    QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE),
                        ctx -> byIds("invoiceId", requestInput(ctx).invoiceId()), INVOICE),
                    QueryEntities.query(QbFees.defaultDataset(QbFees.REQUEST), ctx -> EntityQuery.builder().where(
                        new QueryPredicate.And(List.of(new QueryPredicate.In("invoiceId", ids(requestInput(ctx)
                            .invoiceId())), new QueryPredicate.Eq("status", QbFees.REVIEW)))).limit(1).build(),
                        OPEN_VOID),
                    QueryEntities.query(ApprovalEntities.RULE_DATASET, ctx -> EntityQuery.builder().where(
                        new QueryPredicate.And(List.of(new QueryPredicate.Eq("subject", APPROVAL_SUBJECT),
                            new QueryPredicate.Eq("enabled", true)))).limit(1).build(), RULES)))
                .compute("Check the request", (metadata, ctx) -> checkRequest(ctx))
                .compute("Record the request", (metadata, ctx) -> insertRequest(ctx))
                // Written first, so that the approval names it and binds what is stored (read back, as its result
                // reads it).
                .step("Save the request", SaveChanges.now())
                .step("Read the request", QueryEntities.<ProcessContext>of(QbFees.defaultDataset(QbFees.REQUEST),
                    ctx -> byIds("requestId", (String) ctx.get(NEW_ID)), FEE_REQUEST))
                .step("Ask for another administrator's approval", RequireApproval.<ProcessContext>of(
                    APPROVAL_SUBJECT, ctx -> approvalCase(first(ctx, FEE_REQUEST)), APPROVAL))
                .compute("Wait for the approval", (metadata, ctx) -> waitForApproval(ctx)));
    }

    static void checkRequest(ProcessContext ctx) {
        RequestInput input = requestInput(ctx);
        List<Violation> problems = new ArrayList<>();
        if (first(ctx, SPONSOR) == null) {
            problems.add(new Violation("sponsorId", FeeCodes.AMOUNT, "Only an approved sponsor is charged",
                Map.of()));
        }
        if (QbFees.REQUEST_CHARGE.equals(input.kind())) {
            if (input.amount() == null || input.invoiceId() != null) {
                problems.add(new Violation("amount", FeeCodes.AMOUNT, "A charge by hand has an amount and no invoice",
                    Map.of()));
            }
        } else {
            EntityInstance invoice = first(ctx, INVOICE);
            if (invoice == null || !input.sponsorId().equals(invoice.get("sponsorId"))
                || QbFees.VOID.equals(invoice.get("status")) || first(ctx, OPEN_VOID) != null
                || input.amount() != null) {
                problems.add(new Violation("invoiceId", FeeCodes.INVOICE_STATE, "Only an invoice of the sponsor's "
                    + "that is not void, without another voiding asked, is voided", Map.of()));
            }
        }
        if (first(ctx, RULES) == null) {
            problems.add(noReview());
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
    }

    private static Violation noReview() {
        return new Violation(null, FeeCodes.AMOUNT, "No approval rule asks for a second person on platform fees",
            Map.of());
    }

    static void insertRequest(ProcessContext ctx) {
        RequestInput input = requestInput(ctx);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", input.kind());
        values.put("sponsorId", input.sponsorId());
        values.put("invoiceId", input.invoiceId());
        values.put("amount", input.amount());
        values.put("reason", input.reason().strip());
        values.put("status", QbFees.REVIEW);
        values.put("requestedBy", ctx.request().actorId());
        values.put("requestedAt", ctx.opTime());
        ctx.put(NEW_ID, String.valueOf(ctx.changes().insert(QbFees.REQUEST, values)));
    }

    static void waitForApproval(ProcessContext ctx) {
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        if (approval == null || approval.status() != ApprovalOutcome.Status.PENDING) {
            // The rule's condition no longer applies: never by one person. Nothing is kept.
            throw new BusinessRuleViolationException(List.of(noReview()));
        }
        EntityInstance request = first(ctx, FEE_REQUEST);
        ctx.changes().update(QbFees.REQUEST, request.id(), request.version(),
            Map.of("approvalRequestId", approval.requestId()));
    }

    /** The case: the rule's facts and the content approved, from the stored request; the requester prepared it. */
    static ApprovalCase approvalCase(EntityInstance request) {
        Object amount = request.get("amount");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("fee", true);
        facts.put("kind", request.get("kind"));
        facts.put("amount", amount == null ? BigDecimal.ZERO : money(amount).setScale(0));
        facts.put("sponsorId", request.get("sponsorId"));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("requestId", String.valueOf(request.id()));
        content.put("kind", request.get("kind"));
        content.put("sponsorId", request.get("sponsorId"));
        content.put("invoiceId", request.get("invoiceId"));
        content.put("amount", amount == null ? null : money(amount).setScale(0));
        content.put("reason", request.get("reason"));
        return ApprovalCase.of(String.valueOf(request.id()), facts, content).preparedBy(request.get("requestedBy"))
            .reference(String.valueOf(request.id()).substring(0, 8));
    }

    /**
     * Continues after the approval is decided (subscribed to both approval events, run as the system). Only the
     * request's own approval counts, for what is stored: an approval charges or voids, a rejection closes it.
     */
    public static ProcessDefinition<TransferProcesses.ApprovalResultInput, RequestOutput, ProcessContext>
        requestResult() {
        return ProcessDefinition.define(REQUEST_RESULT, 1, TransferProcesses.ApprovalResultInput.class,
            RequestOutput.class, ProcessContext.class, pb -> pb
                .description("Charges or voids once another administrator approved the request, or closes it when "
                    + "they rejected it.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory(FeeProcesses::context)
                .outputMapper(FeeProcesses::requestOutput)
                .step("Find the request", QueryEntities.<ProcessContext>of(QbFees.defaultDataset(QbFees.REQUEST),
                    ctx -> byIds("requestId", ours(ctx) ? approvalInput(ctx).entityId() : null), EXISTING))
                .step("Lock the sponsor's wallets", HoldLock.<ProcessContext>exclusive(ctx -> {
                    EntityInstance found = first(ctx, EXISTING);
                    return found == null ? null : QbFees.lockName(found.get("sponsorId"));
                }))
                // Again under the lock: a second delivery of the event finds it decided.
                .step("Read the request, its invoice and the cash account", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFees.defaultDataset(QbFees.REQUEST), ctx -> byIds("requestId",
                        first(ctx, EXISTING) == null ? null : String.valueOf(first(ctx, EXISTING).id())), FEE_REQUEST),
                    QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE), ctx -> byIds("invoiceId",
                        first(ctx, EXISTING) == null ? null : first(ctx, EXISTING).get("invoiceId")), INVOICE),
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET), ctx -> QbWallets.query(
                        QbWallets.CASH, first(ctx, EXISTING) == null ? null : first(ctx, EXISTING).get("sponsorId")),
                        CASH_WALLET),
                    QueryEntities.query(ApprovalEntities.REQUEST_DATASET, ctx -> byIds("requestId",
                        ownRequest(ctx) ? approvalInput(ctx).requestId() : null), APPROVAL_REQUEST)))
                .step("Check the approval", RequireApproval.<ProcessContext>when(
                    ctx -> ownRequest(ctx) && ApprovalEntities.APPROVED.equals(approvalInput(ctx).status()),
                    APPROVAL_SUBJECT, ctx -> approvalCase(first(ctx, FEE_REQUEST))
                        .preparedBy(approvalInput(ctx).preparerId()), APPROVAL))
                .compute("Decide", (metadata, ctx) -> decided(ctx))
                .step("Charge the fee", CallProcess.<ProcessContext>when(ctx -> ctx.contains(APPLY_CHARGE), CHARGE, 1,
                    ctx -> {
                        EntityInstance request = first(ctx, FEE_REQUEST);
                        return new ChargeInput(request.get("sponsorId"), QbFees.MANUAL, money(request.get("amount")),
                            null, request.get("reason"), QbFees.REQUEST, String.valueOf(request.id()), false);
                    }, CHARGED))
                .step("Find the invoice's postings", RunTemplate.<ProcessContext>of(VOID_TEMPLATE,
                    ctx -> Map.of("invoiceId", ctx.contains(APPLY_VOID)
                        ? String.valueOf(first(ctx, INVOICE).id()) : NO_ID), VOID_ROWS))
                .step("Reverse them", CallProcess.forEach(LedgerProcesses.REVERSE, 1, FeeProcesses::reversals,
                    REVERSED))
                .compute("Void the invoice", (metadata, ctx) -> voidInvoice(ctx)));
    }

    private static boolean ours(ProcessContext ctx) {
        return APPROVAL_SUBJECT.equals(approvalInput(ctx).subject());
    }

    private static boolean ownRequest(ProcessContext ctx) {
        EntityInstance request = first(ctx, FEE_REQUEST);
        TransferProcesses.ApprovalResultInput input = approvalInput(ctx);
        return request != null && QbFees.REVIEW.equals(request.get("status")) && input.requestId() != null
            && input.requestId().equals(request.get("approvalRequestId"));
    }

    static void decided(ProcessContext ctx) {
        if (!ownRequest(ctx)) {
            return;
        }
        EntityInstance request = first(ctx, FEE_REQUEST);
        TransferProcesses.ApprovalResultInput input = approvalInput(ctx);
        String status;
        if (ApprovalEntities.APPROVED.equals(input.status())) {
            ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
            if (approval.status() != ApprovalOutcome.Status.APPROVED
                || !input.requestId().equals(approval.requestId())) {
                return;
            }
            status = QbFees.APPROVED;
            if (QbFees.REQUEST_CHARGE.equals(request.get("kind"))) {
                ctx.put(APPLY_CHARGE, true);
            } else {
                EntityInstance invoice = first(ctx, INVOICE);
                if (invoice == null || QbFees.VOID.equals(invoice.get("status"))) {
                    // Voided since: nothing more to do, the request is closed as approved.
                    status = QbFees.APPROVED;
                } else {
                    ctx.put(APPLY_VOID, true);
                }
            }
        } else if (ApprovalEntities.REJECTED.equals(input.status()) && rejected(ctx, request)) {
            status = QbFees.REJECTED;
        } else {
            return;
        }
        ctx.put(STATUS_AFTER, status);
        ctx.changes().update(QbFees.REQUEST, request.id(), request.version(), Map.of("status", status,
            "decidedAt", ctx.opTime()));
    }

    /** Whether the stored approval request (not the event) says that the request, as it is, was rejected. */
    private static boolean rejected(ProcessContext ctx, EntityInstance request) {
        EntityInstance approval = first(ctx, APPROVAL_REQUEST);
        if (approval == null) {
            return false;
        }
        String hash = ContentHash.of(approvalCase(request).content());
        return ApprovalEntities.REJECTED.equals(approval.get("status"))
            && APPROVAL_SUBJECT.equals(approval.get("subject"))
            && String.valueOf(request.id()).equals(approval.get("entityId"))
            && hash.equals(String.valueOf((Object) approval.get("contentHash")).strip());
    }

    static List<LedgerProcesses.ReverseInput> reversals(ProcessContext ctx) {
        if (!ctx.contains(APPLY_VOID)) {
            return List.of();
        }
        String reason = "Invoice " + first(ctx, INVOICE).get("invoiceNo") + " voided";
        return rows(ctx, VOID_ROWS).stream().map(row -> new LedgerProcesses.ReverseInput(
            String.valueOf(row.get("transactionId")), reason, ctx.opTime())).toList();
    }

    static void voidInvoice(ProcessContext ctx) {
        if (!ctx.contains(APPLY_VOID)) {
            return;
        }
        EntityInstance invoice = first(ctx, INVOICE);
        ctx.changes().update(QbFees.INVOICE, invoice.id(), invoice.version(), Map.of("status", QbFees.VOID,
            "voidedAt", ctx.opTime()));
        // What the postings took from the cash account comes back.
        BigDecimal back = rows(ctx, VOID_ROWS).stream().map(row -> money(row.get("cash")))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        ctx.put(CASH_DELTA, back);
        changeCash(ctx, invoice.get("sponsorId"));
    }

    private static RequestOutput requestOutput(ProcessContext ctx) {
        EntityInstance request = first(ctx, FEE_REQUEST);
        String status = ctx.contains(STATUS_AFTER) ? (String) ctx.get(STATUS_AFTER)
            : request == null ? null : request.get("status");
        ApprovalOutcome approval = ctx.contains(APPROVAL) ? ctx.get(APPROVAL, ApprovalOutcome.class) : null;
        String approvalId = approval != null && approval.status() == ApprovalOutcome.Status.PENDING
            ? approval.requestId() : request == null ? null : request.get("approvalRequestId");
        return new RequestOutput(request == null ? null : String.valueOf(request.id()),
            status == null && approval != null ? QbFees.REVIEW : status, approvalId);
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    private static ProcessContext context(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private static ChargeInput chargeInput(ProcessContext ctx) {
        return ctx.get(INPUT, ChargeInput.class);
    }

    private static InvoiceItemInput invoiceInput(ProcessContext ctx) {
        return ctx.get(INPUT, InvoiceItemInput.class);
    }

    private static RequestInput requestInput(ProcessContext ctx) {
        return ctx.get(INPUT, RequestInput.class);
    }

    private static TransferProcesses.ApprovalResultInput approvalInput(ProcessContext ctx) {
        return ctx.get(INPUT, TransferProcesses.ApprovalResultInput.class);
    }

    /** The rows whose {@code field} is {@code id}; none for a null id (the query steps have no condition). */
    static EntityQuery byIds(String field, Object id) {
        return EntityQuery.builder().where(new QueryPredicate.In(field, ids(id))).limit(1).build();
    }

    private static List<Object> ids(Object id) {
        List<Object> ids = new ArrayList<>();
        if (id != null) {
            ids.add(String.valueOf(id));
        }
        return ids;
    }

    static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : value instanceof BigDecimal decimal ? decimal
            : new BigDecimal(value.toString());
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(key);
        return rows == null ? List.of() : rows;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> rows = list(ctx, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(ProcessContext ctx, String key) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(key);
        return rows == null ? List.of() : rows;
    }

    private FeeProcesses() {}
}

package com.jabiz.quizbuks.fee;

import com.jabiz.entity.Violation;
import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.identity.SponsorStatus;
import com.jabiz.quizbuks.messaging.ConsentChecks;
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.document.DocumentProcesses;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.numbering.AssignNumber;
import com.jabiz.runtime.param.ParamEntities;
import com.jabiz.param.ParamValues;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.runtime.task.CloseTasks;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The platform's fees (docs/quizbuks/plans/Q87-fee-account.md, D-Q87-4 – D-Q87-7).
 *
 * <ul>
 *   <li>{@value #CHARGE} ({@code subProcessOnly}, under the sponsor's wallet lock the caller holds): charges one fee:
 *       the charge written once, posted as receivable (debit 1130 for the sponsor / credit 4120 and 2150 tax); a
 *       prepaid sponsor's charge gets its invoice at once, paid from the cash account when it covers it (debit 2160 /
 *       credit 1130), open otherwise.</li>
 *   <li>{@value #MONTHLY_ITEM} ({@code internal()}, the item of the monthly job {@value #MONTHLY_JOB}): the monthly
 *       fee of one sponsor for one month, once, when {@code qb.fee.monthly} is above 0.</li>
 *   <li>{@value #INVOICE_ITEM} ({@code internal()}, the item of {@value #INVOICE_JOB}): one postpaid sponsor's
 *       invoice of a month, all its postpaid charges, once; announces it ({@code qb.fee.invoice-issued}).</li>
 *   <li>{@value #PAY} (the sponsor): pays an open or overdue invoice from the cash account.</li>
 *   <li>{@value #PAY_EARLY} (the sponsor): pays the postpaid fees not invoiced yet from the cash account, each
 *       getting an invoice of its own, paid ({@value #SETTLE_ITEM}, one a charge); the month's invoice then covers
 *       what remains.</li>
 *   <li>{@value #DOCUMENT} (the sponsor): the invoice's PDF ({@value #LAYOUT_ID}), issued the first time it is asked
 *       for (a job holds no permission to issue a document), given again after.</li>
 *   <li>{@value #OVERDUE_ITEM} ({@code internal()}, the item of the daily job {@value #OVERDUE_JOB}): marks an
 *       invoice past its due day overdue, asks the mail to remind the sponsor (a week apart, three times at most) and
 *       opens a task for finance and the operator the first time.</li>
 *   <li>{@value #BILLING_SET} (the operator for its own sponsors, headquarters for any): a sponsor's billing mode and
 *       credit limit, with a reason.</li>
 * </ul>
 */
public final class FeeProcesses {

    public static final String CHARGE = "QB_FEE_CHARGE";
    public static final String MONTHLY_ITEM = "QB_FEE_MONTHLY_ITEM";
    public static final String INVOICE_ITEM = "QB_FEE_INVOICE_ISSUE_ITEM";
    public static final String PAY = "QB_FEE_INVOICE_PAY";
    public static final String PAY_EARLY = "QB_FEE_PAY_EARLY";
    public static final String SETTLE_ITEM = "QB_FEE_SETTLE_ITEM";
    public static final String DOCUMENT = "QB_FEE_INVOICE_DOCUMENT";
    public static final String OVERDUE_ITEM = "QB_FEE_OVERDUE_ITEM";
    public static final String BILLING_SET = "QB_OPERATOR_BILLING_SET";
    public static final String MONTHLY_JOB = "qb.fee-monthly";
    public static final String INVOICE_JOB = "qb.fee-invoice";
    public static final String OVERDUE_JOB = "qb.fee-overdue";
    public static final String MONTHLY_DUE_TEMPLATE = "qb.system.fee-monthly-due";
    public static final String INVOICES_DUE_TEMPLATE = "qb.system.fee-invoices-due";
    public static final String OVERDUE_DUE_TEMPLATE = "qb.system.fee-overdue-due";
    public static final String DOCUMENT_TEMPLATE = "qb.sponsor.fee-invoice-doc";
    public static final String LINES_TEMPLATE = "qb.sponsor.fee-invoice-lines";
    public static final String UNINVOICED_TEMPLATE = "qb.sponsor.fee-uninvoiced";
    public static final String ISSUER_TEMPLATE = "qb.sponsor.receipt-issuer";
    public static final String LAYOUT_ID = "qb.fee_invoice";

    /** The most one fee may be, in yen. */
    public static final String MAX_AMOUNT = "1000000000000";

    /**
     * @param sponsorId the sponsor (their user id) charged
     * @param kind      {@code MONTHLY} or {@code MANUAL}
     * @param period    the month ({@code yyyy-MM}) the fee belongs to
     * @param amount    the fee before tax, in yen
     * @param reason    why (a manual fee); a monthly fee has none
     * @param sourceKey what the fee stands for (the monthly fee's month, a manual fee's request)
     * @param chargedBy who asked for it; null for a job
     */
    public record ChargeInput(@NotBlank @Size(max = 128) String sponsorId,
        @NotBlank @Pattern(regexp = "MONTHLY|MANUAL") String kind,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String period,
        @NotNull @DecimalMin("1") @DecimalMax(MAX_AMOUNT) @Digits(integer = 13, fraction = 0) BigDecimal amount,
        @Size(max = QbFees.REASON_LENGTH) String reason, @Size(max = QbFees.SOURCE_LENGTH) String sourceKey,
        @Size(max = 128) String chargedBy) {}

    /** @param invoiceId the prepaid charge's invoice, if one was made; {@code paid} whether it is paid already */
    public record ChargeOutput(String chargeId, BigDecimal total, String billingMode, String invoiceId,
        boolean paid) {}

    public record MonthlyInput(@NotBlank @Size(max = 128) String sponsorId,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String period) {}

    public record InvoiceInput(@NotBlank @Size(max = 128) String sponsorId,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String period) {}

    public record InvoiceRef(@NotBlank @Size(max = 36) String invoiceId) {}

    /** @param language the document's language ({@code en}, {@code zh}, {@code ja}); none: the caller's */
    public record DocumentInput(@NotBlank @Size(max = 36) String invoiceId, @Size(max = 10) String language) {}

    /** @param period only this month's fees ({@code yyyy-MM}); none: every month's */
    public record EarlyInput(@Pattern(regexp = "\\d{4}-\\d{2}") String period) {}

    /** @param invoiceNos the invoices made, one a fee, all paid; {@code cashBalance} the cash after */
    public record EarlyOutput(int count, BigDecimal total, BigDecimal cashBalance, List<String> invoiceNos) {}

    /** One postpaid fee settled early (the item of {@value #PAY_EARLY}): what the caller read under its lock. */
    public record SettleInput(@NotBlank @Size(max = 128) String sponsorId, @NotBlank @Size(max = 36) String chargeId,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String period,
        @NotNull @DecimalMin("0") @DecimalMax(MAX_AMOUNT) @Digits(integer = 13, fraction = 0) BigDecimal amount,
        @NotNull @DecimalMin("0") @DecimalMax(MAX_AMOUNT) @Digits(integer = 13, fraction = 0) BigDecimal tax,
        @NotNull @DecimalMin("1") @DecimalMax(MAX_AMOUNT) @Digits(integer = 13, fraction = 0) BigDecimal total,
        @Size(max = 36) String operatorId, @NotBlank @Size(max = 100) String reference) {}

    public record SettleOutput(String invoiceId, String invoiceNo) {}

    /** @param status the invoice's status after; {@code cashBalance} the cash after a payment */
    public record InvoiceOutput(String invoiceId, String invoiceNo, String status, BigDecimal total,
        BigDecimal cashBalance, String documentRunId, String documentNo) {}

    /** @param outcome what the item did: {@code CHARGED}, {@code ISSUED}, {@code REMINDED} or {@code NONE} */
    public record ItemOutput(String sponsorId, String outcome, String id) {}

    /**
     * @param userId      the sponsor (their user id)
     * @param billingMode {@code PREPAID} or {@code POSTPAID}
     * @param creditLimit postpaid: what may be owed before the sponsor is stopped, in yen; prepaid: 0
     */
    public record BillingInput(@NotBlank @Size(max = 128) String userId,
        @NotBlank @Pattern(regexp = "PREPAID|POSTPAID") String billingMode,
        @NotNull @DecimalMin("0") @DecimalMax(MAX_AMOUNT) @Digits(integer = 13, fraction = 0) BigDecimal creditLimit,
        @NotBlank @Size(max = QbFees.REASON_LENGTH) String reason) {}

    public record BillingOutput(String userId, String billingMode, BigDecimal creditLimit, boolean changed) {}

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String SPONSOR = "sponsor";
    static final String ASSIGNMENT = "assignment";
    static final String CASH_WALLET = "cashWallet";
    static final String EXISTING = "existing";
    static final String CHARGES = "charges";
    static final String INVOICES = "invoices";
    static final String EARLY = "early";
    static final String SETTLED = "settled";
    /** How many charges of a month are read for its invoice: more than any sponsor has; as many is a failure. */
    static final int MAX_CHARGES = 500;
    /** How many fees one early payment settles at most: more than wait for an invoice (one a month, by hand a few). */
    static final int MAX_EARLY = 100;
    static final String CHARGE_ID = "chargeId";
    static final String CHARGE_POSTING = "chargePosting";
    static final String PAY_INPUT = "payInput";
    static final String PAY_POSTING = "payPosting";
    static final String INVOICE_NO = "invoiceNo";
    static final String INVOICE_ID = "invoiceId";
    static final String INVOICE = "invoice";
    static final String FOUND = "found";
    static final String BILLING_MODE = "billingMode";
    static final String TOTAL = "total";
    static final String PAID_NOW = "paidNow";
    static final String CASH_AFTER = "cashAfter";
    static final String OUTCOME = "outcome";
    static final String ISSUER = "issuer";
    static final String ISSUED = "issued";
    static final String REMINDER = "reminder";

    private FeeProcesses() {}

    // ---- charging ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ChargeInput, ChargeOutput, ProcessContext> charge(ZoneId zone) {
        return ProcessDefinition.define(CHARGE, 1, ChargeInput.class, ChargeOutput.class, ProcessContext.class, pb -> pb
            .description("Charges a platform fee to a sponsor: posted as receivable; a prepaid sponsor's is invoiced "
                + "at once and paid from their cash account when it covers it.")
            .permissions(QbPermissions.FEE_SYSTEM)
            .subProcessOnly()
            .contextFactory(FeeProcesses::context)
            .outputMapper(ctx -> new ChargeOutput((String) ctx.get(CHARGE_ID), ctx.get(TOTAL, BigDecimal.class),
                (String) ctx.get(BILLING_MODE), ctx.contains(INVOICE_ID) ? (String) ctx.get(INVOICE_ID) : null,
                Boolean.TRUE.equals(ctx.get(PAID_NOW))))
            .step("Load the fee parameters", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                QbParams.FEE_TAX_RATE, QbParams.FEE_DEFAULT_BILLING_MODE, QbParams.FEE_DUE_DAYS))
            .step("Load the sponsor, their operator and their cash", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> bySponsor("userId",
                    chargeInput(ctx).sponsorId()), SPONSOR),
                QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> bySponsor("userId",
                    chargeInput(ctx).sponsorId()), ASSIGNMENT),
                QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                    ctx -> QbWallets.query(QbWallets.CASH, chargeInput(ctx).sponsorId()), CASH_WALLET)))
            .compute("Check and record the charge", (metadata, ctx) -> insertCharge(ctx))
            // Written first: the posting names it as its source.
            .step("Save the charge", SaveChanges.now())
            .step("Post the fee", CallProcess.<ProcessContext>of(LedgerProcesses.POST, 1,
                ctx -> chargePosting(ctx), CHARGE_POSTING))
            // D-Q87-5: a prepaid sponsor pays as fees arise, when the cash covers it.
            .compute("Decide the payment", (metadata, ctx) -> decidePayment(ctx))
            .step("Post the payment", CallProcess.<ProcessContext>when(ctx -> ctx.contains(PAY_INPUT),
                LedgerProcesses.POST, 1, ctx -> ctx.get(PAY_INPUT), PAY_POSTING))
            .step("Number the invoice", AssignNumber.<ProcessContext>when(
                ctx -> FeeRules.PREPAID.equals(ctx.get(BILLING_MODE)), QbFees.INVOICE_SEQUENCE,
                ctx -> yearOf(chargeInput(ctx).period()), INVOICE_NO))
            .compute("Record the invoice", (metadata, ctx) -> insertPrepaidInvoice(ctx, zone))
            .step("Announce the invoice", PublishEvent.<ProcessContext>when(
                ctx -> ctx.contains(INVOICE_ID) && !Boolean.TRUE.equals(ctx.get(PAID_NOW)),
                QbFees.INVOICE_ISSUED_EVENT, FeeProcesses::invoicePayload)));
    }

    static void insertCharge(ProcessContext ctx) {
        ChargeInput input = chargeInput(ctx);
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        List<Violation> problems = new ArrayList<>();
        EntityInstance sponsor = first(ctx, SPONSOR);
        if (sponsor == null || !SponsorStatus.APPROVED.equals(sponsor.get("reviewStatus"))) {
            problems.add(new Violation("sponsorId", FeeCodes.FEE_SPONSOR, "Only an approved sponsor is charged",
                Map.of()));
        }
        if (!FeeRules.period(input.period())) {
            problems.add(new Violation("period", FeeCodes.FEE_PERIOD, "Not a month", Map.of("period", input.period())));
        }
        BigDecimal rate = params.get(QbParams.FEE_TAX_RATE, BigDecimal.class);
        BigDecimal tax = FeeRules.tax(input.amount(), rate);
        if (tax == null) {
            problems.add(new Violation(null, FeeCodes.FEE_PARAMS, "The tax rate cannot be used",
                Map.of("rate", String.valueOf(rate))));
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        String billingMode = FeeRules.billingModeOf(sponsor.get("billingMode"),
            params.get(QbParams.FEE_DEFAULT_BILLING_MODE, String.class));
        EntityInstance assignment = first(ctx, ASSIGNMENT);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sponsorId", input.sponsorId());
        values.put("operatorId", assignment == null ? null : String.valueOf((Object) assignment.get("operatorId")));
        values.put("billingMode", billingMode);
        values.put("kind", input.kind());
        values.put("period", input.period());
        values.put("amount", input.amount());
        values.put("taxRate", rate);
        values.put("tax", tax);
        values.put("total", input.amount().add(tax));
        values.put("reason", input.reason() == null ? null : input.reason().strip());
        values.put("sourceKey", input.sourceKey());
        values.put("chargedAt", ctx.opTime());
        values.put("chargedBy", input.chargedBy());
        ctx.put(CHARGE_ID, String.valueOf(ctx.changes().insert(QbFees.CHARGE, values)));
        ctx.put(BILLING_MODE, billingMode);
        ctx.put(TOTAL, input.amount().add(tax));
    }

    /** The fee as revenue owed: debit 1130 for the sponsor, credit 4120 the fee and 2150 the tax (none when 0). */
    static LedgerProcesses.PostInput chargePosting(ProcessContext ctx) {
        ChargeInput input = chargeInput(ctx);
        BigDecimal total = ctx.get(TOTAL, BigDecimal.class);
        BigDecimal tax = total.subtract(input.amount());
        List<LedgerProcesses.Line> lines = new ArrayList<>();
        lines.add(new LedgerProcesses.Line(QbLedger.FEES_RECEIVABLE, Direction.DEBIT, total, null,
            Map.of(QbLedger.PARTY, input.sponsorId())));
        lines.add(new LedgerProcesses.Line(QbLedger.PLATFORM_FEE_REVENUE, Direction.CREDIT, input.amount()));
        if (tax.signum() > 0) {
            lines.add(new LedgerProcesses.Line(QbLedger.TAX_PAYABLE, Direction.CREDIT, tax));
        }
        return new LedgerProcesses.PostInput(ctx.opTime(), "Platform fee", reference(input), lines, QbFees.CHARGE,
            (String) ctx.get(CHARGE_ID));
    }

    /** The posting's reference: {@code fee_<kind>_<period>}, so that the monthly report tells the kinds apart. */
    static String reference(ChargeInput input) {
        return "fee_" + input.kind().toLowerCase(java.util.Locale.ROOT) + "_" + input.period();
    }

    static void decidePayment(ProcessContext ctx) {
        if (!FeeRules.PREPAID.equals(ctx.get(BILLING_MODE))) {
            return;
        }
        BigDecimal total = ctx.get(TOTAL, BigDecimal.class);
        if (QbWallets.available(first(ctx, CASH_WALLET)).compareTo(total) >= 0) {
            ctx.put(PAY_INPUT, payment(ctx.opTime(), chargeInput(ctx).sponsorId(), total, QbFees.CHARGE,
                (String) ctx.get(CHARGE_ID), reference(chargeInput(ctx))));
        }
    }

    /** A fee paid from the cash account: debit 2160 / credit 1130, both the sponsor's. */
    static LedgerProcesses.PostInput payment(Instant at, String sponsorId, BigDecimal total, String sourceEntity,
        String sourceId, String reference) {
        Map<String, String> party = Map.of(QbLedger.PARTY, sponsorId);
        return new LedgerProcesses.PostInput(at, "Platform fee paid", reference, List.of(
            new LedgerProcesses.Line(QbLedger.SPONSOR_CASH, Direction.DEBIT, total, null, party),
            new LedgerProcesses.Line(QbLedger.FEES_RECEIVABLE, Direction.CREDIT, total, null, party)),
            sourceEntity, sourceId);
    }

    static void insertPrepaidInvoice(ProcessContext ctx, ZoneId zone) {
        if (!FeeRules.PREPAID.equals(ctx.get(BILLING_MODE))) {
            return;
        }
        ChargeInput input = chargeInput(ctx);
        BigDecimal total = ctx.get(TOTAL, BigDecimal.class);
        boolean paid = ctx.contains(PAY_POSTING);
        EntityInstance assignment = first(ctx, ASSIGNMENT);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("invoiceNo", ctx.get(INVOICE_NO));
        values.put("sponsorId", input.sponsorId());
        values.put("operatorId", assignment == null ? null : String.valueOf((Object) assignment.get("operatorId")));
        values.put("billingMode", FeeRules.PREPAID);
        values.put("period", input.period());
        values.put("chargeId", ctx.get(CHARGE_ID));
        values.put("amount", input.amount());
        values.put("tax", total.subtract(input.amount()));
        values.put("total", total);
        values.put("status", paid ? QbFees.PAID : QbFees.OPEN);
        values.put("issuedAt", ctx.opTime());
        values.put("dueOn", FeeRules.dueOn(ctx.opTime(),
            ctx.get(PARAMS, ParamValues.class).get(QbParams.FEE_DUE_DAYS, BigDecimal.class), zone));
        values.put("paidAt", paid ? ctx.opTime() : null);
        values.put("paymentTransactionId", paid
            ? ctx.get(PAY_POSTING, LedgerProcesses.PostOutput.class).transactionId() : null);
        values.put("remindersSent", BigDecimal.ZERO);
        ctx.put(INVOICE_ID, String.valueOf(ctx.changes().insert(QbFees.INVOICE, values)));
        ctx.put(PAID_NOW, paid);
        if (paid) {
            QbWallets.change(ctx, first(ctx, CASH_WALLET), QbWallets.CASH, input.sponsorId(), total.negate(), 0,
                BigDecimal.ZERO);
        }
    }

    static Map<String, Object> invoicePayload(ProcessContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoiceId", ctx.get(INVOICE_ID));
        payload.put("sponsorId", ctx.contains(INPUT) && ctx.get(INPUT) instanceof ChargeInput c ? c.sponsorId()
            : ctx.get(INPUT) instanceof InvoiceInput i ? i.sponsorId() : null);
        return payload;
    }

    // ---- the monthly fee --------------------------------------------------------------------------------------------

    public static ProcessDefinition<MonthlyInput, ItemOutput, ProcessContext> monthlyItem() {
        return ProcessDefinition.define(MONTHLY_ITEM, 1, MonthlyInput.class, ItemOutput.class, ProcessContext.class,
            pb -> pb
                .description("Charges one sponsor the platform's monthly fee for one month, once.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory(FeeProcesses::context)
                .outputMapper(ctx -> new ItemOutput(monthlyInput(ctx).sponsorId(), (String) ctx.get(OUTCOME),
                    ctx.contains(CHARGE_POSTING) ? ((ChargeOutput) ctx.get(CHARGE_POSTING)).chargeId() : null))
                .step("Load the monthly fee", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.FEE_MONTHLY))
                .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.SPONSOR, monthlyInput(ctx).sponsorId())))
                .step("Look for the month's fee", QueryEntities.<ProcessContext>of(QbFees.defaultDataset(QbFees.CHARGE),
                    ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("sponsorId", monthlyInput(ctx).sponsorId()),
                        new QueryPredicate.Eq("kind", FeeRules.MONTHLY),
                        new QueryPredicate.Eq("period", monthlyInput(ctx).period())))).limit(1).build(), EXISTING))
                .compute("Decide", (metadata, ctx) -> {
                    BigDecimal fee = ctx.get(PARAMS, ParamValues.class).get(QbParams.FEE_MONTHLY, BigDecimal.class);
                    ctx.put(OUTCOME, first(ctx, EXISTING) == null && fee != null && fee.signum() > 0 ? "CHARGED" : "NONE");
                })
                .step("Charge it", CallProcess.<ProcessContext>when(ctx -> "CHARGED".equals(ctx.get(OUTCOME)), CHARGE, 1,
                    ctx -> new ChargeInput(monthlyInput(ctx).sponsorId(), FeeRules.MONTHLY, monthlyInput(ctx).period(),
                        ctx.get(PARAMS, ParamValues.class).get(QbParams.FEE_MONTHLY, BigDecimal.class), null,
                        "monthly:" + monthlyInput(ctx).period(), null), CHARGE_POSTING)));
    }

    // ---- the postpaid month's invoice -------------------------------------------------------------------------------

    public static ProcessDefinition<InvoiceInput, ItemOutput, ProcessContext> invoiceItem(ZoneId zone) {
        return ProcessDefinition.define(INVOICE_ITEM, 1, InvoiceInput.class, ItemOutput.class, ProcessContext.class,
            pb -> pb
                .description("Issues one postpaid sponsor's invoice of a month: all its postpaid charges, once.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory(FeeProcesses::context)
                .outputMapper(ctx -> new ItemOutput(invoiceInput(ctx).sponsorId(), (String) ctx.get(OUTCOME),
                    ctx.contains(INVOICE_ID) ? (String) ctx.get(INVOICE_ID) : null))
                .step("Load the due days", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.FEE_DUE_DAYS))
                .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.SPONSOR, invoiceInput(ctx).sponsorId())))
                // The month's invoices: the month's own (one at most) and those of its charges paid early.
                .step("Load the month's postpaid charges and its invoices", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFees.defaultDataset(QbFees.CHARGE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("sponsorId", invoiceInput(ctx).sponsorId()),
                            new QueryPredicate.Eq("period", invoiceInput(ctx).period()),
                            new QueryPredicate.Eq("billingMode", FeeRules.POSTPAID)))).limit(MAX_CHARGES).build(), CHARGES),
                    QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("sponsorId", invoiceInput(ctx).sponsorId()),
                            new QueryPredicate.Eq("period", invoiceInput(ctx).period())))).limit(MAX_CHARGES).build(),
                        INVOICES),
                    QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT),
                        ctx -> bySponsor("userId", invoiceInput(ctx).sponsorId()), ASSIGNMENT)))
                .compute("Decide", (metadata, ctx) -> decideMonthInvoice(ctx))
                .step("Number the invoice", AssignNumber.<ProcessContext>when(ctx -> "ISSUED".equals(ctx.get(OUTCOME)),
                    QbFees.INVOICE_SEQUENCE, ctx -> yearOf(invoiceInput(ctx).period()), INVOICE_NO))
                .compute("Record the invoice", (metadata, ctx) -> insertPeriodInvoice(ctx, zone))
                .step("Announce the invoice", PublishEvent.<ProcessContext>when(ctx -> ctx.contains(INVOICE_ID),
                    QbFees.INVOICE_ISSUED_EVENT, FeeProcesses::invoicePayload)));
    }

    /** The month's invoice is issued once, for the postpaid charges not paid early (none left: nothing). */
    static void decideMonthInvoice(ProcessContext ctx) {
        if (list(ctx, CHARGES).size() >= MAX_CHARGES || list(ctx, INVOICES).size() >= MAX_CHARGES) {
            // Never invoice short of what is receivable: the item fails and is seen, instead of a lasting difference.
            throw new IllegalStateException("More than " + MAX_CHARGES + " postpaid charges or invoices in one month");
        }
        boolean issued = false;
        List<Object> own = new ArrayList<>();
        for (EntityInstance invoice : list(ctx, INVOICES)) {
            if (invoice.get("chargeId") == null) {
                issued = true;
            } else {
                own.add(String.valueOf((Object) invoice.get("chargeId")));
            }
        }
        List<EntityInstance> remaining = new ArrayList<>();
        for (EntityInstance charge : list(ctx, CHARGES)) {
            if (!own.contains(String.valueOf(charge.id()))) {
                remaining.add(charge);
            }
        }
        ctx.put(CHARGES, remaining);
        ctx.put(OUTCOME, !issued && !remaining.isEmpty() ? "ISSUED" : "NONE");
    }

    static void insertPeriodInvoice(ProcessContext ctx, ZoneId zone) {
        if (!"ISSUED".equals(ctx.get(OUTCOME))) {
            return;
        }
        InvoiceInput input = invoiceInput(ctx);
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (EntityInstance charge : list(ctx, CHARGES)) {
            amount = amount.add(money(charge.get("amount")));
            tax = tax.add(money(charge.get("tax")));
        }
        EntityInstance assignment = first(ctx, ASSIGNMENT);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("invoiceNo", ctx.get(INVOICE_NO));
        values.put("sponsorId", input.sponsorId());
        values.put("operatorId", assignment == null ? null : String.valueOf((Object) assignment.get("operatorId")));
        values.put("billingMode", FeeRules.POSTPAID);
        values.put("period", input.period());
        values.put("chargeId", null);
        values.put("amount", amount);
        values.put("tax", tax);
        values.put("total", amount.add(tax));
        values.put("status", QbFees.OPEN);
        values.put("issuedAt", ctx.opTime());
        values.put("dueOn", FeeRules.dueOn(ctx.opTime(),
            ctx.get(PARAMS, ParamValues.class).get(QbParams.FEE_DUE_DAYS, BigDecimal.class), zone));
        values.put("remindersSent", BigDecimal.ZERO);
        ctx.put(INVOICE_ID, String.valueOf(ctx.changes().insert(QbFees.INVOICE, values)));
    }

    // ---- paying -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<InvoiceRef, InvoiceOutput, ProcessContext> pay() {
        return ProcessDefinition.define(PAY, 1, InvoiceRef.class, InvoiceOutput.class, ProcessContext.class, pb -> pb
            .description("Pays a platform fee invoice of one's own from the cash account.")
            .permissions(QbPermissions.TOPUP)
            .requiresVerifiedEmail()
            .actsOn(QbFees.INVOICE, INVOICE_ID, a -> a.whenField("status", QbFees.OPEN, QbFees.OVERDUE))
            .steps(CallerChecks::refuseBanned)
            .steps(ConsentChecks.sponsor())
            .contextFactory((start, input) -> {
                ProcessContext ctx = context(start, input);
                ctx.put(INVOICE_ID, input.invoiceId());
                return ctx;
            })
            .outputMapper(FeeProcesses::invoiceOutput)
            .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(
                ctx -> QbWallets.lockName(QbWallets.SPONSOR, ctx.request().actorId())))
            // Through the sponsor's own dataset: another sponsor's invoice is not found (404).
            .step("Load the invoice", LoadEntity.by(QbFees.SPONSOR_INVOICES, INVOICE_ID, INVOICE))
            .step("Load the cash", QueryEntities.<ProcessContext>of(QbWallets.defaultDataset(QbWallets.WALLET),
                ctx -> QbWallets.query(QbWallets.CASH, ctx.request().actorId()), CASH_WALLET))
            .compute("Check the payment", (metadata, ctx) -> checkPayment(ctx))
            .step("Post the payment", CallProcess.<ProcessContext>of(LedgerProcesses.POST, 1,
                ctx -> payment(ctx.opTime(), ctx.request().actorId(), money(invoice(ctx).get("total")), QbFees.INVOICE,
                    String.valueOf(invoice(ctx).id()), invoice(ctx).get("invoiceNo")), PAY_POSTING))
            .compute("Record the payment", (metadata, ctx) -> recordPayment(ctx))
            .step("Close the reminder", CloseTasks.<ProcessContext>done(
                ctx -> QbFees.overdueTaskSource(invoice(ctx).id()))));
    }

    static void checkPayment(ProcessContext ctx) {
        EntityInstance invoice = invoice(ctx);
        List<Violation> problems = new ArrayList<>();
        if (!QbFees.UNPAID.contains(String.valueOf((Object) invoice.get("status")))) {
            // Nothing to pay: the cash is not the question.
            throw new BusinessRuleViolationException(List.of(new Violation(null, FeeCodes.FEE_INVOICE_STATE,
                "The invoice is not open", Map.of("status", String.valueOf((Object) invoice.get("status"))))));
        }
        BigDecimal available = QbWallets.available(first(ctx, CASH_WALLET));
        BigDecimal total = money(invoice.get("total"));
        if (available.compareTo(total) < 0) {
            problems.add(new Violation(null, FeeCodes.FEE_CASH_SHORT, "The cash account has " + available
                + " available; the invoice is " + total, Map.of("available", available.toPlainString(),
                "total", total.toPlainString())));
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
    }

    static void recordPayment(ProcessContext ctx) {
        EntityInstance invoice = invoice(ctx);
        BigDecimal total = money(invoice.get("total"));
        EntityInstance cash = first(ctx, CASH_WALLET);
        QbWallets.change(ctx, cash, QbWallets.CASH, ctx.request().actorId(), total.negate(), 0, BigDecimal.ZERO);
        ctx.put(CASH_AFTER, QbWallets.balance(cash).subtract(total));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", QbFees.PAID);
        values.put("paidAt", ctx.opTime());
        values.put("paymentTransactionId", ctx.get(PAY_POSTING, LedgerProcesses.PostOutput.class).transactionId());
        ctx.changes().update(QbFees.INVOICE, invoice.id(), invoice.version(), values);
        ctx.put(OUTCOME, QbFees.PAID);
    }

    // ---- paying early -----------------------------------------------------------------------------------------------

    public static ProcessDefinition<EarlyInput, EarlyOutput, ProcessContext> payEarly() {
        return ProcessDefinition.define(PAY_EARLY, 1, EarlyInput.class, EarlyOutput.class, ProcessContext.class, pb -> pb
            .description("Pays one's own postpaid platform fees not invoiced yet from the cash account: each gets an "
                + "invoice of its own, paid; the month's invoice then covers what remains.")
            .permissions(QbPermissions.TOPUP)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .steps(ConsentChecks.sponsor())
            .contextFactory(FeeProcesses::context)
            .outputMapper(ctx -> new EarlyOutput(settled(ctx).size(), ctx.get(TOTAL, BigDecimal.class),
                ctx.get(CASH_AFTER, BigDecimal.class), settled(ctx).stream().map(SettleOutput::invoiceNo).toList()))
            .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(
                ctx -> QbWallets.lockName(QbWallets.SPONSOR, ctx.request().actorId())))
            // Through the sponsor's own datasets: the fees of the caller only.
            .step("Read the fees not invoiced yet", RunTemplate.<ProcessContext>of(UNINVOICED_TEMPLATE, ctx -> {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("period", ctx.get(INPUT, EarlyInput.class).period());
                return params;
            }, EARLY))
            .step("Load the cash and the operator", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                    ctx -> QbWallets.query(QbWallets.CASH, ctx.request().actorId()), CASH_WALLET),
                QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT),
                    ctx -> bySponsor("userId", ctx.request().actorId()), ASSIGNMENT)))
            .compute("Check the payment", (metadata, ctx) -> checkEarlyPayment(ctx))
            .step("Settle each fee", CallProcess.forEach(SETTLE_ITEM, 1, FeeProcesses::settleInputs, SETTLED))
            .compute("Take the cash", (metadata, ctx) -> {
                BigDecimal total = ctx.get(TOTAL, BigDecimal.class);
                EntityInstance cash = first(ctx, CASH_WALLET);
                QbWallets.change(ctx, cash, QbWallets.CASH, ctx.request().actorId(), total.negate(), 0, BigDecimal.ZERO);
                ctx.put(CASH_AFTER, QbWallets.balance(cash).subtract(total));
            }));
    }

    static void checkEarlyPayment(ProcessContext ctx) {
        List<Map<String, Object>> rows = rows(ctx, EARLY);
        if (rows.isEmpty()) {
            throw new BusinessRuleViolationException(new Violation(null, FeeCodes.FEE_NOTHING_DUE,
                "No platform fee is waiting for its invoice", Map.of()));
        }
        if (rows.size() > MAX_EARLY) {
            // The oldest first; the page offers the rest after.
            rows = rows.subList(0, MAX_EARLY);
            ctx.put(EARLY, rows);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            total = total.add(money(row.get("total")));
        }
        BigDecimal available = QbWallets.available(first(ctx, CASH_WALLET));
        if (available.compareTo(total) < 0) {
            throw new BusinessRuleViolationException(new Violation(null, FeeCodes.FEE_CASH_SHORT, "The cash account has "
                + available + " available; the fees are " + total, Map.of("available", available.toPlainString(),
                "total", total.toPlainString())));
        }
        ctx.put(TOTAL, total);
    }

    static List<SettleInput> settleInputs(ProcessContext ctx) {
        EntityInstance assignment = first(ctx, ASSIGNMENT);
        String operatorId = assignment == null ? null : String.valueOf((Object) assignment.get("operatorId"));
        List<SettleInput> inputs = new ArrayList<>();
        for (Map<String, Object> row : rows(ctx, EARLY)) {
            inputs.add(new SettleInput(ctx.request().actorId(), String.valueOf(row.get("chargeId")),
                String.valueOf(row.get("period")), money(row.get("amount")), money(row.get("tax")),
                money(row.get("total")), operatorId,
                "fee_" + String.valueOf(row.get("kind")).toLowerCase(java.util.Locale.ROOT) + "_" + row.get("period")));
        }
        return inputs;
    }

    @SuppressWarnings("unchecked")
    static List<SettleOutput> settled(ProcessContext ctx) {
        List<Object> outputs = (List<Object>) ctx.get(SETTLED);
        return outputs == null ? List.of() : outputs.stream().map(SettleOutput.class::cast).toList();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(ProcessContext ctx, String key) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(key);
        return rows == null ? List.of() : rows;
    }

    /** One fee paid early: its payment posted from the charge (as a prepaid one's), its invoice numbered and paid. */
    public static ProcessDefinition<SettleInput, SettleOutput, ProcessContext> settleItem(ZoneId zone) {
        return ProcessDefinition.define(SETTLE_ITEM, 1, SettleInput.class, SettleOutput.class, ProcessContext.class,
            pb -> pb
                .description("Settles one postpaid platform fee early: paid from the cash account, invoiced on its own.")
                .permissions(QbPermissions.TOPUP)
                .subProcessOnly()
                .contextFactory(FeeProcesses::context)
                .outputMapper(ctx -> new SettleOutput((String) ctx.get(INVOICE_ID), (String) ctx.get(INVOICE_NO)))
                .step("Load the due days", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.FEE_DUE_DAYS))
                .step("Post the payment", CallProcess.<ProcessContext>of(LedgerProcesses.POST, 1,
                    ctx -> payment(ctx.opTime(), settleInput(ctx).sponsorId(), settleInput(ctx).total(), QbFees.CHARGE,
                        settleInput(ctx).chargeId(), settleInput(ctx).reference()), PAY_POSTING))
                .step("Number the invoice", AssignNumber.<ProcessContext>of(QbFees.INVOICE_SEQUENCE,
                    ctx -> yearOf(settleInput(ctx).period()), INVOICE_NO))
                .compute("Record the invoice", (metadata, ctx) -> insertSettledInvoice(ctx, zone)));
    }

    static void insertSettledInvoice(ProcessContext ctx, ZoneId zone) {
        SettleInput input = settleInput(ctx);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("invoiceNo", ctx.get(INVOICE_NO));
        values.put("sponsorId", input.sponsorId());
        values.put("operatorId", input.operatorId());
        values.put("billingMode", FeeRules.POSTPAID);
        values.put("period", input.period());
        values.put("chargeId", input.chargeId());
        values.put("amount", input.amount());
        values.put("tax", input.tax());
        values.put("total", input.total());
        values.put("status", QbFees.PAID);
        values.put("issuedAt", ctx.opTime());
        values.put("dueOn", FeeRules.dueOn(ctx.opTime(),
            ctx.get(PARAMS, ParamValues.class).get(QbParams.FEE_DUE_DAYS, BigDecimal.class), zone));
        values.put("paidAt", ctx.opTime());
        values.put("paymentTransactionId", ctx.get(PAY_POSTING, LedgerProcesses.PostOutput.class).transactionId());
        values.put("remindersSent", BigDecimal.ZERO);
        ctx.put(INVOICE_ID, String.valueOf(ctx.changes().insert(QbFees.INVOICE, values)));
    }

    // ---- the document -----------------------------------------------------------------------------------------------

    public static ProcessDefinition<DocumentInput, InvoiceOutput, ProcessContext> document() {
        return ProcessDefinition.define(DOCUMENT, 1, DocumentInput.class, InvoiceOutput.class, ProcessContext.class,
            pb -> pb
                .description("Issues the PDF of a platform fee invoice of one's own, or gives the one already issued.")
                // The layout's permission (its issuer template is the receipts', qb.topup).
                .permissions(QbPermissions.TOPUP)
                .requiresVerifiedEmail()
                .actsOn(QbFees.INVOICE, INVOICE_ID)
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = context(start, input);
                    ctx.put(INVOICE_ID, input.invoiceId());
                    return ctx;
                })
                .outputMapper(FeeProcesses::invoiceOutput)
                .step("Take the invoice's lock", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbFees.documentLockName(ctx.get(INVOICE_ID))))
                .step("Load the invoice", LoadEntity.by(QbFees.SPONSOR_INVOICES, INVOICE_ID, INVOICE))
                .step("Load the issuer", QueryEntities.<ProcessContext>of(ParamEntities.DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In(ParamEntities.KEY,
                        new ArrayList<>(QbParams.ISSUER))).limit(QbParams.ISSUER.size()).build(), ISSUER))
                .compute("Check the issuer", (metadata, ctx) -> {
                    boolean named = list(ctx, ISSUER).stream().anyMatch(p -> QbParams.ISSUER_NAME.equals(
                        p.get(ParamEntities.KEY)) && p.get("value") != null && !String.valueOf((Object) p.get("value")).isBlank());
                    if (!named) {
                        throw new BusinessRuleViolationException(new Violation(null, FeeCodes.FEE_PARAMS,
                            "The issuer's name is not set", Map.of("param", QbParams.ISSUER_NAME)));
                    }
                })
                .step("Issue the document", CallProcess.<ProcessContext>when(
                    ctx -> invoice(ctx).get("documentRunId") == null, DocumentProcesses.ISSUE, 1,
                    ctx -> new DocumentProcesses.IssueInput(LAYOUT_ID, Map.of(INVOICE_ID, ctx.get(INVOICE_ID)), null,
                        null, ctx.get(INPUT, DocumentInput.class).language()), ISSUED))
                .compute("Record the document", (metadata, ctx) -> {
                    if (ctx.contains(ISSUED)) {
                        DocumentProcesses.IssueOutput issued = ctx.get(ISSUED, DocumentProcesses.IssueOutput.class);
                        EntityInstance invoice = invoice(ctx);
                        ctx.changes().update(QbFees.INVOICE, invoice.id(), invoice.version(),
                            Map.of("documentNo", issued.documentNo(), "documentRunId", issued.runId()));
                    }
                }));
    }

    // ---- overdue ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<InvoiceRef, ItemOutput, ProcessContext> overdueItem(ZoneId zone) {
        return ProcessDefinition.define(OVERDUE_ITEM, 1, InvoiceRef.class, ItemOutput.class, ProcessContext.class,
            pb -> pb
                .description("Marks an invoice past its due day overdue and reminds the sponsor, a week apart, three "
                    + "times at most; the first time, finance and the operator get a task.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory(FeeProcesses::context)
                .outputMapper(ctx -> new ItemOutput(invoice(ctx) == null ? null : invoice(ctx).get("sponsorId"),
                    (String) ctx.get(OUTCOME), ctx.get(INPUT, InvoiceRef.class).invoiceId()))
                .step("Find the invoice", QueryEntities.<ProcessContext>of(QbFees.defaultDataset(QbFees.INVOICE),
                    ctx -> byId("invoiceId", ctx.get(INPUT, InvoiceRef.class).invoiceId()), FOUND))
                .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(ctx -> first(ctx, FOUND) == null
                    ? null : QbWallets.lockName(QbWallets.SPONSOR, first(ctx, FOUND).get("sponsorId"))))
                .step("Read the invoice again", QueryEntities.<ProcessContext>of(QbFees.defaultDataset(QbFees.INVOICE),
                    ctx -> byId("invoiceId", first(ctx, FOUND) == null ? null : String.valueOf(first(ctx, FOUND).id())),
                    INVOICE))
                .compute("Remind", (metadata, ctx) -> remind(ctx, zone))
                .step("Ask the mail to remind the sponsor", PublishEvent.<ProcessContext>when(
                    ctx -> "REMINDED".equals(ctx.get(OUTCOME)), QbFees.INVOICE_OVERDUE_EVENT, ctx -> {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("invoiceId", String.valueOf(invoice(ctx).id()));
                        payload.put("sponsorId", invoice(ctx).get("sponsorId"));
                        payload.put("reminder", ctx.get(REMINDER));
                        return payload;
                    }))
                .step("Tell finance", CreateTask.<ProcessContext>when(FeeProcesses::firstReminder,
                    ctx -> overdueTask(invoice(ctx), QbPermissions.FEE_MANAGE)))
                .step("Tell the operator", CreateTask.<ProcessContext>when(
                    ctx -> firstReminder(ctx) && invoice(ctx).get("operatorId") != null,
                    ctx -> overdueTask(invoice(ctx), QbPermissions.OPERATOR_USE).scope(QbOperators.SCOPE,
                        String.valueOf((Object) invoice(ctx).get("operatorId"))))));
    }

    static void remind(ProcessContext ctx, ZoneId zone) {
        EntityInstance invoice = invoice(ctx);
        ctx.put(OUTCOME, "NONE");
        if (invoice == null || !QbFees.UNPAID.contains(String.valueOf((Object) invoice.get("status")))) {
            return;
        }
        LocalDate today = ctx.opTime().atZone(zone).toLocalDate();
        LocalDate dueOn = LocalDate.parse(String.valueOf((Object) invoice.get("dueOn")));
        int sent = money(invoice.get("remindersSent")).intValue();
        Object last = invoice.get("lastReminderAt");
        boolean reminderDue = last == null
            || !Instant.parse(String.valueOf(last)).plus(QbFees.REMINDER_DAYS, ChronoUnit.DAYS).isAfter(ctx.opTime());
        if (!dueOn.isBefore(today) || sent >= QbFees.MAX_REMINDERS || !reminderDue) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", QbFees.OVERDUE);
        values.put("remindersSent", BigDecimal.valueOf(sent + 1));
        values.put("lastReminderAt", ctx.opTime());
        ctx.changes().update(QbFees.INVOICE, invoice.id(), invoice.version(), values);
        ctx.put(REMINDER, sent + 1);
        ctx.put(OUTCOME, "REMINDED");
    }

    static boolean firstReminder(ProcessContext ctx) {
        return "REMINDED".equals(ctx.get(OUTCOME)) && Integer.valueOf(1).equals(ctx.get(REMINDER));
    }

    static TaskSpec overdueTask(EntityInstance invoice, String permission) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("invoiceNo", String.valueOf((Object) invoice.get("invoiceNo")));
        params.put("total", money(invoice.get("total")).toPlainString());
        return TaskSpec.forPermission(QbFees.OVERDUE_TASK, QbFees.OVERDUE_TASK_TITLE, params, permission)
            .about(QbFees.INVOICE, String.valueOf(invoice.id())).source(QbFees.overdueTaskSource(invoice.id()));
    }

    // ---- the billing mode -------------------------------------------------------------------------------------------

    public static ProcessDefinition<BillingInput, BillingOutput, ProcessContext> billingSet() {
        return ProcessDefinition.define(BILLING_SET, 1, BillingInput.class, BillingOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Sets how a sponsor pays the platform's fees (prepaid, or postpaid within a credit "
                        + "limit), with a reason; an operator for its own sponsors, headquarters for any.")
                    .permissions(QbPermissions.FEE_BILLING)
                    .requiresVerifiedEmail()
                    .actsOn(QbAccounts.SPONSOR, "userId")
                    .contextFactory(FeeProcesses::context)
                    .outputMapper(ctx -> new BillingOutput(billingInput(ctx).userId(), billingInput(ctx).billingMode(),
                        billingInput(ctx).creditLimit(), Boolean.TRUE.equals(ctx.get(OUTCOME))));
                // Q48e-1: an operator's member is checked within the act; headquarters passes.
                OperatorChecks.memberWhenScoped(pb);
                pb.step("Lock the sponsor's account", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbAccounts.lockName(billingInput(ctx).userId())))
                    .step("Load the sponsor", QueryEntities.<ProcessContext>of(QbAccounts.defaultDataset(QbAccounts.SPONSOR),
                        ctx -> bySponsor("userId", billingInput(ctx).userId()), SPONSOR));
                // Q59c-2: only a sponsor the caller's operator looks after.
                OperatorChecks.ownSponsor(pb, ctx -> billingInput(ctx).userId());
                pb.compute("Set the billing", (metadata, ctx) -> setBilling(ctx));
            });
    }

    static void setBilling(ProcessContext ctx) {
        BillingInput input = billingInput(ctx);
        EntityInstance sponsor = first(ctx, SPONSOR);
        if (sponsor == null) {
            throw new EntityNotFoundException(QbAccounts.SPONSOR + " of " + input.userId() + " not found");
        }
        List<Violation> problems = new ArrayList<>();
        if (ctx.request().actorId().equals(input.userId())) {
            problems.add(new Violation(null, FeeCodes.FEE_OWN, "One's own billing is set by another administrator",
                Map.of()));
        }
        if (FeeRules.PREPAID.equals(input.billingMode()) && input.creditLimit().signum() != 0) {
            problems.add(new Violation("creditLimit", FeeCodes.FEE_BILLING_MODE, "A prepaid sponsor has no credit limit",
                Map.of()));
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        boolean same = input.billingMode().equals(sponsor.get("billingMode"))
            && input.creditLimit().compareTo(money(sponsor.get("creditLimit"))) == 0;
        if (same) {
            ctx.put(OUTCOME, false);
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("billingMode", input.billingMode());
        values.put("creditLimit", input.creditLimit());
        values.put("billingReason", input.reason().strip());
        values.put("billingSetBy", ctx.request().actorId());
        values.put("billingSetAt", ctx.opTime());
        ctx.changes().update(QbAccounts.SPONSOR, sponsor.id(), sponsor.version(), values);
        ctx.put(OUTCOME, true);
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    static ProcessContext context(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    static InvoiceOutput invoiceOutput(ProcessContext ctx) {
        EntityInstance invoice = invoice(ctx);
        String status = ctx.contains(OUTCOME) && ctx.get(OUTCOME) instanceof String s ? s
            : invoice == null ? null : invoice.get("status");
        DocumentProcesses.IssueOutput issued = ctx.contains(ISSUED) ? ctx.get(ISSUED, DocumentProcesses.IssueOutput.class)
            : null;
        return new InvoiceOutput(invoice == null ? null : String.valueOf(invoice.id()),
            invoice == null ? null : invoice.get("invoiceNo"), status, invoice == null ? null : money(invoice.get("total")),
            ctx.contains(CASH_AFTER) ? ctx.get(CASH_AFTER, BigDecimal.class) : null,
            issued != null ? issued.runId() : invoice == null ? null : invoice.get("documentRunId"),
            issued != null ? issued.documentNo() : invoice == null ? null : invoice.get("documentNo"));
    }

    static String yearOf(String period) {
        return period.substring(0, 4);
    }

    static EntityQuery bySponsor(String field, Object sponsorId) {
        return EntityQuery.builder().where(new QueryPredicate.In(field, FeeGate.ids(sponsorId))).limit(1).build();
    }

    static EntityQuery byId(String field, String id) {
        return EntityQuery.builder().where(new QueryPredicate.In(field, FeeGate.ids(id))).limit(1).build();
    }

    static ChargeInput chargeInput(ProcessContext ctx) {
        return ctx.get(INPUT, ChargeInput.class);
    }

    static MonthlyInput monthlyInput(ProcessContext ctx) {
        return ctx.get(INPUT, MonthlyInput.class);
    }

    static InvoiceInput invoiceInput(ProcessContext ctx) {
        return ctx.get(INPUT, InvoiceInput.class);
    }

    static BillingInput billingInput(ProcessContext ctx) {
        return ctx.get(INPUT, BillingInput.class);
    }

    static SettleInput settleInput(ProcessContext ctx) {
        return ctx.get(INPUT, SettleInput.class);
    }

    static EntityInstance invoice(ProcessContext ctx) {
        Object loaded = ctx.get(INVOICE);
        if (loaded instanceof EntityInstance instance) {
            return instance;
        }
        return first(ctx, INVOICE);
    }

    static BigDecimal money(Object value) {
        return FeeGate.money(value);
    }

    static List<EntityInstance> list(ProcessContext ctx, String key) {
        return FeeGate.list(ctx, key);
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        Object loaded = ctx.get(key);
        if (loaded instanceof EntityInstance instance) {
            return instance;
        }
        return FeeGate.first(ctx, key);
    }

    /** The steps a process adds to read what the gate needs (for processes not loading params of their own). */
    static <I, O> void gate(ProcessDefinitionBuilder<I, O, ProcessContext> pb, Function<ProcessContext, Object> sponsor) {
        FeeGate.load(pb, sponsor);
    }
}

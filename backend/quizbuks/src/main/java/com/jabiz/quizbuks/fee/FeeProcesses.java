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
    /** F-M13: the lines by tax rate, and what the invoice was paid with (one row); both read the sponsor's own datasets. */
    public static final String RATES_TEMPLATE = "qb.sponsor.fee-invoice-rates";
    public static final String SOURCES_TEMPLATE = "qb.sponsor.fee-invoice-sources";
    /** The template parameter of the day the payment status is as of. */
    static final String AS_OF_DAY = "asOfDay";
    /** The template parameter of what the document says when the details of how it was paid are not available. */
    static final String UNAVAILABLE_NOTE = "unavailableNote";
    public static final String UNINVOICED_TEMPLATE = "qb.sponsor.fee-uninvoiced";
    public static final String ISSUER_TEMPLATE = "qb.sponsor.receipt-issuer";
    public static final String LAYOUT_ID = "qb.fee_invoice";

    /** The most one fee may be, in yen. */
    public static final String MAX_AMOUNT = "1000000000000";

    /**
     * @param sponsorId the sponsor (their user id) charged
     * @param kind      {@code MONTHLY}, {@code MANUAL}, (F-M5) {@code AUDIENCE}, (F-M6) {@code DISTRIBUTION} or (F-M8) {@code EXPOSURE}
     * @param period    the month ({@code yyyy-MM}) the fee belongs to
     * @param amount    the fee before tax, in yen
     * @param reason    why (a manual fee); a monthly fee has none
     * @param sourceKey what the fee stands for (the monthly fee's month, a manual fee's request)
     * @param chargedBy who asked for it; null for a job
     * @param operatorId F-M11: the operator the fee belongs to, given by the caller (a publication's fees: the one
     *                  frozen at its submission); none: the sponsor's operator now
     * @param prepaymentId F-M5: the prepayment it is paid from (a prepaid sponsor's; a postpaid one's only stops
     *                  counting against the credit limit); none: paid from the account as fees arise
     * @param tax       F-M8: the tax given by the caller, whose rule is not the fee's own on this amount (the exposure fee's
     *                  is cumulative: the tax on all settled so far less what was charged); none: the fee's own rule
     * @param shareExcluded F-M12: the part of the amount (before tax, yen) that does not enter the operator's share
     *                  (an AI fee's cost to the platform); none: 0, and never more than the amount
     */
    public record ChargeInput(@NotBlank @Size(max = 128) String sponsorId,
        @NotBlank @Pattern(regexp = "MONTHLY|MANUAL|AUDIENCE|DISTRIBUTION|EXPOSURE|AI") String kind,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String period,
        @NotNull @DecimalMin("1") @DecimalMax(MAX_AMOUNT) @Digits(integer = 13, fraction = 0) BigDecimal amount,
        @Size(max = QbFees.REASON_LENGTH) String reason, @Size(max = QbFees.SOURCE_LENGTH) String sourceKey,
        @Size(max = 128) String chargedBy, @Size(max = 36) String operatorId, @Size(max = 36) String prepaymentId,
        @DecimalMin("0") @DecimalMax(MAX_AMOUNT) @Digits(integer = 13, fraction = 0) BigDecimal tax,
        @DecimalMin("0") @DecimalMax(MAX_AMOUNT) @Digits(integer = 13, fraction = 0) BigDecimal shareExcluded) {

        /** A fee of the sponsor's current operator, paid from the account (the monthly fee, a fee by hand). */
        public ChargeInput(String sponsorId, String kind, String period, BigDecimal amount, String reason,
            String sourceKey, String chargedBy) {
            this(sponsorId, kind, period, amount, reason, sourceKey, chargedBy, null, null, null, null);
        }

        /** As before F-M8 and F-M12: the tax is the fee's own rule on this amount, the whole amount enters the share. */
        public ChargeInput(String sponsorId, String kind, String period, BigDecimal amount, String reason,
            String sourceKey, String chargedBy, String operatorId, String prepaymentId) {
            this(sponsorId, kind, period, amount, reason, sourceKey, chargedBy, operatorId, prepaymentId, null, null);
        }
    }

    /** @param invoiceId the prepaid charge's invoice, if one was made; {@code paid} whether it is paid already */
    /**
     * @param fromCredit F-M9: what its prepayment paid of it from fee credit (0 without a prepayment)
     * @param fromCash   F-M9: and from cash
     */
    public record ChargeOutput(String chargeId, BigDecimal total, String billingMode, String invoiceId,
        boolean paid, BigDecimal fromCredit, BigDecimal fromCash) {

        public ChargeOutput(String chargeId, BigDecimal total, String billingMode, String invoiceId, boolean paid) {
            this(chargeId, total, billingMode, invoiceId, paid, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    public record MonthlyInput(@NotBlank @Size(max = 128) String sponsorId,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String period) {}

    public record InvoiceInput(@NotBlank @Size(max = 128) String sponsorId,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String period) {}

    public record InvoiceRef(@NotBlank @Size(max = 36) String invoiceId) {}

    /** @param language the document's language ({@code en}, {@code zh}, {@code ja}); none: the caller's */
    public record DocumentInput(@NotBlank @Size(max = 36) String invoiceId, @Size(max = 10) String language) {}

    /** @param period only this month's fees ({@code yyyy-MM}); none: every month's */
    public record EarlyInput(@Pattern(regexp = "\\d{4}-\\d{2}") String period) {}

    /**
     * @param invoiceNos the invoices made, one a fee, all paid; {@code cashBalance} the cash after
     * @param remaining  how many fees still wait (more than one payment settles: {@value #MAX_EARLY} at a time)
     */
    public record EarlyOutput(int count, BigDecimal total, BigDecimal cashBalance, List<String> invoiceNos,
        int remaining) {}

    /**
     * One postpaid fee settled early (the item of {@value #PAY_EARLY}): what the caller read under its lock, the
     * operator the fee was charged under, and the due days loaded once.
     */
    public record SettleInput(@NotBlank @Size(max = 128) String sponsorId, @NotBlank @Size(max = 36) String chargeId,
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String period, @NotBlank @Pattern(regexp = "MONTHLY|MANUAL|DISTRIBUTION|EXPOSURE") String kind,
        @NotNull @DecimalMin("0") @DecimalMax(MAX_AMOUNT) @Digits(integer = 13, fraction = 0) BigDecimal amount,
        @NotNull @DecimalMin("0") @DecimalMax(MAX_AMOUNT) @Digits(integer = 13, fraction = 0) BigDecimal tax,
        @NotNull @DecimalMin("1") @DecimalMax(MAX_AMOUNT) @Digits(integer = 13, fraction = 0) BigDecimal total,
        @Size(max = 36) String operatorId, @NotNull @DecimalMin("0") @DecimalMax("3650") BigDecimal dueDays,
        List<CreditNotes.@NotNull Take> credits) {

        /** F-M7: what the credit notes' credit pays of it (taken by the caller, the earliest note first). */
        public BigDecimal credit() {
            return credits == null ? BigDecimal.ZERO : CreditNotes.takenTotal(credits);
        }
    }

    /** @param cashBalance the cash after this fee's payment */
    public record SettleOutput(String invoiceId, String invoiceNo, BigDecimal cashBalance) {}

    /**
     * @param status      the invoice's status after; {@code cashBalance} the cash after a payment
     * @param paidAmount  what has been paid of the total (F-M1)
     * @param paidNow     what this payment paid (none: not a payment)
     * @param creditPaid  of it, from the fee credit; {@code cashPaid} from the cash
     * @param creditApplied what credit notes paid of it (F-M7); owed = total − creditApplied − paidAmount
     */
    public record InvoiceOutput(String invoiceId, String invoiceNo, String status, BigDecimal total,
        BigDecimal cashBalance, String documentRunId, String documentNo, BigDecimal paidAmount, BigDecimal paidNow,
        BigDecimal creditPaid, BigDecimal cashPaid, BigDecimal creditApplied) {}

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
    static final String REMAINING = "remaining";
    /** How many charges of a month are read for its invoice: more than any sponsor has; as many is a failure. */
    static final int MAX_CHARGES = 500;
    /** How many fees one early payment settles at most: more than wait for an invoice (one a month, by hand a few). */
    static final int MAX_EARLY = 100;
    static final String CHARGE_ID = "chargeId";
    static final String CHARGE_POSTING = "chargePosting";
    static final String SETTLEMENT = "settlement";
    static final String PREPAYMENT = "prepayment";
    static final String SETTLEMENT_REST = "settlementRest";
    static final String PAID_AMOUNT = "paidAmount";
    static final String CREDITS = "credits";
    static final String INVOICE_NO = "invoiceNo";
    static final String INVOICE_ID = "invoiceId";
    static final String INVOICE = "invoice";
    static final String FOUND = "found";
    static final String BILLING_MODE = "billingMode";
    static final String MONTH_INVOICE = "monthInvoice";
    /**
     * How many months past the later of a postpaid fee's own and the current one its month's search reads (a wind-up
     * opens a month ahead only as late fees come; far more than it ever does).
     */
    static final int MONTHS_AHEAD = 24;
    static final String TOTAL = "total";
    /** The month the charge is written for (review L3: a late postpaid one is the current month, not the input's). */
    static final String CHARGE_PERIOD = "chargePeriod";
    static final String PAID_NOW = "paidNow";
    static final String CASH_AFTER = "cashAfter";
    static final String OUTCOME = "outcome";
    static final String ISSUER = "issuer";
    static final String ISSUED = "issued";
    static final String REMINDER = "reminder";
    /** F-M15 b: the wind-up of the invoice's sponsor, when they were erased. */
    static final String ERASED_CLOSURE = "erasedClosure";
    static final String CREDIT_NOTES = "creditNotes";
    static final String CREDIT_TAKES = "creditTakes";
    static final String CREDIT_LEFT = "creditLeft";

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
                Boolean.TRUE.equals(ctx.get(PAID_NOW)),
                ctx.contains(SETTLEMENT) ? ctx.get(SETTLEMENT, FeeSettlement.SettleOutput.class).credit()
                    : BigDecimal.ZERO,
                ctx.contains(SETTLEMENT) ? ctx.get(SETTLEMENT, FeeSettlement.SettleOutput.class).cash()
                    : BigDecimal.ZERO))
            .step("Load the fee parameters", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                QbParams.FEE_TAX_RATE, QbParams.FEE_DEFAULT_BILLING_MODE, QbParams.FEE_DUE_DAYS))
            .step("Load the sponsor and their operator", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> bySponsor("userId",
                    chargeInput(ctx).sponsorId()), SPONSOR),
                QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> bySponsor("userId",
                    chargeInput(ctx).sponsorId()), ASSIGNMENT),
                // F-M5 (M2 of its review): a fee paid from a prepayment is paid as the prepayment was set aside.
                QueryEntities.query(QbFeeAccount.defaultDataset(QbPrepayments.PREPAYMENT), ctx -> byId("prepaymentId",
                    chargeInput(ctx).prepaymentId()), PREPAYMENT),
                // F-M6 (§9.6, N3): the month's postpaid invoice, if issued already (in the same statement).
                QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("sponsorId", chargeInput(ctx).sponsorId()),
                        // F-M15 b: and every later month's (a wind-up invoices the current month and, as late
                        // fees come, the next ones), in the same statement.
                        new QueryPredicate.In("period", new ArrayList<Object>(FeeRules.monthsFrom(
                            chargeInput(ctx).period(), FeeRules.period(ctx.opTime(), zone), MONTHS_AHEAD))),
                        new QueryPredicate.IsNull("chargeId")))).limit(FeeGate.MAX_UNPAID).build(),
                    MONTH_INVOICE)))
            // F-M7 (§10.3-4): a prepaid fee's invoice takes the credit notes' credit left first. Read (H1 of the
            // review: summed in SQL) before anything is written, and only when the prepayment cannot pay it all.
            .step("Read the credit left", RunTemplate.<ProcessContext>when(FeeProcesses::creditMayPay,
                CreditNotes.LEFT_TEMPLATE, ctx -> CreditNotes.leftParams(chargeInput(ctx).sponsorId()), CREDIT_NOTES))
            .compute("Check and record the charge", (metadata, ctx) -> insertCharge(ctx, zone))
            // Written first: the posting names it as its source.
            .step("Save the charge", SaveChanges.now())
            .step("Post the fee", CallProcess.<ProcessContext>of(LedgerProcesses.POST, 1,
                ctx -> chargePosting(ctx), CHARGE_POSTING))
            // F-M5: a publication's fee is paid from what its submission set aside (a postpaid sponsor's prepayment
            // only stops counting against the credit limit; the fee is owed as their other fees).
            .step("Pay the fee from its prepayment", CallProcess.<ProcessContext>when(
                ctx -> chargeInput(ctx).prepaymentId() != null, Prepayments.USE, 1,
                ctx -> new Prepayments.UseInput(chargeInput(ctx).prepaymentId(), (String) ctx.get(CHARGE_ID),
                    ctx.get(TOTAL, BigDecimal.class), chargeReference(ctx),
                    // F-M9 (plan H4): the AI credit is read by lot.
                    FeeRules.AI.equals(chargeInput(ctx).kind())), SETTLEMENT))
            .compute("Take the credit left", (metadata, ctx) -> takeCredit(ctx))
            // D-Q87-5, F-M1: a prepaid sponsor pays as fees arise, from the fee credit and then the cash, as far as
            // they go (the rest stays owed on the charge's invoice). M1 of the F-M5 review: what the prepayment does
            // not cover (the tax rate changed while it waited) is paid so too, on the same invoice.
            .step("Pay the rest from the account", CallProcess.<ProcessContext>when(
                ctx -> FeeRules.PREPAID.equals(ctx.get(BILLING_MODE)) && unpaid(ctx).signum() > 0,
                FeeSettlement.SETTLE, 1, ctx -> new FeeSettlement.SettleInput(chargeInput(ctx).sponsorId(),
                    QbFees.CHARGE, (String) ctx.get(CHARGE_ID), unpaid(ctx), chargeReference(ctx)),
                SETTLEMENT_REST))
            .step("Number the invoice", AssignNumber.<ProcessContext>when(
                ctx -> FeeRules.PREPAID.equals(ctx.get(BILLING_MODE)), QbFees.INVOICE_SEQUENCE,
                ctx -> yearOf(chargeInput(ctx).period()), INVOICE_NO))
            .compute("Record the invoice", (metadata, ctx) -> insertPrepaidInvoice(ctx, zone))
            .step("Announce the invoice", PublishEvent.<ProcessContext>when(
                ctx -> ctx.contains(INVOICE_ID) && !Boolean.TRUE.equals(ctx.get(PAID_NOW)),
                QbFees.INVOICE_ISSUED_EVENT, FeeProcesses::invoicePayload)));
    }

    // The billing mode and the total decided here are foreseen by creditMayPay (before anything is written): keep both.
    static void insertCharge(ProcessContext ctx, ZoneId zone) {
        ChargeInput input = chargeInput(ctx);
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        List<Violation> problems = new ArrayList<>();
        EntityInstance sponsor = first(ctx, SPONSOR);
        // N1 of Fable's second F-M6 review: a defence; the callers read the prepayment of the sponsor they charge.
        EntityInstance held = first(ctx, PREPAYMENT);
        if (held != null && !input.sponsorId().equals(String.valueOf((Object) held.get("sponsorId")))) {
            throw new IllegalStateException("Prepayment " + held.id() + " is not " + input.sponsorId() + "'s");
        }
        // M3 of the F-M6 review: a fee charged from a prepayment is for what was bought with money already set aside
        // (an audience fee at the start, a distribution fee at its settlement), and is billed as the prepayment was
        // (below): it is charged whatever the onboarding is now. A sponsor who erased their account has none, and
        // their ended publication's final settlement still charges its deposit and gives back the rest.
        if (first(ctx, PREPAYMENT) == null
            && (sponsor == null || !SponsorStatus.APPROVED.equals(sponsor.get("reviewStatus")))) {
            problems.add(new Violation("sponsorId", FeeCodes.FEE_SPONSOR, "Only an approved sponsor is charged",
                Map.of()));
        }
        if (!FeeRules.period(input.period())) {
            problems.add(new Violation("period", FeeCodes.FEE_PERIOD, "Not a month", Map.of("period", input.period())));
        }
        // Third re-review L3: a fee of a month long gone is refused.
        if (!FeeRules.recentEnough(input.period(), FeeRules.period(ctx.opTime(), zone))) {
            problems.add(new Violation("period", FeeCodes.FEE_PARAMS, "A month more than "
                + FeeRules.MAX_MONTHS_BACK + " months ago", Map.of("period", input.period())));
        }
        BigDecimal rate = params.get(QbParams.FEE_TAX_RATE, BigDecimal.class);
        BigDecimal tax = input.tax() != null && rate != null ? input.tax() : FeeRules.tax(input.amount(), rate);
        if (tax == null) {
            problems.add(new Violation(null, FeeCodes.FEE_PARAMS, "The tax rate cannot be used",
                Map.of("rate", String.valueOf(rate))));
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        // M2 of the F-M5 review: a fee paid from a prepayment is billed as the prepayment was set aside (prepaid money,
        // or postpaid credit), whatever the sponsor's billing is now: else a prepaid prepayment's fee would be on the
        // month's postpaid invoice too, and a postpaid one's would be paid from the account.
        EntityInstance prepayment = first(ctx, PREPAYMENT);
        String billingMode = prepayment != null ? String.valueOf((Object) prepayment.get("mode"))
            : FeeRules.billingModeOf(sponsor.get("billingMode"), params.get(QbParams.FEE_DEFAULT_BILLING_MODE,
                String.class));
        // F-M6 (§9.6): a postpaid fee of a month whose invoice is out already (a settlement run late) goes on the
        // invoice of the month it is charged in; the invoice is issued under the same wallet lock, so they never cross.
        // F-M15 b: if that month's is out too (a wind-up invoices the current month), the month after it.
        java.util.Set<String> invoiced = FeeGate.list(ctx, MONTH_INVOICE).stream()
            .map(invoice -> String.valueOf((Object) invoice.get("period")))
            .collect(java.util.stream.Collectors.toSet());
        String now = FeeRules.period(ctx.opTime(), zone);
        String period = FeeRules.POSTPAID.equals(billingMode)
            ? FeeRules.chargePeriod(input.period(), now, invoiced) : input.period();
        // Third re-review L2: a month past what was read is not guessed (every month read had its invoice out).
        if (FeeRules.POSTPAID.equals(billingMode)
            && !FeeRules.monthsFrom(input.period(), now, MONTHS_AHEAD).contains(period)) {
            throw new BusinessRuleViolationException(List.of(new Violation("period", FeeCodes.FEE_PARAMS,
                "No month without its invoice out within the months read", Map.of("period", input.period()))));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sponsorId", input.sponsorId());
        values.put("operatorId", operatorOf(ctx));
        values.put("billingMode", billingMode);
        values.put("kind", input.kind());
        ctx.put(CHARGE_PERIOD, period);
        values.put("period", period);
        values.put("amount", input.amount());
        values.put("taxRate", rate);
        values.put("tax", tax);
        values.put("total", input.amount().add(tax));
        values.put("reason", input.reason() == null ? null : input.reason().strip());
        values.put("sourceKey", input.sourceKey());
        values.put("chargedAt", ctx.opTime());
        values.put("chargedBy", input.chargedBy());
        values.put("shareExcluded", shareExcluded(input));
        ctx.put(CHARGE_ID, String.valueOf(ctx.changes().insert(QbFees.CHARGE, values)));
        ctx.put(BILLING_MODE, billingMode);
        ctx.put(TOTAL, input.amount().add(tax));
    }

    /** F-M12: the part of the fee kept out of the operator's share: as given, 0 if none, never more than the amount. */
    static BigDecimal shareExcluded(ChargeInput input) {
        BigDecimal given = input.shareExcluded() == null ? BigDecimal.ZERO : input.shareExcluded();
        return given.max(BigDecimal.ZERO).min(input.amount());
    }

    /**
     * F-M9 (plan §4 item 3, review M1): the operator a caller gives for a fee that belongs to no operator (an AI job
     * asked before the sponsor's onboarding), so that it is not given to the operator the sponsor has now. Never
     * stored: the fee's operator is empty.
     */
    public static final String NO_OPERATOR = "-";

    /** F-M11: the operator given by the caller ({@link #NO_OPERATOR}: none), else the sponsor's operator now. */
    static String operatorOf(ProcessContext ctx) {
        String given = chargeInput(ctx).operatorId();
        if (NO_OPERATOR.equals(given)) {
            return null;
        }
        if (given != null && !given.isBlank()) {
            return given;
        }
        EntityInstance assignment = first(ctx, ASSIGNMENT);
        return assignment == null ? null : String.valueOf((Object) assignment.get("operatorId"));
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
        return new LedgerProcesses.PostInput(ctx.opTime(), "Platform fee", chargeReference(ctx), lines, QbFees.CHARGE,
            (String) ctx.get(CHARGE_ID));
    }

    /** The charge's postings' reference: its kind and the month it is actually written for (review L3). */
    static String chargeReference(ProcessContext ctx) {
        ChargeInput input = chargeInput(ctx);
        String period = ctx.contains(CHARGE_PERIOD) ? (String) ctx.get(CHARGE_PERIOD) : input.period();
        return reference(input.kind(), period);
    }

    /** The posting's reference: {@code fee_<kind>_<period>}, so that the monthly report tells the kinds apart. */
    static String reference(ChargeInput input) {
        return reference(input.kind(), input.period());
    }

    static String reference(String kind, String period) {
        return "fee_" + kind.toLowerCase(java.util.Locale.ROOT) + "_" + period;
    }

    static void insertPrepaidInvoice(ProcessContext ctx, ZoneId zone) {
        if (!FeeRules.PREPAID.equals(ctx.get(BILLING_MODE))) {
            return;
        }
        ChargeInput input = chargeInput(ctx);
        BigDecimal total = ctx.get(TOTAL, BigDecimal.class);
        FeeSettlement.SettleOutput settled = chargePaid(ctx);
        List<CreditNotes.Take> takes = takes(ctx);
        BigDecimal credit = CreditNotes.takenTotal(takes);
        Map<String, Object> values = chargeInvoice(ctx, input.sponsorId(), operatorOf(ctx), FeeRules.PREPAID,
            input.period(), (String) ctx.get(CHARGE_ID), input.amount(), total, settled, credit,
            ctx.get(PARAMS, ParamValues.class).get(QbParams.FEE_DUE_DAYS, BigDecimal.class), zone);
        String invoiceId = String.valueOf(ctx.changes().insert(QbFees.INVOICE, values));
        ctx.put(INVOICE_ID, invoiceId);
        CreditNotes.recordUses(ctx, input.sponsorId(), invoiceId, takes);
        ctx.put(PAID_NOW, settled.paid().add(credit).compareTo(total) >= 0);
    }

    /**
     * F-M7 (§10.3-4): what a prepaid fee takes of the credit notes' credit left, after what its prepayment paid (the
     * money set aside for it) and before the account.
     */
    static void takeCredit(ProcessContext ctx) {
        if (!mayTakeCredit(ctx)) {
            return;
        }
        if (!ctx.contains(CREDIT_NOTES)) {
            // creditMayPay foresees what insertCharge and the prepayment do; never skip the credit if it was wrong.
            throw new IllegalStateException("The credit left was not read for a fee that still owes "
                + owedAfterPrepayment(ctx));
        }
        ctx.put(CREDIT_TAKES, CreditNotes.take(CreditNotes.left(CreditNotes.rows(ctx, CREDIT_NOTES)),
            owedAfterPrepayment(ctx)));
    }

    /**
     * Before the charge is written: whether it will be prepaid and owe something after its prepayment pays (what
     * {@link #insertCharge} and {@code QB_PREPAYMENT_USE} will do: the prepayment's mode, else the sponsor's billing;
     * the amount and its tax; the prepayment pays at most what it holds).
     */
    static boolean creditMayPay(ProcessContext ctx) {
        ChargeInput input = chargeInput(ctx);
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        EntityInstance prepayment = first(ctx, PREPAYMENT);
        EntityInstance sponsor = first(ctx, SPONSOR);
        if (prepayment == null && sponsor == null) {
            return false;
        }
        String billingMode = prepayment != null ? String.valueOf((Object) prepayment.get("mode"))
            : FeeRules.billingModeOf(sponsor.get("billingMode"), params.get(QbParams.FEE_DEFAULT_BILLING_MODE,
                String.class));
        BigDecimal rate = params.get(QbParams.FEE_TAX_RATE, BigDecimal.class);
        BigDecimal tax = input.tax() != null && rate != null ? input.tax() : FeeRules.tax(input.amount(), rate);
        if (!FeeRules.PREPAID.equals(billingMode) || tax == null) {
            return false;
        }
        BigDecimal total = input.amount().add(tax);
        BigDecimal fromPrepayment = prepayment == null ? BigDecimal.ZERO
            : total.min(money(prepayment.get("remaining")).max(BigDecimal.ZERO));
        return total.subtract(fromPrepayment).signum() > 0;
    }

    /** Whether a prepaid fee still owes something after its prepayment paid (only then is the credit taken). */
    static boolean mayTakeCredit(ProcessContext ctx) {
        return FeeRules.PREPAID.equals(ctx.get(BILLING_MODE)) && ctx.contains(TOTAL)
            && owedAfterPrepayment(ctx).signum() > 0;
    }

    static BigDecimal owedAfterPrepayment(ProcessContext ctx) {
        FeeSettlement.SettleOutput used = ctx.get(SETTLEMENT, FeeSettlement.SettleOutput.class);
        return ctx.get(TOTAL, BigDecimal.class).subtract(used == null ? BigDecimal.ZERO : used.paid());
    }

    @SuppressWarnings("unchecked")
    static List<CreditNotes.Take> takes(ProcessContext ctx) {
        return ctx.contains(CREDIT_TAKES) ? (List<CreditNotes.Take>) ctx.get(CREDIT_TAKES) : List.of();
    }

    /** What the prepayment's payment and the credit notes' credit left unpaid of the charge. */
    static BigDecimal unpaid(ProcessContext ctx) {
        FeeSettlement.SettleOutput used = ctx.get(SETTLEMENT, FeeSettlement.SettleOutput.class);
        return ctx.get(TOTAL, BigDecimal.class).subtract(used == null ? BigDecimal.ZERO : used.paid())
            .subtract(CreditNotes.takenTotal(takes(ctx)));
    }

    /**
     * What paid the charge now: its prepayment's payment, the account's, or (M1 of the F-M5 review) both together,
     * the later posting named; nothing when neither paid (the credit notes' credit paid it all, or none was due).
     */
    static FeeSettlement.SettleOutput chargePaid(ProcessContext ctx) {
        FeeSettlement.SettleOutput first = ctx.get(SETTLEMENT, FeeSettlement.SettleOutput.class);
        FeeSettlement.SettleOutput rest = ctx.contains(SETTLEMENT_REST)
            ? ctx.get(SETTLEMENT_REST, FeeSettlement.SettleOutput.class) : null;
        if (rest == null) {
            return first != null ? first : new FeeSettlement.SettleOutput(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, null);
        }
        if (first == null) {
            return rest;
        }
        return new FeeSettlement.SettleOutput(first.paid().add(rest.paid()), first.credit().add(rest.credit()),
            first.cash().add(rest.cash()), rest.transactionId() != null ? rest.transactionId() : first.transactionId(),
            rest.cashBalance(), rest.creditBalance());
    }

    /**
     * The values of one charge's own invoice (a prepaid charge's, or a postpaid fee's paid early): numbered already
     * ({@link #INVOICE_NO}), paid now as far as {@code settled} paid it (in full: {@code PAID}, the payment's posting
     * named; in part: {@code PARTIAL}; nothing: {@code OPEN}).
     */
    static Map<String, Object> chargeInvoice(ProcessContext ctx, String sponsorId, String operatorId,
        String billingMode, String period, String chargeId, BigDecimal amount, BigDecimal total,
        FeeSettlement.SettleOutput settled, BigDecimal creditApplied, BigDecimal dueDays, ZoneId zone) {
        BigDecimal paidAmount = settled.paid();
        // F-M7: what the credit notes' credit paid of it counts as paid, though nothing was received.
        BigDecimal covered = paidAmount.add(creditApplied);
        boolean paid = covered.compareTo(total) >= 0;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("invoiceNo", ctx.get(INVOICE_NO));
        values.put("sponsorId", sponsorId);
        values.put("operatorId", operatorId);
        values.put("billingMode", billingMode);
        values.put("period", period);
        values.put("chargeId", chargeId);
        values.put("amount", amount);
        values.put("tax", total.subtract(amount));
        values.put("total", total);
        values.put("status", paid ? QbFees.PAID : covered.signum() > 0 ? QbFees.PARTIAL : QbFees.OPEN);
        values.put("paidAmount", paidAmount);
        values.put("creditApplied", creditApplied);
        values.put("issuedAt", ctx.opTime());
        values.put("issuedOn", FeeRules.issuedOn(ctx.opTime(), zone));
        values.put("dueOn", FeeRules.dueOn(ctx.opTime(), dueDays, zone));
        values.put("paidAt", paid ? ctx.opTime() : null);
        values.put("paymentTransactionId", paid ? settled.transactionId() : null);
        values.put("remindersSent", BigDecimal.ZERO);
        return values;
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
                // F-M7 (§10.3-4): the credit notes' credit left, which the month's invoice takes first (H1 of the
                // review: summed in SQL; read only when the invoice is issued).
                .step("Read the credit left", RunTemplate.<ProcessContext>when(ctx -> "ISSUED".equals(ctx.get(OUTCOME)),
                    CreditNotes.LEFT_TEMPLATE, ctx -> CreditNotes.leftParams(invoiceInput(ctx).sponsorId()),
                    CREDIT_NOTES))
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
        BigDecimal total = amount.add(tax);
        values.put("total", total);
        // F-M7 (§10.3-4): the credit notes' credit left pays it first (a postpaid fee's note left it, C2).
        List<CreditNotes.Take> takes = CreditNotes.take(CreditNotes.left(CreditNotes.rows(ctx, CREDIT_NOTES)), total);
        BigDecimal credit = CreditNotes.takenTotal(takes);
        boolean paid = credit.compareTo(total) >= 0;
        values.put("status", paid ? QbFees.PAID : credit.signum() > 0 ? QbFees.PARTIAL : QbFees.OPEN);
        values.put("paidAmount", BigDecimal.ZERO);
        values.put("creditApplied", credit);
        values.put("paidAt", paid ? ctx.opTime() : null);
        values.put("issuedAt", ctx.opTime());
        values.put("issuedOn", FeeRules.issuedOn(ctx.opTime(), zone));
        values.put("dueOn", FeeRules.dueOn(ctx.opTime(),
            ctx.get(PARAMS, ParamValues.class).get(QbParams.FEE_DUE_DAYS, BigDecimal.class), zone));
        values.put("remindersSent", BigDecimal.ZERO);
        String invoiceId = String.valueOf(ctx.changes().insert(QbFees.INVOICE, values));
        ctx.put(INVOICE_ID, invoiceId);
        CreditNotes.recordUses(ctx, input.sponsorId(), invoiceId, takes);
    }

    // ---- paying -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<InvoiceRef, InvoiceOutput, ProcessContext> pay() {
        return ProcessDefinition.define(PAY, 1, InvoiceRef.class, InvoiceOutput.class, ProcessContext.class, pb -> pb
            .description("Pays a platform fee invoice of one's own from the account that pays the platform's fees: "
                + "the fee credit first, then the cash, as far as they go (the rest stays owed).")
            .permissions(QbPermissions.TOPUP)
            .requiresVerifiedEmail()
            .actsOn(QbFees.INVOICE, INVOICE_ID, a -> a.whenField("status", QbFees.OPEN, QbFees.PARTIAL,
                QbFees.OVERDUE))
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
            .step("Load the account", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT),
                    ctx -> FeeSettlement.credits(ctx.request().actorId(), ctx.opTime()), CREDITS),
                QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                    ctx -> QbWallets.query(QbWallets.CASH, ctx.request().actorId()), CASH_WALLET)))
            .compute("Check the payment", (metadata, ctx) -> checkPayment(ctx))
            .step("Pay", CallProcess.<ProcessContext>of(FeeSettlement.SETTLE, 1,
                ctx -> new FeeSettlement.SettleInput(ctx.request().actorId(), QbFees.INVOICE,
                    String.valueOf(invoice(ctx).id()), owed(invoice(ctx)), invoice(ctx).get("invoiceNo")), SETTLEMENT))
            .compute("Record the payment", (metadata, ctx) -> recordPayment(ctx))
            .step("Close the reminder", CloseTasks.<ProcessContext>done(
                ctx -> paidNow(ctx) ? QbFees.overdueTaskSource(invoice(ctx).id()) : QbFees.overdueTaskSource("none"))));
    }

    /** What is still owed of an invoice ({@link QbFees#owed}). */
    static BigDecimal owed(EntityInstance invoice) {
        return QbFees.owed(invoice);
    }

    private static boolean paidNow(ProcessContext ctx) {
        return Boolean.TRUE.equals(ctx.get(PAID_NOW));
    }

    static void checkPayment(ProcessContext ctx) {
        EntityInstance invoice = invoice(ctx);
        if (!QbFees.UNPAID.contains(String.valueOf((Object) invoice.get("status")))) {
            // Nothing to pay: the account is not the question.
            throw new BusinessRuleViolationException(List.of(new Violation(null, FeeCodes.FEE_INVOICE_STATE,
                "The invoice is not open", Map.of("status", String.valueOf((Object) invoice.get("status"))))));
        }
        // Paid as far as the account goes (F-M13); refused only when it pays nothing.
        BigDecimal available = FeeSettlement.spendable(list(ctx, CREDITS), first(ctx, CASH_WALLET), ctx.opTime());
        if (available.signum() <= 0) {
            BigDecimal owed = owed(invoice);
            throw new BusinessRuleViolationException(List.of(new Violation(null, FeeCodes.FEE_CASH_SHORT,
                "The account has " + available + " available; the invoice is " + owed, Map.of(
                    "available", available.toPlainString(), "total", owed.toPlainString()))));
        }
    }

    static void recordPayment(ProcessContext ctx) {
        EntityInstance invoice = invoice(ctx);
        FeeSettlement.SettleOutput settled = ctx.get(SETTLEMENT, FeeSettlement.SettleOutput.class);
        BigDecimal paidAmount = money(invoice.get("paidAmount")).add(settled.paid());
        boolean paid = paidAmount.add(money(invoice.get("creditApplied"))).compareTo(money(invoice.get("total"))) >= 0;
        ctx.put(CASH_AFTER, settled.cashBalance());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("paidAmount", paidAmount);
        ctx.put(PAID_AMOUNT, paidAmount);
        String status = String.valueOf((Object) invoice.get("status"));
        if (paid) {
            status = QbFees.PAID;
            values.put("paidAt", ctx.opTime());
            values.put("paymentTransactionId", settled.transactionId());
        } else if (QbFees.OPEN.equals(status)) {
            status = QbFees.PARTIAL;
        }
        if (!status.equals(invoice.get("status"))) {
            values.put("status", status);
        }
        ctx.changes().update(QbFees.INVOICE, invoice.id(), invoice.version(), values);
        ctx.put(OUTCOME, status);
        ctx.put(PAID_NOW, paid);
    }

    // ---- paying early -----------------------------------------------------------------------------------------------

    public static ProcessDefinition<EarlyInput, EarlyOutput, ProcessContext> payEarly() {
        return ProcessDefinition.define(PAY_EARLY, 1, EarlyInput.class, EarlyOutput.class, ProcessContext.class, pb -> pb
            .description("Pays one's own postpaid platform fees not invoiced yet from the account that pays the "
                + "platform's fees (the fee credit first, then the cash): each gets an invoice of its own, paid; the "
                + "month's invoice then covers what remains.")
            .permissions(QbPermissions.TOPUP)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .steps(ConsentChecks.sponsor())
            .contextFactory(FeeProcesses::context)
            .outputMapper(ctx -> new EarlyOutput(settled(ctx).size(), ctx.get(TOTAL, BigDecimal.class),
                ctx.get(CASH_AFTER, BigDecimal.class), settled(ctx).stream().map(SettleOutput::invoiceNo).toList(),
                (Integer) ctx.get(REMAINING)))
            .step("Load the due days", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                QbParams.FEE_DUE_DAYS))
            .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(
                ctx -> QbWallets.lockName(QbWallets.SPONSOR, ctx.request().actorId())))
            // Through the sponsor's own datasets: the fees of the caller only.
            .step("Read the fees not invoiced yet", RunTemplate.<ProcessContext>of(UNINVOICED_TEMPLATE, ctx -> {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("period", ctx.get(INPUT, EarlyInput.class).period());
                return params;
            }, EARLY))
            .step("Load the account", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT),
                    ctx -> FeeSettlement.credits(ctx.request().actorId(), ctx.opTime()), CREDITS),
                QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                    ctx -> QbWallets.query(QbWallets.CASH, ctx.request().actorId()), CASH_WALLET)))
            // F-M7 (C2): the credit notes' credit left pays first (H1 of the review: summed in SQL).
            .step("Read the credit left", RunTemplate.<ProcessContext>of(CreditNotes.LEFT_TEMPLATE,
                ctx -> CreditNotes.leftParams(ctx.request().actorId()), CREDIT_NOTES))
            .compute("Check the payment", (metadata, ctx) -> checkEarlyPayment(ctx))
            // Each fee its own payment (QB_FEE_SETTLE), in the order the fees were charged.
            .step("Settle each fee", CallProcess.forEach(SETTLE_ITEM, 1, FeeProcesses::settleInputs, SETTLED))
            .compute("The cash after", (metadata, ctx) -> {
                List<SettleOutput> settled = settled(ctx);
                ctx.put(CASH_AFTER, settled.isEmpty() ? QbWallets.balance(first(ctx, CASH_WALLET))
                    : settled.getLast().cashBalance());
            }));
    }

    static void checkEarlyPayment(ProcessContext ctx) {
        List<Map<String, Object>> rows = rows(ctx, EARLY);
        if (rows.isEmpty()) {
            throw new BusinessRuleViolationException(new Violation(null, FeeCodes.FEE_NOTHING_DUE,
                "No platform fee is waiting for its invoice", Map.of()));
        }
        ctx.put(REMAINING, Math.max(0, rows.size() - MAX_EARLY));
        if (rows.size() > MAX_EARLY) {
            // The oldest first; the page offers the rest after.
            rows = rows.subList(0, MAX_EARLY);
            ctx.put(EARLY, rows);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            total = total.add(money(row.get("total")));
        }
        // The whole of what is chosen, or nothing: paying early is a choice, not a debt yet. F-M7 (C2): the credit
        // notes' credit left pays first, then the account.
        List<CreditNotes.Unused> unused = CreditNotes.left(CreditNotes.rows(ctx, CREDIT_NOTES));
        ctx.put(CREDIT_LEFT, unused);
        BigDecimal available = FeeSettlement.spendable(list(ctx, CREDITS), first(ctx, CASH_WALLET), ctx.opTime())
            .add(CreditNotes.total(unused));
        if (available.compareTo(total) < 0) {
            throw new BusinessRuleViolationException(new Violation(null, FeeCodes.FEE_CASH_SHORT, "The account has "
                + available + " available; the fees are " + total, Map.of("available", available.toPlainString(),
                "total", total.toPlainString())));
        }
        ctx.put(TOTAL, total);
    }

    static List<SettleInput> settleInputs(ProcessContext ctx) {
        BigDecimal dueDays = ctx.get(PARAMS, ParamValues.class).get(QbParams.FEE_DUE_DAYS, BigDecimal.class);
        @SuppressWarnings("unchecked")
        List<CreditNotes.Unused> unused = ctx.contains(CREDIT_LEFT)
            ? (List<CreditNotes.Unused>) ctx.get(CREDIT_LEFT) : List.of();
        List<SettleInput> inputs = new ArrayList<>();
        for (Map<String, Object> row : rows(ctx, EARLY)) {
            // F-M7: each fee, the oldest first, takes what is left of the notes' credit before the account.
            List<CreditNotes.Take> takes = CreditNotes.take(unused, money(row.get("total")));
            unused = CreditNotes.after(unused, takes);
            // The invoice keeps the operator the fee was charged under, as the charge does.
            inputs.add(new SettleInput(ctx.request().actorId(), String.valueOf(row.get("chargeId")),
                String.valueOf(row.get("period")), String.valueOf(row.get("kind")), money(row.get("amount")),
                money(row.get("tax")), money(row.get("total")),
                row.get("operatorId") == null ? null : String.valueOf(row.get("operatorId")),
                dueDays == null || dueDays.signum() < 0 ? BigDecimal.ZERO : dueDays, takes));
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
                .description("Settles one postpaid platform fee early: paid from the fee credit and the cash, "
                    + "invoiced on its own.")
                .permissions(QbPermissions.TOPUP)
                .subProcessOnly()
                .contextFactory(FeeProcesses::context)
                .outputMapper(ctx -> new SettleOutput((String) ctx.get(INVOICE_ID), (String) ctx.get(INVOICE_NO),
                    ctx.get(SETTLEMENT, FeeSettlement.SettleOutput.class).cashBalance()))
                .step("Pay the fee", CallProcess.<ProcessContext>of(FeeSettlement.SETTLE, 1,
                    ctx -> new FeeSettlement.SettleInput(settleInput(ctx).sponsorId(), QbFees.CHARGE,
                        settleInput(ctx).chargeId(), settleInput(ctx).total().subtract(settleInput(ctx).credit()),
                        reference(settleInput(ctx).kind(), settleInput(ctx).period())), SETTLEMENT))
                .compute("Check it was paid", (metadata, ctx) -> {
                    // The caller checked the notes' credit and the account cover every fee chosen, under the same lock.
                    if (ctx.get(SETTLEMENT, FeeSettlement.SettleOutput.class).paid().add(settleInput(ctx).credit())
                        .compareTo(settleInput(ctx).total()) < 0) {
                        throw new IllegalStateException("A fee paid early was not paid in full: "
                            + settleInput(ctx).chargeId());
                    }
                })
                .step("Number the invoice", AssignNumber.<ProcessContext>of(QbFees.INVOICE_SEQUENCE,
                    ctx -> yearOf(settleInput(ctx).period()), INVOICE_NO))
                .compute("Record the invoice", (metadata, ctx) -> insertSettledInvoice(ctx, zone)));
    }

    static void insertSettledInvoice(ProcessContext ctx, ZoneId zone) {
        SettleInput input = settleInput(ctx);
        Map<String, Object> values = chargeInvoice(ctx, input.sponsorId(), input.operatorId(), FeeRules.POSTPAID,
            input.period(), input.chargeId(), input.amount(), input.total(),
            ctx.get(SETTLEMENT, FeeSettlement.SettleOutput.class), input.credit(), input.dueDays(), zone);
        String invoiceId = String.valueOf(ctx.changes().insert(QbFees.INVOICE, values));
        ctx.put(INVOICE_ID, invoiceId);
        CreditNotes.recordUses(ctx, input.sponsorId(), invoiceId, input.credits() == null ? List.of()
            : input.credits());
    }

    // ---- the document -----------------------------------------------------------------------------------------------

    /**
     * What the document says, in its language, of an invoice whose details of how it was paid are not available (a message
     * of {@code document.qb.fee_invoice}): given the language asked for and the request.
     */
    @FunctionalInterface
    public interface UnavailableNote {
        String in(String language, com.jabiz.context.RequestContext request);
    }

    public static ProcessDefinition<DocumentInput, InvoiceOutput, ProcessContext> document(ZoneId zone,
        UnavailableNote unavailable) {
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
                .compute("Refuse a voided invoice that has no document", (metadata, ctx) -> {
                    // F-M13: one already issued stays downloadable (the original is kept); a voided invoice with none
                    // is not issued now (a document of something void would claim a debt that is not owed).
                    if (QbFees.VOID.equals(invoice(ctx).get("status")) && invoice(ctx).get("documentRunId") == null) {
                        throw new BusinessRuleViolationException(new Violation("invoiceId", FeeCodes.FEE_INVOICE_STATE,
                            "The invoice is voided", Map.of()));
                    }
                })
                .step("Issue the document", CallProcess.<ProcessContext>when(
                    ctx -> invoice(ctx).get("documentRunId") == null, DocumentProcesses.ISSUE, 1,
                    // The payment status is stated as of the issue's day in the fiscal zone: a snapshot, as the
                    // document is (F-M13 §3.2); the time itself is never read by the template.
                    ctx -> new DocumentProcesses.IssueInput(LAYOUT_ID, Map.of(INVOICE_ID, ctx.get(INVOICE_ID),
                        AS_OF_DAY, FeeRules.issuedOn(ctx.opTime(), zone), UNAVAILABLE_NOTE,
                        unavailable.in(ctx.get(INPUT, DocumentInput.class).language(), ctx.request())), null, null,
                        ctx.get(INPUT, DocumentInput.class).language()), ISSUED))
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
                // F-M15 b (§9 M4): with the sponsor's wind-up, in one statement. An erased sponsor's invoices are not
                // reminded of (no mail reaches them, and finance has the reconciliation's ERASED_FEE_OWED instead);
                // the items' template leaves them out, this covers one registered between the two.
                .step("Read the invoice again", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE), ctx -> byId("invoiceId",
                        first(ctx, FOUND) == null ? null : String.valueOf(first(ctx, FOUND).id())), INVOICE),
                    QueryEntities.query(QbFees.defaultDataset(QbSponsorClosures.CLOSURE), ctx -> byId("sponsorId",
                        first(ctx, FOUND) == null ? null : String.valueOf((Object) first(ctx, FOUND).get("sponsorId"))),
                        ERASED_CLOSURE)))
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
        if (invoice == null || !QbFees.UNPAID.contains(String.valueOf((Object) invoice.get("status")))
            || first(ctx, ERASED_CLOSURE) != null) {
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
        // F-M7: what it still owes (QbFees.owed, the one definition).
        params.put("total", QbFees.owed(invoice).toPlainString());
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
                CallerChecks.refuseBanned(pb);
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
        FeeSettlement.SettleOutput settled = ctx.contains(SETTLEMENT)
            ? ctx.get(SETTLEMENT, FeeSettlement.SettleOutput.class) : null;
        return new InvoiceOutput(invoice == null ? null : String.valueOf(invoice.id()),
            invoice == null ? null : invoice.get("invoiceNo"), status, invoice == null ? null : money(invoice.get("total")),
            ctx.contains(CASH_AFTER) ? ctx.get(CASH_AFTER, BigDecimal.class) : null,
            issued != null ? issued.runId() : invoice == null ? null : invoice.get("documentRunId"),
            issued != null ? issued.documentNo() : invoice == null ? null : invoice.get("documentNo"),
            ctx.contains(PAID_AMOUNT) ? ctx.get(PAID_AMOUNT, BigDecimal.class)
                : invoice == null ? null : money(invoice.get("paidAmount")),
            settled == null ? null : settled.paid(), settled == null ? null : settled.credit(),
            settled == null ? null : settled.cash(), invoice == null ? null : money(invoice.get("creditApplied")));
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

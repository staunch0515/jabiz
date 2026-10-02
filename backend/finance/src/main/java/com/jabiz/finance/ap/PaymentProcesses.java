package com.jabiz.finance.ap;

import com.jabiz.approval.ContentHash;
import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.ar.ArEntities;
import com.jabiz.finance.bank.BankEntities;
import com.jabiz.finance.calc.Form1099Allocation;
import com.jabiz.finance.calc.PaymentTerms;
import com.jabiz.finance.fx.FxEntities;
import com.jabiz.finance.fx.FxRates;
import com.jabiz.finance.fx.FxSettingsProcesses;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.SubledgerPosting;
import com.jabiz.numbering.NumberSequence;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.approval.WithdrawApproval;
import com.jabiz.runtime.numbering.AssignNumber;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.security.MfaRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Payment runs and payments (FIN-AP-003, 008, 010…015, FIN-SC-001; docs/finance/00-design.md section 9):
 * <ul>
 *   <li>{@code FIN_PAYMENT_RUN_PROPOSE}: a new run ({@code PAY-RUN-01}) of the bills due by a day (or whose early
 *       payment discount runs to the payment day), of all vendors or some. A bill is held, with its reason, when it is
 *       not approved, its vendor's bank details wait for approval (FIN-AP-003), an ACH vendor has no approved account
 *       or the bill is in another open run. Without a due day the run starts empty, for other payments.</li>
 *   <li>{@code FIN_PAYMENT_RUN_ADD} / {@code _REMOVE}: a draft run's lines: a bill (the same holds), another payment to
 *       an account such as a tax (FIN-AP-015), or a vendor's prepayment (FIN-AP-008).</li>
 *   <li>{@code FIN_PAYMENT_RUN_SUBMIT}: asks for approval under the rules of {@code fin.ap.payment-run}; without a rule
 *       that stops it the run is refused rather than paid unapproved. Whoever changed the run last submits it and
 *       never approves it; once submitted it no longer changes (FIN-AP-011, FIN-CT-003).</li>
 *   <li>{@code FIN_PAYMENT_RUN_APPROVAL_RESULT}: run on the platform's approval events, believing only the platform's
 *       request: approved runs are locked to the content approved; a rejected one is a draft again.</li>
 *   <li>{@code FIN_PAYMENT_RUN_RELEASE}: the treasury releases an approved run, with a second factor (FIN-SC-001):
 *       everything is checked again, checks are numbered from the bank account's stock, and each vendor's bills become
 *       one payment ({@code FIN_PAYMENT_RECORD}, internal): debit payables, credit the discount and the bank; the bills
 *       show paid and link to it (FIN-AP-012).</li>
 *   <li>{@code FIN_PAYMENT_RUN_CANCEL}: a run not released is given up, its approval request withdrawn.</li>
 *   <li>{@code FIN_PAYMENT_VOID}: a payment voided on a day in an open period (a stopped check): its entry is reversed
 *       on that day, its applications taken back and the bills open again; the payment stays visible (FIN-AP-014).</li>
 *   <li>{@code FIN_AP_PREPAYMENT_APPLY}: a prepayment applied to a bill of its vendor: debit payables, credit
 *       prepayments (FIN-AP-008).</li>
 * </ul>
 * A run pays bills in one currency (F7 plan decision D4): one in a foreign currency is a wire or manual run of bills
 * only, without discounts, at the spot rate of the payment day or the rate given, fixed when it is proposed and
 * approved with it. Each payment debits payables with the dollars its bills carry and credits the bank with the
 * dollars paid; the difference is the realized gain or loss (FIN-FX-004). Form 1099 counts the dollars paid.
 */
public final class PaymentProcesses {

    public static final String PROPOSE = "FIN_PAYMENT_RUN_PROPOSE";
    public static final String ADD = "FIN_PAYMENT_RUN_ADD";
    public static final String REMOVE = "FIN_PAYMENT_RUN_REMOVE";
    public static final String SUBMIT = "FIN_PAYMENT_RUN_SUBMIT";
    public static final String APPROVAL_RESULT = "FIN_PAYMENT_RUN_APPROVAL_RESULT";
    public static final String RELEASE = "FIN_PAYMENT_RUN_RELEASE";
    public static final String CANCEL = "FIN_PAYMENT_RUN_CANCEL";
    public static final String RECORD = "FIN_PAYMENT_RECORD";
    public static final String VOID = "FIN_PAYMENT_VOID";
    public static final String PREPAYMENT_APPLY = "FIN_AP_PREPAYMENT_APPLY";

    public static final String RUN_NUMBERS = "fin.ap.payment-run";
    public static final String PAYMENT_NUMBERS = "fin.ap.payment";

    /** The approval subject of payment runs (FIN-AP-011). */
    public static final String SUBJECT = "fin.ap.payment-run";

    public static final String NO_SETTINGS = "FIN_PAYMENT_NO_SETTINGS";
    public static final String UNKNOWN_BANK = "FIN_PAYMENT_UNKNOWN_BANK";
    public static final String INVALID_VALUE = "FIN_PAYMENT_INVALID_VALUE";
    public static final String NOT_DRAFT = "FIN_PAYMENT_RUN_NOT_DRAFT";
    public static final String NOT_APPROVED = "FIN_PAYMENT_RUN_NOT_APPROVED";
    public static final String NOT_PREPARER = "FIN_PAYMENT_RUN_NOT_PREPARER";
    public static final String HELD = "FIN_PAYMENT_BILL_HELD";
    public static final String EMPTY = "FIN_PAYMENT_RUN_EMPTY";
    public static final String NO_RULE = "FIN_PAYMENT_RUN_NO_RULE";
    public static final String CHANGED = "FIN_PAYMENT_RUN_CHANGED";
    public static final String ACCOUNT = "FIN_PAYMENT_ACCOUNT";
    public static final String NO_CHECK_NUMBER = "FIN_PAYMENT_NO_CHECK_NUMBER";
    public static final String NOT_POSTED = "FIN_PAYMENT_NOT_POSTED";
    public static final String APPLY_REFUSED = "FIN_AP_PREPAYMENT_APPLY_REFUSED";
    public static final String TOO_MANY = "FIN_PAYMENT_TOO_MANY";
    public static final String OTHER_METHOD = "FIN_PAYMENT_OTHER_METHOD";
    public static final String SENT = "FIN_PAYMENT_SENT";
    public static final String FOREIGN = "FIN_PAYMENT_FOREIGN";

    /** The reasons a bill is held out of a run (FIN-AP-003, 006, 010). */
    public static final String HOLD_NOT_APPROVED = "not approved";
    public static final String HOLD_BANK_PENDING = "bank details pending approval";
    public static final String HOLD_NO_BANK = "no approved bank account";
    public static final String HOLD_VENDOR = "vendor inactive";
    public static final String HOLD_IN_RUN = "in another payment run";
    public static final String HOLD_NOTHING_OPEN = "nothing open";
    public static final String HOLD_CURRENCY = "in another currency";

    /**
     * @param bankCode     the company bank account paid from; the payables settings' default when absent
     * @param method       {@code ACH}, {@code CHECK} or {@code WIRE}
     * @param dueThrough   the last due day of the bills proposed; none for a run that starts empty
     * @param vendorCodes  only these vendors' bills, if given
     * @param takeDiscounts propose bills whose early-payment discount runs to the payment day, and take it
     * @param currency     the bills' currency; US dollars when absent (F7)
     * @param exchangeRate the rate the bank pays a foreign currency at; the payment day's spot rate when absent
     */
    public record ProposeInput(@Size(max = 20) String bankCode, @NotNull LocalDate paymentDate,
        @NotBlank @Size(max = 10) String method, LocalDate dueThrough,
        @Size(max = 500) List<@NotBlank @Size(max = 20) String> vendorCodes, Boolean takeDiscounts,
        @Size(max = 500) String description, @Size(max = 3) String currency,
        @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 10) BigDecimal exchangeRate) {}

    /**
     * A line added to a draft run: a bill ({@code billId}), another payment ({@code payee}, {@code account},
     * {@code amount}) or a prepayment ({@code kind} {@code PREPAYMENT}, {@code vendorCode}, {@code amount}).
     */
    public record AddInput(@NotNull UUID runId, @Size(max = 15) String kind, UUID billId,
        @Size(max = 20) String vendorCode, @Size(max = 200) String payee, @Size(max = 20) String account,
        @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @Size(max = 500) String description) {}

    public record RemoveInput(@NotNull UUID runId, @NotNull UUID lineId) {}

    public record RunId(@NotNull UUID runId) {}

    public record CancelInput(@NotNull UUID runId, @NotBlank @Size(max = 500) String reason) {}

    /** A bill held out of a run, and why. */
    public record Held(String billId, String billNo, String vendorCode, BigDecimal amount, String reason) {}

    /** A payment released: its number, payee, amount and check number. */
    public record Paid(String paymentId, String paymentNo, String vendorCode, String payee, BigDecimal amount,
        String checkNo) {}

    public record RunOutput(String runId, String runNo, String status, BigDecimal total, int lineCount,
        String approvalRequestId, List<Held> held, List<Paid> payments) {}

    /** The platform's approval decision, as its events carry it: a pointer to the request, checked against it. */
    public record ApprovalResultInput(String subject, String entityId, String status, String requestId) {}

    /** A bill a payment pays: the amount settled and the discount taken of it. */
    public record PaidBill(@NotNull UUID billId, String billNo, @NotNull BigDecimal amount, BigDecimal discount) {}

    /** What {@code FIN_PAYMENT_RECORD} posts: one payment of a released run. */
    public record PaymentInput(@NotNull UUID runId, @NotBlank String runNo, @NotBlank String kind, String vendorCode,
        @NotBlank String payee, @NotBlank String bankCode, @NotBlank String bankAccount, @NotBlank String method,
        @NotNull LocalDate paymentDate, String checkNo, UUID vendorBankAccountId, String account, String description,
        @NotNull @Valid List<@NotNull @Valid PaidBill> bills, BigDecimal amount, String currency,
        BigDecimal exchangeRate) {}

    public record PaymentOutput(String paymentId, String paymentNo, BigDecimal amount, String glNo) {}

    public record VoidInput(@NotNull UUID paymentId, @NotNull LocalDate voidDate,
        @NotBlank @Size(max = 500) String reason) {}

    public record VoidOutput(String paymentId, String paymentNo, String status, String voidGlNo,
        List<String> billsReopened) {}

    public record PrepaymentApplyInput(@NotNull UUID paymentId, @NotNull UUID billId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @NotNull LocalDate applicationDate) {}

    public record PrepaymentApplyOutput(String applicationId, BigDecimal billOpen, BigDecimal prepaymentOpen,
        String glNo) {}

    /** The most rows a payment process reads of one kind; more is refused rather than cut short. */
    static final int MAX_ROWS = 5000;
    static final int MAX_TERMS = 500;

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String RUN_ID = "runId";
    static final String RUN = "run";
    static final String LINES = "lines";
    static final String SETTINGS = "settings";
    static final String BANKS = "banks";
    static final String BILLS = "bills";
    static final String OPEN_RUNS = "openRuns";
    static final String OPEN_LINES = "openLines";
    static final String VENDORS = "vendors";
    static final String VENDOR_BANKS = "vendorBanks";
    static final String TERMS = "terms";
    static final String ACCOUNTS = "accounts";
    static final String PLAN = "plan";
    static final String NUMBER = "number";
    static final String CASE = "case";
    static final String APPROVAL = "approval";
    static final String REQUESTS = "requests";
    static final String DECISIONS = "decisions";
    static final String PAYMENT_INPUTS = "paymentInputs";
    static final String PAYMENTS = "payments";
    static final String PAYMENT = "payment";
    static final String POSTINGS = "postings";
    static final String FILES = "files";
    static final String BILL_LINES = "billLines";
    static final String AMOUNTS_1099 = "amounts1099";
    static final String APPLICATIONS = "applications";
    static final String SUB_INPUT = "subledgerInput";
    static final String SUB_OUTPUT = "subledgerOutput";
    static final String NEW_ID = "newId";
    static final String FX_SETTINGS = "fxSettings";
    static final String FX_RATES = "fxRates";
    static final String SETTLED = "settled";
    static final String RATE = "rate";

    // ---- propose ---------------------------------------------------------------------------------------------------

    /** A proposal: the lines it takes and the bills it holds. */
    record Plan(List<Map<String, Object>> lines, List<Held> held) {}

    public static final ProcessDefinition<ProposeInput, RunOutput, ProcessContext> PROPOSE_PROCESS =
        ProcessDefinition.define(PROPOSE, 1, ProposeInput.class, RunOutput.class, ProcessContext.class, pb -> pb
            .description("Proposes a payment run of the bills due by a day; holds what may not be paid, with why.")
            .permissions(FinancePermissions.PAYMENT_PREPARE)
            .contextFactory(PaymentProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, RunOutput.class))
            .step("Load the settings", QueryEntities.of(ApEntities.SETTINGS_DATASET,
                ctx -> ApSettingsProcesses.current(), SETTINGS))
            .step("Load the bank account", QueryEntities.of(BankEntities.BANK_ACCOUNT_DATASET,
                ctx -> VendorProcesses.eq("bankCode", bankCode(ctx, ctx.get(INPUT, ProposeInput.class).bankCode())),
                BANKS))
            .step("Load the payment terms", QueryEntities.of(ArEntities.PAYMENT_TERMS_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("active", true)).limit(MAX_TERMS).build(),
                TERMS))
            .step("Load the foreign currency settings", QueryEntities.of(FxEntities.SETTINGS_DATASET,
                ctx -> FxSettingsProcesses.current(), FX_SETTINGS))
            .step("Load the exchange rate", QueryEntities.of(GlEntities.EXCHANGE_RATE_DATASET,
                ctx -> FxRates.spot(runCurrency(ctx.get(INPUT, ProposeInput.class).currency()),
                    ctx.get(INPUT, ProposeInput.class).paymentDate(), first(ctx, FX_SETTINGS)), FX_RATES))
            .step("Load the open bills", QueryEntities.of(BillEntities.BILL_DATASET, ctx -> {
                ProposeInput input = ctx.get(INPUT, ProposeInput.class);
                if (input.dueThrough() == null) {
                    return EntityQuery.builder().where(new QueryPredicate.In("billId", List.of())).limit(1).build();
                }
                // Due by the day, or (taking discounts) dated recently enough for a discount to run to the payment.
                QueryPredicate due = new QueryPredicate.Lte("dueDate", input.dueThrough());
                if (Boolean.TRUE.equals(input.takeDiscounts())) {
                    int days = list(ctx, TERMS).stream().filter(t -> t.get("discountDays") != null)
                        .mapToInt(t -> t.<BigDecimal>get("discountDays").intValueExact()).max().orElse(0);
                    due = new QueryPredicate.Or(List.of(due, new QueryPredicate.Gte("invoiceDate",
                        input.paymentDate().minusDays(days))));
                }
                List<QueryPredicate> where = new ArrayList<>(List.of(
                    new QueryPredicate.Eq("status", BillEntities.POSTED),
                    new QueryPredicate.Eq("kind", BillEntities.BILL_KIND),
                    new QueryPredicate.Eq("currency", runCurrency(input.currency())),
                    new QueryPredicate.Ne("openAmount", BigDecimal.ZERO), due));
                if (input.vendorCodes() != null && !input.vendorCodes().isEmpty()) {
                    where.add(new QueryPredicate.In("vendorCode", new ArrayList<>(input.vendorCodes().stream()
                        .map(VendorProcesses::code).toList())));
                }
                return EntityQuery.builder().where(new QueryPredicate.And(where)).limit(MAX_ROWS).build();
            }, BILLS))
            .step("Load the open runs", QueryEntities.of(PaymentEntities.RUN_DATASET, ctx -> openRuns(), OPEN_RUNS))
            .step("Load their lines", QueryEntities.of(PaymentEntities.LINE_DATASET,
                ctx -> linesOfRuns(list(ctx, OPEN_RUNS)), OPEN_LINES))
            .step("Load the vendors", QueryEntities.of(ApEntities.VENDOR_DATASET,
                ctx -> byField("vendorCode", vendorsOf(list(ctx, BILLS))), VENDORS))
            .step("Load their bank accounts", QueryEntities.of(ApEntities.VENDOR_BANK_DATASET,
                ctx -> vendorBanks(vendorsOf(list(ctx, BILLS))), VENDOR_BANKS))
            .compute("Propose", (metadata, ctx) -> propose(ctx))
            .step("Number the run", AssignNumber.when(ctx -> ctx.contains(PLAN), RUN_NUMBERS, null, NUMBER))
            .compute("Record the run", (metadata, ctx) -> recordProposal(ctx)));

    static void propose(ProcessContext ctx) {
        ProposeInput input = ctx.get(INPUT, ProposeInput.class);
        String method = method(ctx, input.method());
        EntityInstance bank = bank(ctx, input.bankCode());
        String currency = runCurrency(input.currency());
        BigDecimal rate = FxRates.rate(currency, input.exchangeRate(), list(ctx, FX_RATES));
        if (!ApFx.dollars(currency)) {
            if (!foreignAllowed(method)) {
                ctx.reject(new Violation("method", FOREIGN, "Bills in " + currency + " are paid by wire or outside "
                    + "the bank files (MANUAL), not by " + method, Map.of("currency", currency,
                    "method", String.valueOf(method))));
            }
            if (rate == null) {
                ctx.reject(FxRates.missing("exchangeRate", currency, input.paymentDate(), first(ctx, FX_SETTINGS)));
            }
        }
        // The datasets cut a query at its limit without a word: a full result may be missing rows that matter.
        if (list(ctx, BILLS).size() >= MAX_ROWS || list(ctx, OPEN_LINES).size() >= MAX_ROWS
            || list(ctx, TERMS).size() >= MAX_TERMS) {
            ctx.reject(new Violation("dueThrough", TOO_MANY, "More than " + MAX_ROWS + " bills or open lines to "
                + "look at: propose for fewer vendors or an earlier day", Map.of("limit", MAX_ROWS)));
        }
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, String> inRun = runsOfBills(ctx, null);
        // Discounts are taken in US dollars only, as on receipts (F7a).
        boolean discounts = Boolean.TRUE.equals(input.takeDiscounts()) && ApFx.dollars(currency);
        List<Map<String, Object>> lines = new ArrayList<>();
        List<Held> held = new ArrayList<>();
        List<EntityInstance> bills = new ArrayList<>(list(ctx, BILLS));
        bills.sort(Comparator.comparing((EntityInstance b) -> (String) b.get("vendorCode"))
            .thenComparing(b -> (LocalDate) b.get("dueDate")).thenComparing(b -> (String) b.get("billNo")));
        for (EntityInstance bill : bills) {
            LocalDate due = bill.get("dueDate");
            BigDecimal discount = discount(ctx, bill, input.paymentDate(), discounts);
            boolean dueNow = due != null && !due.isAfter(input.dueThrough());
            if (!dueNow && discount.signum() == 0) {
                continue;
            }
            String reason = hold(ctx, bill, method, currency, inRun, null);
            BigDecimal open = bill.get("openAmount");
            if (reason != null) {
                held.add(new Held(String.valueOf(bill.id()), bill.get("billNo"), bill.get("vendorCode"), open,
                    reason));
                continue;
            }
            lines.add(billLine(bill, payee(ctx, bill.get("vendorCode")), open, discount));
        }
        ctx.put(PLAN, new Plan(lines, List.copyOf(held)));
        ctx.put(BANKS, List.of(bank));
        ctx.put(RATE, rate);
    }

    static void recordProposal(ProcessContext ctx) {
        if (!ctx.contains(PLAN)) {
            return;
        }
        ProposeInput input = ctx.get(INPUT, ProposeInput.class);
        Plan plan = ctx.get(PLAN, Plan.class);
        EntityInstance bank = list(ctx, BANKS).getFirst();
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("runNo", ctx.get(NUMBER, String.class));
        run.put("bankCode", bank.get("bankCode"));
        run.put("paymentDate", input.paymentDate());
        run.put("method", VendorProcesses.code(input.method()));
        run.put("description", VendorProcesses.trim(input.description()));
        run.put("currency", runCurrency(input.currency()));
        run.put("exchangeRate", ctx.get(RATE));
        run.put("status", PaymentEntities.DRAFT);
        run.put("total", total(plan.lines()));
        run.put("lineCount", BigDecimal.valueOf(plan.lines().size()));
        run.put("preparedBy", ctx.request().actorId());
        Object id = ctx.changes().insert(PaymentEntities.RUN, run);
        for (Map<String, Object> line : plan.lines()) {
            line.put("runId", id);
            ctx.changes().insert(PaymentEntities.LINE, line);
        }
        ctx.put(OUTPUT, new RunOutput(String.valueOf(id), ctx.get(NUMBER, String.class), PaymentEntities.DRAFT,
            total(plan.lines()), plan.lines().size(), null, plan.held(), List.of()));
    }

    // ---- add and remove lines --------------------------------------------------------------------------------------

    public static final ProcessDefinition<AddInput, RunOutput, ProcessContext> ADD_PROCESS =
        ProcessDefinition.define(ADD, 1, AddInput.class, RunOutput.class, ProcessContext.class, pb -> pb
            .description("Adds a bill, another payment or a prepayment to a draft payment run.")
            .permissions(FinancePermissions.PAYMENT_PREPARE)
            .contextFactory((start, input) -> withRun(start, input, input.runId()))
            .outputMapper(ctx -> ctx.get(OUTPUT, RunOutput.class))
            .step("Load the run", LoadEntity.by(PaymentEntities.RUN_DATASET, RUN_ID, RUN))
            .step("Load its lines", QueryEntities.of(PaymentEntities.LINE_DATASET,
                ctx -> linesOfRuns(List.of(run(ctx))), LINES))
            .step("Load the settings", QueryEntities.of(ApEntities.SETTINGS_DATASET,
                ctx -> ApSettingsProcesses.current(), SETTINGS))
            .step("Load the bill", QueryEntities.of(BillEntities.BILL_DATASET,
                ctx -> BillProcesses.byIds(ctx.get(INPUT, AddInput.class).billId()), BILLS))
            .step("Load the open runs", QueryEntities.of(PaymentEntities.RUN_DATASET, ctx -> openRuns(), OPEN_RUNS))
            .step("Load their lines", QueryEntities.of(PaymentEntities.LINE_DATASET,
                ctx -> linesOfRuns(list(ctx, OPEN_RUNS)), OPEN_LINES))
            .step("Load the vendor", QueryEntities.of(ApEntities.VENDOR_DATASET, ctx -> byField("vendorCode",
                addVendors(ctx)), VENDORS))
            .step("Load its bank accounts", QueryEntities.of(ApEntities.VENDOR_BANK_DATASET,
                ctx -> vendorBanks(addVendors(ctx)), VENDOR_BANKS))
            .step("Load the account", QueryEntities.of(GlEntities.ACCOUNT_DATASET,
                ctx -> VendorProcesses.eq("accountCode", VendorProcesses.trim(ctx.get(INPUT, AddInput.class)
                    .account())), ACCOUNTS))
            .compute("Add the line", (metadata, ctx) -> add(ctx)));

    static void add(ProcessContext ctx) {
        AddInput input = ctx.get(INPUT, AddInput.class);
        EntityInstance run = run(ctx);
        if (!PaymentEntities.DRAFT.equals(run.get("status"))) {
            ctx.reject(notDraft(run));
            return;
        }
        String kind = input.kind() == null || input.kind().isBlank()
            ? (input.billId() != null ? PaymentEntities.BILL_LINE : PaymentEntities.OTHER_LINE)
            : VendorProcesses.code(input.kind());
        if (list(ctx, LINES).size() >= MAX_ROWS - 1) {
            ctx.reject(new Violation("runId", TOO_MANY, run.get("runNo") + " has " + MAX_ROWS + " lines: start "
                + "another run", Map.of("limit", MAX_ROWS)));
            return;
        }
        Map<String, Object> line;
        switch (kind) {
            case PaymentEntities.BILL_LINE -> {
                EntityInstance bill = list(ctx, BILLS).isEmpty() ? null : list(ctx, BILLS).getFirst();
                if (bill == null || !BillEntities.POSTED.equals(bill.get("status"))
                    || !BillEntities.BILL_KIND.equals(bill.get("kind"))) {
                    ctx.reject(new Violation("billId", INVALID_VALUE, "Only a posted bill is paid",
                        Map.of("field", "billId", "value", String.valueOf(input.billId()))));
                    return;
                }
                String reason = hold(ctx, bill, run.get("method"), ApFx.currency(run), runsOfBills(ctx, null), null);
                if (reason != null) {
                    ctx.reject(new Violation("billId", HELD, bill.get("billNo") + " is held: " + reason,
                        Map.of("billNo", (Object) bill.get("billNo"), "reason", reason)));
                    return;
                }
                BigDecimal open = bill.get("openAmount");
                BigDecimal amount = input.amount() == null ? open : input.amount();
                if (amount.compareTo(open) > 0) {
                    ctx.reject(new Violation("amount", INVALID_VALUE, "More than is open on " + bill.get("billNo"),
                        Map.of("field", "amount", "value", amount.toPlainString())));
                    return;
                }
                line = billLine(bill, payee(ctx, bill.get("vendorCode")), amount, BigDecimal.ZERO);
            }
            case PaymentEntities.OTHER_LINE -> {
                if (!ApFx.dollars(ApFx.currency(run))) {
                    ctx.reject(foreignLine(run));
                    return;
                }
                if (!otherAllowed(run.get("method"))) {
                    ctx.reject(otherMethod(run));
                    return;
                }
                String account = VendorProcesses.trim(input.account());
                EntityInstance found = list(ctx, ACCOUNTS).isEmpty() ? null : list(ctx, ACCOUNTS).getFirst();
                // A tax or another liability settled in cash: never a control account, whose subledger pays it.
                if (found == null || found.get("controlClass") != null) {
                    ctx.reject(new Violation("account", ACCOUNT, "Another payment is to an account that is no control "
                        + "account; " + account + " is not", Map.of("accountCode", String.valueOf(account))));
                    return;
                }
                if (VendorProcesses.trim(input.payee()) == null || input.amount() == null) {
                    ctx.reject(new Violation("payee", INVALID_VALUE, "Another payment names its payee and amount",
                        Map.of("field", "payee", "value", "")));
                    return;
                }
                line = new LinkedHashMap<>();
                line.put("kind", PaymentEntities.OTHER_LINE);
                line.put("payee", input.payee().trim());
                line.put("account", account);
                line.put("description", VendorProcesses.trim(input.description()));
                line.put("amount", input.amount());
            }
            case PaymentEntities.PREPAYMENT_LINE -> {
                if (!ApFx.dollars(ApFx.currency(run))) {
                    ctx.reject(foreignLine(run));
                    return;
                }
                String vendorCode = VendorProcesses.code(input.vendorCode());
                EntityInstance vendor = list(ctx, VENDORS).isEmpty() ? null : list(ctx, VENDORS).getFirst();
                EntityInstance settings = list(ctx, SETTINGS).isEmpty() ? null : list(ctx, SETTINGS).getFirst();
                if (settings == null || settings.get("prepaymentAccount") == null) {
                    ctx.reject(new Violation("kind", NO_SETTINGS, "The payables settings name no prepayment account",
                        Map.of()));
                    return;
                }
                String reason = vendor == null ? HOLD_VENDOR : vendorHold(ctx, vendor, run.get("method"));
                if (reason != null || input.amount() == null) {
                    ctx.reject(new Violation("vendorCode", HELD, "A prepayment to " + vendorCode + " is held: "
                        + (reason == null ? "no amount" : reason), Map.of("billNo", "prepayment",
                        "reason", reason == null ? "no amount" : reason)));
                    return;
                }
                line = new LinkedHashMap<>();
                line.put("kind", PaymentEntities.PREPAYMENT_LINE);
                line.put("vendorCode", vendorCode);
                line.put("payee", vendor.get("legalName"));
                line.put("account", settings.get("prepaymentAccount"));
                line.put("description", VendorProcesses.trim(input.description()));
                line.put("amount", input.amount());
            }
            default -> {
                ctx.reject(new Violation("kind", INVALID_VALUE, "kind must be one of "
                    + PaymentEntities.LINE_KIND_VALUES, Map.of("field", "kind", "value", kind)));
                return;
            }
        }
        line.put("runId", run.id());
        ctx.changes().insert(PaymentEntities.LINE, line);
        List<Map<String, Object>> all = new ArrayList<>(list(ctx, LINES).stream().map(EntityInstance::attributes)
            .toList());
        all.add(line);
        updateRun(ctx, run, all);
    }

    public static final ProcessDefinition<RemoveInput, RunOutput, ProcessContext> REMOVE_PROCESS =
        ProcessDefinition.define(REMOVE, 1, RemoveInput.class, RunOutput.class, ProcessContext.class, pb -> pb
            .description("Removes a line from a draft payment run.")
            .permissions(FinancePermissions.PAYMENT_PREPARE)
            .contextFactory((start, input) -> withRun(start, input, input.runId()))
            .outputMapper(ctx -> ctx.get(OUTPUT, RunOutput.class))
            .step("Load the run", LoadEntity.by(PaymentEntities.RUN_DATASET, RUN_ID, RUN))
            .step("Load its lines", QueryEntities.of(PaymentEntities.LINE_DATASET,
                ctx -> linesOfRuns(List.of(run(ctx))), LINES))
            .compute("Remove the line", (metadata, ctx) -> {
                RemoveInput input = ctx.get(INPUT, RemoveInput.class);
                EntityInstance run = run(ctx);
                if (!PaymentEntities.DRAFT.equals(run.get("status"))) {
                    ctx.reject(notDraft(run));
                    return;
                }
                EntityInstance line = list(ctx, LINES).stream().filter(l -> input.lineId().equals(uuid(l.id())))
                    .findFirst().orElse(null);
                if (line == null) {
                    ctx.reject(new Violation("lineId", INVALID_VALUE, "The run has no such line",
                        Map.of("field", "lineId", "value", input.lineId().toString())));
                    return;
                }
                ctx.changes().delete(PaymentEntities.LINE, line.id(), line.version());
                updateRun(ctx, run, list(ctx, LINES).stream().filter(l -> l != line).map(EntityInstance::attributes)
                    .toList());
            }));

    /** The run's total and count after a change, and its preparer: whoever changed it last. */
    private static void updateRun(ProcessContext ctx, EntityInstance run, List<Map<String, Object>> lines) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("total", total(lines));
        values.put("lineCount", BigDecimal.valueOf(lines.size()));
        values.put("preparedBy", ctx.request().actorId());
        ctx.changes().update(PaymentEntities.RUN, run.id(), run.version(), values);
        ctx.put(OUTPUT, output(run, values, List.of(), List.of()));
    }

    // ---- submit, approve, cancel -----------------------------------------------------------------------------------

    public static final ProcessDefinition<RunId, RunOutput, ProcessContext> SUBMIT_PROCESS =
        ProcessDefinition.define(SUBMIT, 1, RunId.class, RunOutput.class, ProcessContext.class, pb -> pb
            .description("Submits a payment run for approval; it no longer changes.")
            .permissions(FinancePermissions.PAYMENT_PREPARE)
            .actsOn(PaymentEntities.RUN, "runId", a -> a.whenField("status", PaymentEntities.DRAFT))
            .contextFactory((start, input) -> withRun(start, input, input.runId()))
            .outputMapper(ctx -> ctx.get(OUTPUT, RunOutput.class))
            .step("Load the run", LoadEntity.by(PaymentEntities.RUN_DATASET, RUN_ID, RUN))
            .step("Load its lines", QueryEntities.of(PaymentEntities.LINE_DATASET,
                ctx -> linesOfRuns(List.of(run(ctx))), LINES))
            .compute("Check it", (metadata, ctx) -> {
                EntityInstance run = run(ctx);
                if (!PaymentEntities.DRAFT.equals(run.get("status"))) {
                    ctx.reject(notDraft(run));
                    return;
                }
                if (!Objects.equals(ctx.request().actorId(), run.get("preparedBy"))) {
                    ctx.reject(new Violation("runId", NOT_PREPARER, "A run is submitted by whoever changed it last",
                        Map.of()));
                    return;
                }
                if (list(ctx, LINES).isEmpty()) {
                    ctx.reject(new Violation("runId", EMPTY, run.get("runNo") + " pays nothing",
                        Map.of("runNo", (Object) run.get("runNo"))));
                    return;
                }
                Map<String, Object> content = content(run, list(ctx, LINES));
                Map<String, Object> facts = new LinkedHashMap<>();
                // In US dollars, as the rules' limits are: at the run's rate.
                facts.put("amount", BillPosting.usd(run.get("total"), ApFx.rate(run)));
                facts.put("method", run.get("method"));
                facts.put("bankCode", run.get("bankCode"));
                ctx.put(CASE, ApprovalCase.of(run.id(), facts, content).preparedBy(run.get("preparedBy")));
            })
            .step("Ask for approval", RequireApproval.when(ctx -> ctx.contains(CASE), SUBJECT,
                ctx -> ctx.get(CASE, ApprovalCase.class), APPROVAL))
            .compute("Record it", (metadata, ctx) -> {
                if (!ctx.contains(APPROVAL)) {
                    return;
                }
                EntityInstance run = run(ctx);
                ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
                if (approval.status() == ApprovalOutcome.Status.NOT_REQUIRED) {
                    // A run is never paid unapproved: a rule must name its approvers (FIN-AP-011).
                    ctx.reject(new Violation("runId", NO_RULE, "No approval rule of payment runs applies: a "
                        + "controller sets one before anything is paid", Map.of()));
                    return;
                }
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("status", approval.status() == ApprovalOutcome.Status.APPROVED ? PaymentEntities.APPROVED
                    : PaymentEntities.SUBMITTED);
                values.put("approvalRequestId", approval.requestId());
                values.put("contentHash", ContentHash.of(content(run, list(ctx, LINES))));
                ctx.changes().update(PaymentEntities.RUN, run.id(), run.version(), values);
                ctx.put(OUTPUT, output(run, values, List.of(), List.of()));
            }));

    public static final ProcessDefinition<ApprovalResultInput, RunOutput, ProcessContext> APPROVAL_RESULT_PROCESS =
        ProcessDefinition.define(APPROVAL_RESULT, 1, ApprovalResultInput.class, RunOutput.class,
            ProcessContext.class, pb -> pb
                .description("Locks an approved payment run, or makes a rejected one a draft again.")
                .permissions(FinancePermissions.AP_INTERNAL)
                .internal()
                .contextFactory(PaymentProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, RunOutput.class))
                .step("Load the run", QueryEntities.of(PaymentEntities.RUN_DATASET, ctx -> {
                    ApprovalResultInput input = ctx.get(INPUT, ApprovalResultInput.class);
                    UUID id = SUBJECT.equals(input.subject()) ? safeUuid(input.entityId()) : null;
                    return EntityQuery.builder().where(new QueryPredicate.In("runId", id == null ? List.of()
                        : List.of(id))).limit(1).build();
                }, OPEN_RUNS))
                .step("Load the approval request", QueryEntities.of(ApprovalEntities.REQUEST_DATASET,
                    ctx -> byRequest("requestId", ctx), REQUESTS))
                .step("Load its decisions", QueryEntities.of(ApprovalEntities.DECISION_DATASET,
                    ctx -> byRequest("requestId", ctx), DECISIONS))
                .compute("Mark it", (metadata, ctx) -> {
                    ApprovalResultInput input = ctx.get(INPUT, ApprovalResultInput.class);
                    EntityInstance run = list(ctx, OPEN_RUNS).isEmpty() ? null : list(ctx, OPEN_RUNS).getFirst();
                    EntityInstance request = list(ctx, REQUESTS).isEmpty() ? null : list(ctx, REQUESTS).getFirst();
                    ctx.put(OUTPUT, new RunOutput(run == null ? null : String.valueOf(run.id()), run == null ? null
                        : run.get("runNo"), run == null ? null : run.get("status"), null, 0, input.requestId(),
                        List.of(), List.of()));
                    if (run == null || request == null || !PaymentEntities.SUBMITTED.equals(run.get("status"))
                        || !Objects.equals(input.requestId(), run.get("approvalRequestId"))
                        || !SUBJECT.equals(request.get("subject"))
                        || !String.valueOf(run.id()).equals(request.get("entityId"))
                        || !Objects.equals(run.get("contentHash"), request.get("contentHash"))) {
                        return;
                    }
                    String decided = request.get("status");
                    Map<String, Object> values = new LinkedHashMap<>();
                    if (ApprovalEntities.APPROVED.equals(decided)) {
                        values.put("status", PaymentEntities.APPROVED);
                        values.put("approvedBy", list(ctx, DECISIONS).stream()
                            .max(Comparator.comparing(d -> new BigDecimal(String.valueOf((Object) d.get("levelNo")))))
                            .map(d -> String.valueOf((Object) d.get("approverId"))).orElse(null));
                    } else if (ApprovalEntities.REJECTED.equals(decided)) {
                        values.put("status", PaymentEntities.DRAFT);
                        values.put("approvalRequestId", null);
                        values.put("contentHash", null);
                    } else {
                        return;
                    }
                    ctx.changes().update(PaymentEntities.RUN, run.id(), run.version(), values);
                    ctx.put(OUTPUT, output(run, values, List.of(), List.of()));
                }));

    public static final ProcessDefinition<CancelInput, RunOutput, ProcessContext> CANCEL_PROCESS =
        ProcessDefinition.define(CANCEL, 1, CancelInput.class, RunOutput.class, ProcessContext.class, pb -> pb
            .description("Gives up a payment run that is not released.")
            .permissions(FinancePermissions.PAYMENT_PREPARE)
            .contextFactory((start, input) -> withRun(start, input, input.runId()))
            .outputMapper(ctx -> ctx.get(OUTPUT, RunOutput.class))
            .step("Load the run", LoadEntity.by(PaymentEntities.RUN_DATASET, RUN_ID, RUN))
            .compute("Cancel it", (metadata, ctx) -> {
                EntityInstance run = run(ctx);
                if (List.of(PaymentEntities.RELEASED, PaymentEntities.CANCELLED).contains(run.get("status"))) {
                    ctx.reject(new Violation("runId", NOT_DRAFT, run.get("runNo") + " is " + run.get("status"),
                        Map.of("runNo", (Object) run.get("runNo"), "status", (Object) run.get("status"))));
                    return;
                }
                Map<String, Object> values = Map.of("status", PaymentEntities.CANCELLED, "cancelReason",
                    ctx.get(INPUT, CancelInput.class).reason().trim());
                ctx.changes().update(PaymentEntities.RUN, run.id(), run.version(), values);
                ctx.put(OUTPUT, output(run, values, List.of(), List.of()));
            })
            .step("Withdraw its approval request", WithdrawApproval.of(SUBJECT, ctx -> ctx.contains(OUTPUT)
                && PaymentEntities.SUBMITTED.equals(run(ctx).get("status")) ? run(ctx).id() : "none")));

    // ---- release ---------------------------------------------------------------------------------------------------

    public static final ProcessDefinition<RunId, RunOutput, ProcessContext> RELEASE_PROCESS =
        ProcessDefinition.define(RELEASE, 1, RunId.class, RunOutput.class, ProcessContext.class, pb -> pb
            .description("Releases an approved payment run to the bank: posts its payments.")
            .permissions(FinancePermissions.PAYMENT_RELEASE)
            .requiresMfa(MfaRequirement.ALWAYS)
            .actsOn(PaymentEntities.RUN, "runId", a -> a.whenField("status", PaymentEntities.APPROVED))
            .contextFactory((start, input) -> withRun(start, input, input.runId()))
            .outputMapper(ctx -> ctx.get(OUTPUT, RunOutput.class))
            .step("Load the run", LoadEntity.by(PaymentEntities.RUN_DATASET, RUN_ID, RUN))
            .step("Load its lines", QueryEntities.of(PaymentEntities.LINE_DATASET,
                ctx -> linesOfRuns(List.of(run(ctx))), LINES))
            .step("Load the settings", QueryEntities.of(ApEntities.SETTINGS_DATASET,
                ctx -> ApSettingsProcesses.current(), SETTINGS))
            .step("Load the bank account", QueryEntities.of(BankEntities.BANK_ACCOUNT_DATASET,
                ctx -> VendorProcesses.eq("bankCode", run(ctx).get("bankCode")), BANKS))
            .step("Load the bills", QueryEntities.of(BillEntities.BILL_DATASET, ctx -> BillProcesses.byIds(
                list(ctx, LINES).stream().map(l -> uuid(l.get("billId"))).filter(Objects::nonNull)
                    .toArray(UUID[]::new)), BILLS))
            .step("Load the open runs", QueryEntities.of(PaymentEntities.RUN_DATASET, ctx -> openRuns(), OPEN_RUNS))
            .step("Load their lines", QueryEntities.of(PaymentEntities.LINE_DATASET,
                ctx -> linesOfRuns(list(ctx, OPEN_RUNS)), OPEN_LINES))
            .step("Load the vendors", QueryEntities.of(ApEntities.VENDOR_DATASET,
                ctx -> byField("vendorCode", lineVendors(ctx)), VENDORS))
            .step("Load their bank accounts", QueryEntities.of(ApEntities.VENDOR_BANK_DATASET,
                ctx -> vendorBanks(lineVendors(ctx)), VENDOR_BANKS))
            .compute("Check it again", (metadata, ctx) -> planRelease(ctx))
            .step("Post the payments", CallProcess.forEach(RECORD, 1,
                ctx -> ctx.contains(PAYMENT_INPUTS) ? (List<?>) ctx.get(PAYMENT_INPUTS) : List.of(), PAYMENTS))
            .compute("Record the release", (metadata, ctx) -> recordRelease(ctx)));

    static void planRelease(ProcessContext ctx) {
        EntityInstance run = run(ctx);
        if (!PaymentEntities.APPROVED.equals(run.get("status"))) {
            ctx.reject(new Violation("runId", NOT_APPROVED, run.get("runNo") + " is " + run.get("status")
                + ": only an approved run is released", Map.of("runNo", (Object) run.get("runNo"),
                "status", (Object) run.get("status"))));
            return;
        }
        if (!Objects.equals(run.get("contentHash"), ContentHash.of(content(run, list(ctx, LINES))))) {
            ctx.reject(new Violation("runId", CHANGED, run.get("runNo") + " is not as it was approved",
                Map.of("runNo", (Object) run.get("runNo"))));
            return;
        }
        EntityInstance settings = list(ctx, SETTINGS).isEmpty() ? null : list(ctx, SETTINGS).getFirst();
        EntityInstance bank = list(ctx, BANKS).isEmpty() ? null : list(ctx, BANKS).getFirst();
        if (settings == null) {
            ctx.reject(new Violation("runId", NO_SETTINGS, "The payables settings are not set", Map.of()));
            return;
        }
        if (bank == null || !Boolean.TRUE.equals(bank.get("active"))) {
            ctx.reject(new Violation("runId", UNKNOWN_BANK, "There is no active bank account "
                + run.get("bankCode"), Map.of("bankCode", (Object) run.get("bankCode"))));
            return;
        }
        String method = run.get("method");
        Map<String, String> inOtherRuns = runsOfBills(ctx, run.id());
        Map<String, EntityInstance> vendors = new LinkedHashMap<>();
        list(ctx, VENDORS).forEach(v -> vendors.put(v.get("vendorCode"), v));
        Map<String, List<PaidBill>> byVendor = new LinkedHashMap<>();
        // The payee as approved with the run, not as the vendor is named now.
        Map<String, String> payees = new LinkedHashMap<>();
        List<PaymentInput> payments = new ArrayList<>();
        List<EntityInstance> lines = new ArrayList<>(list(ctx, LINES));
        lines.sort(Comparator.comparing((EntityInstance l) -> String.valueOf((Object) l.get("vendorCode")))
            .thenComparing(l -> String.valueOf((Object) l.get("billNo"))));
        for (EntityInstance line : lines) {
            if (PaymentEntities.BILL_LINE.equals(line.get("kind"))) {
                EntityInstance bill = list(ctx, BILLS).stream().filter(b -> Objects.equals(uuid(b.id()),
                    uuid(line.get("billId")))).findFirst().orElse(null);
                BigDecimal amount = line.get("amount");
                String reason = bill == null || !BillEntities.POSTED.equals(bill.get("status")) ? "not posted"
                    : amount.compareTo(bill.get("openAmount")) > 0 ? "paid or credited since"
                    : hold(ctx, bill, method, ApFx.currency(run), inOtherRuns, run.id());
                if (reason != null) {
                    ctx.reject(new Violation("runId", HELD, line.get("billNo") + " is held: " + reason,
                        Map.of("billNo", (Object) line.get("billNo"), "reason", reason)));
                    continue;
                }
                payees.putIfAbsent(bill.get("vendorCode"), line.get("payee"));
                byVendor.computeIfAbsent(bill.get("vendorCode"), v -> new ArrayList<>()).add(new PaidBill(
                    uuid(bill.id()), bill.get("billNo"), amount, line.get("discount")));
            } else if (PaymentEntities.PREPAYMENT_LINE.equals(line.get("kind"))) {
                EntityInstance vendor = vendors.get(line.get("vendorCode"));
                String reason = vendor == null ? HOLD_VENDOR : vendorHold(ctx, vendor, method);
                if (reason != null) {
                    ctx.reject(new Violation("runId", HELD, "The prepayment to " + line.get("vendorCode")
                        + " is held: " + reason, Map.of("billNo", "prepayment", "reason", reason)));
                    continue;
                }
                payments.add(new PaymentInput(uuid(run.id()), run.get("runNo"), PaymentEntities.PREPAYMENT_LINE,
                    vendor.get("vendorCode"), line.get("payee"), bank.get("bankCode"), bank.get("glAccount"),
                    method, run.get("paymentDate"), null, activeBank(ctx, vendor.get("vendorCode")),
                    line.get("account"), line.get("description"), List.of(), line.get("amount"),
                    ApFx.currency(run), ApFx.rate(run)));
            } else {
                if (!otherAllowed(method)) {
                    ctx.reject(otherMethod(run));
                    continue;
                }
                payments.add(new PaymentInput(uuid(run.id()), run.get("runNo"), PaymentEntities.OTHER_LINE, null,
                    line.get("payee"), bank.get("bankCode"), bank.get("glAccount"), method, run.get("paymentDate"),
                    null, null, line.get("account"), line.get("description"), List.of(), line.get("amount"),
                    ApFx.currency(run), ApFx.rate(run)));
            }
        }
        if (ctx.hasViolations()) {
            return;
        }
        List<PaymentInput> vendorPayments = new ArrayList<>();
        byVendor.forEach((vendorCode, bills) -> {
            vendorPayments.add(new PaymentInput(uuid(run.id()), run.get("runNo"), PaymentEntities.BILL_LINE,
                vendorCode, payees.get(vendorCode), bank.get("bankCode"), bank.get("glAccount"), method,
                run.get("paymentDate"), null, activeBank(ctx, vendorCode), null, null, List.copyOf(bills), null,
                ApFx.currency(run), ApFx.rate(run)));
        });
        vendorPayments.addAll(payments);
        if ("CHECK".equals(method)) {
            BigDecimal next = bank.get("nextCheckNo");
            if (next == null) {
                ctx.reject(new Violation("runId", NO_CHECK_NUMBER, "The bank account " + bank.get("bankCode")
                    + " has no next check number", Map.of("bankCode", (Object) bank.get("bankCode"))));
                return;
            }
            // Checks are numbered from the stock in the order the payments are made.
            long number = next.longValueExact();
            List<PaymentInput> numbered = new ArrayList<>();
            for (PaymentInput payment : vendorPayments) {
                numbered.add(new PaymentInput(payment.runId(), payment.runNo(), payment.kind(), payment.vendorCode(),
                    payment.payee(), payment.bankCode(), payment.bankAccount(), payment.method(),
                    payment.paymentDate(), String.valueOf(number++), payment.vendorBankAccountId(), payment.account(),
                    payment.description(), payment.bills(), payment.amount(), payment.currency(),
                    payment.exchangeRate()));
            }
            vendorPayments.clear();
            vendorPayments.addAll(numbered);
            ctx.changes().update(BankEntities.BANK_ACCOUNT, bank.id(), bank.version(),
                Map.of("nextCheckNo", BigDecimal.valueOf(number)));
        }
        ctx.put(PAYMENT_INPUTS, List.copyOf(vendorPayments));
    }

    @SuppressWarnings("unchecked")
    static void recordRelease(ProcessContext ctx) {
        if (!ctx.contains(PAYMENT_INPUTS)) {
            return;
        }
        EntityInstance run = run(ctx);
        List<PaymentOutput> outputs = ctx.contains(PAYMENTS) ? (List<PaymentOutput>) ctx.get(PAYMENTS) : List.of();
        List<PaymentInput> inputs = (List<PaymentInput>) ctx.get(PAYMENT_INPUTS);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", PaymentEntities.RELEASED);
        values.put("releasedBy", ctx.request().actorId());
        values.put("releasedTime", ctx.opTime());
        ctx.changes().update(PaymentEntities.RUN, run.id(), run.version(), values);
        List<Paid> paid = new ArrayList<>();
        for (int i = 0; i < outputs.size(); i++) {
            PaymentOutput out = outputs.get(i);
            PaymentInput in = inputs.get(i);
            paid.add(new Paid(out.paymentId(), out.paymentNo(), in.vendorCode(), in.payee(), out.amount(),
                in.checkNo()));
        }
        ctx.put(OUTPUT, output(run, values, List.of(), paid));
    }

    // ---- one payment -----------------------------------------------------------------------------------------------

    public static final ProcessDefinition<PaymentInput, PaymentOutput, ProcessContext> RECORD_PROCESS =
        ProcessDefinition.define(RECORD, 1, PaymentInput.class, PaymentOutput.class, ProcessContext.class, pb -> pb
            .description("Posts one payment of a released payment run.")
            .permissions(FinancePermissions.AP_INTERNAL)
            .internal()
            .contextFactory(PaymentProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, PaymentOutput.class))
            .step("Load the bills", QueryEntities.of(BillEntities.BILL_DATASET, ctx -> BillProcesses.byIds(
                ctx.get(INPUT, PaymentInput.class).bills().stream().map(PaidBill::billId).toArray(UUID[]::new)),
                BILLS))
            .step("Load the settings", QueryEntities.of(ApEntities.SETTINGS_DATASET,
                ctx -> ApSettingsProcesses.current(), SETTINGS))
            .step("Load the bills' lines", QueryEntities.of(BillEntities.LINE_DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.In("billId", new ArrayList<>(ctx.get(INPUT, PaymentInput.class).bills()
                    .stream().map(b -> (Object) b.billId()).toList()))).limit(MAX_ROWS).build(), BILL_LINES))
            .step("Load the vendor", QueryEntities.of(ApEntities.VENDOR_DATASET, ctx -> VendorProcesses.eq(
                "vendorCode", ctx.get(INPUT, PaymentInput.class).vendorCode()), VENDORS))
            .step("Load the foreign currency settings", QueryEntities.of(FxEntities.SETTINGS_DATASET,
                ctx -> FxSettingsProcesses.current(), FX_SETTINGS))
            .step("Number the payment", AssignNumber.of(PAYMENT_NUMBERS, NUMBER))
            .compute("Build the entry", (metadata, ctx) -> buildPayment(ctx))
            // The ledger checks that the source document exists: the payment is saved before it is booked.
            .step("Save the payment", SaveChanges.now())
            .step("Book it", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.POST, 1,
                ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT))
            .compute("Mark the bills paid", (metadata, ctx) -> recordPayment(ctx)));

    static void buildPayment(ProcessContext ctx) {
        PaymentInput input = ctx.get(INPUT, PaymentInput.class);
        EntityInstance settings = list(ctx, SETTINGS).getFirst();
        String number = ctx.get(NUMBER, String.class);
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        for (PaidBill bill : input.bills()) {
            gross = gross.add(bill.amount());
            discount = discount.add(bill.discount() == null ? BigDecimal.ZERO : bill.discount());
        }
        boolean bills = PaymentEntities.BILL_LINE.equals(input.kind());
        BigDecimal cash = bills ? gross.subtract(discount) : input.amount();
        if (discount.signum() != 0 && settings.get("discountAccount") == null) {
            ctx.reject(new Violation("runId", NO_SETTINGS, "The payables settings name no discount account",
                Map.of()));
            return;
        }
        String memo = "Payment " + number + " " + input.payee();
        // The bills give up the dollars they carry; the bank pays the cash at the run's rate (FIN-FX-004).
        BigDecimal rate = input.exchangeRate() == null ? BigDecimal.ONE : input.exchangeRate();
        BigDecimal cashUsd = BillPosting.usd(cash, rate);
        Map<UUID, Settled> settled = settled(ctx, input, cashUsd);
        BigDecimal clearedUsd = settled.values().stream().map(Settled::cleared).reduce(BigDecimal.ZERO,
            BigDecimal::add);
        BigDecimal gainLoss = bills ? clearedUsd.subtract(discount).subtract(cashUsd) : BigDecimal.ZERO;
        EntityInstance fx = first(ctx, FX_SETTINGS);
        if (gainLoss.signum() != 0 && (fx == null || fx.get("realizedAccount") == null)) {
            ctx.reject(new Violation("runId", FxSettingsProcesses.NO_SETTINGS, "The payment's dollars differ from "
                + "the bills' and the foreign currency settings name no account for the exchange gain or loss",
                Map.of()));
            return;
        }
        List<JournalProcesses.LineInput> lines = new ArrayList<>();
        if (bills) {
            lines.add(new JournalProcesses.LineInput(settings.get("payableAccount"), clearedUsd, null, memo, null,
                null));
            if (discount.signum() != 0) {
                lines.add(new JournalProcesses.LineInput(settings.get("discountAccount"), null, discount,
                    "Discounts taken " + number, null, null));
            }
            if (gainLoss.signum() != 0) {
                lines.add(ApFx.realized(fx.get("realizedAccount"), gainLoss, number));
            }
        } else {
            lines.add(new JournalProcesses.LineInput(input.account(), cashUsd, null, memo, null, null));
        }
        lines.add(new JournalProcesses.LineInput(input.bankAccount(), null, cashUsd, memo, null, null));
        ctx.put(SETTLED, settled);
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("paymentNo", number);
        payment.put("runId", input.runId());
        payment.put("runNo", input.runNo());
        payment.put("kind", input.kind());
        payment.put("vendorCode", input.vendorCode());
        payment.put("payee", input.payee());
        payment.put("bankCode", input.bankCode());
        payment.put("method", input.method());
        payment.put("paymentDate", input.paymentDate());
        payment.put("amount", cash);
        payment.put("discount", bills ? discount : null);
        payment.put("currency", input.currency() == null ? FxRates.USD : input.currency());
        payment.put("exchangeRate", rate);
        payment.put("amountUsd", cashUsd);
        payment.put("checkNo", input.checkNo());
        payment.put("vendorBankAccountId", input.vendorBankAccountId());
        payment.put("openAmount", PaymentEntities.PREPAYMENT_LINE.equals(input.kind()) ? cash : null);
        payment.put("status", PaymentEntities.POSTED);
        Object id = ctx.changes().insert(PaymentEntities.PAYMENT, payment);
        ctx.put(NEW_ID, id);
        String description = input.description() != null ? input.description()
            : "Payment " + number + " " + input.payee() + " (" + input.runNo() + ")";
        ctx.put(SUB_INPUT, new SubledgerPosting.PostInput("AP", input.paymentDate(), description, number,
            PaymentEntities.PAYMENT, String.valueOf(id), lines, List.of("AP", "BANK")));
    }

    static void recordPayment(ProcessContext ctx) {
        if (!ctx.contains(SUB_OUTPUT)) {
            return;
        }
        PaymentInput input = ctx.get(INPUT, PaymentInput.class);
        Object id = ctx.get(NEW_ID);
        String number = ctx.get(NUMBER, String.class);
        BigDecimal cash = BigDecimal.ZERO;
        for (PaidBill paid : input.bills()) {
            EntityInstance bill = list(ctx, BILLS).stream().filter(b -> paid.billId().equals(uuid(b.id())))
                .findFirst().orElseThrow();
            BigDecimal discount = paid.discount() == null ? BigDecimal.ZERO : paid.discount();
            Map<String, Object> application = new LinkedHashMap<>();
            application.put("sourceKind", "PAYMENT");
            application.put("sourceId", String.valueOf(id));
            application.put("sourceNo", number);
            application.put("billId", bill.id());
            application.put("vendorCode", bill.get("vendorCode"));
            application.put("applicationDate", input.paymentDate());
            application.put("amount", paid.amount().subtract(discount));
            application.put("discount", discount.signum() == 0 ? null : discount);
            Settled usd = settled(ctx).get(paid.billId());
            if (!ApFx.dollars(input.currency())) {
                application.put("amountUsd", usd.cleared());
                application.put("sourceAmountUsd", usd.source());
                application.put("fxGainLoss", usd.cleared().subtract(usd.source()));
            }
            ctx.changes().insert(BillEntities.APPLICATION, application);
            ctx.changes().update(BillEntities.BILL, bill.id(), bill.version(), BillProcesses.open(bill,
                bill.<BigDecimal>get("openAmount").subtract(paid.amount()), ApFx.openUsd(bill).subtract(usd.cleared())));
            cash = cash.add(paid.amount().subtract(discount));
        }
        if (!PaymentEntities.BILL_LINE.equals(input.kind())) {
            cash = input.amount();
        }
        record1099(ctx, input, id, number);
        ctx.put(OUTPUT, new PaymentOutput(String.valueOf(id), number, cash,
            ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class).glNo()));
    }

    /**
     * What the payment counts on the vendor's Form 1099 (FIN-AP-020, FIN-AP-021): a bill's cash spread over its lines'
     * boxes, a prepayment in the vendor's box. Card payments count nothing (the card processor reports them on Form
     * 1099-K), and other payments are no vendor's.
     */
    private static void record1099(ProcessContext ctx, PaymentInput input, Object paymentId, String paymentNo) {
        if (PaymentEntities.CARD.equals(input.method()) || PaymentEntities.OTHER_LINE.equals(input.kind())) {
            return;
        }
        if (PaymentEntities.PREPAYMENT_LINE.equals(input.kind())) {
            EntityInstance vendor = list(ctx, VENDORS).isEmpty() ? null : list(ctx, VENDORS).getFirst();
            if (vendor != null && vendor.get("form1099") != null && vendor.get("box1099") != null) {
                insert1099(ctx, input.vendorCode(), vendor.get("form1099"), vendor.get("box1099"), input.amount(),
                    Form1099Entities.PREPAYMENT_SOURCE, input.paymentDate().getYear(), input.paymentDate(), paymentId,
                    paymentNo, null, null);
            }
            return;
        }
        if (list(ctx, BILL_LINES).size() >= MAX_ROWS) {
            ctx.reject(new Violation("runId", TOO_MANY, "The bills of " + input.payee() + " have more than "
                + MAX_ROWS + " lines: pay them in parts", Map.of("limit", MAX_ROWS)));
            return;
        }
        for (PaidBill paid : input.bills()) {
            Map<String, BigDecimal> parts = new LinkedHashMap<>();
            list(ctx, BILL_LINES).stream().filter(l -> paid.billId().equals(uuid(l.get("billId"))))
                .forEach(l -> parts.merge(Form1099Allocation.key(l.get("form1099"), l.get("box1099")),
                    l.get("amount"), BigDecimal::add));
            if (parts.isEmpty()) {
                // An open item brought over has no lines: the bill's own form and box (FIN-DI-002).
                list(ctx, BILLS).stream().filter(b -> paid.billId().equals(uuid(b.id()))).findFirst()
                    .ifPresent(b -> parts.put(Form1099Allocation.key(b.get("form1099"), b.get("box1099")),
                        b.get("total")));
            }
            BigDecimal discount = paid.discount() == null ? BigDecimal.ZERO : paid.discount();
            // What the vendor received, in US dollars: a foreign bill's at the payment's rate.
            BigDecimal received = ApFx.dollars(input.currency()) ? paid.amount().subtract(discount)
                : settled(ctx).get(paid.billId()).source();
            Form1099Allocation.allocate(received, parts).forEach((key, amount) -> {
                String[] formBox = key.split("\\|");
                insert1099(ctx, input.vendorCode(), formBox[0], formBox[1], amount,
                    Form1099Entities.PAYMENT_SOURCE, input.paymentDate().getYear(), input.paymentDate(), paymentId,
                    paymentNo, paid.billId(),
                    paid.billNo());
            });
        }
    }

    private static void insert1099(ProcessContext ctx, String vendorCode, String form, String box, BigDecimal amount,
        String source, int taxYear, LocalDate day, Object paymentId, String paymentNo, Object billId, String billNo) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("vendorCode", vendorCode);
        row.put("taxYear", BigDecimal.valueOf(taxYear));
        row.put("form1099", form);
        row.put("box1099", box);
        row.put("amount", amount);
        row.put("source", source);
        row.put("paymentDate", day);
        row.put("paymentId", paymentId);
        row.put("paymentNo", paymentNo);
        row.put("billId", billId);
        row.put("billNo", billNo);
        ctx.changes().insert(Form1099Entities.AMOUNT, row);
    }

    // ---- void ------------------------------------------------------------------------------------------------------

    public static final ProcessDefinition<VoidInput, VoidOutput, ProcessContext> VOID_PROCESS =
        ProcessDefinition.define(VOID, 1, VoidInput.class, VoidOutput.class, ProcessContext.class, pb -> pb
            .description("Voids a payment, such as a stopped check: reverses it and opens its bills again.")
            .permissions(FinancePermissions.PAYMENT_VOID)
            // Reopening paid bills lets them be paid again: as strong as releasing the payment.
            .requiresMfa(MfaRequirement.ALWAYS)
            .actsOn(PaymentEntities.PAYMENT, "paymentId", a -> a.whenField("status", PaymentEntities.POSTED))
            .contextFactory((start, input) -> {
                ProcessContext ctx = withInput(start, input);
                ctx.put("paymentId", input.paymentId());
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, VoidOutput.class))
            .step("Load the payment", LoadEntity.by(PaymentEntities.PAYMENT_DATASET, "paymentId", PAYMENT))
            .step("Load its entry", QueryEntities.of(JournalEntities.POSTING_DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.Eq("documentNo", payment(ctx).get("paymentNo"))).limit(50).build(), POSTINGS))
            .step("Load its 1099 amounts", QueryEntities.of(Form1099Entities.AMOUNT_DATASET, ctx -> EntityQuery
                .builder().where(new QueryPredicate.Eq("paymentId", payment(ctx).id())).limit(MAX_ROWS).build(),
                AMOUNTS_1099))
            .step("Load its run's files", QueryEntities.of(PaymentEntities.FILE_DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.Eq("runId", payment(ctx).get("runId"))).limit(100).build(), FILES))
            .step("Load its applications", QueryEntities.of(BillEntities.APPLICATION_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("sourceId",
                    String.valueOf(payment(ctx).id()))).limit(MAX_ROWS).build(), APPLICATIONS))
            .step("Load the bills", QueryEntities.of(BillEntities.BILL_DATASET, ctx -> BillProcesses.byIds(
                list(ctx, APPLICATIONS).stream().map(a -> uuid(a.get("billId"))).distinct().toArray(UUID[]::new)),
                BILLS))
            .compute("Check it", (metadata, ctx) -> {
                EntityInstance payment = payment(ctx);
                VoidInput input = ctx.get(INPUT, VoidInput.class);
                if (!PaymentEntities.POSTED.equals(payment.get("status"))) {
                    ctx.reject(new Violation("paymentId", NOT_POSTED, payment.get("paymentNo") + " is not posted",
                        Map.of("paymentNo", (Object) payment.get("paymentNo"))));
                    return;
                }
                // An ACH or wire payment in a file the bank has is money gone: only the bank can return it. The file
                // is cancelled first (the bank did not take it), or the return is booked when it comes back.
                String fileKind = "ACH".equals(payment.get("method")) ? PaymentEntities.NACHA
                    : "WIRE".equals(payment.get("method")) ? PaymentEntities.WIRE : null;
                if (fileKind != null && list(ctx, FILES).stream().anyMatch(f -> fileKind.equals(f.get("fileKind"))
                    && PaymentEntities.ACTIVE.equals(f.get("status")))) {
                    ctx.reject(new Violation("paymentId", SENT, payment.get("paymentNo") + " is in a file sent to "
                        + "the bank: cancel the file first if the bank did not take it",
                        Map.of("paymentNo", (Object) payment.get("paymentNo"))));
                    return;
                }
                if (input.voidDate().isBefore(payment.get("paymentDate"))) {
                    ctx.reject(new Violation("voidDate", INVALID_VALUE, "A payment is voided on or after its date",
                        Map.of("field", "voidDate", "value", input.voidDate().toString())));
                    return;
                }
                if (PaymentEntities.PREPAYMENT_LINE.equals(payment.get("kind"))
                    && payment.<BigDecimal>get("openAmount").compareTo(payment.get("amount")) != 0) {
                    ctx.reject(new Violation("paymentId", NOT_POSTED, "Part of the prepayment "
                        + payment.get("paymentNo") + " is applied to bills", Map.of("paymentNo",
                        (Object) payment.get("paymentNo"))));
                    return;
                }
                EntityInstance posting = list(ctx, POSTINGS).stream()
                    .filter(p -> PaymentEntities.PAYMENT.equals(p.get("sourceEntity"))
                        && String.valueOf(payment.id()).equals(p.get("sourceId")))
                    .min(Comparator.comparing(p -> (String) p.get("glNo"))).orElseThrow();
                ctx.put(SUB_INPUT, new SubledgerPosting.ReverseInput("AP",
                    String.valueOf((Object) posting.get("transactionId")), input.voidDate(),
                    "Void of " + payment.get("paymentNo") + ": " + input.reason().trim(), payment.get("paymentNo"),
                    PaymentEntities.PAYMENT, String.valueOf(payment.id())));
            })
            .step("Reverse its entry", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.REVERSE, 1,
                ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT))
            .compute("Open the bills again", (metadata, ctx) -> {
                if (!ctx.contains(SUB_OUTPUT)) {
                    return;
                }
                EntityInstance payment = payment(ctx);
                VoidInput input = ctx.get(INPUT, VoidInput.class);
                Set<Object> reversed = new LinkedHashSet<>();
                list(ctx, APPLICATIONS).forEach(a -> {
                    if (a.get("reversesApplicationId") != null) {
                        reversed.add(String.valueOf((Object) a.get("reversesApplicationId")));
                    }
                });
                Map<Object, BigDecimal> reopen = new LinkedHashMap<>();
                Map<Object, BigDecimal> reopenUsd = new LinkedHashMap<>();
                for (EntityInstance application : list(ctx, APPLICATIONS)) {
                    if (application.get("reversesApplicationId") != null
                        || reversed.contains(String.valueOf(application.id()))) {
                        continue;
                    }
                    BigDecimal amount = application.get("amount");
                    BigDecimal discount = application.get("discount") == null ? BigDecimal.ZERO
                        : application.get("discount");
                    Map<String, Object> back = new LinkedHashMap<>();
                    back.put("sourceKind", application.get("sourceKind"));
                    back.put("sourceId", application.get("sourceId"));
                    back.put("sourceNo", application.get("sourceNo"));
                    back.put("billId", application.get("billId"));
                    back.put("vendorCode", application.get("vendorCode"));
                    back.put("applicationDate", input.voidDate());
                    back.put("amount", amount.negate());
                    back.put("discount", discount.signum() == 0 ? null : discount.negate());
                    if (application.get("amountUsd") != null) {
                        back.put("amountUsd", ApFx.amountUsd(application).negate());
                        back.put("sourceAmountUsd", ApFx.sourceUsd(application).negate());
                        back.put("fxGainLoss", application.get("fxGainLoss") == null ? null
                            : application.<BigDecimal>get("fxGainLoss").negate());
                    }
                    back.put("reversesApplicationId", application.id());
                    back.put("reason", "Void of " + payment.get("paymentNo") + ": " + input.reason().trim());
                    ctx.changes().insert(BillEntities.APPLICATION, back);
                    reopen.merge(String.valueOf((Object) application.get("billId")), amount.add(discount),
                        BigDecimal::add);
                    reopenUsd.merge(String.valueOf((Object) application.get("billId")),
                        ApFx.amountUsd(application).add(discount), BigDecimal::add);
                }
                List<String> reopened = new ArrayList<>();
                for (EntityInstance bill : list(ctx, BILLS)) {
                    BigDecimal back = reopen.get(String.valueOf(bill.id()));
                    if (back != null) {
                        ctx.changes().update(BillEntities.BILL, bill.id(), bill.version(), BillProcesses.open(bill,
                            bill.<BigDecimal>get("openAmount").add(back),
                            ApFx.openUsd(bill).add(reopenUsd.get(String.valueOf(bill.id())))));
                        reopened.add(bill.get("billNo"));
                    }
                }
                // A voided payment was never received: its amounts come off the vendor's 1099 of the year they were
                // counted in, even when voided the next year (a filed year is then corrected, FIN-AP-023).
                if (list(ctx, AMOUNTS_1099).size() >= MAX_ROWS) {
                    ctx.reject(new Violation("paymentId", TOO_MANY, payment.get("paymentNo") + " counts in more than "
                        + MAX_ROWS + " Form 1099 boxes", Map.of("limit", MAX_ROWS)));
                    return;
                }
                for (EntityInstance counted : list(ctx, AMOUNTS_1099)) {
                    if (!Form1099Entities.VOID_SOURCE.equals(counted.get("source"))) {
                        insert1099(ctx, counted.get("vendorCode"), counted.get("form1099"), counted.get("box1099"),
                            counted.<BigDecimal>get("amount").negate(), Form1099Entities.VOID_SOURCE,
                            counted.<BigDecimal>get("taxYear").intValueExact(), input.voidDate(), payment.id(),
                            payment.get("paymentNo"), counted.get("billId"), counted.get("billNo"));
                    }
                }
                String glNo = ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class).glNo();
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("status", PaymentEntities.VOID);
                values.put("voidDate", input.voidDate());
                values.put("voidReason", input.reason().trim());
                values.put("voidGlNo", glNo);
                if (payment.get("openAmount") != null) {
                    values.put("openAmount", BigDecimal.ZERO.setScale(2));
                }
                ctx.changes().update(PaymentEntities.PAYMENT, payment.id(), payment.version(), values);
                ctx.put(OUTPUT, new VoidOutput(String.valueOf(payment.id()), payment.get("paymentNo"),
                    PaymentEntities.VOID, glNo, reopened));
            }));

    // ---- apply a prepayment ----------------------------------------------------------------------------------------

    public static final ProcessDefinition<PrepaymentApplyInput, PrepaymentApplyOutput, ProcessContext>
        PREPAYMENT_APPLY_PROCESS = ProcessDefinition.define(PREPAYMENT_APPLY, 1, PrepaymentApplyInput.class,
            PrepaymentApplyOutput.class, ProcessContext.class, pb -> pb
                .description("Applies a vendor prepayment to a bill of the vendor.")
                .permissions(FinancePermissions.BILL_PREPARE)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = withInput(start, input);
                    ctx.put("paymentId", input.paymentId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, PrepaymentApplyOutput.class))
                .step("Load the prepayment", LoadEntity.by(PaymentEntities.PAYMENT_DATASET, "paymentId", PAYMENT))
                .step("Load the bill", QueryEntities.of(BillEntities.BILL_DATASET, ctx -> BillProcesses.byIds(
                    ctx.get(INPUT, PrepaymentApplyInput.class).billId()), BILLS))
                .step("Load the settings", QueryEntities.of(ApEntities.SETTINGS_DATASET,
                    ctx -> ApSettingsProcesses.current(), SETTINGS))
                .compute("Check it", (metadata, ctx) -> {
                    PrepaymentApplyInput input = ctx.get(INPUT, PrepaymentApplyInput.class);
                    EntityInstance payment = payment(ctx);
                    EntityInstance bill = list(ctx, BILLS).isEmpty() ? null : list(ctx, BILLS).getFirst();
                    EntityInstance settings = list(ctx, SETTINGS).isEmpty() ? null : list(ctx, SETTINGS).getFirst();
                    String reason = null;
                    if (!PaymentEntities.PREPAYMENT_LINE.equals(payment.get("kind"))
                        || !PaymentEntities.POSTED.equals(payment.get("status"))) {
                        reason = "it is not a posted prepayment";
                    } else if (bill == null || !BillEntities.POSTED.equals(bill.get("status"))
                        || !BillEntities.BILL_KIND.equals(bill.get("kind"))
                        || BillEntities.REJECTED.equals(bill.get("approval"))) {
                        reason = "the bill is not a posted bill";
                    } else if (!Objects.equals(payment.get("vendorCode"), bill.get("vendorCode"))) {
                        reason = "the prepayment and the bill are of different vendors";
                    } else if (!ApFx.dollars(ApFx.currency(bill))) {
                        reason = "prepayments are in US dollars and the bill is in " + ApFx.currency(bill);
                    } else if (input.amount().compareTo(payment.get("openAmount")) > 0
                        || input.amount().compareTo(bill.get("openAmount")) > 0) {
                        reason = "the amount is more than is open on the prepayment or the bill";
                    } else if (input.applicationDate().isBefore(payment.get("paymentDate"))
                        || input.applicationDate().isBefore(bill.get("invoiceDate"))) {
                        reason = "a prepayment is applied on or after the dates of both";
                    } else if (settings == null || settings.get("prepaymentAccount") == null) {
                        reason = "the payables settings name no prepayment account";
                    }
                    if (reason != null) {
                        ctx.reject(new Violation("amount", APPLY_REFUSED, "The prepayment cannot be applied: "
                            + reason, Map.of("reason", reason)));
                        return;
                    }
                    String number = payment.get("paymentNo");
                    String memo = "Prepayment " + number + " applied to " + bill.get("billNo");
                    ctx.put(SUB_INPUT, new SubledgerPosting.PostInput("AP", input.applicationDate(), memo, number,
                        PaymentEntities.PAYMENT, String.valueOf(payment.id()), List.of(
                            new JournalProcesses.LineInput(settings.get("payableAccount"), input.amount(), null, memo,
                                null, null),
                            new JournalProcesses.LineInput(settings.get("prepaymentAccount"), null, input.amount(),
                                memo, null, null)), List.of("AP")));
                })
                .step("Book it", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.POST, 1,
                    ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT))
                .compute("Apply it", (metadata, ctx) -> {
                    if (!ctx.contains(SUB_OUTPUT)) {
                        return;
                    }
                    PrepaymentApplyInput input = ctx.get(INPUT, PrepaymentApplyInput.class);
                    EntityInstance payment = payment(ctx);
                    EntityInstance bill = list(ctx, BILLS).getFirst();
                    Map<String, Object> application = new LinkedHashMap<>();
                    application.put("sourceKind", "PREPAYMENT");
                    application.put("sourceId", String.valueOf(payment.id()));
                    application.put("sourceNo", payment.get("paymentNo"));
                    application.put("billId", bill.id());
                    application.put("vendorCode", bill.get("vendorCode"));
                    application.put("applicationDate", input.applicationDate());
                    application.put("amount", input.amount());
                    Object id = ctx.changes().insert(BillEntities.APPLICATION, application);
                    BigDecimal billOpen = bill.<BigDecimal>get("openAmount").subtract(input.amount());
                    BigDecimal prepaymentOpen = payment.<BigDecimal>get("openAmount").subtract(input.amount());
                    ctx.changes().update(BillEntities.BILL, bill.id(), bill.version(), BillProcesses.open(bill,
                        billOpen, ApFx.openUsd(bill).subtract(input.amount())));
                    ctx.changes().update(PaymentEntities.PAYMENT, payment.id(), payment.version(),
                        Map.of("openAmount", prepaymentOpen));
                    ctx.put(OUTPUT, new PrepaymentApplyOutput(String.valueOf(id), billOpen, prepaymentOpen,
                        ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class).glNo()));
                }));

    // ---- holds -----------------------------------------------------------------------------------------------------

    /** Why a bill may not be paid now, or null (FIN-AP-003, 006, 010). */
    static String hold(ProcessContext ctx, EntityInstance bill, String method, String currency,
        Map<String, String> inRun, Object ownRun) {
        if (bill.<BigDecimal>get("openAmount").signum() <= 0) {
            return HOLD_NOTHING_OPEN;
        }
        if (!ApFx.currency(bill).equals(currency)) {
            return HOLD_CURRENCY + " (" + ApFx.currency(bill) + ")";
        }
        if (inRun.containsKey(String.valueOf(bill.id()))) {
            return HOLD_IN_RUN + " (" + inRun.get(String.valueOf(bill.id())) + ")";
        }
        String approval = bill.get("approval");
        if (!BillEntities.NOT_REQUIRED.equals(approval) && !BillEntities.APPROVED.equals(approval)) {
            return HOLD_NOT_APPROVED;
        }
        EntityInstance vendor = list(ctx, VENDORS).stream().filter(v -> Objects.equals(v.get("vendorCode"),
            bill.get("vendorCode"))).findFirst().orElse(null);
        return vendor == null ? HOLD_VENDOR : vendorHold(ctx, vendor, method);
    }

    /** Why a vendor may not be paid now, or null: its bank details wait for approval, or it has none for ACH. */
    static String vendorHold(ProcessContext ctx, EntityInstance vendor, String method) {
        if (!"ACTIVE".equals(vendor.get("status"))) {
            return HOLD_VENDOR;
        }
        List<EntityInstance> banks = list(ctx, VENDOR_BANKS).stream()
            .filter(b -> Objects.equals(b.get("vendorCode"), vendor.get("vendorCode"))).toList();
        if (banks.stream().anyMatch(b -> ApEntities.PENDING.equals(b.get("status")))) {
            return HOLD_BANK_PENDING;
        }
        if (("ACH".equals(method) || "WIRE".equals(method)) && VendorBankProcesses.active(banks) == null) {
            return HOLD_NO_BANK;
        }
        return null;
    }

    private static UUID activeBank(ProcessContext ctx, String vendorCode) {
        EntityInstance active = VendorBankProcesses.active(list(ctx, VENDOR_BANKS).stream()
            .filter(b -> Objects.equals(b.get("vendorCode"), vendorCode)).toList());
        return active == null ? null : uuid(active.id());
    }

    /** The early-payment discount of a bill paid in full on the day, if asked for and still offered. */
    private static BigDecimal discount(ProcessContext ctx, EntityInstance bill, LocalDate paidOn, boolean asked) {
        if (!asked || bill.<BigDecimal>get("openAmount").compareTo(bill.get("total")) != 0) {
            return BigDecimal.ZERO;
        }
        EntityInstance terms = list(ctx, TERMS).stream().filter(t -> Objects.equals(t.get("termsCode"),
            bill.get("termsCode"))).findFirst().orElse(null);
        if (terms == null || terms.get("discountPercent") == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal days = terms.get("discountDays");
        PaymentTerms paymentTerms = new PaymentTerms(terms.<BigDecimal>get("netDays").intValueExact(),
            terms.get("discountPercent"), days == null ? null : days.intValueExact(),
            Boolean.TRUE.equals(terms.get("endOfMonth")));
        return paymentTerms.discountAvailable(bill.get("invoiceDate"), paidOn)
            ? paymentTerms.discount(bill.get("total")) : BigDecimal.ZERO;
    }

    /** The bills in open runs other than {@code except}: bill id to run number. */
    private static Map<String, String> runsOfBills(ProcessContext ctx, Object except) {
        Map<String, String> numbers = new LinkedHashMap<>();
        list(ctx, OPEN_RUNS).forEach(r -> numbers.put(String.valueOf(r.id()), r.get("runNo")));
        Map<String, String> inRun = new LinkedHashMap<>();
        for (EntityInstance line : list(ctx, OPEN_LINES)) {
            String run = String.valueOf((Object) line.get("runId"));
            if (line.get("billId") != null && numbers.containsKey(run)
                && (except == null || !run.equals(String.valueOf(except)))) {
                inRun.put(String.valueOf((Object) line.get("billId")), numbers.get(run));
            }
        }
        return inRun;
    }

    /** The vendor's name as approved with the run: what the payment and the check show. */
    private static String payee(ProcessContext ctx, String vendorCode) {
        return list(ctx, VENDORS).stream().filter(v -> Objects.equals(v.get("vendorCode"), vendorCode))
            .map(v -> (String) v.get("legalName")).findFirst().orElse(vendorCode);
    }

    private static Map<String, Object> billLine(EntityInstance bill, String payee, BigDecimal amount,
        BigDecimal discount) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("kind", PaymentEntities.BILL_LINE);
        line.put("billId", bill.id());
        line.put("billNo", bill.get("billNo"));
        line.put("vendorCode", bill.get("vendorCode"));
        line.put("payee", payee);
        line.put("amount", amount);
        line.put("discount", discount.signum() == 0 ? null : discount);
        return line;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** What an approval of a run is given for: its lines, amounts, bank, method and day (FIN-CT-003). */
    static Map<String, Object> content(EntityInstance run, List<EntityInstance> lines) {
        Map<String, Object> content = new LinkedHashMap<>();
        for (String field : List.of("runNo", "bankCode", "paymentDate", "method")) {
            content.put(field, run.get(field));
        }
        // A foreign run is approved at its rate; runs in dollars keep the content they were approved with.
        if (!ApFx.dollars(ApFx.currency(run))) {
            content.put("currency", ApFx.currency(run));
            content.put("exchangeRate", ApFx.rate(run).stripTrailingZeros().toPlainString());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        lines.stream().sorted(Comparator.comparing(l -> String.valueOf(l.id()))).forEach(line -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineId", String.valueOf(line.id()));
            for (String field : List.of("kind", "billId", "vendorCode", "payee", "account", "amount", "discount")) {
                Object value = line.get(field);
                row.put(field, value == null ? null : value instanceof BigDecimal d ? d.stripTrailingZeros()
                    .toPlainString() : String.valueOf(value));
            }
            rows.add(row);
        });
        content.put("lines", rows);
        return content;
    }

    private static BigDecimal total(List<Map<String, Object>> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> line : lines) {
            BigDecimal amount = new BigDecimal(String.valueOf(line.get("amount")));
            Object discount = line.get("discount");
            total = total.add(amount).subtract(discount == null ? BigDecimal.ZERO
                : new BigDecimal(String.valueOf(discount)));
        }
        return total;
    }

    private static String method(ProcessContext ctx, String value) {
        String method = VendorProcesses.code(value);
        if (!PaymentEntities.METHOD_VALUES.contains(method)) {
            ctx.reject(new Violation("method", INVALID_VALUE, "method must be one of "
                + PaymentEntities.METHOD_VALUES, Map.of("field", "method", "value", String.valueOf(value))));
        }
        return method;
    }

    private static EntityInstance bank(ProcessContext ctx, String code) {
        if (list(ctx, SETTINGS).isEmpty()) {
            ctx.reject(new Violation("bankCode", NO_SETTINGS, "The payables settings are not set", Map.of()));
            return null;
        }
        EntityInstance bank = list(ctx, BANKS).isEmpty() ? null : list(ctx, BANKS).getFirst();
        if (bank == null || !Boolean.TRUE.equals(bank.get("active"))) {
            String named = bankCode(ctx, code);
            ctx.reject(new Violation("bankCode", UNKNOWN_BANK, "There is no active bank account " + named,
                Map.of("bankCode", String.valueOf(named))));
            return null;
        }
        return bank;
    }

    private static String bankCode(ProcessContext ctx, String given) {
        if (given != null && !given.isBlank()) {
            return VendorProcesses.code(given);
        }
        return list(ctx, SETTINGS).isEmpty() ? null : list(ctx, SETTINGS).getFirst().get("defaultBank");
    }

    private static EntityQuery openRuns() {
        return EntityQuery.builder().where(new QueryPredicate.In("status", List.of(PaymentEntities.DRAFT,
            PaymentEntities.SUBMITTED, PaymentEntities.APPROVED))).limit(MAX_ROWS).build();
    }

    private static EntityQuery linesOfRuns(List<EntityInstance> runs) {
        return EntityQuery.builder().where(new QueryPredicate.In("runId", new ArrayList<>(runs.stream()
            .map(EntityInstance::id).toList()))).limit(MAX_ROWS).build();
    }

    private static EntityQuery byField(String field, List<String> values) {
        return EntityQuery.builder().where(new QueryPredicate.In(field, new ArrayList<>(values)))
            .limit(Math.max(1, values.size() * 50)).build();
    }

    /**
     * The vendors' accounts that decide a hold: the one in use and the one waiting for approval, at most two each
     * (only one change waits at a time), so the query never meets the dataset's cap and silently misses one.
     */
    private static EntityQuery vendorBanks(List<String> vendorCodes) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.In("vendorCode", new ArrayList<>(vendorCodes)),
                new QueryPredicate.In("status", List.of(ApEntities.PENDING, ApEntities.ACTIVE)))))
            .limit(Math.max(1, vendorCodes.size() * 2)).build();
    }

    private static List<String> vendorsOf(List<EntityInstance> bills) {
        return bills.stream().map(b -> (String) b.get("vendorCode")).distinct().toList();
    }

    private static List<String> lineVendors(ProcessContext ctx) {
        return list(ctx, LINES).stream().map(l -> (String) l.get("vendorCode")).filter(Objects::nonNull).distinct()
            .toList();
    }

    private static List<String> addVendors(ProcessContext ctx) {
        AddInput input = ctx.get(INPUT, AddInput.class);
        Set<String> codes = new LinkedHashSet<>();
        if (input.vendorCode() != null) {
            codes.add(VendorProcesses.code(input.vendorCode()));
        }
        list(ctx, BILLS).forEach(b -> codes.add(b.get("vendorCode")));
        return List.copyOf(codes);
    }

    private static EntityQuery byRequest(String field, ProcessContext ctx) {
        UUID id = safeUuid(ctx.get(INPUT, ApprovalResultInput.class).requestId());
        return EntityQuery.builder().where(new QueryPredicate.In(field, id == null ? List.of() : List.of(id)))
            .limit(20).build();
    }

    /**
     * Another payment has no vendor account to pay into: it is made by check or outside the bank files (a tax paid
     * on the authority's portal), never in an ACH or wire run, whose file could not carry it.
     */
    private static boolean otherAllowed(String method) {
        return "CHECK".equals(method) || PaymentEntities.MANUAL.equals(method)
            || PaymentEntities.CARD.equals(method);
    }

    /** Wires carry the currency; a manual payment is made outside the files. ACH and checks are in dollars. */
    private static boolean foreignAllowed(String method) {
        return "WIRE".equals(method) || PaymentEntities.MANUAL.equals(method);
    }

    private static Violation foreignLine(EntityInstance run) {
        return new Violation("kind", FOREIGN, run.get("runNo") + " pays bills in " + ApFx.currency(run)
            + ": other payments and prepayments are made in US dollars", Map.of("currency", ApFx.currency(run)));
    }

    static String runCurrency(String given) {
        String code = VendorProcesses.code(given);
        return code == null ? FxRates.USD : code;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    /**
     * What a bill of a payment clears in US dollars (at the bill's rate, all it carries when paid in full) and what the
     * payment gave for it (the cash in dollars spread over the bills, the last taking what is left).
     */
    record Settled(BigDecimal cleared, BigDecimal source) {}

    private static Map<UUID, Settled> settled(ProcessContext ctx, PaymentInput input, BigDecimal cashUsd) {
        Map<UUID, Settled> settled = new LinkedHashMap<>();
        BigDecimal cash = BigDecimal.ZERO;
        for (PaidBill paid : input.bills()) {
            cash = cash.add(paid.amount().subtract(paid.discount() == null ? BigDecimal.ZERO : paid.discount()));
        }
        BigDecimal left = cashUsd;
        for (int i = 0; i < input.bills().size(); i++) {
            PaidBill paid = input.bills().get(i);
            EntityInstance bill = list(ctx, BILLS).stream().filter(b -> paid.billId().equals(uuid(b.id())))
                .findFirst().orElseThrow();
            BigDecimal net = paid.amount().subtract(paid.discount() == null ? BigDecimal.ZERO : paid.discount());
            BigDecimal source = i == input.bills().size() - 1 || cash.signum() == 0 ? left
                : com.jabiz.finance.calc.Money.usd(cashUsd.multiply(net).divide(cash, 10,
                    java.math.RoundingMode.HALF_UP));
            left = left.subtract(source);
            settled.put(paid.billId(), new Settled(ApFx.cleared(bill, paid.amount()), source));
        }
        return settled;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Settled> settled(ProcessContext ctx) {
        return (Map<UUID, Settled>) ctx.get(SETTLED);
    }

    private static Violation otherMethod(EntityInstance run) {
        return new Violation("kind", OTHER_METHOD, "A " + run.get("method") + " run pays vendors' approved "
            + "accounts only: another payment is made by check or outside the bank files (MANUAL)",
            Map.of("method", (Object) run.get("method")));
    }

    private static Violation notDraft(EntityInstance run) {
        return new Violation("runId", NOT_DRAFT, run.get("runNo") + " is " + run.get("status") + ": only a draft run "
            + "changes", Map.of("runNo", (Object) run.get("runNo"), "status", (Object) run.get("status")));
    }

    private static RunOutput output(EntityInstance run, Map<String, Object> changed, List<Held> held,
        List<Paid> paid) {
        Map<String, Object> state = new LinkedHashMap<>(run.attributes());
        state.putAll(changed);
        Object count = state.get("lineCount");
        return new RunOutput(String.valueOf(run.id()), (String) state.get("runNo"), (String) state.get("status"),
            state.get("total") == null ? null : new BigDecimal(String.valueOf(state.get("total"))),
            count == null ? 0 : new BigDecimal(String.valueOf(count)).intValue(),
            (String) state.get("approvalRequestId"), held, paid);
    }

    static UUID uuid(Object value) {
        return value == null ? null : value instanceof UUID u ? u : UUID.fromString(value.toString());
    }

    private static UUID safeUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static EntityInstance run(ProcessContext ctx) {
        return ctx.get(RUN, EntityInstance.class);
    }

    private static EntityInstance payment(ProcessContext ctx) {
        return ctx.get(PAYMENT, EntityInstance.class);
    }

    static ProcessContext withInput(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private static ProcessContext withRun(ProcessStart start, Object input, UUID runId) {
        ProcessContext ctx = withInput(start, input);
        ctx.put(RUN_ID, runId);
        return ctx;
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    static NumberSequence runNumbers() {
        return NumberSequence.define(RUN_NUMBERS, s -> s.format("PAY-RUN-{n:2}").startAt(1));
    }

    static NumberSequence paymentNumbers() {
        return NumberSequence.define(PAYMENT_NUMBERS, s -> s.format("PMT-{n:4}").startAt(1));
    }

    private PaymentProcesses() {}
}

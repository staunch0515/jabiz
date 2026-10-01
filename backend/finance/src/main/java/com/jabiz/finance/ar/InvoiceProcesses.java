package com.jabiz.finance.ar;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.Money;
import com.jabiz.finance.calc.PaymentTerms;
import com.jabiz.finance.calc.SalesTax;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.JournalValidator;
import com.jabiz.finance.gl.SubledgerPosting;
import com.jabiz.finance.tax.TaxEntities;
import com.jabiz.finance.tax.TaxProcesses;
import com.jabiz.numbering.NumberSequence;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.approval.WithdrawApproval;
import com.jabiz.runtime.numbering.AssignNumber;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Invoices and credit memos (FIN-AR-003, 004, 006, FIN-TX-002…005, FIN-GL-021; docs/finance/00-design.md section 8):
 * <ul>
 *   <li>{@code FIN_INVOICE_SAVE} / {@code FIN_INVOICE_DELETE}: a draft, new or changed, or gone; drafts use no
 *       number.</li>
 *   <li>{@code FIN_INVOICE_POST}: computes the tax (destination, certificates, the original invoice's date for a
 *       credit memo), the due date and the US dollar amounts, numbers the document without gaps ({@code INV-},
 *       {@code CM-}) and posts its entry in the same transaction through {@code FIN_SUBLEDGER_POST}. A customer going
 *       over the credit limit is warned (FIN-AR-013). A posted document never changes.</li>
 *   <li>{@code FIN_INVOICE_VOID}: a posted document nothing was applied to is voided by reversing its entry on a day
 *       in an open period.</li>
 *   <li>{@code FIN_CREDIT_APPLY}: a posted credit memo applied to an open invoice of the same customer and currency,
 *       fully or partly.</li>
 *   <li>{@code FIN_AR_OPENING}: the legacy system's open invoices brought over as open items, not posted again;
 *       together they must equal the receivables account in the opening entry (FIN-DI-002).</li>
 * </ul>
 * Tax codes that charge no tax are the customer's: a clerk cannot pick one for an invoice other than the customer's,
 * nor for a line other than a non-taxable service ({@code fin.customer.tax} can). Lines post to revenue accounts only,
 * the receivable to the {@code AR} control account. What writes receivables down, posting a credit memo and voiding,
 * needs {@code fin.invoice.credit} and is never the preparer's own; a credit memo against an invoice credits at most
 * what is left of it, with its tax codes and per jurisdiction no more tax than it bore (FIN-CT-001, FIN-TX-005).
 */
public final class InvoiceProcesses {

    public static final String SAVE = "FIN_INVOICE_SAVE";
    public static final String DELETE = "FIN_INVOICE_DELETE";
    public static final String POST = "FIN_INVOICE_POST";
    public static final String VOID = "FIN_INVOICE_VOID";
    public static final String APPLY = "FIN_CREDIT_APPLY";
    public static final String OPENING = "FIN_AR_OPENING";

    public static final String INVOICE_NUMBERS = "fin.ar.invoice";
    public static final String CREDIT_MEMO_NUMBERS = "fin.ar.credit-memo";

    public static final String NOT_DRAFT = "FIN_INVOICE_NOT_DRAFT";
    public static final String NOT_POSTED = "FIN_INVOICE_NOT_POSTED";
    public static final String INVALID_VALUE = "FIN_INVOICE_INVALID_VALUE";
    public static final String UNKNOWN_CUSTOMER = "FIN_INVOICE_UNKNOWN_CUSTOMER";
    public static final String UNKNOWN_TERMS = "FIN_INVOICE_UNKNOWN_TERMS";
    public static final String NO_RATE = "FIN_INVOICE_NO_EXCHANGE_RATE";
    public static final String NO_SETTINGS = "FIN_AR_NO_SETTINGS";
    public static final String ORIGINAL = "FIN_INVOICE_ORIGINAL";
    public static final String NO_LINES = "FIN_INVOICE_NO_LINES";
    public static final String NO_ACCOUNT = "FIN_INVOICE_NO_ACCOUNT";
    public static final String TAX_RESTRICTED = "FIN_INVOICE_TAX_RESTRICTED";
    public static final String APPLIED = "FIN_INVOICE_APPLIED";
    public static final String APPLY_REFUSED = "FIN_CREDIT_APPLY_REFUSED";
    public static final String OPENING_TOTAL = "FIN_AR_OPENING_TOTAL";
    public static final String OPENING_NONE = "FIN_AR_OPENING_NO_ENTRY";
    public static final String CREDIT_LIMIT = "FIN_AR_CREDIT_LIMIT";
    public static final String ACCOUNT = "FIN_INVOICE_ACCOUNT";
    public static final String ZERO = "FIN_INVOICE_ZERO";
    public static final String CREDIT_RESTRICTED = "FIN_INVOICE_CREDIT_RESTRICTED";
    public static final String OWN_DOCUMENT = "FIN_INVOICE_OWN_DOCUMENT";
    public static final String EXCEEDS = "FIN_CREDIT_MEMO_EXCEEDS";
    public static final String OPENING_DONE = "FIN_AR_OPENING_DONE";
    public static final String OPENING_NUMBER = "FIN_AR_OPENING_NUMBER";

    /**
     * The approval subject of invoices (FIN-AR-013): no rule stops an invoice unless the controller sets one, such as
     * one for invoices that take a customer over the credit limit.
     */
    public static final String SUBJECT = "fin.ar.invoice";
    public static final String APPROVAL_RESULT = "FIN_INVOICE_APPROVAL_RESULT";

    public record LineInput(@NotBlank @Size(max = 500) String description,
        @NotNull @DecimalMin("0.0001") @Digits(integer = 11, fraction = 4) BigDecimal quantity,
        @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 4) BigDecimal unitPrice,
        @Size(max = 20) String revenueAccount, @Size(max = 20) String taxCode, @Size(max = 20) String department,
        @Size(max = 20) String location) {}

    /**
     * A draft; a new one without {@code invoiceId}. The terms, the currency and the ship-to tax code default to the
     * customer's.
     *
     * @param kind              {@code INVOICE} (the default) or {@code CREDIT_MEMO}
     * @param originalInvoiceId a credit memo's invoice, whose date sets its tax rates
     */
    public record InvoiceInput(UUID invoiceId, String kind, @NotBlank @Size(max = 20) String customerCode,
        @NotNull LocalDate invoiceDate, @Size(max = 20) String termsCode, @Size(max = 20) String taxCode,
        @Size(max = 3) String currency, @Size(max = 500) String description, @Size(max = 100) String reference,
        UUID originalInvoiceId, @NotEmpty @Size(max = 500) List<@Valid @NotNull LineInput> lines) {}

    public record InvoiceId(@NotNull UUID invoiceId) {}

    public record VoidInput(@NotNull UUID invoiceId, @NotNull LocalDate voidDate,
        @NotBlank @Size(max = 500) String reason) {}

    public record ApplyInput(@NotNull UUID creditMemoId, @NotNull UUID invoiceId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @NotNull LocalDate applicationDate) {}

    /**
     * @param warnings what the poster should know, such as a credit limit exceeded
     * @param approval {@code PENDING} when an approval rule stops the posting: the document stays a draft and is
     *                 posted again once approved; {@code APPROVED} when it was
     */
    public record InvoiceOutput(String invoiceId, String invoiceNo, String kind, String status, LocalDate dueDate,
        BigDecimal subtotal, BigDecimal taxTotal, BigDecimal total, BigDecimal totalUsd, BigDecimal openAmount,
        String glNo, List<String> warnings, String approval) {}

    /** The platform's approval decision, as its events carry it. */
    public record ApprovalResultInput(String subject, String entityId, String status, String contentHash,
        String requestId) {}

    public record ApplyOutput(String applicationId, BigDecimal invoiceOpen, BigDecimal creditOpen) {}

    public record OpeningItem(@NotBlank @Size(max = 40) String document, @NotBlank @Size(max = 20) String customerCode,
        @NotNull LocalDate invoiceDate, @NotNull LocalDate dueDate,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount) {}

    public record OpeningInput(@NotEmpty @Size(max = 5000) List<@Valid @NotNull OpeningItem> items) {}

    public record OpeningOutput(int items, BigDecimal total) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String INVOICE_ID = "invoiceId";
    static final String INVOICE = "invoice";
    static final String FOUND = "found";
    static final String LINES = "lines";
    static final String CUSTOMERS = "customers";
    static final String SETTINGS = "settings";
    static final String TERMS = "terms";
    static final String CURRENCIES = "currencies";
    static final String FX = "fx";
    static final String ORIGINALS = "originals";
    static final String CODES = "codes";
    static final String RATES = "rates";
    static final String CERTIFICATES = "certificates";
    static final String OPEN_ITEMS = "openItems";
    static final String ACCOUNTS = "accounts";
    static final String PREPARED = "prepared";
    static final String NUMBER = "number";
    static final String SUB_INPUT = "subledgerInput";
    static final String SUB_OUTPUT = "subledgerOutput";
    static final String APPLICATIONS = "applications";
    static final String DECISIONS = "decisions";
    static final String JOURNALS = "journals";
    static final String CREDITS = "credits";
    static final String USED_CODES = "usedCodes";
    static final String PERIODS = "periods";
    static final String ORIGINAL_TAXES = "originalTaxes";
    static final String APPROVAL = "approval";
    static final String HELD = "held";

    // ---- save and delete -------------------------------------------------------------------------------------------

    public static final ProcessDefinition<InvoiceInput, InvoiceOutput, ProcessContext> SAVE_PROCESS =
        ProcessDefinition.define(SAVE, 1, InvoiceInput.class, InvoiceOutput.class, ProcessContext.class, pb -> pb
            .description("Creates or changes a draft invoice or credit memo.")
            .permissions(FinancePermissions.INVOICE_PREPARE)
            .contextFactory(InvoiceProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, InvoiceOutput.class))
            .step("Load the draft", QueryEntities.of(InvoiceEntities.INVOICE_DATASET,
                ctx -> byIds(saveInput(ctx).invoiceId(), saveInput(ctx).originalInvoiceId()), FOUND))
            .step("Load its lines", QueryEntities.of(InvoiceEntities.LINE_DATASET,
                ctx -> linesOf(saveInput(ctx).invoiceId()), LINES))
            .step("Load the customer", QueryEntities.of(ArEntities.CUSTOMER_DATASET,
                ctx -> CustomerProcesses.byCode(saveInput(ctx).customerCode()), CUSTOMERS))
            .step("Load the settings", QueryEntities.of(ArEntities.SETTINGS_DATASET,
                ctx -> ArSettingsProcesses.current(), SETTINGS))
            .step("Load the currencies", QueryEntities.of(GlEntities.CURRENCY_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("active", true)).limit(200).build(),
                CURRENCIES))
            .step("Load the tax codes", QueryEntities.of(TaxEntities.CODE_DATASET, ctx -> {
                Set<String> used = new LinkedHashSet<>();
                InvoiceInput input = saveInput(ctx);
                if (input.taxCode() != null) {
                    used.add(input.taxCode());
                }
                list(ctx, CUSTOMERS).forEach(c -> used.add(c.get("taxCode")));
                list(ctx, FOUND).forEach(i -> used.add(i.get("taxCode")));
                input.lines().forEach(l -> {
                    if (l.taxCode() != null) {
                        used.add(l.taxCode());
                    }
                });
                return TaxProcesses.codes(used);
            }, CODES))
            .step("Load the accounts", QueryEntities.of(GlEntities.ACCOUNT_DATASET, ctx -> accountsOf(
                saveInput(ctx).lines().stream().map(LineInput::revenueAccount).toList()), ACCOUNTS))
            .compute("Save the draft", (metadata, ctx) -> save(ctx))
            // A pending approval of the old content is of no use any more; its tasks go too.
            .step("Withdraw the approval request", WithdrawApproval.of(SUBJECT, InvoiceProcesses::approvalCaseId)));

    public static final ProcessDefinition<InvoiceId, InvoiceOutput, ProcessContext> DELETE_PROCESS =
        ProcessDefinition.define(DELETE, 1, InvoiceId.class, InvoiceOutput.class, ProcessContext.class, pb -> pb
            .description("Deletes a draft invoice or credit memo.")
            .permissions(FinancePermissions.INVOICE_PREPARE)
            .actsOn(InvoiceEntities.INVOICE, "invoiceId", a -> a.whenField("status", InvoiceEntities.DRAFT))
            .contextFactory(InvoiceProcesses::withId)
            .outputMapper(ctx -> ctx.get(OUTPUT, InvoiceOutput.class))
            .step("Load the draft", LoadEntity.by(InvoiceEntities.INVOICE_DATASET, INVOICE_ID, INVOICE))
            .step("Load its lines", QueryEntities.of(InvoiceEntities.LINE_DATASET,
                ctx -> linesOf(ctx.get(INVOICE_ID)), LINES))
            .compute("Delete it", (metadata, ctx) -> {
                EntityInstance invoice = ctx.get(INVOICE, EntityInstance.class);
                if (!InvoiceEntities.DRAFT.equals(invoice.get("status"))) {
                    ctx.reject(notDraft(invoice));
                    return;
                }
                for (EntityInstance line : list(ctx, LINES)) {
                    ctx.changes().delete(InvoiceEntities.LINE, line.id(), line.version());
                }
                ctx.changes().delete(InvoiceEntities.INVOICE, invoice.id(), invoice.version());
                ctx.put(OUTPUT, output(invoice, Map.of("status", "DELETED"), List.of()));
            })
            .step("Withdraw the approval request", WithdrawApproval.of(SUBJECT, InvoiceProcesses::approvalCaseId)));

    static void save(ProcessContext ctx) {
        InvoiceInput input = saveInput(ctx);
        EntityInstance current = input.invoiceId() == null ? null : find(ctx, FOUND, input.invoiceId());
        if (input.invoiceId() != null && current == null) {
            ctx.reject(new Violation("invoiceId", NOT_DRAFT, "There is no invoice " + input.invoiceId(),
                Map.of("invoiceNo", String.valueOf(input.invoiceId()))));
            return;
        }
        String kind = input.kind() == null || input.kind().isBlank() ? InvoiceEntities.INVOICE_KIND
            : input.kind().trim().toUpperCase(Locale.ROOT);
        if (!InvoiceEntities.KIND_VALUES.contains(kind)) {
            ctx.reject(new Violation("kind", INVALID_VALUE, "kind must be one of " + InvoiceEntities.KIND_VALUES,
                Map.of("value", input.kind())));
            return;
        }
        String customerCode = input.customerCode().trim().toUpperCase(Locale.ROOT);
        if (current != null) {
            if (!InvoiceEntities.DRAFT.equals(current.get("status"))) {
                ctx.reject(notDraft(current));
                return;
            }
            if (!kind.equals(current.get("kind")) || !customerCode.equals(current.get("customerCode"))
                || !Objects.equals(input.originalInvoiceId(), uuid(current.get("originalInvoiceId")))) {
                ctx.reject(new Violation("customerCode", INVALID_VALUE, "A draft's kind, customer and original "
                    + "invoice stay as they were created", Map.of("value", customerCode)));
                return;
            }
        }
        EntityInstance customer = list(ctx, CUSTOMERS).isEmpty() ? null : list(ctx, CUSTOMERS).getFirst();
        if (customer == null || !"ACTIVE".equals(customer.get("status"))) {
            ctx.reject(new Violation("customerCode", UNKNOWN_CUSTOMER, "There is no active customer " + customerCode,
                Map.of("customerCode", customerCode)));
            return;
        }
        EntityInstance settings = list(ctx, SETTINGS).isEmpty() ? null : list(ctx, SETTINGS).getFirst();
        if (settings == null) {
            ctx.reject(new Violation("customerCode", NO_SETTINGS, "The receivables settings are not set",
                Map.of()));
            return;
        }
        String currency = upper(input.currency(), customer.get("currency"));
        EntityInstance currencyRow = list(ctx, CURRENCIES).stream()
            .filter(c -> currency.equals(c.get("currencyCode"))).findFirst().orElse(null);
        if (currencyRow == null) {
            ctx.reject(new Violation("currency", INVALID_VALUE, "There is no active currency " + currency,
                Map.of("value", currency)));
            return;
        }
        int scale = currencyRow.<BigDecimal>get("minorUnits").intValueExact();
        boolean creditMemo = InvoiceEntities.CREDIT_MEMO.equals(kind);
        EntityInstance original = null;
        if (input.originalInvoiceId() != null) {
            original = find(ctx, FOUND, input.originalInvoiceId());
            if (!creditMemo || original == null || !InvoiceEntities.INVOICE_KIND.equals(original.get("kind"))
                || !InvoiceEntities.POSTED.equals(original.get("status"))
                || !customerCode.equals(original.get("customerCode")) || !currency.equals(original.get("currency"))) {
                ctx.reject(new Violation("originalInvoiceId", ORIGINAL, "A credit memo refers to a posted invoice of "
                    + "the same customer and currency", Map.of()));
                return;
            }
        }
        // A credit memo of an invoice is taxed as the invoice was.
        String headerCode = upper(input.taxCode(),
            original != null ? original.get("taxCode") : customer.get("taxCode"));
        Map<String, EntityInstance> codes = new LinkedHashMap<>();
        for (EntityInstance code : list(ctx, CODES)) {
            if (Boolean.TRUE.equals(code.get("active"))) {
                codes.put(code.get("taxCode"), code);
            }
        }
        boolean taxPermitted = ctx.request().hasPermission(FinancePermissions.CUSTOMER_TAX);
        boolean ownCode = headerCode.equals(customer.get("taxCode"))
            || original != null && headerCode.equals(original.get("taxCode"));
        // A credit memo of no invoice takes back tax only at the customer's code (FIN-TX-005).
        if (!ownCode && !taxPermitted && (creditMemo || !taxable(codes.get(headerCode)))) {
            ctx.reject(new Violation("taxCode", TAX_RESTRICTED, "Tax code " + headerCode + " charges no tax; the "
                + "customer's is " + customer.get("taxCode"), Map.of("taxCode", headerCode)));
        }
        if (!codes.containsKey(headerCode)) {
            ctx.reject(new Violation("taxCode", INVALID_VALUE, "There is no active tax code " + headerCode,
                Map.of("value", headerCode)));
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (int i = 0; i < input.lines().size(); i++) {
            LineInput line = input.lines().get(i);
            String account = line.revenueAccount() == null || line.revenueAccount().isBlank()
                ? (creditMemo ? settings.get("returnsAccount") : null) : line.revenueAccount().trim();
            if (account == null) {
                ctx.reject(new Violation("lines[" + i + "].revenueAccount", NO_ACCOUNT, "Line " + (i + 1)
                    + " needs a revenue account", Map.of("line", i + 1)));
                continue;
            }
            if (!revenueAccount(ctx, account)) {
                ctx.reject(new Violation("lines[" + i + "].revenueAccount", ACCOUNT, "Account " + account
                    + " is not a revenue account that takes postings", Map.of("accountCode", account)));
            }
            String lineCode = line.taxCode() == null || line.taxCode().isBlank() ? null
                : line.taxCode().trim().toUpperCase(Locale.ROOT);
            if (lineCode != null) {
                EntityInstance code = codes.get(lineCode);
                if (code == null) {
                    ctx.reject(new Violation("lines[" + i + "].taxCode", INVALID_VALUE, "There is no active tax code "
                        + lineCode, Map.of("value", lineCode)));
                } else if (!taxPermitted && !taxable(code) && !"NON_TAXABLE_SERVICE".equals(code.get("reason"))) {
                    ctx.reject(new Violation("lines[" + i + "].taxCode", TAX_RESTRICTED, "Tax code " + lineCode
                        + " charges no tax", Map.of("taxCode", lineCode)));
                }
            }
            BigDecimal amount = Money.round(line.quantity().multiply(line.unitPrice()), scale);
            subtotal = subtotal.add(amount);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("lineNo", BigDecimal.valueOf(i + 1));
            values.put("description", line.description().trim());
            values.put("quantity", line.quantity());
            values.put("unitPrice", line.unitPrice());
            values.put("amount", amount);
            values.put("revenueAccount", account);
            values.put("taxCode", lineCode);
            values.put("department", blankToNull(line.department()));
            values.put("location", blankToNull(line.location()));
            lines.add(values);
        }
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("invoiceDate", input.invoiceDate());
        header.put("currency", currency);
        header.put("termsCode", upper(input.termsCode(), customer.get("termsCode")));
        header.put("taxCode", headerCode);
        header.put("description", blankToNull(input.description()));
        header.put("reference", blankToNull(input.reference()));
        header.put("subtotal", subtotal);
        Object id;
        if (current == null) {
            header.put("kind", kind);
            header.put("customerCode", customerCode);
            header.put("originalInvoiceId", input.originalInvoiceId());
            header.put("source", InvoiceEntities.MANUAL);
            header.put("status", InvoiceEntities.DRAFT);
            header.put("preparedBy", ctx.request().actorId());
            id = ctx.changes().insert(InvoiceEntities.INVOICE, header);
        } else {
            id = current.id();
            // An approval was given for the content as it was.
            header.put("approval", null);
            header.put("approvalRequestId", null);
            ctx.changes().update(InvoiceEntities.INVOICE, current.id(), current.version(), header);
            for (EntityInstance line : list(ctx, LINES)) {
                ctx.changes().delete(InvoiceEntities.LINE, line.id(), line.version());
            }
        }
        for (Map<String, Object> line : lines) {
            line.put("invoiceId", id);
            ctx.changes().insert(InvoiceEntities.LINE, line);
        }
        ctx.put(OUTPUT, new InvoiceOutput(String.valueOf(id), null, kind, InvoiceEntities.DRAFT, null, subtotal, null,
            null, null, null, null, List.of(), null));
    }

    // ---- post ------------------------------------------------------------------------------------------------------

    /** What posting a document will write, computed before it is numbered. */
    record Prepared(boolean creditMemo, LocalDate dueDate, BigDecimal rate, BigDecimal subtotal, BigDecimal tax,
        BigDecimal total, InvoicePosting.Result posting, SalesTax.Result taxes, List<EntityInstance> lines,
        List<String> warnings, boolean overCreditLimit) {}

    public static final ProcessDefinition<InvoiceId, InvoiceOutput, ProcessContext> POST_PROCESS =
        ProcessDefinition.define(POST, 1, InvoiceId.class, InvoiceOutput.class, ProcessContext.class, pb -> pb
            .description("Posts an invoice or credit memo: computes its tax, numbers it and books it.")
            .permissions(FinancePermissions.INVOICE_PREPARE)
            .actsOn(InvoiceEntities.INVOICE, "invoiceId", a -> a.whenField("status", InvoiceEntities.DRAFT))
            .contextFactory(InvoiceProcesses::withId)
            .outputMapper(ctx -> ctx.get(OUTPUT, InvoiceOutput.class))
            .step("Load the document", LoadEntity.by(InvoiceEntities.INVOICE_DATASET, INVOICE_ID, INVOICE))
            .step("Load its lines", QueryEntities.of(InvoiceEntities.LINE_DATASET,
                ctx -> linesOf(ctx.get(INVOICE_ID)), LINES))
            .step("Load the customer", QueryEntities.of(ArEntities.CUSTOMER_DATASET,
                ctx -> CustomerProcesses.byCode(invoice(ctx).get("customerCode")), CUSTOMERS))
            .step("Load the settings", QueryEntities.of(ArEntities.SETTINGS_DATASET,
                ctx -> ArSettingsProcesses.current(), SETTINGS))
            .step("Load the terms", QueryEntities.of(ArEntities.PAYMENT_TERMS_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("termsCode", invoice(ctx).get("termsCode")))
                    .limit(1).build(), TERMS))
            .step("Load the exchange rate", QueryEntities.of(GlEntities.EXCHANGE_RATE_DATASET, ctx -> {
                EntityInstance invoice = invoice(ctx);
                return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("fromCurrency", invoice.get("currency")),
                    new QueryPredicate.Eq("toCurrency", "USD"),
                    new QueryPredicate.Eq("rateDate", invoice.get("invoiceDate")),
                    new QueryPredicate.Eq("rateType", "SPOT")))).limit(1).build();
            }, FX))
            .step("Load the original invoice", QueryEntities.of(InvoiceEntities.INVOICE_DATASET,
                ctx -> byIds(uuid(invoice(ctx).get("originalInvoiceId"))), ORIGINALS))
            .step("Load the tax codes", QueryEntities.of(TaxEntities.CODE_DATASET, ctx -> {
                Set<String> used = new LinkedHashSet<>();
                used.add(invoice(ctx).get("taxCode"));
                list(ctx, LINES).forEach(l -> {
                    if (l.get("taxCode") != null) {
                        used.add(l.get("taxCode"));
                    }
                });
                return TaxProcesses.codes(used);
            }, USED_CODES))
            .step("Load their charge codes", QueryEntities.of(TaxEntities.CODE_DATASET, ctx -> {
                Set<String> charged = new LinkedHashSet<>();
                list(ctx, USED_CODES).forEach(c -> {
                    if (c.get("chargeCode") != null) {
                        charged.add(c.get("chargeCode"));
                    }
                });
                return TaxProcesses.codes(charged);
            }, CODES))
            .step("Load the tax rates in effect", QueryEntities.of(TaxEntities.RATE_DATASET, ctx -> {
                LocalDate date = taxDate(ctx);
                return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.In("jurisdictionCode", new ArrayList<>(jurisdictions(ctx))),
                    new QueryPredicate.Lte("effectiveFrom", date),
                    new QueryPredicate.Or(List.of(new QueryPredicate.IsNull("effectiveTo"),
                        new QueryPredicate.Gte("effectiveTo", date)))))).limit(500).build();
            }, RATES))
            .step("Load the certificates", QueryEntities.of(ArEntities.CERTIFICATE_DATASET,
                ctx -> CustomerProcesses.certificatesOf(invoice(ctx).get("customerCode")), CERTIFICATES))
            .step("Load the accounts", QueryEntities.of(GlEntities.ACCOUNT_DATASET, ctx -> accountsOf(
                list(ctx, LINES).stream().map(l -> (String) l.get("revenueAccount")).toList()), ACCOUNTS))
            .step("Load the original's other credit memos", QueryEntities.of(InvoiceEntities.INVOICE_DATASET,
                ctx -> creditsOf(uuid(invoice(ctx).get("originalInvoiceId"))), CREDITS))
            .step("Load the original's and their tax", QueryEntities.of(InvoiceEntities.TAX_DATASET, ctx -> {
                List<Object> ids = new ArrayList<>();
                if (invoice(ctx).get("originalInvoiceId") != null) {
                    ids.add(invoice(ctx).get("originalInvoiceId"));
                }
                list(ctx, CREDITS).stream().filter(c -> InvoiceEntities.POSTED.equals(c.get("status")))
                    .forEach(c -> ids.add(c.id()));
                return EntityQuery.builder().where(new QueryPredicate.In("invoiceId", ids)).limit(500).build();
            }, ORIGINAL_TAXES))
            .step("Load the customer's open documents", QueryEntities.of(InvoiceEntities.INVOICE_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("customerCode", invoice(ctx).get("customerCode")),
                    new QueryPredicate.Eq("status", InvoiceEntities.POSTED),
                    new QueryPredicate.Ne("openAmountUsd", BigDecimal.ZERO)))).limit(500).build(), OPEN_ITEMS))
            .compute("Compute the document", (metadata, ctx) -> prepare(ctx))
            // Invoices only: a credit memo lowers what the customer owes.
            .step("Apply the approval rules", RequireApproval.when(ctx -> ctx.contains(PREPARED)
                && !prepared(ctx).creditMemo(), SUBJECT, InvoiceProcesses::approvalCase, APPROVAL))
            .compute("Hold it for approval", (metadata, ctx) -> hold(ctx))
            // Numbered only when it posts: a refused or held document uses no number (FIN-AR-004).
            .step("Number the invoice", AssignNumber.when(ctx -> proceeds(ctx)
                && !prepared(ctx).creditMemo(), INVOICE_NUMBERS, null, NUMBER))
            .step("Number the credit memo", AssignNumber.when(ctx -> proceeds(ctx)
                && prepared(ctx).creditMemo(), CREDIT_MEMO_NUMBERS, null, NUMBER))
            .compute("Build the entry", (metadata, ctx) -> {
                if (!proceeds(ctx)) {
                    return;
                }
                EntityInstance invoice = invoice(ctx);
                Prepared prepared = prepared(ctx);
                String number = ctx.get(NUMBER, String.class);
                List<JournalProcesses.LineInput> lines = prepared.posting().lines().stream()
                    .map(l -> new JournalProcesses.LineInput(l.accountCode(), l.debit(), l.credit(), l.memo(),
                        l.department(), l.location())).toList();
                String description = invoice.get("description") != null ? invoice.get("description")
                    : (prepared.creditMemo() ? "Credit memo " : "Invoice ") + number + " "
                        + invoice.get("customerCode");
                ctx.put(SUB_INPUT, new SubledgerPosting.PostInput("AR", invoice.get("invoiceDate"), description,
                    number, InvoiceEntities.SOURCE_ENTITY, String.valueOf(invoice.id()), lines, List.of("AR")));
            })
            .step("Book it", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.POST, 1,
                ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT))
            .compute("Record the posting", (metadata, ctx) -> recordPosting(ctx)));

    static void prepare(ProcessContext ctx) {
        EntityInstance invoice = invoice(ctx);
        if (!InvoiceEntities.DRAFT.equals(invoice.get("status"))) {
            ctx.reject(notDraft(invoice));
            return;
        }
        boolean creditMemo = InvoiceEntities.CREDIT_MEMO.equals(invoice.get("kind"));
        if (creditMemo) {
            if (!ctx.request().hasPermission(FinancePermissions.INVOICE_CREDIT)) {
                ctx.reject(new Violation("invoiceId", CREDIT_RESTRICTED, "Posting a credit memo needs permission "
                    + FinancePermissions.INVOICE_CREDIT, Map.of()));
                return;
            }
            if (Objects.equals(ctx.request().actorId(), invoice.get("preparedBy"))) {
                ctx.reject(new Violation("invoiceId", OWN_DOCUMENT, "The preparer of a credit memo does not post it",
                    Map.of("invoiceNo", "the credit memo")));
                return;
            }
        }
        List<EntityInstance> lines = new ArrayList<>(list(ctx, LINES));
        lines.sort(java.util.Comparator.comparing(l -> l.<BigDecimal>get("lineNo")));
        if (lines.isEmpty()) {
            ctx.reject(new Violation("lines", NO_LINES, "A document has at least one line", Map.of()));
        }
        for (EntityInstance line : lines) {
            if (!revenueAccount(ctx, line.get("revenueAccount"))) {
                ctx.reject(new Violation("lines", ACCOUNT, "Account " + line.get("revenueAccount") + " is not a "
                    + "revenue account that takes postings",
                    Map.of("accountCode", (Object) line.get("revenueAccount"))));
            }
        }
        EntityInstance customer = list(ctx, CUSTOMERS).isEmpty() ? null : list(ctx, CUSTOMERS).getFirst();
        if (customer == null || !"ACTIVE".equals(customer.get("status"))) {
            ctx.reject(new Violation("customerCode", UNKNOWN_CUSTOMER, "There is no active customer "
                + invoice.get("customerCode"), Map.of("customerCode", (Object) invoice.get("customerCode"))));
        }
        EntityInstance settings = list(ctx, SETTINGS).isEmpty() ? null : list(ctx, SETTINGS).getFirst();
        if (settings == null) {
            ctx.reject(new Violation("invoiceId", NO_SETTINGS, "The receivables settings are not set", Map.of()));
        }
        EntityInstance terms = list(ctx, TERMS).isEmpty() ? null : list(ctx, TERMS).getFirst();
        if (terms == null) {
            ctx.reject(new Violation("termsCode", UNKNOWN_TERMS, "There are no payment terms "
                + invoice.get("termsCode"), Map.of("termsCode", (Object) invoice.get("termsCode"))));
        }
        BigDecimal rate = "USD".equals(invoice.get("currency")) ? BigDecimal.ONE
            : list(ctx, FX).isEmpty() ? null : list(ctx, FX).getFirst().get("rate");
        if (rate == null) {
            ctx.reject(new Violation("currency", NO_RATE, "There is no spot rate of " + invoice.get("currency")
                + " to USD on " + invoice.get("invoiceDate"), Map.of("currency", (Object) invoice.get("currency"),
                "date", String.valueOf((Object) invoice.get("invoiceDate")))));
        }
        LocalDate taxDate = invoice.get("invoiceDate");
        if (invoice.get("originalInvoiceId") != null) {
            EntityInstance original = list(ctx, ORIGINALS).isEmpty() ? null : list(ctx, ORIGINALS).getFirst();
            if (original == null || !InvoiceEntities.POSTED.equals(original.get("status"))) {
                ctx.reject(new Violation("originalInvoiceId", ORIGINAL, "The credit memo's invoice is not posted",
                    Map.of()));
            } else {
                // The original invoice's rates (FIN-TX-005).
                taxDate = original.get("invoiceDate");
            }
        }
        // The customer's code may have changed since the draft: a code that charges no tax stays the controller's.
        if (customer != null && !ctx.request().hasPermission(FinancePermissions.CUSTOMER_TAX)
            && !Objects.equals(invoice.get("taxCode"), customer.get("taxCode"))
            && (creditMemo && invoice.get("originalInvoiceId") == null || codesInUse(ctx).stream().anyMatch(c ->
                Objects.equals(c.get("taxCode"), invoice.get("taxCode")) && !taxable(c)))) {
            ctx.reject(new Violation("taxCode", TAX_RESTRICTED, "Tax code " + invoice.get("taxCode")
                + " is not the customer's", Map.of("taxCode", (Object) invoice.get("taxCode"))));
        }
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, SalesTax.Code> codes = new LinkedHashMap<>();
        for (EntityInstance code : codesInUse(ctx)) {
            String jurisdictions = code.get("jurisdictions");
            codes.put(code.get("taxCode"), new SalesTax.Code(code.get("taxCode"),
                SalesTax.Kind.valueOf(code.get("kind")), code.get("reason"), code.get("state"),
                jurisdictions == null ? List.of() : Arrays.asList(jurisdictions.split(",")),
                Boolean.TRUE.equals(code.get("certificateRequired")), code.get("chargeCode")));
        }
        List<SalesTax.Rate> rates = list(ctx, RATES).stream().map(r -> new SalesTax.Rate(r.get("jurisdictionCode"),
            r.get("effectiveFrom"), r.get("effectiveTo"), r.get("ratePercent"))).toList();
        List<SalesTax.Certificate> certificates = list(ctx, CERTIFICATES).stream()
            .filter(c -> Boolean.TRUE.equals(c.get("active")))
            .map(c -> new SalesTax.Certificate(c.get("state"), c.get("certificateNo"), c.get("certificateType"),
                c.get("issueDate"), c.get("expiryDate"))).toList();
        List<SalesTax.Line> taxLines = lines.stream().map(l -> new SalesTax.Line(l.get("amount"),
            l.get("taxCode") != null ? l.get("taxCode") : invoice.get("taxCode"))).toList();
        SalesTax.Result taxes = SalesTax.compute(new SalesTax.Request(taxDate, taxLines, codes, rates, certificates,
            SalesTax.OnMissingCertificate.valueOf(settings.get("missingCertificate"))));
        for (SalesTax.Problem problem : taxes.problems()) {
            ctx.reject(new Violation(problem.line() < 0 ? "lines" : "lines[" + problem.line() + "].taxCode",
                problem.code(), problem.message(), problem.params()));
        }
        if (ctx.hasViolations()) {
            return;
        }
        BigDecimal subtotal = lines.stream().map(l -> l.<BigDecimal>get("amount")).reduce(BigDecimal.ZERO,
            BigDecimal::add);
        if (invoice.get("originalInvoiceId") != null) {
            checkCredit(ctx, invoice, subtotal, taxes);
            if (ctx.hasViolations()) {
                return;
            }
        }
        BigDecimal total = subtotal.add(taxes.total());
        List<InvoicePosting.Line> postingLines = lines.stream().map(l -> new InvoicePosting.Line(l.get("amount"),
            l.get("revenueAccount"), l.get("department"), l.get("location"))).toList();
        Set<String> jurisdictions = new LinkedHashSet<>();
        taxes.taxes().forEach(t -> jurisdictions.add(t.jurisdiction()));
        InvoicePosting.Result posting = InvoicePosting.lines(creditMemo, postingLines, taxes.total(), rate,
            settings.get("receivableAccount"), settings.get("salesTaxAccount"), null,
            jurisdictions.isEmpty() ? null : "Sales tax " + String.join(", ", jurisdictions), null);
        if (posting.lines().isEmpty()) {
            ctx.reject(new Violation("lines", ZERO, "The document comes to nothing", Map.of()));
            return;
        }
        LocalDate invoiceDate = invoice.get("invoiceDate");
        PaymentTerms paymentTerms = new PaymentTerms(terms.<BigDecimal>get("netDays").intValueExact(),
            terms.get("discountPercent"), terms.get("discountDays") == null ? null
                : terms.<BigDecimal>get("discountDays").intValueExact(), Boolean.TRUE.equals(terms.get("endOfMonth")));
        LocalDate dueDate = creditMemo ? invoiceDate : paymentTerms.dueDate(invoiceDate);
        List<String> warnings = new ArrayList<>();
        BigDecimal limit = customer.get("creditLimit");
        boolean overLimit = false;
        if (!creditMemo && limit != null && !"OFF".equals(settings.get("creditLimitCheck"))) {
            BigDecimal open = BigDecimal.ZERO;
            for (EntityInstance item : list(ctx, OPEN_ITEMS)) {
                BigDecimal itemOpen = item.get("openAmountUsd") == null ? BigDecimal.ZERO : item.get("openAmountUsd");
                open = InvoiceEntities.CREDIT_MEMO.equals(item.get("kind")) ? open.subtract(itemOpen)
                    : open.add(itemOpen);
            }
            overLimit = open.add(posting.totalUsd()).compareTo(limit) > 0;
            if (overLimit) {
                warnings.add(CREDIT_LIMIT + ": open items " + Money.usd(open).toPlainString() + " and this invoice "
                    + posting.totalUsd().toPlainString() + " exceed the credit limit " + limit.toPlainString());
            }
        }
        ctx.put(PREPARED, new Prepared(creditMemo, dueDate, rate, subtotal, taxes.total(), total, posting, taxes,
            List.copyOf(lines), List.copyOf(warnings), overLimit));
    }

    /**
     * A credit memo of an invoice credits at most what is left of it: its amount before tax and, per jurisdiction,
     * its tax, less what earlier credit memos took back, and only under the tax codes the invoice bore (FIN-TX-005).
     */
    static void checkCredit(ProcessContext ctx, EntityInstance credit, BigDecimal subtotal, SalesTax.Result taxes) {
        EntityInstance original = list(ctx, ORIGINALS).getFirst();
        Object originalId = original.id();
        Set<Object> earlier = new LinkedHashSet<>();
        BigDecimal leftAmount = original.get("subtotal");
        for (EntityInstance other : list(ctx, CREDITS)) {
            if (InvoiceEntities.POSTED.equals(other.get("status")) && !Objects.equals(other.id(), credit.id())) {
                earlier.add(other.id());
                leftAmount = leftAmount.subtract(other.get("subtotal"));
            }
        }
        if (subtotal.compareTo(leftAmount) > 0) {
            ctx.reject(new Violation("lines", EXCEEDS, "The credit memo credits " + subtotal.toPlainString()
                + "; " + leftAmount.toPlainString() + " is left of the invoice", Map.of("what", "amount "
                + subtotal.toPlainString() + " of " + leftAmount.toPlainString() + " left")));
        }
        Map<String, BigDecimal> leftTax = new LinkedHashMap<>();
        Set<String> invoiceCodes = new LinkedHashSet<>();
        for (EntityInstance row : list(ctx, ORIGINAL_TAXES)) {
            boolean ofOriginal = Objects.equals(uuid(row.get("invoiceId")), uuid(originalId));
            if (row.get("jurisdiction") != null) {
                BigDecimal tax = row.get("tax");
                if (ofOriginal) {
                    leftTax.merge(row.get("jurisdiction"), tax, BigDecimal::add);
                } else if (earlier.contains(row.get("invoiceId")) || earlier.stream()
                    .anyMatch(id -> Objects.equals(uuid(id), uuid(row.get("invoiceId"))))) {
                    leftTax.merge(row.get("jurisdiction"), tax.negate(), BigDecimal::add);
                }
            } else if (ofOriginal) {
                invoiceCodes.add(row.get("taxCode"));
            }
        }
        for (SalesTax.LineResult line : taxes.lines()) {
            if (!invoiceCodes.contains(line.taxCode())) {
                ctx.reject(new Violation("lines[" + line.line() + "].taxCode", EXCEEDS, "Tax code " + line.taxCode()
                    + " was not on the invoice", Map.of("what", "tax code " + line.taxCode())));
            }
        }
        for (SalesTax.JurisdictionTax tax : taxes.taxes()) {
            BigDecimal left = leftTax.getOrDefault(tax.jurisdiction(), BigDecimal.ZERO);
            if (tax.tax().compareTo(left) > 0) {
                ctx.reject(new Violation("lines", EXCEEDS, "The credit memo takes back " + tax.tax().toPlainString()
                    + " of " + tax.jurisdiction() + " tax; " + left.toPlainString() + " is left",
                    Map.of("what", tax.jurisdiction() + " tax " + tax.tax().toPlainString() + " of "
                        + left.toPlainString() + " left")));
            }
        }
    }

    /**
     * The approval case of an invoice (FIN-AR-013): what the rules ask about, and the content an approval is given for
     * — a changed draft needs a new approval.
     */
    static com.jabiz.runtime.approval.ApprovalCase approvalCase(ProcessContext ctx) {
        EntityInstance invoice = invoice(ctx);
        Prepared prepared = prepared(ctx);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("amount", prepared.posting().totalUsd());
        facts.put("customerCode", invoice.get("customerCode"));
        facts.put("overCreditLimit", prepared.overCreditLimit());
        Map<String, Object> content = new LinkedHashMap<>();
        for (String field : List.of("kind", "customerCode", "invoiceDate", "currency", "termsCode", "taxCode",
            "description", "reference")) {
            content.put(field, invoice.get(field));
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (EntityInstance line : prepared.lines()) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String field : List.of("lineNo", "description", "quantity", "unitPrice", "amount", "revenueAccount",
                "taxCode", "department", "location")) {
                values.put(field, line.get(field));
            }
            lines.add(values);
        }
        content.put("lines", lines);
        content.put("total", prepared.total());
        return com.jabiz.runtime.approval.ApprovalCase.of(invoice.id(), facts, content);
    }

    /** The approval case of the document saved or deleted; a new draft has none, and nothing is filed under "none". */
    private static Object approvalCaseId(ProcessContext ctx) {
        Object id = ctx.get(INPUT) instanceof InvoiceInput input ? input.invoiceId() : ctx.get(INVOICE_ID);
        return id == null || ctx.hasViolations() ? "none" : id;
    }

    /** Whether the document posts now: computed, and not stopped by an approval rule. */
    private static boolean proceeds(ProcessContext ctx) {
        return ctx.contains(PREPARED) && !ctx.contains(HELD);
    }

    /** A document an approval rule stopped stays a draft and waits; it is posted again once approved. */
    static void hold(ProcessContext ctx) {
        if (!ctx.contains(APPROVAL)) {
            return;
        }
        com.jabiz.runtime.approval.ApprovalOutcome approval = ctx.get(APPROVAL,
            com.jabiz.runtime.approval.ApprovalOutcome.class);
        if (approval.mayProceed()) {
            return;
        }
        EntityInstance invoice = invoice(ctx);
        ctx.put(HELD, Boolean.TRUE);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("approval", InvoiceEntities.APPROVAL_PENDING);
        values.put("approvalRequestId", approval.requestId());
        ctx.changes().update(InvoiceEntities.INVOICE, invoice.id(), invoice.version(), values);
        InvoiceOutput held = output(invoice, values, prepared(ctx).warnings());
        ctx.put(OUTPUT, held);
    }

    static void recordPosting(ProcessContext ctx) {
        if (!ctx.contains(PREPARED) || !ctx.contains(SUB_OUTPUT)) {
            return;
        }
        EntityInstance invoice = invoice(ctx);
        Prepared prepared = prepared(ctx);
        String number = ctx.get(NUMBER, String.class);
        SubledgerPosting.PostOutput booked = ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", InvoiceEntities.POSTED);
        values.put("invoiceNo", number);
        values.put("dueDate", prepared.dueDate());
        values.put("exchangeRate", prepared.rate());
        values.put("subtotal", prepared.subtotal());
        values.put("taxTotal", prepared.tax());
        values.put("total", prepared.total());
        values.put("totalUsd", prepared.posting().totalUsd());
        values.put("openAmount", prepared.total());
        values.put("openAmountUsd", prepared.posting().totalUsd());
        values.put("glNo", booked.glNo());
        values.put("transactionId", UUID.fromString(booked.transactionId()));
        if (ctx.contains(APPROVAL)) {
            com.jabiz.runtime.approval.ApprovalOutcome approval = ctx.get(APPROVAL,
                com.jabiz.runtime.approval.ApprovalOutcome.class);
            if (approval.status() == com.jabiz.runtime.approval.ApprovalOutcome.Status.APPROVED) {
                values.put("approval", "APPROVED");
                values.put("approvalRequestId", approval.requestId());
            }
        }
        ctx.changes().update(InvoiceEntities.INVOICE, invoice.id(), invoice.version(), values);
        for (SalesTax.JurisdictionTax tax : prepared.taxes().taxes()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("invoiceId", invoice.id());
            row.put("jurisdiction", tax.jurisdiction());
            row.put("base", tax.base());
            row.put("ratePercent", tax.percent());
            row.put("rateFrom", tax.rateFrom());
            row.put("tax", tax.tax());
            ctx.changes().insert(InvoiceEntities.TAX, row);
        }
        for (SalesTax.LineResult result : prepared.taxes().lines()) {
            EntityInstance line = prepared.lines().get(result.line());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("invoiceId", invoice.id());
            row.put("lineNo", line.get("lineNo"));
            row.put("taxCode", result.taxCode());
            row.put("taxKind", result.kind().name());
            row.put("reason", result.reason());
            row.put("certificateNo", result.certificate());
            row.put("base", line.get("amount"));
            row.put("tax", result.tax());
            ctx.changes().insert(InvoiceEntities.TAX, row);
        }
        Map<String, Object> shown = new LinkedHashMap<>(values);
        ctx.put(OUTPUT, output(invoice, shown, prepared.warnings()));
    }

    // ---- void ------------------------------------------------------------------------------------------------------

    public static final ProcessDefinition<VoidInput, InvoiceOutput, ProcessContext> VOID_PROCESS =
        ProcessDefinition.define(VOID, 1, VoidInput.class, InvoiceOutput.class, ProcessContext.class, pb -> pb
            .description("Voids a posted invoice or credit memo nothing was applied to, reversing its entry.")
            .permissions(FinancePermissions.INVOICE_CREDIT)
            .actsOn(InvoiceEntities.INVOICE, "invoiceId", a -> a.whenField("status", InvoiceEntities.POSTED))
            .contextFactory((start, input) -> {
                ProcessContext ctx = withInput(start, input);
                ctx.put(INVOICE_ID, input.invoiceId());
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, InvoiceOutput.class))
            .step("Load the document", LoadEntity.by(InvoiceEntities.INVOICE_DATASET, INVOICE_ID, INVOICE))
            .step("Load what was applied", QueryEntities.of(InvoiceEntities.APPLICATION_DATASET,
                ctx -> applicationsOf(ctx.get(INVOICE_ID)), APPLICATIONS))
            .compute("Check it", (metadata, ctx) -> {
                EntityInstance invoice = invoice(ctx);
                VoidInput input = ctx.get(INPUT, VoidInput.class);
                if (!InvoiceEntities.POSTED.equals(invoice.get("status"))
                    || !InvoiceEntities.MANUAL.equals(invoice.get("source"))) {
                    ctx.reject(new Violation("invoiceId", NOT_POSTED, "Only a posted document entered here is voided",
                        Map.of("invoiceNo", String.valueOf((Object) invoice.get("invoiceNo")))));
                    return;
                }
                if (Objects.equals(ctx.request().actorId(), invoice.get("preparedBy"))) {
                    ctx.reject(new Violation("invoiceId", OWN_DOCUMENT, "The preparer of " + invoice.get("invoiceNo")
                        + " does not void it", Map.of("invoiceNo", (Object) invoice.get("invoiceNo"))));
                    return;
                }
                if (!list(ctx, APPLICATIONS).isEmpty()) {
                    ctx.reject(new Violation("invoiceId", APPLIED, "Something was applied to "
                        + invoice.get("invoiceNo") + "; take it back first",
                        Map.of("invoiceNo", (Object) invoice.get("invoiceNo"))));
                    return;
                }
                if (input.voidDate().isBefore(invoice.get("invoiceDate"))) {
                    ctx.reject(new Violation("voidDate", INVALID_VALUE, "A document is voided on or after its date",
                        Map.of("value", input.voidDate().toString())));
                    return;
                }
                ctx.put(SUB_INPUT, new SubledgerPosting.ReverseInput("AR",
                    String.valueOf((Object) invoice.get("transactionId")), input.voidDate(),
                    "Void of " + invoice.get("invoiceNo") + ": " + input.reason().trim(), invoice.get("invoiceNo"),
                    InvoiceEntities.SOURCE_ENTITY, String.valueOf(invoice.id())));
            })
            .step("Reverse its entry", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.REVERSE, 1,
                ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT))
            .compute("Record the void", (metadata, ctx) -> {
                if (!ctx.contains(SUB_OUTPUT)) {
                    return;
                }
                EntityInstance invoice = invoice(ctx);
                VoidInput input = ctx.get(INPUT, VoidInput.class);
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("status", InvoiceEntities.VOID);
                values.put("voidDate", input.voidDate());
                values.put("voidReason", input.reason().trim());
                values.put("voidGlNo", ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class).glNo());
                values.put("openAmount", BigDecimal.ZERO.setScale(2));
                values.put("openAmountUsd", BigDecimal.ZERO.setScale(2));
                ctx.changes().update(InvoiceEntities.INVOICE, invoice.id(), invoice.version(), values);
                ctx.put(OUTPUT, output(invoice, values, List.of()));
            }));

    // ---- apply a credit memo ---------------------------------------------------------------------------------------

    public static final ProcessDefinition<ApplyInput, ApplyOutput, ProcessContext> APPLY_PROCESS =
        ProcessDefinition.define(APPLY, 1, ApplyInput.class, ApplyOutput.class, ProcessContext.class, pb -> pb
            .description("Applies a credit memo to an open invoice of the same customer.")
            .permissions(FinancePermissions.INVOICE_PREPARE)
            .contextFactory(InvoiceProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, ApplyOutput.class))
            .step("Load both documents", QueryEntities.of(InvoiceEntities.INVOICE_DATASET, ctx -> {
                ApplyInput input = ctx.get(INPUT, ApplyInput.class);
                return byIds(input.creditMemoId(), input.invoiceId());
            }, FOUND))
            .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                ctx -> SubledgerPosting.periodsOn(ctx.get(INPUT, ApplyInput.class).applicationDate()), PERIODS))
            .compute("Apply the credit", (metadata, ctx) -> apply(ctx)));

    static void apply(ProcessContext ctx) {
        ApplyInput input = ctx.get(INPUT, ApplyInput.class);
        EntityInstance credit = find(ctx, FOUND, input.creditMemoId());
        EntityInstance invoice = find(ctx, FOUND, input.invoiceId());
        String reason = null;
        if (credit == null || !InvoiceEntities.CREDIT_MEMO.equals(credit.get("kind"))
            || !InvoiceEntities.POSTED.equals(credit.get("status"))) {
            reason = "the credit is not a posted credit memo";
        } else if (invoice == null || !InvoiceEntities.INVOICE_KIND.equals(invoice.get("kind"))
            || !InvoiceEntities.POSTED.equals(invoice.get("status"))) {
            reason = "the invoice is not a posted invoice";
        } else if (!Objects.equals(credit.get("customerCode"), invoice.get("customerCode"))
            || !Objects.equals(credit.get("currency"), invoice.get("currency"))) {
            reason = "the credit memo and the invoice are of different customers or currencies";
        } else if (input.amount().compareTo(credit.get("openAmount")) > 0
            || input.amount().compareTo(invoice.get("openAmount")) > 0) {
            reason = "the amount is more than is open on the credit memo or the invoice";
        } else if (input.applicationDate().isBefore(credit.get("invoiceDate"))
            || input.applicationDate().isBefore(invoice.get("invoiceDate"))) {
            reason = "a credit is applied on or after the dates of both documents";
        } else if (!Money.fits(input.amount(), Money.USD_SCALE)) {
            reason = "the amount has more decimals than the currency";
        } else if (((BigDecimal) credit.get("exchangeRate")).compareTo(invoice.get("exchangeRate")) != 0) {
            // The difference is a realized gain or loss, booked from F7 (FIN-FX-003); until then the subledger and
            // the receivables account would part.
            reason = "the credit memo and the invoice were converted at different rates";
        }
        if (reason != null) {
            ctx.reject(new Violation("amount", APPLY_REFUSED, "The credit cannot be applied: " + reason,
                Map.of("reason", reason)));
            return;
        }
        var closed = SubledgerPosting.periodRefusal(list(ctx, PERIODS), "AR", input.applicationDate(),
            "applicationDate");
        if (closed.isPresent()) {
            ctx.reject(closed.get());
            return;
        }
        BigDecimal invoiceOpen = invoice.<BigDecimal>get("openAmount").subtract(input.amount());
        BigDecimal creditOpen = credit.<BigDecimal>get("openAmount").subtract(input.amount());
        // In US dollars at each document's own rate; the whole open amount clears its dollars exactly.
        BigDecimal invoiceUsd = invoiceOpen.signum() == 0 ? invoice.get("openAmountUsd")
            : Money.usd(input.amount().multiply(invoice.get("exchangeRate")));
        BigDecimal creditUsd = creditOpen.signum() == 0 ? credit.get("openAmountUsd")
            : Money.usd(input.amount().multiply(credit.get("exchangeRate")));
        Map<String, Object> application = new LinkedHashMap<>();
        application.put("sourceKind", InvoiceEntities.CREDIT_MEMO);
        application.put("sourceId", String.valueOf(credit.id()));
        application.put("sourceNo", credit.get("invoiceNo"));
        application.put("invoiceId", invoice.id());
        application.put("customerCode", invoice.get("customerCode"));
        application.put("applicationDate", input.applicationDate());
        application.put("amount", input.amount());
        application.put("amountUsd", invoiceUsd);
        Object id = ctx.changes().insert(InvoiceEntities.APPLICATION, application);
        ctx.changes().update(InvoiceEntities.INVOICE, invoice.id(), invoice.version(), Map.of(
            "openAmount", invoiceOpen, "openAmountUsd", invoice.<BigDecimal>get("openAmountUsd").subtract(invoiceUsd)));
        ctx.changes().update(InvoiceEntities.INVOICE, credit.id(), credit.version(), Map.of(
            "openAmount", creditOpen, "openAmountUsd", credit.<BigDecimal>get("openAmountUsd").subtract(creditUsd)));
        ctx.put(OUTPUT, new ApplyOutput(String.valueOf(id), invoiceOpen, creditOpen));
    }

    // ---- approval results ------------------------------------------------------------------------------------------

    /**
     * Marks a held invoice approved or rejected when its approvers decide (FIN-AR-013); an approved one is posted by
     * posting it again, which finds the approval of the same content.
     */
    public static final ProcessDefinition<ApprovalResultInput, InvoiceOutput, ProcessContext>
        APPROVAL_RESULT_PROCESS = ProcessDefinition.define(APPROVAL_RESULT, 1, ApprovalResultInput.class,
            InvoiceOutput.class, ProcessContext.class, pb -> pb
                .description("Marks an invoice held for approval approved or rejected.")
                .permissions(FinancePermissions.SUBLEDGER_POST)
                .internal()
                .contextFactory(InvoiceProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, InvoiceOutput.class))
                .step("Load the invoice", QueryEntities.of(InvoiceEntities.INVOICE_DATASET, ctx -> {
                    ApprovalResultInput input = ctx.get(INPUT, ApprovalResultInput.class);
                    // Approvals of other subjects are none of this process's business.
                    return byIds(SUBJECT.equals(input.subject()) && input.entityId() != null
                        ? uuid(input.entityId()) : null);
                }, FOUND))
                .compute("Mark it", (metadata, ctx) -> {
                    ApprovalResultInput input = ctx.get(INPUT, ApprovalResultInput.class);
                    EntityInstance invoice = list(ctx, FOUND).isEmpty() ? null : list(ctx, FOUND).getFirst();
                    if (invoice == null || !InvoiceEntities.DRAFT.equals(invoice.get("status"))
                        || !Objects.equals(input.requestId(), invoice.get("approvalRequestId"))) {
                        return;
                    }
                    Map<String, Object> values = Map.of("approval",
                        "APPROVED".equals(input.status()) ? "APPROVED" : "REJECTED");
                    ctx.changes().update(InvoiceEntities.INVOICE, invoice.id(), invoice.version(), values);
                    ctx.put(OUTPUT, output(invoice, values, List.of()));
                }));

    // ---- opening open items ----------------------------------------------------------------------------------------

    /**
     * @param invoiceStart    the first number the invoice sequence gives out: a legacy {@code INV-} number from it on
     *                        would collide with a new invoice's
     * @param creditMemoStart the same for credit memos
     */
    public static ProcessDefinition<OpeningInput, OpeningOutput, ProcessContext> openingProcess(long invoiceStart,
        long creditMemoStart) {
        return ProcessDefinition.define(OPENING, 1, OpeningInput.class, OpeningOutput.class, ProcessContext.class,
            pb -> pb
                .description("Brings over the legacy system's open invoices; they add up to the opening receivables.")
                .permissions(FinancePermissions.MIGRATION)
                .contextFactory(InvoiceProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, OpeningOutput.class))
                .step("Load the settings", QueryEntities.of(ArEntities.SETTINGS_DATASET,
                    ctx -> ArSettingsProcesses.current(), SETTINGS))
                .step("Load the merge decisions", QueryEntities.of(com.jabiz.finance.migration.MigrationEntities
                    .DECISION_DATASET, ctx -> CustomerProcesses.customerDecisions(openingCodes(ctx)), DECISIONS))
                .step("Load the customers", QueryEntities.of(ArEntities.CUSTOMER_DATASET,
                    ctx -> CustomerProcesses.byCodes(customerCodes(ctx)), CUSTOMERS))
                .step("Load the opening entry", QueryEntities.of(com.jabiz.finance.gl.JournalEntities.JOURNAL_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("source",
                        com.jabiz.finance.gl.JournalEntities.OPENING)).limit(1).build(), JOURNALS))
                .step("Load its receivables lines", QueryEntities.of(com.jabiz.finance.gl.JournalEntities.LINE_DATASET,
                    ctx -> {
                        List<EntityInstance> journals = list(ctx, JOURNALS);
                        List<EntityInstance> settings = list(ctx, SETTINGS);
                        if (journals.isEmpty() || settings.isEmpty()) {
                            return EntityQuery.builder().where(new QueryPredicate.In("journalId", List.of())).limit(1)
                                .build();
                        }
                        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("journalId", journals.getFirst().id()),
                            new QueryPredicate.Eq("accountCode", settings.getFirst().get("receivableAccount")))))
                            .limit(500).build();
                    }, LINES))
                .step("Look for items brought over", QueryEntities.of(InvoiceEntities.INVOICE_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("source", InvoiceEntities.OPENING))
                        .limit(1).build(), FOUND))
                .compute("Bring the items over", (metadata, ctx) -> opening(ctx, invoiceStart, creditMemoStart)));
    }

    private static final java.util.regex.Pattern NUMBERED = java.util.regex.Pattern.compile("(INV|CM)-(\\d{1,18})");

    static void opening(ProcessContext ctx, long invoiceStart, long creditMemoStart) {
        OpeningInput input = ctx.get(INPUT, OpeningInput.class);
        // Once: further open items would be receivables without an entry.
        if (!list(ctx, FOUND).isEmpty()) {
            ctx.reject(new Violation("items", OPENING_DONE, "The open receivables were brought over already",
                Map.of()));
            return;
        }
        for (int i = 0; i < input.items().size(); i++) {
            OpeningItem item = input.items().get(i);
            java.util.regex.Matcher m = NUMBERED.matcher(item.document().trim());
            if (m.matches() && Long.parseLong(m.group(2)) >= ("INV".equals(m.group(1)) ? invoiceStart
                : creditMemoStart)) {
                ctx.reject(new Violation("items[" + i + "].document", OPENING_NUMBER, "Document " + item.document()
                    + " would collide with the numbering of new documents", Map.of("document", item.document())));
            }
            if (item.dueDate().isBefore(item.invoiceDate())) {
                ctx.reject(new Violation("items[" + i + "].dueDate", INVALID_VALUE, "An item is due on or after its "
                    + "date", Map.of("value", item.dueDate().toString())));
            }
        }
        if (ctx.hasViolations()) {
            return;
        }
        if (list(ctx, SETTINGS).isEmpty()) {
            ctx.reject(new Violation("items", NO_SETTINGS, "The receivables settings are not set", Map.of()));
            return;
        }
        List<EntityInstance> journals = list(ctx, JOURNALS);
        if (journals.isEmpty() || !"POSTED".equals(journals.getFirst().get("status"))) {
            ctx.reject(new Violation("items", OPENING_NONE, "The opening entry is not posted yet", Map.of()));
            return;
        }
        BigDecimal ledger = BigDecimal.ZERO;
        for (EntityInstance line : list(ctx, LINES)) {
            BigDecimal debit = line.get("debit");
            BigDecimal credit = line.get("credit");
            ledger = ledger.add(debit == null ? BigDecimal.ZERO : debit).subtract(credit == null ? BigDecimal.ZERO
                : credit);
        }
        BigDecimal total = input.items().stream().map(OpeningItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(ledger) != 0) {
            ctx.reject(new Violation("items", OPENING_TOTAL, "The open items add up to " + total.toPlainString()
                + ", the opening receivables are " + ledger.toPlainString(), Map.of("total", total, "ledger", ledger,
                "difference", total.subtract(ledger).abs())));
            return;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        for (EntityInstance decision : list(ctx, DECISIONS)) {
            merged.put(decision.get("legacyValue"), decision.get("decidedValue"));
        }
        Map<String, EntityInstance> customers = new LinkedHashMap<>();
        for (EntityInstance customer : list(ctx, CUSTOMERS)) {
            customers.put(customer.get("customerCode"), customer);
        }
        for (int i = 0; i < input.items().size(); i++) {
            OpeningItem item = input.items().get(i);
            String legacy = item.customerCode().trim().toUpperCase(Locale.ROOT);
            String code = merged.getOrDefault(legacy, legacy);
            EntityInstance customer = customers.get(code);
            if (customer == null) {
                ctx.reject(new Violation("items[" + i + "].customerCode", UNKNOWN_CUSTOMER, "There is no customer "
                    + legacy, Map.of("customerCode", legacy)));
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("kind", InvoiceEntities.INVOICE_KIND);
            values.put("invoiceNo", item.document().trim());
            values.put("customerCode", code);
            values.put("invoiceDate", item.invoiceDate());
            values.put("dueDate", item.dueDate());
            values.put("currency", "USD");
            values.put("exchangeRate", BigDecimal.ONE);
            values.put("termsCode", customer.get("termsCode"));
            values.put("taxCode", customer.get("taxCode"));
            values.put("description", "Open item brought over from the legacy system");
            values.put("source", InvoiceEntities.OPENING);
            values.put("status", InvoiceEntities.POSTED);
            values.put("subtotal", item.amount());
            values.put("taxTotal", BigDecimal.ZERO.setScale(2));
            values.put("total", item.amount());
            values.put("totalUsd", item.amount());
            values.put("openAmount", item.amount());
            values.put("openAmountUsd", item.amount());
            ctx.changes().insert(InvoiceEntities.INVOICE, values);
        }
        ctx.put(OUTPUT, new OpeningOutput(input.items().size(), total));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static boolean taxable(EntityInstance code) {
        return code != null && "TAXABLE".equals(code.get("kind"));
    }

    /** The codes the document uses and their charge codes, as loaded. */
    private static List<EntityInstance> codesInUse(ProcessContext ctx) {
        List<EntityInstance> codes = new ArrayList<>(list(ctx, USED_CODES));
        codes.addAll(list(ctx, CODES));
        return codes;
    }

    /** The day the tax rates and certificates are read on: the original invoice's for its credit memo. */
    private static LocalDate taxDate(ProcessContext ctx) {
        EntityInstance invoice = invoice(ctx);
        if (invoice.get("originalInvoiceId") != null && !list(ctx, ORIGINALS).isEmpty()) {
            return list(ctx, ORIGINALS).getFirst().get("invoiceDate");
        }
        return invoice.get("invoiceDate");
    }

    private static Set<String> jurisdictions(ProcessContext ctx) {
        Set<String> jurisdictions = new LinkedHashSet<>();
        for (EntityInstance code : codesInUse(ctx)) {
            String value = code.get("jurisdictions");
            if (value != null) {
                jurisdictions.addAll(Arrays.asList(value.split(",")));
            }
        }
        return jurisdictions;
    }

    private static List<String> openingCodes(ProcessContext ctx) {
        return ctx.get(INPUT, OpeningInput.class).items().stream()
            .map(i -> i.customerCode().trim().toUpperCase(Locale.ROOT)).distinct().toList();
    }

    private static List<String> customerCodes(ProcessContext ctx) {
        Set<String> codes = new LinkedHashSet<>(openingCodes(ctx));
        for (EntityInstance decision : list(ctx, DECISIONS)) {
            codes.add(decision.get("decidedValue"));
        }
        return List.copyOf(codes);
    }

    /** Whether {@code code} is a revenue account that takes postings: no control account, no other type. */
    private static boolean revenueAccount(ProcessContext ctx, String code) {
        return list(ctx, ACCOUNTS).stream().anyMatch(a -> Objects.equals(code, a.get("accountCode"))
            && "REVENUE".equals(a.get("financialType")) && a.get("controlClass") == null);
    }

    static EntityQuery accountsOf(List<String> codes) {
        List<Object> present = codes.stream().filter(c -> c != null && !c.isBlank()).map(String::trim).distinct()
            .map(c -> (Object) c).toList();
        // The returns account may be used without being named.
        return EntityQuery.builder().where(new QueryPredicate.Or(List.of(
                new QueryPredicate.In("accountCode", new ArrayList<>(present)),
                new QueryPredicate.Eq("financialType", "REVENUE")))).limit(500).build();
    }

    static EntityQuery creditsOf(UUID originalId) {
        return EntityQuery.builder().where(new QueryPredicate.In("originalInvoiceId",
            originalId == null ? List.of() : List.of(originalId))).limit(500).build();
    }

    static EntityQuery byIds(UUID... ids) {
        List<Object> present = Arrays.stream(ids).filter(Objects::nonNull).map(id -> (Object) id).toList();
        return EntityQuery.builder().where(new QueryPredicate.In("invoiceId", new ArrayList<>(present)))
            .limit(present.size() + 1).build();
    }

    static EntityQuery linesOf(Object invoiceId) {
        return EntityQuery.builder().where(new QueryPredicate.In("invoiceId",
            invoiceId == null ? List.of() : List.of(invoiceId))).limit(500).build();
    }

    static EntityQuery applicationsOf(Object documentId) {
        return EntityQuery.builder().where(new QueryPredicate.Or(List.of(
            new QueryPredicate.Eq("invoiceId", documentId),
            new QueryPredicate.Eq("sourceId", String.valueOf(documentId))))).limit(1).build();
    }

    private static EntityInstance find(ProcessContext ctx, String key, UUID id) {
        if (id == null) {
            return null;
        }
        return list(ctx, key).stream().filter(e -> id.equals(uuid(e.id()))).findFirst().orElse(null);
    }

    private static Violation notDraft(EntityInstance invoice) {
        return new Violation("invoiceId", NOT_DRAFT, "Document " + (invoice.get("invoiceNo") == null ? invoice.id()
            : invoice.get("invoiceNo")) + " is " + invoice.get("status") + ": a posted document is corrected by a "
            + "credit memo or voided", Map.of("invoiceNo", String.valueOf(invoice.get("invoiceNo") == null
            ? invoice.id() : invoice.get("invoiceNo")), "status", (Object) invoice.get("status")));
    }

    private static InvoiceOutput output(EntityInstance invoice, Map<String, Object> changed, List<String> warnings) {
        Map<String, Object> state = new LinkedHashMap<>(invoice.attributes());
        state.putAll(changed);
        return new InvoiceOutput(String.valueOf(invoice.id()), (String) state.get("invoiceNo"),
            (String) state.get("kind"), (String) state.get("status"), (LocalDate) state.get("dueDate"),
            (BigDecimal) state.get("subtotal"), (BigDecimal) state.get("taxTotal"), (BigDecimal) state.get("total"),
            (BigDecimal) state.get("totalUsd"), (BigDecimal) state.get("openAmount"), (String) state.get("glNo"),
            warnings, (String) state.get("approval"));
    }

    static UUID uuid(Object value) {
        return value == null ? null : value instanceof UUID u ? u : UUID.fromString(value.toString());
    }

    private static String upper(String given, Object fallback) {
        return given != null && !given.isBlank() ? given.trim().toUpperCase(Locale.ROOT) : (String) fallback;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static InvoiceInput saveInput(ProcessContext ctx) {
        return ctx.get(INPUT, InvoiceInput.class);
    }

    private static EntityInstance invoice(ProcessContext ctx) {
        return ctx.get(INVOICE, EntityInstance.class);
    }

    private static Prepared prepared(ProcessContext ctx) {
        return ctx.get(PREPARED, Prepared.class);
    }

    static ProcessContext withInput(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private static ProcessContext withId(ProcessStart start, InvoiceId input) {
        ProcessContext ctx = withInput(start, input);
        ctx.put(INVOICE_ID, input.invoiceId());
        return ctx;
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    /** The invoice number sequence starts at a given number (the sample company's next is 1004). */
    static NumberSequence invoiceNumbers(long first) {
        return NumberSequence.define(INVOICE_NUMBERS, s -> s.format("INV-{n}").startAt(first));
    }

    static NumberSequence creditMemoNumbers(long first) {
        return NumberSequence.define(CREDIT_MEMO_NUMBERS, s -> s.format("CM-{n}").startAt(first));
    }

    private InvoiceProcesses() {}
}

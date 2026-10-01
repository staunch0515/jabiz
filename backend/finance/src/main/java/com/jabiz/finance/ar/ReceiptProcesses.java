package com.jabiz.finance.ar;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.Money;
import com.jabiz.finance.calc.PaymentTerms;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.SubledgerPosting;
import com.jabiz.numbering.NumberSequence;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.numbering.AssignNumber;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.jabiz.finance.ar.InvoiceProcesses.list;
import static com.jabiz.finance.ar.InvoiceProcesses.uuid;

/**
 * Customer receipts and what is applied to invoices (FIN-AR-002, 007, 008; docs/finance/00-design.md section 8):
 * <ul>
 *   <li>{@code FIN_RECEIPT_RECORD}: money received into a bank account, numbered ({@code RCPT-0001}) and posted at
 *       once: the bank is debited, receivables credited with what is applied to invoices (and any early-payment
 *       discount taken, debited to the discount account), and the rest is unapplied cash. Without an unapplied cash
 *       account a receipt is applied in full.</li>
 *   <li>{@code FIN_RECEIPT_APPLY}: unapplied cash of a receipt applied to open invoices later.</li>
 *   <li>{@code FIN_APPLICATION_REVERSE}: an application of a receipt or a credit memo taken back by a new application
 *       of the opposite amount; a receipt's money returns to unapplied cash. The history keeps both, so the open
 *       items of any earlier day stay as they were.</li>
 *   <li>{@code FIN_RECEIPT_REASSIGN}: a receipt recorded for the wrong customer, with nothing of it applied, moved to
 *       the right one.</li>
 *   <li>{@code FIN_RECEIPT_VOID}: a receipt that bounced or was recorded in error, with nothing of it applied, taken
 *       out of the bank again.</li>
 * </ul>
 * Receipts are in US dollars until F7 brings the gains and losses of foreign currency settlements (FIN-FX-003).
 * Everything posts in the receivables subledger, on a day in a period open for it.
 */
public final class ReceiptProcesses {

    public static final String RECORD = "FIN_RECEIPT_RECORD";
    public static final String APPLY = "FIN_RECEIPT_APPLY";
    public static final String REVERSE = "FIN_APPLICATION_REVERSE";
    public static final String REASSIGN = "FIN_RECEIPT_REASSIGN";
    public static final String VOID = "FIN_RECEIPT_VOID";
    public static final String REFUND = "FIN_CREDIT_REFUND";

    public static final String RECEIPT_NUMBERS = "fin.ar.receipt";

    public static final String INVALID_VALUE = "FIN_RECEIPT_INVALID_VALUE";
    public static final String CURRENCY = "FIN_RECEIPT_CURRENCY";
    public static final String BANK_ACCOUNT = "FIN_RECEIPT_BANK_ACCOUNT";
    public static final String NOT_OPEN = "FIN_RECEIPT_INVOICE_NOT_OPEN";
    public static final String OVER_APPLIED = "FIN_RECEIPT_OVER_APPLIED";
    public static final String UNAPPLIED = "FIN_RECEIPT_UNAPPLIED_CASH";
    public static final String DISCOUNT = "FIN_RECEIPT_DISCOUNT";
    public static final String NOT_REVERSIBLE = "FIN_APPLICATION_NOT_REVERSIBLE";
    public static final String APPLIED = "FIN_RECEIPT_APPLIED";
    public static final String NOT_POSTED = "FIN_RECEIPT_NOT_POSTED";
    public static final String NOT_REFUNDABLE = "FIN_CREDIT_NOT_REFUNDABLE";

    /**
     * An invoice and what of it a receipt pays.
     *
     * @param discount the early-payment discount taken with it (FIN-AR-002): it clears the invoice as cash does
     */
    public record ApplicationInput(@NotNull UUID invoiceId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @DecimalMin("0") @Digits(integer = 13, fraction = 2) BigDecimal discount) {}

    /**
     * @param method      {@code CHECK}, {@code ACH}, {@code WIRE} or {@code CARD} (card settlement)
     * @param bankAccount the general ledger account of the bank account it was deposited to
     */
    public record ReceiptInput(@NotBlank @Size(max = 20) String customerCode, @NotNull LocalDate receiptDate,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 10) String method, @Size(max = 100) String reference,
        @NotBlank @Size(max = 20) String bankAccount, @Size(max = 500) String description,
        @Size(max = 200) List<@Valid @NotNull ApplicationInput> applications) {}

    public record ApplyInput(@NotNull UUID receiptId, @NotNull LocalDate applicationDate,
        @NotEmpty @Size(max = 200) List<@Valid @NotNull ApplicationInput> applications) {}

    public record ReverseInput(@NotNull UUID applicationId, @NotNull LocalDate reverseDate,
        @NotBlank @Size(max = 500) String reason) {}

    public record ReassignInput(@NotNull UUID receiptId, @NotBlank @Size(max = 20) String customerCode,
        @NotBlank @Size(max = 500) String reason) {}

    public record VoidInput(@NotNull UUID receiptId, @NotNull LocalDate voidDate,
        @NotBlank @Size(max = 500) String reason) {}

    /** A credit memo's open credit paid out to the customer from a bank account (FIN-AR-006). */
    public record RefundInput(@NotNull UUID creditMemoId, @NotNull LocalDate refundDate,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 10) String method, @Size(max = 100) String reference,
        @NotBlank @Size(max = 20) String bankAccount) {}

    public record RefundOutput(String applicationId, String creditMemoNo, BigDecimal creditOpen, String glNo) {}

    /** @param invoiceOpen what is still open of the invoice afterwards */
    public record Applied(String applicationId, String invoiceId, String invoiceNo, BigDecimal amount,
        BigDecimal discount, BigDecimal invoiceOpen) {}

    public record ReceiptOutput(String receiptId, String receiptNo, String customerCode, String status,
        BigDecimal amount, BigDecimal unappliedAmount, String glNo, List<Applied> applications) {}

    /** @param sourceOpen what is unapplied of the receipt, or open of the credit memo, afterwards */
    public record ReverseOutput(String applicationId, String reversedId, BigDecimal invoiceOpen,
        BigDecimal sourceOpen, String glNo) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String RECEIPT_ID = "receiptId";
    static final String RECEIPT = "receipt";
    static final String APPLICATION_ID = "applicationId";
    static final String APPLICATION = "application";
    static final String CUSTOMERS = "customers";
    static final String SETTINGS = "settings";
    static final String ACCOUNTS = "accounts";
    static final String INVOICES = "invoices";
    static final String TERMS = "terms";
    static final String APPLICATIONS = "applications";
    static final String REVERSALS = "reversals";
    static final String SOURCES = "sources";
    static final String PERIODS = "periods";
    static final String PLAN = "plan";
    static final String NUMBER = "number";
    static final String SUB_INPUT = "subledgerInput";
    static final String SUB_OUTPUT = "subledgerOutput";
    static final String NEW_ID = "newId";

    /** What a receipt pays: each invoice with the amount and the discount, all checked. */
    record Plan(List<Planned> items, BigDecimal applied, BigDecimal discount) {}

    record Planned(EntityInstance invoice, BigDecimal amount, BigDecimal discount) {}

    // ---- record ----------------------------------------------------------------------------------------------------

    public static final ProcessDefinition<ReceiptInput, ReceiptOutput, ProcessContext> RECORD_PROCESS =
        ProcessDefinition.define(RECORD, 1, ReceiptInput.class, ReceiptOutput.class, ProcessContext.class, pb -> pb
            .description("Records a customer receipt and applies it to open invoices; posts it at once.")
            .permissions(FinancePermissions.RECEIPT_RECORD)
            .contextFactory(InvoiceProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, ReceiptOutput.class))
            .step("Load the customer", QueryEntities.of(ArEntities.CUSTOMER_DATASET,
                ctx -> CustomerProcesses.byCode(recordInput(ctx).customerCode()), CUSTOMERS))
            .step("Load the settings", QueryEntities.of(ArEntities.SETTINGS_DATASET,
                ctx -> ArSettingsProcesses.current(), SETTINGS))
            .step("Load the bank account", QueryEntities.of(GlEntities.ACCOUNT_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("accountCode",
                    recordInput(ctx).bankAccount().trim())).limit(1).build(), ACCOUNTS))
            .step("Load the invoices", QueryEntities.of(InvoiceEntities.INVOICE_DATASET,
                ctx -> invoicesOf(recordInput(ctx).applications()), INVOICES))
            .step("Load their terms", QueryEntities.of(ArEntities.PAYMENT_TERMS_DATASET,
                ReceiptProcesses::termsOf, TERMS))
            .step("Load what was applied to them", QueryEntities.of(InvoiceEntities.APPLICATION_DATASET,
                ReceiptProcesses::applicationsOf, APPLICATIONS))
            .compute("Check the receipt", (metadata, ctx) -> checkRecord(ctx))
            .step("Number the receipt", AssignNumber.when(ctx -> ctx.contains(PLAN), RECEIPT_NUMBERS, null, NUMBER))
            .compute("Build the entry", (metadata, ctx) -> buildRecord(ctx))
            // The ledger checks that the document its entry refers to exists.
            .step("Save the receipt", SaveChanges.now())
            .step("Book it", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.POST, 1,
                ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT))
            .compute("Record the receipt", (metadata, ctx) -> recordReceipt(ctx)));

    static void checkRecord(ProcessContext ctx) {
        ReceiptInput input = recordInput(ctx);
        String customerCode = code(input.customerCode());
        EntityInstance customer = first(ctx, CUSTOMERS);
        if (customer == null || !"ACTIVE".equals(customer.get("status"))) {
            ctx.reject(new Violation("customerCode", InvoiceProcesses.UNKNOWN_CUSTOMER, "There is no active customer "
                + customerCode, Map.of("customerCode", customerCode)));
            return;
        }
        EntityInstance settings = settings(ctx);
        if (settings == null) {
            return;
        }
        String method = input.method().trim().toUpperCase(Locale.ROOT);
        if (!ReceiptEntities.METHOD_VALUES.contains(method)) {
            ctx.reject(new Violation("method", INVALID_VALUE, "method must be one of "
                + ReceiptEntities.METHOD_VALUES, Map.of("value", input.method())));
        }
        if (!"USD".equals(customer.get("currency"))) {
            ctx.reject(new Violation("customerCode", CURRENCY, "Receipts in " + customer.get("currency")
                + " come with foreign currency settlement (F7)", Map.of("currency", (Object) customer.get("currency"))));
        }
        EntityInstance bank = first(ctx, ACCOUNTS);
        if (bank == null || !"BANK".equals(bank.get("controlClass"))) {
            ctx.reject(new Violation("bankAccount", BANK_ACCOUNT, "Account " + input.bankAccount().trim()
                + " is not a bank account", Map.of("accountCode", input.bankAccount().trim())));
        }
        if (ctx.hasViolations()) {
            return;
        }
        List<ApplicationInput> applications = input.applications() == null ? List.of() : input.applications();
        Plan plan = plan(ctx, customerCode, input.receiptDate(), input.amount(), applications, settings);
        if (plan == null) {
            return;
        }
        if (plan.applied().compareTo(input.amount()) < 0 && settings.get("unappliedCashAccount") == null) {
            ctx.reject(new Violation("applications", UNAPPLIED, "There is no unapplied cash account: apply all "
                + input.amount().toPlainString() + " of the receipt", Map.of("amount", input.amount(),
                "applied", plan.applied())));
            return;
        }
        ctx.put(PLAN, plan);
    }

    static void buildRecord(ProcessContext ctx) {
        if (!ctx.contains(PLAN)) {
            return;
        }
        ReceiptInput input = recordInput(ctx);
        Plan plan = ctx.get(PLAN, Plan.class);
        EntityInstance settings = settings(ctx);
        String number = ctx.get(NUMBER, String.class);
        BigDecimal unapplied = input.amount().subtract(plan.applied());
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("receiptNo", number);
        receipt.put("customerCode", code(input.customerCode()));
        receipt.put("receiptDate", input.receiptDate());
        receipt.put("amount", input.amount());
        receipt.put("currency", "USD");
        receipt.put("method", input.method().trim().toUpperCase(Locale.ROOT));
        receipt.put("reference", blankToNull(input.reference()));
        receipt.put("bankAccount", input.bankAccount().trim());
        receipt.put("description", blankToNull(input.description()));
        receipt.put("unappliedAmount", unapplied);
        receipt.put("status", ReceiptEntities.POSTED);
        receipt.put("preparedBy", ctx.request().actorId());
        Object id = ctx.changes().insert(ReceiptEntities.RECEIPT, receipt);
        ctx.put(NEW_ID, id);
        List<JournalProcesses.LineInput> lines = new ArrayList<>();
        lines.add(debit(input.bankAccount().trim(), input.amount(), "Receipt " + number));
        lines.addAll(applicationLines(plan, settings, number));
        if (unapplied.signum() > 0) {
            lines.add(credit(settings.get("unappliedCashAccount"), unapplied, "Unapplied cash " + number));
        }
        String description = input.description() != null && !input.description().isBlank()
            ? input.description().trim() : "Receipt " + number + " " + code(input.customerCode());
        ctx.put(SUB_INPUT, new SubledgerPosting.PostInput("AR", input.receiptDate(), description, number,
            ReceiptEntities.SOURCE_ENTITY, String.valueOf(id), lines, List.of("AR", "BANK")));
    }

    static void recordReceipt(ProcessContext ctx) {
        if (!ctx.contains(SUB_OUTPUT)) {
            return;
        }
        ReceiptInput input = recordInput(ctx);
        Object id = ctx.get(NEW_ID);
        String number = ctx.get(NUMBER, String.class);
        SubledgerPosting.PostOutput booked = ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class);
        List<Applied> applied = writeApplications(ctx, ctx.get(PLAN, Plan.class), id, number, input.receiptDate());
        BigDecimal unapplied = input.amount().subtract(ctx.get(PLAN, Plan.class).applied());
        ctx.put(OUTPUT, new ReceiptOutput(String.valueOf(id), number, code(input.customerCode()),
            ReceiptEntities.POSTED, input.amount(), unapplied, booked.glNo(), applied));
    }

    // ---- apply unapplied cash --------------------------------------------------------------------------------------

    public static final ProcessDefinition<ApplyInput, ReceiptOutput, ProcessContext> APPLY_PROCESS =
        ProcessDefinition.define(APPLY, 1, ApplyInput.class, ReceiptOutput.class, ProcessContext.class, pb -> pb
            .description("Applies a receipt's unapplied cash to open invoices of its customer.")
            .permissions(FinancePermissions.RECEIPT_RECORD)
            .actsOn(ReceiptEntities.RECEIPT, "receiptId", a -> a.whenField("status", ReceiptEntities.POSTED))
            .contextFactory((start, input) -> {
                ProcessContext ctx = InvoiceProcesses.withInput(start, input);
                ctx.put(RECEIPT_ID, input.receiptId());
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, ReceiptOutput.class))
            .step("Load the receipt", LoadEntity.by(ReceiptEntities.RECEIPT_DATASET, RECEIPT_ID, RECEIPT))
            .step("Load the settings", QueryEntities.of(ArEntities.SETTINGS_DATASET,
                ctx -> ArSettingsProcesses.current(), SETTINGS))
            .step("Load the invoices", QueryEntities.of(InvoiceEntities.INVOICE_DATASET,
                ctx -> invoicesOf(ctx.get(INPUT, ApplyInput.class).applications()), INVOICES))
            .step("Load their terms", QueryEntities.of(ArEntities.PAYMENT_TERMS_DATASET,
                ReceiptProcesses::termsOf, TERMS))
            .step("Load what was applied to them", QueryEntities.of(InvoiceEntities.APPLICATION_DATASET,
                ReceiptProcesses::applicationsOf, APPLICATIONS))
            .compute("Check the application", (metadata, ctx) -> checkApply(ctx))
            .step("Book it", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.POST, 1,
                ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT))
            .compute("Record the application", (metadata, ctx) -> recordApply(ctx)));

    static void checkApply(ProcessContext ctx) {
        ApplyInput input = ctx.get(INPUT, ApplyInput.class);
        EntityInstance receipt = ctx.get(RECEIPT, EntityInstance.class);
        if (!ReceiptEntities.POSTED.equals(receipt.get("status"))) {
            ctx.reject(notPosted(receipt));
            return;
        }
        if (input.applicationDate().isBefore(receipt.get("receiptDate"))) {
            ctx.reject(new Violation("applicationDate", INVALID_VALUE, "A receipt is applied on or after the day it "
                + "came in", Map.of("value", input.applicationDate().toString())));
            return;
        }
        EntityInstance settings = settings(ctx);
        if (settings == null) {
            return;
        }
        if (settings.get("unappliedCashAccount") == null) {
            ctx.reject(new Violation("receiptId", UNAPPLIED, "There is no unapplied cash account", Map.of(
                "amount", receipt.get("unappliedAmount"), "applied", BigDecimal.ZERO)));
            return;
        }
        Plan plan = plan(ctx, receipt.get("customerCode"), input.applicationDate(), receipt.get("unappliedAmount"),
            input.applications(), settings);
        if (plan == null) {
            return;
        }
        ctx.put(PLAN, plan);
        String number = receipt.get("receiptNo");
        List<JournalProcesses.LineInput> lines = new ArrayList<>();
        lines.add(debit(settings.get("unappliedCashAccount"), plan.applied(), "Unapplied cash " + number));
        lines.addAll(applicationLines(plan, settings, number));
        ctx.put(SUB_INPUT, new SubledgerPosting.PostInput("AR", input.applicationDate(), "Application of " + number,
            number, ReceiptEntities.SOURCE_ENTITY, String.valueOf(receipt.id()), lines, List.of("AR")));
    }

    static void recordApply(ProcessContext ctx) {
        if (!ctx.contains(SUB_OUTPUT)) {
            return;
        }
        ApplyInput input = ctx.get(INPUT, ApplyInput.class);
        EntityInstance receipt = ctx.get(RECEIPT, EntityInstance.class);
        Plan plan = ctx.get(PLAN, Plan.class);
        List<Applied> applied = writeApplications(ctx, plan, receipt.id(), receipt.get("receiptNo"),
            input.applicationDate());
        BigDecimal unapplied = receipt.<BigDecimal>get("unappliedAmount").subtract(plan.applied());
        ctx.changes().update(ReceiptEntities.RECEIPT, receipt.id(), receipt.version(),
            Map.of("unappliedAmount", unapplied));
        ctx.put(OUTPUT, new ReceiptOutput(String.valueOf(receipt.id()), receipt.get("receiptNo"),
            receipt.get("customerCode"), receipt.get("status"), receipt.get("amount"), unapplied,
            ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class).glNo(), applied));
    }

    // ---- shared: what a receipt pays -------------------------------------------------------------------------------

    /**
     * Checks what a receipt of {@code customerCode} pays on {@code date} out of {@code available}: posted invoices of
     * the customer, in US dollars, dated on or before the day, each paid no more than is open of it; a discount only
     * within the invoice's discount days and up to its terms' discount, less discounts taken before. Null, with the
     * refusals recorded, when anything is wrong.
     */
    static Plan plan(ProcessContext ctx, String customerCode, LocalDate date, BigDecimal available,
        List<ApplicationInput> applications, EntityInstance settings) {
        Map<UUID, EntityInstance> invoices = new LinkedHashMap<>();
        for (EntityInstance invoice : list(ctx, INVOICES)) {
            invoices.put(uuid(invoice.id()), invoice);
        }
        Map<String, EntityInstance> terms = new LinkedHashMap<>();
        for (EntityInstance row : list(ctx, TERMS)) {
            terms.put(row.get("termsCode"), row);
        }
        Map<UUID, BigDecimal> discounted = new LinkedHashMap<>();
        for (EntityInstance row : list(ctx, APPLICATIONS)) {
            if (row.get("discount") != null) {
                discounted.merge(uuid(row.get("invoiceId")), row.get("discount"), BigDecimal::add);
            }
        }
        Map<UUID, BigDecimal> taking = new LinkedHashMap<>();
        Map<UUID, BigDecimal> discounting = new LinkedHashMap<>();
        List<Planned> items = new ArrayList<>();
        BigDecimal applied = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        for (int i = 0; i < applications.size(); i++) {
            ApplicationInput application = applications.get(i);
            String field = "applications[" + i + "]";
            EntityInstance invoice = invoices.get(application.invoiceId());
            String reason = null;
            if (invoice == null || !InvoiceEntities.INVOICE_KIND.equals(invoice.get("kind"))
                || !InvoiceEntities.POSTED.equals(invoice.get("status"))) {
                reason = "it is not a posted invoice";
            } else if (!Objects.equals(customerCode, invoice.get("customerCode"))) {
                reason = "it is " + invoice.get("customerCode") + "'s, the receipt is " + customerCode + "'s";
            } else if (!"USD".equals(invoice.get("currency"))) {
                reason = "it is in " + invoice.get("currency");
            } else if (date.isBefore(invoice.get("invoiceDate"))) {
                reason = "it is dated after the receipt";
            }
            if (reason != null) {
                ctx.reject(new Violation(field + ".invoiceId", NOT_OPEN, "The receipt cannot pay "
                    + (invoice == null ? application.invoiceId() : invoice.get("invoiceNo")) + ": " + reason,
                    Map.of("invoiceNo", String.valueOf(invoice == null ? application.invoiceId()
                        : invoice.get("invoiceNo")), "reason", reason)));
                continue;
            }
            BigDecimal amount = application.amount();
            BigDecimal taken = application.discount() == null ? BigDecimal.ZERO : application.discount();
            if (taken.signum() > 0) {
                EntityInstance invoiceTerms = terms.get(invoice.get("termsCode"));
                PaymentTerms paymentTerms = invoiceTerms == null ? null : new PaymentTerms(
                    invoiceTerms.<BigDecimal>get("netDays").intValueExact(), invoiceTerms.get("discountPercent"),
                    invoiceTerms.get("discountDays") == null ? null
                        : invoiceTerms.<BigDecimal>get("discountDays").intValueExact(),
                    Boolean.TRUE.equals(invoiceTerms.get("endOfMonth")));
                BigDecimal allowed = paymentTerms == null ? BigDecimal.ZERO
                    : paymentTerms.discount(invoice.get("total"))
                        .subtract(discounted.getOrDefault(application.invoiceId(), BigDecimal.ZERO))
                        .subtract(discounting.getOrDefault(application.invoiceId(), BigDecimal.ZERO));
                String why = null;
                if (settings.get("discountAccount") == null) {
                    why = "there is no sales discount account";
                } else if (paymentTerms == null || !paymentTerms.discountAvailable(invoice.get("invoiceDate"), date)) {
                    why = "the terms give no discount on " + date;
                } else if (taken.compareTo(allowed) > 0) {
                    why = "the terms give at most " + allowed.max(BigDecimal.ZERO).toPlainString();
                }
                if (why != null) {
                    ctx.reject(new Violation(field + ".discount", DISCOUNT, "No discount of " + taken.toPlainString()
                        + " on " + invoice.get("invoiceNo") + ": " + why, Map.of("invoiceNo",
                        (Object) invoice.get("invoiceNo"), "reason", why)));
                    continue;
                }
                discounting.merge(application.invoiceId(), taken, BigDecimal::add);
            }
            BigDecimal clearing = taking.getOrDefault(application.invoiceId(), BigDecimal.ZERO).add(amount).add(taken);
            if (clearing.compareTo(invoice.get("openAmount")) > 0) {
                ctx.reject(new Violation(field + ".amount", OVER_APPLIED, invoice.get("invoiceNo") + " has "
                    + invoice.<BigDecimal>get("openAmount").toPlainString() + " open; " + clearing.toPlainString()
                    + " would be applied", Map.of("what", invoice.get("invoiceNo") + " open "
                    + invoice.<BigDecimal>get("openAmount").toPlainString())));
                continue;
            }
            taking.put(application.invoiceId(), clearing);
            items.add(new Planned(invoice, amount, taken));
            applied = applied.add(amount);
            discount = discount.add(taken);
        }
        if (applied.compareTo(available) > 0) {
            ctx.reject(new Violation("applications", OVER_APPLIED, "The applications come to "
                + applied.toPlainString() + "; the receipt has " + available.toPlainString(),
                Map.of("what", "receipt " + available.toPlainString())));
        }
        return ctx.hasViolations() ? null : new Plan(List.copyOf(items), applied, discount);
    }

    /** Receivables credited with what is applied and discounted; the discounts debited to their account. */
    private static List<JournalProcesses.LineInput> applicationLines(Plan plan, EntityInstance settings,
        String number) {
        List<JournalProcesses.LineInput> lines = new ArrayList<>();
        if (plan.discount().signum() > 0) {
            lines.add(debit(settings.get("discountAccount"), plan.discount(), "Sales discount " + number));
        }
        BigDecimal cleared = plan.applied().add(plan.discount());
        if (cleared.signum() > 0) {
            Set<String> invoices = new LinkedHashSet<>();
            plan.items().forEach(p -> invoices.add(p.invoice().get("invoiceNo")));
            lines.add(credit(settings.get("receivableAccount"), cleared, memo(number + " to "
                + String.join(", ", invoices))));
        }
        return lines;
    }

    private static List<Applied> writeApplications(ProcessContext ctx, Plan plan, Object receiptId, String number,
        LocalDate date) {
        Map<Object, BigDecimal> open = new LinkedHashMap<>();
        Map<Object, EntityInstance> invoices = new LinkedHashMap<>();
        List<Applied> applied = new ArrayList<>();
        for (Planned item : plan.items()) {
            EntityInstance invoice = item.invoice();
            invoices.put(invoice.id(), invoice);
            BigDecimal before = open.getOrDefault(invoice.id(), invoice.get("openAmount"));
            BigDecimal after = before.subtract(item.amount()).subtract(item.discount());
            open.put(invoice.id(), after);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceKind", InvoiceEntities.RECEIPT_SOURCE);
            row.put("sourceId", String.valueOf(receiptId));
            row.put("sourceNo", number);
            row.put("invoiceId", invoice.id());
            row.put("customerCode", invoice.get("customerCode"));
            row.put("applicationDate", date);
            row.put("amount", item.amount());
            row.put("amountUsd", item.amount());
            row.put("discount", item.discount().signum() == 0 ? null : item.discount());
            Object id = ctx.changes().insert(InvoiceEntities.APPLICATION, row);
            applied.add(new Applied(String.valueOf(id), String.valueOf(invoice.id()), invoice.get("invoiceNo"),
                item.amount(), item.discount(), after));
        }
        for (Map.Entry<Object, BigDecimal> entry : open.entrySet()) {
            EntityInstance invoice = invoices.get(entry.getKey());
            // In US dollars, so the dollars open are the amount open.
            ctx.changes().update(InvoiceEntities.INVOICE, invoice.id(), invoice.version(),
                Map.of("openAmount", entry.getValue(), "openAmountUsd", entry.getValue()));
        }
        return applied;
    }

    // ---- take an application back ----------------------------------------------------------------------------------

    public static final ProcessDefinition<ReverseInput, ReverseOutput, ProcessContext> REVERSE_PROCESS =
        ProcessDefinition.define(REVERSE, 1, ReverseInput.class, ReverseOutput.class, ProcessContext.class, pb -> pb
            .description("Takes back an application of a receipt or credit memo; the history keeps both.")
            .permissions(FinancePermissions.RECEIPT_RECORD)
            .actsOn(InvoiceEntities.APPLICATION, "applicationId")
            .contextFactory((start, input) -> {
                ProcessContext ctx = InvoiceProcesses.withInput(start, input);
                ctx.put(APPLICATION_ID, input.applicationId());
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, ReverseOutput.class))
            .step("Load the application", LoadEntity.by(InvoiceEntities.APPLICATION_DATASET, APPLICATION_ID,
                APPLICATION))
            .step("Look for its reversal", QueryEntities.of(InvoiceEntities.APPLICATION_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("reversesApplicationId",
                    ctx.get(APPLICATION_ID))).limit(1).build(), REVERSALS))
            .step("Load the invoice and the credit memo", QueryEntities.of(InvoiceEntities.INVOICE_DATASET, ctx -> {
                EntityInstance application = application(ctx);
                return InvoiceProcesses.byIds(uuid(application.get("invoiceId")),
                    InvoiceEntities.CREDIT_MEMO.equals(application.get("sourceKind"))
                        ? uuid(application.get("sourceId")) : null);
            }, INVOICES))
            .step("Load the receipt", QueryEntities.of(ReceiptEntities.RECEIPT_DATASET, ctx -> {
                EntityInstance application = application(ctx);
                List<Object> ids = InvoiceEntities.RECEIPT_SOURCE.equals(application.get("sourceKind"))
                    ? List.of(uuid(application.get("sourceId"))) : List.of();
                return EntityQuery.builder().where(new QueryPredicate.In("receiptId", new ArrayList<>(ids)))
                    .limit(1).build();
            }, SOURCES))
            .step("Load the settings", QueryEntities.of(ArEntities.SETTINGS_DATASET,
                ctx -> ArSettingsProcesses.current(), SETTINGS))
            .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                ctx -> SubledgerPosting.periodsOn(ctx.get(INPUT, ReverseInput.class).reverseDate()), PERIODS))
            .compute("Check it", (metadata, ctx) -> checkReverse(ctx))
            .step("Book it", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.POST, 1,
                ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT))
            .compute("Record the reversal", (metadata, ctx) -> recordReverse(ctx)));

    static final String READY = "ready";

    static void checkReverse(ProcessContext ctx) {
        ReverseInput input = ctx.get(INPUT, ReverseInput.class);
        EntityInstance application = application(ctx);
        String kind = application.get("sourceKind");
        String reason = null;
        if (application.get("reversesApplicationId") != null) {
            reason = "it takes back another application";
        } else if (!list(ctx, REVERSALS).isEmpty()) {
            reason = "it was taken back already";
        } else if (!InvoiceEntities.RECEIPT_SOURCE.equals(kind) && !InvoiceEntities.CREDIT_MEMO.equals(kind)) {
            reason = "only applications of receipts and credit memos are taken back; a write-off is recovered";
        } else if (input.reverseDate().isBefore(application.get("applicationDate"))) {
            reason = "it is taken back on or after the day it was applied";
        }
        EntityInstance invoice = find(ctx, INVOICES, application.get("invoiceId"));
        EntityInstance source = InvoiceEntities.RECEIPT_SOURCE.equals(kind) ? first(ctx, SOURCES)
            : find(ctx, INVOICES, application.get("sourceId"));
        if (reason == null && (invoice == null || source == null || !InvoiceEntities.POSTED.equals(source.get(
            "status")))) {
            reason = "what it applied is no longer posted";
        }
        EntityInstance settings = InvoiceEntities.RECEIPT_SOURCE.equals(kind) ? settings(ctx) : null;
        if (reason == null && InvoiceEntities.RECEIPT_SOURCE.equals(kind) && settings != null
            && settings.get("unappliedCashAccount") == null) {
            reason = "there is no unapplied cash account to take the money back to";
        }
        if (reason != null) {
            ctx.reject(new Violation("applicationId", NOT_REVERSIBLE, "The application cannot be taken back: "
                + reason, Map.of("reason", reason)));
            return;
        }
        if (ctx.hasViolations()) {
            return;
        }
        var closed = SubledgerPosting.periodRefusal(list(ctx, PERIODS), "AR", input.reverseDate(), "reverseDate");
        if (closed.isPresent()) {
            ctx.reject(closed.get());
            return;
        }
        ctx.put(READY, Boolean.TRUE);
        if (InvoiceEntities.RECEIPT_SOURCE.equals(kind)) {
            BigDecimal amount = application.get("amount");
            BigDecimal discount = application.get("discount") == null ? BigDecimal.ZERO : application.get("discount");
            String number = source.get("receiptNo");
            List<JournalProcesses.LineInput> lines = new ArrayList<>();
            lines.add(debit(settings.get("receivableAccount"), amount.add(discount), memo(number + " taken back from "
                + invoice.get("invoiceNo"))));
            lines.add(credit(settings.get("unappliedCashAccount"), amount, "Unapplied cash " + number));
            if (discount.signum() > 0) {
                if (settings.get("discountAccount") == null) {
                    ctx.reject(new Violation("applicationId", NOT_REVERSIBLE, "The application cannot be taken back: "
                        + "there is no sales discount account", Map.of("reason", "no sales discount account")));
                    ctx.put(READY, Boolean.FALSE);
                    return;
                }
                lines.add(credit(settings.get("discountAccount"), discount, "Sales discount " + number));
            }
            ctx.put(SUB_INPUT, new SubledgerPosting.PostInput("AR", input.reverseDate(), memo("Application of "
                + number + " to " + invoice.get("invoiceNo") + " taken back: " + input.reason().trim()), number,
                ReceiptEntities.SOURCE_ENTITY, String.valueOf(source.id()), lines, List.of("AR")));
        }
    }

    static void recordReverse(ProcessContext ctx) {
        if (!Boolean.TRUE.equals(ctx.get(READY)) || ctx.hasViolations()) {
            return;
        }
        ReverseInput input = ctx.get(INPUT, ReverseInput.class);
        EntityInstance application = application(ctx);
        boolean receipt = InvoiceEntities.RECEIPT_SOURCE.equals(application.get("sourceKind"));
        if (receipt && !ctx.contains(SUB_OUTPUT)) {
            return;
        }
        EntityInstance invoice = find(ctx, INVOICES, application.get("invoiceId"));
        EntityInstance source = receipt ? first(ctx, SOURCES) : find(ctx, INVOICES, application.get("sourceId"));
        BigDecimal amount = application.get("amount");
        BigDecimal discount = application.get("discount") == null ? BigDecimal.ZERO : application.get("discount");
        BigDecimal amountUsd = application.get("amountUsd");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sourceKind", application.get("sourceKind"));
        row.put("sourceId", application.get("sourceId"));
        row.put("sourceNo", application.get("sourceNo"));
        row.put("invoiceId", application.get("invoiceId"));
        row.put("customerCode", application.get("customerCode"));
        row.put("applicationDate", input.reverseDate());
        row.put("amount", amount.negate());
        row.put("amountUsd", amountUsd.negate());
        row.put("discount", discount.signum() == 0 ? null : discount.negate());
        row.put("reversesApplicationId", application.id());
        row.put("reason", input.reason().trim());
        Object id = ctx.changes().insert(InvoiceEntities.APPLICATION, row);
        BigDecimal invoiceOpen = invoice.<BigDecimal>get("openAmount").add(amount).add(discount);
        Map<String, Object> invoiceChanges = new LinkedHashMap<>();
        invoiceChanges.put("openAmount", invoiceOpen);
        invoiceChanges.put("openAmountUsd", invoice.<BigDecimal>get("openAmountUsd").add(amountUsd).add(discount));
        if (InvoiceEntities.WRITTEN_OFF.equals(invoice.get("status"))) {
            invoiceChanges.put("status", InvoiceEntities.POSTED);
        }
        ctx.changes().update(InvoiceEntities.INVOICE, invoice.id(), invoice.version(), invoiceChanges);
        BigDecimal sourceOpen;
        if (receipt) {
            sourceOpen = source.<BigDecimal>get("unappliedAmount").add(amount);
            ctx.changes().update(ReceiptEntities.RECEIPT, source.id(), source.version(),
                Map.of("unappliedAmount", sourceOpen));
        } else {
            sourceOpen = source.<BigDecimal>get("openAmount").add(amount);
            ctx.changes().update(InvoiceEntities.INVOICE, source.id(), source.version(), Map.of(
                "openAmount", sourceOpen, "openAmountUsd", source.<BigDecimal>get("openAmountUsd").add(amountUsd)));
        }
        ctx.put(OUTPUT, new ReverseOutput(String.valueOf(id), String.valueOf(application.id()), invoiceOpen,
            sourceOpen, receipt ? ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class).glNo() : null));
    }

    // ---- reassign and void -----------------------------------------------------------------------------------------

    public static final ProcessDefinition<ReassignInput, ReceiptOutput, ProcessContext> REASSIGN_PROCESS =
        ProcessDefinition.define(REASSIGN, 1, ReassignInput.class, ReceiptOutput.class, ProcessContext.class, pb -> pb
            .description("Moves a receipt with nothing applied to the customer who paid it.")
            .permissions(FinancePermissions.RECEIPT_RECORD)
            .actsOn(ReceiptEntities.RECEIPT, "receiptId", a -> a.whenField("status", ReceiptEntities.POSTED))
            .contextFactory((start, input) -> {
                ProcessContext ctx = InvoiceProcesses.withInput(start, input);
                ctx.put(RECEIPT_ID, input.receiptId());
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, ReceiptOutput.class))
            .step("Load the receipt", LoadEntity.by(ReceiptEntities.RECEIPT_DATASET, RECEIPT_ID, RECEIPT))
            .step("Load the customer", QueryEntities.of(ArEntities.CUSTOMER_DATASET,
                ctx -> CustomerProcesses.byCode(ctx.get(INPUT, ReassignInput.class).customerCode()), CUSTOMERS))
            .compute("Move it", (metadata, ctx) -> {
                ReassignInput input = ctx.get(INPUT, ReassignInput.class);
                EntityInstance receipt = ctx.get(RECEIPT, EntityInstance.class);
                String customerCode = code(input.customerCode());
                EntityInstance customer = first(ctx, CUSTOMERS);
                if (!ReceiptEntities.POSTED.equals(receipt.get("status"))) {
                    ctx.reject(notPosted(receipt));
                    return;
                }
                if (receipt.<BigDecimal>get("unappliedAmount").compareTo(receipt.get("amount")) != 0) {
                    ctx.reject(applied(receipt));
                    return;
                }
                if (customer == null || !"ACTIVE".equals(customer.get("status"))
                    || !Objects.equals(customer.get("currency"), receipt.get("currency"))) {
                    ctx.reject(new Violation("customerCode", InvoiceProcesses.UNKNOWN_CUSTOMER, "There is no active "
                        + "customer " + customerCode + " in " + receipt.get("currency"),
                        Map.of("customerCode", customerCode)));
                    return;
                }
                ctx.changes().update(ReceiptEntities.RECEIPT, receipt.id(), receipt.version(),
                    Map.of("customerCode", customerCode));
                ctx.put(OUTPUT, new ReceiptOutput(String.valueOf(receipt.id()), receipt.get("receiptNo"), customerCode,
                    receipt.get("status"), receipt.get("amount"), receipt.get("unappliedAmount"), null, List.of()));
            }));

    public static final ProcessDefinition<VoidInput, ReceiptOutput, ProcessContext> VOID_PROCESS =
        ProcessDefinition.define(VOID, 1, VoidInput.class, ReceiptOutput.class, ProcessContext.class, pb -> pb
            .description("Voids a receipt with nothing applied: the money leaves the bank account again.")
            .permissions(FinancePermissions.RECEIPT_VOID)
            .actsOn(ReceiptEntities.RECEIPT, "receiptId", a -> a.whenField("status", ReceiptEntities.POSTED))
            .contextFactory((start, input) -> {
                ProcessContext ctx = InvoiceProcesses.withInput(start, input);
                ctx.put(RECEIPT_ID, input.receiptId());
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, ReceiptOutput.class))
            .step("Load the receipt", LoadEntity.by(ReceiptEntities.RECEIPT_DATASET, RECEIPT_ID, RECEIPT))
            .step("Load the settings", QueryEntities.of(ArEntities.SETTINGS_DATASET,
                ctx -> ArSettingsProcesses.current(), SETTINGS))
            .compute("Check it", (metadata, ctx) -> {
                VoidInput input = ctx.get(INPUT, VoidInput.class);
                EntityInstance receipt = ctx.get(RECEIPT, EntityInstance.class);
                if (!ReceiptEntities.POSTED.equals(receipt.get("status"))) {
                    ctx.reject(notPosted(receipt));
                    return;
                }
                if (Objects.equals(ctx.request().actorId(), receipt.get("preparedBy"))) {
                    ctx.reject(new Violation("receiptId", InvoiceProcesses.OWN_DOCUMENT, "The preparer of "
                        + receipt.get("receiptNo") + " does not void it",
                        Map.of("invoiceNo", (Object) receipt.get("receiptNo"))));
                    return;
                }
                if (receipt.<BigDecimal>get("unappliedAmount").compareTo(receipt.get("amount")) != 0) {
                    ctx.reject(applied(receipt));
                    return;
                }
                if (input.voidDate().isBefore(receipt.get("receiptDate"))) {
                    ctx.reject(new Violation("voidDate", INVALID_VALUE, "A receipt is voided on or after its date",
                        Map.of("value", input.voidDate().toString())));
                    return;
                }
                EntityInstance settings = settings(ctx);
                if (settings == null) {
                    return;
                }
                // All of it waits as unapplied cash: that, not receivables, leaves with the money.
                String number = receipt.get("receiptNo");
                List<JournalProcesses.LineInput> lines = List.of(
                    debit(settings.get("unappliedCashAccount"), receipt.get("amount"), "Unapplied cash " + number),
                    credit(receipt.get("bankAccount"), receipt.get("amount"), "Void of " + number));
                ctx.put(SUB_INPUT, new SubledgerPosting.PostInput("AR", input.voidDate(), memo("Void of " + number
                    + ": " + input.reason().trim()), number, ReceiptEntities.SOURCE_ENTITY,
                    String.valueOf(receipt.id()), lines, List.of("AR", "BANK")));
            })
            .step("Book it", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.POST, 1,
                ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT))
            .compute("Record the void", (metadata, ctx) -> {
                if (!ctx.contains(SUB_OUTPUT)) {
                    return;
                }
                VoidInput input = ctx.get(INPUT, VoidInput.class);
                EntityInstance receipt = ctx.get(RECEIPT, EntityInstance.class);
                String glNo = ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class).glNo();
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("status", ReceiptEntities.VOID);
                values.put("unappliedAmount", BigDecimal.ZERO.setScale(2));
                values.put("voidDate", input.voidDate());
                values.put("voidReason", input.reason().trim());
                values.put("voidGlNo", glNo);
                ctx.changes().update(ReceiptEntities.RECEIPT, receipt.id(), receipt.version(), values);
                ctx.put(OUTPUT, new ReceiptOutput(String.valueOf(receipt.id()), receipt.get("receiptNo"),
                    receipt.get("customerCode"), ReceiptEntities.VOID, receipt.get("amount"),
                    BigDecimal.ZERO.setScale(2), glNo, List.of()));
            }));

    // ---- refund a credit memo --------------------------------------------------------------------------------------

    static final String CREDIT_ID = "creditMemoId";

    public static final ProcessDefinition<RefundInput, RefundOutput, ProcessContext> REFUND_PROCESS =
        ProcessDefinition.define(REFUND, 1, RefundInput.class, RefundOutput.class, ProcessContext.class, pb -> pb
            .description("Pays a credit memo's open credit back to the customer from a bank account.")
            .permissions(FinancePermissions.INVOICE_CREDIT)
            .actsOn(InvoiceEntities.INVOICE, "creditMemoId", a -> a.whenField("kind", InvoiceEntities.CREDIT_MEMO))
            .contextFactory((start, input) -> {
                ProcessContext ctx = InvoiceProcesses.withInput(start, input);
                ctx.put(CREDIT_ID, input.creditMemoId());
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, RefundOutput.class))
            .step("Load the credit memo", LoadEntity.by(InvoiceEntities.INVOICE_DATASET, CREDIT_ID, RECEIPT))
            .step("Load the settings", QueryEntities.of(ArEntities.SETTINGS_DATASET,
                ctx -> ArSettingsProcesses.current(), SETTINGS))
            .step("Load the bank account", QueryEntities.of(GlEntities.ACCOUNT_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("accountCode",
                    ctx.get(INPUT, RefundInput.class).bankAccount().trim())).limit(1).build(), ACCOUNTS))
            .compute("Check it", (metadata, ctx) -> {
                RefundInput input = ctx.get(INPUT, RefundInput.class);
                EntityInstance credit = ctx.get(RECEIPT, EntityInstance.class);
                String reason = null;
                if (!InvoiceEntities.CREDIT_MEMO.equals(credit.get("kind"))
                    || !InvoiceEntities.POSTED.equals(credit.get("status"))) {
                    reason = "it is not a posted credit memo";
                } else if (!"USD".equals(credit.get("currency"))) {
                    reason = "refunds in " + credit.get("currency") + " come with foreign currency settlement (F7)";
                } else if (input.amount().compareTo(credit.get("openAmount")) > 0) {
                    reason = credit.<BigDecimal>get("openAmount").toPlainString() + " of it is open";
                } else if (input.refundDate().isBefore(credit.get("invoiceDate"))) {
                    reason = "a refund is dated on or after the credit memo";
                } else if (!ReceiptEntities.METHOD_VALUES.contains(input.method().trim().toUpperCase(Locale.ROOT))) {
                    reason = "method must be one of " + ReceiptEntities.METHOD_VALUES;
                }
                if (reason != null) {
                    ctx.reject(new Violation("creditMemoId", NOT_REFUNDABLE, "Nothing is refunded: " + reason,
                        Map.of("reason", reason)));
                    return;
                }
                EntityInstance bank = first(ctx, ACCOUNTS);
                if (bank == null || !"BANK".equals(bank.get("controlClass"))) {
                    ctx.reject(new Violation("bankAccount", BANK_ACCOUNT, "Account " + input.bankAccount().trim()
                        + " is not a bank account", Map.of("accountCode", input.bankAccount().trim())));
                    return;
                }
                EntityInstance settings = settings(ctx);
                if (settings == null) {
                    return;
                }
                String number = credit.get("invoiceNo");
                List<JournalProcesses.LineInput> lines = List.of(
                    debit(settings.get("receivableAccount"), input.amount(), "Refund of " + number),
                    credit(input.bankAccount().trim(), input.amount(), "Refund of " + number));
                ctx.put(SUB_INPUT, new SubledgerPosting.PostInput("AR", input.refundDate(), memo("Refund of " + number
                    + " to " + credit.get("customerCode")), number, InvoiceEntities.SOURCE_ENTITY,
                    String.valueOf(credit.id()), lines, List.of("AR", "BANK")));
            })
            .step("Book it", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.POST, 1,
                ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT))
            .compute("Record the refund", (metadata, ctx) -> {
                if (!ctx.contains(SUB_OUTPUT)) {
                    return;
                }
                RefundInput input = ctx.get(INPUT, RefundInput.class);
                EntityInstance credit = ctx.get(RECEIPT, EntityInstance.class);
                // Applied to the credit memo itself: what is open of it shrinks as an invoice's does when paid.
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sourceKind", InvoiceEntities.REFUND_SOURCE);
                row.put("sourceId", String.valueOf(credit.id()));
                row.put("sourceNo", credit.get("invoiceNo"));
                row.put("invoiceId", credit.id());
                row.put("customerCode", credit.get("customerCode"));
                row.put("applicationDate", input.refundDate());
                row.put("amount", input.amount());
                row.put("amountUsd", input.amount());
                row.put("reason", blankToNull(input.reference()));
                Object id = ctx.changes().insert(InvoiceEntities.APPLICATION, row);
                BigDecimal open = credit.<BigDecimal>get("openAmount").subtract(input.amount());
                ctx.changes().update(InvoiceEntities.INVOICE, credit.id(), credit.version(), Map.of("openAmount", open,
                    "openAmountUsd", credit.<BigDecimal>get("openAmountUsd").subtract(input.amount())));
                ctx.put(OUTPUT, new RefundOutput(String.valueOf(id), credit.get("invoiceNo"), open,
                    ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class).glNo()));
            }));

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** Receipt numbers without gaps, {@code RCPT-0001} on. */
    static NumberSequence receiptNumbers() {
        return NumberSequence.define(RECEIPT_NUMBERS, s -> s.format("RCPT-{n:4}"));
    }

    static EntityQuery invoicesOf(List<ApplicationInput> applications) {
        List<Object> ids = applications == null ? List.of() : applications.stream().map(ApplicationInput::invoiceId)
            .filter(Objects::nonNull).distinct().map(id -> (Object) id).toList();
        return EntityQuery.builder().where(new QueryPredicate.In("invoiceId", new ArrayList<>(ids)))
            .limit(ids.size() + 1).build();
    }

    static EntityQuery termsOf(ProcessContext ctx) {
        List<Object> codes = list(ctx, INVOICES).stream().map(i -> (Object) i.get("termsCode")).distinct().toList();
        return EntityQuery.builder().where(new QueryPredicate.In("termsCode", new ArrayList<>(codes)))
            .limit(codes.size() + 1).build();
    }

    /** What was applied to the invoices before, for the discounts taken; at most 500 rows. */
    static EntityQuery applicationsOf(ProcessContext ctx) {
        List<Object> ids = list(ctx, INVOICES).stream().map(EntityInstance::id).toList();
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.In("invoiceId", new ArrayList<>(ids)),
            new QueryPredicate.Eq("sourceKind", InvoiceEntities.RECEIPT_SOURCE)))).limit(500).build();
    }

    private static EntityInstance settings(ProcessContext ctx) {
        EntityInstance settings = first(ctx, SETTINGS);
        if (settings == null) {
            ctx.reject(new Violation("customerCode", InvoiceProcesses.NO_SETTINGS, "The receivables settings are "
                + "not set", Map.of()));
        }
        return settings;
    }

    private static Violation notPosted(EntityInstance receipt) {
        return new Violation("receiptId", NOT_POSTED, "Receipt " + receipt.get("receiptNo") + " is "
            + receipt.get("status"), Map.of("receiptNo", (Object) receipt.get("receiptNo"),
            "status", (Object) receipt.get("status")));
    }

    private static Violation applied(EntityInstance receipt) {
        return new Violation("receiptId", APPLIED, "Something of " + receipt.get("receiptNo") + " is applied; "
            + "take it back first", Map.of("receiptNo", (Object) receipt.get("receiptNo")));
    }

    private static JournalProcesses.LineInput debit(String account, BigDecimal amount, String memo) {
        return new JournalProcesses.LineInput(account, amount, null, memo(memo), null, null);
    }

    private static JournalProcesses.LineInput credit(String account, BigDecimal amount, String memo) {
        return new JournalProcesses.LineInput(account, null, amount, memo(memo), null, null);
    }

    /** A memo of at most 200 characters, as the ledger takes them. */
    static String memo(String text) {
        return text.length() > 200 ? text.substring(0, 197) + "..." : text;
    }

    private static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    private static EntityInstance find(ProcessContext ctx, String key, Object id) {
        UUID wanted = uuid(id);
        return list(ctx, key).stream().filter(e -> Objects.equals(wanted, uuid(e.id()))).findFirst().orElse(null);
    }

    private static EntityInstance application(ProcessContext ctx) {
        return ctx.get(APPLICATION, EntityInstance.class);
    }

    private static ReceiptInput recordInput(ProcessContext ctx) {
        return ctx.get(INPUT, ReceiptInput.class);
    }

    private static String code(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ReceiptProcesses() {}
}

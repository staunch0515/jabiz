package com.jabiz.finance.ap;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.ar.ArEntities;
import com.jabiz.finance.calc.BillDuplicates;
import com.jabiz.finance.calc.PaymentTerms;
import com.jabiz.finance.calc.SalesTax;
import com.jabiz.finance.fa.AssetEntities;
import com.jabiz.finance.fa.AssetProcesses;
import com.jabiz.finance.fx.FxEntities;
import com.jabiz.finance.fx.FxRates;
import com.jabiz.finance.fx.FxSettingsProcesses;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalProcesses;
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
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Vendor bills and credits (FIN-AP-004…008, FIN-TX-007, FIN-GL-021; docs/finance/00-design.md section 9):
 * <ul>
 *   <li>{@code FIN_BILL_SAVE} / {@code FIN_BILL_DELETE}: a draft, new or changed, or gone. The vendor's invoice number
 *       may not be one of the vendor's other bills (refused); the same vendor, amount and date under another number
 *       is saved only with a reason (FIN-AP-005). Lines take the vendor's 1099 form and box unless they name their
 *       own (FIN-AP-020).</li>
 *   <li>{@code FIN_BILL_POST}: computes the use tax, the due date and the 1099 boxes, numbers the document without
 *       gaps ({@code BILL-}, {@code VC-}), books it through {@code FIN_SUBLEDGER_POST} and registers an asset for each
 *       line on a fixed-asset cost account (FIN-AP-007). The approval rules of {@code fin.ap.bill} are asked: a bill
 *       a rule stops is posted all the same and waits for approval before it can be paid (FIN-AP-006). A 1099
 *       vendor with no TIN is warned of backup withholding (FIN-AP-002).</li>
 *   <li>{@code FIN_BILL_VOID}: a posted document nothing was applied to is voided by reversing its entry, never by
 *       its preparer; its assets go out of the register.</li>
 *   <li>{@code FIN_AP_APPLY}: a posted vendor credit applied to an open bill of the same vendor (FIN-AP-008).</li>
 *   <li>{@code FIN_BILL_APPROVAL_RESULT}: run on the platform's approval events; believes only the platform's approval
 *       request it points to.</li>
 *   <li>{@code FIN_AP_OPENING}: the legacy system's open payables brought over as approved open bills, not posted
 *       again; together they equal the payables account in the opening entry (FIN-DI-002).</li>
 * </ul>
 * Bills are in the vendor's currency (F7 plan decision D4, FIN-FX-003): posted at the spot rate of the bill's day,
 * they keep the rate and what they owe in US dollars; a credit applied at another rate realizes the difference
 * (FIN-FX-004). Use tax accrues only on bills in US dollars.
 */
public final class BillProcesses {

    public static final String SAVE = "FIN_BILL_SAVE";
    public static final String DELETE = "FIN_BILL_DELETE";
    public static final String POST = "FIN_BILL_POST";
    public static final String VOID = "FIN_BILL_VOID";
    public static final String APPLY = "FIN_AP_APPLY";
    public static final String UNAPPLY = "FIN_AP_UNAPPLY";
    public static final String APPROVAL_RESULT = "FIN_BILL_APPROVAL_RESULT";
    public static final String OPENING = "FIN_AP_OPENING";

    public static final String BILL_NUMBERS = "fin.ap.bill";
    public static final String CREDIT_NUMBERS = "fin.ap.credit";

    /** The approval subject of bills (FIN-AP-006). */
    public static final String SUBJECT = "fin.ap.bill";

    public static final String NOT_DRAFT = "FIN_BILL_NOT_DRAFT";
    public static final String NOT_POSTED = "FIN_BILL_NOT_POSTED";
    public static final String INVALID_VALUE = "FIN_BILL_INVALID_VALUE";
    public static final String UNKNOWN_VENDOR = "FIN_BILL_UNKNOWN_VENDOR";
    public static final String UNKNOWN_TERMS = "FIN_BILL_UNKNOWN_TERMS";
    public static final String CURRENCY = "FIN_BILL_CURRENCY";
    public static final String NO_SETTINGS = "FIN_AP_NO_SETTINGS";
    public static final String NO_LINES = "FIN_BILL_NO_LINES";
    public static final String ACCOUNT = "FIN_BILL_ACCOUNT";
    public static final String USE_TAX_CODE = "FIN_BILL_USE_TAX_CODE";
    public static final String NO_USE_TAX_ACCOUNT = "FIN_BILL_NO_USE_TAX_ACCOUNT";
    public static final String DUPLICATE = "FIN_BILL_DUPLICATE";
    public static final String POSSIBLE_DUPLICATE = "FIN_BILL_POSSIBLE_DUPLICATE";
    public static final String ORIGINAL = "FIN_BILL_ORIGINAL";
    public static final String EXCEEDS = "FIN_BILL_CREDIT_EXCEEDS";
    public static final String OWN_DOCUMENT = "FIN_BILL_OWN_DOCUMENT";
    public static final String APPLIED = "FIN_BILL_APPLIED";
    public static final String ASSET_DEPRECIATED = "FIN_BILL_ASSET_DEPRECIATED";
    public static final String APPLY_REFUSED = "FIN_AP_APPLY_REFUSED";
    public static final String BOX = "FIN_BILL_1099_BOX";
    public static final String NOT_PREPARER = "FIN_BILL_NOT_PREPARER";
    public static final String NO_NUMBER = "FIN_BILL_NO_NUMBER";
    public static final String UNAPPLY_REFUSED = "FIN_AP_UNAPPLY_REFUSED";
    public static final String OPENING_TOTAL = "FIN_AP_OPENING_TOTAL";
    public static final String OPENING_NONE = "FIN_AP_OPENING_NO_ENTRY";
    public static final String OPENING_DONE = "FIN_AP_OPENING_DONE";
    public static final String OPENING_NUMBER = "FIN_AP_OPENING_NUMBER";
    public static final String OPENING_TWICE = "FIN_AP_OPENING_TWICE";
    public static final String OPENING_DATE = "FIN_AP_OPENING_DATE";
    /** A warning, not a refusal: the 1099 vendor has no TIN on file (26 U.S.C. 3406). */
    public static final String BACKUP_WITHHOLDING = "FIN_BILL_BACKUP_WITHHOLDING";

    /**
     * @param account     an expense or asset account; the vendor's default expense account when absent
     * @param useTaxCode  a taxable purchase's tax code when the vendor charged no tax: use tax accrues (FIN-TX-007)
     * @param form1099    {@code NEC} or {@code MISC}, or empty for a payment not reportable; the bill's when absent
     */
    public record LineInput(@NotBlank @Size(max = 500) String description,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @Size(max = 20) String account, @Size(max = 20) String useTaxCode, @Size(max = 20) String department,
        @Size(max = 20) String location, @Size(max = 10) String form1099, @Size(max = 2) String box1099) {}

    /**
     * A draft; a new one without {@code billId}. The terms default to the vendor's.
     *
     * @param kind            {@code BILL} (the default) or {@code CREDIT}
     * @param originalBillId  a vendor credit's bill, if it credits one
     * @param duplicateReason why a bill like another of the same vendor, amount and date is entered all the same
     */
    public record BillInput(UUID billId, @Size(max = 10) String kind, @NotBlank @Size(max = 20) String vendorCode,
        @NotBlank @Size(max = 40) String vendorInvoiceNo, @NotNull LocalDate invoiceDate, LocalDate receivedDate,
        @Size(max = 20) String termsCode, @Size(max = 500) String description, UUID originalBillId,
        UUID attachmentFileId, @Size(max = 500) String duplicateReason,
        @NotEmpty @Size(max = 500) List<@Valid @NotNull LineInput> lines) {}

    public record BillId(@NotNull UUID billId) {}

    public record VoidInput(@NotNull UUID billId, @NotNull LocalDate voidDate,
        @NotBlank @Size(max = 500) String reason) {}

    public record ApplyInput(@NotNull UUID creditId, @NotNull UUID billId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @NotNull LocalDate applicationDate) {}

    /**
     * @param approval {@code NOT_REQUIRED}, or {@code PENDING} when a rule stops payment until approved
     * @param warnings what the poster should know, such as backup withholding
     * @param assets   the assets registered from the bill's lines
     */
    public record BillOutput(String billId, String billNo, String kind, String status, LocalDate dueDate,
        BigDecimal total, BigDecimal useTaxTotal, BigDecimal openAmount, String glNo, String approval,
        String approvalRequestId, List<String> warnings, List<String> assets) {}

    public record ApplyOutput(String applicationId, BigDecimal billOpen, BigDecimal creditOpen) {}

    /** Takes back an application of a vendor credit on a day: a new application of the opposite amount. */
    public record UnapplyInput(@NotNull UUID applicationId, @NotNull LocalDate applicationDate,
        @NotBlank @Size(max = 500) String reason) {}

    /** The platform's approval decision, as its events carry it: a pointer to the request, checked against it. */
    public record ApprovalResultInput(String subject, String entityId, String status, String requestId) {}

    public record OpeningItem(@NotBlank @Size(max = 40) String document, @NotBlank @Size(max = 20) String vendorCode,
        @NotNull LocalDate invoiceDate, @NotNull LocalDate dueDate,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount) {}

    public record OpeningInput(@NotEmpty @Size(max = 5000) List<@Valid @NotNull OpeningItem> items) {}

    public record OpeningOutput(int items, BigDecimal total) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String BILL_ID = "billId";
    static final String BILL = "bill";
    static final String FOUND = "found";
    static final String LINES = "lines";
    static final String VENDORS = "vendors";
    static final String VENDOR_BILLS = "vendorBills";
    static final String SETTINGS = "settings";
    static final String TERMS = "terms";
    static final String ACCOUNTS = "accounts";
    static final String CODES = "codes";
    static final String RATES = "rates";
    static final String TAX_INFOS = "taxInfos";
    static final String PREPARED = "prepared";
    static final String APPROVAL = "approval";
    static final String NUMBER = "number";
    static final String SUB_INPUT = "subledgerInput";
    static final String SUB_OUTPUT = "subledgerOutput";
    static final String ASSET_INPUTS = "assetInputs";
    static final String ASSETS = "assets";
    static final String APPLICATIONS = "applications";
    static final String PERIODS = "periods";
    static final String REQUESTS = "requests";
    static final String JOURNALS = "journals";
    static final String CREDITS = "credits";
    static final String OPENING_LINES = "openingLines";
    static final String FX_SETTINGS = "fxSettings";
    static final String FX_RATES = "fxRates";

    // ---- save and delete -------------------------------------------------------------------------------------------

    public static final ProcessDefinition<BillInput, BillOutput, ProcessContext> SAVE_PROCESS =
        ProcessDefinition.define(SAVE, 1, BillInput.class, BillOutput.class, ProcessContext.class, pb -> pb
            .description("Creates or changes a draft vendor bill or credit.")
            .permissions(FinancePermissions.BILL_PREPARE)
            .contextFactory(BillProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, BillOutput.class))
            .step("Load the draft", QueryEntities.of(BillEntities.BILL_DATASET,
                ctx -> byIds(saveInput(ctx).billId(), saveInput(ctx).originalBillId()), FOUND))
            .step("Load its lines", QueryEntities.of(BillEntities.LINE_DATASET,
                ctx -> linesOf(saveInput(ctx).billId()), LINES))
            .step("Load the vendor", QueryEntities.of(ApEntities.VENDOR_DATASET,
                ctx -> VendorProcesses.byCode(saveInput(ctx).vendorCode()), VENDORS))
            .step("Load the vendor's bills like it", QueryEntities.of(BillEntities.BILL_DATASET,
                ctx -> candidates(saveInput(ctx).vendorCode(), saveInput(ctx).vendorInvoiceNo(),
                    saveInput(ctx).invoiceDate()), VENDOR_BILLS))
            .step("Load the accounts", QueryEntities.of(GlEntities.ACCOUNT_DATASET, ctx -> {
                List<String> codes = new ArrayList<>(saveInput(ctx).lines().stream().map(LineInput::account).toList());
                list(ctx, VENDORS).forEach(v -> codes.add(v.get("expenseAccount")));
                return accountsOf(codes);
            }, ACCOUNTS))
            .step("Load the use tax codes", QueryEntities.of(TaxEntities.CODE_DATASET, ctx -> TaxProcesses.codes(
                useTaxCodes(saveInput(ctx).lines().stream().map(LineInput::useTaxCode).toList())), CODES))
            .compute("Save the draft", (metadata, ctx) -> save(ctx)));

    public static final ProcessDefinition<BillId, BillOutput, ProcessContext> DELETE_PROCESS =
        ProcessDefinition.define(DELETE, 1, BillId.class, BillOutput.class, ProcessContext.class, pb -> pb
            .description("Deletes a draft vendor bill or credit.")
            .permissions(FinancePermissions.BILL_PREPARE)
            .actsOn(BillEntities.BILL, "billId", a -> a.whenField("status", BillEntities.DRAFT))
            .contextFactory(BillProcesses::withId)
            .outputMapper(ctx -> ctx.get(OUTPUT, BillOutput.class))
            .step("Load the draft", LoadEntity.by(BillEntities.BILL_DATASET, BILL_ID, BILL))
            .step("Load its lines", QueryEntities.of(BillEntities.LINE_DATASET,
                ctx -> linesOf(ctx.get(BILL_ID)), LINES))
            .compute("Delete it", (metadata, ctx) -> {
                EntityInstance bill = bill(ctx);
                if (!BillEntities.DRAFT.equals(bill.get("status"))) {
                    ctx.reject(notDraft(bill));
                    return;
                }
                for (EntityInstance line : list(ctx, LINES)) {
                    ctx.changes().delete(BillEntities.LINE, line.id(), line.version());
                }
                ctx.changes().delete(BillEntities.BILL, bill.id(), bill.version());
                ctx.put(OUTPUT, output(bill, Map.of("status", "DELETED"), List.of(), List.of()));
            }));

    static void save(ProcessContext ctx) {
        BillInput input = saveInput(ctx);
        EntityInstance current = input.billId() == null ? null : find(ctx, FOUND, input.billId());
        if (input.billId() != null && current == null) {
            ctx.reject(new Violation("billId", NOT_DRAFT, "There is no bill " + input.billId(),
                Map.of("billNo", String.valueOf(input.billId()), "status", "unknown")));
            return;
        }
        String kind = input.kind() == null || input.kind().isBlank() ? BillEntities.BILL_KIND
            : input.kind().trim().toUpperCase(Locale.ROOT);
        if (!BillEntities.KIND_VALUES.contains(kind)) {
            ctx.reject(new Violation("kind", INVALID_VALUE, "kind must be one of " + BillEntities.KIND_VALUES,
                Map.of("value", input.kind())));
            return;
        }
        String vendorCode = VendorProcesses.code(input.vendorCode());
        if (current != null) {
            if (!BillEntities.DRAFT.equals(current.get("status"))) {
                ctx.reject(notDraft(current));
                return;
            }
            if (!kind.equals(current.get("kind")) || !vendorCode.equals(current.get("vendorCode"))
                || !Objects.equals(input.originalBillId(), uuid(current.get("originalBillId")))) {
                ctx.reject(new Violation("vendorCode", INVALID_VALUE, "A draft's kind, vendor and original bill stay "
                    + "as they were created", Map.of("value", vendorCode)));
                return;
            }
        }
        EntityInstance vendor = first(ctx, VENDORS);
        if (vendor == null || !"ACTIVE".equals(vendor.get("status"))) {
            ctx.reject(new Violation("vendorCode", UNKNOWN_VENDOR, "There is no active vendor " + vendorCode,
                Map.of("vendorCode", vendorCode)));
            return;
        }
        boolean credit = BillEntities.CREDIT.equals(kind);
        if (BillDuplicates.key(input.vendorInvoiceNo()).isEmpty()) {
            ctx.reject(new Violation("vendorInvoiceNo", NO_NUMBER, "The vendor's invoice number has letters or "
                + "digits", Map.of()));
            return;
        }
        if (input.receivedDate() != null && input.receivedDate().isBefore(input.invoiceDate())) {
            ctx.reject(new Violation("receivedDate", INVALID_VALUE, "A bill is received on or after its date",
                Map.of("value", input.receivedDate().toString())));
            return;
        }
        if (input.originalBillId() != null) {
            EntityInstance original = find(ctx, FOUND, input.originalBillId());
            if (!credit || original == null || !BillEntities.BILL_KIND.equals(original.get("kind"))
                || !BillEntities.POSTED.equals(original.get("status"))
                || !vendorCode.equals(original.get("vendorCode"))) {
                ctx.reject(new Violation("originalBillId", ORIGINAL, "A vendor credit refers to a posted bill of the "
                    + "same vendor", Map.of()));
                return;
            }
        }
        String defaultForm = vendor.get("form1099");
        String defaultBox = vendor.get("box1099");
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        Map<String, EntityInstance> codes = new LinkedHashMap<>();
        list(ctx, CODES).forEach(c -> codes.put(c.get("taxCode"), c));
        for (int i = 0; i < input.lines().size(); i++) {
            LineInput line = input.lines().get(i);
            String field = "lines[" + i + "]";
            String account = line.account() == null || line.account().isBlank() ? vendor.get("expenseAccount")
                : line.account().trim();
            if (account == null) {
                ctx.reject(new Violation(field + ".account", ACCOUNT, "Line " + (i + 1) + " needs an account and the "
                    + "vendor has no default", Map.of("accountCode", "")));
            } else if (!billAccount(ctx, account, credit)) {
                ctx.reject(new Violation(field + ".account", ACCOUNT, "Account " + account + " is not an expense or "
                    + "asset account a bill posts to", Map.of("accountCode", account)));
            }
            String useTax = VendorProcesses.code(line.useTaxCode());
            if (useTax != null) {
                EntityInstance code = codes.get(useTax);
                if (code == null || !Boolean.TRUE.equals(code.get("active")) || !"TAXABLE".equals(code.get("kind"))) {
                    ctx.reject(new Violation(field + ".useTaxCode", USE_TAX_CODE, "Use tax accrues at an active "
                        + "taxable code; " + useTax + " is not one", Map.of("taxCode", useTax)));
                }
            }
            // No form: the vendor's form, and its box unless the line names one; an empty form: not reportable.
            String form = line.form1099() == null ? defaultForm : VendorProcesses.code(line.form1099());
            String givenBox = line.box1099() == null || line.box1099().isBlank() ? null : line.box1099().trim();
            String box = form == null ? null : givenBox != null ? givenBox
                : line.form1099() == null ? defaultBox : "1";
            if (form != null && (!ApEntities.BOXES.containsKey(form) || !ApEntities.BOXES.get(form).contains(box))) {
                ctx.reject(new Violation(field + ".box1099", BOX, "1099-" + form + " box " + box + " is not a box "
                    + "payments are reported in", Map.of("form", form, "box", String.valueOf(box))));
            }
            total = total.add(line.amount());
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("lineNo", BigDecimal.valueOf(i + 1));
            values.put("description", line.description().trim());
            values.put("amount", line.amount());
            values.put("account", account);
            values.put("useTaxCode", useTax);
            values.put("department", VendorProcesses.trim(line.department()));
            values.put("location", VendorProcesses.trim(line.location()));
            values.put("form1099", form);
            values.put("box1099", box);
            lines.add(values);
        }
        if (ctx.hasViolations()) {
            return;
        }
        String reason = VendorProcesses.trim(input.duplicateReason());
        if (!duplicates(ctx, current == null ? null : current.id(), kind, vendorCode, input.vendorInvoiceNo().trim(),
            input.invoiceDate(), total, reason)) {
            return;
        }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("vendorInvoiceNo", input.vendorInvoiceNo().trim());
        header.put("vendorInvoiceKey", BillDuplicates.key(input.vendorInvoiceNo()));
        // Whoever saved it last prepared it: only they post it, and the approval never lets them approve it.
        header.put("preparedBy", ctx.request().actorId());
        header.put("invoiceDate", input.invoiceDate());
        header.put("receivedDate", input.receivedDate());
        header.put("currency", vendor.get("currency"));
        header.put("termsCode", VendorProcesses.code(input.termsCode()) == null ? vendor.get("termsCode")
            : VendorProcesses.code(input.termsCode()));
        header.put("description", VendorProcesses.trim(input.description()));
        header.put("form1099", defaultForm);
        header.put("box1099", defaultBox);
        header.put("attachmentFileId", input.attachmentFileId());
        header.put("duplicateReason", reason);
        header.put("subtotal", total);
        header.put("total", total);
        Object id;
        if (current == null) {
            header.put("kind", kind);
            header.put("vendorCode", vendorCode);
            header.put("originalBillId", input.originalBillId());
            header.put("source", BillEntities.MANUAL);
            header.put("status", BillEntities.DRAFT);
            id = ctx.changes().insert(BillEntities.BILL, header);
        } else {
            id = current.id();
            ctx.changes().update(BillEntities.BILL, current.id(), current.version(), header);
            for (EntityInstance line : list(ctx, LINES)) {
                ctx.changes().delete(BillEntities.LINE, line.id(), line.version());
            }
        }
        for (Map<String, Object> line : lines) {
            line.put("billId", id);
            ctx.changes().insert(BillEntities.LINE, line);
        }
        ctx.put(OUTPUT, new BillOutput(String.valueOf(id), null, kind, BillEntities.DRAFT, null, total, null, null,
            null, null, null, List.of(), List.of()));
    }

    /**
     * The duplicate check (FIN-AP-005) against the vendor's other bills: false, with the refusal recorded, when the
     * bill may not be saved or posted.
     */
    static boolean duplicates(ProcessContext ctx, Object id, String kind, String vendorCode, String vendorInvoiceNo,
        LocalDate invoiceDate, BigDecimal total, String reason) {
        List<BillDuplicates.Bill> others = list(ctx, VENDOR_BILLS).stream().map(b -> new BillDuplicates.Bill(b.id(),
            b.get("billNo"), b.get("kind"), b.get("vendorCode"), b.get("vendorInvoiceNo"), b.get("invoiceDate"),
            b.get("total"), BillEntities.VOID.equals(b.get("status")))).toList();
        BillDuplicates.Result result = BillDuplicates.check(new BillDuplicates.Bill(id, null, kind, vendorCode,
            vendorInvoiceNo, invoiceDate, total, false), others);
        if (result.same() != null) {
            ctx.reject(new Violation("vendorInvoiceNo", DUPLICATE, vendorCode + "'s invoice " + vendorInvoiceNo
                + " is entered already" + (result.same().billNo() == null ? " as a draft"
                : " as " + result.same().billNo()), Map.of("vendorCode", vendorCode, "vendorInvoiceNo",
                vendorInvoiceNo, "billNo", String.valueOf(result.same().billNo() == null ? "a draft"
                : result.same().billNo()))));
            return false;
        }
        if (!result.similar().isEmpty() && reason == null) {
            BillDuplicates.Bill other = result.similar().getFirst();
            ctx.reject(new Violation("duplicateReason", POSSIBLE_DUPLICATE, "A bill of " + vendorCode + " for "
                + total.toPlainString() + " on " + invoiceDate + " is entered already as " + other.vendorInvoiceNo()
                + ": confirm with a reason", Map.of("vendorCode", vendorCode, "vendorInvoiceNo",
                String.valueOf(other.vendorInvoiceNo()), "amount", total.toPlainString(),
                "date", invoiceDate.toString())));
            return false;
        }
        return true;
    }

    // ---- post ------------------------------------------------------------------------------------------------------

    /** What posting a document will write, computed before it is numbered. */
    record Prepared(boolean credit, LocalDate dueDate, BigDecimal total, BigDecimal useTax, BigDecimal rate,
        BillPosting.Result posting, SalesTax.Result taxes, List<EntityInstance> lines, List<EntityInstance> capital,
        List<String> warnings) {}

    public static final ProcessDefinition<BillId, BillOutput, ProcessContext> POST_PROCESS =
        ProcessDefinition.define(POST, 1, BillId.class, BillOutput.class, ProcessContext.class, pb -> pb
            .description("Posts a vendor bill or credit: numbers it, books it and registers its assets.")
            .permissions(FinancePermissions.BILL_PREPARE)
            .actsOn(BillEntities.BILL, "billId", a -> a.whenField("status", BillEntities.DRAFT))
            .contextFactory(BillProcesses::withId)
            .outputMapper(ctx -> ctx.get(OUTPUT, BillOutput.class))
            .step("Load the document", LoadEntity.by(BillEntities.BILL_DATASET, BILL_ID, BILL))
            .step("Load its lines", QueryEntities.of(BillEntities.LINE_DATASET,
                ctx -> linesOf(ctx.get(BILL_ID)), LINES))
            .step("Load the vendor", QueryEntities.of(ApEntities.VENDOR_DATASET,
                ctx -> VendorProcesses.byCode(bill(ctx).get("vendorCode")), VENDORS))
            .step("Load the vendor's tax information", QueryEntities.of(ApEntities.TAX_INFO_DATASET,
                ctx -> VendorProcesses.byCode(bill(ctx).get("vendorCode")), TAX_INFOS))
            .step("Load the vendor's bills like it", QueryEntities.of(BillEntities.BILL_DATASET,
                ctx -> candidates(bill(ctx).get("vendorCode"), bill(ctx).get("vendorInvoiceNo"),
                    bill(ctx).get("invoiceDate")), VENDOR_BILLS))
            .step("Load the credits of its bill", QueryEntities.of(BillEntities.BILL_DATASET,
                ctx -> {
                    Object original = bill(ctx).get("originalBillId");
                    return EntityQuery.builder().where(new QueryPredicate.In("originalBillId",
                        original == null ? List.of() : List.of(original))).limit(5000).build();
                }, CREDITS))
            .step("Load the settings", QueryEntities.of(ApEntities.SETTINGS_DATASET,
                ctx -> ApSettingsProcesses.current(), SETTINGS))
            .step("Load the terms", QueryEntities.of(ArEntities.PAYMENT_TERMS_DATASET,
                ctx -> VendorProcesses.eq("termsCode", bill(ctx).get("termsCode")), TERMS))
            .step("Load the accounts", QueryEntities.of(GlEntities.ACCOUNT_DATASET, ctx -> accountsOf(
                list(ctx, LINES).stream().map(l -> (String) l.get("account")).toList()), ACCOUNTS))
            .step("Load the use tax codes", QueryEntities.of(TaxEntities.CODE_DATASET, ctx -> TaxProcesses.codes(
                useTaxCodes(list(ctx, LINES).stream().map(l -> (String) l.get("useTaxCode")).toList())), CODES))
            .step("Load the tax rates in effect", QueryEntities.of(TaxEntities.RATE_DATASET, ctx -> {
                LocalDate date = bill(ctx).get("invoiceDate");
                Set<String> jurisdictions = new LinkedHashSet<>();
                list(ctx, CODES).forEach(c -> {
                    String value = c.get("jurisdictions");
                    if (value != null) {
                        jurisdictions.addAll(Arrays.asList(value.split(",")));
                    }
                });
                return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.In("jurisdictionCode", new ArrayList<>(jurisdictions)),
                    new QueryPredicate.Lte("effectiveFrom", date),
                    new QueryPredicate.Or(List.of(new QueryPredicate.IsNull("effectiveTo"),
                        new QueryPredicate.Gte("effectiveTo", date)))))).limit(500).build();
            }, RATES))
            .step("Load the original bill", QueryEntities.of(BillEntities.BILL_DATASET,
                ctx -> byIds(uuid(bill(ctx).get("originalBillId"))), FOUND))
            .step("Load the foreign currency settings", QueryEntities.of(FxEntities.SETTINGS_DATASET,
                ctx -> FxSettingsProcesses.current(), FX_SETTINGS))
            .step("Load the exchange rate", QueryEntities.of(GlEntities.EXCHANGE_RATE_DATASET,
                ctx -> FxRates.spot(bill(ctx).get("currency"), bill(ctx).get("invoiceDate"), first(ctx, FX_SETTINGS)),
                FX_RATES))
            .compute("Compute the document", (metadata, ctx) -> prepare(ctx))
            // Bills only: a vendor credit lowers what is owed.
            .step("Apply the approval rules", RequireApproval.when(ctx -> ctx.contains(PREPARED)
                && !prepared(ctx).credit(), SUBJECT, BillProcesses::approvalCase, APPROVAL))
            .step("Number the bill", AssignNumber.when(ctx -> ctx.contains(PREPARED) && !prepared(ctx).credit(),
                BILL_NUMBERS, null, NUMBER))
            .step("Number the credit", AssignNumber.when(ctx -> ctx.contains(PREPARED) && prepared(ctx).credit(),
                CREDIT_NUMBERS, null, NUMBER))
            .compute("Build the entry", (metadata, ctx) -> {
                if (!ctx.contains(PREPARED)) {
                    return;
                }
                EntityInstance bill = bill(ctx);
                Prepared prepared = prepared(ctx);
                String number = ctx.get(NUMBER, String.class);
                List<JournalProcesses.LineInput> lines = prepared.posting().lines().stream()
                    .map(l -> new JournalProcesses.LineInput(l.accountCode(), l.debit(), l.credit(), l.memo(),
                        l.department(), l.location())).toList();
                String description = bill.get("description") != null ? bill.get("description")
                    : (prepared.credit() ? "Vendor credit " : "Bill ") + bill.get("vendorInvoiceNo") + " "
                        + bill.get("vendorCode");
                List<String> controls = prepared.capital().isEmpty() ? List.of("AP") : List.of("AP", "FA_COST");
                ctx.put(SUB_INPUT, new SubledgerPosting.PostInput("AP", bill.get("invoiceDate"), description, number,
                    BillEntities.BILL, String.valueOf(bill.id()), lines, controls));
            })
            .step("Book it", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.POST, 1,
                ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT))
            .compute("Prepare the assets", (metadata, ctx) -> assetInputs(ctx))
            .step("Register the assets", CallProcess.forEach(AssetProcesses.CREATE, 1,
                ctx -> ctx.contains(ASSET_INPUTS) ? (List<?>) ctx.get(ASSET_INPUTS) : List.of(), ASSETS))
            .compute("Record the posting", (metadata, ctx) -> recordPosting(ctx)));

    static void prepare(ProcessContext ctx) {
        EntityInstance bill = bill(ctx);
        if (!BillEntities.DRAFT.equals(bill.get("status"))) {
            ctx.reject(notDraft(bill));
            return;
        }
        boolean credit = BillEntities.CREDIT.equals(bill.get("kind"));
        if (!Objects.equals(ctx.request().actorId(), bill.get("preparedBy"))) {
            // What is posted is what its preparer saved: the approval rules then know who may not approve it.
            ctx.reject(new Violation("billId", NOT_PREPARER, "A bill is posted by whoever saved it last; save it "
                + "first to post it", Map.of()));
            return;
        }
        List<EntityInstance> lines = new ArrayList<>(list(ctx, LINES));
        lines.sort(Comparator.comparing(l -> l.<BigDecimal>get("lineNo")));
        if (lines.isEmpty()) {
            ctx.reject(new Violation("lines", NO_LINES, "A document has at least one line", Map.of()));
        }
        for (EntityInstance line : lines) {
            if (!billAccount(ctx, line.get("account"), credit)) {
                ctx.reject(new Violation("lines", ACCOUNT, "Account " + line.get("account") + " is not an expense or "
                    + "asset account a bill posts to", Map.of("accountCode", (Object) line.get("account"))));
            }
        }
        EntityInstance vendor = first(ctx, VENDORS);
        if (vendor == null || !"ACTIVE".equals(vendor.get("status"))) {
            ctx.reject(new Violation("vendorCode", UNKNOWN_VENDOR, "There is no active vendor "
                + bill.get("vendorCode"), Map.of("vendorCode", (Object) bill.get("vendorCode"))));
        }
        String currency = ApFx.currency(bill);
        BigDecimal rate = FxRates.rate(currency, null, list(ctx, FX_RATES));
        if (rate == null) {
            ctx.reject(FxRates.missing("invoiceDate", currency, bill.get("invoiceDate"), first(ctx, FX_SETTINGS)));
        }
        for (EntityInstance line : lines) {
            Object code = line.get("useTaxCode");
            if (code != null && !ApFx.dollars(currency)) {
                // Use tax is the state's on what was bought in dollars; a foreign bill's would be in euros.
                ctx.reject(new Violation("lines", CURRENCY, "Use tax accrues on bills in US dollars; this one is in "
                    + currency, Map.of("currency", currency)));
                continue;
            }
            if (code != null && list(ctx, CODES).stream().noneMatch(c -> code.equals(c.get("taxCode"))
                && Boolean.TRUE.equals(c.get("active")) && "TAXABLE".equals(c.get("kind")))) {
                ctx.reject(new Violation("lines", USE_TAX_CODE, "Use tax accrues at an active taxable code; " + code
                    + " is not one", Map.of("taxCode", code)));
            }
        }
        EntityInstance settings = first(ctx, SETTINGS);
        if (settings == null) {
            ctx.reject(new Violation("billId", NO_SETTINGS, "The payables settings are not set", Map.of()));
        }
        EntityInstance terms = first(ctx, TERMS);
        if (terms == null) {
            ctx.reject(new Violation("termsCode", UNKNOWN_TERMS, "There are no payment terms "
                + bill.get("termsCode"), Map.of("termsCode", (Object) bill.get("termsCode"))));
        }
        if (bill.get("originalBillId") != null) {
            EntityInstance original = first(ctx, FOUND);
            if (original == null || !BillEntities.POSTED.equals(original.get("status"))) {
                ctx.reject(new Violation("originalBillId", ORIGINAL, "The vendor credit's bill is not posted",
                    Map.of()));
            } else if (!ApFx.currency(original).equals(ApFx.currency(bill))) {
                // What is left of the bill is in its currency: a credit in another could not be weighed against it.
                ctx.reject(new Violation("originalBillId", ORIGINAL, "The vendor credit is in " + ApFx.currency(bill)
                    + " and its bill in " + ApFx.currency(original), Map.of()));
            }
        }
        if (ctx.hasViolations()) {
            return;
        }
        BigDecimal total = lines.stream().map(l -> l.<BigDecimal>get("amount")).reduce(BigDecimal.ZERO,
            BigDecimal::add);
        // Checked again: another bill of the same number may have been entered since the draft was saved.
        if (!duplicates(ctx, bill.id(), bill.get("kind"), bill.get("vendorCode"), bill.get("vendorInvoiceNo"),
            bill.get("invoiceDate"), total, bill.get("duplicateReason"))) {
            return;
        }
        if (credit && bill.get("originalBillId") != null) {
            EntityInstance original = first(ctx, FOUND);
            BigDecimal left = original.get("total");
            for (EntityInstance other : list(ctx, CREDITS)) {
                if (BillEntities.POSTED.equals(other.get("status")) && !Objects.equals(other.id(), bill.id())
                    && Objects.equals(uuid(other.get("originalBillId")), uuid(original.id()))) {
                    left = left.subtract(other.get("total"));
                }
            }
            if (total.compareTo(left) > 0) {
                ctx.reject(new Violation("lines", EXCEEDS, "The credit is " + total.toPlainString() + "; "
                    + left.toPlainString() + " is left of the bill", Map.of("amount", total.toPlainString(),
                    "left", left.toPlainString())));
                return;
            }
        }
        // Use tax, as sales tax would have been charged: at the bill's date, by the line's code (FIN-TX-007).
        Map<String, SalesTax.Code> codes = new LinkedHashMap<>();
        for (EntityInstance code : list(ctx, CODES)) {
            String jurisdictions = code.get("jurisdictions");
            codes.put(code.get("taxCode"), new SalesTax.Code(code.get("taxCode"),
                SalesTax.Kind.valueOf(code.get("kind")), code.get("reason"), code.get("state"),
                jurisdictions == null ? List.of() : Arrays.asList(jurisdictions.split(",")),
                Boolean.TRUE.equals(code.get("certificateRequired")), code.get("chargeCode")));
        }
        List<EntityInstance> taxed = lines.stream().filter(l -> l.get("useTaxCode") != null).toList();
        SalesTax.Result taxes = null;
        Map<Object, BigDecimal> lineTax = new LinkedHashMap<>();
        if (!taxed.isEmpty()) {
            if (settings.get("useTaxAccount") == null) {
                ctx.reject(new Violation("lines", NO_USE_TAX_ACCOUNT, "The payables settings name no use tax "
                    + "account", Map.of()));
                return;
            }
            List<SalesTax.Rate> rates = list(ctx, RATES).stream().map(r -> new SalesTax.Rate(
                r.get("jurisdictionCode"), r.get("effectiveFrom"), r.get("effectiveTo"), r.get("ratePercent")))
                .toList();
            taxes = SalesTax.compute(new SalesTax.Request(bill.get("invoiceDate"), taxed.stream()
                .map(l -> new SalesTax.Line(l.get("amount"), l.get("useTaxCode"))).toList(), codes, rates, List.of(),
                SalesTax.OnMissingCertificate.CHARGE));
            for (SalesTax.Problem problem : taxes.problems()) {
                ctx.reject(new Violation("lines", problem.code(), problem.message(), problem.params()));
            }
            if (ctx.hasViolations()) {
                return;
            }
            for (SalesTax.LineResult result : taxes.lines()) {
                lineTax.put(taxed.get(result.line()).get("lineNo"), result.tax());
            }
        }
        List<BillPosting.Line> postingLines = lines.stream().map(l -> new BillPosting.Line(l.get("amount"),
            l.get("account"), l.get("department"), l.get("location"),
            lineTax.getOrDefault(l.get("lineNo"), BigDecimal.ZERO))).toList();
        BillPosting.Result posting = BillPosting.lines(credit, postingLines, rate, settings.get("payableAccount"),
            settings.get("useTaxAccount"), null, taxes == null ? null : "Use tax "
                + String.join(", ", taxes.taxes().stream().map(SalesTax.JurisdictionTax::jurisdiction).toList()));
        List<EntityInstance> capital = credit ? List.of() : lines.stream().filter(l -> list(ctx, ACCOUNTS).stream()
            .anyMatch(a -> Objects.equals(a.get("accountCode"), l.get("account"))
                && "FA_COST".equals(a.get("controlClass")))).toList();
        PaymentTerms paymentTerms = new PaymentTerms(terms.<BigDecimal>get("netDays").intValueExact(),
            terms.get("discountPercent"), terms.get("discountDays") == null ? null
                : terms.<BigDecimal>get("discountDays").intValueExact(), Boolean.TRUE.equals(terms.get("endOfMonth")));
        LocalDate invoiceDate = bill.get("invoiceDate");
        LocalDate dueDate = credit ? invoiceDate : paymentTerms.dueDate(invoiceDate);
        List<String> warnings = new ArrayList<>();
        boolean reportable = lines.stream().anyMatch(l -> l.get("form1099") != null);
        EntityInstance taxInfo = first(ctx, TAX_INFOS);
        if (!credit && reportable && (taxInfo == null || taxInfo.get("tin") == null)) {
            warnings.add(BACKUP_WITHHOLDING + ": " + bill.get("vendorCode") + " is reported on Form 1099 and has no "
                + "TIN on file: backup withholding may apply");
        }
        ctx.put(PREPARED, new Prepared(credit, dueDate, total, posting.useTax(), rate, posting, taxes,
            List.copyOf(lines),
            List.copyOf(capital), List.copyOf(warnings)));
    }

    /**
     * What the approval rules of bills can ask about (FIN-AP-006): the amount, the vendor, the account and department
     * of the largest line, and whether the bill makes an asset. The approval binds the bill's content.
     */
    static ApprovalCase approvalCase(ProcessContext ctx) {
        EntityInstance bill = bill(ctx);
        Prepared prepared = prepared(ctx);
        EntityInstance largest = prepared.lines().stream().max(Comparator.comparing(l -> l.<BigDecimal>get("amount")))
            .orElseThrow();
        Map<String, Object> facts = new LinkedHashMap<>();
        // In US dollars, as the rules' limits are.
        facts.put("amount", prepared.posting().total());
        facts.put("vendorCode", bill.get("vendorCode"));
        facts.put("account", largest.get("account"));
        facts.put("department", largest.get("department") == null ? "" : largest.get("department"));
        facts.put("capital", !prepared.capital().isEmpty());
        Map<String, Object> content = new LinkedHashMap<>();
        for (String field : List.of("kind", "vendorCode", "vendorInvoiceNo", "invoiceDate", "termsCode",
            "description")) {
            content.put(field, bill.get(field));
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (EntityInstance line : prepared.lines()) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String field : List.of("lineNo", "description", "amount", "account", "useTaxCode", "department",
                "location", "form1099", "box1099")) {
                values.put(field, line.get(field));
            }
            lines.add(values);
        }
        content.put("lines", lines);
        content.put("total", prepared.total());
        return ApprovalCase.of(bill.id(), facts, content).preparedBy(bill.get("preparedBy"));
    }

    static void assetInputs(ProcessContext ctx) {
        if (!ctx.contains(SUB_OUTPUT)) {
            return;
        }
        EntityInstance bill = bill(ctx);
        Prepared prepared = prepared(ctx);
        SubledgerPosting.PostOutput booked = ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class);
        List<AssetProcesses.AssetInput> inputs = new ArrayList<>();
        for (EntityInstance line : prepared.capital()) {
            // Its cost is what the line came to, use tax included.
            Object lineNo = line.get("lineNo");
            BigDecimal tax = prepared.taxes() == null ? BigDecimal.ZERO : prepared.taxes().lines().stream()
                .filter(r -> prepared.lines().stream().filter(l -> l.get("useTaxCode") != null).toList()
                    .get(r.line()).get("lineNo").equals(lineNo)).map(SalesTax.LineResult::tax)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            inputs.add(new AssetProcesses.AssetInput(line.get("description"), line.get("account"),
                BillPosting.usd(line.get("amount"), prepared.rate()).add(tax), bill.get("invoiceDate"), line.get("department"),
                line.get("location"), uuid(bill.id()), ctx.get(NUMBER, String.class), bill.get("vendorCode"),
                UUID.fromString(booked.transactionId())));
        }
        ctx.put(ASSET_INPUTS, List.copyOf(inputs));
    }

    @SuppressWarnings("unchecked")
    static void recordPosting(ProcessContext ctx) {
        if (!ctx.contains(PREPARED) || !ctx.contains(SUB_OUTPUT)) {
            return;
        }
        EntityInstance bill = bill(ctx);
        Prepared prepared = prepared(ctx);
        SubledgerPosting.PostOutput booked = ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", BillEntities.POSTED);
        values.put("billNo", ctx.get(NUMBER, String.class));
        values.put("dueDate", prepared.dueDate());
        values.put("subtotal", prepared.total());
        values.put("useTaxTotal", prepared.useTax());
        values.put("total", prepared.total());
        values.put("openAmount", prepared.total());
        values.put("exchangeRate", prepared.rate());
        values.put("totalUsd", prepared.posting().total());
        values.put("openAmountUsd", prepared.posting().total());
        values.put("glNo", booked.glNo());
        values.put("postedTime", ctx.opTime());
        values.put("transactionId", UUID.fromString(booked.transactionId()));
        String approval = BillEntities.NOT_REQUIRED;
        if (ctx.contains(APPROVAL)) {
            ApprovalOutcome outcome = ctx.get(APPROVAL, ApprovalOutcome.class);
            approval = switch (outcome.status()) {
                case PENDING -> BillEntities.PENDING;
                case APPROVED -> BillEntities.APPROVED;
                default -> BillEntities.NOT_REQUIRED;
            };
            values.put("approvalRequestId", outcome.requestId());
        }
        values.put("approval", approval);
        ctx.changes().update(BillEntities.BILL, bill.id(), bill.version(), values);
        if (prepared.taxes() != null) {
            List<EntityInstance> taxed = prepared.lines().stream().filter(l -> l.get("useTaxCode") != null).toList();
            for (SalesTax.JurisdictionTax tax : prepared.taxes().taxes()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("billId", bill.id());
                row.put("jurisdiction", tax.jurisdiction());
                row.put("base", tax.base());
                row.put("ratePercent", tax.percent());
                row.put("rateFrom", tax.rateFrom());
                row.put("tax", tax.tax());
                ctx.changes().insert(BillEntities.TAX, row);
            }
            for (SalesTax.LineResult result : prepared.taxes().lines()) {
                EntityInstance line = taxed.get(result.line());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("billId", bill.id());
                row.put("lineNo", line.get("lineNo"));
                row.put("taxCode", result.taxCode());
                row.put("base", line.get("amount"));
                row.put("tax", result.tax());
                ctx.changes().insert(BillEntities.TAX, row);
            }
        }
        List<String> assets = ctx.contains(ASSETS) ? ((List<AssetProcesses.AssetOutput>) ctx.get(ASSETS)).stream()
            .map(AssetProcesses.AssetOutput::assetNo).toList() : List.of();
        ctx.put(OUTPUT, output(bill, values, prepared.warnings(), assets));
    }

    // ---- void ------------------------------------------------------------------------------------------------------

    public static final ProcessDefinition<VoidInput, BillOutput, ProcessContext> VOID_PROCESS =
        ProcessDefinition.define(VOID, 1, VoidInput.class, BillOutput.class, ProcessContext.class, pb -> pb
            .description("Voids a posted vendor bill or credit nothing was applied to, reversing its entry.")
            .permissions(FinancePermissions.BILL_VOID)
            .actsOn(BillEntities.BILL, "billId", a -> a.whenField("status", BillEntities.POSTED))
            .contextFactory((start, input) -> {
                ProcessContext ctx = withInput(start, input);
                ctx.put(BILL_ID, input.billId());
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, BillOutput.class))
            .step("Load the document", LoadEntity.by(BillEntities.BILL_DATASET, BILL_ID, BILL))
            .step("Load what was applied", QueryEntities.of(BillEntities.APPLICATION_DATASET,
                ctx -> applicationsOf(ctx.get(BILL_ID)), APPLICATIONS))
            .step("Load its assets", QueryEntities.of(AssetEntities.ASSET_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("sourceBillId", ctx.get(BILL_ID))).limit(100)
                    .build(), ASSETS))
            .compute("Check it", (metadata, ctx) -> {
                EntityInstance bill = bill(ctx);
                VoidInput input = ctx.get(INPUT, VoidInput.class);
                if (!BillEntities.POSTED.equals(bill.get("status"))
                    || !BillEntities.MANUAL.equals(bill.get("source"))) {
                    ctx.reject(new Violation("billId", NOT_POSTED, "Only a posted document entered here is voided",
                        Map.of("billNo", String.valueOf((Object) bill.get("billNo")))));
                    return;
                }
                if (Objects.equals(ctx.request().actorId(), bill.get("preparedBy"))) {
                    ctx.reject(new Violation("billId", OWN_DOCUMENT, "The preparer of " + bill.get("billNo")
                        + " does not void it", Map.of("billNo", (Object) bill.get("billNo"))));
                    return;
                }
                BigDecimal applied = list(ctx, APPLICATIONS).stream().map(a -> a.<BigDecimal>get("amount"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (applied.signum() != 0
                    || bill.<BigDecimal>get("openAmount").compareTo(bill.get("total")) != 0) {
                    ctx.reject(new Violation("billId", APPLIED, "Something was applied to or paid on "
                        + bill.get("billNo") + "; take it back first", Map.of("billNo", (Object) bill.get("billNo"))));
                    return;
                }
                // Its assets go with it: not once something was depreciated or disposed of (F6c), which the
                // register and the roll-forward could no longer show.
                List<String> used = list(ctx, ASSETS).stream()
                    .filter(a -> Boolean.TRUE.equals(a.get("active")) && (a.get("depreciatedThrough") != null
                        || AssetEntities.DISPOSED.equals(a.get("status"))))
                    .map(a -> (String) a.get("assetNo")).toList();
                if (!used.isEmpty()) {
                    ctx.reject(new Violation("billId", ASSET_DEPRECIATED, "Assets of " + bill.get("billNo")
                        + " have been depreciated or disposed of: " + String.join(", ", used),
                        Map.of("billNo", (Object) bill.get("billNo"), "assets", String.join(", ", used))));
                    return;
                }
                if (input.voidDate().isBefore(bill.get("invoiceDate"))) {
                    ctx.reject(new Violation("voidDate", INVALID_VALUE, "A document is voided on or after its date",
                        Map.of("value", input.voidDate().toString())));
                    return;
                }
                ctx.put(SUB_INPUT, new SubledgerPosting.ReverseInput("AP",
                    String.valueOf((Object) bill.get("transactionId")), input.voidDate(),
                    "Void of " + bill.get("billNo") + ": " + input.reason().trim(), bill.get("billNo"),
                    BillEntities.BILL, String.valueOf(bill.id())));
            })
            .step("Reverse its entry", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.REVERSE, 1,
                ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT))
            .compute("Record the void", (metadata, ctx) -> {
                if (!ctx.contains(SUB_OUTPUT)) {
                    return;
                }
                EntityInstance bill = bill(ctx);
                VoidInput input = ctx.get(INPUT, VoidInput.class);
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("status", BillEntities.VOID);
                values.put("voidDate", input.voidDate());
                values.put("voidReason", input.reason().trim());
                values.put("voidGlNo", ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class).glNo());
                values.put("openAmount", BigDecimal.ZERO.setScale(2));
                if (bill.get("openAmountUsd") != null) {
                    values.put("openAmountUsd", BigDecimal.ZERO.setScale(2));
                }
                ctx.changes().update(BillEntities.BILL, bill.id(), bill.version(), values);
                // The cost the assets carried is reversed with the bill.
                for (EntityInstance asset : list(ctx, ASSETS)) {
                    ctx.changes().update(AssetEntities.ASSET, asset.id(), asset.version(), Map.of("active", false));
                }
                ctx.put(OUTPUT, output(bill, values, List.of(), List.of()));
            })
            // A void bill is never paid: the approval it waited for, and the approver's task, go.
            .step("Withdraw its approval request", WithdrawApproval.of(SUBJECT, ctx -> ctx.contains(SUB_OUTPUT)
                && BillEntities.PENDING.equals(bill(ctx).get("approval")) ? bill(ctx).id() : "none")));

    // ---- apply a vendor credit -------------------------------------------------------------------------------------

    public static final ProcessDefinition<ApplyInput, ApplyOutput, ProcessContext> APPLY_PROCESS =
        ProcessDefinition.define(APPLY, 1, ApplyInput.class, ApplyOutput.class, ProcessContext.class, pb -> pb
            .description("Applies a vendor credit to an open bill of the same vendor.")
            .permissions(FinancePermissions.BILL_PREPARE)
            .contextFactory(BillProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, ApplyOutput.class))
            .step("Load both documents", QueryEntities.of(BillEntities.BILL_DATASET, ctx -> {
                ApplyInput input = ctx.get(INPUT, ApplyInput.class);
                return byIds(input.creditId(), input.billId());
            }, FOUND))
            .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                ctx -> SubledgerPosting.periodsOn(ctx.get(INPUT, ApplyInput.class).applicationDate()), PERIODS))
            .step("Load the settings", QueryEntities.of(ApEntities.SETTINGS_DATASET,
                ctx -> ApSettingsProcesses.current(), SETTINGS))
            .step("Load the foreign currency settings", QueryEntities.of(FxEntities.SETTINGS_DATASET,
                ctx -> FxSettingsProcesses.current(), FX_SETTINGS))
            .compute("Apply the credit", (metadata, ctx) -> apply(ctx))
            // At different rates payables take the difference, a realized gain or loss (FIN-FX-004).
            .step("Book the difference", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.POST, 1,
                ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT)));

    static void apply(ProcessContext ctx) {
        ApplyInput input = ctx.get(INPUT, ApplyInput.class);
        EntityInstance credit = find(ctx, FOUND, input.creditId());
        EntityInstance bill = find(ctx, FOUND, input.billId());
        String reason = null;
        if (credit == null || !BillEntities.CREDIT.equals(credit.get("kind"))
            || !BillEntities.POSTED.equals(credit.get("status"))) {
            reason = "the credit is not a posted vendor credit";
        } else if (bill == null || !BillEntities.BILL_KIND.equals(bill.get("kind"))
            || !BillEntities.POSTED.equals(bill.get("status"))) {
            reason = "the bill is not a posted bill";
        } else if (BillEntities.REJECTED.equals(bill.get("approval"))) {
            reason = "the bill's approval was refused: it is voided, not paid or credited";
        } else if (!Objects.equals(credit.get("vendorCode"), bill.get("vendorCode"))) {
            reason = "the credit and the bill are of different vendors";
        } else if (!ApFx.currency(credit).equals(ApFx.currency(bill))) {
            reason = "the credit and the bill are in different currencies";
        } else if (input.amount().compareTo(credit.get("openAmount")) > 0
            || input.amount().compareTo(bill.get("openAmount")) > 0) {
            reason = "the amount is more than is open on the credit or the bill";
        } else if (input.applicationDate().isBefore(credit.get("invoiceDate"))
            || input.applicationDate().isBefore(bill.get("invoiceDate"))) {
            reason = "a credit is applied on or after the dates of both documents";
        }
        if (reason != null) {
            ctx.reject(new Violation("amount", APPLY_REFUSED, "The credit cannot be applied: " + reason,
                Map.of("reason", reason)));
            return;
        }
        var closed = SubledgerPosting.periodRefusal(list(ctx, PERIODS), "AP", input.applicationDate(),
            "applicationDate");
        if (closed.isPresent()) {
            ctx.reject(closed.get());
            return;
        }
        BigDecimal billOpen = bill.<BigDecimal>get("openAmount").subtract(input.amount());
        BigDecimal creditOpen = credit.<BigDecimal>get("openAmount").subtract(input.amount());
        // The bill gives up the dollars it carries for the amount; the credit gives what it carries. A bill carrying
        // more than the credit leaves less owed: a gain.
        BigDecimal billUsd = ApFx.cleared(bill, input.amount());
        BigDecimal creditUsd = ApFx.cleared(credit, input.amount());
        BigDecimal gainLoss = billUsd.subtract(creditUsd);
        String memo = credit.get("billNo") + " to " + bill.get("billNo");
        if (!difference(ctx, gainLoss, input.applicationDate(), memo, credit)) {
            return;
        }
        Map<String, Object> application = new LinkedHashMap<>();
        application.put("sourceKind", BillEntities.CREDIT_SOURCE);
        application.put("sourceId", String.valueOf(credit.id()));
        application.put("sourceNo", credit.get("billNo"));
        application.put("billId", bill.id());
        application.put("vendorCode", bill.get("vendorCode"));
        application.put("applicationDate", input.applicationDate());
        application.put("amount", input.amount());
        if (!ApFx.dollars(ApFx.currency(bill))) {
            application.put("amountUsd", billUsd);
            application.put("sourceAmountUsd", creditUsd);
            application.put("fxGainLoss", gainLoss);
        }
        Object id = ctx.changes().insert(BillEntities.APPLICATION, application);
        ctx.changes().update(BillEntities.BILL, bill.id(), bill.version(), open(bill, billOpen,
            ApFx.openUsd(bill).subtract(billUsd)));
        ctx.changes().update(BillEntities.BILL, credit.id(), credit.version(), open(credit, creditOpen,
            ApFx.openUsd(credit).subtract(creditUsd)));
        ctx.put(OUTPUT, new ApplyOutput(String.valueOf(id), billOpen, creditOpen));
    }

    /** What is open on a document, in its currency and, when it keeps them, in US dollars. */
    static Map<String, Object> open(EntityInstance document, BigDecimal open, BigDecimal openUsd) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("openAmount", open);
        if (document.get("openAmountUsd") != null) {
            values.put("openAmountUsd", openUsd);
        }
        return values;
    }

    /**
     * Books a realized difference of applying a credit (a gain positive) to payables and the realized account; false,
     * refused, when the settings name no account for it.
     */
    private static boolean difference(ProcessContext ctx, BigDecimal gainLoss, LocalDate day, String memo,
        EntityInstance credit) {
        if (gainLoss.signum() == 0) {
            return true;
        }
        EntityInstance settings = first(ctx, SETTINGS);
        EntityInstance fx = first(ctx, FX_SETTINGS);
        if (settings == null || fx == null || fx.get("realizedAccount") == null) {
            ctx.reject(new Violation("amount", FxSettingsProcesses.NO_SETTINGS, "The credit and the bill carry "
                + "different rates and the foreign currency settings name no account for the exchange gain or loss",
                Map.of()));
            return false;
        }
        ctx.put(SUB_INPUT, new SubledgerPosting.PostInput("AP", day, "Application of " + memo, credit.get("billNo"),
            BillEntities.BILL, String.valueOf(credit.id()), ApFx.difference(settings.get("payableAccount"),
            fx.get("realizedAccount"), gainLoss, memo), List.of("AP")));
        return true;
    }

    public static final ProcessDefinition<UnapplyInput, ApplyOutput, ProcessContext> UNAPPLY_PROCESS =
        ProcessDefinition.define(UNAPPLY, 1, UnapplyInput.class, ApplyOutput.class, ProcessContext.class, pb -> pb
            .description("Takes back a vendor credit applied to a bill.")
            .permissions(FinancePermissions.BILL_PREPARE)
            .contextFactory(BillProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, ApplyOutput.class))
            .step("Load the application and what took it back", QueryEntities.of(BillEntities.APPLICATION_DATASET,
                ctx -> {
                    UUID id = ctx.get(INPUT, UnapplyInput.class).applicationId();
                    return EntityQuery.builder().where(new QueryPredicate.Or(List.of(
                        new QueryPredicate.Eq("applicationId", id),
                        new QueryPredicate.Eq("reversesApplicationId", id)))).limit(10).build();
                }, APPLICATIONS))
            .step("Load both documents", QueryEntities.of(BillEntities.BILL_DATASET, ctx -> {
                UUID id = ctx.get(INPUT, UnapplyInput.class).applicationId();
                EntityInstance application = list(ctx, APPLICATIONS).stream()
                    .filter(a -> id.equals(uuid(a.id()))).findFirst().orElse(null);
                return application == null ? byIds() : byIds(uuid(application.get("billId")),
                    safeUuid(application.get("sourceId")));
            }, FOUND))
            .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                ctx -> SubledgerPosting.periodsOn(ctx.get(INPUT, UnapplyInput.class).applicationDate()), PERIODS))
            .step("Load the settings", QueryEntities.of(ApEntities.SETTINGS_DATASET,
                ctx -> ApSettingsProcesses.current(), SETTINGS))
            .step("Load the foreign currency settings", QueryEntities.of(FxEntities.SETTINGS_DATASET,
                ctx -> FxSettingsProcesses.current(), FX_SETTINGS))
            .compute("Take it back", (metadata, ctx) -> unapply(ctx))
            .step("Take back the difference", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.POST,
                1, ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT)));

    static void unapply(ProcessContext ctx) {
        UnapplyInput input = ctx.get(INPUT, UnapplyInput.class);
        EntityInstance application = list(ctx, APPLICATIONS).stream()
            .filter(a -> input.applicationId().equals(uuid(a.id()))).findFirst().orElse(null);
        String reason = null;
        if (application == null || !BillEntities.CREDIT_SOURCE.equals(application.get("sourceKind"))
            || application.get("reversesApplicationId") != null) {
            reason = "it is not an application of a vendor credit";
        } else if (list(ctx, APPLICATIONS).stream().anyMatch(a -> a.get("reversesApplicationId") != null)) {
            reason = "it was taken back already";
        } else if (input.applicationDate().isBefore(application.get("applicationDate"))) {
            reason = "an application is taken back on or after its date";
        }
        EntityInstance bill = application == null ? null : find(ctx, FOUND, uuid(application.get("billId")));
        EntityInstance credit = application == null ? null : find(ctx, FOUND, safeUuid(application.get("sourceId")));
        if (reason == null && (bill == null || credit == null || !BillEntities.POSTED.equals(bill.get("status"))
            || !BillEntities.POSTED.equals(credit.get("status")))) {
            reason = "the bill or the credit is no longer posted";
        }
        if (reason != null) {
            ctx.reject(new Violation("applicationId", UNAPPLY_REFUSED, "The application cannot be taken back: "
                + reason, Map.of("reason", reason)));
            return;
        }
        var closed = SubledgerPosting.periodRefusal(list(ctx, PERIODS), "AP", input.applicationDate(),
            "applicationDate");
        if (closed.isPresent()) {
            ctx.reject(closed.get());
            return;
        }
        BigDecimal amount = application.get("amount");
        BigDecimal gainLoss = application.get("fxGainLoss") == null ? BigDecimal.ZERO : application.get("fxGainLoss");
        if (!difference(ctx, gainLoss.negate(), input.applicationDate(), "back " + application.get("sourceNo")
            + " to " + bill.get("billNo"), credit)) {
            return;
        }
        Map<String, Object> back = new LinkedHashMap<>();
        back.put("sourceKind", BillEntities.CREDIT_SOURCE);
        back.put("sourceId", application.get("sourceId"));
        back.put("sourceNo", application.get("sourceNo"));
        back.put("billId", application.get("billId"));
        back.put("vendorCode", application.get("vendorCode"));
        back.put("applicationDate", input.applicationDate());
        back.put("amount", amount.negate());
        if (application.get("amountUsd") != null) {
            back.put("amountUsd", ApFx.amountUsd(application).negate());
            back.put("sourceAmountUsd", ApFx.sourceUsd(application).negate());
            back.put("fxGainLoss", gainLoss.negate());
        }
        back.put("reversesApplicationId", application.id());
        back.put("reason", input.reason().trim());
        Object id = ctx.changes().insert(BillEntities.APPLICATION, back);
        BigDecimal billOpen = bill.<BigDecimal>get("openAmount").add(amount);
        BigDecimal creditOpen = credit.<BigDecimal>get("openAmount").add(amount);
        ctx.changes().update(BillEntities.BILL, bill.id(), bill.version(), open(bill, billOpen,
            ApFx.openUsd(bill).add(ApFx.amountUsd(application))));
        ctx.changes().update(BillEntities.BILL, credit.id(), credit.version(), open(credit, creditOpen,
            ApFx.openUsd(credit).add(ApFx.sourceUsd(application))));
        ctx.put(OUTPUT, new ApplyOutput(String.valueOf(id), billOpen, creditOpen));
    }

    // ---- approval results ------------------------------------------------------------------------------------------

    /**
     * Marks a bill approved or rejected when its approvers decide (FIN-AP-006). The event is only a pointer: what was
     * decided is read from the platform's approval request, which must be this bill's own.
     */
    public static final ProcessDefinition<ApprovalResultInput, BillOutput, ProcessContext> APPROVAL_RESULT_PROCESS =
        ProcessDefinition.define(APPROVAL_RESULT, 1, ApprovalResultInput.class, BillOutput.class,
            ProcessContext.class, pb -> pb
                .description("Marks a bill waiting for approval approved or rejected.")
                .permissions(FinancePermissions.AP_INTERNAL)
                .internal()
                .contextFactory(BillProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, BillOutput.class))
                .step("Load the bill", QueryEntities.of(BillEntities.BILL_DATASET, ctx -> {
                    ApprovalResultInput input = ctx.get(INPUT, ApprovalResultInput.class);
                    return byIds(SUBJECT.equals(input.subject()) ? safeUuid(input.entityId()) : null);
                }, FOUND))
                .step("Load the approval request", QueryEntities.of(ApprovalEntities.REQUEST_DATASET, ctx -> {
                    UUID request = safeUuid(ctx.get(INPUT, ApprovalResultInput.class).requestId());
                    return EntityQuery.builder().where(new QueryPredicate.In("requestId",
                        request == null ? List.of() : List.of(request))).limit(1).build();
                }, REQUESTS))
                .compute("Mark it", (metadata, ctx) -> {
                    ApprovalResultInput input = ctx.get(INPUT, ApprovalResultInput.class);
                    EntityInstance bill = first(ctx, FOUND);
                    // Whatever the event says, there is an answer: an event of another subject changes nothing.
                    ctx.put(OUTPUT, bill == null ? new BillOutput(null, null, null, null, null, null, null, null,
                        null, null, input.requestId(), List.of(), List.of()) : output(bill, Map.of(), List.of(),
                        List.of()));
                    EntityInstance request = first(ctx, REQUESTS);
                    if (bill == null || request == null || !BillEntities.POSTED.equals(bill.get("status"))
                        || !BillEntities.PENDING.equals(bill.get("approval"))
                        || !Objects.equals(input.requestId(), bill.get("approvalRequestId"))
                        || !SUBJECT.equals(request.get("subject"))
                        || !String.valueOf(bill.id()).equals(request.get("entityId"))) {
                        return;
                    }
                    String decided = request.get("status");
                    if (!ApprovalEntities.APPROVED.equals(decided) && !ApprovalEntities.REJECTED.equals(decided)) {
                        return;
                    }
                    Map<String, Object> values = Map.of("approval", ApprovalEntities.APPROVED.equals(decided)
                        ? BillEntities.APPROVED : BillEntities.REJECTED);
                    ctx.changes().update(BillEntities.BILL, bill.id(), bill.version(), values);
                    ctx.put(OUTPUT, output(bill, values, List.of(), List.of()));
                }));

    // ---- opening open items ----------------------------------------------------------------------------------------

    private static final java.util.regex.Pattern NUMBERED = java.util.regex.Pattern.compile("(BILL|VC)-(\\d{1,18})");

    public static final ProcessDefinition<OpeningInput, OpeningOutput, ProcessContext> OPENING_PROCESS =
        ProcessDefinition.define(OPENING, 1, OpeningInput.class, OpeningOutput.class, ProcessContext.class, pb -> pb
            .description("Brings over the legacy system's open payables; they add up to the opening payables.")
            .permissions(FinancePermissions.MIGRATION)
            .contextFactory(BillProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, OpeningOutput.class))
            .step("Load the settings", QueryEntities.of(ApEntities.SETTINGS_DATASET,
                ctx -> ApSettingsProcesses.current(), SETTINGS))
            .step("Load the vendors", QueryEntities.of(ApEntities.VENDOR_DATASET, ctx -> {
                List<Object> codes = ctx.get(INPUT, OpeningInput.class).items().stream()
                    .map(i -> (Object) VendorProcesses.code(i.vendorCode())).distinct().toList();
                return EntityQuery.builder().where(new QueryPredicate.In("vendorCode", new ArrayList<>(codes)))
                    .limit(codes.size() + 1).build();
            }, VENDORS))
            .step("Load the opening entry", QueryEntities.of(JournalEntities.JOURNAL_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("source", JournalEntities.OPENING)).limit(1)
                    .build(), JOURNALS))
            .step("Load its payables lines", QueryEntities.of(JournalEntities.LINE_DATASET, ctx -> {
                List<EntityInstance> journals = list(ctx, JOURNALS);
                List<EntityInstance> settings = list(ctx, SETTINGS);
                if (journals.isEmpty() || settings.isEmpty()) {
                    return EntityQuery.builder().where(new QueryPredicate.In("journalId", List.of())).limit(1).build();
                }
                return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("journalId", journals.getFirst().id()),
                    new QueryPredicate.Eq("accountCode", settings.getFirst().get("payableAccount"))))).limit(500)
                    .build();
            }, LINES))
            .step("Look for items brought over", QueryEntities.of(BillEntities.BILL_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("source", BillEntities.OPENING)).limit(1)
                    .build(), FOUND))
            .compute("Bring the items over", (metadata, ctx) -> opening(ctx)));

    static void opening(ProcessContext ctx) {
        OpeningInput input = ctx.get(INPUT, OpeningInput.class);
        // Once: further open items would be payables without an entry.
        if (!list(ctx, FOUND).isEmpty()) {
            ctx.reject(new Violation("items", OPENING_DONE, "The open payables were brought over already", Map.of()));
            return;
        }
        Map<String, EntityInstance> vendors = new LinkedHashMap<>();
        list(ctx, VENDORS).forEach(v -> vendors.put(v.get("vendorCode"), v));
        LocalDate opening = list(ctx, JOURNALS).isEmpty() ? null : list(ctx, JOURNALS).getFirst().get("postingDate");
        Set<String> documents = new LinkedHashSet<>();
        for (int i = 0; i < input.items().size(); i++) {
            OpeningItem item = input.items().get(i);
            if (!documents.add(item.document().trim().toUpperCase(Locale.ROOT))) {
                ctx.reject(new Violation("items[" + i + "].document", OPENING_TWICE, "Document " + item.document()
                    + " is in the file twice", Map.of("value", item.document())));
            }
            if (opening != null && item.invoiceDate().isAfter(opening)) {
                // Dated after the opening entry, it would not be open on the opening day.
                ctx.reject(new Violation("items[" + i + "].invoiceDate", OPENING_DATE, "An open item is dated on or "
                    + "before the opening entry, " + opening, Map.of("value", item.invoiceDate().toString(),
                    "opening", opening.toString())));
            }
            EntityInstance vendor = vendors.get(VendorProcesses.code(item.vendorCode()));
            if (vendor != null && (!"ACTIVE".equals(vendor.get("status")) || !"USD".equals(vendor.get("currency")))) {
                ctx.reject(new Violation("items[" + i + "].vendorCode", UNKNOWN_VENDOR, "Open items are of active "
                    + "vendors in US dollars; " + item.vendorCode() + " is not one",
                    Map.of("vendorCode", item.vendorCode())));
            }
            if (NUMBERED.matcher(item.document().trim()).matches()) {
                ctx.reject(new Violation("items[" + i + "].document", OPENING_NUMBER, "Document " + item.document()
                    + " would collide with the numbering of new documents", Map.of("document", item.document())));
            }
            if (item.dueDate().isBefore(item.invoiceDate())) {
                ctx.reject(new Violation("items[" + i + "].dueDate", INVALID_VALUE, "An item is due on or after its "
                    + "date", Map.of("value", item.dueDate().toString())));
            }
            if (!vendors.containsKey(VendorProcesses.code(item.vendorCode()))) {
                ctx.reject(new Violation("items[" + i + "].vendorCode", UNKNOWN_VENDOR, "There is no vendor "
                    + item.vendorCode(), Map.of("vendorCode", item.vendorCode())));
            }
        }
        if (ctx.hasViolations()) {
            return;
        }
        if (list(ctx, SETTINGS).isEmpty()) {
            ctx.reject(new Violation("items", NO_SETTINGS, "The payables settings are not set", Map.of()));
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
            ledger = ledger.add(credit == null ? BigDecimal.ZERO : credit).subtract(debit == null ? BigDecimal.ZERO
                : debit);
        }
        BigDecimal total = input.items().stream().map(OpeningItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(ledger) != 0) {
            ctx.reject(new Violation("items", OPENING_TOTAL, "The open items add up to " + total.toPlainString()
                + ", the opening payables are " + ledger.toPlainString(), Map.of("total", total, "ledger", ledger,
                "difference", total.subtract(ledger).abs())));
            return;
        }
        for (OpeningItem item : input.items()) {
            EntityInstance vendor = vendors.get(VendorProcesses.code(item.vendorCode()));
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("kind", BillEntities.BILL_KIND);
            values.put("billNo", item.document().trim());
            values.put("vendorCode", vendor.get("vendorCode"));
            values.put("vendorInvoiceNo", item.document().trim());
            values.put("vendorInvoiceKey", BillDuplicates.key(item.document()));
            values.put("invoiceDate", item.invoiceDate());
            values.put("dueDate", item.dueDate());
            values.put("currency", "USD");
            values.put("termsCode", vendor.get("termsCode"));
            values.put("description", "Open item brought over from the legacy system");
            // Paid later, it is reported as the vendor's 1099 setting says (DC-2025-12 in V200's 2026 1099).
            values.put("form1099", vendor.get("form1099"));
            values.put("box1099", vendor.get("box1099"));
            values.put("source", BillEntities.OPENING);
            values.put("status", BillEntities.POSTED);
            // The legacy system approved it.
            values.put("approval", BillEntities.NOT_REQUIRED);
            values.put("subtotal", item.amount());
            values.put("useTaxTotal", BigDecimal.ZERO.setScale(2));
            values.put("total", item.amount());
            values.put("openAmount", item.amount());
            ctx.changes().insert(BillEntities.BILL, values);
        }
        ctx.put(OUTPUT, new OpeningOutput(input.items().size(), total));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** An expense or asset account a bill posts to: no control account but, for a bill, a fixed asset cost account. */
    private static boolean billAccount(ProcessContext ctx, String code, boolean credit) {
        // A credit on an asset's cost would take it off the asset, which the register does from F6 only.
        return list(ctx, ACCOUNTS).stream().anyMatch(a -> Objects.equals(code, a.get("accountCode"))
            && List.of("EXPENSE", "ASSET", "OTHER").contains(String.valueOf((Object) a.get("financialType")))
            && (a.get("controlClass") == null || !credit && "FA_COST".equals(a.get("controlClass"))));
    }

    static EntityQuery accountsOf(List<String> codes) {
        List<Object> present = codes.stream().filter(c -> c != null && !c.isBlank()).map(String::trim).distinct()
            .map(c -> (Object) c).toList();
        return EntityQuery.builder().where(new QueryPredicate.In("accountCode", new ArrayList<>(present)))
            .limit(present.size() + 1).build();
    }

    private static List<String> useTaxCodes(List<String> codes) {
        return codes.stream().filter(c -> c != null && !c.isBlank()).map(c -> c.trim().toUpperCase(Locale.ROOT))
            .distinct().toList();
    }

    static EntityQuery byIds(UUID... ids) {
        List<Object> present = Arrays.stream(ids).filter(Objects::nonNull).map(id -> (Object) id).toList();
        return EntityQuery.builder().where(new QueryPredicate.In("billId", new ArrayList<>(present)))
            .limit(present.size() + 1).build();
    }

    static EntityQuery linesOf(Object billId) {
        return EntityQuery.builder().where(new QueryPredicate.In("billId",
            billId == null ? List.of() : List.of(billId))).limit(500).build();
    }

    static EntityQuery applicationsOf(Object documentId) {
        return EntityQuery.builder().where(new QueryPredicate.Or(List.of(
            new QueryPredicate.Eq("billId", documentId),
            new QueryPredicate.Eq("sourceId", String.valueOf(documentId))))).limit(5000).build();
    }

    /** The vendor's documents that may duplicate a bill: the same number, or the same date. */
    static EntityQuery candidates(String vendorCode, String vendorInvoiceNo, LocalDate invoiceDate) {
        String code = VendorProcesses.code(vendorCode);
        if (code == null) {
            return EntityQuery.builder().where(new QueryPredicate.In("vendorCode", List.of())).limit(1).build();
        }
        List<QueryPredicate> either = new ArrayList<>();
        either.add(new QueryPredicate.Eq("vendorInvoiceKey", BillDuplicates.key(vendorInvoiceNo)));
        if (invoiceDate != null) {
            either.add(new QueryPredicate.Eq("invoiceDate", invoiceDate));
        }
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("vendorCode", code),
            new QueryPredicate.Or(either)))).limit(5000).build();
    }

    private static EntityInstance find(ProcessContext ctx, String key, UUID id) {
        if (id == null) {
            return null;
        }
        return list(ctx, key).stream().filter(e -> id.equals(uuid(e.id()))).findFirst().orElse(null);
    }

    private static EntityInstance first(ProcessContext ctx, String key) {
        return list(ctx, key).isEmpty() ? null : list(ctx, key).getFirst();
    }

    private static Violation notDraft(EntityInstance bill) {
        String number = String.valueOf(bill.get("billNo") == null ? bill.id() : bill.get("billNo"));
        return new Violation("billId", NOT_DRAFT, "Document " + number + " is " + bill.get("status") + ": a posted "
            + "document is corrected by a vendor credit or voided", Map.of("billNo", number,
            "status", (Object) bill.get("status")));
    }

    private static BillOutput output(EntityInstance bill, Map<String, Object> changed, List<String> warnings,
        List<String> assets) {
        Map<String, Object> state = new LinkedHashMap<>(bill.attributes());
        state.putAll(changed);
        return new BillOutput(String.valueOf(bill.id()), (String) state.get("billNo"), (String) state.get("kind"),
            (String) state.get("status"), (LocalDate) state.get("dueDate"), (BigDecimal) state.get("total"),
            (BigDecimal) state.get("useTaxTotal"), (BigDecimal) state.get("openAmount"), (String) state.get("glNo"),
            (String) state.get("approval"), (String) state.get("approvalRequestId"), warnings, assets);
    }

    static UUID uuid(Object value) {
        return value == null ? null : value instanceof UUID u ? u : UUID.fromString(value.toString());
    }

    private static UUID safeUuid(Object value) {
        try {
            return value == null ? null : UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static BillInput saveInput(ProcessContext ctx) {
        return ctx.get(INPUT, BillInput.class);
    }

    private static EntityInstance bill(ProcessContext ctx) {
        return ctx.get(BILL, EntityInstance.class);
    }

    private static Prepared prepared(ProcessContext ctx) {
        return ctx.get(PREPARED, Prepared.class);
    }

    static ProcessContext withInput(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private static ProcessContext withId(ProcessStart start, BillId input) {
        ProcessContext ctx = withInput(start, input);
        ctx.put(BILL_ID, input.billId());
        return ctx;
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    static NumberSequence billNumbers() {
        return NumberSequence.define(BILL_NUMBERS, s -> s.format("BILL-{n}").startAt(1));
    }

    static NumberSequence creditNumbers() {
        return NumberSequence.define(CREDIT_NUMBERS, s -> s.format("VC-{n}").startAt(1));
    }

    private BillProcesses() {}
}

package com.jabiz.finance.ap;

import com.jabiz.document.DocumentLayout;
import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.Form1099File;
import com.jabiz.finance.company.CompanyEntities;
import com.jabiz.finance.company.CompanyProcesses;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.document.DocumentProcesses;
import com.jabiz.runtime.file.GeneratedFileProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Forms 1099 of a tax year (FIN-AP-021…023; docs/finance/00-design.md section 9):
 * <ul>
 *   <li>{@code FIN_1099_ISSUE}: a reportable vendor's recipient copy, a PDF document ({@code finance.ap.form_1099})
 *       kept exactly as issued; the recipient's TIN truncated as the IRS allows on recipient copies.</li>
 *   <li>{@code FIN_1099_EXPORT}: the year's export for electronic filing (a filing-service CSV, {@link Form1099File}),
 *       one record per reportable vendor, form and box, each kept as a {@code Fin1099Filing}; refused while a
 *       reportable vendor has no TIN or address, or the company no EIN.</li>
 *   <li>{@code FIN_1099_CORRECT}: after the year was filed, the records whose amount or TIN changed since, marked
 *       corrected, and those that became reportable since (FIN-AP-023).</li>
 * </ul>
 * The amounts are the payments' ({@code Fin1099Amount}); a vendor's form is reported when its total for the year
 * reaches the threshold of the threshold table (FIN-AP-021). The files hold TINs in full: only who files reads them.
 */
public final class Form1099Processes {

    public static final String ISSUE = "FIN_1099_ISSUE";
    public static final String EXPORT = "FIN_1099_EXPORT";
    public static final String CORRECT = "FIN_1099_CORRECT";

    public static final String COPY_LAYOUT = "finance.ap.form_1099";
    public static final String REPORT = "finance.ap.form_1099";
    public static final String COPY_HEADER = "finance.ap.form_1099_copy_header";
    public static final String COPY_LINES = "finance.ap.form_1099_copy_lines";

    public static final String NOT_REPORTABLE = "FIN_1099_NOT_REPORTABLE";
    public static final String NO_PAYER_TIN = "FIN_1099_NO_PAYER_TIN";
    public static final String MISSING = "FIN_1099_MISSING";
    public static final String FILED = "FIN_1099_FILED";
    public static final String NOT_FILED = "FIN_1099_NOT_FILED";
    public static final String NOTHING = "FIN_1099_NOTHING";
    public static final String TOO_MANY = "FIN_1099_TOO_MANY";

    /** The recipient copy: payer and recipient, then each form and box with its amount. */
    public static final DocumentLayout COPY = DocumentLayout.define(COPY_LAYOUT, d -> d
        .permissions(FinancePermissions.FORM_1099_FILE)
        .subject(ApEntities.VENDOR, "vendorId")
        .number(COPY_HEADER, "documentNo")
        .party("payer", COPY_HEADER, "payerName", "payerStreet", "payerCityLine", "payerTin")
        .party("recipient", COPY_HEADER, "recipientName", "recipientStreet", "recipientCityLine", "recipientTin")
        .facts(COPY_HEADER, "taxYear", "accountNumber")
        .table(COPY_LINES, "form1099", "box1099", "boxName", "amount")
        .note("copyB"));

    public record YearInput(@NotNull @Min(2000) @Max(2100) Integer taxYear) {}

    public record IssueInput(@NotNull @Min(2000) @Max(2100) Integer taxYear,
        @NotBlank @Size(max = 20) String vendorCode) {}

    public record IssueOutput(String runId, String documentNo, String pdfHash) {}

    /** One record of a filing: the vendor, form, box and amount, and whether it corrects one filed before. */
    public record Filed(String vendorCode, String form1099, String box1099, BigDecimal amount, String kind) {}

    public record ExportOutput(String fileId, String fileName, String sha256, List<Filed> records) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String BOXES = "boxes";
    static final String VENDORS = "vendors";
    static final String TAX_INFOS = "taxInfos";
    static final String PROFILES = "profiles";
    static final String FILINGS = "filings";
    static final String ARCHIVE_INPUT = "archiveInput";
    static final String ARCHIVED = "archived";
    static final String RECORDS = "records";
    static final String ISSUE_INPUT = "issueInput";
    static final String ISSUED = "issued";

    static final int MAX_ROWS = PaymentProcesses.MAX_ROWS;

    /** A vendor's form and box of a year: its amount, and whether the vendor's form is reported. */
    record Box(String vendorCode, String form, String box, BigDecimal amount, boolean reportable) {}

    // ---- recipient copies ------------------------------------------------------------------------------------------

    public static final ProcessDefinition<IssueInput, IssueOutput, ProcessContext> ISSUE_PROCESS =
        ProcessDefinition.define(ISSUE, 1, IssueInput.class, IssueOutput.class, ProcessContext.class, pb -> pb
            .description("Issues a reportable vendor's Form 1099 recipient copy for a tax year.")
            .permissions(FinancePermissions.FORM_1099_FILE)
            .contextFactory(Form1099Processes::withInput)
            .outputMapper(ctx -> {
                DocumentProcesses.IssueOutput issued = ctx.get(ISSUED, DocumentProcesses.IssueOutput.class);
                return new IssueOutput(issued.runId(), issued.documentNo(), issued.pdfHash());
            })
            .step("Load the vendor", QueryEntities.of(ApEntities.VENDOR_DATASET, ctx -> VendorProcesses.eq(
                "vendorCode", VendorProcesses.code(ctx.get(INPUT, IssueInput.class).vendorCode())), VENDORS))
            .step("Load its Form 1099", RunTemplate.of(REPORT, ctx -> Map.of("taxYear",
                ctx.get(INPUT, IssueInput.class).taxYear()), BOXES))
            .step("Load its tax information", QueryEntities.of(ApEntities.TAX_INFO_DATASET, ctx -> VendorProcesses.eq(
                "vendorCode", VendorProcesses.code(ctx.get(INPUT, IssueInput.class).vendorCode())), TAX_INFOS))
            .step("Load the company", QueryEntities.of(CompanyEntities.PROFILE_DATASET,
                ctx -> CompanyProcesses.current(), PROFILES))
            .compute("Check it", (metadata, ctx) -> {
                IssueInput input = ctx.get(INPUT, IssueInput.class);
                EntityInstance vendor = first(ctx, VENDORS);
                if (vendor == null || boxes(ctx).stream().noneMatch(b -> b.reportable()
                    && b.vendorCode().equals(vendor.get("vendorCode")))) {
                    ctx.reject(new Violation("vendorCode", NOT_REPORTABLE, "Nothing of "
                        + input.vendorCode() + " is reported on Form 1099 for " + input.taxYear(),
                        Map.of("vendorCode", input.vendorCode(), "taxYear", input.taxYear())));
                    return;
                }
                if (!payerTin(ctx)) {
                    return;
                }
                EntityInstance taxInfo = first(ctx, TAX_INFOS);
                if (!filable(vendor, taxInfo)) {
                    ctx.reject(new Violation("vendorCode", MISSING, "Without a TIN or an address on file: "
                        + vendor.get("vendorCode") + " (see the 1099 review report)",
                        Map.of("vendors", (Object) vendor.get("vendorCode"))));
                    return;
                }
                ctx.put(ISSUE_INPUT, new DocumentProcesses.IssueInput(COPY_LAYOUT, Map.of("vendorId",
                    String.valueOf(vendor.id()), "taxYear", input.taxYear()), null, null, null));
            })
            .step("Issue it", CallProcess.<ProcessContext>when(ctx -> ctx.contains(ISSUE_INPUT),
                DocumentProcesses.ISSUE, 1, ctx -> ctx.get(ISSUE_INPUT), ISSUED)));

    // ---- export and corrections ------------------------------------------------------------------------------------

    public static final ProcessDefinition<YearInput, ExportOutput, ProcessContext> EXPORT_PROCESS = filing(EXPORT,
        "Exports a tax year's Forms 1099 for electronic filing and records what was filed.", false);

    public static final ProcessDefinition<YearInput, ExportOutput, ProcessContext> CORRECT_PROCESS = filing(CORRECT,
        "Exports the corrections of a filed tax year's Forms 1099: changed amounts and TINs, and late records.",
        true);

    private static ProcessDefinition<YearInput, ExportOutput, ProcessContext> filing(String name, String description,
        boolean correction) {
        return ProcessDefinition.define(name, 1, YearInput.class, ExportOutput.class, ProcessContext.class, pb -> pb
            .description(description)
            .permissions(FinancePermissions.FORM_1099_FILE)
            .contextFactory(Form1099Processes::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, ExportOutput.class))
            // The year's boxes as the 1099 report adds them up: one row a vendor's box, never cut short.
            .step("Load the year's Forms 1099", RunTemplate.of(REPORT, ctx -> Map.of("taxYear",
                ctx.get(INPUT, YearInput.class).taxYear()), BOXES))
            .step("Load what was filed", QueryEntities.of(Form1099Entities.FILING_DATASET, ctx -> EntityQuery
                .builder().where(new QueryPredicate.Eq("taxYear", year(ctx))).limit(MAX_ROWS).build(), FILINGS))
            .step("Load the vendors", QueryEntities.of(ApEntities.VENDOR_DATASET,
                ctx -> byVendors(ctx), VENDORS))
            .step("Load their tax information", QueryEntities.of(ApEntities.TAX_INFO_DATASET,
                ctx -> byVendors(ctx), TAX_INFOS))
            .step("Load the company", QueryEntities.of(CompanyEntities.PROFILE_DATASET,
                ctx -> CompanyProcesses.current(), PROFILES))
            .compute("Make the records", (metadata, ctx) -> records(ctx, correction))
            .step("Keep the file", CallProcess.<ProcessContext>when(ctx -> ctx.contains(ARCHIVE_INPUT),
                GeneratedFileProcesses.ARCHIVE, 1, ctx -> ctx.get(ARCHIVE_INPUT), ARCHIVED))
            .compute("Record what was filed", (metadata, ctx) -> recordFilings(ctx)));
    }

    /** A record to file, with the filing it corrects if any. */
    record Planned(Form1099File.Record record, String tin, EntityInstance supersedes) {}

    static void records(ProcessContext ctx, boolean correction) {
        int taxYear = ctx.get(INPUT, YearInput.class).taxYear();
        if (list(ctx, FILINGS).size() >= MAX_ROWS || list(ctx, VENDORS).size() >= MAX_ROWS) {
            ctx.reject(new Violation("taxYear", TOO_MANY, "More than " + MAX_ROWS + " filings or vendors of "
                + taxYear + ": the export would be cut short", Map.of("limit", MAX_ROWS)));
            return;
        }
        // The latest filing of each vendor, form and box: none supersedes it.
        Map<String, EntityInstance> filed = new TreeMap<>();
        List<String> superseded = list(ctx, FILINGS).stream().map(f -> String.valueOf((Object) f.get("supersedesId")))
            .toList();
        for (EntityInstance filing : list(ctx, FILINGS)) {
            if (!superseded.contains(String.valueOf(filing.id()))) {
                filed.put(key(filing.get("vendorCode"), filing.get("form1099"), filing.get("box1099")), filing);
            }
        }
        if (!correction && !filed.isEmpty()) {
            ctx.reject(new Violation("taxYear", FILED, taxYear + " was filed: changes since are filed as "
                + "corrections", Map.of("taxYear", taxYear)));
            return;
        }
        if (correction && filed.isEmpty()) {
            ctx.reject(new Violation("taxYear", NOT_FILED, taxYear + " was not filed yet: export it first",
                Map.of("taxYear", taxYear)));
            return;
        }
        if (!payerTin(ctx)) {
            return;
        }
        Map<String, EntityInstance> vendors = new LinkedHashMap<>();
        list(ctx, VENDORS).forEach(v -> vendors.put(v.get("vendorCode"), v));
        Map<String, EntityInstance> taxInfos = new LinkedHashMap<>();
        list(ctx, TAX_INFOS).forEach(t -> taxInfos.put(t.get("vendorCode"), t));
        Map<String, Box> current = new TreeMap<>();
        for (Box box : boxes(ctx)) {
            current.put(key(box.vendorCode(), box.form(), box.box()), box);
        }
        // What is due now: each reportable box, and nothing in a box filed before that no longer is.
        Map<String, BigDecimal> due = new TreeMap<>();
        current.forEach((key, box) -> {
            if (box.reportable()) {
                due.put(key, box.amount());
            }
        });
        filed.keySet().forEach(key -> due.putIfAbsent(key, BigDecimal.ZERO.setScale(2)));

        List<Planned> planned = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : due.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            EntityInstance vendor = vendors.get(parts[0]);
            EntityInstance taxInfo = taxInfos.get(parts[0]);
            String tin = taxInfo == null ? null : taxInfo.get("tin");
            if (!filable(vendor, taxInfo)) {
                if (!missing.contains(parts[0])) {
                    missing.add(parts[0]);
                }
                continue;
            }
            EntityInstance before = filed.get(entry.getKey());
            if (before != null && new BigDecimal(String.valueOf((Object) before.get("amount")))
                .compareTo(entry.getValue()) == 0 && Objects.equals(before.get("tin"), tin)) {
                continue;
            }
            planned.add(new Planned(new Form1099File.Record(before != null, taxYear, parts[1], parts[2],
                entry.getValue(), taxInfo.get("tinType"), tin, vendor.get("legalName"), vendor.get("remitStreet"),
                vendor.get("remitCity"), vendor.get("remitState"), vendor.get("remitPostalCode"), parts[0]),
                tin, before));
        }
        if (!missing.isEmpty()) {
            ctx.reject(new Violation("taxYear", MISSING, "Without a TIN or an address on file: "
                + String.join(", ", missing) + " (see the 1099 review report)",
                Map.of("vendors", String.join(", ", missing))));
            return;
        }
        if (planned.isEmpty()) {
            ctx.reject(new Violation("taxYear", NOTHING, correction ? "Nothing changed since " + taxYear
                + " was filed" : "Nothing is reported on Form 1099 for " + taxYear, Map.of("taxYear", taxYear)));
            return;
        }
        EntityInstance company = first(ctx, PROFILES);
        Form1099File.Payer payer = new Form1099File.Payer(company.get("taxId"), company.get("legalName"),
            company.get("street"), company.get("city"), company.get("state"), company.get("postalCode"));
        String content = Form1099File.write(payer, planned.stream().map(Planned::record).toList());
        String fileName = "1099-" + taxYear + (correction ? "-corrections" : "") + ".csv";
        ctx.put(RECORDS, List.copyOf(planned));
        ctx.put(ARCHIVE_INPUT, new GeneratedFileProcesses.ArchiveInput(fileName, "text/csv",
            content.getBytes(StandardCharsets.UTF_8), List.of(FinancePermissions.FORM_1099_FILE),
            null, null));
    }

    @SuppressWarnings("unchecked")
    static void recordFilings(ProcessContext ctx) {
        if (!ctx.contains(ARCHIVED)) {
            return;
        }
        GeneratedFileProcesses.ArchiveOutput archived = ctx.get(ARCHIVED, GeneratedFileProcesses.ArchiveOutput.class);
        GeneratedFileProcesses.ArchiveInput archive = ctx.get(ARCHIVE_INPUT, GeneratedFileProcesses.ArchiveInput.class);
        List<Filed> records = new ArrayList<>();
        for (Planned planned : (List<Planned>) ctx.get(RECORDS)) {
            Form1099File.Record r = planned.record();
            String kind = r.corrected() ? Form1099Entities.CORRECTION : Form1099Entities.ORIGINAL;
            Map<String, Object> filing = new LinkedHashMap<>();
            filing.put("taxYear", BigDecimal.valueOf(r.taxYear()));
            filing.put("vendorCode", r.accountNumber());
            filing.put("form1099", r.form());
            filing.put("box1099", r.box());
            filing.put("amount", r.amount());
            filing.put("tin", planned.tin());
            filing.put("kind", kind);
            filing.put("supersedesId", planned.supersedes() == null ? null : planned.supersedes().id());
            filing.put("generatedFileId", archived.fileId());
            filing.put("filedBy", ctx.request().actorId());
            ctx.changes().insert(Form1099Entities.FILING, filing);
            records.add(new Filed(r.accountNumber(), r.form(), r.box(), r.amount(), kind));
        }
        ctx.put(OUTPUT, new ExportOutput(archived.fileId(), archive.fileName(), archived.sha256(), records));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** The year's boxes as {@code finance.ap.form_1099} reports them, with whether each is reported. */
    @SuppressWarnings("unchecked")
    static List<Box> boxes(ProcessContext ctx) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(BOXES);
        List<Box> boxes = new ArrayList<>();
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            boxes.add(new Box((String) row.get("vendorCode"), (String) row.get("form1099"), (String) row.get("box1099"),
                new BigDecimal(String.valueOf(row.get("amount"))), Boolean.TRUE.equals(row.get("reportable"))));
        }
        return boxes;
    }

    /** Whether a vendor can be filed: a TIN, and an address to mail the recipient copy to. */
    private static boolean filable(EntityInstance vendor, EntityInstance taxInfo) {
        return vendor != null && taxInfo != null && taxInfo.get("tin") != null && !blank(vendor.get("remitStreet"))
            && !blank(vendor.get("remitCity")) && !blank(vendor.get("remitPostalCode"));
    }

    private static boolean payerTin(ProcessContext ctx) {
        EntityInstance company = first(ctx, PROFILES);
        if (company == null || blank(company.get("taxId"))) {
            ctx.reject(new Violation("taxYear", NO_PAYER_TIN, "The company's profile has no EIN: Forms 1099 name "
                + "the payer by it", Map.of()));
            return false;
        }
        return true;
    }

    private static EntityQuery byVendors(ProcessContext ctx) {
        List<Object> codes = new ArrayList<>();
        boxes(ctx).forEach(b -> codes.add(b.vendorCode()));
        list(ctx, FILINGS).forEach(f -> codes.add(f.get("vendorCode")));
        List<Object> distinct = codes.stream().distinct().toList();
        return EntityQuery.builder().where(new QueryPredicate.In("vendorCode", new ArrayList<>(distinct)))
            .limit(Math.max(1, distinct.size())).build();
    }

    private static BigDecimal year(ProcessContext ctx) {
        return BigDecimal.valueOf(ctx.get(INPUT, YearInput.class).taxYear());
    }

    private static String key(Object vendor, Object form, Object box) {
        return vendor + "|" + form + "|" + box;
    }

    private static boolean blank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        return PaymentProcesses.list(ctx, key);
    }

    private static ProcessContext withInput(com.jabiz.process.ProcessStart start, Object input) {
        return PaymentProcesses.withInput(start, input);
    }

    private Form1099Processes() {}
}

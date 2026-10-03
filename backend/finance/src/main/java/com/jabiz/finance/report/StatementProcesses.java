package com.jabiz.finance.report;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.PeriodBalances;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.report.ReportProcesses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The statement layouts' processes (FIN-RP-002, RP-011; ROADMAP F9 decision D2): {@value #PUBLISH} writes a layout's
 * next version; {@value #ISSUE} issues a statement with a definite layout version, refused while an account with a
 * balance is on no line of it (unmapped). The sample layouts follow FIN-EXP-04, 05 and 07.
 */
public final class StatementProcesses {

    public static final String PUBLISH = "FIN_STATEMENT_LAYOUT_PUBLISH";
    public static final String ISSUE = "FIN_STATEMENT_ISSUE";

    public static final String INVALID = "FIN_STATEMENT_LAYOUT_INVALID";
    public static final String UNMAPPED = "FIN_STATEMENT_UNMAPPED";
    public static final String NO_LAYOUT = "FIN_STATEMENT_NO_LAYOUT";
    public static final String NOT_A_STATEMENT = "FIN_STATEMENT_NOT_A_STATEMENT";
    public static final String EMPTY = "FIN_STATEMENT_EMPTY";
    /** A statement the process cannot read whole: its template read is cut at {@link PeriodBalances#CAP} rows. */
    public static final String TOO_LONG = "FIN_STATEMENT_TOO_LONG";

    public static final String BALANCE_SHEET = "finance.report.balance_sheet";
    public static final String INCOME_STATEMENT = "finance.report.income_statement";
    public static final String EQUITY = "finance.report.equity";
    /** Each statement's template and the layout it uses unless told. */
    public static final Map<String, String> DEFAULT_LAYOUTS = Map.of(BALANCE_SHEET, "BS", INCOME_STATEMENT, "IS",
        EQUITY, "EQ");
    /** The statement each template shows. */
    static final Map<String, String> STATEMENT_OF = Map.of(BALANCE_SHEET, StatementEntities.BALANCE_SHEET,
        INCOME_STATEMENT, StatementEntities.INCOME_STATEMENT, EQUITY, StatementEntities.EQUITY);
    /** The row kind the statements give an account with a balance that is on no line. */
    public static final String UNMAPPED_KIND = "UNMAPPED";

    public static final int MAX_ROWS = 200;
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,9}");
    private static final Pattern LINE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,29}");
    private static final Pattern RANGE = Pattern.compile("[0-9A-Z]+(-[0-9A-Z]+)?");

    /**
     * @param accounts     code ranges, comma separated: {@code 1000-1199,1300}; none for a heading
     * @param sign         1 to show debits positive, -1 credits; 1 when absent
     * @param detail       a line of each account instead of one for them all
     * @param omitZero     left out when it is zero in every column
     * @param noteAccounts what {@code {note}} in the label stands for: these accounts' balance, unsigned
     */
    public record RowInput(@NotBlank @Size(max = 30) String lineCode, @NotBlank @Size(max = 200) String label,
        @NotBlank String kind, @Size(max = 500) String accounts, Integer sign, Boolean detail, Boolean omitZero,
        @Size(max = 500) String noteAccounts) {

        /** The same row, left out when zero in every column. */
        public RowInput omitted() {
            return new RowInput(lineCode, label, kind, accounts, sign, detail, true, noteAccounts);
        }
    }

    public record PublishInput(@NotBlank @Size(max = 10) String layoutCode, @NotBlank String statement,
        @NotBlank @Size(max = 200) String title, @NotEmpty @Valid List<RowInput> rows) {}

    public record PublishOutput(String layoutId, String layoutCode, int version, int rows) {}

    /** @param params the statement's parameters; {@code layoutVersion} is fixed to the latest when not given */
    public record IssueInput(@NotBlank String templateId, Map<String, Object> params) {}

    public record IssueOutput(String runId, String templateId, String layoutCode, int layoutVersion,
        String contentHash) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String LAYOUTS = "layouts";
    static final String READY = "ready";
    static final String ISSUE_INPUT = "issueInput";
    static final String ISSUED = "issued";

    public static final ProcessDefinition<PublishInput, PublishOutput, ProcessContext> PUBLISH_PROCESS =
        ProcessDefinition.define(PUBLISH, 1, PublishInput.class, PublishOutput.class, ProcessContext.class,
            pb -> pb
                .description("Publishes the next version of a statement layout: which accounts make each line.")
                .permissions(FinancePermissions.PERIOD_CLOSE)
                .contextFactory(StatementProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, PublishOutput.class))
                .step("Load its versions", QueryEntities.of(StatementEntities.LAYOUT_DATASET,
                    ctx -> byCode(code(ctx.get(INPUT, PublishInput.class).layoutCode())), LAYOUTS))
                .compute("Publish it", (metadata, ctx) -> publish(ctx)));

    public static final ProcessDefinition<IssueInput, IssueOutput, ProcessContext> ISSUE_PROCESS =
        ProcessDefinition.define(ISSUE, 1, IssueInput.class, IssueOutput.class, ProcessContext.class, pb -> pb
            .description("Issues a financial statement with its layout version, once every account is on a line.")
            .permissions(com.jabiz.runtime.report.ReportPermissions.ISSUE)
            .contextFactory(StatementProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, IssueOutput.class))
            .step("Load the layout's versions", QueryEntities.of(StatementEntities.LAYOUT_DATASET,
                ctx -> byCode(layoutCode(ctx)), LAYOUTS))
            .compute("Fix the version", (metadata, ctx) -> fixVersion(ctx))
            // Each template gives rows only for a layout of its own statement: the others read nothing.
            .step("Run the balance sheet", RunTemplate.of(BALANCE_SHEET, ctx -> paramsFor(ctx, BALANCE_SHEET),
                BALANCE_SHEET))
            .step("Run the income statement", RunTemplate.of(INCOME_STATEMENT,
                ctx -> paramsFor(ctx, INCOME_STATEMENT), INCOME_STATEMENT))
            .step("Run the statement of equity", RunTemplate.of(EQUITY, ctx -> paramsFor(ctx, EQUITY), EQUITY))
            .compute("Check every account is on a line", (metadata, ctx) -> checkMapped(ctx))
            .step("Issue it", CallProcess.when(ctx -> ctx.contains(ISSUE_INPUT), ReportProcesses.ISSUE, 1,
                ctx -> ctx.get(ISSUE_INPUT), ISSUED))
            .compute("Answer", (metadata, ctx) -> answer(ctx)));

    // ---- publish ---------------------------------------------------------------------------------------------------

    static void publish(ProcessContext ctx) {
        PublishInput input = ctx.get(INPUT, PublishInput.class);
        String code = code(input.layoutCode());
        String statement = input.statement().trim().toUpperCase(Locale.ROOT);
        List<String> problems = validate(code, statement, input.rows());
        if (!problems.isEmpty()) {
            ctx.reject(new Violation("rows", INVALID, String.join("; ", problems), Map.of("layoutCode", code)));
            return;
        }
        List<EntityInstance> versions = list(ctx, LAYOUTS);
        if (versions.stream().anyMatch(v -> !statement.equals(v.get("statement")))) {
            ctx.reject(new Violation("statement", INVALID, "Layout " + code + " is a layout of another statement",
                Map.of("layoutCode", code)));
            return;
        }
        int version = versions.stream().mapToInt(v -> v.<BigDecimal>get("version").intValue()).max().orElse(0) + 1;
        ctx.put(OUTPUT, write(ctx, code, statement, input.title().trim(), version, input.rows()));
    }

    /** Writes a layout version and its rows; also the sample layouts of {@code FIN_SETUP}. */
    public static PublishOutput write(ProcessContext ctx, String code, String statement, String title, int version,
        List<RowInput> rows) {
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("layoutCode", code);
        layout.put("version", BigDecimal.valueOf(version));
        layout.put("statement", statement);
        layout.put("title", title);
        layout.put("publishedBy", ctx.request().actorId());
        layout.put("publishedAt", ctx.opTime());
        Object layoutId = ctx.changes().insert(StatementEntities.LAYOUT, layout);
        int seq = 0;
        for (RowInput row : rows) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("layoutId", layoutId);
            values.put("layoutCode", code);
            values.put("version", BigDecimal.valueOf(version));
            values.put("seq", BigDecimal.valueOf(seq += 10));
            values.put("lineCode", row.lineCode().trim().toUpperCase(Locale.ROOT));
            values.put("label", row.label().trim());
            values.put("kind", row.kind().trim().toUpperCase(Locale.ROOT));
            values.put("accounts", blank(row.accounts()) ? null : row.accounts().replace(" ", ""));
            values.put("sign", BigDecimal.valueOf(row.sign() == null ? 1 : row.sign()));
            values.put("detail", Boolean.TRUE.equals(row.detail()));
            values.put("omitZero", Boolean.TRUE.equals(row.omitZero()));
            values.put("noteAccounts", blank(row.noteAccounts()) ? null : row.noteAccounts().replace(" ", ""));
            ctx.changes().insert(StatementEntities.ROW, values);
        }
        return new PublishOutput(String.valueOf(layoutId), code, version, rows.size());
    }

    /** What is wrong with a layout, if anything: one message each. */
    public static List<String> validate(String code, String statement, List<RowInput> rows) {
        List<String> problems = new ArrayList<>();
        if (!CODE.matcher(code).matches()) {
            problems.add("The layout code is 1 to 10 capital letters, digits or _, starting with a letter");
        }
        if (!StatementEntities.STATEMENT_VALUES.contains(statement)) {
            problems.add("The statement is one of " + StatementEntities.STATEMENT_VALUES);
        }
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS) {
            problems.add("A layout has 1 to " + MAX_ROWS + " rows");
            return problems;
        }
        Set<String> codes = new HashSet<>();
        for (RowInput row : rows) {
            String line = row.lineCode() == null ? "" : row.lineCode().trim().toUpperCase(Locale.ROOT);
            String kind = row.kind() == null ? "" : row.kind().trim().toUpperCase(Locale.ROOT);
            if (!LINE_CODE.matcher(line).matches()) {
                problems.add("Line code " + line + " is 1 to 30 capital letters, digits or _, starting with a letter");
            } else if (!codes.add(line)) {
                problems.add("Line code " + line + " appears twice");
            }
            if (!StatementEntities.ROW_KIND_VALUES.contains(kind)) {
                problems.add(line + ": the kind is one of " + StatementEntities.ROW_KIND_VALUES);
                continue;
            }
            if (StatementEntities.HEADING.equals(kind) && !blank(row.accounts())) {
                problems.add(line + ": a heading has no accounts");
            }
            if (!StatementEntities.HEADING.equals(kind)) {
                problems.addAll(ranges(line, "accounts", row.accounts(), true));
            }
            problems.addAll(ranges(line, "noteAccounts", row.noteAccounts(), false));
            if (row.sign() != null && row.sign() != 1 && row.sign() != -1) {
                problems.add(line + ": the sign is 1 or -1");
            }
            if (Boolean.TRUE.equals(row.detail()) && !StatementEntities.LINE.equals(kind)) {
                problems.add(line + ": only a line shows each account");
            }
            if (row.label() != null && row.label().contains("{note}") && blank(row.noteAccounts())) {
                problems.add(line + ": the label has {note} but no note accounts");
            }
        }
        problems.addAll(overlaps(rows));
        return problems;
    }

    /** Two lines taking the same account would show it twice, and the lines would not add up to the totals. */
    private static List<String> overlaps(List<RowInput> rows) {
        List<String[]> spans = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (RowInput row : rows) {
            if (!StatementEntities.LINE.equals(row.kind() == null ? "" : row.kind().trim().toUpperCase(Locale.ROOT))
                || blank(row.accounts())) {
                continue;
            }
            for (String part : row.accounts().replace(" ", "").split(",")) {
                if (!RANGE.matcher(part).matches()) {
                    continue;
                }
                String[] ends = part.split("-");
                String from = ends[0];
                String to = ends.length == 2 ? ends[1] : ends[0];
                for (String[] other : spans) {
                    if (from.compareTo(other[1]) <= 0 && other[0].compareTo(to) <= 0
                        && !other[2].equals(row.lineCode())) {
                        problems.add(row.lineCode() + ": accounts " + part + " are on line " + other[2] + " too");
                    }
                }
                spans.add(new String[] {from, to, row.lineCode()});
            }
        }
        return problems;
    }

    private static List<String> ranges(String line, String field, String value, boolean required) {
        if (blank(value)) {
            return required ? List.of(line + ": " + field + " are needed") : List.of();
        }
        List<String> problems = new ArrayList<>();
        for (String part : value.replace(" ", "").split(",", -1)) {
            if (!RANGE.matcher(part).matches()) {
                problems.add(line + ": " + field + " range '" + part + "' is a code or two codes joined by -");
                continue;
            }
            String[] ends = part.split("-");
            if (ends.length == 2 && ends[0].compareTo(ends[1]) > 0) {
                problems.add(line + ": " + field + " range " + part + " ends before it starts");
            }
        }
        return problems;
    }

    // ---- issue -----------------------------------------------------------------------------------------------------

    static void fixVersion(ProcessContext ctx) {
        IssueInput input = ctx.get(INPUT, IssueInput.class);
        if (!DEFAULT_LAYOUTS.containsKey(input.templateId())) {
            ctx.reject(new Violation("templateId", NOT_A_STATEMENT, input.templateId() + " is not one of the "
                + "statements " + DEFAULT_LAYOUTS.keySet(), Map.of("templateId", input.templateId())));
            return;
        }
        String code = layoutCode(ctx);
        Object asked = input.params() == null ? null : input.params().get("layoutVersion");
        List<EntityInstance> versions = list(ctx, LAYOUTS).stream()
            .filter(v -> STATEMENT_OF.get(input.templateId()).equals(v.get("statement"))).toList();
        Map<String, Object> params = new LinkedHashMap<>();
        // A form sends empty fields as null: they are not given.
        (input.params() == null ? Map.<String, Object>of() : input.params()).forEach((k, v) -> {
            if (v != null) {
                params.put(k, v);
            }
        });
        Object knownAt = params.get("knownAt");
        java.time.Instant known = knownAt == null ? null : instant(knownAt);
        if (knownAt != null && known == null) {
            ctx.reject(new Violation("params", NO_LAYOUT, knownAt + " is not a time", Map.of("layoutCode", code)));
            return;
        }
        // As known at a time, the latest version published by then: the statement as it could be issued then.
        Integer version;
        try {
            version = asked == null
                ? versions.stream().filter(v -> known == null || !known.isBefore(instant(v.get("publishedAt"))))
                    .map(v -> v.<BigDecimal>get("version").intValue()).max(Integer::compare).orElse(null)
                : Integer.valueOf(new BigDecimal(String.valueOf(asked)).intValueExact());
        } catch (NumberFormatException | ArithmeticException e) {
            version = null;
        }
        Integer fixed = version;
        EntityInstance layout = fixed == null ? null : versions.stream()
            .filter(v -> v.<BigDecimal>get("version").intValue() == fixed).findFirst().orElse(null);
        if (layout == null) {
            ctx.reject(new Violation("params", NO_LAYOUT, "There is no layout " + code + " of this statement"
                + (asked == null ? "" : " in version " + asked) + (known == null ? "" : " as known at " + knownAt),
                Map.of("layoutCode", code)));
            return;
        }
        // As known before the layout version existed, the statement would read no layout at all.
        if (known != null && known.isBefore(instant(layout.get("publishedAt")))) {
            ctx.reject(new Violation("params", NO_LAYOUT, "Layout " + code + " version " + fixed
                + " was published after " + knownAt, Map.of("layoutCode", code)));
            return;
        }
        params.put("layout", code);
        params.put("layoutVersion", BigDecimal.valueOf(fixed));
        ctx.put(READY, Map.copyOf(params));
    }

    /** A time given as an instant or as text (with or without seconds, as the template reads it); null if none. */
    private static java.time.Instant instant(Object value) {
        if (value instanceof java.time.Instant i) {
            return i;
        }
        if (value instanceof java.time.OffsetDateTime o) {
            return o.toInstant();
        }
        try {
            return java.time.OffsetDateTime.parse(String.valueOf(value)).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** The statement's parameters for its own template; for the others a layout that is none, so they read nothing. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> paramsFor(ProcessContext ctx, String templateId) {
        IssueInput input = ctx.get(INPUT, IssueInput.class);
        if (!ctx.contains(READY)) {
            return skipped(templateId);
        }
        if (templateId.equals(input.templateId())) {
            return (Map<String, Object>) ctx.get(READY);
        }
        return skipped(templateId);
    }

    /** The required parameters of a template, with no layout: it gives no rows. */
    private static Map<String, Object> skipped(String templateId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("layout", "-");
        switch (templateId) {
            case BALANCE_SHEET -> params.put("asOf", "2000-01-01");
            case INCOME_STATEMENT, EQUITY -> params.put("through", "2000-01-01");
            default -> { }
        }
        return params;
    }

    @SuppressWarnings("unchecked")
    static void checkMapped(ProcessContext ctx) {
        if (!ctx.contains(READY)) {
            return;
        }
        IssueInput input = ctx.get(INPUT, IssueInput.class);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(input.templateId());
        if (rows == null || rows.stream().noneMatch(r -> StatementEntities.LINE.equals(r.get("kind"))
            || StatementEntities.TOTAL.equals(r.get("kind")))) {
            ctx.reject(new Violation("params", EMPTY, "The statement has no lines to issue",
                Map.of("layoutCode", layoutCode(ctx))));
            return;
        }
        if (rows.size() >= PeriodBalances.CAP) {
            // A cut read could leave out the unmapped rows at its end: never issue what was not read whole.
            ctx.reject(new Violation("params", TOO_LONG, "The statement has " + PeriodBalances.CAP
                + " rows or more", Map.of("layoutCode", layoutCode(ctx), "max", PeriodBalances.CAP - 1)));
            return;
        }
        List<String> unmapped = rows.stream()
            .filter(r -> UNMAPPED_KIND.equals(r.get("kind"))).map(r -> String.valueOf(r.get("label"))).toList();
        if (!unmapped.isEmpty()) {
            ctx.reject(new Violation("params", UNMAPPED, "Accounts on no line of layout " + layoutCode(ctx) + ": "
                + String.join(", ", unmapped), Map.of("layoutCode", layoutCode(ctx), "accounts",
                String.join(", ", unmapped))));
            return;
        }
        ctx.put(ISSUE_INPUT, new ReportProcesses.IssueInput(input.templateId(),
            (Map<String, Object>) ctx.get(READY), null, null, null));
    }

    @SuppressWarnings("unchecked")
    static void answer(ProcessContext ctx) {
        if (!ctx.contains(ISSUED)) {
            return;
        }
        IssueInput input = ctx.get(INPUT, IssueInput.class);
        ReportProcesses.IssueOutput issued = ctx.get(ISSUED, ReportProcesses.IssueOutput.class);
        Map<String, Object> params = (Map<String, Object>) ctx.get(READY);
        ctx.put(OUTPUT, new IssueOutput(issued.runId(), input.templateId(), (String) params.get("layout"),
            ((BigDecimal) params.get("layoutVersion")).intValue(), issued.contentHash()));
    }

    private static String layoutCode(ProcessContext ctx) {
        IssueInput input = ctx.get(INPUT, IssueInput.class);
        Object asked = input.params() == null ? null : input.params().get("layout");
        return asked == null ? DEFAULT_LAYOUTS.getOrDefault(input.templateId(), "-") : code(String.valueOf(asked));
    }

    // ---- the sample layouts (FIN-EXP-04, 05, 07) ------------------------------------------------------------------

    private static RowInput heading(String code, String label) {
        return new RowInput(code, label, StatementEntities.HEADING, null, 1, false, false, null);
    }

    private static RowInput line(String code, String label, String accounts, int sign) {
        return new RowInput(code, label, StatementEntities.LINE, accounts, sign, false, false, null);
    }

    private static RowInput total(String code, String label, String accounts, int sign) {
        return new RowInput(code, label, StatementEntities.TOTAL, accounts, sign, false, false, null);
    }

    /** The classified balance sheet of FIN-EXP-05; retained earnings with the year's income not yet closed. */
    public static final List<RowInput> SAMPLE_BALANCE_SHEET = List.of(
        heading("ASSETS", "Assets"),
        line("CASH", "Cash and cash equivalents", "1000-1199", 1),
        new RowInput("RECEIVABLES", "Accounts receivable, net of allowance of {note}", StatementEntities.LINE,
            "1200-1299", 1, false, false, "1210"),
        line("INVENTORY", "Inventory", "1400-1499", 1).omitted(),
        line("PREPAID", "Prepaid expenses", "1300-1399", 1),
        total("CURRENT_ASSETS", "Total current assets", "1000-1499", 1),
        line("PPE_COST", "Property and equipment, at cost", "1500-1589", 1),
        line("PPE_DEPRECIATION", "Less: accumulated depreciation", "1590-1599", 1),
        total("PPE_NET", "Property and equipment, net", "1500-1599", 1),
        line("OTHER_ASSETS", "Other assets", "1600-1999", 1).omitted(),
        total("TOTAL_ASSETS", "Total assets", "1000-1999", 1),
        heading("LIABILITIES", "Liabilities"),
        line("PAYABLES", "Accounts payable", "2000-2099", -1),
        line("ACCRUED", "Accrued liabilities", "2100-2149", -1),
        line("PAYROLL", "Payroll liabilities", "2150-2199", -1),
        line("SALES_TAX", "Sales tax payable", "2200-2299", -1),
        line("INCOME_TAX_PAYABLE", "Income tax payable", "2400-2499", -1),
        line("CREDIT_LINE", "Line of credit", "2300-2399", -1),
        line("OTHER_LIABILITIES", "Other liabilities", "2500-2999", -1).omitted(),
        total("TOTAL_LIABILITIES", "Total liabilities", "2000-2999", -1),
        heading("EQUITY", "Stockholders' equity"),
        line("COMMON_STOCK", "Common stock", "3000-3099", -1),
        line("APIC", "Additional paid-in capital", "3100-3199", -1),
        line("RETAINED", "Retained earnings", "3200-3999,4000-9999", -1),
        total("TOTAL_EQUITY", "Total stockholders' equity", "3000-9999", -1),
        total("TOTAL_LIABILITIES_EQUITY", "Total liabilities and stockholders' equity", "2000-9999", -1));

    /** The multi-step income statement of FIN-EXP-04: income positive, expenses in parentheses. */
    public static final List<RowInput> SAMPLE_INCOME_STATEMENT = List.of(
        line("PRODUCT_SALES", "Product sales", "4000-4099", -1),
        line("SERVICE_REVENUE", "Service revenue", "4100-4899", -1),
        line("RETURNS", "Less: sales returns and allowances", "4900-4999", -1),
        total("NET_REVENUE", "Net revenue", "4000-4999", -1),
        line("COGS", "Cost of goods sold", "5000-5999", -1),
        total("GROSS_PROFIT", "Gross profit", "4000-5999", -1),
        new RowInput("OPERATING_EXPENSES", "Operating expenses", StatementEntities.LINE, "6000-6999", -1, true,
            false, null),
        total("TOTAL_OPERATING_EXPENSES", "Total operating expenses", "6000-6999", -1),
        total("OPERATING_INCOME", "Operating income", "4000-6999", -1),
        line("INTEREST_EXPENSE", "Interest expense", "7100-7199", -1),
        line("INTEREST_INCOME", "Interest income", "7300-7399", -1),
        line("REALIZED_FX", "Realized foreign exchange gain (loss)", "7200-7209", -1).omitted(),
        line("UNREALIZED_FX", "Unrealized foreign exchange gain (loss)", "7210-7299", -1).omitted(),
        line("OTHER_INCOME", "Other income (expense)", "7400-7999", -1).omitted(),
        total("PRETAX_INCOME", "Income before income taxes", "4000-7999", -1),
        line("INCOME_TAX", "Income tax expense", "8000-8999", -1),
        total("NET_INCOME", "Net income", "4000-8999", -1));

    /** The statement of stockholders' equity of FIN-EXP-07: one row per component. */
    public static final List<RowInput> SAMPLE_EQUITY = List.of(
        line("COMMON_STOCK", "Common stock", "3000-3099", -1),
        line("APIC", "Additional paid-in capital", "3100-3199", -1),
        line("RETAINED", "Retained earnings", "3200-3999,4000-9999", -1),
        total("TOTAL", "Total", "3000-9999", -1));

    /** The sample layouts by code: statement, title and rows. */
    public record Sample(String code, String statement, String title, List<RowInput> rows) {}

    public static final List<Sample> SAMPLES = List.of(
        new Sample("BS", StatementEntities.BALANCE_SHEET, "Balance sheet", SAMPLE_BALANCE_SHEET),
        new Sample("IS", StatementEntities.INCOME_STATEMENT, "Income statement", SAMPLE_INCOME_STATEMENT),
        new Sample("EQ", StatementEntities.EQUITY, "Statement of stockholders' equity", SAMPLE_EQUITY));

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static EntityQuery byCode(String code) {
        return EntityQuery.builder().where(new QueryPredicate.Eq("layoutCode", code)).limit(1000).build();
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> list = (List<EntityInstance>) ctx.get(key);
        return list == null ? List.of() : list;
    }

    private static String code(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ProcessContext withInput(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private StatementProcesses() {}
}

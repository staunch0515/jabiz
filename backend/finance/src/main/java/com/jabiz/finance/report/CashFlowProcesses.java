package com.jabiz.finance.report;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.report.ReportPermissions;
import com.jabiz.runtime.report.ReportProcesses;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The report settings and the issue of the statement of cash flows (FIN-RP-004, RP-012; ROADMAP F9 decisions D5,
 * D11):
 * <ul>
 *   <li>{@code FIN_REPORT_SETTINGS_SET}: the accounts behind interest and income taxes paid and those of the note
 *       schedules, each as code ranges;</li>
 *   <li>{@code FIN_CASH_FLOW_ISSUE}: issues {@code finance.report.cash_flow} through {@code REPORT_ISSUE} once the
 *       statement explains the whole change in cash (no unclassified account, an unexplained difference of zero).</li>
 * </ul>
 */
public final class CashFlowProcesses {

    public static final String SETTINGS_SET = "FIN_REPORT_SETTINGS_SET";
    public static final String ISSUE = "FIN_CASH_FLOW_ISSUE";
    public static final String CASH_FLOW = "finance.report.cash_flow";
    public static final String NOTE = "finance.report.note_rollforward";

    public static final String SETTINGS_INVALID = "FIN_REPORT_SETTINGS_INVALID";
    /** Accounts with a change and no cash flow class, or a change in cash the statement does not explain. */
    public static final String UNEXPLAINED = "FIN_CASH_FLOW_UNEXPLAINED";

    /** Every field code ranges, comma separated ({@code 2100-2199,2150}); none leaves it unset. */
    public record SettingsInput(@Size(max = 500) String interestAccounts,
        @Size(max = 500) String interestPayableAccounts, @Size(max = 500) String incomeTaxAccounts,
        @Size(max = 500) String incomeTaxPayableAccounts, @Size(max = 500) String receivablesAccounts,
        @Size(max = 500) String accruedAccounts, @Size(max = 500) String debtAccounts) {}

    public record SettingsOutput(String settingsId) {}

    /** @param params the statement's parameters: {@code through} required, {@code from}, {@code knownAt} … */
    public record IssueInput(@NotNull Map<String, Object> params) {}

    public record IssueOutput(String runId, String contentHash, int rows) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String SETTINGS = "settings";
    static final String ROWS = "rows";
    static final String ISSUE_INPUT = "issueInput";
    static final String ISSUED = "issued";

    public static final ProcessDefinition<SettingsInput, SettingsOutput, ProcessContext> SETTINGS_PROCESS =
        ProcessDefinition.define(SETTINGS_SET, 1, SettingsInput.class, SettingsOutput.class, ProcessContext.class,
            pb -> pb
                .description("Sets the accounts of interest and income taxes paid and of the note schedules.")
                .permissions(FinancePermissions.PERIOD_CLOSE)
                .contextFactory(CashFlowProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, SettingsOutput.class))
                .step("Load the settings", QueryEntities.of(StatementEntities.SETTINGS_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("settingsKey",
                        StatementEntities.SETTINGS_KEY)).limit(1).build(), SETTINGS))
                .compute("Set them", (metadata, ctx) -> setSettings(ctx)));

    public static final ProcessDefinition<IssueInput, IssueOutput, ProcessContext> ISSUE_PROCESS =
        ProcessDefinition.define(ISSUE, 1, IssueInput.class, IssueOutput.class, ProcessContext.class, pb -> pb
            .description("Issues the statement of cash flows once it explains the whole change in cash.")
            .permissions(ReportPermissions.ISSUE)
            .contextFactory(CashFlowProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, IssueOutput.class))
            .step("Run the statement", RunTemplate.of(CASH_FLOW, CashFlowProcesses::params, ROWS))
            .compute("Check it explains the change in cash", (metadata, ctx) -> check(ctx))
            .step("Issue it", CallProcess.when(ctx -> ctx.contains(ISSUE_INPUT), ReportProcesses.ISSUE, 1,
                ctx -> ctx.get(ISSUE_INPUT), ISSUED))
            .compute("Answer", (metadata, ctx) -> answer(ctx)));

    // ---- settings --------------------------------------------------------------------------------------------------

    static void setSettings(ProcessContext ctx) {
        SettingsInput input = ctx.get(INPUT, SettingsInput.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("interestAccounts", input.interestAccounts());
        values.put("interestPayableAccounts", input.interestPayableAccounts());
        values.put("incomeTaxAccounts", input.incomeTaxAccounts());
        values.put("incomeTaxPayableAccounts", input.incomeTaxPayableAccounts());
        values.put("receivablesAccounts", input.receivablesAccounts());
        values.put("accruedAccounts", input.accruedAccounts());
        values.put("debtAccounts", input.debtAccounts());
        List<String> problems = new ArrayList<>();
        values.replaceAll((field, value) -> {
            String ranges = value == null || ((String) value).isBlank() ? null
                : ((String) value).replace(" ", "").toUpperCase(java.util.Locale.ROOT);
            problems.addAll(StatementProcesses.ranges(field, "accounts", ranges, false));
            return ranges;
        });
        if (!problems.isEmpty()) {
            ctx.reject(new Violation("settings", SETTINGS_INVALID, String.join("; ", problems), Map.of()));
            return;
        }
        EntityInstance settings = first(ctx, SETTINGS);
        String id;
        if (settings == null) {
            Map<String, Object> inserted = new LinkedHashMap<>(values);
            inserted.put("settingsKey", StatementEntities.SETTINGS_KEY);
            id = String.valueOf(ctx.changes().insert(StatementEntities.SETTINGS, inserted));
        } else {
            ctx.changes().update(StatementEntities.SETTINGS, settings.id(), settings.version(), values);
            id = String.valueOf(settings.id());
        }
        ctx.put(OUTPUT, new SettingsOutput(id));
    }

    // ---- issue -----------------------------------------------------------------------------------------------------

    /** The parameters given, those a form sent empty left out. */
    static Map<String, Object> params(ProcessContext ctx) {
        Map<String, Object> params = new LinkedHashMap<>();
        ctx.get(INPUT, IssueInput.class).params().forEach((k, v) -> {
            if (v != null) {
                params.put(k, v);
            }
        });
        return params;
    }

    @SuppressWarnings("unchecked")
    static void check(ProcessContext ctx) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(ROWS);
        List<String> unclassified = rows.stream().filter(r -> "UNCLASSIFIED".equals(r.get("kind")))
            .map(r -> String.valueOf(r.get("label"))).toList();
        BigDecimal difference = rows.stream().filter(r -> "UNEXPLAINED".equals(r.get("lineCode")))
            .map(r -> (BigDecimal) r.get("amount")).findFirst().orElse(BigDecimal.ZERO);
        if (!unclassified.isEmpty() || difference.signum() != 0) {
            String accounts = unclassified.isEmpty() ? "-" : String.join(", ", unclassified);
            ctx.reject(new Violation("params", UNEXPLAINED, "The statement leaves " + difference.toPlainString()
                + " of the change in cash unexplained; accounts with no cash flow class: " + accounts,
                Map.of("difference", difference.toPlainString(), "accounts", accounts)));
            return;
        }
        ctx.put(ISSUE_INPUT, new ReportProcesses.IssueInput(CASH_FLOW, params(ctx), null, null, null));
    }

    static void answer(ProcessContext ctx) {
        if (!ctx.contains(ISSUED)) {
            return;
        }
        ReportProcesses.IssueOutput issued = ctx.get(ISSUED, ReportProcesses.IssueOutput.class);
        ctx.put(OUTPUT, new IssueOutput(issued.runId(), issued.contentHash(), issued.rowCount()));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> list = (List<EntityInstance>) ctx.get(key);
        return list == null || list.isEmpty() ? null : list.getFirst();
    }

    private static ProcessContext withInput(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private CashFlowProcesses() {}
}

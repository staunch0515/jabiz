package com.jabiz.finance.gl;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jabiz.finance.gl.AccountProcesses.list;

/**
 * The standard chart (FIN-GL-002): {@code FIN_COA_TEMPLATE_PREVIEW} shows the proposed accounts without saving
 * anything; {@code FIN_COA_TEMPLATE_APPLY} copies them into books that have no accounts yet, leaving out the codes the
 * controller excluded (and the accounts under them). Afterwards the chart is adapted account by account.
 */
public final class ChartTemplateProcesses {

    public static final String PREVIEW = "FIN_COA_TEMPLATE_PREVIEW";
    public static final String APPLY = "FIN_COA_TEMPLATE_APPLY";

    public static final String CHART_NOT_EMPTY = "FIN_CHART_NOT_EMPTY";

    /** @param excludeCodes codes not to copy; the accounts that roll up into them are left out too */
    public record ApplyInput(List<String> excludeCodes) {}

    public record PreviewInput() {}

    public record TemplateOutput(List<ChartTemplate.Line> accounts) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String EXISTING = "existing";

    public static final ProcessDefinition<PreviewInput, TemplateOutput, ProcessContext> PREVIEW_PROCESS =
        ProcessDefinition.define(PREVIEW, 1, PreviewInput.class, TemplateOutput.class, ProcessContext.class,
            pb -> pb
                .description("Shows the standard US chart of accounts for review; saves nothing.")
                .permissions(FinancePermissions.ACCOUNT_MAINTAIN)
                .contextFactory((start, input) -> new ProcessContext(start))
                .outputMapper(ctx -> new TemplateOutput(ChartTemplate.lines()))
                .compute("Propose the chart", (metadata, ctx) -> { }));

    public static final ProcessDefinition<ApplyInput, TemplateOutput, ProcessContext> APPLY_PROCESS =
        ProcessDefinition.define(APPLY, 1, ApplyInput.class, TemplateOutput.class, ProcessContext.class, pb -> pb
            .description("Copies the standard US chart of accounts into books without accounts.")
            .permissions(FinancePermissions.ACCOUNT_MAINTAIN)
            .contextFactory(AccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, TemplateOutput.class))
            .step("Look for accounts", QueryEntities.of(LedgerEntities.ACCOUNT_DATASET,
                ctx -> EntityQuery.builder().limit(1).build(), EXISTING))
            .compute("Copy the chart", (metadata, ctx) -> apply(ctx)));

    static void apply(ProcessContext ctx) {
        if (!list(ctx, EXISTING).isEmpty()) {
            ctx.reject(new Violation(null, CHART_NOT_EMPTY,
                "The books have accounts already; the template only starts new books", Map.of()));
            return;
        }
        ApplyInput input = ctx.get(INPUT, ApplyInput.class);
        Set<String> excluded = new HashSet<>(input == null || input.excludeCodes() == null ? List.of()
            : input.excludeCodes().stream().filter(java.util.Objects::nonNull).map(String::trim).toList());
        Map<String, Object> ledgerIds = new HashMap<>();
        List<ChartTemplate.Line> copied = new java.util.ArrayList<>();
        for (ChartTemplate.Line line : ChartTemplate.lines()) {
            if (excluded.contains(line.accountCode())
                || line.parentCode() != null && excluded.contains(line.parentCode())) {
                excluded.add(line.accountCode());
                continue;
            }
            Map<String, Object> ledger = new LinkedHashMap<>();
            ledger.put("accountCode", line.accountCode());
            ledger.put("accountName", line.accountName());
            ledger.put("accountType", AccountTypes.ledgerType(line.financialType(), line.normalBalance()));
            ledger.put("enabled", true);
            ledger.put("parentId", line.parentCode() == null ? null : ledgerIds.get(line.parentCode()));
            ledger.put("summary", line.summary());
            Object ledgerId = ctx.changes().insert(LedgerEntities.ACCOUNT, ledger);
            ledgerIds.put(line.accountCode(), ledgerId);

            Map<String, Object> fin = new LinkedHashMap<>();
            fin.put("accountCode", line.accountCode());
            fin.put("ledgerAccountId", ledgerId);
            fin.put("financialType", line.financialType());
            fin.put("normalBalance", line.normalBalance());
            fin.put("statementLine", line.statementLine());
            fin.put("cashFlowClass", line.cashFlowClass());
            fin.put("controlClass", line.controlClass());
            fin.put("clearing", line.clearing());
            fin.put("requiredDimension", null);
            ctx.changes().insert(GlEntities.ACCOUNT, fin);
            copied.add(line);
        }
        ctx.put(OUTPUT, new TemplateOutput(List.copyOf(copied)));
    }

    private ChartTemplateProcesses() {}
}

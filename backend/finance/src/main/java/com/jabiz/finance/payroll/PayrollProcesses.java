package com.jabiz.finance.payroll;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.JournalValidator;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code FIN_PAYROLL_IMPORT} (FIN-DI-004): one payroll run of the provider's file as one summary journal entry
 * numbered as the run ("PAYROLL-2601", source {@code PAYROLL}), its lines from the mappings ({@link PayrollLines}),
 * submitted at once unless asked not to: the approval rules apply as to any entry. A run is imported once. Bank
 * accounts among the mapped accounts post under the mapping's standing exception, recorded on the entry; any other
 * control account is refused.
 */
public final class PayrollProcesses {

    public static final String IMPORT_RUN = "FIN_PAYROLL_IMPORT";

    public static final String UNMAPPED = "FIN_PAYROLL_UNMAPPED";
    public static final String CONTROL_ACCOUNT = "FIN_PAYROLL_CONTROL_ACCOUNT";
    public static final String IMPORTED_ALREADY = "FIN_PAYROLL_IMPORTED_ALREADY";

    /** Who stands for the exception of a payroll entry to a bank account: the mappings the controller keeps. */
    static final String MAPPING_EXCEPTION = "payroll mapping";

    public record ProviderLineInput(@NotBlank @Size(max = 40) String code, @Size(max = 20) String department,
        @NotNull BigDecimal amount) {}

    /**
     * @param run    the provider's run, the entry's number: capitals, digits and hyphens
     * @param submit whether to submit it at once; yes when absent
     */
    public record PayrollInput(@NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9-]{0,29}") String run,
        @NotNull LocalDate payDate, @NotBlank @Size(max = 500) String description,
        @NotNull @Size(min = 1, max = 2000) List<@Valid @NotNull ProviderLineInput> lines, Boolean submit) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String FOUND = "found";
    static final String MAPPINGS = "mappings";
    static final String ACCOUNTS = "accounts";
    static final String SUBMIT_INPUT = "submitInput";
    static final String SUBMITTED = "submitted";

    public static final ProcessDefinition<PayrollInput, JournalProcesses.JournalOutput, ProcessContext>
        IMPORT_PROCESS = ProcessDefinition.define(IMPORT_RUN, 1, PayrollInput.class,
            JournalProcesses.JournalOutput.class, ProcessContext.class, pb -> pb
                .description("Books a payroll run of the provider's file as one summary journal entry.")
                .permissions(FinancePermissions.PAYROLL_IMPORT, FinancePermissions.JOURNAL_PREPARE)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.contains(SUBMITTED)
                    ? ctx.get(SUBMITTED, JournalProcesses.JournalOutput.class)
                    : ctx.get(OUTPUT, JournalProcesses.JournalOutput.class))
                .step("Look for the run", QueryEntities.of(JournalEntities.JOURNAL_DATASET,
                    ctx -> JournalProcesses.byExternalRef(input(ctx).run()), FOUND))
                .step("Load the mappings", QueryEntities.of(PayrollEntities.MAPPING_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("providerCode", new ArrayList<>(codes(ctx))),
                        new QueryPredicate.Eq("active", true)))).limit(Math.max(1, codes(ctx).size())).build(),
                    MAPPINGS))
                .step("Load the accounts", QueryEntities.of(GlEntities.ACCOUNT_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("accountCode",
                        new ArrayList<>(accountCodes(ctx)))).limit(Math.max(1, accountCodes(ctx).size())).build(),
                    ACCOUNTS))
                .compute("Make the entry", (metadata, ctx) -> make(ctx))
                .step("Save", SaveChanges.now())
                .step("Submit it", CallProcess.when(ctx -> ctx.contains(SUBMIT_INPUT), JournalProcesses.SUBMIT, 1,
                    ctx -> ctx.get(SUBMIT_INPUT), SUBMITTED)));

    static void make(ProcessContext ctx) {
        PayrollInput input = input(ctx);
        if (!list(ctx, FOUND).isEmpty()) {
            ctx.reject(new Violation("run", IMPORTED_ALREADY, "Payroll run " + input.run() + " was imported already",
                Map.of("run", input.run())));
            return;
        }
        PayrollLines.Result result = PayrollLines.lines(input.lines().stream()
            .map(l -> new PayrollLines.ProviderLine(l.code(), l.department(), l.amount())).toList(), mappings(ctx));
        for (String code : result.unmapped()) {
            ctx.reject(new Violation("lines", UNMAPPED, "Provider code " + code + " has no active mapping",
                Map.of("code", code)));
        }
        Map<String, String> controlClasses = new LinkedHashMap<>();
        for (EntityInstance account : list(ctx, ACCOUNTS)) {
            if (account.get("controlClass") != null) {
                controlClasses.put(account.get("accountCode"), account.get("controlClass"));
            }
        }
        Set<String> banks = new LinkedHashSet<>();
        for (JournalValidator.Line line : result.lines()) {
            String controlClass = controlClasses.get(line.accountCode());
            if ("BANK".equals(controlClass)) {
                banks.add(line.accountCode());
            } else if (controlClass != null) {
                ctx.reject(new Violation("lines", CONTROL_ACCOUNT, "Account " + line.accountCode() + " is a "
                    + controlClass + " control account: payroll posts only to bank accounts among them",
                    Map.of("accountCode", line.accountCode(), "controlClass", controlClass)));
            }
        }
        if (ctx.hasViolations()) {
            return;
        }
        String reason = banks.isEmpty() ? null : "Payroll run " + input.run() + " pays out of " + String.join(", ",
            banks) + " as the payroll mapping says";
        Object id = JournalProcesses.insertDraft(ctx, input.payDate(), input.payDate(), input.description(),
            JournalEntities.PAYROLL, input.run(), input.run(), result.lines(),
            reason == null ? null : MAPPING_EXCEPTION, reason);
        JournalValidator.Totals totals = JournalValidator.totals(result.lines());
        ctx.put(OUTPUT, new JournalProcesses.JournalOutput(String.valueOf(id), input.run(), JournalEntities.DRAFT,
            totals.debit(), totals.credit(), null, null, null, null));
        if (!Boolean.FALSE.equals(input.submit())) {
            ctx.put(SUBMIT_INPUT, new JournalProcesses.JournalId(UUID.fromString(String.valueOf(id))));
        }
    }

    private static Map<String, PayrollLines.Mapping> mappings(ProcessContext ctx) {
        Map<String, PayrollLines.Mapping> mappings = new LinkedHashMap<>();
        for (EntityInstance m : list(ctx, MAPPINGS)) {
            mappings.put(m.get("providerCode"), new PayrollLines.Mapping(m.get("providerCode"), m.get("accountCode"),
                m.get("side"), m.get("department")));
        }
        return mappings;
    }

    private static Set<String> codes(ProcessContext ctx) {
        Set<String> codes = new LinkedHashSet<>();
        for (ProviderLineInput line : input(ctx).lines()) {
            codes.add(line.code().trim());
        }
        return codes;
    }

    private static Set<String> accountCodes(ProcessContext ctx) {
        Set<String> codes = new LinkedHashSet<>();
        for (EntityInstance m : list(ctx, MAPPINGS)) {
            codes.add(m.get("accountCode"));
        }
        return codes;
    }

    private static PayrollInput input(ProcessContext ctx) {
        return ctx.get(INPUT, PayrollInput.class);
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    private PayrollProcesses() {}
}

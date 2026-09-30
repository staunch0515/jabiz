package com.jabiz.finance.gl;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The chart of accounts (FIN-GL-001, 003, 004; docs/finance/00-design.md section 6.1). An account is two entities that
 * change together: the platform's {@code LedgerAccount} (code, name, ledger type, parent, summary, active) and
 * {@link GlEntities#ACCOUNT} (the finance classification). Both are temporal, so the account history shows every
 * change with who and when.
 * <ul>
 *   <li>{@code FIN_ACCOUNT_CREATE}: a new account; a code is used once.</li>
 *   <li>{@code FIN_ACCOUNT_UPDATE}: name, normal balance, statement line and the other classifications, parent,
 *       summary; the code and the account type never change.</li>
 *   <li>{@code FIN_ACCOUNT_DEACTIVATE} / {@code FIN_ACCOUNT_REACTIVATE}: an inactive account takes no postings (the
 *       ledger refuses them) and stays in reports.</li>
 *   <li>{@code FIN_ACCOUNT_DELETE}: only an account that was never posted to and has no sub-accounts; otherwise the
 *       caller is told to deactivate it.</li>
 * </ul>
 */
public final class AccountProcesses {

    public static final String CREATE = "FIN_ACCOUNT_CREATE";
    public static final String UPDATE = "FIN_ACCOUNT_UPDATE";
    public static final String DEACTIVATE = "FIN_ACCOUNT_DEACTIVATE";
    public static final String REACTIVATE = "FIN_ACCOUNT_REACTIVATE";
    public static final String DELETE = "FIN_ACCOUNT_DELETE";

    public static final String CODE_TAKEN = "FIN_ACCOUNT_CODE_TAKEN";
    public static final String NOT_FOUND = "FIN_ACCOUNT_NOT_FOUND";
    public static final String PARENT_NOT_FOUND = "FIN_ACCOUNT_PARENT_NOT_FOUND";
    public static final String HAS_POSTINGS = "FIN_ACCOUNT_HAS_POSTINGS";
    public static final String HAS_CHILDREN = "FIN_ACCOUNT_HAS_CHILDREN";
    public static final String INVALID_VALUE = "FIN_ACCOUNT_INVALID_VALUE";

    /**
     * @param financialType     one of {@link GlEntities#FINANCIAL_TYPE_VALUES}
     * @param normalBalance     {@code DEBIT} or {@code CREDIT}
     * @param cashFlowClass     optional, one of {@link GlEntities#CASH_FLOW_VALUES}
     * @param controlClass      optional, one of {@link GlEntities#CONTROL_CLASS_VALUES}: only its subledger posts
     * @param clearing          whether it must be at zero at period end; false when absent
     * @param requiredDimension optional: lines on it must name this dimension
     * @param parentCode        optional summary account it rolls up into
     * @param summary           whether it groups other accounts and takes no postings; false when absent
     */
    public record AccountInput(@NotBlank String accountCode, @NotBlank String accountName,
        @NotBlank String financialType, @NotBlank String normalBalance, @NotBlank String statementLine,
        String cashFlowClass, String controlClass, Boolean clearing, String requiredDimension, String parentCode,
        Boolean summary) {}

    /**
     * Only what is given changes; for the optional classifications and the parent an empty text clears them.
     */
    public record AccountChange(@NotBlank String accountCode, String accountName, String normalBalance,
        String statementLine, String cashFlowClass, String controlClass, Boolean clearing, String requiredDimension,
        String parentCode, Boolean summary) {}

    public record AccountCode(@NotBlank String accountCode) {}

    /** @param changed false when nothing had to change */
    public record AccountOutput(String finAccountId, String ledgerAccountId, String accountCode, boolean active,
        boolean changed) {}

    // Context keys.
    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String FIN_ACCOUNTS = "finAccounts";
    static final String LEDGER_ACCOUNTS = "ledgerAccounts";
    static final String ENTRIES = "entries";
    static final String CHILDREN = "children";

    public static final ProcessDefinition<AccountInput, AccountOutput, ProcessContext> CREATE_PROCESS =
        ProcessDefinition.define(CREATE, 1, AccountInput.class, AccountOutput.class, ProcessContext.class, pb -> pb
            .description("Opens an account of the chart of accounts.")
            .permissions(FinancePermissions.ACCOUNT_MAINTAIN)
            .contextFactory(AccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, AccountOutput.class))
            .step("Load the account and its parent", QueryEntities.of(LedgerEntities.ACCOUNT_DATASET,
                ctx -> codes(input(ctx, AccountInput.class).accountCode(),
                    input(ctx, AccountInput.class).parentCode()), LEDGER_ACCOUNTS))
            .step("Load the finance account", QueryEntities.of(GlEntities.ACCOUNT_DATASET,
                ctx -> codes(input(ctx, AccountInput.class).accountCode()), FIN_ACCOUNTS))
            .compute("Open the account", (metadata, ctx) -> create(ctx)));

    public static final ProcessDefinition<AccountChange, AccountOutput, ProcessContext> UPDATE_PROCESS =
        ProcessDefinition.define(UPDATE, 1, AccountChange.class, AccountOutput.class, ProcessContext.class, pb -> pb
            .description("Changes the name, classification or parent of an account.")
            .permissions(FinancePermissions.ACCOUNT_MAINTAIN)
            .contextFactory(AccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, AccountOutput.class))
            .step("Load the account and its parent", QueryEntities.of(LedgerEntities.ACCOUNT_DATASET,
                ctx -> codes(input(ctx, AccountChange.class).accountCode(),
                    input(ctx, AccountChange.class).parentCode()), LEDGER_ACCOUNTS))
            .step("Load the finance account", QueryEntities.of(GlEntities.ACCOUNT_DATASET,
                ctx -> codes(input(ctx, AccountChange.class).accountCode()), FIN_ACCOUNTS))
            .compute("Change the account", (metadata, ctx) -> update(ctx)));

    public static final ProcessDefinition<AccountCode, AccountOutput, ProcessContext> DEACTIVATE_PROCESS =
        activation(DEACTIVATE, false, "Deactivates an account: it takes no new postings and stays in reports.");

    public static final ProcessDefinition<AccountCode, AccountOutput, ProcessContext> REACTIVATE_PROCESS =
        activation(REACTIVATE, true, "Makes an inactive account take postings again.");

    public static final ProcessDefinition<AccountCode, AccountOutput, ProcessContext> DELETE_PROCESS =
        ProcessDefinition.define(DELETE, 1, AccountCode.class, AccountOutput.class, ProcessContext.class, pb -> pb
            .description("Deletes an account that was never posted to; others are deactivated instead.")
            .permissions(FinancePermissions.ACCOUNT_MAINTAIN)
            .contextFactory(AccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, AccountOutput.class))
            .step("Load the account", QueryEntities.of(LedgerEntities.ACCOUNT_DATASET,
                ctx -> codes(input(ctx, AccountCode.class).accountCode()), LEDGER_ACCOUNTS))
            .step("Load the finance account", QueryEntities.of(GlEntities.ACCOUNT_DATASET,
                ctx -> codes(input(ctx, AccountCode.class).accountCode()), FIN_ACCOUNTS))
            .step("Look for a posting", QueryEntities.of(LedgerEntities.ENTRY_DATASET,
                ctx -> byLedgerAccount(ctx, "accountId"), ENTRIES))
            .step("Look for a sub-account", QueryEntities.of(LedgerEntities.ACCOUNT_DATASET,
                ctx -> byLedgerAccount(ctx, "parentId"), CHILDREN))
            .compute("Delete the account", (metadata, ctx) -> delete(ctx)));

    private static ProcessDefinition<AccountCode, AccountOutput, ProcessContext> activation(String name,
        boolean active, String description) {
        return ProcessDefinition.define(name, 1, AccountCode.class, AccountOutput.class, ProcessContext.class,
            pb -> pb
                .description(description)
                .permissions(FinancePermissions.ACCOUNT_MAINTAIN)
                .contextFactory(AccountProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, AccountOutput.class))
                .step("Load the account", QueryEntities.of(LedgerEntities.ACCOUNT_DATASET,
                    ctx -> codes(input(ctx, AccountCode.class).accountCode()), LEDGER_ACCOUNTS))
                .step("Load the finance account", QueryEntities.of(GlEntities.ACCOUNT_DATASET,
                    ctx -> codes(input(ctx, AccountCode.class).accountCode()), FIN_ACCOUNTS))
                .compute(active ? "Reactivate the account" : "Deactivate the account",
                    (metadata, ctx) -> activate(ctx, active)));
    }

    // ---- queries -------------------------------------------------------------------------------------------------

    static EntityQuery codes(String... codes) {
        List<Object> present = new ArrayList<>();
        for (String code : codes) {
            if (code != null && !code.isBlank()) {
                present.add(code.trim());
            }
        }
        return EntityQuery.builder().where(new QueryPredicate.In("accountCode", present)).limit(present.size() + 1)
            .build();
    }

    private static EntityQuery byLedgerAccount(ProcessContext ctx, String field) {
        EntityInstance account = ledger(ctx, input(ctx, AccountCode.class).accountCode());
        // An empty IN matches nothing: an unknown account is reported by the computation.
        QueryPredicate where = account == null ? new QueryPredicate.In(field, List.of())
            : new QueryPredicate.Eq(field, account.id());
        return EntityQuery.builder().where(where).limit(1).build();
    }

    // ---- computations ---------------------------------------------------------------------------------------------

    static void create(ProcessContext ctx) {
        AccountInput input = input(ctx, AccountInput.class);
        String code = input.accountCode().trim();
        if (ledger(ctx, code) != null || fin(ctx, code) != null) {
            ctx.reject(new Violation("accountCode", CODE_TAKEN, "Account " + code + " already exists",
                Map.of("accountCode", code)));
            return;
        }
        String financialType = upper(input.financialType());
        String normalBalance = upper(input.normalBalance());
        checkValue(ctx, "financialType", financialType, GlEntities.FINANCIAL_TYPE_VALUES);
        checkValue(ctx, "normalBalance", normalBalance, GlEntities.NORMAL_BALANCE_VALUES);
        String cashFlowClass = optionalCode(ctx, "cashFlowClass", upper(input.cashFlowClass()),
            GlEntities.CASH_FLOW_VALUES);
        String controlClass = optionalCode(ctx, "controlClass", upper(input.controlClass()),
            GlEntities.CONTROL_CLASS_VALUES);
        String requiredDimension = optionalCode(ctx, "requiredDimension", lower(input.requiredDimension()),
            GlEntities.DIMENSION_VALUES);
        Object parentId = parent(ctx, input.parentCode());
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("accountCode", code);
        ledger.put("accountName", input.accountName().trim());
        ledger.put("accountType", AccountTypes.ledgerType(financialType, normalBalance));
        ledger.put("enabled", true);
        ledger.put("parentId", parentId);
        ledger.put("summary", Boolean.TRUE.equals(input.summary()));
        Object ledgerId = ctx.changes().insert(LedgerEntities.ACCOUNT, ledger);

        Map<String, Object> fin = new LinkedHashMap<>();
        fin.put("accountCode", code);
        fin.put("ledgerAccountId", ledgerId);
        fin.put("financialType", financialType);
        fin.put("normalBalance", normalBalance);
        fin.put("statementLine", input.statementLine().trim());
        fin.put("cashFlowClass", cashFlowClass);
        fin.put("controlClass", controlClass);
        fin.put("clearing", Boolean.TRUE.equals(input.clearing()));
        fin.put("requiredDimension", requiredDimension);
        Object finId = ctx.changes().insert(GlEntities.ACCOUNT, fin);
        ctx.put(OUTPUT, new AccountOutput(String.valueOf(finId), String.valueOf(ledgerId), code, true, true));
    }

    static void update(ProcessContext ctx) {
        AccountChange input = input(ctx, AccountChange.class);
        String code = input.accountCode().trim();
        EntityInstance ledger = ledger(ctx, code);
        EntityInstance fin = fin(ctx, code);
        if (ledger == null || fin == null) {
            notFound(ctx, code);
            return;
        }
        Map<String, Object> ledgerChanges = new LinkedHashMap<>();
        if (input.accountName() != null) {
            if (input.accountName().isBlank()) {
                ctx.reject(new Violation("accountName", INVALID_VALUE, "An account needs a name",
                    Map.of("value", input.accountName())));
            }
            ledgerChanges.put("accountName", input.accountName().trim());
        }
        if (input.parentCode() != null) {
            ledgerChanges.put("parentId", input.parentCode().isBlank() ? null : parent(ctx, input.parentCode()));
        }
        if (input.summary() != null) {
            ledgerChanges.put("summary", input.summary());
        }
        Map<String, Object> finChanges = new LinkedHashMap<>();
        if (input.normalBalance() != null) {
            String normalBalance = upper(input.normalBalance());
            checkValue(ctx, "normalBalance", normalBalance, GlEntities.NORMAL_BALANCE_VALUES);
            // The ledger type of other income or expense follows the normal balance and cannot change.
            String type = fin.get("financialType");
            boolean sameLedgerType = ctx.hasViolations()
                || AccountTypes.ledgerType(type, normalBalance).equals(ledger.get("accountType"));
            if (!sameLedgerType) {
                ctx.reject(new Violation("normalBalance", INVALID_VALUE, "Account " + code
                    + " would change its ledger type; open a new account instead", Map.of("accountCode", code)));
            }
            finChanges.put("normalBalance", normalBalance);
        }
        if (input.statementLine() != null) {
            finChanges.put("statementLine", input.statementLine().trim());
        }
        if (input.cashFlowClass() != null) {
            finChanges.put("cashFlowClass", optionalCode(ctx, "cashFlowClass", upper(input.cashFlowClass()),
                GlEntities.CASH_FLOW_VALUES));
        }
        if (input.controlClass() != null) {
            finChanges.put("controlClass", optionalCode(ctx, "controlClass", upper(input.controlClass()),
                GlEntities.CONTROL_CLASS_VALUES));
        }
        if (input.requiredDimension() != null) {
            finChanges.put("requiredDimension", optionalCode(ctx, "requiredDimension",
                lower(input.requiredDimension()), GlEntities.DIMENSION_VALUES));
        }
        if (input.clearing() != null) {
            finChanges.put("clearing", input.clearing());
        }
        if (ctx.hasViolations()) {
            return;
        }
        ledgerChanges.entrySet().removeIf(e -> Objects.equals(e.getValue(), ledger.get(e.getKey())));
        finChanges.entrySet().removeIf(e -> Objects.equals(e.getValue(), fin.get(e.getKey())));
        if (!ledgerChanges.isEmpty()) {
            ctx.changes().update(LedgerEntities.ACCOUNT, ledger.id(), ledger.version(), ledgerChanges);
        }
        if (!finChanges.isEmpty()) {
            ctx.changes().update(GlEntities.ACCOUNT, fin.id(), fin.version(), finChanges);
        }
        ctx.put(OUTPUT, output(fin, ledger, Boolean.TRUE.equals(ledger.get("enabled")),
            !ledgerChanges.isEmpty() || !finChanges.isEmpty()));
    }

    static void activate(ProcessContext ctx, boolean active) {
        String code = input(ctx, AccountCode.class).accountCode().trim();
        EntityInstance ledger = ledger(ctx, code);
        EntityInstance fin = fin(ctx, code);
        if (ledger == null || fin == null) {
            notFound(ctx, code);
            return;
        }
        boolean change = active != Boolean.TRUE.equals(ledger.get("enabled"));
        if (change) {
            ctx.changes().update(LedgerEntities.ACCOUNT, ledger.id(), ledger.version(), Map.of("enabled", active));
        }
        ctx.put(OUTPUT, output(fin, ledger, active, change));
    }

    static void delete(ProcessContext ctx) {
        String code = input(ctx, AccountCode.class).accountCode().trim();
        EntityInstance ledger = ledger(ctx, code);
        EntityInstance fin = fin(ctx, code);
        if (ledger == null || fin == null) {
            notFound(ctx, code);
            return;
        }
        if (!list(ctx, ENTRIES).isEmpty()) {
            ctx.reject(new Violation("accountCode", HAS_POSTINGS, "Account " + code
                + " has postings and cannot be deleted; deactivate it instead", Map.of("accountCode", code)));
            return;
        }
        if (!list(ctx, CHILDREN).isEmpty()) {
            ctx.reject(new Violation("accountCode", HAS_CHILDREN, "Account " + code + " has sub-accounts",
                Map.of("accountCode", code)));
            return;
        }
        ctx.changes().delete(GlEntities.ACCOUNT, fin.id(), fin.version());
        ctx.changes().delete(LedgerEntities.ACCOUNT, ledger.id(), ledger.version());
        ctx.put(OUTPUT, output(fin, ledger, false, true));
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static Object parent(ProcessContext ctx, String parentCode) {
        if (parentCode == null || parentCode.isBlank()) {
            return null;
        }
        EntityInstance parent = ledger(ctx, parentCode.trim());
        if (parent == null) {
            ctx.reject(new Violation("parentCode", PARENT_NOT_FOUND, "There is no account " + parentCode.trim(),
                Map.of("accountCode", parentCode.trim())));
            return null;
        }
        return parent.id();
    }

    /** A code that may be left out (blank: none), else one of {@code allowed}. */
    private static String optionalCode(ProcessContext ctx, String field, String value, List<String> allowed) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        checkValue(ctx, field, value, allowed);
        return value;
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static void checkValue(ProcessContext ctx, String field, String value, List<String> allowed) {
        if (!allowed.contains(value)) {
            ctx.reject(new Violation(field, INVALID_VALUE, field + " must be one of " + allowed,
                Map.of("value", value)));
        }
    }

    private static void notFound(ProcessContext ctx, String code) {
        ctx.reject(new Violation("accountCode", NOT_FOUND, "There is no account " + code, Map.of("accountCode", code)));
    }

    private static AccountOutput output(EntityInstance fin, EntityInstance ledger, boolean active, boolean changed) {
        return new AccountOutput(String.valueOf(fin.id()), String.valueOf(ledger.id()), fin.get("accountCode"),
            active, changed);
    }

    static EntityInstance ledger(ProcessContext ctx, String code) {
        return byCode(list(ctx, LEDGER_ACCOUNTS), code);
    }

    static EntityInstance fin(ProcessContext ctx, String code) {
        return byCode(list(ctx, FIN_ACCOUNTS), code);
    }

    private static EntityInstance byCode(List<EntityInstance> accounts, String code) {
        return accounts.stream().filter(a -> code.equals(a.get("accountCode"))).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    static <T> T input(ProcessContext ctx, Class<T> type) {
        return ctx.get(INPUT, type);
    }

    static ProcessContext withInput(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private AccountProcesses() {}
}

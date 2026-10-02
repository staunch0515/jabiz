package com.jabiz.finance.fa;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.jabiz.finance.fa.FaSupport.INPUT;
import static com.jabiz.finance.fa.FaSupport.code;
import static com.jabiz.finance.fa.FaSupport.first;
import static com.jabiz.finance.fa.FaSupport.list;
import static com.jabiz.finance.fa.FaSupport.trim;

/**
 * Asset classes and the asset settings, the controller's (FIN-FA-001):
 * <ul>
 *   <li>{@code FIN_FA_CLASS_SAVE}: a class with its cost account (a {@code FA_COST} control account belonging to no
 *       other class), accumulated depreciation account ({@code FA_ACCUM}), depreciation expense account (an expense
 *       account that is no control account), default method, useful life and convention, and capitalization
 *       threshold. Assets keep the terms they were given: a class's new defaults apply to assets made after; a class
 *       with assets keeps its cost and accumulated depreciation accounts.</li>
 *   <li>{@code FIN_FA_SETTINGS_SET}: the account of disposal gains and losses (an account of the income statement
 *       that is no control account).</li>
 * </ul>
 */
public final class AssetClassProcesses {

    public static final String CLASS_SAVE = "FIN_FA_CLASS_SAVE";
    public static final String SETTINGS_SET = "FIN_FA_SETTINGS_SET";

    public static final String WRONG_ACCOUNT = "FIN_FA_CLASS_ACCOUNT";
    public static final String ACCOUNT_TAKEN = "FIN_FA_CLASS_ACCOUNT_TAKEN";
    public static final String WRONG_TERMS = "FIN_FA_TERMS";
    public static final String WRONG_SETTINGS_ACCOUNT = "FIN_FA_SETTINGS_ACCOUNT";
    public static final String CLASS_IN_USE = "FIN_FA_CLASS_IN_USE";

    public record ClassInput(@NotBlank @Size(max = 20) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]*") String classCode,
        @NotBlank @Size(max = 100) String className, @NotBlank @Size(max = 20) String costAccount,
        @NotBlank @Size(max = 20) String accumulatedAccount, @NotBlank @Size(max = 20) String expenseAccount,
        @NotBlank @Size(max = 6) String method, @NotNull @Min(1) @Max(1200) Integer lifeMonths,
        @NotBlank @Size(max = 12) String convention,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal threshold, Boolean active) {}

    public record ClassOutput(String classId, String classCode, boolean created, boolean changed) {}

    public record SettingsInput(@Size(max = 20) String gainLossAccount) {}

    public record SettingsOutput(String settingsId, boolean changed) {}

    static final String OUTPUT = "output";
    static final String CLASSES = "classes";
    static final String OWNERS = "owners";
    static final String ACCOUNTS = "accounts";
    static final String SETTINGS = "settings";
    static final String MEMBERS = "members";

    public static final ProcessDefinition<ClassInput, ClassOutput, ProcessContext> CLASS_PROCESS =
        ProcessDefinition.define(CLASS_SAVE, 1, ClassInput.class, ClassOutput.class, ProcessContext.class, pb -> pb
            .description("Saves an asset class: its accounts, default method, life and convention, and threshold.")
            .permissions(FinancePermissions.FA_MAINTAIN)
            .contextFactory(FaSupport::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, ClassOutput.class))
            .step("Load the class", QueryEntities.of(AssetEntities.ASSET_CLASS_DATASET,
                ctx -> FaSupport.eq("classCode", code(input(ctx).classCode())), CLASSES))
            .step("Load the cost account's class", QueryEntities.of(AssetEntities.ASSET_CLASS_DATASET,
                ctx -> FaSupport.eq("costAccount", trim(input(ctx).costAccount())), OWNERS))
            .step("Load the accounts", QueryEntities.of(GlEntities.ACCOUNT_DATASET, ctx -> {
                ClassInput input = input(ctx);
                List<Object> codes = new ArrayList<>();
                for (String account : new String[] {input.costAccount(), input.accumulatedAccount(),
                    input.expenseAccount()}) {
                    if (trim(account) != null) {
                        codes.add(trim(account));
                    }
                }
                return FaSupport.in("accountCode", codes, 4);
            }, ACCOUNTS))
            .step("Look for its assets", QueryEntities.of(AssetEntities.ASSET_DATASET,
                ctx -> FaSupport.eq("classCode", code(input(ctx).classCode())), MEMBERS))
            .compute("Save the class", (metadata, ctx) -> saveClass(ctx)));

    public static final ProcessDefinition<SettingsInput, SettingsOutput, ProcessContext> SETTINGS_PROCESS =
        ProcessDefinition.define(SETTINGS_SET, 1, SettingsInput.class, SettingsOutput.class, ProcessContext.class,
            pb -> pb
                .description("Sets the account of disposal gains and losses.")
                .permissions(FinancePermissions.FA_MAINTAIN)
                .contextFactory(FaSupport::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, SettingsOutput.class))
                .step("Load the settings", QueryEntities.of(AssetEntities.SETTINGS_DATASET, ctx -> current(),
                    SETTINGS))
                .step("Load the account", QueryEntities.of(GlEntities.ACCOUNT_DATASET, ctx -> FaSupport.eq(
                    "accountCode", trim(ctx.get(INPUT, SettingsInput.class).gainLossAccount())), ACCOUNTS))
                .compute("Set the settings", (metadata, ctx) -> setSettings(ctx)));

    /** The one settings row. */
    public static EntityQuery current() {
        return FaSupport.eq("settingsKey", AssetEntities.SETTINGS_KEY);
    }

    static void saveClass(ProcessContext ctx) {
        ClassInput input = input(ctx);
        String classCode = code(input.classCode());
        String method = code(input.method());
        String convention = code(input.convention());
        if (!AssetEntities.METHOD_VALUES.contains(method) || !AssetEntities.CONVENTION_VALUES.contains(convention)) {
            ctx.reject(new Violation("method", WRONG_TERMS, "A method is one of " + AssetEntities.METHOD_VALUES
                + " and a convention one of " + AssetEntities.CONVENTION_VALUES, Map.of()));
        }
        account(ctx, "costAccount", trim(input.costAccount()), "a FA_COST control account",
            a -> "FA_COST".equals(a.get("controlClass")));
        account(ctx, "accumulatedAccount", trim(input.accumulatedAccount()), "a FA_ACCUM control account",
            a -> "FA_ACCUM".equals(a.get("controlClass")));
        account(ctx, "expenseAccount", trim(input.expenseAccount()), "an expense account that is no control account",
            a -> a.get("controlClass") == null && "EXPENSE".equals(a.get("financialType")));
        EntityInstance owner = first(ctx, OWNERS);
        if (owner != null && !Objects.equals(owner.get("classCode"), classCode)) {
            // A bill line coded to the account must know its class.
            ctx.reject(new Violation("costAccount", ACCOUNT_TAKEN, "Account " + trim(input.costAccount())
                + " is the cost account of class " + owner.get("classCode") + " already",
                Map.of("accountCode", trim(input.costAccount()), "classCode", (Object) owner.get("classCode"))));
        }
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("className", input.className().trim());
        values.put("costAccount", trim(input.costAccount()));
        values.put("accumulatedAccount", trim(input.accumulatedAccount()));
        values.put("expenseAccount", trim(input.expenseAccount()));
        values.put("method", method);
        values.put("lifeMonths", BigDecimal.valueOf(input.lifeMonths()));
        values.put("convention", convention);
        values.put("threshold", input.threshold().setScale(2));
        values.put("active", input.active() == null || input.active());
        EntityInstance found = first(ctx, CLASSES);
        if (found != null && first(ctx, MEMBERS) != null
            && (!Objects.equals(found.get("costAccount"), values.get("costAccount"))
                || !Objects.equals(found.get("accumulatedAccount"), values.get("accumulatedAccount")))) {
            // Its assets are carried and depreciated on these accounts: moving them would split their balances.
            ctx.reject(new Violation("costAccount", CLASS_IN_USE, "Class " + classCode + " has assets: its cost and "
                + "accumulated depreciation accounts stay", Map.of("classCode", classCode)));
            return;
        }
        if (found == null) {
            values.put("classCode", classCode);
            Object id = ctx.changes().insert(AssetEntities.ASSET_CLASS, values);
            ctx.put(OUTPUT, new ClassOutput(String.valueOf(id), classCode, true, true));
            return;
        }
        Map<String, Object> changes = changed(found, values);
        if (!changes.isEmpty()) {
            ctx.changes().update(AssetEntities.ASSET_CLASS, found.id(), found.version(), changes);
        }
        ctx.put(OUTPUT, new ClassOutput(String.valueOf(found.id()), classCode, false, !changes.isEmpty()));
    }

    static void setSettings(ProcessContext ctx) {
        String account = trim(ctx.get(INPUT, SettingsInput.class).gainLossAccount());
        if (account != null) {
            EntityInstance found = first(ctx, ACCOUNTS);
            if (found == null || found.get("controlClass") != null
                || !List.of("REVENUE", "EXPENSE", "OTHER").contains(String.valueOf((Object) found.get("financialType")))) {
                ctx.reject(new Violation("gainLossAccount", WRONG_SETTINGS_ACCOUNT, "Disposal gains and losses go to "
                    + "an account of the income statement that is no control account; " + account + " is not",
                    Map.of("accountCode", account)));
                return;
            }
        }
        EntityInstance row = first(ctx, SETTINGS);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("gainLossAccount", account != null ? account : row == null ? null : row.get("gainLossAccount"));
        if (row == null) {
            values.put("settingsKey", AssetEntities.SETTINGS_KEY);
            Object id = ctx.changes().insert(AssetEntities.SETTINGS, values);
            ctx.put(OUTPUT, new SettingsOutput(String.valueOf(id), true));
            return;
        }
        Map<String, Object> changes = changed(row, values);
        if (!changes.isEmpty()) {
            ctx.changes().update(AssetEntities.SETTINGS, row.id(), row.version(), changes);
        }
        ctx.put(OUTPUT, new SettingsOutput(String.valueOf(row.id()), !changes.isEmpty()));
    }

    private static void account(ProcessContext ctx, String field, String code, String what,
        java.util.function.Predicate<EntityInstance> fits) {
        EntityInstance account = list(ctx, ACCOUNTS).stream().filter(a -> Objects.equals(a.get("accountCode"), code))
            .findFirst().orElse(null);
        if (account == null || !fits.test(account)) {
            ctx.reject(new Violation(field, WRONG_ACCOUNT, "The " + field + " is " + what + "; " + code + " is not",
                Map.of("accountCode", String.valueOf(code))));
        }
    }

    /** The values that differ from what is stored. */
    static Map<String, Object> changed(EntityInstance row, Map<String, Object> values) {
        Map<String, Object> changes = new LinkedHashMap<>();
        values.forEach((field, value) -> {
            Object stored = row.get(field);
            boolean same = stored instanceof BigDecimal a && value instanceof BigDecimal b ? a.compareTo(b) == 0
                : Objects.equals(stored, value);
            if (!same) {
                changes.put(field, value);
            }
        });
        return changes;
    }

    private static ClassInput input(ProcessContext ctx) {
        return ctx.get(INPUT, ClassInput.class);
    }

    private AssetClassProcesses() {}
}

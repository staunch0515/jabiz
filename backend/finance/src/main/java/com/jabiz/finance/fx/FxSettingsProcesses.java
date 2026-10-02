package com.jabiz.finance.fx;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@code FIN_FX_SETTINGS_SET} (F7 plan decision D2): the accounts of realized and unrealized exchange gains and
 * losses (income statement accounts that are no control accounts), the rate type of a revaluation and the days a
 * spot rate may be taken back. What is not given stays.
 */
public final class FxSettingsProcesses {

    public static final String SET = "FIN_FX_SETTINGS_SET";

    public static final String WRONG_ACCOUNT = "FIN_FX_SETTINGS_ACCOUNT";
    public static final String WRONG_RATE_TYPE = "FIN_FX_SETTINGS_RATE_TYPE";
    public static final String NO_SETTINGS = "FIN_FX_NO_SETTINGS";

    public record SettingsInput(@Size(max = 20) String realizedAccount, @Size(max = 20) String unrealizedAccount,
        @Size(max = 10) String revaluationRateType, @Min(0) @Max(31) Integer toleranceDays) {}

    public record SettingsOutput(String settingsId, boolean changed) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String SETTINGS = "settings";
    static final String ACCOUNTS = "accounts";

    public static final ProcessDefinition<SettingsInput, SettingsOutput, ProcessContext> SET_PROCESS =
        ProcessDefinition.define(SET, 1, SettingsInput.class, SettingsOutput.class, ProcessContext.class, pb -> pb
            .description("Sets the accounts of exchange gains and losses and how rates are taken.")
            .permissions(FinancePermissions.FX_SETTINGS)
            .contextFactory(FxSettingsProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, SettingsOutput.class))
            .step("Load the settings", QueryEntities.of(FxEntities.SETTINGS_DATASET, ctx -> current(), SETTINGS))
            .step("Load the accounts", QueryEntities.of(GlEntities.ACCOUNT_DATASET, ctx -> {
                SettingsInput input = ctx.get(INPUT, SettingsInput.class);
                List<Object> codes = new ArrayList<>();
                for (String code : new String[] {trim(input.realizedAccount()), trim(input.unrealizedAccount())}) {
                    if (code != null) {
                        codes.add(code);
                    }
                }
                return EntityQuery.builder().where(new QueryPredicate.In("accountCode", codes)).limit(2).build();
            }, ACCOUNTS))
            .compute("Set them", (metadata, ctx) -> set(ctx)));

    /** The one settings row. */
    public static EntityQuery current() {
        return EntityQuery.builder().where(new QueryPredicate.Eq("settingsKey", FxEntities.SETTINGS_KEY)).limit(1)
            .build();
    }


    static void set(ProcessContext ctx) {
        SettingsInput input = ctx.get(INPUT, SettingsInput.class);
        @SuppressWarnings("unchecked")
        List<EntityInstance> accounts = (List<EntityInstance>) ctx.get(ACCOUNTS);
        Map<String, EntityInstance> byCode = new LinkedHashMap<>();
        if (accounts != null) {
            accounts.forEach(a -> byCode.put(a.get("accountCode"), a));
        }
        for (String[] field : new String[][] {{"realizedAccount", trim(input.realizedAccount())},
            {"unrealizedAccount", trim(input.unrealizedAccount())}}) {
            if (field[1] == null) {
                continue;
            }
            EntityInstance account = byCode.get(field[1]);
            if (account == null || account.get("controlClass") != null
                || !List.of("REVENUE", "EXPENSE", "OTHER").contains(String.valueOf((Object) account.get("financialType")))) {
                ctx.reject(new Violation(field[0], WRONG_ACCOUNT, "Exchange gains and losses go to an account of the "
                    + "income statement that is no control account; " + field[1] + " is not",
                    Map.of("accountCode", field[1])));
            }
        }
        String rateType = input.revaluationRateType() == null ? null
            : input.revaluationRateType().trim().toUpperCase(Locale.ROOT);
        if (rateType != null && !FxEntities.RATE_TYPES.contains(rateType)) {
            ctx.reject(new Violation("revaluationRateType", WRONG_RATE_TYPE, "A rate type is one of "
                + FxEntities.RATE_TYPES, Map.of("value", rateType)));
        }
        if (ctx.hasViolations()) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(SETTINGS);
        EntityInstance row = rows == null || rows.isEmpty() ? null : rows.getFirst();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("realizedAccount", pick(trim(input.realizedAccount()), row, "realizedAccount"));
        values.put("unrealizedAccount", pick(trim(input.unrealizedAccount()), row, "unrealizedAccount"));
        values.put("revaluationRateType", rateType != null ? rateType
            : row == null ? FxEntities.CLOSING : row.get("revaluationRateType"));
        values.put("toleranceDays", input.toleranceDays() != null ? BigDecimal.valueOf(input.toleranceDays())
            : row == null ? BigDecimal.valueOf(FxEntities.DEFAULT_TOLERANCE_DAYS) : row.get("toleranceDays"));
        if (row == null) {
            values.put("settingsKey", FxEntities.SETTINGS_KEY);
            Object id = ctx.changes().insert(FxEntities.SETTINGS, values);
            ctx.put(OUTPUT, new SettingsOutput(String.valueOf(id), true));
            return;
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            Object now = row.get(key);
            boolean same = value instanceof BigDecimal b && now instanceof BigDecimal n ? b.compareTo(n) == 0
                : Objects.equals(value, now);
            if (!same) {
                changes.put(key, value);
            }
        });
        if (!changes.isEmpty()) {
            ctx.changes().update(FxEntities.SETTINGS, row.id(), row.version(), changes);
        }
        ctx.put(OUTPUT, new SettingsOutput(String.valueOf(row.id()), !changes.isEmpty()));
    }

    private static Object pick(String given, EntityInstance row, String field) {
        return given != null ? given : row == null ? null : row.get(field);
    }

    static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static ProcessContext withInput(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private FxSettingsProcesses() {}
}

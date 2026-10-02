package com.jabiz.finance.bank;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.jabiz.finance.bank.BankAccountProcesses.list;
import static com.jabiz.finance.bank.BankAccountProcesses.trim;

/**
 * {@code FIN_BANK_SETTINGS_SET}: the bank settings, kept by the controller. The in-transit account, where given, is an
 * asset account that is no control account (the sample chart has none: the controller adds one, F5 plan decision
 * D1). What is not given keeps its value: the in-transit account once set, the matching window and the age of a stale
 * check their defaults (3 and 90 days). A transfer keeps the in-transit account it was sent to, so changing it does
 * not strand money on its way.
 */
public final class BankSettingsProcesses {

    public static final String SET = "FIN_BANK_SETTINGS_SET";

    public static final String WRONG_ACCOUNT = "FIN_BANK_SETTINGS_WRONG_ACCOUNT";

    public record SettingsInput(@Size(max = 20) String inTransitAccount, @Min(0) @Max(31) Integer matchWindowDays,
        @Min(1) @Max(3650) Integer staleCheckDays) {}

    public record SettingsOutput(String settingsId, boolean changed) {}

    /** The settings as the bank processes read them; the defaults where none are set. */
    public record Settings(String inTransitAccount, int matchWindowDays, int staleCheckDays) {

        public static Settings of(List<EntityInstance> rows) {
            if (rows.isEmpty()) {
                return new Settings(null, BankEntities.DEFAULT_MATCH_WINDOW_DAYS, BankEntities.DEFAULT_STALE_CHECK_DAYS);
            }
            EntityInstance row = rows.getFirst();
            return new Settings(row.get("inTransitAccount"),
                row.<BigDecimal>get("matchWindowDays").intValueExact(),
                row.<BigDecimal>get("staleCheckDays").intValueExact());
        }
    }

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String SETTINGS = "settings";
    static final String ACCOUNTS = "accounts";

    public static final ProcessDefinition<SettingsInput, SettingsOutput, ProcessContext> SET_PROCESS =
        ProcessDefinition.define(SET, 1, SettingsInput.class, SettingsOutput.class, ProcessContext.class, pb -> pb
            .description("Sets the in-transit account, the matching window and the age of a stale check.")
            .permissions(FinancePermissions.BANK_SETTINGS)
            .contextFactory(BankAccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, SettingsOutput.class))
            .step("Load the settings", QueryEntities.of(BankEntities.SETTINGS_DATASET, ctx -> current(), SETTINGS))
            .step("Load the account", QueryEntities.of(GlEntities.ACCOUNT_DATASET, ctx -> BankAccountProcesses.eq(
                "accountCode", trim(ctx.get(INPUT, SettingsInput.class).inTransitAccount())), ACCOUNTS))
            .compute("Set the settings", (metadata, ctx) -> set(ctx)));

    /** The one settings row. */
    public static EntityQuery current() {
        return EntityQuery.builder().where(new QueryPredicate.Eq("settingsKey", BankEntities.SETTINGS_KEY)).limit(1)
            .build();
    }

    static void set(ProcessContext ctx) {
        SettingsInput input = ctx.get(INPUT, SettingsInput.class);
        String inTransit = trim(input.inTransitAccount());
        if (inTransit != null) {
            EntityInstance account = list(ctx, ACCOUNTS).stream().findFirst().orElse(null);
            // Money between two accounts of the company: an asset, and one any process may post to.
            if (account == null || account.get("controlClass") != null
                || !"ASSET".equals(account.get("financialType"))) {
                ctx.reject(new Violation("inTransitAccount", WRONG_ACCOUNT, "The in-transit account is an asset "
                    + "account that is no control account; " + inTransit + " is not",
                    Map.of("accountCode", inTransit)));
                return;
            }
        }
        List<EntityInstance> found = list(ctx, SETTINGS);
        Settings current = Settings.of(found);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("inTransitAccount", inTransit != null ? inTransit : current.inTransitAccount());
        values.put("matchWindowDays", BigDecimal.valueOf(input.matchWindowDays() != null ? input.matchWindowDays()
            : current.matchWindowDays()));
        values.put("staleCheckDays", BigDecimal.valueOf(input.staleCheckDays() != null ? input.staleCheckDays()
            : current.staleCheckDays()));
        if (found.isEmpty()) {
            values.put("settingsKey", BankEntities.SETTINGS_KEY);
            Object id = ctx.changes().insert(BankEntities.SETTINGS, values);
            ctx.put(OUTPUT, new SettingsOutput(String.valueOf(id), true));
            return;
        }
        EntityInstance row = found.getFirst();
        Map<String, Object> changes = new LinkedHashMap<>();
        values.forEach((field, value) -> {
            Object stored = row.get(field);
            boolean same = stored instanceof BigDecimal a && value instanceof BigDecimal b ? a.compareTo(b) == 0
                : Objects.equals(stored, value);
            if (!same) {
                changes.put(field, value);
            }
        });
        if (!changes.isEmpty()) {
            ctx.changes().update(BankEntities.SETTINGS, row.id(), row.version(), changes);
        }
        ctx.put(OUTPUT, new SettingsOutput(String.valueOf(row.id()), !changes.isEmpty()));
    }

    private BankSettingsProcesses() {}
}

package com.jabiz.finance.ar;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code FIN_AR_SETTINGS_SET}: the receivables settings, kept by the controller (docs/finance/ROADMAP.md F3, decision
 * 3). The accounts must be in the chart: the receivables account is the {@code AR} control account; the allowance,
 * returns, sales tax and discount accounts are no control accounts; an unapplied cash account, if any, is a clearing
 * account (FIN-CT-005) and no control account. Without one a receipt must be applied in full.
 */
public final class ArSettingsProcesses {

    public static final String SET = "FIN_AR_SETTINGS_SET";

    public static final String UNKNOWN_ACCOUNT = "FIN_AR_SETTINGS_UNKNOWN_ACCOUNT";
    public static final String WRONG_ACCOUNT = "FIN_AR_SETTINGS_WRONG_ACCOUNT";
    public static final String INVALID_VALUE = "FIN_AR_SETTINGS_INVALID_VALUE";

    /**
     * @param missingCertificate {@code BLOCK} (the default) or {@code CHARGE} (FIN-TX-004)
     * @param creditLimitCheck   {@code WARN} (the default) or {@code OFF} (FIN-AR-013); requiring approval is an
     *                           approval rule of invoices instead
     * @param lossRateCurrent    the expected loss in percent of what is current, and the next ones of the aging
     *                           buckets after it (FIN-AR-011); none means no suggestion for the bucket
     */
    public record SettingsInput(@NotBlank @Size(max = 20) String receivableAccount,
        @NotBlank @Size(max = 20) String allowanceAccount, @NotBlank @Size(max = 20) String returnsAccount,
        @NotBlank @Size(max = 20) String salesTaxAccount, @Size(max = 20) String unappliedCashAccount,
        @Size(max = 20) String discountAccount, String missingCertificate, String creditLimitCheck,
        @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal lossRateCurrent,
        @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal lossRate1,
        @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal lossRate2,
        @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal lossRate3,
        @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal lossRateOver) {}

    public record SettingsOutput(String settingsId, boolean changed) {}

    /** The settings as the receivables processes read them. */
    public record Settings(String receivableAccount, String allowanceAccount, String returnsAccount,
        String salesTaxAccount, String unappliedCashAccount, String discountAccount, String missingCertificate,
        String creditLimitCheck) {

        public static Settings of(EntityInstance row) {
            return new Settings(row.get("receivableAccount"), row.get("allowanceAccount"), row.get("returnsAccount"),
                row.get("salesTaxAccount"), row.get("unappliedCashAccount"), row.get("discountAccount"),
                row.get("missingCertificate"), row.get("creditLimitCheck"));
        }
    }

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String SETTINGS = "settings";
    static final String ACCOUNTS = "accounts";

    public static final ProcessDefinition<SettingsInput, SettingsOutput, ProcessContext> SET_PROCESS =
        ProcessDefinition.define(SET, 1, SettingsInput.class, SettingsOutput.class, ProcessContext.class, pb -> pb
            .description("Sets the receivables accounts and policies.")
            .permissions(FinancePermissions.AR_SETTINGS)
            .contextFactory(CustomerProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, SettingsOutput.class))
            .step("Load the settings", QueryEntities.of(ArEntities.SETTINGS_DATASET, ctx -> current(), SETTINGS))
            .step("Load the accounts", QueryEntities.of(GlEntities.ACCOUNT_DATASET, ctx -> {
                SettingsInput input = ctx.get(INPUT, SettingsInput.class);
                Set<Object> codes = new LinkedHashSet<>();
                for (String code : new String[] {input.receivableAccount(), input.allowanceAccount(),
                    input.returnsAccount(), input.salesTaxAccount(), input.unappliedCashAccount(),
                    input.discountAccount()}) {
                    if (code != null && !code.isBlank()) {
                        codes.add(code.trim());
                    }
                }
                return EntityQuery.builder().where(new QueryPredicate.In("accountCode", new ArrayList<>(codes)))
                    .limit(codes.size() + 1).build();
            }, ACCOUNTS))
            .compute("Set the settings", (metadata, ctx) -> set(ctx)));

    /** The one settings row. */
    public static EntityQuery current() {
        return EntityQuery.builder().where(new QueryPredicate.Eq("settingsKey", ArEntities.SETTINGS_KEY)).limit(1)
            .build();
    }

    static void set(ProcessContext ctx) {
        SettingsInput input = ctx.get(INPUT, SettingsInput.class);
        Map<String, EntityInstance> accounts = new LinkedHashMap<>();
        for (EntityInstance account : CustomerProcesses.list(ctx, ACCOUNTS)) {
            accounts.put(account.get("accountCode"), account);
        }
        check(ctx, accounts, "receivableAccount", input.receivableAccount(), "AR", false);
        check(ctx, accounts, "allowanceAccount", input.allowanceAccount(), null, false);
        check(ctx, accounts, "returnsAccount", input.returnsAccount(), null, false);
        check(ctx, accounts, "salesTaxAccount", input.salesTaxAccount(), null, false);
        check(ctx, accounts, "unappliedCashAccount", input.unappliedCashAccount(), null, true);
        check(ctx, accounts, "discountAccount", input.discountAccount(), null, false);
        String missing = choice(ctx, "missingCertificate", input.missingCertificate(), "BLOCK",
            ArEntities.MISSING_CERTIFICATE_VALUES);
        String creditCheck = choice(ctx, "creditLimitCheck", input.creditLimitCheck(), "WARN",
            ArEntities.CREDIT_LIMIT_CHECK_VALUES);
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("receivableAccount", input.receivableAccount().trim());
        values.put("allowanceAccount", input.allowanceAccount().trim());
        values.put("returnsAccount", input.returnsAccount().trim());
        values.put("salesTaxAccount", input.salesTaxAccount().trim());
        values.put("unappliedCashAccount", blankToNull(input.unappliedCashAccount()));
        values.put("discountAccount", blankToNull(input.discountAccount()));
        values.put("missingCertificate", missing);
        values.put("creditLimitCheck", creditCheck);
        values.put("lossRateCurrent", input.lossRateCurrent());
        values.put("lossRate1", input.lossRate1());
        values.put("lossRate2", input.lossRate2());
        values.put("lossRate3", input.lossRate3());
        values.put("lossRateOver", input.lossRateOver());
        List<EntityInstance> found = CustomerProcesses.list(ctx, SETTINGS);
        if (found.isEmpty()) {
            values.put("settingsKey", ArEntities.SETTINGS_KEY);
            Object id = ctx.changes().insert(ArEntities.SETTINGS, values);
            ctx.put(OUTPUT, new SettingsOutput(String.valueOf(id), true));
            return;
        }
        EntityInstance current = found.getFirst();
        Map<String, Object> changes = CustomerProcesses.differences(current, values);
        if (!changes.isEmpty()) {
            ctx.changes().update(ArEntities.SETTINGS, current.id(), current.version(), changes);
        }
        ctx.put(OUTPUT, new SettingsOutput(String.valueOf(current.id()), !changes.isEmpty()));
    }

    private static void check(ProcessContext ctx, Map<String, EntityInstance> accounts, String field, String code,
        String controlClass, boolean clearing) {
        if (code == null || code.isBlank()) {
            return;
        }
        EntityInstance account = accounts.get(code.trim());
        if (account == null) {
            ctx.reject(new Violation(field, UNKNOWN_ACCOUNT, "There is no account " + code.trim(),
                Map.of("accountCode", code.trim())));
            return;
        }
        Object actualClass = account.get("controlClass");
        boolean wrong = controlClass == null ? actualClass != null : !controlClass.equals(actualClass);
        if (clearing && !Boolean.TRUE.equals(account.get("clearing"))) {
            wrong = true;
        }
        if (wrong) {
            String expected = controlClass != null ? "the " + controlClass + " control account"
                : clearing ? "a clearing account that is no control account" : "an account that is no control account";
            ctx.reject(new Violation(field, WRONG_ACCOUNT, field + " must be " + expected + "; " + code.trim()
                + " is not", Map.of("accountCode", code.trim(), "field", field)));
        }
    }

    private static String choice(ProcessContext ctx, String field, String value, String fallback,
        List<String> allowed) {
        String chosen = value == null || value.isBlank() ? fallback : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!allowed.contains(chosen)) {
            ctx.reject(new Violation(field, INVALID_VALUE, field + " must be one of " + allowed,
                Map.of("field", field, "value", value)));
        }
        return chosen;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ArSettingsProcesses() {}
}

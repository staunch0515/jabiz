package com.jabiz.finance.ap;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.bank.BankEntities;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jabiz.finance.ap.VendorProcesses.list;

/**
 * The payables settings and the 1099 thresholds, kept by the controller:
 * <ul>
 *   <li>{@code FIN_AP_SETTINGS_SET}: the payables account is the {@code AP} control account; the cash discount, use
 *       tax and prepayment accounts, where given, are no control accounts (the sample chart has none of them: the
 *       controller adds them, F4 plan decision D5); the default bank is an active company bank account.</li>
 *   <li>{@code FIN_1099_THRESHOLD_SET}: the reporting threshold of a tax year, for one form or, when none is named,
 *       for 1099-NEC and 1099-MISC alike (FIN-AP-021): the report reads the table, so a changed threshold needs no
 *       code.</li>
 * </ul>
 */
public final class ApSettingsProcesses {

    public static final String SET = "FIN_AP_SETTINGS_SET";
    public static final String THRESHOLD_SET = "FIN_1099_THRESHOLD_SET";

    public static final String UNKNOWN_ACCOUNT = "FIN_AP_SETTINGS_UNKNOWN_ACCOUNT";
    public static final String WRONG_ACCOUNT = "FIN_AP_SETTINGS_WRONG_ACCOUNT";
    public static final String UNKNOWN_BANK = "FIN_AP_SETTINGS_UNKNOWN_BANK";
    public static final String INVALID_FORM = "FIN_1099_THRESHOLD_FORM";

    public record SettingsInput(@NotBlank @Size(max = 20) String payableAccount,
        @Size(max = 20) String discountAccount, @Size(max = 20) String useTaxAccount,
        @Size(max = 20) String prepaymentAccount, @Size(max = 20) String defaultBank) {}

    public record SettingsOutput(String settingsId, boolean changed) {}

    /** The settings as the payables processes read them. */
    public record Settings(String payableAccount, String discountAccount, String useTaxAccount,
        String prepaymentAccount, String defaultBank) {

        public static Settings of(EntityInstance row) {
            return new Settings(row.get("payableAccount"), row.get("discountAccount"), row.get("useTaxAccount"),
                row.get("prepaymentAccount"), row.get("defaultBank"));
        }
    }

    /** @param form1099 {@code NEC} or {@code MISC}; both when absent */
    public record ThresholdInput(@NotNull @Min(2000) @Max(2100) Integer taxYear, @Size(max = 10) String form1099,
        @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 2) BigDecimal threshold) {}

    /** @param forms the forms whose threshold was set */
    public record ThresholdOutput(int taxYear, List<String> forms, BigDecimal threshold, boolean changed) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String SETTINGS = "settings";
    static final String ACCOUNTS = "accounts";
    static final String BANKS = "banks";
    static final String THRESHOLDS = "thresholds";

    public static final ProcessDefinition<SettingsInput, SettingsOutput, ProcessContext> SET_PROCESS =
        ProcessDefinition.define(SET, 1, SettingsInput.class, SettingsOutput.class, ProcessContext.class, pb -> pb
            .description("Sets the payables accounts and the default bank account.")
            .permissions(FinancePermissions.AP_SETTINGS)
            .contextFactory(VendorProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, SettingsOutput.class))
            .step("Load the settings", QueryEntities.of(ApEntities.SETTINGS_DATASET, ctx -> current(), SETTINGS))
            .step("Load the accounts", QueryEntities.of(GlEntities.ACCOUNT_DATASET, ctx -> {
                SettingsInput input = ctx.get(INPUT, SettingsInput.class);
                Set<Object> codes = new LinkedHashSet<>();
                for (String code : new String[] {input.payableAccount(), input.discountAccount(),
                    input.useTaxAccount(), input.prepaymentAccount()}) {
                    if (code != null && !code.isBlank()) {
                        codes.add(code.trim());
                    }
                }
                return EntityQuery.builder().where(new QueryPredicate.In("accountCode", new ArrayList<>(codes)))
                    .limit(codes.size() + 1).build();
            }, ACCOUNTS))
            .step("Load the bank account", QueryEntities.of(BankEntities.BANK_ACCOUNT_DATASET,
                ctx -> VendorProcesses.eq("bankCode", VendorProcesses.code(ctx.get(INPUT, SettingsInput.class)
                    .defaultBank())), BANKS))
            .compute("Set the settings", (metadata, ctx) -> set(ctx)));

    public static final ProcessDefinition<ThresholdInput, ThresholdOutput, ProcessContext> THRESHOLD_PROCESS =
        ProcessDefinition.define(THRESHOLD_SET, 1, ThresholdInput.class, ThresholdOutput.class, ProcessContext.class,
            pb -> pb
                .description("Sets the 1099 reporting threshold of a tax year.")
                .permissions(FinancePermissions.FORM_1099_MAINTAIN)
                .contextFactory(VendorProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, ThresholdOutput.class))
                .step("Load the thresholds", QueryEntities.of(ApEntities.THRESHOLD_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("taxYear",
                        BigDecimal.valueOf(ctx.get(INPUT, ThresholdInput.class).taxYear()))).limit(10).build(),
                    THRESHOLDS))
                .compute("Set the threshold", (metadata, ctx) -> setThreshold(ctx)));

    /** The one settings row. */
    public static EntityQuery current() {
        return EntityQuery.builder().where(new QueryPredicate.Eq("settingsKey", ApEntities.SETTINGS_KEY)).limit(1)
            .build();
    }

    static void set(ProcessContext ctx) {
        SettingsInput input = ctx.get(INPUT, SettingsInput.class);
        Map<String, EntityInstance> accounts = new LinkedHashMap<>();
        for (EntityInstance account : list(ctx, ACCOUNTS)) {
            accounts.put(account.get("accountCode"), account);
        }
        check(ctx, accounts, "payableAccount", input.payableAccount(), "AP");
        check(ctx, accounts, "discountAccount", input.discountAccount(), null);
        check(ctx, accounts, "useTaxAccount", input.useTaxAccount(), null);
        check(ctx, accounts, "prepaymentAccount", input.prepaymentAccount(), null);
        String bank = VendorProcesses.code(input.defaultBank());
        if (bank != null && list(ctx, BANKS).stream().noneMatch(b -> Boolean.TRUE.equals(b.get("active")))) {
            ctx.reject(new Violation("defaultBank", UNKNOWN_BANK, "There is no active bank account " + bank,
                Map.of("bankCode", bank)));
        }
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("payableAccount", input.payableAccount().trim());
        values.put("discountAccount", VendorProcesses.trim(input.discountAccount()));
        values.put("useTaxAccount", VendorProcesses.trim(input.useTaxAccount()));
        values.put("prepaymentAccount", VendorProcesses.trim(input.prepaymentAccount()));
        values.put("defaultBank", bank);
        List<EntityInstance> found = list(ctx, SETTINGS);
        if (found.isEmpty()) {
            values.put("settingsKey", ApEntities.SETTINGS_KEY);
            Object id = ctx.changes().insert(ApEntities.SETTINGS, values);
            ctx.put(OUTPUT, new SettingsOutput(String.valueOf(id), true));
            return;
        }
        EntityInstance current = found.getFirst();
        Map<String, Object> changes = VendorProcesses.differences(current, values);
        if (!changes.isEmpty()) {
            ctx.changes().update(ApEntities.SETTINGS, current.id(), current.version(), changes);
        }
        ctx.put(OUTPUT, new SettingsOutput(String.valueOf(current.id()), !changes.isEmpty()));
    }

    private static void check(ProcessContext ctx, Map<String, EntityInstance> accounts, String field, String code,
        String controlClass) {
        if (code == null || code.isBlank()) {
            return;
        }
        EntityInstance account = accounts.get(code.trim());
        if (account == null) {
            ctx.reject(new Violation(field, UNKNOWN_ACCOUNT, "There is no account " + code.trim(),
                Map.of("accountCode", code.trim())));
            return;
        }
        Object actual = account.get("controlClass");
        if (controlClass == null ? actual != null : !controlClass.equals(actual)) {
            String expected = controlClass != null ? "the " + controlClass + " control account"
                : "an account that is no control account";
            ctx.reject(new Violation(field, WRONG_ACCOUNT, field + " must be " + expected + "; " + code.trim()
                + " is not", Map.of("accountCode", code.trim(), "field", field)));
        }
    }

    static void setThreshold(ProcessContext ctx) {
        ThresholdInput input = ctx.get(INPUT, ThresholdInput.class);
        List<String> forms;
        if (input.form1099() == null || input.form1099().isBlank()) {
            forms = ApEntities.FORM_1099_VALUES;
        } else {
            String form = VendorProcesses.code(input.form1099());
            if (!ApEntities.FORM_1099_VALUES.contains(form)) {
                ctx.reject(new Violation("form1099", INVALID_FORM, "form1099 must be one of "
                    + ApEntities.FORM_1099_VALUES, Map.of("value", input.form1099())));
                return;
            }
            forms = List.of(form);
        }
        boolean changed = false;
        for (String form : forms) {
            EntityInstance current = list(ctx, THRESHOLDS).stream().filter(t -> form.equals(t.get("form1099")))
                .findFirst().orElse(null);
            if (current == null) {
                ctx.changes().insert(ApEntities.THRESHOLD, Map.of("taxYear", BigDecimal.valueOf(input.taxYear()),
                    "form1099", form, "threshold", input.threshold()));
                changed = true;
            } else if (current.<BigDecimal>get("threshold").compareTo(input.threshold()) != 0) {
                ctx.changes().update(ApEntities.THRESHOLD, current.id(), current.version(),
                    Map.of("threshold", input.threshold()));
                changed = true;
            }
        }
        ctx.put(OUTPUT, new ThresholdOutput(input.taxYear(), forms, input.threshold(), changed));
    }

    private ApSettingsProcesses() {}
}

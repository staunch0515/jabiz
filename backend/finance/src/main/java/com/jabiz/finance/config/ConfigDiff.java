package com.jabiz.finance.config;

import com.jabiz.finance.config.ConfigPackage.Account;
import com.jabiz.finance.config.ConfigPackage.Jurisdiction;
import com.jabiz.finance.config.ConfigPackage.Layout;
import com.jabiz.finance.config.ConfigPackage.Rate;
import com.jabiz.finance.config.ConfigPackage.TaxCode;
import com.jabiz.finance.gl.AccountProcesses;
import com.jabiz.finance.report.CashFlowProcesses;
import com.jabiz.finance.report.StatementProcesses;
import com.jabiz.finance.tax.TaxProcesses;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What applying a configuration package to an environment would change (ROADMAP F10d): every difference, as people
 * read it, and the inputs of the finance processes that make each change, so the package is applied through the
 * same rules as changes made by hand. Accounts and rates the environment has and the package lacks are listed and
 * kept (F10 plan, D10); what no process can change (an account's financial type, a layout's statement) is a
 * problem that keeps the package from being proposed.
 */
public record ConfigDiff(List<Difference> differences, List<String> problems, Plan plan) {

    public static final String ACCOUNT = "ACCOUNT";
    public static final String JURISDICTION = "JURISDICTION";
    public static final String RATE = "RATE";
    public static final String TAX_CODE = "TAX_CODE";
    public static final String LAYOUT = "LAYOUT";
    public static final String REPORT_SETTINGS = "REPORT_SETTINGS";

    public static final String NEW = "NEW";
    public static final String CHANGED = "CHANGED";
    public static final String DEACTIVATED = "DEACTIVATED";
    public static final String REACTIVATED = "REACTIVATED";
    public static final String ONLY_HERE = "ONLY_HERE";

    /** One difference: what (area and key), how (action) and, for a change, the fields from and to. */
    public record Difference(String area, String key, String action, String detail) {}

    /** The calls that apply the package, in the order they are made. */
    public record Plan(List<AccountProcesses.AccountInput> accountsCreated,
        List<AccountProcesses.AccountChange> accountsChanged, List<AccountProcesses.AccountCode> accountsDeactivated,
        List<AccountProcesses.AccountCode> accountsReactivated, List<TaxProcesses.JurisdictionInput> jurisdictions,
        List<TaxProcesses.RateInput> rates, List<TaxProcesses.TaxCodeInput> taxCodes,
        List<StatementProcesses.PublishInput> layouts, CashFlowProcesses.SettingsInput reportSettings) {

        public boolean isEmpty() {
            return accountsCreated.isEmpty() && accountsChanged.isEmpty() && accountsDeactivated.isEmpty()
                && accountsReactivated.isEmpty() && jurisdictions.isEmpty() && rates.isEmpty() && taxCodes.isEmpty()
                && layouts.isEmpty() && reportSettings == null;
        }
    }

    /** The differences between {@code current} (the environment) and {@code wanted} (the package). */
    public static ConfigDiff of(ConfigPackage wanted, ConfigPackage current) {
        List<Difference> differences = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        // Accounts: new ones parents first, so a parent exists before its sub-accounts; then the changes.
        Map<String, Account> here = byKey(current.accounts(), Account::accountCode);
        Map<String, Account> there = byKey(wanted.accounts(), Account::accountCode);
        List<AccountProcesses.AccountInput> created = new ArrayList<>();
        List<AccountProcesses.AccountChange> changed = new ArrayList<>();
        List<AccountProcesses.AccountCode> deactivated = new ArrayList<>();
        List<AccountProcesses.AccountCode> reactivated = new ArrayList<>();
        for (Account a : parentsFirst(wanted.accounts(), there)) {
            Account old = here.get(a.accountCode());
            if (old == null) {
                created.add(new AccountProcesses.AccountInput(a.accountCode(), a.accountName(), a.financialType(),
                    a.normalBalance(), a.statementLine(), a.cashFlowClass(), a.controlClass(), a.clearing(),
                    a.requiredDimension(), a.parentCode(), a.summary()));
                differences.add(new Difference(ACCOUNT, a.accountCode(), NEW, a.accountName()));
                if (!a.active()) {
                    deactivated.add(new AccountProcesses.AccountCode(a.accountCode()));
                }
                continue;
            }
            if (!Objects.equals(old.financialType(), a.financialType())) {
                problems.add("Account " + a.accountCode() + " is " + old.financialType() + " here and "
                    + a.financialType() + " in the package; a financial type never changes");
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            String name = differs(fields, "accountName", old.accountName(), a.accountName());
            String balance = differs(fields, "normalBalance", old.normalBalance(), a.normalBalance());
            String line = differs(fields, "statementLine", old.statementLine(), a.statementLine());
            String cashFlow = differs(fields, "cashFlowClass", old.cashFlowClass(), a.cashFlowClass());
            String control = differs(fields, "controlClass", old.controlClass(), a.controlClass());
            String dimension = differs(fields, "requiredDimension", old.requiredDimension(), a.requiredDimension());
            String parent = differs(fields, "parentCode", old.parentCode(), a.parentCode());
            Boolean clearing = old.clearing() == a.clearing() ? null : a.clearing();
            if (clearing != null) {
                fields.put("clearing", old.clearing() + " → " + a.clearing());
            }
            Boolean summary = old.summary() == a.summary() ? null : a.summary();
            if (summary != null) {
                fields.put("summary", old.summary() + " → " + a.summary());
            }
            if (!fields.isEmpty()) {
                // An empty text clears an optional field; null leaves a field as it is.
                changed.add(new AccountProcesses.AccountChange(a.accountCode(), name, balance, line, cashFlow,
                    control, clearing, dimension, parent, summary));
                differences.add(new Difference(ACCOUNT, a.accountCode(), CHANGED, describe(fields)));
            }
            if (old.active() != a.active()) {
                (a.active() ? reactivated : deactivated).add(new AccountProcesses.AccountCode(a.accountCode()));
                differences.add(new Difference(ACCOUNT, a.accountCode(), a.active() ? REACTIVATED : DEACTIVATED,
                    a.accountName()));
            }
        }
        here.keySet().stream().filter(code -> !there.containsKey(code)).sorted()
            .forEach(code -> differences.add(new Difference(ACCOUNT, code, ONLY_HERE, here.get(code).accountName())));

        // Jurisdictions, then their rates, then the codes naming them (taxable ones first: a charge code must be one).
        Map<String, Jurisdiction> jurisdictionsHere = byKey(current.jurisdictions(), Jurisdiction::jurisdictionCode);
        List<TaxProcesses.JurisdictionInput> jurisdictions = new ArrayList<>();
        for (Jurisdiction j : wanted.jurisdictions()) {
            Jurisdiction old = jurisdictionsHere.get(j.jurisdictionCode());
            if (j.equals(old)) {
                continue;
            }
            jurisdictions.add(new TaxProcesses.JurisdictionInput(j.jurisdictionCode(), j.jurisdictionName(),
                j.level(), j.state(), j.active()));
            differences.add(old == null ? new Difference(JURISDICTION, j.jurisdictionCode(), NEW, j.jurisdictionName())
                : new Difference(JURISDICTION, j.jurisdictionCode(), CHANGED, describe(jurisdictionFields(old, j))));
        }
        Map<String, Rate> ratesHere = byKey(current.rates(), ConfigDiff::rateKey);
        Map<String, Rate> ratesThere = byKey(wanted.rates(), ConfigDiff::rateKey);
        List<TaxProcesses.RateInput> rates = new ArrayList<>();
        for (Rate r : wanted.rates()) {
            Rate old = ratesHere.get(rateKey(r));
            if (old != null && old.ratePercent().compareTo(r.ratePercent()) == 0) {
                continue;
            }
            // A rate the one in effect here that day already is changes nothing (setting it does nothing either).
            Rate inEffect = old != null ? null : current.rates().stream()
                .filter(h -> h.jurisdictionCode().equals(r.jurisdictionCode())
                    && !h.effectiveFrom().isAfter(r.effectiveFrom()))
                .max(Comparator.comparing(Rate::effectiveFrom)).orElse(null);
            if (inEffect != null && inEffect.ratePercent().compareTo(r.ratePercent()) == 0) {
                continue;
            }
            rates.add(new TaxProcesses.RateInput(r.jurisdictionCode(), r.effectiveFrom(), r.ratePercent()));
            differences.add(new Difference(RATE, rateKey(r), old == null ? NEW : CHANGED, old == null
                ? r.ratePercent().toPlainString() + "%"
                : "ratePercent: " + old.ratePercent().toPlainString() + " → " + r.ratePercent().toPlainString()));
        }
        ratesHere.keySet().stream().filter(key -> !ratesThere.containsKey(key)).sorted()
            .forEach(key -> differences.add(new Difference(RATE, key, ONLY_HERE,
                ratesHere.get(key).ratePercent().toPlainString() + "%")));
        Map<String, TaxCode> codesHere = byKey(current.taxCodes(), TaxCode::taxCode);
        List<TaxProcesses.TaxCodeInput> taxCodes = new ArrayList<>();
        for (TaxCode c : wanted.taxCodes().stream()
            .sorted(Comparator.comparing((TaxCode c) -> !"TAXABLE".equals(c.kind())).thenComparing(TaxCode::taxCode))
            .toList()) {
            TaxCode old = codesHere.get(c.taxCode());
            if (c.equals(old)) {
                continue;
            }
            taxCodes.add(new TaxProcesses.TaxCodeInput(c.taxCode(), c.description(), c.kind(), c.reason(), c.state(),
                c.jurisdictions().stream().map(j -> new TaxProcesses.JurisdictionPart(j, null, null, null, null))
                    .toList(), c.certificateRequired(), c.chargeCode(), c.active(), null));
            differences.add(old == null ? new Difference(TAX_CODE, c.taxCode(), NEW, c.description())
                : new Difference(TAX_CODE, c.taxCode(), CHANGED, describe(taxCodeFields(old, c))));
        }

        // A layout whose latest version differs is published again: a new version, the old ones kept.
        Map<String, Layout> layoutsHere = byKey(current.layouts(), Layout::layoutCode);
        List<StatementProcesses.PublishInput> layouts = new ArrayList<>();
        for (Layout l : wanted.layouts()) {
            Layout old = layoutsHere.get(l.layoutCode());
            if (l.equals(old)) {
                continue;
            }
            if (old != null && !old.statement().equals(l.statement())) {
                problems.add("Layout " + l.layoutCode() + " is a " + old.statement() + " layout here and a "
                    + l.statement() + " layout in the package; a layout keeps its statement");
                continue;
            }
            layouts.add(new StatementProcesses.PublishInput(l.layoutCode(), l.statement(), l.title(), l.rows().stream()
                .map(r -> new StatementProcesses.RowInput(r.lineCode(), r.label(), r.kind(), r.accounts(), r.sign(),
                    r.detail(), r.omitZero(), r.noteAccounts())).toList()));
            differences.add(new Difference(LAYOUT, l.layoutCode(), old == null ? NEW : CHANGED, old == null
                ? l.title() : layoutDetail(old, l)));
        }

        ConfigPackage.ReportSettings settings = wanted.reportSettings();
        CashFlowProcesses.SettingsInput settingsInput = null;
        if (!settings.equals(current.reportSettings())) {
            settingsInput = new CashFlowProcesses.SettingsInput(settings.interestAccounts(),
                settings.interestPayableAccounts(), settings.incomeTaxAccounts(), settings.incomeTaxPayableAccounts(),
                settings.receivablesAccounts(), settings.accruedAccounts(), settings.debtAccounts());
            differences.add(new Difference(REPORT_SETTINGS, "REPORTS", current.reportSettings() == null ? NEW : CHANGED,
                current.reportSettings() == null ? "" : describe(settingsFields(current.reportSettings(), settings))));
        }
        return new ConfigDiff(List.copyOf(differences), List.copyOf(problems), new Plan(List.copyOf(created),
            List.copyOf(changed), List.copyOf(deactivated), List.copyOf(reactivated), List.copyOf(jurisdictions),
            List.copyOf(rates), List.copyOf(taxCodes), List.copyOf(layouts), settingsInput));
    }

    /** The differences that the package would apply (those kept as they are, ONLY_HERE, left out). */
    public long changes() {
        return differences.stream().filter(d -> !ONLY_HERE.equals(d.action())).count();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    /** The new value for a change of an optional text: null when equal, an empty text to clear it. */
    private static String differs(Map<String, String> fields, String field, String old, String wanted) {
        if (Objects.equals(blankToNull(old), blankToNull(wanted))) {
            return null;
        }
        fields.put(field, shown(old) + " → " + shown(wanted));
        return wanted == null ? "" : wanted;
    }

    private static List<Account> parentsFirst(List<Account> accounts, Map<String, Account> byCode) {
        Map<String, Integer> depth = new HashMap<>();
        for (Account a : accounts) {
            depth(a.accountCode(), byCode, depth, 0);
        }
        return accounts.stream().sorted(Comparator.comparing((Account a) -> depth.get(a.accountCode()))
            .thenComparing(Account::accountCode)).toList();
    }

    private static int depth(String code, Map<String, Account> byCode, Map<String, Integer> depth, int guard) {
        Integer known = depth.get(code);
        if (known != null) {
            return known;
        }
        Account a = byCode.get(code);
        // A parent outside the package must be in the environment already; a cycle stops at the chart's size.
        int d = a == null || a.parentCode() == null || guard > byCode.size() ? 0
            : 1 + depth(a.parentCode(), byCode, depth, guard + 1);
        depth.put(code, d);
        return d;
    }

    private static Map<String, String> jurisdictionFields(Jurisdiction old, Jurisdiction j) {
        Map<String, String> fields = new LinkedHashMap<>();
        differs(fields, "jurisdictionName", old.jurisdictionName(), j.jurisdictionName());
        differs(fields, "level", old.level(), j.level());
        differs(fields, "state", old.state(), j.state());
        if (old.active() != j.active()) {
            fields.put("active", old.active() + " → " + j.active());
        }
        return fields;
    }

    private static Map<String, String> taxCodeFields(TaxCode old, TaxCode c) {
        Map<String, String> fields = new LinkedHashMap<>();
        differs(fields, "description", old.description(), c.description());
        differs(fields, "kind", old.kind(), c.kind());
        differs(fields, "reason", old.reason(), c.reason());
        differs(fields, "state", old.state(), c.state());
        differs(fields, "jurisdictions", String.join(",", old.jurisdictions()), String.join(",", c.jurisdictions()));
        if (old.certificateRequired() != c.certificateRequired()) {
            fields.put("certificateRequired", old.certificateRequired() + " → " + c.certificateRequired());
        }
        differs(fields, "chargeCode", old.chargeCode(), c.chargeCode());
        if (old.active() != c.active()) {
            fields.put("active", old.active() + " → " + c.active());
        }
        return fields;
    }

    private static Map<String, String> settingsFields(ConfigPackage.ReportSettings old,
        ConfigPackage.ReportSettings s) {
        Map<String, String> fields = new LinkedHashMap<>();
        differs(fields, "interestAccounts", old.interestAccounts(), s.interestAccounts());
        differs(fields, "interestPayableAccounts", old.interestPayableAccounts(), s.interestPayableAccounts());
        differs(fields, "incomeTaxAccounts", old.incomeTaxAccounts(), s.incomeTaxAccounts());
        differs(fields, "incomeTaxPayableAccounts", old.incomeTaxPayableAccounts(), s.incomeTaxPayableAccounts());
        differs(fields, "receivablesAccounts", old.receivablesAccounts(), s.receivablesAccounts());
        differs(fields, "accruedAccounts", old.accruedAccounts(), s.accruedAccounts());
        differs(fields, "debtAccounts", old.debtAccounts(), s.debtAccounts());
        return fields;
    }

    private static String layoutDetail(Layout old, Layout l) {
        Map<String, ConfigPackage.Row> before = byKey(old.rows(), ConfigPackage.Row::lineCode);
        Map<String, ConfigPackage.Row> after = byKey(l.rows(), ConfigPackage.Row::lineCode);
        long added = after.keySet().stream().filter(k -> !before.containsKey(k)).count();
        long removed = before.keySet().stream().filter(k -> !after.containsKey(k)).count();
        long rowsChanged = after.keySet().stream().filter(k -> before.containsKey(k)
            && !before.get(k).equals(after.get(k))).count();
        List<String> parts = new ArrayList<>();
        if (!Objects.equals(old.title(), l.title())) {
            parts.add("title: " + old.title() + " → " + l.title());
        }
        parts.add("rows added " + added + ", removed " + removed + ", changed " + rowsChanged);
        if (added == 0 && removed == 0 && rowsChanged == 0 && Objects.equals(old.title(), l.title())) {
            parts.set(parts.size() - 1, "rows reordered");
        }
        return String.join("; ", parts);
    }

    private static String rateKey(Rate r) {
        return r.jurisdictionCode() + "@" + r.effectiveFrom();
    }

    private static String describe(Map<String, String> fields) {
        return fields.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue())
            .collect(Collectors.joining("; "));
    }

    private static String shown(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static <T> Map<String, T> byKey(List<T> items, Function<T, String> key) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T item : items) {
            map.put(key.apply(item), item);
        }
        return map;
    }
}

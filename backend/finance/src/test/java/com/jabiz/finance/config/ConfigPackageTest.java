package com.jabiz.finance.config;

import com.jabiz.finance.config.ConfigPackage.Account;
import com.jabiz.finance.config.ConfigPackage.Jurisdiction;
import com.jabiz.finance.config.ConfigPackage.Layout;
import com.jabiz.finance.config.ConfigPackage.Rate;
import com.jabiz.finance.config.ConfigPackage.ReportSettings;
import com.jabiz.finance.config.ConfigPackage.Row;
import com.jabiz.finance.config.ConfigPackage.TaxCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** The configuration package's text, hash and differences (ROADMAP F10d). */
class ConfigPackageTest {

    private static final Instant AT = Instant.parse("2026-03-02T15:00:00Z");
    private static final ReportSettings SETTINGS = new ReportSettings("7100", null, "8000", "2400", "1200", "2100",
        "2500-2599");

    static Account account(String code, String name, String type, String parent) {
        return new Account(code, name, type, "ASSET".equals(type) || "EXPENSE".equals(type) ? "DEBIT" : "CREDIT",
            "Line " + code, null, null, false, null, parent, false, true);
    }

    static ConfigPackage pkg(List<Account> accounts, List<Rate> rates, List<TaxCode> codes, List<Layout> layouts,
        ReportSettings settings) {
        return new ConfigPackage(ConfigPackage.FORMAT, "test", AT, accounts,
            List.of(new Jurisdiction("TX", "Texas", "STATE", "TX", true)), rates, codes, layouts, settings);
    }

    static final List<Account> CHART = List.of(account("1000", "Cash", "ASSET", null),
        account("6000", "Expenses", "EXPENSE", null), account("6400", "Professional Fees", "EXPENSE", "6000"));
    static final List<Rate> RATES = List.of(new Rate("TX", LocalDate.parse("2026-01-01"), new BigDecimal("6.2500")));
    static final List<TaxCode> CODES = List.of(new TaxCode("TX", "Texas", "TAXABLE", null, "TX", List.of("TX"), false,
        null, true), new TaxCode("RESALE", "Resale", "EXEMPT", "RESALE", "TX", List.of(), true, "TX", true));
    static final Layout IS = new Layout("IS", "INCOME_STATEMENT", "Income statement", List.of(
        new Row("REVENUE", "Revenue", "LINE", "4000-4999", -1, false, false, null),
        new Row("NET_INCOME", "Net income", "TOTAL", null, 1, false, false, null)));

    @Test
    void theSameConfigurationAlwaysGivesTheSameTextAndHash() {
        ConfigPackage one = pkg(CHART, RATES, CODES, List.of(IS), SETTINGS);
        List<Account> shuffled = new ArrayList<>(CHART);
        Collections.reverse(shuffled);
        List<TaxCode> codes = new ArrayList<>(CODES);
        Collections.reverse(codes);
        ConfigPackage two = pkg(shuffled, RATES, codes, List.of(IS), SETTINGS);
        assertThat(two.text()).isEqualTo(one.text()).doesNotContain("\n").startsWith("{\"format\":\"" + ConfigPackage.FORMAT);
        assertThat(ConfigPackage.sha256(two.text())).isEqualTo(ConfigPackage.sha256(one.text())).hasSize(64);
        // A form may change the line ends and add blanks around it: the same text.
        assertThat(ConfigPackage.sha256("\r\n " + one.text() + "\r\n")).isEqualTo(ConfigPackage.sha256(one.text()));
        assertThat(ConfigPackage.read(one.text())).isEqualTo(one);
        // Scale is part of a rate.
        assertThat(one.text()).contains("6.2500");
    }

    @Test
    void onlyAPackageOfItsFormatIsRead() {
        String text = pkg(CHART, RATES, CODES, List.of(IS), SETTINGS).text();
        assertThatThrownBy(() -> ConfigPackage.read("{not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigPackage.read(text.replace(ConfigPackage.FORMAT, "other/1")))
            .hasMessageContaining("format");
        assertThatThrownBy(() -> ConfigPackage.read(text.replace("\"source\"", "\"surprise\":1,\"source\"")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigPackage.read(text.replace("\"reportSettings\":{", "\"reportSettings\":null,\"x\":{")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPackageMissingWhatItNeedsIsNotRead() {
        String text = pkg(CHART, RATES, CODES, List.of(IS), SETTINGS).text();
        assertThatThrownBy(() -> ConfigPackage.read("null")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigPackage.read(text.replace("\"ratePercent\":6.2500", "\"ratePercent\":null")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigPackage.read(text.replace("\"accountCode\":\"1000\"", "\"accountCode\":null")))
            .isInstanceOf(IllegalArgumentException.class);
        // A code without jurisdictions, a layout without rows: read as empty, refused by their processes' rules.
        ConfigPackage read = ConfigPackage.read(text.replace("\"jurisdictions\":[\"TX\"]", "\"jurisdictions\":null"));
        assertThat(read.taxCodes()).filteredOn(c -> "TX".equals(c.taxCode())).singleElement()
            .satisfies(c -> assertThat(c.jurisdictions()).isEmpty());
        String noRows = pkg(CHART, RATES, CODES, List.of(new Layout("IS", "INCOME_STATEMENT", "Empty", null)),
            SETTINGS).text();
        assertThat(ConfigPackage.read(noRows).layouts().getFirst().rows()).isEmpty();
    }

    @Test
    void theProcessesRulesAreCheckedOnThePackagesInputs() {
        ConfigPackage here = pkg(CHART, RATES, CODES, List.of(IS), SETTINGS);
        List<Account> wanted = new ArrayList<>(CHART);
        wanted.add(new Account("6450", " ", "EXPENSE", "DEBIT", "Operating expenses", null, null, false, null, null,
            false, true));
        Layout empty = new Layout("EMPTY", "INCOME_STATEMENT", "Nothing", List.of());
        ConfigDiff diff = ConfigDiff.of(pkg(wanted, RATES, CODES, List.of(IS, empty), SETTINGS), here);
        assertThat(ConfigProcesses.invalidInputs(diff.plan())).containsExactly(
            "account 6450: accountName must not be blank", "layout EMPTY: rows must not be empty");
    }

    @Test
    void aRateTheOneInEffectAlreadyIsChangesNothing() {
        ConfigPackage here = pkg(CHART, RATES, CODES, List.of(IS), SETTINGS);
        List<Rate> wanted = List.of(RATES.getFirst(), new Rate("TX", LocalDate.parse("2026-07-01"),
            new BigDecimal("6.25")));
        assertThat(ConfigDiff.of(pkg(CHART, wanted, CODES, List.of(IS), SETTINGS), here).differences()).isEmpty();
    }

    @Test
    void theSameConfigurationChangesNothing() {
        ConfigPackage here = pkg(CHART, RATES, CODES, List.of(IS), SETTINGS);
        ConfigDiff diff = ConfigDiff.of(here, here);
        assertThat(diff.differences()).isEmpty();
        assertThat(diff.plan().isEmpty()).isTrue();
        assertThat(diff.changes()).isZero();
    }

    @Test
    void newAccountsComeParentsFirstChangesNameTheirFieldsAndAccountsOnlyHereAreKept() {
        ConfigPackage here = pkg(List.of(account("1000", "Cash", "ASSET", null), account("1999", "Old", "ASSET",
            null), new Account("6000", "Expenses", "EXPENSE", "DEBIT", "Line 6000", "OPERATING", null, false, null,
                null, true, true)), RATES, CODES, List.of(IS), SETTINGS);
        List<Account> wanted = new ArrayList<>(List.of(account("1000", "Cash and equivalents", "ASSET", null),
            account("6400", "Professional Fees", "EXPENSE", "6100"), account("6100", "Services", "EXPENSE", "6000"),
            account("6000", "Expenses", "EXPENSE", null)));
        ConfigDiff diff = ConfigDiff.of(pkg(wanted, RATES, CODES, List.of(IS), SETTINGS), here);
        assertThat(diff.problems()).isEmpty();
        assertThat(diff.plan().accountsCreated()).extracting(a -> a.accountCode()).containsExactly("6100", "6400");
        assertThat(diff.plan().accountsChanged()).extracting(c -> c.accountCode(), c -> c.accountName(),
            c -> c.cashFlowClass(), c -> c.summary(), c -> c.statementLine())
            .containsExactly(tuple("1000", "Cash and equivalents", null, null, null),
                tuple("6000", null, "", false, null));
        assertThat(diff.differences()).extracting(d -> d.area() + " " + d.key() + " " + d.action()).containsExactly(
            "ACCOUNT 1000 CHANGED", "ACCOUNT 6000 CHANGED", "ACCOUNT 6100 NEW", "ACCOUNT 6400 NEW",
            "ACCOUNT 1999 ONLY_HERE");
        assertThat(diff.differences().get(1).detail()).isEqualTo(
            "cashFlowClass: OPERATING → (none); summary: true → false");
        assertThat(diff.changes()).isEqualTo(4);
    }

    @Test
    void anAccountsActivityFollowsThePackageAndItsTypeNeverChanges() {
        ConfigPackage here = pkg(CHART, RATES, CODES, List.of(IS), SETTINGS);
        List<Account> wanted = new ArrayList<>(CHART);
        Account fees = CHART.get(2);
        wanted.set(2, new Account(fees.accountCode(), fees.accountName(), fees.financialType(), fees.normalBalance(),
            fees.statementLine(), null, null, false, null, "6000", false, false));
        assertThat(ConfigDiff.of(pkg(wanted, RATES, CODES, List.of(IS), SETTINGS), here).plan().accountsDeactivated())
            .extracting(a -> a.accountCode()).containsExactly("6400");
        assertThat(ConfigDiff.of(here, pkg(wanted, RATES, CODES, List.of(IS), SETTINGS)).plan().accountsReactivated())
            .extracting(a -> a.accountCode()).containsExactly("6400");
        wanted.set(0, account("1000", "Cash", "LIABILITY", null));
        assertThat(ConfigDiff.of(pkg(wanted, RATES, CODES, List.of(IS), SETTINGS), here).problems())
            .singleElement().asString().contains("1000", "ASSET", "LIABILITY");
    }

    @Test
    void ratesAreSetWhereNewOrDifferentAndCodesTaxableFirst() {
        ConfigPackage here = pkg(CHART, List.of(new Rate("TX", LocalDate.parse("2025-01-01"), new BigDecimal("6.0000")),
            RATES.getFirst()), List.of(), List.of(IS), SETTINGS);
        List<Rate> wanted = List.of(new Rate("TX", LocalDate.parse("2026-01-01"), new BigDecimal("6.25")),
            new Rate("TX", LocalDate.parse("2026-07-01"), new BigDecimal("6.5000")));
        ConfigDiff diff = ConfigDiff.of(pkg(CHART, wanted, CODES, List.of(IS), SETTINGS), here);
        // 6.25 and 6.2500 are the same rate.
        assertThat(diff.plan().rates()).extracting(r -> r.effectiveFrom().toString(), r -> r.ratePercent())
            .containsExactly(tuple("2026-07-01", new BigDecimal("6.5000")));
        assertThat(diff.differences()).filteredOn(d -> ConfigDiff.RATE.equals(d.area()))
            .extracting(d -> d.key() + " " + d.action()).containsExactly("TX@2026-07-01 NEW", "TX@2025-01-01 ONLY_HERE");
        // RESALE charges TX when the certificate is missing: TX is saved first.
        assertThat(diff.plan().taxCodes()).extracting(c -> c.taxCode()).containsExactly("TX", "RESALE");
        assertThat(diff.plan().taxCodes().getFirst().jurisdictions()).extracting(j -> j.jurisdictionCode())
            .containsExactly("TX");
    }

    @Test
    void aChangedLayoutIsPublishedAgainButKeepsItsStatement() {
        ConfigPackage here = pkg(CHART, RATES, CODES, List.of(IS), SETTINGS);
        Layout changed = new Layout("IS", "INCOME_STATEMENT", "Statement of operations", List.of(IS.rows().getFirst(),
            new Row("OTHER", "Other income", "LINE", "7000-7099", -1, true, true, null), IS.rows().get(1)));
        ConfigDiff diff = ConfigDiff.of(pkg(CHART, RATES, CODES, List.of(changed), SETTINGS), here);
        assertThat(diff.plan().layouts()).singleElement().satisfies(p -> {
            assertThat(p.title()).isEqualTo("Statement of operations");
            assertThat(p.rows()).extracting(r -> r.lineCode()).containsExactly("REVENUE", "OTHER", "NET_INCOME");
        });
        assertThat(diff.differences()).singleElement().satisfies(d -> assertThat(d.detail())
            .isEqualTo("title: Income statement → Statement of operations; rows added 1, removed 0, changed 0"));
        Layout other = new Layout("IS", "BALANCE_SHEET", "Income statement", IS.rows());
        assertThat(ConfigDiff.of(pkg(CHART, RATES, CODES, List.of(other), SETTINGS), here).problems())
            .singleElement().asString().contains("keeps its statement");
    }

    @Test
    void theReportSettingsAreSetWhenTheyDiffer() {
        ConfigPackage here = pkg(CHART, RATES, CODES, List.of(IS), SETTINGS);
        ReportSettings wanted = new ReportSettings("7100", null, "8000", "2400", "1200", "2100", "2500-2699");
        ConfigDiff diff = ConfigDiff.of(pkg(CHART, RATES, CODES, List.of(IS), wanted), here);
        assertThat(diff.plan().reportSettings().debtAccounts()).isEqualTo("2500-2699");
        assertThat(diff.differences()).singleElement().satisfies(d -> assertThat(d.detail())
            .isEqualTo("debtAccounts: 2500-2599 → 2500-2699"));
    }
}

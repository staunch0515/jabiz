package com.jabiz.finance.it;

import com.jabiz.finance.ap.ApEntities;
import com.jabiz.finance.ap.ApSettingsProcesses;
import com.jabiz.finance.ap.BillProcesses;
import com.jabiz.finance.ap.VendorProcesses;
import com.jabiz.finance.fa.AssetClassProcesses;
import com.jabiz.finance.fa.AssetEntities;
import com.jabiz.finance.fa.AssetScheduleProcesses;
import com.jabiz.finance.fa.DepreciationProcesses;
import com.jabiz.finance.gl.AccountTypes;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.runtime.security.SecurityEntities;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-SCN-01, setting up the books, in the order the scenario has it: users and their roles without a conflict; the
 * chart, the fiscal year 2026 and the approval rules; customers, vendors, tax codes, exchange rates and the asset
 * classes; the opening balances (an unbalanced file refused first), the open receivables and payables and the legacy
 * asset register. The trial balance at 2025-12-31 is FIN-EXP-01, and the receivables, payables and asset subledgers
 * equal their control accounts; the payables clerk sees TINs masked. Then FIN-EXP-11: January's bill for FA-003 and
 * the depreciation run, the register, the schedule and the roll-forward as the expected results have them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FinScn01IT extends FinanceItSupport {

    private String financeController() {
        return inRoles("controller", FinanceRoles.CONTROLLER);
    }

    private String clerk() {
        return inRoles("ap-clerk", FinanceRoles.PAYABLES_CLERK);
    }

    @Test
    @Order(1)
    void theBooksAreSetUp() throws IOException {
        String controller = financeController();
        String clerk = clerk();
        String migrator = as("migrator", "fin.migration", "fin.import", "fin.ap.read");

        // Step 2 first, for the roles and the segregation rules: the chart, the year and the approval rules.
        openBooks();

        // Step 1: the administrator's users and their roles; the conflict report names none of them.
        String admin = as("admin", "*");
        Map<String, String> people = Map.of("scn01-controller", FinanceRoles.CONTROLLER,
            "scn01-accountant", FinanceRoles.ACCOUNTANT, "scn01-clerk", FinanceRoles.PAYABLES_CLERK,
            "scn01-treasurer", FinanceRoles.TREASURER);
        people.forEach((name, role) -> {
            String userId = (String) ok("SEC_USER_CREATE", admin, Map.of("userName", name, "displayName", name,
                "password", "password-123")).get("userId");
            post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", admin, Map.of("changes",
                List.of(Map.of("action", "INSERT", "attributes", Map.of("userId", userId, "roleId",
                    find(SecurityEntities.ROLE_DATASET, "roleCode", role).getFirst().get("roleId"))))))
                .expectStatus().isOk();
        });
        List<Map<String, Object>> conflicts = get("/api/sod/conflicts", controller).expectStatus().isOk()
            .expectBody(LIST).returnResult().getResponseBody();
        assertThat(conflicts).noneMatch(row -> people.containsKey(String.valueOf(row.get("userName"))));

        // Step 3: currencies and rates, tax codes, customers, vendors and the asset classes.
        post("/api/datasets/" + GlEntities.CURRENCY_DATASET + "/commit", controller(), Map.of("changes",
            List.of(Map.of("action", "INSERT", "attributes", Map.of("currencyCode", "EUR", "currencyName", "Euro",
                "minorUnits", 2, "active", true))))).expectStatus().isOk();
        importCsv("finance.fx_rates", controller, sampleText("fx-rates.csv"), "commit",
            Map.of("columns", Map.of("rateDate", "date", "rate", "eur_usd"),
                "constants", Map.of("fromCurrency", "EUR", "toCurrency", "USD")), null, 200);
        importCsv("finance.tax_codes", controller, sampleText("tax-codes.csv"), "commit", null,
            Map.of("ratesFrom", "2025-01-01"), 200);
        importCsv("finance.customers", controller, sampleText("customers.csv"), "commit", null, null, 200);
        importCsv("finance.vendors", clerk, sampleText("vendors.csv"), "commit", null, null, 200);
        ok(AssetClassProcesses.CLASS_SAVE, controller, assetClass("MACH", "Machinery and equipment", "1500", "SL", 60));
        ok(AssetClassProcesses.CLASS_SAVE, controller, assetClass("VEH", "Vehicles", "1510", "DDB", 84));
        ok(AssetClassProcesses.CLASS_SAVE, controller, assetClass("COMP", "Computer equipment", "1520", "SL", 36));

        // Step 4: an unbalanced opening file is refused, then the sample's is posted.
        String opening = sampleText("opening-balances.csv");
        assertThat(issues(importCsv("finance.opening_balances", controller,
            opening.replace("2025-12-31,2400,,8000.00", "2025-12-31,2400,,8000.50"), "commit", null, null, 422)))
            .contains("0:FIN_JOURNAL_UNBALANCED");
        importCsv("finance.opening_balances", controller, opening, "commit", null, null, 200);
        // The accounts the sample chart lacks, which the controller adds (design Q4, F4 and F6 plans).
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", "1250", "accountName", "Unapplied Cash",
            "financialType", AccountTypes.fromChart("Liability"), "normalBalance",
            AccountTypes.normalBalanceFromChart("C"), "statementLine", "Accrued liabilities", "clearing", true));
        account("4950", "Sales Discounts", "Revenue", "D");
        account("5900", "Purchase Discounts", "Expense", "C");
        account("2210", "Use Tax Payable", "Liability", "C");
        account("1310", "Vendor Prepayments", "Asset", "D");
        account("7400", "Gain or Loss on Disposal of Assets", "Other", "D");
        ok("FIN_AR_SETTINGS_SET", controller, Map.of("receivableAccount", "1200", "allowanceAccount", "1210",
            "returnsAccount", "4900", "salesTaxAccount", "2200", "discountAccount", "4950",
            "unappliedCashAccount", "1250", "lossRateCurrent", "1", "lossRate1", "5"));
        ok(ApSettingsProcesses.SET, controller, Map.of("payableAccount", "2000", "discountAccount", "5900",
            "useTaxAccount", "2210", "prepaymentAccount", "1310"));
        ok(AssetClassProcesses.SETTINGS_SET, controller, Map.of("gainLossAccount", "7400"));
        importCsv("finance.open_receivables", controller, sampleText("open-receivables.csv"), "commit", null, null,
            200);
        importCsv("finance.open_payables", migrator, sampleText("open-payables.csv"), "commit", null, null, 200);
        importCsv("finance.fixed_assets", migrator, sampleText("fixed-assets.csv"), "commit", null, null, 200);

        // Expected: the trial balance at 2025-12-31 is FIN-EXP-01.
        Map<String, BigDecimal> balances = ledgerBalances("2025-12-31");
        Map<String, BigDecimal[]> expected = expectedOpeningTrialBalance();
        assertThat(balances.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((code, sides) -> assertThat(balances.get(code)).as(code)
            .isEqualByComparingTo(sides[0].subtract(sides[1])));

        // The subledgers equal their control accounts: receivables, payables, assets.
        BigDecimal receivables = report("finance.ar.aging", controller, Map.of("agingDate", "2025-12-31")).stream()
            .map(r -> amount(r.get("openAmountUsd"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(receivables).isEqualByComparingTo(balances.get("1200"));
        BigDecimal payables = report("finance.ap.aging", controller, Map.of("agingDate", "2025-12-31")).stream()
            .map(r -> amount(r.get("openAmount"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(payables.negate()).isEqualByComparingTo(balances.get("2000"));
        assertAssetsEqualTheLedger("2025-12-31", balances);

        // The payables clerk sees a TIN masked.
        ok(VendorProcesses.TAX_SAVE, clerk, Map.of("vendorCode", "V200", "tinType", "EIN", "tin", "45-1234567"));
        assertThat(find(ApEntities.TAX_INFO_DATASET, "vendorCode", "V200").getFirst())
            .containsEntry("tin", "**-***4567");
    }

    @Test
    @Order(2)
    void januarysAssetsAreAsFinExp11Has() throws IOException {
        String controller = financeController();
        String accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);
        Map<String, Object> bill = new HashMap<>(Map.of("vendorCode", "V700", "vendorInvoiceNo", "TS-5520",
            "invoiceDate", "2026-01-15", "lines", List.of(Map.of("description", "Application server",
                "amount", "12000.00", "account", "1520"))));
        String clerk = clerk();
        ok(BillProcesses.POST, clerk, Map.of("billId", ok(BillProcesses.SAVE, clerk, bill).get("billId")));
        // FIN-SCN-08 step 1: the run posts DEP-2601; a second run posts nothing.
        assertThat(ok(DepreciationProcesses.RUN, accountant, Map.of("periodKey", "2026-01")))
            .containsEntry("runNo", "DEP-2601").containsEntry("posted", true);
        assertThat(ok(DepreciationProcesses.RUN, accountant, Map.of("periodKey", "2026-01")))
            .containsEntry("posted", false);
        assertThat(postingLines("DEP-2601")).isEqualTo(expectedDocuments("DEP-2601", "depreciation").get("DEP-2601"));

        // FIN-EXP-11, asset by asset: cost, January's depreciation, accumulated and net book value.
        Map<String, Map<String, Object>> register = byAsset(report("finance.fa.register", controller,
            Map.of("asOf", "2026-01-31")));
        Map<String, Map<String, Object>> january = byAsset(report("finance.fa.depreciation_schedule", controller,
            Map.of("fromPeriod", "2026-01", "toPeriod", "2026-01")));
        List<String[]> rows = expectedRows("FIN-EXP-11");
        assertThat(rows).hasSize(3);
        for (String[] row : rows) {
            String asset = row[0];
            assertThat(register.get(asset)).as(asset).containsEntry("costAccount", row[2])
                .containsEntry("inServiceDate", row[4]).containsEntry("status", "IN_SERVICE");
            assertThat(amount(register.get(asset).get("cost"))).as(asset).isEqualByComparingTo(money(row[3]));
            assertThat(amount(january.get(asset).get("amount"))).as(asset).isEqualByComparingTo(money(row[8]));
            assertThat(amount(register.get(asset).get("accumulated"))).as(asset).isEqualByComparingTo(money(row[9]));
            assertThat(amount(register.get(asset).get("netBookValue"))).as(asset)
                .isEqualByComparingTo(money(row[10]));
        }
        assertAssetsEqualTheLedger("2026-01-31", ledgerBalances("2026-01-31"));

        // FIN-FA-009 acceptance 1: cost 190,000.00 to 202,000.00, accumulated depreciation 58,000.00 to 62,000.00.
        List<Map<String, Object>> rollForward = report("finance.fa.roll_forward", controller,
            Map.of("from", "2026-01-01", "to", "2026-01-31"));
        Function<String, BigDecimal> total = column -> rollForward.stream().map(r -> amount(r.get(column)))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total.apply("costOpening")).isEqualByComparingTo("190000.00");
        assertThat(total.apply("additions")).isEqualByComparingTo("12000.00");
        assertThat(total.apply("disposals")).isEqualByComparingTo("0.00");
        assertThat(total.apply("costClosing")).isEqualByComparingTo("202000.00");
        assertThat(total.apply("accumulatedOpening")).isEqualByComparingTo("58000.00");
        assertThat(total.apply("depreciation")).isEqualByComparingTo("4000.00");
        assertThat(total.apply("accumulatedClosing")).isEqualByComparingTo("62000.00");

        // The months ahead: FA-003 333.33 a month to the end of its life, exactly 12,000.00 in all.
        String fa003 = (String) find(AssetEntities.ASSET_DATASET, "assetNo", "FA-003").getFirst().get("assetId");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ahead = (List<Map<String, Object>>) ok(AssetScheduleProcesses.PROJECT, controller,
            Map.of("assetId", fa003, "months", 1200)).get("months");
        assertThat(ahead).hasSize(35);
        assertThat(ahead.getFirst()).containsEntry("periodKey", "2026-02");
        assertThat(amount(ahead.getFirst().get("amount"))).isEqualByComparingTo("333.33");
        assertThat(amount(ahead.getLast().get("accumulated"))).isEqualByComparingTo("12000.00");
        assertThat(amount(ahead.getLast().get("netBookValue"))).isEqualByComparingTo("0.00");
        // Projecting writes nothing; reading the register needs the asset permission.
        run(AssetScheduleProcesses.PROJECT, clerk, Map.of("assetId", fa003)).expectStatus().isForbidden();
        report403("finance.fa.register", clerk);
    }

    /** The register's cost by cost account and accumulated depreciation equal the ledger on the day. */
    private void assertAssetsEqualTheLedger(String day, Map<String, BigDecimal> balances) {
        Map<String, BigDecimal> cost = new TreeMap<>();
        BigDecimal accumulated = BigDecimal.ZERO;
        for (Map<String, Object> row : report("finance.fa.register", financeController(), Map.of("asOf", day))) {
            cost.merge((String) row.get("costAccount"), amount(row.get("cost")), BigDecimal::add);
            accumulated = accumulated.add(amount(row.get("accumulated")));
        }
        for (String account : List.of("1500", "1510", "1520")) {
            assertThat(cost.getOrDefault(account, BigDecimal.ZERO)).as(day + " " + account)
                .isEqualByComparingTo(balances.getOrDefault(account, BigDecimal.ZERO));
        }
        assertThat(accumulated.negate()).as(day + " 1590").isEqualByComparingTo(balances.get("1590"));
    }

    private void report403(String template, String authorization) {
        post("/api/queries/" + template, authorization, Map.of("params", Map.of("asOf", "2026-01-31")))
            .expectStatus().isForbidden();
    }

    private static Map<String, Map<String, Object>> byAsset(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byAsset = new LinkedHashMap<>();
        rows.forEach(r -> byAsset.put((String) r.get("assetNo"), r));
        return byAsset;
    }

    private void account(String code, String name, String type, String balance) {
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", code, "accountName", name,
            "financialType", AccountTypes.fromChart(type), "normalBalance", AccountTypes.normalBalanceFromChart(balance),
            "statementLine", name));
    }

    private static Map<String, Object> assetClass(String code, String name, String cost, String method, int life) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("classCode", code);
        input.put("className", name);
        input.put("costAccount", cost);
        input.put("accumulatedAccount", "1590");
        input.put("expenseAccount", "6700");
        input.put("method", method);
        input.put("lifeMonths", life);
        input.put("convention", "FULL_MONTH");
        input.put("threshold", "2500.00");
        return input;
    }
}

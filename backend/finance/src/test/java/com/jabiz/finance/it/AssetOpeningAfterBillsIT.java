package com.jabiz.finance.it;

import com.jabiz.finance.ap.ApSettingsProcesses;
import com.jabiz.finance.ap.BillProcesses;
import com.jabiz.finance.fa.AssetClassProcesses;
import com.jabiz.finance.fa.AssetEntities;
import com.jabiz.finance.fa.AssetOpeningProcesses;
import com.jabiz.finance.gl.AccountTypes;
import com.jabiz.finance.setup.FinanceRoles;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The legacy register brought over after January's bills (ROADMAP F6a, F6 plan D9): the application server came by
 * BILL-TS-5520 with no class yet, after another bill took FA-003, so it is FA-004; the register's row of it (FA-003 in
 * the legacy file) is that asset by its account, cost and date, and gives it the terms of its class. A row that is no
 * registered asset and asks other terms refuses the whole file.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class AssetOpeningAfterBillsIT extends FinanceItSupport {

    @Test
    void theRegistersLaterRowGivesTheBillsAssetItsTerms() {
        String controller = inRoles("controller", FinanceRoles.CONTROLLER);
        String clerk = inRoles("ap-clerk", FinanceRoles.PAYABLES_CLERK);
        String migrator = as("migrator", "fin.migration", "fin.import");
        openBooks();
        importCsv("finance.opening_balances", controller, sampleText("opening-balances.csv"), "commit", null, null,
            200);
        importCsv("finance.vendors", clerk, sampleText("vendors.csv"), "commit", null, null, 200);
        for (String[] a : new String[][] {{"5900", "Purchase Discounts", "Expense", "C"},
            {"2210", "Use Tax Payable", "Liability", "C"}, {"1310", "Vendor Prepayments", "Asset", "D"}}) {
            ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", a[0], "accountName", a[1], "financialType",
                AccountTypes.fromChart(a[2]), "normalBalance", AccountTypes.normalBalanceFromChart(a[3]),
                "statementLine", a[1]));
        }
        ok(ApSettingsProcesses.SET, controller, Map.of("payableAccount", "2000", "discountAccount", "5900",
            "useTaxAccount", "2210", "prepaymentAccount", "1310"));
        // Furniture first: the sequence gives it FA-003.
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", "1530", "accountName", "Furniture",
            "financialType", AccountTypes.fromChart("Asset"), "normalBalance", AccountTypes.normalBalanceFromChart("D"),
            "statementLine", "Property and equipment, net", "controlClass", "FA_COST"));
        String furniture = (String) ok(BillProcesses.SAVE, clerk, new HashMap<>(Map.of("vendorCode", "V400",
            "vendorInvoiceNo", "FURN-01", "invoiceDate", "2026-01-10", "lines", List.of(Map.of("description", "Desks",
                "amount", "8000.00", "account", "1530"))))).get("billId");
        ok(BillProcesses.POST, clerk, Map.of("billId", furniture));
        // TS-5520 before any class: FA-004, unclassified (F6 plan D4).
        String billId = (String) ok(BillProcesses.SAVE, clerk, new HashMap<>(Map.of("vendorCode", "V700",
            "vendorInvoiceNo", "TS-5520", "invoiceDate", "2026-01-15", "lines", List.of(Map.of("description",
                "Application server", "amount", "12000.00", "account", "1520"))))).get("billId");
        ok(BillProcesses.POST, clerk, Map.of("billId", billId));
        assertThat(find(AssetEntities.ASSET_DATASET, "assetNo", "FA-004").getFirst())
            .containsEntry("costAccount", "1520").containsEntry("classCode", null);
        for (String[] c : new String[][] {{"MACH", "1500", "SL", "60"}, {"VEH", "1510", "DDB", "84"},
            {"COMP", "1520", "SL", "36"}}) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("classCode", c[0]);
            input.put("className", c[0]);
            input.put("costAccount", c[1]);
            input.put("accumulatedAccount", "1590");
            input.put("expenseAccount", "6700");
            input.put("method", c[2]);
            input.put("lifeMonths", Integer.parseInt(c[3]));
            input.put("convention", "FULL_MONTH");
            input.put("threshold", "2500.00");
            ok(AssetClassProcesses.CLASS_SAVE, controller, input);
        }

        String sample = sampleText("fixed-assets.csv");
        // A row of FA-003 that is no registered asset (another cost) and asks other terms refuses the file.
        assertThat(issues(importCsv("finance.fixed_assets", migrator, sample.replace(
            "FA-003,Application server,1520,12000.00,2026-01-15,SL,36",
            "FA-003,Application server,1520,11000.00,2026-01-15,SL,48"), "commit", null, null,
            422))).anySatisfy(i -> assertThat(i).contains(AssetOpeningProcesses.OPENING_LATER));
        importCsv("finance.fixed_assets", migrator, sample, "commit", null, null, 200);
        assertThat(find(AssetEntities.ASSET_DATASET, "assetNo", "FA-004").getFirst())
            .containsEntry("classCode", "COMP").containsEntry("method", "SL").containsEntry("source", "BILL")
            .satisfies(a -> assertThat(amount(a.get("lifeMonths"))).isEqualByComparingTo("36"));
        assertThat(find(AssetEntities.ASSET_DATASET, "source", "OPENING")).hasSize(2);
    }
}

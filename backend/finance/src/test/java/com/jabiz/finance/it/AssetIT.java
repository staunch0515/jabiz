package com.jabiz.finance.it;

import com.jabiz.finance.ap.ApSettingsProcesses;
import com.jabiz.finance.ap.BillProcesses;
import com.jabiz.finance.fa.AssetClassProcesses;
import com.jabiz.finance.fa.AssetEntities;
import com.jabiz.finance.fa.AssetOpeningProcesses;
import com.jabiz.finance.fa.AssetProcesses;
import com.jabiz.finance.gl.AccountTypes;
import com.jabiz.finance.setup.FinanceRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fixed asset register (ROADMAP F6a; FIN-FA-001, 002): the sample's classes with their accounts and defaults; the
 * legacy register brought over at the cutover, adding up to the opening entry's 1500, 1510 and 1590 or not at all;
 * FA-003 made by its bill in the class of 1520 with the class's terms (FIN-FA-001 acceptance 1); a purchase below the
 * threshold refused as an asset; an asset of an account without a class classified later; an acquisition posted by
 * the asset module; and terms that changed only before anything was depreciated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AssetIT extends FinanceItSupport {

    private static boolean loaded;

    private String controller;
    private String accountant;
    private String clerk;
    private String migrator;

    @BeforeEach
    void books() {
        controller = inRoles("controller", FinanceRoles.CONTROLLER);
        accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);
        clerk = inRoles("ap-clerk", FinanceRoles.PAYABLES_CLERK);
        migrator = as("migrator", "fin.migration", "fin.import");
        if (loaded) {
            return;
        }
        loaded = true;
        openBooks();
        importCsv("finance.opening_balances", controller, sampleText("opening-balances.csv"), "commit", null, null,
            200);
        importCsv("finance.vendors", clerk, sampleText("vendors.csv"), "commit", null, null, 200);
        account("5900", "Purchase Discounts", "Expense", "C", null);
        account("2210", "Use Tax Payable", "Liability", "C", null);
        account("1310", "Vendor Prepayments", "Asset", "D", null);
        ok(ApSettingsProcesses.SET, controller, Map.of("payableAccount", "2000", "discountAccount", "5900",
            "useTaxAccount", "2210", "prepaymentAccount", "1310"));
    }

    private void account(String code, String name, String type, String balance, String controlClass) {
        Map<String, Object> input = new HashMap<>(Map.of("accountCode", code, "accountName", name,
            "financialType", AccountTypes.fromChart(type), "normalBalance", AccountTypes.normalBalanceFromChart(balance),
            "statementLine", name));
        if (controlClass != null) {
            input.put("controlClass", controlClass);
        }
        ok("FIN_ACCOUNT_CREATE", controller(), input);
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

    private Map<String, Object> asset(String number) {
        return find(AssetEntities.ASSET_DATASET, "assetNo", number).stream().findFirst().orElse(null);
    }

    private Map<String, Object> postBill(String vendor, String number, String date, String amount, String account) {
        Map<String, Object> input = new HashMap<>(Map.of("vendorCode", vendor, "vendorInvoiceNo", number,
            "invoiceDate", date, "lines", List.of(Map.of("description", number, "amount", amount,
                "account", account))));
        String billId = (String) ok(BillProcesses.SAVE, clerk, input).get("billId");
        return Map.of("billId", billId, "post", run(BillProcesses.POST, clerk, Map.of("billId", billId)));
    }

    @Test
    @Order(1)
    void theClassesHaveTheirAccountsAndDefaults() {
        ok(AssetClassProcesses.CLASS_SAVE, controller, assetClass("MACH", "Machinery and equipment", "1500", "SL", 60));
        ok(AssetClassProcesses.CLASS_SAVE, controller, assetClass("VEH", "Vehicles", "1510", "DDB", 84));
        Map<String, Object> computer = ok(AssetClassProcesses.CLASS_SAVE, controller,
            assetClass("COMP", "Computer equipment", "1520", "SL", 36));
        assertThat(computer).containsEntry("created", true);
        // Saved again unchanged: nothing changes.
        assertThat(ok(AssetClassProcesses.CLASS_SAVE, controller, assetClass("COMP", "Computer equipment", "1520",
            "SL", 36))).containsEntry("created", false).containsEntry("changed", false);
        // A cost account is a FA_COST control account of one class; the expense account no control account.
        assertThat(refused(AssetClassProcesses.CLASS_SAVE, controller, assetClass("X", "X", "6700", "SL", 36), 422))
            .isEqualTo(AssetClassProcesses.WRONG_ACCOUNT);
        assertThat(refused(AssetClassProcesses.CLASS_SAVE, controller, assetClass("SERVERS", "Servers", "1520", "SL",
            48), 422)).isEqualTo(AssetClassProcesses.ACCOUNT_TAKEN);
        Map<String, Object> wrongExpense = assetClass("Y", "Y", "1500", "SL", 36);
        wrongExpense.put("expenseAccount", "1590");
        assertThat(refused(AssetClassProcesses.CLASS_SAVE, controller, wrongExpense, 422))
            .isEqualTo(AssetClassProcesses.WRONG_ACCOUNT);
        run(AssetClassProcesses.CLASS_SAVE, accountant, assetClass("Z", "Z", "1500", "SL", 36)).expectStatus()
            .isForbidden();
        // The gain or loss of disposals: an income statement account the controller adds (F6 plan D8).
        assertThat(refused(AssetClassProcesses.SETTINGS_SET, controller, Map.of("gainLossAccount", "1500"), 422))
            .isEqualTo(AssetClassProcesses.WRONG_SETTINGS_ACCOUNT);
        account("7400", "Gain or Loss on Disposal of Assets", "Other", "D", null);
        assertThat(ok(AssetClassProcesses.SETTINGS_SET, controller, Map.of("gainLossAccount", "7400")))
            .containsEntry("changed", true);
    }

    @Test
    @Order(2)
    void theLegacyRegisterIsBroughtOverOnlyWhenItAddsUpToTheOpeningEntry() {
        String sample = sampleText("fixed-assets.csv");
        // FA-001 at 119,000.00: the register would not be the ledger's 1500.
        Map<String, Object> refused = importCsv("finance.fixed_assets", migrator,
            sample.replace("FA-001,CNC machining center,1500,120000.00", "FA-001,CNC machining center,1500,119000.00"),
            "commit", null, null, 422);
        assertThat(issues(refused)).anySatisfy(i -> assertThat(i).contains(AssetOpeningProcesses.OPENING_TOTAL));
        assertThat(asset("FA-001")).isNull();

        // FA-003 is not registered yet: its row may not ask other terms than its class's, which its bill will give.
        assertThat(issues(importCsv("finance.fixed_assets", migrator, sample.replace("2026-01-15,SL,36",
            "2026-01-15,SL,48"), "commit", null, null, 422)))
            .anySatisfy(i -> assertThat(i).contains(AssetOpeningProcesses.OPENING_LATER));
        // An asset whose life ended before the cutover, yet not fully depreciated, would never be.
        assertThat(issues(importCsv("finance.fixed_assets", migrator, sample.replace("1500,120000.00,2024-01-01",
            "1500,120000.00,2019-01-01"), "commit", null, null, 422)))
            .anySatisfy(i -> assertThat(i).contains(AssetOpeningProcesses.OPENING_ITEM));
        // The legacy system's names of the methods are read as the register's.
        Map<String, Object> report = importCsv("finance.fixed_assets", migrator, sample
            .replace(",SL,60,", ",Straight-line,60,").replace(",DDB,84,", ",Double declining,84,"), "commit", null,
            null, 200);
        assertThat(report).containsEntry("committed", true);
        // FIN-FA-002: FA-001 and FA-002 with what they accumulated by 2025-12-31, depreciated here from January.
        assertThat(asset("FA-001")).containsEntry("classCode", "MACH").containsEntry("method", "SL")
            .containsEntry("source", "OPENING").containsEntry("openingPeriod", "2026-01")
            .containsEntry("status", "IN_SERVICE").containsEntry("convention", "FULL_MONTH")
            .satisfies(a -> {
                assertThat(amount(a.get("cost"))).isEqualByComparingTo("120000.00");
                assertThat(amount(a.get("openingAccumulated"))).isEqualByComparingTo("48000.00");
                assertThat(amount(a.get("lifeMonths"))).isEqualByComparingTo("60");
            });
        assertThat(asset("FA-002")).containsEntry("classCode", "VEH").containsEntry("method", "DDB")
            .satisfies(a -> assertThat(amount(a.get("openingAccumulated"))).isEqualByComparingTo("10000.00"));
        // FA-003 came in January: it is left to its bill.
        assertThat(asset("FA-003")).isNull();
        // Once: another file of the register is refused too (the same file again the platform refuses itself).
        assertThat(issues(importCsv("finance.fixed_assets", migrator, sample + "\n", "commit", null, null, 422)))
            .anySatisfy(i -> assertThat(i).contains(AssetOpeningProcesses.OPENING_DONE));
    }

    @Test
    @Order(3)
    void fa003IsMadeByItsBillInTheClassOfItsAccount() {
        Map<String, Object> bill = postBill("V700", "TS-5520", "2026-01-15", "12000.00", "1520");
        ((org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec) bill.get("post")).expectStatus()
            .isOk();
        // FIN-FA-001 acceptance 1: Computer equipment, 36 months, straight-line.
        assertThat(asset("FA-003")).containsEntry("classCode", "COMP").containsEntry("method", "SL")
            .containsEntry("convention", "FULL_MONTH").containsEntry("source", "BILL")
            .containsEntry("status", "IN_SERVICE")
            .satisfies(a -> {
                assertThat(amount(a.get("lifeMonths"))).isEqualByComparingTo("36");
                assertThat(amount(a.get("salvage"))).isEqualByComparingTo("0.00");
            });
        // Below the class's threshold a purchase is an expense: the bill is refused with the line (F6 plan D2).
        Map<String, Object> small = new HashMap<>(Map.of("vendorCode", "V700", "vendorInvoiceNo", "TS-5521",
            "invoiceDate", "2026-01-16", "lines", List.of(Map.of("description", "Keyboard", "amount", "120.00",
                "account", "1520"))));
        String smallId = (String) ok(BillProcesses.SAVE, clerk, small).get("billId");
        assertThat(refused(BillProcesses.POST, clerk, Map.of("billId", smallId), 422))
            .isEqualTo(AssetProcesses.BELOW_THRESHOLD);
        // The register's later rows give the terms of the asset that came by its bill, once it exists.
        assertThat(asset("FA-004")).isNull();
    }

    @Test
    @Order(4)
    void anAssetOfAnAccountWithoutAClassIsClassifiedLater() {
        account("1530", "Furniture and Fixtures", "Asset", "D", "FA_COST");
        postBill("V400", "FURN-01", "2026-01-20", "8000.00", "1530");
        Map<String, Object> desks = find(AssetEntities.ASSET_DATASET, "costAccount", "1530").getFirst();
        assertThat(desks.get("classCode")).isNull();
        // Its class, given now: the class's defaults (F6 plan D4).
        Map<String, Object> furniture = assetClass("FURN", "Furniture", "1530", "SL", 84);
        ok(AssetClassProcesses.CLASS_SAVE, controller, furniture);
        // A class of another account does not fit it.
        assertThat(refused(AssetProcesses.SAVE, controller, Map.of("assetId", desks.get("assetId"),
            "classCode", "COMP"), 422)).isEqualTo(AssetProcesses.CLASS_ACCOUNT);
        ok(AssetProcesses.SAVE, controller, Map.of("assetId", desks.get("assetId"), "classCode", "FURN",
            "custodian", "Office manager", "location", "AUSTIN"));
        Map<String, Object> classified = read(AssetEntities.ASSET_DATASET, desks.get("assetId"));
        assertThat(classified).containsEntry("classCode", "FURN").containsEntry("method", "SL")
            .containsEntry("custodian", "Office manager").containsEntry("location", "AUSTIN");
        assertThat(amount(classified.get("lifeMonths"))).isEqualByComparingTo("84");
        // Before anything is depreciated its terms may still change; a salvage above cost may not.
        ok(AssetProcesses.SAVE, controller, Map.of("assetId", desks.get("assetId"), "terms",
            Map.of("lifeMonths", 60, "salvage", "500.00")));
        assertThat(amount(read(AssetEntities.ASSET_DATASET, desks.get("assetId")).get("lifeMonths")))
            .isEqualByComparingTo("60");
        assertThat(refused(AssetProcesses.SAVE, controller, Map.of("assetId", desks.get("assetId"), "terms",
            Map.of("salvage", "9000.00")), 422)).isEqualTo(AssetProcesses.WRONG_TERMS);
        assertThat(refused(AssetProcesses.SAVE, controller, Map.of("assetId", desks.get("assetId"), "terms",
            Map.of("method", "UOP")), 422)).isEqualTo(AssetProcesses.WRONG_TERMS);
        // An asset brought over has depreciated already: its terms change by a change in estimate (F6b).
        assertThat(refused(AssetProcesses.SAVE, controller, Map.of("assetId", asset("FA-001").get("assetId"),
            "terms", Map.of("lifeMonths", 72)), 422)).isEqualTo(AssetProcesses.TERMS_LOCKED);
        run(AssetProcesses.SAVE, accountant, Map.of("assetId", desks.get("assetId"), "custodian", "X"))
            .expectStatus().isForbidden();
    }

    @Test
    @Order(5)
    void anAcquisitionPostsItsCostThroughTheAssetModule() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("classCode", "MACH");
        input.put("description", "Laser cutter");
        input.put("cost", "45000.00");
        input.put("inServiceDate", "2026-01-28");
        input.put("offsetAccount", "1010");
        input.put("location", "AUSTIN");
        Map<String, Object> acquired = ok(AssetProcesses.ACQUIRE, controller, input);
        String number = (String) acquired.get("assetNo");
        assertThat(number).startsWith("FA-");
        assertThat(asset(number)).containsEntry("source", "ACQUISITION").containsEntry("costAccount", "1500")
            .containsEntry("method", "SL");
        // Dr the class's cost account, Cr the bank, source FA (F6 plan D3).
        assertThat(postingLines(number)).isEqualTo(Map.of("1500", new BigDecimal("45000.00"),
            "1010", new BigDecimal("-45000.00")));
        // Not against a control account of another subledger; not below the threshold; not by the accountant.
        Map<String, Object> payable = new LinkedHashMap<>(input);
        payable.put("offsetAccount", "2000");
        assertThat(refused(AssetProcesses.ACQUIRE, controller, payable, 422)).isEqualTo(AssetProcesses.WRONG_OFFSET);
        Map<String, Object> small = new LinkedHashMap<>(input);
        small.put("cost", "900.00");
        assertThat(refused(AssetProcesses.ACQUIRE, controller, small, 422)).isEqualTo(AssetProcesses.BELOW_THRESHOLD);
        Map<String, Object> unknown = new LinkedHashMap<>(input);
        unknown.put("classCode", "NONE");
        assertThat(refused(AssetProcesses.ACQUIRE, controller, unknown, 422)).isEqualTo(AssetProcesses.UNKNOWN_CLASS);
        run(AssetProcesses.ACQUIRE, accountant, input).expectStatus().isForbidden();
    }

    @Test
    @Order(6)
    void aClassInUseKeepsItsAccountsAndAnInactiveOneTakesNoAssets() {
        account("1591", "Accumulated Depreciation - Computers", "Asset", "C", "FA_ACCUM");
        Map<String, Object> moved = assetClass("COMP", "Computer equipment", "1520", "SL", 36);
        moved.put("accumulatedAccount", "1591");
        assertThat(refused(AssetClassProcesses.CLASS_SAVE, controller, moved, 422))
            .isEqualTo(AssetClassProcesses.CLASS_IN_USE);
        // Its defaults may change; its assets keep theirs.
        ok(AssetClassProcesses.CLASS_SAVE, controller, assetClass("COMP", "Computer equipment", "1520", "SL", 48));
        assertThat(amount(asset("FA-003").get("lifeMonths"))).isEqualByComparingTo("36");
        Map<String, Object> retired = assetClass("FURN", "Furniture", "1530", "SL", 84);
        retired.put("active", false);
        ok(AssetClassProcesses.CLASS_SAVE, controller, retired);
        Map<String, Object> bill = new HashMap<>(Map.of("vendorCode", "V400", "vendorInvoiceNo", "FURN-02",
            "invoiceDate", "2026-01-29", "lines", List.of(Map.of("description", "Chairs", "amount", "6000.00",
                "account", "1530"))));
        String billId = (String) ok(BillProcesses.SAVE, clerk, bill).get("billId");
        assertThat(refused(BillProcesses.POST, clerk, Map.of("billId", billId), 422))
            .isEqualTo(AssetProcesses.INACTIVE_CLASS);
    }

    @Test
    @Order(7)
    void theRegisterTablesKeepTheirVersions() {
        assertOnlyInserted("fi_asset_version", "fi_asset_class_version", "fi_fa_settings_version");
    }
}

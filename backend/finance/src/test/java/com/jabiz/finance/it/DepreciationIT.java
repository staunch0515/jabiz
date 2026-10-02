package com.jabiz.finance.it;

import com.jabiz.finance.ap.ApSettingsProcesses;
import com.jabiz.finance.ap.BillProcesses;
import com.jabiz.finance.fa.AssetClassProcesses;
import com.jabiz.finance.fa.AssetEntities;
import com.jabiz.finance.fa.AssetEventProcesses;
import com.jabiz.finance.fa.AssetProcesses;
import com.jabiz.finance.fa.DepreciationEntities;
import com.jabiz.finance.fa.DepreciationProcesses;
import com.jabiz.finance.gl.AccountTypes;
import com.jabiz.finance.setup.FinanceRoles;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Depreciation (ROADMAP F6b; FIN-FA-005, 006, 007 and units of production): the sample register's January run
 * {@code DEP-2601} of 4,000.00 as FIN-EXP-11 has it, run once; months in order, a closed month refused; the latest run
 * taken back and run again; FA-001's life extended from February to 1,489.36 a month with January as it was; FA-002
 * sold at the end of January for a gain of 1,666.67; an asset by units of production that needs its units; a disposal
 * in a month not yet run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DepreciationIT extends FinanceItSupport {

    private static boolean loaded;
    private static String cutterNo;

    private String controller;
    private String accountant;

    @BeforeEach
    void books() {
        controller = inRoles("controller", FinanceRoles.CONTROLLER);
        accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);
        if (loaded) {
            return;
        }
        loaded = true;
        String clerk = inRoles("ap-clerk", FinanceRoles.PAYABLES_CLERK);
        String migrator = as("migrator", "fin.migration", "fin.import");
        openBooks();
        importCsv("finance.opening_balances", controller, sampleText("opening-balances.csv"), "commit", null, null,
            200);
        importCsv("finance.vendors", clerk, sampleText("vendors.csv"), "commit", null, null, 200);
        account("5900", "Purchase Discounts", "Expense", "C");
        account("2210", "Use Tax Payable", "Liability", "C");
        account("1310", "Vendor Prepayments", "Asset", "D");
        account("7400", "Gain or Loss on Disposal of Assets", "Other", "D");
        ok(ApSettingsProcesses.SET, controller, Map.of("payableAccount", "2000", "discountAccount", "5900",
            "useTaxAccount", "2210", "prepaymentAccount", "1310"));
        ok(AssetClassProcesses.CLASS_SAVE, controller, assetClass("MACH", "Machinery and equipment", "1500", "SL", 60));
        ok(AssetClassProcesses.CLASS_SAVE, controller, assetClass("VEH", "Vehicles", "1510", "DDB", 84));
        ok(AssetClassProcesses.CLASS_SAVE, controller, assetClass("COMP", "Computer equipment", "1520", "SL", 36));
        importCsv("finance.fixed_assets", migrator, sampleText("fixed-assets.csv"), "commit", null, null, 200);
        Map<String, Object> bill = new HashMap<>(Map.of("vendorCode", "V700", "vendorInvoiceNo", "TS-5520",
            "invoiceDate", "2026-01-15", "lines", List.of(Map.of("description", "Application server",
                "amount", "12000.00", "account", "1520"))));
        ok(BillProcesses.POST, clerk, Map.of("billId", ok(BillProcesses.SAVE, clerk, bill).get("billId")));
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

    private Map<String, Object> asset(String number) {
        return find(AssetEntities.ASSET_DATASET, "assetNo", number).stream().findFirst().orElseThrow();
    }

    private Map<String, BigDecimal> lines(String runId) {
        Map<String, BigDecimal> lines = new java.util.TreeMap<>();
        find(DepreciationEntities.LINE_DATASET, "runId", runId)
            .forEach(l -> lines.put((String) l.get("assetNo"), amount(l.get("amount"))));
        return lines;
    }

    private static Map<String, BigDecimal> amounts(Object... pairs) {
        Map<String, BigDecimal> amounts = new java.util.TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            amounts.put((String) pairs[i], new BigDecimal((String) pairs[i + 1]));
        }
        return amounts;
    }

    @Test
    @Order(1)
    void januaryIsDepreciatedOnceAsFinExp11Has() throws IOException {
        // Months run in order from the month after the cutover.
        assertThat(refused(DepreciationProcesses.RUN, accountant, Map.of("periodKey", "2026-02"), 422))
            .isEqualTo(DepreciationProcesses.OUT_OF_ORDER);
        assertThat(refused(DepreciationProcesses.RUN, accountant, Map.of("periodKey", "2025-12"), 422))
            .isEqualTo(DepreciationProcesses.OUT_OF_ORDER);
        run(DepreciationProcesses.RUN, accountant, Map.of("periodKey", "2026-13")).expectStatus().isBadRequest();
        // A month the subledger closed is not run.
        ok("FIN_PERIOD_SET_SUBLEDGER_STATE", controller, Map.of("periodKey", "2026-01", "subledger", "FA",
            "status", "CLOSED"));
        assertThat(refused(DepreciationProcesses.RUN, accountant, Map.of("periodKey", "2026-01"), 422))
            .isEqualTo("FIN_SUBLEDGER_CLOSED");
        ok("FIN_PERIOD_SET_SUBLEDGER_STATE", controller, Map.of("periodKey", "2026-01", "subledger", "FA",
            "status", "OPEN"));

        Map<String, Object> run = ok(DepreciationProcesses.RUN, accountant, Map.of("periodKey", "2026-01"));
        assertThat(run).containsEntry("runNo", "DEP-2601").containsEntry("posted", true)
            .containsEntry("assetCount", 3);
        assertThat(amount(run.get("total"))).isEqualByComparingTo("4000.00");
        // FIN-FA-005 acceptance 1 and FIN-EXP-11: one entry, 6700 4,000.00 / 1590 (4,000.00), detailed per asset.
        assertThat(postingLines("DEP-2601")).isEqualTo(expectedDocuments("DEP-2601", "depreciation").get("DEP-2601"));
        assertThat(lines((String) run.get("runId")))
            .isEqualTo(amounts("FA-001", "2000.00", "FA-002", "1666.67", "FA-003", "333.33"));
        assertThat(asset("FA-001")).containsEntry("depreciatedThrough", "2026-01")
            .satisfies(a -> assertThat(amount(a.get("accumulated"))).isEqualByComparingTo("50000.00"));
        assertThat(amount(asset("FA-002").get("accumulated"))).isEqualByComparingTo("11666.67");
        assertThat(amount(asset("FA-003").get("accumulated"))).isEqualByComparingTo("333.33");

        // Run again: nothing posts twice.
        Map<String, Object> again = ok(DepreciationProcesses.RUN, accountant, Map.of("periodKey", "2026-01"));
        assertThat(again).containsEntry("posted", false).containsEntry("runNo", "DEP-2601")
            .containsEntry("runId", run.get("runId"));
        assertThat(postingLines("DEP-2601")).containsEntry("6700", new BigDecimal("4000.00"));
        // Running is the accountant's; the payables clerk does not.
        run(DepreciationProcesses.RUN, inRoles("ap-clerk", FinanceRoles.PAYABLES_CLERK),
            Map.of("periodKey", "2026-01")).expectStatus().isForbidden();
    }

    @Test
    @Order(2)
    void theLatestRunIsTakenBackAndRunAgain() {
        assertThat(refused(DepreciationProcesses.REVERSE, accountant, Map.of("periodKey", "2025-12",
            "reason", "Wrong month"), 422)).isEqualTo(DepreciationProcesses.NOT_LATEST);
        Map<String, Object> reversed = ok(DepreciationProcesses.REVERSE, accountant, Map.of("periodKey", "2026-01",
            "reason", "FA-003 classified late"));
        assertThat(reversed).containsEntry("runNo", "DEP-2601");
        assertThat(reversed.get("glNo")).isNotNull();
        // The entry and the assets are as before the run.
        assertThat(postingLines("DEP-2601")).isEmpty();
        assertThat(asset("FA-001")).containsEntry("depreciatedThrough", null)
            .satisfies(a -> assertThat(amount(a.get("accumulated"))).isEqualByComparingTo("48000.00"));
        assertThat(asset("FA-003")).containsEntry("depreciatedThrough", null)
            .satisfies(a -> assertThat(amount(a.get("accumulated"))).isEqualByComparingTo("0.00"));
        assertThat(find(DepreciationEntities.RUN_DATASET, "runNo", "DEP-2601")).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("status", "REVERSED").containsEntry("reason",
                "FA-003 classified late"));
        // Nothing left to take back.
        assertThat(refused(DepreciationProcesses.REVERSE, accountant, Map.of("periodKey", "2026-01",
            "reason", "Again"), 422)).isEqualTo(DepreciationProcesses.NOT_LATEST);

        // The month runs again in a second round.
        Map<String, Object> run = ok(DepreciationProcesses.RUN, accountant, Map.of("periodKey", "2026-01"));
        assertThat(run).containsEntry("runNo", "DEP-2601-2").containsEntry("posted", true);
        assertThat(amount(run.get("total"))).isEqualByComparingTo("4000.00");
        assertThat(postingLines("DEP-2601-2")).isEqualTo(Map.of("6700", new BigDecimal("4000.00"),
            "1590", new BigDecimal("-4000.00")));
    }

    @Test
    @Order(3)
    void fa001sLifeIsExtendedFromFebruary() {
        String fa001 = (String) asset("FA-001").get("assetId");
        assertThat(refused(AssetEventProcesses.CHANGE, controller, Map.of("assetId", fa001, "reason", "Nothing"), 422))
            .isEqualTo(AssetEventProcesses.NO_CHANGE);
        // A life ending before the month it takes effect in.
        assertThat(refused(AssetEventProcesses.CHANGE, controller, Map.of("assetId", fa001, "lifeMonths", 24,
            "reason", "Too short"), 422)).isEqualTo(AssetEventProcesses.WRONG_TERMS);
        run(AssetEventProcesses.CHANGE, accountant, Map.of("assetId", fa001, "lifeMonths", 72, "reason", "x"))
            .expectStatus().isForbidden();
        // FIN-FA-006 acceptance 1: 70,000.00 over the 47 months left.
        Map<String, Object> change = ok(AssetEventProcesses.CHANGE, controller, Map.of("assetId", fa001,
            "lifeMonths", 72, "reason", "Overhauled: twelve more months"));
        assertThat(change).containsEntry("fromPeriod", "2026-02");
        assertThat(amount(change.get("nextAmount"))).isEqualByComparingTo("1489.36");
        assertThat(find(DepreciationEntities.CHANGE_DATASET, "assetNo", "FA-001")).singleElement()
            .satisfies(c -> {
                assertThat(amount(c.get("accumulatedAt"))).isEqualByComparingTo("50000.00");
                assertThat(amount(c.get("oldLifeMonths"))).isEqualByComparingTo("60");
            });
        assertThat(refused(AssetEventProcesses.CHANGE, controller, Map.of("assetId", fa001, "lifeMonths", 84,
            "reason", "Again"), 422)).isEqualTo(AssetEventProcesses.CHANGE_EXISTS);
        // January stands as it was; and the run it follows can no longer be taken back.
        assertThat(postingLines("DEP-2601-2")).containsEntry("6700", new BigDecimal("4000.00"));
        assertThat(refused(DepreciationProcesses.REVERSE, accountant, Map.of("periodKey", "2026-01",
            "reason", "Late"), 422)).isEqualTo(DepreciationProcesses.CHANGED_SINCE);
    }

    @Test
    @Order(4)
    void fa002IsSoldAtTheEndOfJanuary() {
        String fa002 = (String) asset("FA-002").get("assetId");
        Map<String, Object> sale = new LinkedHashMap<>(Map.of("assetId", fa002, "disposalDate", "2026-01-31",
            "kind", "SALE", "proceeds", "60000.00", "proceedsAccount", "1010", "reason", "Sold to the dealer"));
        Map<String, Object> noProceeds = new LinkedHashMap<>(sale);
        noProceeds.put("proceeds", "0.00");
        assertThat(refused(AssetEventProcesses.DISPOSE, controller, noProceeds, 422))
            .isEqualTo(AssetEventProcesses.WRONG_PROCEEDS);
        Map<String, Object> payable = new LinkedHashMap<>(sale);
        payable.put("proceedsAccount", "2000");
        assertThat(refused(AssetEventProcesses.DISPOSE, controller, payable, 422))
            .isEqualTo(AssetEventProcesses.WRONG_OFFSET);
        run(AssetEventProcesses.DISPOSE, accountant, sale).expectStatus().isForbidden();
        // A gain or loss needs the account of the asset settings.
        assertThat(refused(AssetEventProcesses.DISPOSE, controller, sale, 422))
            .isEqualTo(AssetEventProcesses.NO_GAIN_LOSS);
        ok(AssetClassProcesses.SETTINGS_SET, controller, Map.of("gainLossAccount", "7400"));

        // FIN-FA-007 acceptance 1: January run, net book value 58,333.33, a gain of 1,666.67.
        Map<String, Object> sold = ok(AssetEventProcesses.DISPOSE, controller, sale);
        assertThat(sold).containsEntry("documentNo", "DSP-FA-002");
        assertThat(amount(sold.get("monthDepreciation"))).isEqualByComparingTo("0.00");
        assertThat(amount(sold.get("gainLoss"))).isEqualByComparingTo("1666.67");
        assertThat(postingLines("DSP-FA-002")).isEqualTo(amounts("1590", "11666.67", "1010", "60000.00",
            "1510", "-70000.00", "7400", "-1666.67"));
        assertThat(asset("FA-002")).containsEntry("status", "DISPOSED");
        assertThat(refused(AssetEventProcesses.DISPOSE, controller, sale, 422))
            .isEqualTo(AssetEventProcesses.WRONG_STATUS);
    }

    @Test
    @Order(5)
    void anAssetByUseNeedsItsUnits() {
        Map<String, Object> acquire = new LinkedHashMap<>();
        acquire.put("classCode", "MACH");
        acquire.put("description", "Laser cutter");
        acquire.put("cost", "10000.00");
        acquire.put("inServiceDate", "2026-02-10");
        acquire.put("offsetAccount", "1010");
        acquire.put("terms", Map.of("method", "UOP", "totalUnits", "1000.00"));
        Map<String, Object> acquired = ok(AssetProcesses.ACQUIRE, controller, acquire);
        String number = (String) acquired.get("assetNo");
        String assetId = (String) acquired.get("assetId");
        cutterNo = number;
        // Without February's units the month is not run.
        assertThat(refused(DepreciationProcesses.RUN, accountant, Map.of("periodKey", "2026-02"), 422))
            .isEqualTo(DepreciationProcesses.USAGE_MISSING);
        assertThat(refused(AssetEventProcesses.USAGE, accountant, Map.of("assetId", asset("FA-001").get("assetId"),
            "periodKey", "2026-02", "units", "1"), 422)).isEqualTo(AssetEventProcesses.NOT_BY_USE);
        assertThat(refused(AssetEventProcesses.USAGE, accountant, Map.of("assetId", assetId,
            "periodKey", "2026-01", "units", "1"), 422)).isEqualTo(AssetEventProcesses.WRONG_TERMS);
        Map<String, Object> first = ok(AssetEventProcesses.USAGE, accountant, Map.of("assetId", assetId,
            "periodKey", "2026-02", "units", "100"));
        // Corrected before the month is run.
        Map<String, Object> corrected = ok(AssetEventProcesses.USAGE, accountant, Map.of("assetId", assetId,
            "periodKey", "2026-02", "units", "150"));
        assertThat(corrected.get("usageId")).isEqualTo(first.get("usageId"));
        // An asset of an account without a class stops the month until it is classified (F6 plan D4).
        ok("FIN_ACCOUNT_CREATE", controller, Map.of("accountCode", "1530", "accountName", "Furniture and Fixtures",
            "financialType", AccountTypes.fromChart("Asset"), "normalBalance", AccountTypes.normalBalanceFromChart("D"),
            "statementLine", "Furniture and Fixtures", "controlClass", "FA_COST"));
        String clerk = inRoles("ap-clerk", FinanceRoles.PAYABLES_CLERK);
        Map<String, Object> bill = new HashMap<>(Map.of("vendorCode", "V400", "vendorInvoiceNo", "FURN-01",
            "invoiceDate", "2026-02-03", "lines", List.of(Map.of("description", "Desks", "amount", "3600.00",
                "account", "1530"))));
        ok(BillProcesses.POST, clerk, Map.of("billId", ok(BillProcesses.SAVE, clerk, bill).get("billId")));
        Map<String, Object> desks = find(AssetEntities.ASSET_DATASET, "costAccount", "1530").getFirst();
        Map<String, Object> unclassified = run(DepreciationProcesses.RUN, accountant, Map.of("periodKey", "2026-02"))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(unclassified.get("violations").toString()).contains(DepreciationProcesses.UNCLASSIFIED)
            .contains((String) desks.get("assetNo"));
        ok(AssetClassProcesses.CLASS_SAVE, controller, assetClass("FURN", "Furniture", "1530", "SL", 36));
        ok(AssetProcesses.SAVE, controller, Map.of("assetId", desks.get("assetId"), "classCode", "FURN"));

        Map<String, Object> run = ok(DepreciationProcesses.RUN, accountant, Map.of("periodKey", "2026-02"));
        assertThat(run).containsEntry("runNo", "DEP-2602").containsEntry("assetCount", 4);
        // FA-001 by its new life, FA-003 as planned, the cutter 150 of 1,000 units, the desks 3,600.00 over 36
        // months; FA-002 is gone.
        assertThat(lines((String) run.get("runId"))).isEqualTo(amounts("FA-001", "1489.36", "FA-003", "333.33",
            number, "1500.00", (String) desks.get("assetNo"), "100.00"));
        assertThat(postingLines("DEP-2602")).isEqualTo(amounts("6700", "3422.69", "1590", "-3422.69"));
        assertThat(asset(number)).satisfies(a -> {
            assertThat(amount(a.get("unitsUsed"))).isEqualByComparingTo("150");
            assertThat(amount(a.get("accumulated"))).isEqualByComparingTo("1500.00");
        });
        assertThat(refused(AssetEventProcesses.USAGE, accountant, Map.of("assetId", assetId,
            "periodKey", "2026-02", "units", "200"), 422)).isEqualTo(AssetEventProcesses.USAGE_RUN);
    }

    @Test
    @Order(6)
    void aDisposalWaitsForTheMonthsBeforeIt() {
        String fa003 = (String) asset("FA-003").get("assetId");
        Map<String, Object> scrap = new LinkedHashMap<>(Map.of("assetId", fa003, "disposalDate", "2026-04-05",
            "kind", "SCRAP", "proceeds", "0.00", "reason", "Failed beyond repair"));
        // March is not run yet.
        assertThat(refused(AssetEventProcesses.DISPOSE, controller, scrap, 422))
            .isEqualTo(AssetEventProcesses.MONTHS_NOT_RUN);
        // In March, not yet run: by the full-month convention March takes nothing, the rest is a loss.
        scrap.put("disposalDate", "2026-03-20");
        Map<String, Object> scrapped = ok(AssetEventProcesses.DISPOSE, controller, scrap);
        assertThat(amount(scrapped.get("monthDepreciation"))).isEqualByComparingTo("0.00");
        assertThat(amount(scrapped.get("gainLoss"))).isEqualByComparingTo("-11333.34");
        assertThat(postingLines("DSP-FA-003")).isEqualTo(amounts("1590", "666.66", "1520", "-12000.00",
            "7400", "11333.34"));
        assertThat(asset("FA-003")).containsEntry("status", "DISPOSED").containsEntry("depreciatedThrough", "2026-02");
        // February's run had FA-003: it is no longer taken back.
        assertThat(refused(DepreciationProcesses.REVERSE, accountant, Map.of("periodKey", "2026-02",
            "reason", "Late"), 422)).isEqualTo(DepreciationProcesses.CHANGED_SINCE);

        // In service in January, registered after January and February were run: its disposal takes January too.
        Map<String, Object> late = ok(AssetProcesses.ACQUIRE, controller, acquisition("Late printer", "3600.00",
            "2026-01-10"));
        Map<String, Object> printer = ok(AssetEventProcesses.DISPOSE, controller, Map.of("assetId",
            late.get("assetId"), "disposalDate", "2026-02-20", "kind", "WRITE_OFF", "proceeds", "0.00",
            "reason", "Stolen"));
        assertThat(amount(printer.get("monthDepreciation"))).isEqualByComparingTo("100.00");
        assertThat(amount(printer.get("gainLoss"))).isEqualByComparingTo("-3500.00");
        // By use, the month's units come first.
        Map<String, Object> cutter = asset(cutterNo);
        assertThat(refused(AssetEventProcesses.DISPOSE, controller, Map.of("assetId", cutter.get("assetId"),
            "disposalDate", "2026-03-10", "kind", "SCRAP", "proceeds", "0.00", "reason", "Broken"), 422))
            .isEqualTo(DepreciationProcesses.USAGE_MISSING);
    }

    private static Map<String, Object> acquisition(String description, String cost, String inService) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("classCode", "COMP");
        input.put("description", description);
        input.put("cost", cost);
        input.put("inServiceDate", inService);
        input.put("offsetAccount", "1010");
        return input;
    }

    @Test
    @Order(7)
    void aRunTakesMoreAssetsThanAWriteBatch() {
        String last = null;
        for (int i = 1; i <= 101; i++) {
            last = (String) ok(AssetProcesses.ACQUIRE, controller, acquisition("Laptop " + i, "3600.00",
                "2026-03-02")).get("assetNo");
        }
        String cutter = (String) asset(cutterNo).get("assetId");
        ok(AssetEventProcesses.USAGE, accountant, Map.of("assetId", cutter, "periodKey", "2026-03", "units", "0"));
        Map<String, Object> run = ok(DepreciationProcesses.RUN, accountant, Map.of("periodKey", "2026-03"));
        // FA-001, the cutter (no units), the desks and 101 laptops at 100.00.
        assertThat(run).containsEntry("assetCount", 104);
        assertThat(amount(run.get("total"))).isEqualByComparingTo("11689.36");
        ok(DepreciationProcesses.REVERSE, accountant, Map.of("periodKey", "2026-03", "reason", "Laptops late"));
        assertThat(postingLines("DEP-2603")).isEmpty();
        assertThat(asset(last)).containsEntry("depreciatedThrough", null);
    }

    @Test
    @Order(8)
    void theRunsAndTheirLinesAreOnlyAppended() {
        assertOnlyInserted("fi_depreciation_run_version", "fi_depreciation_line_version", "fi_asset_change_version",
            "fi_asset_disposal_version", "fi_asset_usage_version", "fi_asset_version");
    }
}

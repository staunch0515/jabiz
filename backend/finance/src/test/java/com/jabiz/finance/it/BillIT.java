package com.jabiz.finance.it;

import com.jabiz.finance.ap.ApSettingsProcesses;
import com.jabiz.finance.ap.BillEntities;
import com.jabiz.finance.ap.BillProcesses;
import com.jabiz.finance.ap.VendorProcesses;
import com.jabiz.finance.fa.AssetEntities;
import com.jabiz.finance.gl.AccountTypes;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.runtime.event.OutboxDeliverer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vendor bills and credits (ROADMAP F4b): the legacy open payables brought over against the opening entry, January's
 * bills posted as FIN-EXP-02 has them, duplicates, the approval a large bill needs before it is paid, an asset from a
 * capitalized bill, a vendor credit applied, use tax, voids, and the aging adding up to the payables account.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BillIT extends FinanceItSupport {

    /** The schema lives as long as the class: the books are opened and January's bills posted once. */
    private static boolean loaded;
    /** The sample's January bills: the document of FIN-EXP-02 to the bill posted for it. */
    private static final Map<String, Map<String, Object>> POSTED = new TreeMap<>();

    @Autowired
    OutboxDeliverer deliverer;

    private String clerk;
    private String controller;

    @BeforeEach
    void books() {
        clerk = inRoles("ap-clerk", FinanceRoles.PAYABLES_CLERK);
        controller = inRoles("controller", FinanceRoles.CONTROLLER);
        if (loaded) {
            return;
        }
        loaded = true;
        openBooks();
        importCsv("finance.opening_balances", controller, sampleText("opening-balances.csv"), "commit", null, null,
            200);
        importCsv("finance.tax_codes", controller, sampleText("tax-codes.csv"), "commit", null,
            Map.of("ratesFrom", "2025-01-01"), 200);
        importCsv("finance.vendors", clerk, sampleText("vendors.csv"), "commit", null, null, 200);
        // The sample chart lacks them (F4 plan D5): the controller adds them and sets the payables accounts.
        account("5900", "Purchase Discounts", "Expense", "C");
        account("2210", "Use Tax Payable", "Liability", "C");
        account("1310", "Vendor Prepayments", "Asset", "D");
        // V200 and V300 gave their W-9s; V800 did not (FIN-AP-002 acceptance 2).
        ok(VendorProcesses.TAX_SAVE, clerk, Map.of("vendorCode", "V200", "tinType", "EIN", "tin", "45-1234567"));
        ok(VendorProcesses.TAX_SAVE, clerk, Map.of("vendorCode", "V300", "tinType", "EIN", "tin", "45-7654321"));
        ok(ApSettingsProcesses.SET, controller, Map.of("payableAccount", "2000", "discountAccount", "5900",
            "useTaxAccount", "2210", "prepaymentAccount", "1310"));
    }

    @Test
    @Order(1)
    void theOpenPayablesAddUpToTheOpeningEntry() {
        String migrator = as("migrator", "fin.migration", "fin.import", "fin.ap.read");
        // One amount off: refused whole, with the difference (FIN-DI-002).
        String wrong = sampleText("open-payables.csv").replace("9000.00", "9100.00");
        Map<String, Object> refused = importCsv("finance.open_payables", migrator, wrong, "commit", null, null, 422);
        assertThat(refused.toString()).contains(BillProcesses.OPENING_TOTAL, "41400.00", "41300.00");
        // A document twice, or dated after the opening entry: refused whole.
        Map<String, Object> twice = importCsv("finance.open_payables", migrator, sampleText("open-payables.csv")
            + "P-7781,V100,2025-12-10,2026-01-09,1.00\nLATE-1,V100,2026-01-05,2026-02-04,1.00\n", "commit", null, null,
            422);
        assertThat(twice.toString()).contains(BillProcesses.OPENING_TWICE, BillProcesses.OPENING_DATE);
        Map<String, Object> report = importCsv("finance.open_payables", migrator, sampleText("open-payables.csv"),
            "commit", null, null, 200);
        assertThat(report).containsEntry("committed", true);
        assertThat(find(BillEntities.BILL_DATASET, "source", "OPENING")).extracting(b -> b.get("billNo") + " "
            + b.get("vendorCode") + " " + amount(b.get("openAmount")).toPlainString() + " " + b.get("approval"))
            .containsExactlyInAnyOrder("P-7781 V100 28300.00 NOT_REQUIRED", "DC-2025-12 V200 9000.00 NOT_REQUIRED",
                "CPL-1225 V600 4000.00 NOT_REQUIRED");
        // DC-2025-12 is reported as V200's work when paid (FIN-AP-021).
        assertThat(find(BillEntities.BILL_DATASET, "billNo", "DC-2025-12").getFirst())
            .containsEntry("form1099", "NEC").containsEntry("box1099", "1");
        assertThat(refused(BillProcesses.OPENING, migrator, Map.of("items", List.of(Map.of("document", "X-1",
            "vendorCode", "V100", "invoiceDate", "2025-12-01", "dueDate", "2025-12-31", "amount", "1.00"))), 422))
            .isEqualTo(BillProcesses.OPENING_DONE);
    }

    @Test
    @Order(2)
    @SuppressWarnings("unchecked")
    void januarysBillsPostAsTheExpectedResultsHaveThem() throws Exception {
        Map<String, Map<String, BigDecimal>> expected = expectedDocuments("BILL-[A-Z0-9-]+", "bill");
        // FIN-EXP-02's January bills: vendor, the vendor's number, date, account, amount.
        record Sample(String vendor, String number, String date, String account, String amount, String text) {}
        Map<String, Sample> january = new LinkedHashMap<>();
        january.put("BILL-V300-2601", new Sample("V300", "MP-2026-01", "2026-01-02", "6200", "8500.00",
            "January rent"));
        january.put("BILL-P-7902", new Sample("V100", "P-7902", "2026-01-09", "5000", "22000.00",
            "Components purchased"));
        january.put("BILL-TS-5520", new Sample("V700", "TS-5520", "2026-01-15", "1520", "12000.00",
            "Application server"));
        january.put("BILL-DC-2601", new Sample("V200", "DC-2026-01", "2026-01-20", "6400", "7500.00",
            "January consulting"));
        january.put("BILL-CS-0126", new Sample("V400", "CS-0126", "2026-01-20", "6500", "1200.00",
            "Software subscription January"));
        january.put("BILL-JR-014", new Sample("V800", "JR-014", "2026-01-21", "6400", "1500.00", "Drafting services"));
        january.put("BILL-CPL-0126", new Sample("V600", "CPL-0126", "2026-01-28", "6300", "3600.00",
            "Electricity January"));
        assertThat(january.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet().stream()
            .filter(d -> !d.equals("BILL-P-8010") && !d.equals("BILL-OS-0120")).toList());
        for (Map.Entry<String, Sample> entry : january.entrySet()) {
            Sample s = entry.getValue();
            String id = save(s.vendor(), s.number(), s.date(), List.of(line(s.text(), s.amount(), s.account())));
            Map<String, Object> posted = ok(BillProcesses.POST, clerk, Map.of("billId", id));
            POSTED.put(entry.getKey(), posted);
            assertThat(postingLines((String) posted.get("billNo"))).as(entry.getKey())
                .isEqualTo(expected.get(entry.getKey()));
        }
        // Numbered without gaps in the order posted.
        assertThat(POSTED.values()).extracting(p -> (String) p.get("billNo")).containsExactlyInAnyOrder("BILL-1",
            "BILL-2", "BILL-3", "BILL-4", "BILL-5", "BILL-6", "BILL-7");

        // BILL-DC-2601: 6400 debited, 2000 credited, 1099-NEC box 1 from V200 (FIN-AP-004); due on net 30.
        Map<String, Object> dc = POSTED.get("BILL-DC-2601");
        assertThat(dc).containsEntry("approval", "NOT_REQUIRED").containsEntry("dueDate", "2026-02-19");
        assertThat(find(BillEntities.LINE_DATASET, "billId", dc.get("billId")).getFirst())
            .containsEntry("form1099", "NEC").containsEntry("box1099", "1");
        // CPL-0126 of V600, net 23.
        assertThat(POSTED.get("BILL-CPL-0126")).containsEntry("dueDate", "2026-02-20");
        // P-7902 is in the books but waits for the controller before it is paid (FIN-AP-006).
        assertThat(POSTED.get("BILL-P-7902")).containsEntry("approval", "PENDING");
        // V800 is reported on Form 1099 and has no TIN on file (FIN-AP-002 acceptance 2).
        assertThat((List<String>) POSTED.get("BILL-JR-014").get("warnings"))
            .anySatisfy(w -> assertThat(w).startsWith(BillProcesses.BACKUP_WITHHOLDING).contains("V800"));
        assertThat((List<String>) dc.get("warnings")).isEmpty();
        // BILL-TS-5520 on 1520 makes asset FA-003 with its cost and the bill (FIN-AP-007).
        assertThat((List<String>) POSTED.get("BILL-TS-5520").get("assets")).containsExactly("FA-003");
        assertThat(find(AssetEntities.ASSET_DATASET, "assetNo", "FA-003").getFirst())
            .containsEntry("costAccount", "1520").containsEntry("inServiceDate", "2026-01-15")
            .containsEntry("sourceBillNo", POSTED.get("BILL-TS-5520").get("billNo"))
            .satisfies(a -> assertThat(amount(a.get("cost"))).isEqualByComparingTo("12000.00"));

        // The aging on 31 January adds up to the payables account (FIN-AP-009); the payments of F4c bring it to
        // FIN-EXP-09.
        List<Map<String, Object>> aging = report("finance.ap.aging", clerk, Map.of("agingDate", "2026-01-31"));
        assertThat(total(aging)).isEqualByComparingTo(balance("2000", "2026-01-31").negate())
            .isEqualByComparingTo("97600.00");
        assertThat(aging).filteredOn(r -> "TS-5520".equals(r.get("vendorInvoiceNo"))).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("bucket", "Current").containsEntry("dueDate", "2026-02-14"));
        assertThat(aging).filteredOn(r -> "P-7781".equals(r.get("vendorInvoiceNo"))).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("bucket", "1-30"));
        // The register lists them with their totals.
        assertThat(report("finance.ap.bill_register", clerk, Map.of("from", "2026-01-01", "to", "2026-01-31")))
            .hasSize(7);
    }

    @Test
    @Order(3)
    void aSecondEntryOfAVendorInvoiceIsRefusedAndASimilarOneNeedsAReason() {
        // P-7902 again, written differently: refused (FIN-AP-005 acceptance 1).
        Map<String, Object> again = bill("V100", "p 7902", "2026-01-30", List.of(line("Again", "100.00", "5000")));
        assertThat(refused(BillProcesses.SAVE, clerk, again, 422)).isEqualTo(BillProcesses.DUPLICATE);
        // Another number, same vendor, amount and date: a warning to confirm with a reason (acceptance 2).
        Map<String, Object> similar = bill("V100", "P-7903", "2026-01-09", List.of(line("Components", "22000.00",
            "5000")));
        assertThat(refused(BillProcesses.SAVE, clerk, similar, 422)).isEqualTo(BillProcesses.POSSIBLE_DUPLICATE);
        similar.put("duplicateReason", "Second delivery of the same order, confirmed with the vendor");
        String id = (String) ok(BillProcesses.SAVE, clerk, similar).get("billId");
        assertThat(read(BillEntities.BILL_DATASET, id)).containsEntry("duplicateReason",
            "Second delivery of the same order, confirmed with the vendor");
        ok(BillProcesses.DELETE, clerk, Map.of("billId", id));
        // A draft of the same number counts too.
        String draft = save("V500", "HI-77", "2026-01-30", List.of(line("Insurance", "900.00", "6600")));
        assertThat(refused(BillProcesses.SAVE, clerk, bill("V500", "HI-77", "2026-01-31",
            List.of(line("Insurance", "900.00", "6600"))), 422)).isEqualTo(BillProcesses.DUPLICATE);
        ok(BillProcesses.DELETE, clerk, Map.of("billId", draft));
    }

    @Test
    @Order(4)
    void aLargeBillIsPaidOnlyOnceTheControllerApprovesIt() {
        Map<String, Object> p7902 = POSTED.get("BILL-P-7902");
        // The clerk who entered it cannot approve it (FIN-CT-001), even holding the permission.
        run("APPROVAL_DECIDE", inRoles("ap-clerk", FinanceRoles.PAYABLES_CLERK, FinanceRoles.CONTROLLER),
            Map.of("requestId", p7902.get("approvalRequestId"), "decision", "APPROVE"))
            .expectStatus().is4xxClientError();
        // Nobody can say it was approved but the platform's approval request.
        run(BillProcesses.APPROVAL_RESULT, as("admin", "*"), Map.of("subject", BillProcesses.SUBJECT,
            "entityId", p7902.get("billId"), "status", "APPROVED", "requestId", p7902.get("approvalRequestId")))
            .expectStatus().isOk();
        assertThat(read(BillEntities.BILL_DATASET, p7902.get("billId"))).containsEntry("approval", "PENDING");
        ok("APPROVAL_DECIDE", controller, Map.of("requestId", p7902.get("approvalRequestId"), "decision", "APPROVE"));
        deliverer.deliverPending().block();
        assertThat(read(BillEntities.BILL_DATASET, p7902.get("billId"))).containsEntry("approval", "APPROVED");

        // A rejected one stays in the books, never paid, until voided.
        String id = save("V100", "P-7950", "2026-01-30", List.of(line("Components", "15000.00", "5000")));
        Map<String, Object> posted = ok(BillProcesses.POST, clerk, Map.of("billId", id));
        assertThat(posted).containsEntry("approval", "PENDING");
        ok("APPROVAL_DECIDE", controller, Map.of("requestId", posted.get("approvalRequestId"), "decision", "REJECT",
            "reason", "Not ordered"));
        deliverer.deliverPending().block();
        assertThat(read(BillEntities.BILL_DATASET, id)).containsEntry("approval", "REJECTED");
        ok(BillProcesses.VOID, controller, Map.of("billId", id, "voidDate", "2026-01-31", "reason", "Not ordered"));
        assertThat(postingLines((String) posted.get("billNo"))).isEmpty();
    }

    @Test
    @Order(4)
    void whoeverSavedADraftLastPostsItAndIsItsPreparer() {
        String other = inRoles("ap-clerk-2", FinanceRoles.PAYABLES_CLERK);
        String id = save("V500", "HI-0130", "2026-01-30", List.of(line("Insurance", "20000.00", "6600")));
        // Another clerk changes it: now theirs to post, and theirs never to approve.
        Map<String, Object> changed = bill("V500", "HI-0130", "2026-01-30", List.of(line("Insurance", "25000.00",
            "6600")));
        changed.put("billId", id);
        ok(BillProcesses.SAVE, other, changed);
        assertThat(refused(BillProcesses.POST, clerk, Map.of("billId", id), 422))
            .isEqualTo(BillProcesses.NOT_PREPARER);
        Map<String, Object> posted = ok(BillProcesses.POST, other, Map.of("billId", id));
        assertThat(posted).containsEntry("approval", "PENDING");
        run("APPROVAL_DECIDE", inRoles("ap-clerk-2", FinanceRoles.PAYABLES_CLERK, FinanceRoles.CONTROLLER),
            Map.of("requestId", posted.get("approvalRequestId"), "decision", "APPROVE"))
            .expectStatus().is4xxClientError();
        // Voided while it waits: the request and the approver's task go with it.
        ok(BillProcesses.VOID, controller, Map.of("billId", id, "voidDate", "2026-01-31", "reason", "Entered twice"));
        assertThat(read(com.jabiz.runtime.approval.ApprovalEntities.REQUEST_DATASET,
            posted.get("approvalRequestId"))).containsEntry("status", "WITHDRAWN");
        run("APPROVAL_DECIDE", controller, Map.of("requestId", posted.get("approvalRequestId"),
            "decision", "APPROVE")).expectStatus().is4xxClientError();
    }

    @Test
    @Order(5)
    void aVendorCreditAppliedToABillLeavesTheRestPayable() {
        // A bill of 1,200.00 and a credit of 500.00 against it (FIN-AP-008).
        String billId = save("V500", "HI-0201", "2026-01-29", List.of(line("Insurance", "1200.00", "6600")));
        ok(BillProcesses.POST, clerk, Map.of("billId", billId));
        Map<String, Object> credit = bill("V500", "HI-CR-0201", "2026-01-30", List.of(line("Premium refund",
            "500.00", "6600")));
        credit.put("kind", "CREDIT");
        credit.put("originalBillId", billId);
        String creditId = (String) ok(BillProcesses.SAVE, clerk, credit).get("billId");
        Map<String, Object> postedCredit = ok(BillProcesses.POST, clerk, Map.of("billId", creditId));
        assertThat(postedCredit).containsEntry("billNo", "VC-1").containsEntry("approval", "NOT_REQUIRED");
        assertThat(postingLines("VC-1")).isEqualTo(new TreeMap<>(Map.of("2000", new BigDecimal("500.00"),
            "6600", new BigDecimal("-500.00"))));
        Map<String, Object> applied = ok(BillProcesses.APPLY, clerk, Map.of("creditId", creditId, "billId", billId,
            "amount", "500.00", "applicationDate", "2026-01-30"));
        assertThat(amount(applied.get("billOpen"))).isEqualByComparingTo("700.00");
        assertThat(amount(applied.get("creditOpen"))).isEqualByComparingTo("0.00");
        assertThat(refused(BillProcesses.APPLY, clerk, Map.of("creditId", creditId, "billId", billId,
            "amount", "1.00", "applicationDate", "2026-01-30"), 422)).isEqualTo(BillProcesses.APPLY_REFUSED);
        // A credit of more than is left of its bill is refused.
        Map<String, Object> tooMuch = bill("V500", "HI-CR-0202", "2026-01-30", List.of(line("Refund", "800.00",
            "6600")));
        tooMuch.put("kind", "CREDIT");
        tooMuch.put("originalBillId", billId);
        String tooMuchId = (String) ok(BillProcesses.SAVE, clerk, tooMuch).get("billId");
        assertThat(refused(BillProcesses.POST, clerk, Map.of("billId", tooMuchId), 422))
            .isEqualTo(BillProcesses.EXCEEDS);
        // Taken back, both are open again, and the credit may be voided; a second take-back is refused.
        Map<String, Object> back = ok(BillProcesses.UNAPPLY, clerk, Map.of("applicationId",
            applied.get("applicationId"), "applicationDate", "2026-01-31", "reason", "Applied to the wrong bill"));
        assertThat(amount(back.get("billOpen"))).isEqualByComparingTo("1200.00");
        assertThat(amount(back.get("creditOpen"))).isEqualByComparingTo("500.00");
        assertThat(refused(BillProcesses.UNAPPLY, clerk, Map.of("applicationId", applied.get("applicationId"),
            "applicationDate", "2026-01-31", "reason", "Again"), 422)).isEqualTo(BillProcesses.UNAPPLY_REFUSED);
        ok(BillProcesses.VOID, controller, Map.of("billId", creditId, "voidDate", "2026-01-31",
            "reason", "Vendor withdrew the credit"));
        assertThat(postingLines("VC-1")).isEmpty();
        // A credit on an asset's cost account is refused: the register takes assets down from F6.
        Map<String, Object> assetCredit = bill("V700", "TS-CR-1", "2026-01-30", List.of(line("Returned server",
            "100.00", "1520")));
        assetCredit.put("kind", "CREDIT");
        assertThat(refused(BillProcesses.SAVE, clerk, assetCredit, 422)).isEqualTo(BillProcesses.ACCOUNT);
        // Still payable after all: the aging still adds up.
        assertThat(total(report("finance.ap.aging", clerk, Map.of("agingDate", "2026-01-31"))))
            .isEqualByComparingTo(balance("2000", "2026-01-31").negate());
    }

    @Test
    @Order(6)
    void useTaxAccruesOnATaxablePurchaseTheVendorChargedNoTaxOn() {
        // 1,000.00 in Austin, no tax on the vendor's invoice: 8.25 % use tax (FIN-TX-007).
        Map<String, Object> input = bill("V400", "CS-0226", "2026-01-30", List.of(line("Laptop stand", "1000.00",
            "6500")));
        ((Map<String, Object>) ((List<?>) input.get("lines")).getFirst()).put("useTaxCode", "TX-AUSTIN");
        String id = (String) ok(BillProcesses.SAVE, clerk, input).get("billId");
        Map<String, Object> posted = ok(BillProcesses.POST, clerk, Map.of("billId", id));
        assertThat(amount(posted.get("useTaxTotal"))).isEqualByComparingTo("82.50");
        assertThat(amount(posted.get("total"))).isEqualByComparingTo("1000.00");
        assertThat(postingLines((String) posted.get("billNo"))).isEqualTo(new TreeMap<>(Map.of(
            "6500", new BigDecimal("1082.50"), "2000", new BigDecimal("-1000.00"),
            "2210", new BigDecimal("-82.50"))));
        assertThat(find(BillEntities.TAX_DATASET, "billId", id)).extracting(t -> t.get("jurisdiction") + " "
            + amount(t.get("tax")).toPlainString()).contains("null 82.50");
        // A code that charges no tax is no use tax code.
        Map<String, Object> exempt = bill("V400", "CS-0227", "2026-01-30", List.of(line("Stand", "10.00", "6500")));
        ((Map<String, Object>) ((List<?>) exempt.get("lines")).getFirst()).put("useTaxCode", "OR-NONE");
        assertThat(refused(BillProcesses.SAVE, clerk, exempt, 422)).isEqualTo(BillProcesses.USE_TAX_CODE);
    }

    @Test
    @Order(7)
    void aVoidReversesTheBillAndItsAssetAndIsNeverThePreparers() {
        String id = save("V700", "TS-5590", "2026-01-30", List.of(line("Docking station", "2500.00", "1520")));
        Map<String, Object> posted = ok(BillProcesses.POST, clerk, Map.of("billId", id));
        String asset = ((List<?>) posted.get("assets")).getFirst().toString();
        assertThat(asset).isEqualTo("FA-004");
        // Voiding takes fin.bill.void, and not the preparer's own.
        run(BillProcesses.VOID, clerk, Map.of("billId", id, "voidDate", "2026-01-31", "reason", "Wrong"))
            .expectStatus().isForbidden();
        assertThat(refused(BillProcesses.VOID, inRoles("ap-clerk", FinanceRoles.CONTROLLER), Map.of("billId", id,
            "voidDate", "2026-01-31", "reason", "Wrong"), 422)).isEqualTo(BillProcesses.OWN_DOCUMENT);
        Map<String, Object> voided = ok(BillProcesses.VOID, controller, Map.of("billId", id, "voidDate", "2026-01-31",
            "reason", "Returned to the vendor"));
        assertThat(voided).containsEntry("status", "VOID");
        assertThat(postingLines((String) posted.get("billNo"))).isEmpty();
        assertThat(find(AssetEntities.ASSET_DATASET, "assetNo", asset).getFirst()).containsEntry("active", false);
        // A posted bill does not change.
        assertThat(refused(BillProcesses.POST, clerk, Map.of("billId", id), 422)).isEqualTo(BillProcesses.NOT_DRAFT);
        assertThat(total(report("finance.ap.aging", clerk, Map.of("agingDate", "2026-01-31"))))
            .isEqualByComparingTo(balance("2000", "2026-01-31").negate());
    }

    @Test
    @Order(8)
    void billsAreKeptThroughTheirProcessesOnly() {
        // A bill of many lines saves, changes and posts in one go.
        List<Map<String, Object>> many = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) {
            many.add(line("Part " + i, "1.00", "5000"));
        }
        String big = save("V100", "P-9000", "2026-01-30", many);
        Map<String, Object> again = bill("V100", "P-9000", "2026-01-30", many);
        again.put("billId", big);
        ok(BillProcesses.SAVE, clerk, again);
        assertThat(amount(ok(BillProcesses.POST, clerk, Map.of("billId", big)).get("total")))
            .isEqualByComparingTo("150.00");
        assertThat(commitRefused(BillEntities.BILL_DATASET, as("admin", "*"), Map.of("action", "INSERT",
            "attributes", Map.of("vendorCode", "V100")))).isNotBlank();
        // A control account, a revenue account and an unknown vendor are refused; so is a vendor in another currency.
        assertThat(refused(BillProcesses.SAVE, clerk, bill("V100", "X-1", "2026-01-30",
            List.of(line("Bad", "1.00", "2000"))), 422)).isEqualTo(BillProcesses.ACCOUNT);
        assertThat(refused(BillProcesses.SAVE, clerk, bill("V100", "X-2", "2026-01-30",
            List.of(line("Bad", "1.00", "4000"))), 422)).isEqualTo(BillProcesses.ACCOUNT);
        assertThat(refused(BillProcesses.SAVE, clerk, bill("V999", "X-3", "2026-01-30",
            List.of(line("Bad", "1.00", "6400"))), 422)).isEqualTo(BillProcesses.UNKNOWN_VENDOR);
        run(BillProcesses.SAVE, inRoles("treasurer", FinanceRoles.TREASURER), bill("V100", "X-4", "2026-01-30",
            List.of(line("Bad", "1.00", "6400")))).expectStatus().isForbidden();
        assertOnlyInserted("fi_bill_version", "fi_bill_line_version", "fi_bill_tax_version", "fi_ap_application_version", "fi_asset_version",
            "fi_posting_version");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static Map<String, Object> bill(String vendor, String number, String date,
        List<Map<String, Object>> lines) {
        Map<String, Object> input = new HashMap<>();
        input.put("vendorCode", vendor);
        input.put("vendorInvoiceNo", number);
        input.put("invoiceDate", date);
        input.put("lines", lines);
        return input;
    }

    private static Map<String, Object> line(String description, String amount, String account) {
        Map<String, Object> line = new HashMap<>();
        line.put("description", description);
        line.put("amount", amount);
        line.put("account", account);
        return line;
    }

    private String save(String vendor, String number, String date, List<Map<String, Object>> lines) {
        return (String) ok(BillProcesses.SAVE, clerk, bill(vendor, number, date, lines)).get("billId");
    }

    private void account(String code, String name, String type, String balance) {
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", code, "accountName", name,
            "financialType", AccountTypes.fromChart(type), "normalBalance", AccountTypes.normalBalanceFromChart(balance),
            "statementLine", name));
    }

    private static BigDecimal total(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> amount(r.get("openAmount"))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal balance(String account, String through) {
        return report("finance.gl.trial_balance", controller, Map.of("through", through)).stream()
            .filter(r -> account.equals(r.get("accountCode"))).map(r -> amount(r.get("balance"))).findFirst()
            .orElseThrow();
    }
}

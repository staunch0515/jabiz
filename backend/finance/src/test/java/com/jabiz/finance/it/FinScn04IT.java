package com.jabiz.finance.it;

import com.jabiz.finance.ap.ApSettingsProcesses;
import com.jabiz.finance.ap.BillEntities;
import com.jabiz.finance.ap.BillProcesses;
import com.jabiz.finance.ap.Form1099Entities;
import com.jabiz.finance.ap.Form1099Processes;
import com.jabiz.finance.ap.PaymentFiles;
import com.jabiz.finance.ap.PaymentProcesses;
import com.jabiz.finance.ap.VendorBankProcesses;
import com.jabiz.finance.ap.VendorProcesses;
import com.jabiz.finance.bank.BankAccountProcesses;
import com.jabiz.finance.calc.Form1099File;
import com.jabiz.finance.calc.NachaValidator;
import com.jabiz.finance.fa.AssetEntities;
import com.jabiz.finance.gl.AccountTypes;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-SCN-04, bills to payment with Form 1099 (docs/finance-requirements/30-acceptance-scenarios.md), as the payables
 * clerk, the controller and the treasurer do it: January's bills, the payment runs, the sales tax payment, and the
 * Forms 1099 of 2026 — the summary equal to FIN-EXP-14 and the aging equal to FIN-EXP-09. Then the rest of Form 1099
 * (ROADMAP F4d): card payments left out, voids taken back, a threshold changed for a test year, the review's
 * exceptions, recipient copies, the export for filing and a correction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FinScn04IT extends FinanceItSupport {

    private static boolean loaded;
    /** Bills by the vendor's number (open items by theirs). */
    private static final Map<String, String> BILLS = new TreeMap<>();
    private static Map<String, Object> v200Change;
    private static String payRun02;

    @Autowired
    OutboxDeliverer deliverer;

    private String clerk;
    private String controller;
    private String treasurer;

    @BeforeEach
    void books() {
        clerk = inRoles("ap-clerk", FinanceRoles.PAYABLES_CLERK);
        controller = inRoles("controller", FinanceRoles.CONTROLLER);
        treasurer = inRoles("treasurer", FinanceRoles.TREASURER);
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
        importCsv("finance.ap_thresholds", clerk, sampleText("thresholds-1099.csv"), "commit", null, null, 200);
        account("5900", "Purchase Discounts", "Expense", "C");
        account("2210", "Use Tax Payable", "Liability", "C");
        account("1310", "Vendor Prepayments", "Asset", "D");
        ok(BankAccountProcesses.SAVE, treasurer, Map.of("bankCode", "OPERATING", "bankName",
            "Lakeside National Bank", "glAccount", "1010", "routingNumber", "111000025",
            "companyAccountNumber", "000123456789", "achCompanyId", "1234567890", "achCompanyName", "NORTHWIND",
            "nextCheckNo", 10001));
        ok(ApSettingsProcesses.SET, controller, Map.of("payableAccount", "2000", "discountAccount", "5900",
            "useTaxAccount", "2210", "prepaymentAccount", "1310", "defaultBank", "OPERATING"));
        importCsv("finance.open_payables", as("migrator", "fin.migration", "fin.import", "fin.ap.read"),
            sampleText("open-payables.csv"), "commit", null, null, 200);
        ok("FIN_COMPANY_PROFILE_SET", controller, Map.of("legalName", "Northwind Components, Inc.",
            "street", "500 Congress Avenue", "city", "Austin", "state", "TX", "postalCode", "78701",
            "country", "United States", "taxId", "12-3456789"));
        approveBank("V100", "021000021", "100200300");
        approveBank("V300", "091000019", "300400500");
        approveBank("V600", "111000025", "600700800");
        approveBank("V800", "021000021", "800900100");
        approveBank("V200", "011000015", "200300400");
        // The 1099 vendors' W-9s and addresses; V800's TIN comes in step 6.
        ok(VendorProcesses.TAX_SAVE, clerk, Map.of("vendorCode", "V200", "tinType", "EIN", "tin", "45-1234567"));
        ok(VendorProcesses.TAX_SAVE, clerk, Map.of("vendorCode", "V300", "tinType", "EIN", "tin", "45-7654321"));
        for (String vendor : List.of("V200", "V300", "V800")) {
            ok(VendorProcesses.SAVE, clerk, Map.of("vendorCode", vendor, "remit", Map.of("street",
                "100 Main Street", "city", "Austin", "state", "TX", "postalCode", "78701", "country",
                "United States")));
        }
        find(BillEntities.BILL_DATASET, "source", "OPENING").forEach(b -> BILLS.put((String) b.get("billNo"),
            (String) b.get("billId")));
    }

    // ---- FIN-SCN-04 ------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    void step1JanuarysBillsAndTheLargeOneWaitsForTheController() {
        record Sample(String vendor, String number, String date, String account, String amount) {}
        for (Sample s : List.of(new Sample("V300", "MP-2026-01", "2026-01-02", "6200", "8500.00"),
            new Sample("V100", "P-7902", "2026-01-09", "5000", "22000.00"),
            new Sample("V700", "TS-5520", "2026-01-15", "1520", "12000.00"),
            new Sample("V200", "DC-2026-01", "2026-01-20", "6400", "7500.00"),
            new Sample("V400", "CS-0126", "2026-01-20", "6500", "1200.00"),
            new Sample("V800", "JR-014", "2026-01-21", "6400", "1500.00"),
            new Sample("V600", "CPL-0126", "2026-01-28", "6300", "3600.00"))) {
            Map<String, Object> posted = ok(BillProcesses.POST, clerk, Map.of("billId", saveBill(s.vendor(),
                s.number(), s.date(), s.account(), s.amount())));
            BILLS.put(s.number(), (String) posted.get("billId"));
            if (s.number().equals("P-7902")) {
                assertThat(posted).containsEntry("approval", "PENDING");
                // A second entry of the vendor's invoice is blocked.
                assertThat(refused(BillProcesses.SAVE, clerk, bill("V100", "P 7902", "2026-01-30", "5000", "1.00"),
                    422)).isEqualTo(BillProcesses.DUPLICATE);
                decide(posted.get("approvalRequestId"), "APPROVE");
            }
        }
        assertThat(read(BillEntities.BILL_DATASET, BILLS.get("P-7902"))).containsEntry("approval", "APPROVED");
    }

    @Test
    @Order(2)
    void step2TheServerBillMakesAnAsset() {
        assertThat(find(AssetEntities.ASSET_DATASET, "assetNo", "FA-003")).singleElement()
            .satisfies(a -> assertThat(a).containsEntry("sourceBillId", BILLS.get("TS-5520")));
    }

    @Test
    @Order(3)
    @SuppressWarnings("unchecked")
    void step3PayRun01IsApprovedReleasedWithASecondFactorAndItsFileValidates() {
        Map<String, Object> run = ok(PaymentProcesses.PROPOSE, clerk, Map.of("paymentDate", "2026-01-08",
            "method", "ACH", "dueThrough", "2026-01-20"));
        assertThat(run).containsEntry("runNo", "PAY-RUN-01");
        assertThat(amount(run.get("total"))).isEqualByComparingTo("32300.00");
        String runId = (String) run.get("runId");
        Map<String, Object> submitted = ok(PaymentProcesses.SUBMIT, clerk, Map.of("runId", runId));
        run("APPROVAL_DECIDE", clerk, Map.of("requestId", submitted.get("approvalRequestId"), "decision", "APPROVE"))
            .expectStatus().is4xxClientError();
        decide(submitted.get("approvalRequestId"), "APPROVE");
        run(PaymentProcesses.RELEASE, TestTokens.withoutMfa(tokens, "treasurer", permissions(FinanceRoles.TREASURER)),
            Map.of("runId", runId)).expectStatus().isForbidden();
        ok(PaymentProcesses.RELEASE, treasurer, Map.of("runId", runId));
        Map<String, Object> file = ok(PaymentFiles.GENERATE, treasurer, Map.of("runId", runId, "fileKind", "NACHA"));
        NachaValidator.Result checked = NachaValidator.validate(download((String) file.get("generatedFileId"),
            treasurer));
        assertThat(checked.problems()).isEmpty();
        assertThat(checked.entries()).isEqualTo(2);
        assertThat(checked.totalCredit()).isEqualByComparingTo("32300.00");
    }

    @Test
    @Order(4)
    @SuppressWarnings("unchecked")
    void step4AChangeOfV200sBankDetailsHoldsItsBillUntilASecondPersonApprovesIt() {
        v200Change = ok(VendorBankProcesses.CHANGE, clerk, Map.of("vendorCode", "V200", "bankName", "Other Bank",
            "routingNumber", "021000021", "bankAccountNumber", "222333444", "reason", "Vendor's letter"));
        Map<String, Object> run = ok(PaymentProcesses.PROPOSE, clerk, Map.of("paymentDate", "2026-01-22",
            "method", "ACH", "dueThrough", "2026-01-31", "vendorCodes", List.of("V200", "V300", "V800")));
        assertThat(run).containsEntry("runNo", "PAY-RUN-02").containsEntry("lineCount", 0);
        assertThat((List<Map<String, Object>>) run.get("held")).singleElement().satisfies(h -> assertThat(h)
            .containsEntry("billNo", "DC-2025-12").containsEntry("reason", PaymentProcesses.HOLD_BANK_PENDING));
        payRun02 = (String) run.get("runId");
        // The clerk who asked cannot approve it; the controller does, and the bill can be paid.
        run("APPROVAL_DECIDE", clerk, Map.of("requestId", v200Change.get("approvalRequestId"), "decision",
            "APPROVE")).expectStatus().is4xxClientError();
        decide(v200Change.get("approvalRequestId"), "APPROVE");
        ok(PaymentProcesses.ADD, clerk, Map.of("runId", payRun02, "billId", BILLS.get("DC-2025-12")));
    }

    @Test
    @Order(5)
    @SuppressWarnings("unchecked")
    void step5TheSalesTaxIsRecordedAndPayRun02IsPaid() throws Exception {
        List<Map<String, Object>> tax = pay("MANUAL", "2026-01-20", List.of(), Map.of("payee", "Texas Comptroller",
            "account", "2200", "amount", "3300.00", "description", "Texas sales tax return December 2025"));
        assertThat(postingLines((String) tax.getFirst().get("paymentNo"))).isEqualTo(Map.of(
            "2200", new BigDecimal("3300.00"), "1010", new BigDecimal("-3300.00")));

        ok(PaymentProcesses.ADD, clerk, Map.of("runId", payRun02, "billId", BILLS.get("MP-2026-01")));
        ok(PaymentProcesses.ADD, clerk, Map.of("runId", payRun02, "billId", BILLS.get("JR-014")));
        decide(ok(PaymentProcesses.SUBMIT, clerk, Map.of("runId", payRun02)).get("approvalRequestId"), "APPROVE");
        List<Map<String, Object>> payments = (List<Map<String, Object>>) ok(PaymentProcesses.RELEASE, treasurer,
            Map.of("runId", payRun02)).get("payments");
        Map<String, BigDecimal> lines = new TreeMap<>();
        payments.forEach(p -> postingLines((String) p.get("paymentNo")).forEach((a, v) -> lines.merge(a, v,
            BigDecimal::add)));
        assertThat(lines).isEqualTo(expectedDocuments("PAY-RUN-02", "payment").get("PAY-RUN-02"));
        // Its file, generated twice without cancelling: the second is refused (FIN-BK-011).
        ok(PaymentFiles.GENERATE, treasurer, Map.of("runId", payRun02, "fileKind", "NACHA"));
        assertThat(refused(PaymentFiles.GENERATE, treasurer, Map.of("runId", payRun02, "fileKind", "NACHA"), 422))
            .isEqualTo(PaymentFiles.EXISTS);
    }

    @Test
    @Order(6)
    void step6The1099ReportAndItsReviewAndTheAging() {
        // V800 has no TIN yet: the review lists it (FIN-AP-022 acceptance 1).
        assertThat(report("finance.ap.form_1099_review", clerk, Map.of("taxYear", 2026)))
            .extracting(r -> r.get("vendorCode") + " " + r.get("issue")).containsExactly("V800 NO_TIN");
        ok(VendorProcesses.TAX_SAVE, clerk, Map.of("vendorCode", "V800", "tinType", "SSN", "tin", "123-45-6789"));
        assertThat(report("finance.ap.form_1099_review", clerk, Map.of("taxYear", 2026))).isEmpty();

        // FIN-EXP-14: payments of 2026, by vendor, form and box, against the 2026 threshold of 2,000.00.
        assertThat(form1099(2026)).containsExactlyInAnyOrder("V200 NEC 1 9000.00 true", "V300 MISC 1 8500.00 true",
            "V800 NEC 1 1500.00 false");
        assertThat(report("finance.ap.form_1099", clerk, Map.of("taxYear", 2026))).allSatisfy(r ->
            assertThat(amount(r.get("threshold"))).isEqualByComparingTo("2000.00"));
        // MP-2026-01 paid by PAY-RUN-02 counts as rents (FIN-AP-020 acceptance 1).
        assertThat(find(Form1099Entities.AMOUNT_DATASET, "vendorCode", "V300")).singleElement().satisfies(a ->
            assertThat(a).containsEntry("form1099", "MISC").containsEntry("box1099", "1")
                .containsEntry("billNo", "BILL-1").containsEntry("source", "PAYMENT"));

        // FIN-EXP-09: the aging of 31 January equals the payables account.
        List<Map<String, Object>> aging = report("finance.ap.aging", clerk, Map.of("agingDate", "2026-01-31"));
        assertThat(aging.stream().map(r -> amount(r.get("openAmount"))).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("46300.00");
        assertThat(aging).extracting(r -> (String) r.get("vendorInvoiceNo")).containsExactlyInAnyOrder("P-7902",
            "TS-5520", "DC-2026-01", "CS-0126", "CPL-0126");
    }

    // ---- the rest of Form 1099 -------------------------------------------------------------------------------------

    @Test
    @Order(7)
    void cardPaymentsAreLeftOutAndVoidsAreTakenBack() {
        // V200 paid by company card in February: reported by the card processor on Form 1099-K (FIN-AP-020 acc. 2).
        String card = postBill("V200", "DC-2026-CARD", "2026-02-02", "6400", "400.00");
        pay("CARD", "2026-02-03", List.of(card), null);
        // V300 paid by check, then the check voided: nothing stays on its form.
        String extra = postBill("V300", "MP-2026-02X", "2026-02-02", "6200", "300.00");
        List<Map<String, Object>> paid = pay("CHECK", "2026-02-06", List.of(extra), null);
        assertThat(form1099(2026)).contains("V300 MISC 1 8800.00 true");
        ok(PaymentProcesses.VOID, controller, Map.of("paymentId", paid.getFirst().get("paymentId"),
            "voidDate", "2026-02-10", "reason", "Check lost"));
        assertThat(form1099(2026)).containsExactlyInAnyOrder("V200 NEC 1 9000.00 true", "V300 MISC 1 8500.00 true",
            "V800 NEC 1 1500.00 false");
        assertThat(find(Form1099Entities.AMOUNT_DATASET, "paymentId", paid.getFirst().get("paymentId")))
            .extracting(a -> a.get("source") + " " + amount(a.get("amount")).toPlainString())
            .containsExactlyInAnyOrder("PAYMENT 300.00", "VOID -300.00");
    }

    @Test
    @Order(8)
    void aThresholdChangedForATestYearMakesV800ReportableWithoutCode() {
        ok("FIN_FISCAL_YEAR_CREATE", controller(), Map.of("fiscalYear", 2027, "adjustmentPeriod", false));
        ok(ApSettingsProcesses.THRESHOLD_SET, clerk, Map.of("taxYear", 2027, "form1099", "NEC",
            "threshold", "2000.00"));
        String bill = postBill("V800", "JR-2701", "2027-01-05", "6400", "1500.00");
        pay("CHECK", "2027-01-06", List.of(bill), null);
        assertThat(form1099(2027)).containsExactly("V800 NEC 1 1500.00 false");
        // The table changed to 600.00 for the test year (FIN-AP-021 acceptance 2).
        ok(ApSettingsProcesses.THRESHOLD_SET, clerk, Map.of("taxYear", 2027, "form1099", "NEC",
            "threshold", "600.00"));
        assertThat(form1099(2027)).containsExactly("V800 NEC 1 1500.00 true");
    }

    @Test
    @Order(9)
    void recipientCopiesShowThePayersEinAndTheRecipientsTinTruncated() {
        Map<String, Object> copy = ok(Form1099Processes.ISSUE, controller, Map.of("taxYear", 2026,
            "vendorCode", "V200"));
        assertThat(copy).containsEntry("documentNo", "1099-2026-V200");
        String text = pdfText(documentPdf((String) copy.get("runId"), controller));
        assertThat(text).contains("Delta Consulting LLC", "Northwind Components, Inc.", "12-3456789",
            "**-***4567", "9,000.00", "Nonemployee compensation").doesNotContain("45-1234567");
        assertThat(refused(Form1099Processes.ISSUE, controller, Map.of("taxYear", 2026, "vendorCode", "V800"),
            422)).isEqualTo(Form1099Processes.NOT_REPORTABLE);
        run(Form1099Processes.ISSUE, clerk, Map.of("taxYear", 2026, "vendorCode", "V200"))
            .expectStatus().isForbidden();
    }

    @Test
    @Order(10)
    @SuppressWarnings("unchecked")
    void theExportHasARecordPerReportableBoxAndACorrectionIsMarked() {
        run(Form1099Processes.EXPORT, clerk, Map.of("taxYear", 2026)).expectStatus().isForbidden();
        Map<String, Object> export = ok(Form1099Processes.EXPORT, controller, Map.of("taxYear", 2026));
        assertThat((List<Map<String, Object>>) export.get("records"))
            .extracting(r -> r.get("vendorCode") + " " + r.get("form1099") + " " + r.get("box1099") + " "
                + amount(r.get("amount")).toPlainString() + " " + r.get("kind"))
            .containsExactly("V200 NEC 1 9000.00 ORIGINAL", "V300 MISC 1 8500.00 ORIGINAL");
        String file = download((String) export.get("fileId"), controller);
        assertThat(file.lines().toList()).hasSize(3).first().isEqualTo(Form1099File.HEADER);
        assertThat(file).contains("ORIGINAL,2026,NEC,1,9000.00,12-3456789,\"Northwind Components, Inc.\"",
            "EIN,45-1234567,Delta Consulting LLC,100 Main Street,Austin,TX,78701,V200,TX,9000.00");
        // Not for who lacks fin.1099.file: the platform does not even say the file exists.
        get("/api/generated-files/" + export.get("fileId"), treasurer).expectStatus().is4xxClientError();
        assertThat(refused(Form1099Processes.EXPORT, controller, Map.of("taxYear", 2026), 422))
            .isEqualTo(Form1099Processes.FILED);
        assertThat(refused(Form1099Processes.CORRECT, controller, Map.of("taxYear", 2026), 422))
            .isEqualTo(Form1099Processes.NOTHING);
        // The TINs filed are masked as the vendors' are.
        assertThat(find(Form1099Entities.FILING_DATASET, "vendorCode", "V200")).singleElement()
            .satisfies(f -> assertThat(f).containsEntry("tin", "**-***4567"));

        // 500.00 more paid to V200 in 2026 after filing: 9,000.00 corrected to 9,500.00 (FIN-AP-023).
        String more = postBill("V200", "DC-2026-02", "2026-02-04", "6400", "500.00");
        pay("MANUAL", "2026-02-05", List.of(more), null);
        Map<String, Object> corrected = ok(Form1099Processes.CORRECT, controller, Map.of("taxYear", 2026));
        assertThat((List<Map<String, Object>>) corrected.get("records"))
            .extracting(r -> r.get("vendorCode") + " " + amount(r.get("amount")).toPlainString() + " " + r.get("kind"))
            .containsExactly("V200 9500.00 CORRECTION");
        assertThat(download((String) corrected.get("fileId"), controller).lines().skip(1).toList()).singleElement()
            .satisfies(l -> assertThat(l).startsWith("CORRECTED,2026,NEC,1,9500.00,"));
        assertThat(refused(Form1099Processes.CORRECT, controller, Map.of("taxYear", 2026), 422))
            .isEqualTo(Form1099Processes.NOTHING);

        // V300's TIN corrected after filing: the same amount, refiled with the new TIN.
        ok(VendorProcesses.TAX_SAVE, clerk, Map.of("vendorCode", "V300", "tinType", "EIN", "tin", "45-7654322"));
        assertThat(corrections()).containsExactly("V300 MISC 1 8500.00 CORRECTION");
        // The threshold raised above V200's total: its record is corrected to nothing.
        ok(ApSettingsProcesses.THRESHOLD_SET, clerk, Map.of("taxYear", 2026, "form1099", "NEC",
            "threshold", "10000.00"));
        assertThat(corrections()).containsExactly("V200 NEC 1 0.00 CORRECTION");
        // Lowered to 1,000.00: V200 is reported again and V800, never filed, is filed late as an original.
        ok(ApSettingsProcesses.THRESHOLD_SET, clerk, Map.of("taxYear", 2026, "form1099", "NEC",
            "threshold", "1000.00"));
        assertThat(corrections()).containsExactly("V200 NEC 1 9500.00 CORRECTION", "V800 NEC 1 1500.00 ORIGINAL");
    }

    @Test
    @Order(11)
    void aCheckOfDecemberVoidedInJanuaryComesOffTheYearItWasCountedIn() {
        String bill = postBill("V300", "MP-2026-12X", "2026-12-15", "6200", "300.00");
        List<Map<String, Object>> paid = pay("CHECK", "2026-12-20", List.of(bill), null);
        ok(PaymentProcesses.VOID, controller, Map.of("paymentId", paid.getFirst().get("paymentId"),
            "voidDate", "2027-01-10", "reason", "Stopped"));
        assertThat(find(Form1099Entities.AMOUNT_DATASET, "paymentId", paid.getFirst().get("paymentId")))
            .extracting(a -> a.get("source") + " " + amount(a.get("taxYear")).intValue() + " "
                + amount(a.get("amount")).toPlainString() + " " + a.get("paymentDate"))
            .containsExactlyInAnyOrder("PAYMENT 2026 300.00 2026-12-20", "VOID 2026 -300.00 2027-01-10");
        assertThat(form1099(2026)).contains("V300 MISC 1 8500.00 true");
        assertThat(form1099(2027)).noneMatch(r -> r.startsWith("V300"));
        assertOnlyInserted("fi_1099_amount_version", "fi_1099_filing_version", "fi_payment_version",
            "fi_ap_application_version");
    }

    /** A correction export of 2026: its records as vendor, form, box, amount and kind. */
    @SuppressWarnings("unchecked")
    private List<String> corrections() {
        return ((List<Map<String, Object>>) ok(Form1099Processes.CORRECT, controller, Map.of("taxYear", 2026))
            .get("records")).stream().map(r -> r.get("vendorCode") + " " + r.get("form1099") + " " + r.get("box1099")
                + " " + amount(r.get("amount")).toPlainString() + " " + r.get("kind")).toList();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** The 1099 report of a year: vendor, form, box, amount and whether it is reported. */
    private List<String> form1099(int year) {
        return report("finance.ap.form_1099", clerk, Map.of("taxYear", year)).stream()
            .map(r -> r.get("vendorCode") + " " + r.get("form1099") + " " + r.get("box1099") + " "
                + amount(r.get("amount")).toPlainString() + " " + r.get("reportable")).toList();
    }

    /** A run of the bills (and another payment) proposed, approved and released; its payments. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pay(String method, String date, List<String> bills, Map<String, Object> other) {
        String runId = (String) ok(PaymentProcesses.PROPOSE, clerk, Map.of("paymentDate", date, "method", method))
            .get("runId");
        for (String bill : bills) {
            ok(PaymentProcesses.ADD, clerk, Map.of("runId", runId, "billId", bill));
        }
        if (other != null) {
            Map<String, Object> line = new HashMap<>(other);
            line.put("runId", runId);
            ok(PaymentProcesses.ADD, clerk, line);
        }
        decide(ok(PaymentProcesses.SUBMIT, clerk, Map.of("runId", runId)).get("approvalRequestId"), "APPROVE");
        return (List<Map<String, Object>>) ok(PaymentProcesses.RELEASE, treasurer, Map.of("runId", runId))
            .get("payments");
    }

    private String download(String fileId, String authorization) {
        return new String(get("/api/generated-files/" + fileId, authorization).expectStatus().isOk()
            .expectBody(byte[].class).returnResult().getResponseBody(), StandardCharsets.UTF_8);
    }

    private void decide(Object requestId, String decision) {
        ok("APPROVAL_DECIDE", controller, Map.of("requestId", requestId, "decision", decision));
        deliverer.deliverPending().block();
    }

    private void approveBank(String vendor, String routing, String account) {
        decide(ok(VendorBankProcesses.CHANGE, clerk, Map.of("vendorCode", vendor, "bankName", "Some Bank",
            "routingNumber", routing, "bankAccountNumber", account, "reason", "Vendor set-up form"))
            .get("approvalRequestId"), "APPROVE");
    }

    private static Map<String, Object> bill(String vendor, String number, String date, String account,
        String amount) {
        return new HashMap<>(Map.of("vendorCode", vendor, "vendorInvoiceNo", number, "invoiceDate", date,
            "lines", List.of(Map.of("description", number, "amount", amount, "account", account))));
    }

    private String saveBill(String vendor, String number, String date, String account, String amount) {
        return (String) ok(BillProcesses.SAVE, clerk, bill(vendor, number, date, account, amount)).get("billId");
    }

    private String postBill(String vendor, String number, String date, String account, String amount) {
        return (String) ok(BillProcesses.POST, clerk, Map.of("billId", saveBill(vendor, number, date, account,
            amount))).get("billId");
    }

    private static String[] permissions(String role) {
        return FinanceRoles.all().stream().filter(r -> r.code().equals(role)).findFirst().orElseThrow()
            .permissions().toArray(String[]::new);
    }

    private void account(String code, String name, String type, String balance) {
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", code, "accountName", name,
            "financialType", AccountTypes.fromChart(type), "normalBalance", AccountTypes.normalBalanceFromChart(balance),
            "statementLine", name));
    }
}

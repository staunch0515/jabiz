package com.jabiz.finance.it;

import com.jabiz.finance.ap.ApSettingsProcesses;
import com.jabiz.finance.ap.BillEntities;
import com.jabiz.finance.ap.BillProcesses;
import com.jabiz.finance.ap.PaymentEntities;
import com.jabiz.finance.ap.PaymentFiles;
import com.jabiz.finance.ap.PaymentProcesses;
import com.jabiz.finance.ap.VendorBankProcesses;
import com.jabiz.finance.ar.CustomerProcesses;
import com.jabiz.finance.bank.BankAccountProcesses;
import com.jabiz.finance.bank.BankEntities;
import com.jabiz.finance.calc.NachaValidator;
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
 * Payment runs (ROADMAP F4c): the sample's January runs PAY-RUN-01 and PAY-RUN-02 proposed, approved by another, released
 * with a second factor and posted as FIN-EXP-02 has them, with their NACHA files; the sales tax payment STX-PAY-2512
 * paid outside the bank files and a wire with its instruction file; a check run in February with a discount, a prepayment and a voided check; and the aging of 31 January equal to
 * FIN-EXP-09.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentIT extends FinanceItSupport {

    /** The schema lives as long as the class: the books, the open payables and January's bills are loaded once. */
    private static boolean loaded;
    /** January's bills by the vendor's number. */
    private static final Map<String, String> BILLS = new TreeMap<>();
    /** V200's bank change, waiting for approval until the second run. */
    private static Map<String, Object> v200Change;

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
        // The vendors paid by ACH give their accounts; V200's waits for approval (FIN-AP-003).
        approveBank("V100", "021000021", "100200300");
        approveBank("V300", "091000019", "300400500");
        approveBank("V600", "111000025", "600700800");
        approveBank("V800", "021000021", "800900100");
        approveBank("V700", "021000021", "700800900");
        v200Change = ok(VendorBankProcesses.CHANGE, clerk, bankChange("V200", "011000015", "200300400"));
        // FIN-EXP-02's January bills; P-7902 is approved by the controller.
        record Sample(String vendor, String number, String date, String account, String amount) {}
        for (Sample s : List.of(new Sample("V300", "MP-2026-01", "2026-01-02", "6200", "8500.00"),
            new Sample("V100", "P-7902", "2026-01-09", "5000", "22000.00"),
            new Sample("V700", "TS-5520", "2026-01-15", "1520", "12000.00"),
            new Sample("V200", "DC-2026-01", "2026-01-20", "6400", "7500.00"),
            new Sample("V400", "CS-0126", "2026-01-20", "6500", "1200.00"),
            new Sample("V800", "JR-014", "2026-01-21", "6400", "1500.00"),
            new Sample("V600", "CPL-0126", "2026-01-28", "6300", "3600.00"))) {
            Map<String, Object> posted = ok(BillProcesses.POST, clerk, Map.of("billId", saveBill(s.vendor(),
                s.number(), s.date(), s.account(), s.amount(), null)));
            BILLS.put(s.number(), (String) posted.get("billId"));
            if ("PENDING".equals(posted.get("approval"))) {
                decide(posted.get("approvalRequestId"), "APPROVE");
            }
        }
        find(BillEntities.BILL_DATASET, "source", "OPENING").forEach(b -> BILLS.put((String) b.get("billNo"),
            (String) b.get("billId")));
    }

    @Test
    @Order(1)
    @SuppressWarnings("unchecked")
    void payRun01PaysWhatIsDueAndHoldsAVendorWhoseBankDetailsWait() throws Exception {
        // On 8 January, the approved bills due by the 20th (FIN-AP-010).
        Map<String, Object> proposed = ok(PaymentProcesses.PROPOSE, clerk, Map.of("paymentDate", "2026-01-08",
            "method", "ACH", "dueThrough", "2026-01-20", "description", "ACH payment run 01"));
        assertThat(proposed).containsEntry("runNo", "PAY-RUN-01").containsEntry("status", "DRAFT")
            .containsEntry("lineCount", 2);
        assertThat(amount(proposed.get("total"))).isEqualByComparingTo("32300.00");
        assertThat((List<?>) proposed.get("held")).isEmpty();
        String runId = (String) proposed.get("runId");
        assertThat(find(PaymentEntities.LINE_DATASET, "runId", runId)).extracting(l -> (String) l.get("billNo"))
            .containsExactlyInAnyOrder("P-7781", "CPL-1225");
        // DC-2025-12 is held while V200's new bank details wait for approval (FIN-AP-003).
        var held = run(PaymentProcesses.ADD, clerk, Map.of("runId", runId, "billId", BILLS.get("DC-2025-12")))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(held.toString()).contains(PaymentProcesses.HELD, PaymentProcesses.HOLD_BANK_PENDING);
        // So is a bill not yet approved, and a bill already in the run.
        Map<String, Object> waiting = ok(BillProcesses.POST, clerk, Map.of("billId", saveBill("V100", "P-7999",
            "2026-01-05", "5000", "10500.00", null)));
        assertThat(refused(PaymentProcesses.ADD, clerk, Map.of("runId", runId, "billId", waiting.get("billId")),
            422)).isEqualTo(PaymentProcesses.HELD);
        assertThat(refused(PaymentProcesses.ADD, clerk, Map.of("runId", runId, "billId", BILLS.get("P-7781")),
            422)).isEqualTo(PaymentProcesses.HELD);
        // An ACH run pays vendors' approved accounts only.
        assertThat(refused(PaymentProcesses.ADD, clerk, Map.of("runId", runId, "payee", "Texas Comptroller",
            "account", "2200", "amount", "1.00"), 422)).isEqualTo(PaymentProcesses.OTHER_METHOD);
        decide(waiting.get("approvalRequestId"), "REJECT");
        ok(BillProcesses.VOID, controller, Map.of("billId", waiting.get("billId"), "voidDate", "2026-01-31",
            "reason", "Not ordered"));

        // The clerk submits; the clerk cannot approve it, the controller does (FIN-AP-011, FIN-CT-001).
        Map<String, Object> submitted = ok(PaymentProcesses.SUBMIT, clerk, Map.of("runId", runId));
        assertThat(submitted).containsEntry("status", "SUBMITTED");
        run("APPROVAL_DECIDE", inRoles("ap-clerk", FinanceRoles.PAYABLES_CLERK, FinanceRoles.CONTROLLER),
            Map.of("requestId", submitted.get("approvalRequestId"), "decision", "APPROVE"))
            .expectStatus().is4xxClientError();
        assertThat(refused(PaymentProcesses.ADD, clerk, Map.of("runId", runId, "billId", BILLS.get("DC-2025-12")),
            422)).isEqualTo(PaymentProcesses.NOT_DRAFT);
        assertThat(refused(PaymentProcesses.RELEASE, treasurer, Map.of("runId", runId), 422))
            .isEqualTo(PaymentProcesses.NOT_APPROVED);
        decide(submitted.get("approvalRequestId"), "APPROVE");
        assertThat(read(PaymentEntities.RUN_DATASET, runId)).containsEntry("status", "APPROVED")
            .containsEntry("approvedBy", "controller").containsEntry("preparedBy", "ap-clerk");

        // Released by the treasurer with a second factor (FIN-SC-001); the clerk never releases.
        run(PaymentProcesses.RELEASE, TestTokens.withoutMfa(tokens, "treasurer", permissions(FinanceRoles.TREASURER)),
            Map.of("runId", runId)).expectStatus().isForbidden();
        run(PaymentProcesses.RELEASE, clerk, Map.of("runId", runId)).expectStatus().isForbidden();
        Map<String, Object> released = ok(PaymentProcesses.RELEASE, treasurer, Map.of("runId", runId));
        assertThat(released).containsEntry("status", "RELEASED");
        List<Map<String, Object>> payments = (List<Map<String, Object>>) released.get("payments");
        assertThat(payments).extracting(p -> p.get("vendorCode") + " " + amount(p.get("amount")).toPlainString())
            .containsExactlyInAnyOrder("V100 28300.00", "V600 4000.00");
        // Together they are PAY-RUN-01 of FIN-EXP-02; the bills are paid and link to their payments (FIN-AP-012).
        assertThat(runLines(payments)).isEqualTo(expectedDocuments("PAY-RUN-01", "payment").get("PAY-RUN-01"));
        for (String bill : List.of("P-7781", "CPL-1225")) {
            assertThat(amount(read(BillEntities.BILL_DATASET, BILLS.get(bill)).get("openAmount"))).isZero();
            assertThat(find(BillEntities.APPLICATION_DATASET, "billId", BILLS.get(bill))).singleElement()
                .satisfies(a -> assertThat(a).containsEntry("sourceKind", "PAYMENT"));
        }

        // The NACHA file: one CCD batch of two entries, 32,300.00, as a bank checks it (FIN-AP-013).
        Map<String, Object> file = ok(PaymentFiles.GENERATE, treasurer, Map.of("runId", runId,
            "fileKind", "NACHA"));
        assertThat(file).containsEntry("fileName", "PAY-RUN-01-ACH.txt").containsEntry("entryCount", 2);
        String nacha = download((String) file.get("generatedFileId"), treasurer);
        assertThat(sha256(nacha.getBytes(StandardCharsets.UTF_8))).isEqualTo(file.get("sha256"));
        NachaValidator.Result checked = NachaValidator.validate(nacha);
        assertThat(checked.problems()).isEmpty();
        assertThat(checked.entries()).isEqualTo(2);
        assertThat(checked.batches()).isEqualTo(1);
        assertThat(checked.totalCredit()).isEqualByComparingTo("32300.00");
        assertThat(nacha.lines().filter(l -> l.startsWith("5"))).singleElement()
            .satisfies(l -> assertThat(l.substring(50, 53)).isEqualTo("CCD"));
        assertThat(nacha).contains("100200300", "600700800", "PRECISION PARTS CO.");
        // One file of a kind: a second is refused until the first is cancelled, with why.
        assertThat(refused(PaymentFiles.GENERATE, treasurer, Map.of("runId", runId, "fileKind", "NACHA"), 422))
            .isEqualTo(PaymentFiles.EXISTS);
        assertThat(refused(PaymentFiles.GENERATE, treasurer, Map.of("runId", runId, "fileKind", "CHECKS"), 422))
            .isEqualTo(PaymentFiles.WRONG_KIND);
        // Only who releases payments reads it: it holds the account numbers in full.
        get("/api/generated-files/" + file.get("generatedFileId"), clerk).expectStatus().isForbidden();
        ok(PaymentFiles.CANCEL, treasurer, Map.of("paymentFileId", file.get("paymentFileId"),
            "reason", "The bank refused the file: wrong effective date"));
        Map<String, Object> again = ok(PaymentFiles.GENERATE, treasurer, Map.of("runId", runId, "fileKind", "NACHA"));
        String second = download((String) again.get("generatedFileId"), treasurer);
        assertThat(second.substring(33, 34)).isEqualTo("B");
        assertThat(NachaValidator.validate(second).problems()).isEmpty();
        // A payment in a file the bank has is not voided: the money is gone.
        assertThat(refused(PaymentProcesses.VOID, controller, Map.of("paymentId", payments.getFirst().get("paymentId"),
            "voidDate", "2026-01-31", "reason", "Wrong vendor"), 422)).isEqualTo(PaymentProcesses.SENT);
    }

    @Test
    @Order(2)
    @SuppressWarnings("unchecked")
    void payRun02PaysThreeVendorsInACorporateAndAPersonalBatch() throws Exception {
        // V200's new bank details are approved: DC-2025-12 can be paid.
        decide(v200Change.get("approvalRequestId"), "APPROVE");
        Map<String, Object> proposed = ok(PaymentProcesses.PROPOSE, clerk, Map.of("paymentDate", "2026-01-22",
            "method", "ACH", "dueThrough", "2026-01-31", "vendorCodes", List.of("V200", "V300", "V800"),
            "description", "ACH payment run 02"));
        assertThat(proposed).containsEntry("runNo", "PAY-RUN-02").containsEntry("lineCount", 1);
        assertThat((List<?>) proposed.get("held")).isEmpty();
        String runId = (String) proposed.get("runId");
        ok(PaymentProcesses.ADD, clerk, Map.of("runId", runId, "billId", BILLS.get("MP-2026-01")));
        // A bill added by mistake is removed again.
        Map<String, Object> mistake = ok(PaymentProcesses.ADD, clerk, Map.of("runId", runId,
            "billId", BILLS.get("DC-2026-01")));
        assertThat(amount(mistake.get("total"))).isEqualByComparingTo("25000.00");
        Map<String, Object> wrongLine = find(PaymentEntities.LINE_DATASET, "runId", runId).stream()
            .filter(l -> BILLS.get("DC-2026-01").equals(l.get("billId"))).findFirst().orElseThrow();
        ok(PaymentProcesses.REMOVE, clerk, Map.of("runId", runId, "lineId", wrongLine.get("lineId")));
        Map<String, Object> added = ok(PaymentProcesses.ADD, clerk, Map.of("runId", runId,
            "billId", BILLS.get("JR-014")));
        assertThat(amount(added.get("total"))).isEqualByComparingTo("19000.00");
        assertThat(added).containsEntry("lineCount", 3);
        // A bill in an open run is held from another, and one not approved is never added.
        Map<String, Object> other = ok(PaymentProcesses.PROPOSE, clerk, Map.of("paymentDate", "2026-01-22",
            "method", "CHECK", "dueThrough", "2026-01-31", "vendorCodes", List.of("V200")));
        assertThat((List<Map<String, Object>>) other.get("held")).singleElement().satisfies(h -> assertThat(
            (String) h.get("reason")).startsWith(PaymentProcesses.HOLD_IN_RUN).contains("PAY-RUN-02"));
        ok(PaymentProcesses.CANCEL, clerk, Map.of("runId", other.get("runId"), "reason", "Proposed by mistake"));

        Map<String, Object> submitted = ok(PaymentProcesses.SUBMIT, clerk, Map.of("runId", runId));
        decide(submitted.get("approvalRequestId"), "APPROVE");
        Map<String, Object> released = ok(PaymentProcesses.RELEASE, treasurer, Map.of("runId", runId));
        List<Map<String, Object>> payments = (List<Map<String, Object>>) released.get("payments");
        assertThat(payments).hasSize(3);
        assertThat(runLines(payments)).isEqualTo(expectedDocuments("PAY-RUN-02", "payment").get("PAY-RUN-02"));
        // The payment to V200 goes to the account approved, not the one replaced.
        Map<String, Object> toV200 = find(PaymentEntities.PAYMENT_DATASET, "vendorCode", "V200").getFirst();
        assertThat(toV200.get("vendorBankAccountId")).isEqualTo(v200Change.get("bankAccountId"));

        // V800 is a person: a PPD batch; V200 and V300 are businesses: a CCD batch.
        Map<String, Object> file = ok(PaymentFiles.GENERATE, treasurer, Map.of("runId", runId,
            "fileKind", "NACHA"));
        NachaValidator.Result checked = NachaValidator.validate(download((String) file.get("generatedFileId"),
            treasurer));
        assertThat(checked.problems()).isEmpty();
        assertThat(checked.batches()).isEqualTo(2);
        assertThat(checked.entries()).isEqualTo(3);
        assertThat(checked.totalCredit()).isEqualByComparingTo("19000.00");
        // Generated twice without cancelling: refused (FIN-BK-011).
        assertThat(refused(PaymentFiles.GENERATE, treasurer, Map.of("runId", runId, "fileKind", "NACHA"), 422))
            .isEqualTo(PaymentFiles.EXISTS);
    }

    @Test
    @Order(3)
    void theSalesTaxIsPaidOutsideTheFilesAndAWireCarriesTheAccountsInFull() {
        // STX-PAY-2512: another payment, to the sales tax payable, paid on the Comptroller's portal (FIN-AP-015);
        // a control account is never paid this way.
        Map<String, Object> proposed = ok(PaymentProcesses.PROPOSE, clerk, Map.of("paymentDate", "2026-01-20",
            "method", "MANUAL"));
        String runId = (String) proposed.get("runId");
        assertThat(refused(PaymentProcesses.ADD, clerk, Map.of("runId", runId, "payee", "Texas Comptroller",
            "account", "2000", "amount", "3300.00"), 422)).isEqualTo(PaymentProcesses.ACCOUNT);
        ok(PaymentProcesses.ADD, clerk, Map.of("runId", runId, "payee", "Texas Comptroller", "account", "2200",
            "amount", "3300.00", "description", "Texas sales tax return December 2025"));
        Map<String, Object> submitted = ok(PaymentProcesses.SUBMIT, clerk, Map.of("runId", runId));
        decide(submitted.get("approvalRequestId"), "APPROVE");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> payments = (List<Map<String, Object>>) ok(PaymentProcesses.RELEASE, treasurer,
            Map.of("runId", runId)).get("payments");
        assertThat(postingLines((String) payments.getFirst().get("paymentNo"))).isEqualTo(Map.of(
            "2200", new BigDecimal("3300.00"), "1010", new BigDecimal("-3300.00")));
        assertThat(refused(PaymentFiles.GENERATE, treasurer, Map.of("runId", runId, "fileKind", "WIRE"), 422))
            .isEqualTo(PaymentFiles.WRONG_KIND);

        // TS-5520 wired on 14 February: the instructions carry the accounts in full, for the bank.
        Map<String, Object> wireRun = ok(PaymentProcesses.PROPOSE, clerk, Map.of("paymentDate", "2026-02-14",
            "method", "WIRE", "dueThrough", "2026-02-14", "vendorCodes", List.of("V700")));
        String wireRunId = (String) wireRun.get("runId");
        assertThat(amount(wireRun.get("total"))).isEqualByComparingTo("12000.00");
        assertThat(refused(PaymentProcesses.ADD, clerk, Map.of("runId", wireRunId, "payee", "Texas Comptroller",
            "account", "2200", "amount", "1.00"), 422)).isEqualTo(PaymentProcesses.OTHER_METHOD);
        decide(ok(PaymentProcesses.SUBMIT, clerk, Map.of("runId", wireRunId)).get("approvalRequestId"), "APPROVE");
        ok(PaymentProcesses.RELEASE, treasurer, Map.of("runId", wireRunId));
        Map<String, Object> wire = ok(PaymentFiles.GENERATE, treasurer, Map.of("runId", wireRunId,
            "fileKind", "WIRE"));
        String instructions = download((String) wire.get("generatedFileId"), treasurer);
        assertThat(sha256(instructions.getBytes(StandardCharsets.UTF_8))).isEqualTo(wire.get("sha256"));
        assertThat(instructions.lines().skip(1).toList()).singleElement().satisfies(l -> assertThat(l)
            .contains("2026-02-14,USD,12000.00,111000025,000123456789,\"TechSource, Inc.\",Some Bank,021000021,"
                + "700800900"));
    }

    @Test
    @Order(4)
    @SuppressWarnings("unchecked")
    void aCheckRunTakesADiscountPaysAPrepaymentAndAVoidedCheckReopensItsBill() {
        // February: V700's new bill on 2% 10 days; a check run on the 5th takes the discount (FIN-AP-010).
        ok(CustomerProcesses.TERMS_SAVE, inRoles("controller", FinanceRoles.CONTROLLER), Map.of("termsCode",
            "2/10NET30", "description", "2% 10, net 30", "netDays", 30, "discountPercent", "2.00",
            "discountDays", 10));
        String discounted = (String) ok(BillProcesses.POST, clerk, Map.of("billId", saveBill("V700", "TS-5601",
            "2026-02-02", "6500", "1000.00", "2/10NET30"))).get("billId");
        Map<String, Object> proposed = ok(PaymentProcesses.PROPOSE, clerk, Map.of("paymentDate", "2026-02-05",
            "method", "CHECK", "dueThrough", "2026-02-05", "vendorCodes", List.of("V700"), "takeDiscounts", true));
        assertThat(amount(proposed.get("total"))).isEqualByComparingTo("980.00");
        String runId = (String) proposed.get("runId");
        // CS-0126 by check, and a prepayment to V500 (FIN-AP-008).
        ok(PaymentProcesses.ADD, clerk, Map.of("runId", runId, "billId", BILLS.get("CS-0126")));
        ok(PaymentProcesses.ADD, clerk, Map.of("runId", runId, "kind", "PREPAYMENT", "vendorCode", "V500",
            "amount", "1000.00", "description", "Deposit on the 2026 policy"));
        Map<String, Object> submitted = ok(PaymentProcesses.SUBMIT, clerk, Map.of("runId", runId));
        decide(submitted.get("approvalRequestId"), "APPROVE");
        List<Map<String, Object>> payments = (List<Map<String, Object>>) ok(PaymentProcesses.RELEASE, treasurer,
            Map.of("runId", runId)).get("payments");
        // Checks numbered from the bank account's stock.
        assertThat(payments).extracting(p -> p.get("vendorCode") + " " + p.get("checkNo") + " "
            + amount(p.get("amount")).toPlainString())
            .containsExactly("V400 10001 1200.00", "V700 10002 980.00", "V500 10003 1000.00");
        assertThat(find(BankEntities.BANK_ACCOUNT_DATASET, "bankCode", "OPERATING").getFirst().get("nextCheckNo"))
            .satisfies(n -> assertThat(amount(n)).isEqualByComparingTo("10004"));
        Map<String, Object> v700 = payments.get(1);
        assertThat(postingLines((String) v700.get("paymentNo"))).isEqualTo(Map.of("2000", new BigDecimal("1000.00"),
            "5900", new BigDecimal("-20.00"), "1010", new BigDecimal("-980.00")));
        assertThat(amount(read(BillEntities.BILL_DATASET, discounted).get("openAmount"))).isZero();
        Map<String, Object> prepayment = payments.get(2);
        assertThat(postingLines((String) prepayment.get("paymentNo"))).isEqualTo(Map.of(
            "1310", new BigDecimal("1000.00"), "1010", new BigDecimal("-1000.00")));

        // The check file and the positive pay file list the checks.
        Map<String, Object> checks = ok(PaymentFiles.GENERATE, treasurer, Map.of("runId", runId,
            "fileKind", "CHECKS"));
        assertThat(download((String) checks.get("generatedFileId"), treasurer)).contains(
            "10001,2026-02-05,\"CloudStack, Inc.\",1200.00,One thousand two hundred and 00/100");
        Map<String, Object> positive = ok(PaymentFiles.GENERATE, treasurer, Map.of("runId", runId,
            "fileKind", "POSITIVE_PAY"));
        assertThat(download((String) positive.get("generatedFileId"), treasurer).lines().skip(1).toList())
            .containsExactly("000123456789,10001,2026-02-05,1200.00,\"CloudStack, Inc.\",I",
                "000123456789,10002,2026-02-05,980.00,\"TechSource, Inc.\",I",
                "000123456789,10003,2026-02-05,1000.00,Hartwell Insurance Company,I");

        // The prepayment is applied to V500's bill when it comes: debit payables, credit prepayments.
        String policy = (String) ok(BillProcesses.POST, clerk, Map.of("billId", saveBill("V500", "HI-2026",
            "2026-02-06", "6600", "900.00", null))).get("billId");
        Map<String, Object> applied = ok(PaymentProcesses.PREPAYMENT_APPLY, clerk, Map.of("paymentId",
            prepayment.get("paymentId"), "billId", policy, "amount", "900.00", "applicationDate", "2026-02-06"));
        assertThat(amount(applied.get("billOpen"))).isZero();
        assertThat(amount(applied.get("prepaymentOpen"))).isEqualByComparingTo("100.00");
        assertThat(refused(PaymentProcesses.PREPAYMENT_APPLY, clerk, Map.of("paymentId", prepayment.get("paymentId"),
            "billId", BILLS.get("TS-5520"), "amount", "50.00", "applicationDate", "2026-02-06"), 422))
            .isEqualTo(PaymentProcesses.APPLY_REFUSED);

        // Check 10001 is stopped and voided on 10 February: reversed then, and CS-0126 open again (FIN-AP-014).
        Map<String, Object> toV400 = payments.getFirst();
        run(PaymentProcesses.VOID, clerk, Map.of("paymentId", toV400.get("paymentId"), "voidDate", "2026-02-10",
            "reason", "Check lost in the mail")).expectStatus().isForbidden();
        run(PaymentProcesses.VOID, TestTokens.withoutMfa(tokens, "controller", permissions(FinanceRoles.CONTROLLER)),
            Map.of("paymentId", toV400.get("paymentId"), "voidDate", "2026-02-10", "reason", "No second factor"))
            .expectStatus().isForbidden();
        Map<String, Object> voided = ok(PaymentProcesses.VOID, controller, Map.of("paymentId",
            toV400.get("paymentId"), "voidDate", "2026-02-10", "reason", "Check lost in the mail"));
        assertThat(voided).containsEntry("status", "VOID").containsEntry("billsReopened", List.of("BILL-5"));
        assertThat(amount(read(BillEntities.BILL_DATASET, BILLS.get("CS-0126")).get("openAmount")))
            .isEqualByComparingTo("1200.00");
        assertThat(postingLines((String) toV400.get("paymentNo"))).isEmpty();
        assertThat(refused(PaymentProcesses.VOID, controller, Map.of("paymentId", toV400.get("paymentId"),
            "voidDate", "2026-02-11", "reason", "Again"), 422)).isEqualTo(PaymentProcesses.NOT_POSTED);
        // The bank is told: a new positive pay file marks the check void.
        ok(PaymentFiles.CANCEL, treasurer, Map.of("paymentFileId", positive.get("paymentFileId"),
            "reason", "Check 10001 voided"));
        Map<String, Object> renewed = ok(PaymentFiles.GENERATE, treasurer, Map.of("runId", runId,
            "fileKind", "POSITIVE_PAY"));
        assertThat(download((String) renewed.get("generatedFileId"), treasurer))
            .contains("000123456789,10001,2026-02-05,1200.00,\"CloudStack, Inc.\",V");
    }

    @Test
    @Order(5)
    void aRejectedRunIsADraftAgainAndNobodyElseSaysWhatWasDecided() {
        Map<String, Object> proposed = ok(PaymentProcesses.PROPOSE, clerk, Map.of("paymentDate", "2026-01-30",
            "method", "ACH", "dueThrough", "2026-02-19", "vendorCodes", List.of("V200")));
        String runId = (String) proposed.get("runId");
        Map<String, Object> submitted = ok(PaymentProcesses.SUBMIT, clerk, Map.of("runId", runId));
        // Only the platform's request decides: a forged result changes nothing.
        ok(PaymentProcesses.APPROVAL_RESULT, as("admin", "*"), Map.of("subject", PaymentProcesses.SUBJECT,
            "entityId", runId, "status", "APPROVED", "requestId", submitted.get("approvalRequestId")));
        assertThat(read(PaymentEntities.RUN_DATASET, runId)).containsEntry("status", "SUBMITTED");
        run(PaymentProcesses.APPROVAL_RESULT, controller, Map.of("subject", PaymentProcesses.SUBJECT,
            "entityId", runId, "status", "APPROVED")).expectStatus().isForbidden();
        decide(submitted.get("approvalRequestId"), "REJECT");
        assertThat(read(PaymentEntities.RUN_DATASET, runId)).containsEntry("status", "DRAFT")
            .containsEntry("approvalRequestId", null);
        // Who changes it submits it again; a cancelled run frees its bills.
        assertThat(refused(PaymentProcesses.SUBMIT, inRoles("ap-clerk-2", FinanceRoles.PAYABLES_CLERK),
            Map.of("runId", runId), 422)).isEqualTo(PaymentProcesses.NOT_PREPARER);
        ok(PaymentProcesses.CANCEL, clerk, Map.of("runId", runId, "reason", "Paid next week"));
        assertThat(read(PaymentEntities.RUN_DATASET, runId)).containsEntry("status", "CANCELLED");
        assertThat(refused(PaymentProcesses.RELEASE, treasurer, Map.of("runId", runId), 422))
            .isEqualTo(PaymentProcesses.NOT_APPROVED);
    }

    @Test
    @Order(6)
    void theAgingOfJanuaryIsTheExpectedResult() {
        // FIN-EXP-09: what is open on 31 January, by due date, adds up to the payables account (FIN-AP-009).
        List<Map<String, Object>> aging = report("finance.ap.aging", clerk, Map.of("agingDate", "2026-01-31"));
        assertThat(aging).extracting(r -> r.get("vendorCode") + " " + r.get("vendorInvoiceNo") + " "
            + r.get("bucket") + " " + amount(r.get("openAmount")).toPlainString())
            .containsExactlyInAnyOrder("V100 P-7902 Current 22000.00", "V700 TS-5520 Current 12000.00",
                "V200 DC-2026-01 Current 7500.00", "V400 CS-0126 Current 1200.00", "V600 CPL-0126 Current 3600.00");
        BigDecimal total = aging.stream().map(r -> amount(r.get("openAmount"))).reduce(BigDecimal.ZERO,
            BigDecimal::add);
        assertThat(total).isEqualByComparingTo("46300.00");
        assertThat(report("finance.gl.trial_balance", controller, Map.of("through", "2026-01-31")).stream()
            .filter(r -> "2000".equals(r.get("accountCode"))).map(r -> amount(r.get("balance"))).findFirst()
            .orElseThrow()).isEqualByComparingTo("-46300.00");
        // V200's statement for January: DC-2025-12 owed at the start, DC-2026-01 billed, PAY-RUN-02 pays the first; the
        // closing balance is V200's aging.
        List<Map<String, Object>> statement = report("finance.ap.vendor_statement", clerk, Map.of("vendorCode", "V200",
            "from", "2026-01-01", "to", "2026-01-31"));
        assertThat(statement).extracting(r -> r.get("entry") + " " + amount(r.get("balance")).toPlainString())
            .containsExactly("OPENING 9000.00", "BILL 16500.00", "PAYMENT 7500.00", "CLOSING 7500.00");
        // A payment names the bill it paid, to be ticked off against the vendor's own statement.
        assertThat(statement).filteredOn(r -> "PAYMENT".equals(r.get("entry")))
            .extracting(r -> r.get("reference")).containsExactly("DC-2025-12");
        // The registers of January: the runs paid then and their payments.
        assertThat(report("finance.ap.payment_run_register", clerk, Map.of("from", "2026-01-01", "to", "2026-01-31",
            "status", "RELEASED"))).extracting(r -> (String) r.get("runNo")).contains("PAY-RUN-01", "PAY-RUN-02");
        List<Map<String, Object>> payments = report("finance.ap.payment_register", clerk, Map.of("from", "2026-01-01",
            "to", "2026-01-31", "status", "POSTED", "method", "ACH"));
        assertThat(payments.stream().map(r -> amount(r.get("amount"))).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("51300.00");
        assertOnlyInserted("fi_payment_run_version", "fi_payment_line_version", "fi_payment_version",
            "fi_payment_file_version", "fi_bill_version", "fi_ap_application_version", "fi_posting_version");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** The ledger lines of a run's payments together. */
    private static Map<String, BigDecimal> runLines(List<Map<String, Object>> payments) {
        Map<String, BigDecimal> lines = new TreeMap<>();
        for (Map<String, Object> payment : payments) {
            postingLines((String) payment.get("paymentNo")).forEach((a, v) -> lines.merge(a, v, BigDecimal::add));
        }
        return lines;
    }

    private String download(String fileId, String authorization) {
        return new String(get("/api/generated-files/" + fileId, authorization).expectStatus().isOk()
            .expectBody(byte[].class).returnResult().getResponseBody(), StandardCharsets.UTF_8);
    }

    private void decide(Object requestId, String decision) {
        Map<String, Object> input = new HashMap<>(Map.of("requestId", requestId, "decision", decision));
        if ("REJECT".equals(decision)) {
            input.put("reason", "Not this week");
        }
        ok("APPROVAL_DECIDE", controller, input);
        deliverer.deliverPending().block();
    }

    private void approveBank(String vendor, String routing, String account) {
        decide(ok(VendorBankProcesses.CHANGE, clerk, bankChange(vendor, routing, account)).get("approvalRequestId"),
            "APPROVE");
    }

    private static Map<String, Object> bankChange(String vendor, String routing, String account) {
        return Map.of("vendorCode", vendor, "bankName", "Some Bank", "routingNumber", routing,
            "bankAccountNumber", account, "reason", "Vendor set-up form");
    }

    private String saveBill(String vendor, String number, String date, String account, String amount, String terms) {
        Map<String, Object> input = new HashMap<>(Map.of("vendorCode", vendor, "vendorInvoiceNo", number,
            "invoiceDate", date, "lines", List.of(Map.of("description", number, "amount", amount,
                "account", account))));
        if (terms != null) {
            input.put("termsCode", terms);
        }
        return (String) ok(BillProcesses.SAVE, clerk, input).get("billId");
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

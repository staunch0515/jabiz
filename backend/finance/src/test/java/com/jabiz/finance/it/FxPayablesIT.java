package com.jabiz.finance.it;

import com.jabiz.finance.ap.ApSettingsProcesses;
import com.jabiz.finance.ap.BillEntities;
import com.jabiz.finance.ap.BillProcesses;
import com.jabiz.finance.ap.Form1099Entities;
import com.jabiz.finance.ap.PaymentFiles;
import com.jabiz.finance.ap.PaymentProcesses;
import com.jabiz.finance.ap.VendorBankProcesses;
import com.jabiz.finance.ap.VendorProcesses;
import com.jabiz.finance.bank.BankAccountProcesses;
import com.jabiz.finance.fx.FxRates;
import com.jabiz.finance.fx.FxSettingsProcesses;
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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payables in euros (ROADMAP F7b; FIN-FX-003, 004): a bill posted at the rate of its day, approved by its dollars, paid
 * by wire at the payment day's rate with the loss realized and the wire in euros; a vendor credit at another rate and
 * a partial manual payment realizing their differences, taken back and voided; the aging in dollars equal to the
 * payables account throughout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FxPayablesIT extends FinanceItSupport {

    private static boolean loaded;
    private static final Map<String, String> IDS = new LinkedHashMap<>();

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
        openReceivables();
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
        ok(FxSettingsProcesses.SET, controller, Map.of("realizedAccount", "7200", "unrealizedAccount", "7210"));
        // A German supplier billing in euros, paid by wire and reported on Form 1099-NEC.
        Map<String, Object> vendor = new HashMap<>(Map.of("vendorCode", "V900", "legalName", "Rheinwerk GmbH",
            "currency", "EUR", "termsDays", 30, "expenseAccount", "6400", "paymentMethod", "WIRE",
            "entityType", "C_CORPORATION", "form1099", "NEC"));
        ok(VendorProcesses.SAVE, clerk, vendor);
        decide(ok(VendorBankProcesses.CHANGE, clerk, Map.of("vendorCode", "V900", "bankName", "Rheinbank",
            "routingNumber", "021000021", "bankAccountNumber", "900100200", "reason", "Vendor set-up form"))
            .get("approvalRequestId"), "APPROVE");
    }

    @Test
    @Order(1)
    void aEuroBillIsPostedAtTheRateOfItsDayAndApprovedByItsDollars() {
        // EUR 9,500.00 on 12 January at 1.0850: 10,307.50, above the 10,000 the bill rule asks about.
        Map<String, Object> posted = ok(BillProcesses.POST, clerk, Map.of("billId", saveBill("RW-1001", "2026-01-12",
            "9500.00", null)));
        IDS.put("bill", (String) posted.get("billId"));
        assertThat(posted).containsEntry("approval", "PENDING");
        decide(posted.get("approvalRequestId"), "APPROVE");
        assertThat(bill(IDS.get("bill"))).containsEntry("currency", "EUR").satisfies(b -> {
            assertThat(new BigDecimal(String.valueOf(b.get("exchangeRate")))).isEqualByComparingTo("1.0850");
            assertThat(amount(b.get("totalUsd"))).isEqualByComparingTo("10307.50");
            assertThat(amount(b.get("openAmountUsd"))).isEqualByComparingTo("10307.50");
        });
        assertThat(postingLines((String) posted.get("billNo")))
            .isEqualTo(amounts("2000", "-10307.50", "6400", "10307.50"));
        // Use tax accrues on bills in dollars only; a day eight days after the last rate has none.
        Map<String, Object> taxed = billInput("RW-1002", "2026-01-12", "100.00", null);
        taxed.put("lines", List.of(Map.of("description", "Tooling", "amount", "100.00", "account", "6400",
            "useTaxCode", "TX-AUSTIN")));
        assertThat(refused(BillProcesses.POST, clerk, Map.of("billId", ok(BillProcesses.SAVE, clerk, taxed)
            .get("billId")), 422)).isEqualTo(BillProcesses.CURRENCY);
        assertThat(refused(BillProcesses.POST, clerk, Map.of("billId", saveBill("RW-1003", "2026-01-21", "100.00",
            null)), 422)).isEqualTo(FxRates.NO_RATE);
        agingEqualsTheLedger("2026-01-31");
    }

    @Test
    @Order(2)
    @SuppressWarnings("unchecked")
    void aWireInEurosPaysItAtThePaymentDaysRateAndRealizesTheLoss() {
        // Euros are wired or paid outside the files, never by ACH or check.
        assertThat(refused(PaymentProcesses.PROPOSE, clerk, Map.of("paymentDate", "2026-01-31", "method", "ACH",
            "dueThrough", "2026-02-28", "currency", "EUR"), 422)).isEqualTo(PaymentProcesses.FOREIGN);
        // A run in dollars holds the euro bill.
        String dollars = (String) ok(PaymentProcesses.PROPOSE, clerk, Map.of("paymentDate", "2026-01-31",
            "method", "WIRE")).get("runId");
        assertThat(refused(PaymentProcesses.ADD, clerk, Map.of("runId", dollars, "billId", IDS.get("bill")), 422))
            .isEqualTo(PaymentProcesses.HELD);
        ok(PaymentProcesses.CANCEL, clerk, Map.of("runId", dollars, "reason", "Wrong currency"));

        // On 31 January at 1.0920: EUR 9,500.00 is 10,374.00; the bill carries 10,307.50, a loss of 66.50.
        Map<String, Object> proposed = ok(PaymentProcesses.PROPOSE, clerk, Map.of("paymentDate", "2026-01-31",
            "method", "WIRE", "dueThrough", "2026-02-28", "vendorCodes", List.of("V900"), "currency", "EUR"));
        String runId = (String) proposed.get("runId");
        assertThat(amount(proposed.get("total"))).isEqualByComparingTo("9500.00");
        assertThat(refused(PaymentProcesses.ADD, clerk, Map.of("runId", runId, "kind", "PREPAYMENT",
            "vendorCode", "V900", "amount", "100.00"), 422)).isEqualTo(PaymentProcesses.FOREIGN);
        decide(ok(PaymentProcesses.SUBMIT, clerk, Map.of("runId", runId)).get("approvalRequestId"), "APPROVE");
        List<Map<String, Object>> paid = (List<Map<String, Object>>) ok(PaymentProcesses.RELEASE, treasurer,
            Map.of("runId", runId)).get("payments");
        String paymentNo = (String) paid.getFirst().get("paymentNo");
        assertThat(postingLines(paymentNo)).isEqualTo(amounts("1010", "-10374.00", "2000", "10307.50",
            "7200", "66.50"));
        assertThat(find(com.jabiz.finance.ap.PaymentEntities.PAYMENT_DATASET, "paymentNo", paymentNo))
            .singleElement().satisfies(p -> {
                assertThat(p).containsEntry("currency", "EUR");
                assertThat(amount(p.get("amount"))).isEqualByComparingTo("9500.00");
                assertThat(amount(p.get("amountUsd"))).isEqualByComparingTo("10374.00");
            });
        assertThat(bill(IDS.get("bill"))).satisfies(b -> {
            assertThat(amount(b.get("openAmount"))).isZero();
            assertThat(amount(b.get("openAmountUsd"))).isZero();
        });
        assertThat(find(BillEntities.APPLICATION_DATASET, "billId", IDS.get("bill"))).singleElement()
            .satisfies(a -> assertThat(amount(a.get("fxGainLoss"))).isEqualByComparingTo("-66.50"));
        // The vendor received 10,374.00 dollars' worth: so its 1099 counts.
        assertThat(find(Form1099Entities.AMOUNT_DATASET, "vendorCode", "V900")).singleElement()
            .satisfies(a -> assertThat(amount(a.get("amount"))).isEqualByComparingTo("10374.00"));
        // The wire is in euros.
        Map<String, Object> wire = ok(PaymentFiles.GENERATE, treasurer, Map.of("runId", runId, "fileKind", "WIRE"));
        assertThat(download((String) wire.get("generatedFileId"))).contains("2026-01-31,EUR,9500.00,");
        agingEqualsTheLedger("2026-01-31");
    }

    @Test
    @Order(3)
    void aCreditAtAnotherRateRealizesTheDifferenceAndIsTakenBack() {
        // EUR 5,000.00 on 14 January (the 12th's 1.0850: 5,425.00) and a credit of EUR 1,000.00 on the 31st at
        // 1.0920 (1,092.00): applied, the bill gives up 1,085.00 and the credit 1,092.00, a loss of 7.00.
        String bill = (String) ok(BillProcesses.POST, clerk, Map.of("billId", saveBill("RW-1010", "2026-01-14",
            "5000.00", null))).get("billId");
        IDS.put("second", bill);
        Map<String, Object> credit = ok(BillProcesses.POST, clerk, Map.of("billId", saveBill("RW-CN-1",
            "2026-01-31", "1000.00", "CREDIT")));
        String creditNo = (String) credit.get("billNo");
        Map<String, Object> applied = ok(BillProcesses.APPLY, clerk, Map.of("creditId", credit.get("billId"),
            "billId", bill, "amount", "1000.00", "applicationDate", "2026-02-02"));
        assertThat(postingLines(creditNo)).isEqualTo(amounts("2000", "1085.00", "6400", "-1092.00",
            "7200", "7.00"));
        assertThat(amount(bill(bill).get("openAmountUsd"))).isEqualByComparingTo("4340.00");
        assertThat(amount(bill((String) credit.get("billId")).get("openAmountUsd"))).isZero();
        agingEqualsTheLedger("2026-02-02");
        ok(BillProcesses.UNAPPLY, clerk, Map.of("applicationId", applied.get("applicationId"),
            "applicationDate", "2026-02-03", "reason", "Credit for another delivery"));
        assertThat(postingLines(creditNo)).isEqualTo(amounts("2000", "1092.00", "6400", "-1092.00"));
        assertThat(amount(bill(bill).get("openAmountUsd"))).isEqualByComparingTo("5425.00");
        assertThat(amount(bill((String) credit.get("billId")).get("openAmountUsd"))).isEqualByComparingTo("1092.00");
        agingEqualsTheLedger("2026-02-03");
    }

    @Test
    @Order(4)
    @SuppressWarnings("unchecked")
    void aPartialManualPaymentRealizesAGainAndItsVoidTakesItBack() {
        // EUR 2,000.00 of the 5,000.00 paid on 20 February at 1.0800: 2,160.00 for what carries 2,170.00, a gain.
        String runId = (String) ok(PaymentProcesses.PROPOSE, clerk, Map.of("paymentDate", "2026-02-20",
            "method", "MANUAL", "currency", "EUR")).get("runId");
        ok(PaymentProcesses.ADD, clerk, Map.of("runId", runId, "billId", IDS.get("second"), "amount", "2000.00"));
        decide(ok(PaymentProcesses.SUBMIT, clerk, Map.of("runId", runId)).get("approvalRequestId"), "APPROVE");
        Map<String, Object> payment = ((List<Map<String, Object>>) ok(PaymentProcesses.RELEASE, treasurer,
            Map.of("runId", runId)).get("payments")).getFirst();
        assertThat(postingLines((String) payment.get("paymentNo"))).isEqualTo(amounts("1010", "-2160.00",
            "2000", "2170.00", "7200", "-10.00"));
        assertThat(bill(IDS.get("second"))).satisfies(b -> {
            assertThat(amount(b.get("openAmount"))).isEqualByComparingTo("3000.00");
            assertThat(amount(b.get("openAmountUsd"))).isEqualByComparingTo("3255.00");
        });
        agingEqualsTheLedger("2026-02-20");
        // Voided, the bill is open again at its own dollars and the gain goes.
        ok(PaymentProcesses.VOID, controller, Map.of("paymentId", payment.get("paymentId"),
            "voidDate", "2026-02-21", "reason", "Sent twice"));
        assertThat(bill(IDS.get("second"))).satisfies(b -> {
            assertThat(amount(b.get("openAmount"))).isEqualByComparingTo("5000.00");
            assertThat(amount(b.get("openAmountUsd"))).isEqualByComparingTo("5425.00");
        });
        agingEqualsTheLedger("2026-02-28");
        // The wire's loss is all that is left.
        assertThat(ledgerBalances("2026-02-28").get("7200")).isEqualByComparingTo("66.50");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** The payables' dollars on the aging equal the payables account on the day (a credit balance). */
    private void agingEqualsTheLedger(String day) {
        BigDecimal aging = report("finance.ap.aging", controller, Map.of("agingDate", day)).stream()
            .map(r -> amount(r.get("openAmountUsd"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(aging.negate()).as(day).isEqualByComparingTo(ledgerBalances(day).getOrDefault("2000",
            BigDecimal.ZERO));
    }

    private Map<String, Object> bill(String id) {
        return read(BillEntities.BILL_DATASET, id);
    }

    private static Map<String, Object> billInput(String number, String date, String amount, String kind) {
        Map<String, Object> input = new HashMap<>(Map.of("vendorCode", "V900", "vendorInvoiceNo", number,
            "invoiceDate", date, "lines", List.of(Map.of("description", number, "amount", amount,
                "account", "6400"))));
        if (kind != null) {
            input.put("kind", kind);
        }
        return input;
    }

    private String saveBill(String number, String date, String amount, String kind) {
        return (String) ok(BillProcesses.SAVE, clerk, billInput(number, date, amount, kind)).get("billId");
    }

    private static Map<String, BigDecimal> amounts(String... pairs) {
        Map<String, BigDecimal> amounts = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            amounts.put(pairs[i], new BigDecimal(pairs[i + 1]));
        }
        return amounts;
    }

    private String download(String fileId) {
        return new String(get("/api/generated-files/" + fileId, treasurer).expectStatus().isOk()
            .expectBody(byte[].class).returnResult().getResponseBody(), StandardCharsets.UTF_8);
    }

    private void decide(Object requestId, String decision) {
        ok("APPROVAL_DECIDE", controller, Map.of("requestId", requestId, "decision", decision));
        deliverer.deliverPending().block();
    }

    private void account(String code, String name, String type, String balance) {
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", code, "accountName", name,
            "financialType", AccountTypes.fromChart(type), "normalBalance", AccountTypes.normalBalanceFromChart(balance),
            "statementLine", name));
    }
}

package com.jabiz.finance.it;

import com.jabiz.finance.ap.ApEntities;
import com.jabiz.finance.ap.ApSettingsProcesses;
import com.jabiz.finance.ap.VendorBankProcesses;
import com.jabiz.finance.ap.VendorProcesses;
import com.jabiz.finance.bank.BankAccountProcesses;
import com.jabiz.finance.bank.BankEntities;
import com.jabiz.finance.gl.AccountTypes;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.finance.setup.SetupProcesses;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payables' master data (ROADMAP F4a): the sample company's vendors and 1099 thresholds imported as they are,
 * vendors' TINs masked but to the tax-data permission, vendors' bank changes taking effect only once another person
 * approves, the payables settings, the company's bank accounts, and the segregation of payables from releasing
 * payments.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PayablesMasterIT extends FinanceItSupport {

    /** The schema lives as long as the class: the books, the vendors and thresholds are loaded once. */
    private static boolean loaded;
    /** The segregation rules are held back for the conflict report's test, which publishes them. */
    private static Map<String, String> heldSod = Map.of();

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
        heldSod = openBooksHolding(SetupProcesses.SOD_VENDOR_BANK, SetupProcesses.SOD_PAYABLES);
        Map<String, Object> vendors = importCsv("finance.vendors", clerk, sampleText("vendors.csv"), "commit", null,
            null, 200);
        assertThat(vendors).containsEntry("committed", true).containsEntry("units", 8);
        Map<String, Object> thresholds = importCsv("finance.ap_thresholds", clerk, sampleText("thresholds-1099.csv"),
            "commit", null, null, 200);
        assertThat(thresholds).containsEntry("committed", true).containsEntry("units", 2);
    }

    // ---- FIN-AP-001: the vendor file -------------------------------------------------------------------------------

    @Test
    void theSampleVendorsImportWithTheirEntityTypesAnd1099Settings() {
        Map<String, Map<String, Object>> vendors = new LinkedHashMap<>();
        for (String code : List.of("V100", "V200", "V300", "V400", "V500", "V600", "V700", "V800")) {
            vendors.put(code, find(ApEntities.VENDOR_DATASET, "vendorCode", code).getFirst());
        }
        assertThat(vendors.get("V100")).containsEntry("legalName", "Precision Parts Co.")
            .containsEntry("entityType", "C_CORPORATION").containsEntry("form1099", null)
            .containsEntry("termsCode", "NET30").containsEntry("currency", "USD").containsEntry("w9OnFile", true)
            .containsEntry("status", "ACTIVE");
        assertThat(vendors.get("V200")).containsEntry("entityType", "SINGLE_MEMBER_LLC")
            .containsEntry("form1099", "NEC").containsEntry("box1099", "1");
        assertThat(vendors.get("V300")).containsEntry("entityType", "PARTNERSHIP")
            .containsEntry("form1099", "MISC").containsEntry("box1099", "1");
        assertThat(vendors.get("V400")).containsEntry("legalName", "CloudStack, Inc.");
        assertThat(vendors.get("V600")).containsEntry("entityType", "GOVERNMENT").containsEntry("termsCode", "NET23");
        assertThat(vendors.get("V800")).containsEntry("entityType", "INDIVIDUAL").containsEntry("form1099", "NEC")
            .containsEntry("box1099", "1");
        assertThat(vendors.values()).filteredOn(v -> v.get("form1099") != null).extracting(v -> v.get("vendorCode"))
            .containsExactly("V200", "V300", "V800");

        // One row that does not read refuses the whole file (FIN-DI-001).
        String code = "VX" + unique();
        Map<String, Object> refused = importCsv("finance.vendors", clerk, "id,name,entity_type,form_1099\n"
            + code + ",Good Co.,C corporation,\n" + code + "B,Bad Co.,cooperative,\n", "commit", null, null, 422);
        assertThat(refused.toString()).contains("cooperative");
        assertThat(find(ApEntities.VENDOR_DATASET, "vendorCode", code)).isEmpty();
    }

    @Test
    void aVendorIsKeptThroughItsProcessOnly() {
        String code = "VN" + unique();
        Map<String, Object> created = ok(VendorProcesses.SAVE, clerk, vendor(code, "Northstar Freight LLC",
            Map.of("termsDays", 15, "paymentMethod", "check", "form1099", "MISC", "box1099", "3",
                "expenseAccount", "6400")));
        assertThat(created).containsEntry("created", true);
        assertThat(find(ApEntities.VENDOR_DATASET, "vendorCode", code).getFirst())
            .containsEntry("termsCode", "NET15").containsEntry("paymentMethod", "CHECK")
            .containsEntry("form1099", "MISC").containsEntry("box1099", "3").containsEntry("expenseAccount", "6400");

        assertThat(refused(VendorProcesses.SAVE, clerk, Map.of("vendorCode", code, "form1099", "NEC",
            "box1099", "3"), 422)).isEqualTo(VendorProcesses.INVALID_BOX);
        assertThat(refused(VendorProcesses.SAVE, clerk, Map.of("vendorCode", code, "expenseAccount", "2000"), 422))
            .isEqualTo(VendorProcesses.UNKNOWN_ACCOUNT);
        assertThat(refused(VendorProcesses.SAVE, clerk, Map.of("vendorCode", "VM" + unique(), "legalName", "M"), 422))
            .isEqualTo(VendorProcesses.MISSING);
        // The box changes alone, within the vendor's form.
        ok(VendorProcesses.SAVE, clerk, Map.of("vendorCode", code, "box1099", "10"));
        assertThat(find(ApEntities.VENDOR_DATASET, "vendorCode", code).getFirst())
            .containsEntry("form1099", "MISC").containsEntry("box1099", "10");
        // An empty form clears the 1099 setting.
        ok(VendorProcesses.SAVE, clerk, Map.of("vendorCode", code, "form1099", ""));
        assertThat(find(ApEntities.VENDOR_DATASET, "vendorCode", code).getFirst())
            .containsEntry("form1099", null).containsEntry("box1099", null);
        // Not through the dataset API, and not by someone who does not keep vendors.
        assertThat(commitRefused(ApEntities.VENDOR_DATASET, as("admin", "*"), Map.of("action", "INSERT",
            "attributes", Map.of("vendorCode", "VZ" + unique())))).isNotBlank();
        run(VendorProcesses.SAVE, inRoles("treasurer", FinanceRoles.TREASURER), Map.of("vendorCode", code))
            .expectStatus().isForbidden();
    }

    // ---- FIN-AP-002, FIN-SC-004: the TIN ---------------------------------------------------------------------------

    @Test
    void theTinIsMaskedButToTheTaxDataPermissionAndEveryDisplayIsRecorded() {
        // The clerk records V800's W-9 and sees the SSN masked from then on.
        ok(VendorProcesses.TAX_SAVE, clerk, Map.of("vendorCode", "V800", "tinType", "SSN", "tin", "123451234",
            "w9Date", "2025-11-03"));
        Map<String, Object> taxInfo = find(ApEntities.TAX_INFO_DATASET, "vendorCode", "V800").getFirst();
        assertThat(taxInfo).containsEntry("tin", "***-**-1234").containsEntry("tinType", "SSN")
            .containsEntry("tinStatus", "UNVERIFIED").containsEntry("backupWithholding", false);
        String id = String.valueOf(taxInfo.get("taxInfoId"));
        post("/api/datasets/" + ApEntities.TAX_INFO_DATASET + "/reveal", clerk, Map.of("id", id, "field", "tin"))
            .expectStatus().isForbidden();

        // The controller holds the tax-data permission: one value at a time, on the record.
        Map<String, Object> revealed = post("/api/datasets/" + ApEntities.TAX_INFO_DATASET + "/reveal", controller,
            Map.of("id", id, "field", "tin")).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(revealed).containsEntry("value", "123-45-1234");
        assertThat(query("SELECT kind, entity, fields FROM sys_reveal_record WHERE actor_id = 'controller' "
            + "AND entity = ?", ApEntities.TAX_INFO)).singleElement()
            .satisfies(row -> assertThat(String.valueOf(row.get("fields"))).contains("tin"));
        // The operation record keeps no TIN either.
        assertThat(query("SELECT input_summary::text AS s FROM op_process WHERE process_name = ?",
            VendorProcesses.TAX_SAVE)).allSatisfy(row -> assertThat(String.valueOf(row.get("s")))
                .doesNotContain("123451234").doesNotContain("123-45-1234"));

        // An EIN reads as an EIN; a masked or impossible number is refused.
        ok(VendorProcesses.TAX_SAVE, clerk, Map.of("vendorCode", "V300", "tinType", "EIN", "tin", "45-6789012"));
        assertThat(find(ApEntities.TAX_INFO_DATASET, "vendorCode", "V300").getFirst())
            .containsEntry("tin", "**-***9012");
        assertThat(refused(VendorProcesses.TAX_SAVE, clerk, Map.of("vendorCode", "V800", "tinType", "SSN",
            "tin", "***-**-1234"), 422)).isEqualTo(VendorProcesses.INVALID_TIN);
        assertThat(refused(VendorProcesses.TAX_SAVE, clerk, Map.of("vendorCode", "V800", "tinType", "SSN",
            "tin", "666-12-3456"), 422)).isEqualTo(VendorProcesses.INVALID_TIN);
        // A type alone does not erase the number it would leave unchecked.
        assertThat(refused(VendorProcesses.TAX_SAVE, clerk, Map.of("vendorCode", "V800", "tinType", "ITIN"), 422))
            .isEqualTo(VendorProcesses.INVALID_TIN);
        ok(VendorProcesses.TAX_SAVE, clerk, Map.of("vendorCode", "V800", "backupWithholding", true));
        assertThat(find(ApEntities.TAX_INFO_DATASET, "vendorCode", "V800").getFirst())
            .containsEntry("tin", "***-**-1234").containsEntry("backupWithholding", true);
        assertThat(refused(VendorProcesses.TAX_SAVE, clerk, Map.of("vendorCode", "V999", "tinType", "SSN",
            "tin", "123-45-1234"), 422)).isEqualTo(VendorProcesses.UNKNOWN_VENDOR);
        assertOnlyInserted("fi_vendor_tax_info_version");
    }

    // ---- FIN-AP-003, FIN-CT-010, FIN-SC-001: bank changes ----------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void aBankChangeTakesEffectOnlyOnceAnotherPersonApproves() {
        Map<String, Object> first = ok(VendorBankProcesses.CHANGE, clerk, bankChange("V200", "111000025",
            "000123456789", "New vendor set-up"));
        assertThat(first).containsEntry("status", "PENDING");
        // The clerk cannot approve the change asked for (FIN-CT-001); a controller does.
        run("APPROVAL_DECIDE", inRoles("ap-clerk", FinanceRoles.PAYABLES_CLERK, FinanceRoles.CONTROLLER),
            Map.of("requestId", first.get("approvalRequestId"), "decision", "APPROVE")).expectStatus().is4xxClientError();
        approve(first);
        assertThat(account(first)).containsEntry("status", "ACTIVE").containsEntry("decidedBy", "controller")
            .containsEntry("accountNumber", "****6789").containsEntry("requestedBy", "ap-clerk");

        // A second change waits: the account in use stays until it is approved.
        Map<String, Object> second = ok(VendorBankProcesses.CHANGE, clerk, bankChange("V200", "021000021",
            "987654321", "Vendor's letter of 2026-01-15"));
        assertThat(account(second)).containsEntry("status", "PENDING");
        assertThat(account(first)).containsEntry("status", "ACTIVE");
        assertThat(refused(VendorBankProcesses.CHANGE, clerk, bankChange("V200", "021000021", "55554444", "Again"),
            422)).isEqualTo(VendorBankProcesses.PENDING_CHANGE);
        approve(second);
        assertThat(account(second)).containsEntry("status", "ACTIVE");
        assertThat(account(first)).containsEntry("status", "REPLACED");

        // The audit trail of the change: masked old and new numbers, who asked and who approved (FIN-CT-010).
        Map<String, Object> trail = get("/api/audit/records?entityType=" + ApEntities.VENDOR_BANK + "&limit=50",
            as("auditor", "audit.read")).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        List<Map<String, Object>> records = (List<Map<String, Object>>) trail.get("items");
        assertThat(records.toString()).doesNotContain("000123456789").doesNotContain("987654321");
        assertThat(records).filteredOn(r -> second.get("bankAccountId").equals(r.get("entityId")))
            .extracting(r -> r.get("action")).containsExactly("UPDATE", "INSERT");
        // The account it replaced: taken into use, then replaced.
        assertThat(records).filteredOn(r -> first.get("bankAccountId").equals(r.get("entityId")))
            .extracting(r -> r.get("action")).containsExactly("UPDATE", "UPDATE", "INSERT");
        Map<String, Object> inserted = records.stream()
            .filter(r -> second.get("bankAccountId").equals(r.get("entityId")) && "INSERT".equals(r.get("action")))
            .findFirst().orElseThrow();
        assertThat(inserted).containsEntry("actorId", "ap-clerk");
        assertThat(((Map<String, Object>) inserted.get("changes")).get("accountNumber"))
            .isEqualTo(pair(null, "****4321"));
        Map<String, Object> decided = records.stream()
            .filter(r -> second.get("bankAccountId").equals(r.get("entityId"))
                && ((Map<String, Object>) r.get("changes")).containsKey("decidedBy"))
            .findFirst().orElseThrow();
        assertThat(((Map<String, Object>) decided.get("changes")).get("decidedBy")).isEqualTo(pair(null, "controller"));
        assertThat(((Map<String, Object>) decided.get("changes")).get("status")).isEqualTo(pair("PENDING", "ACTIVE"));
        assertOnlyInserted("fi_vendor_bank_account_version");
    }

    @Test
    void aRejectedChangeIsNeverPaidToAndBadNumbersAreRefused() {
        Map<String, Object> asked = ok(VendorBankProcesses.CHANGE, clerk, bankChange("V300", "091000019",
            "44443333", "Phone call from someone claiming to be the vendor"));
        ok("APPROVAL_DECIDE", controller, Map.of("requestId", asked.get("approvalRequestId"), "decision", "REJECT",
            "reason", "Not confirmed with the vendor"));
        delivered();
        assertThat(account(asked)).containsEntry("status", "REJECTED").containsEntry("decidedBy", "controller");

        assertThat(refused(VendorBankProcesses.CHANGE, clerk, bankChange("V300", "111000026", "44443333", "Typo"),
            422)).isEqualTo(VendorBankProcesses.INVALID_ROUTING);
        assertThat(refused(VendorBankProcesses.CHANGE, clerk, bankChange("V300", "111000025", "****3333", "Masked"),
            422)).isEqualTo(VendorBankProcesses.INVALID_ACCOUNT);
        assertThat(refused(VendorBankProcesses.CHANGE, clerk, bankChange("V999", "111000025", "44443333", "Who"),
            422)).isEqualTo(VendorProcesses.UNKNOWN_VENDOR);
        // A second factor is required (FIN-SC-001); the treasurer, who releases payments, keeps no vendor details.
        run(VendorBankProcesses.CHANGE, TestTokens.withoutMfa(tokens, "ap-clerk",
            FinanceRoles.all().stream().filter(r -> r.code().equals(FinanceRoles.PAYABLES_CLERK)).findFirst()
                .orElseThrow().permissions().toArray(String[]::new)),
            bankChange("V300", "111000025", "44443333", "No second factor")).expectStatus().isForbidden();
        run(VendorBankProcesses.CHANGE, treasurer, bankChange("V300", "111000025", "44443333", "Treasurer"))
            .expectStatus().isForbidden();
        // The decision is the platform's approval events' only.
        run(VendorBankProcesses.APPROVAL_RESULT, controller, Map.of("subject", VendorBankProcesses.SUBJECT,
            "status", "APPROVED")).expectStatus().isForbidden();
        // Even who holds everything cannot say a waiting change was approved: the platform's request says otherwise.
        Map<String, Object> waiting = ok(VendorBankProcesses.CHANGE, clerk, bankChange("V300", "111000025",
            "77776666", "Vendor's letter"));
        Map<String, Object> forged = ok(VendorBankProcesses.APPROVAL_RESULT, as("admin", "*"), Map.of(
            "subject", VendorBankProcesses.SUBJECT, "entityId", "x", "status", "APPROVED",
            "requestId", waiting.get("approvalRequestId"), "contentHash", account(waiting).get("contentHash")));
        assertThat(forged).containsEntry("status", "PENDING");
        assertThat(account(waiting)).containsEntry("status", "PENDING").containsEntry("decidedBy", null);
        approve(waiting);
        assertThat(account(waiting)).containsEntry("status", "ACTIVE").containsEntry("decidedBy", "controller");
        // The account number never reaches the operation records.
        assertThat(query("SELECT input_summary::text AS s FROM op_process WHERE process_name = ?",
            VendorBankProcesses.CHANGE)).isNotEmpty().allSatisfy(row -> assertThat(String.valueOf(row.get("s")))
                .doesNotContain("44443333").doesNotContain("000123456789"));
    }

    // ---- settings, thresholds, the company's bank account ----------------------------------------------------------

    @Test
    void theSettingsTheThresholdsAndTheCompanysBankAccount() {
        assertThat(find(ApEntities.THRESHOLD_DATASET, "taxYear", 2026)).extracting(t -> t.get("form1099") + " "
            + new BigDecimal(String.valueOf(t.get("threshold"))).setScale(2).toPlainString())
            .containsExactlyInAnyOrder("NEC 2000.00", "MISC 2000.00");
        assertThat(find(ApEntities.THRESHOLD_DATASET, "taxYear", 2025)).hasSize(2);
        // A changed threshold is data, not code (FIN-AP-021 acceptance 2).
        assertThat(ok(ApSettingsProcesses.THRESHOLD_SET, clerk, Map.of("taxYear", 2027, "form1099", "NEC",
            "threshold", "600.00"))).containsEntry("forms", List.of("NEC"));
        assertThat(refused(ApSettingsProcesses.THRESHOLD_SET, clerk, Map.of("taxYear", 2027, "form1099", "K",
            "threshold", "600.00"), 422)).isEqualTo(ApSettingsProcesses.INVALID_FORM);

        // The treasurer keeps the company's bank account, with a second factor; its number is masked.
        Map<String, Object> bank = new HashMap<>(Map.of("bankCode", "OPERATING", "bankName",
            "Lakeside National Bank", "glAccount", "1010", "routingNumber", "111000025",
            "companyAccountNumber", "000123456789", "achCompanyId", "1234567890", "achCompanyName", "NORTHWIND",
            "nextCheckNo", 10001));
        assertThat(ok(BankAccountProcesses.SAVE, treasurer, bank)).containsEntry("created", true);
        assertThat(find(BankEntities.BANK_ACCOUNT_DATASET, "bankCode", "OPERATING").getFirst())
            .containsEntry("accountNumber", "****6789").containsEntry("glAccount", "1010")
            .containsEntry("currency", "USD");
        bank.put("bankCode", "WRONG");
        bank.put("glAccount", "1200");
        assertThat(refused(BankAccountProcesses.SAVE, treasurer, bank, 422)).isEqualTo(BankAccountProcesses.WRONG_ACCOUNT);
        run(BankAccountProcesses.SAVE, clerk, bank).expectStatus().isForbidden();

        // The controller adds the accounts the sample chart lacks (F4 plan D5) and sets the payables accounts.
        account("5900", "Purchase Discounts", "Expense", "C");
        account("2210", "Use Tax Payable", "Liability", "C");
        account("1310", "Vendor Prepayments", "Asset", "D");
        Map<String, Object> settings = new HashMap<>(Map.of("payableAccount", "2000", "discountAccount", "5900",
            "useTaxAccount", "2210", "prepaymentAccount", "1310", "defaultBank", "operating"));
        assertThat(ok(ApSettingsProcesses.SET, controller, settings)).containsEntry("changed", true);
        assertThat(find(ApEntities.SETTINGS_DATASET, "settingsKey", "AP").getFirst())
            .containsEntry("defaultBank", "OPERATING").containsEntry("useTaxAccount", "2210");
        settings.put("payableAccount", "2100");
        assertThat(refused(ApSettingsProcesses.SET, controller, settings, 422))
            .isEqualTo(ApSettingsProcesses.WRONG_ACCOUNT);
        settings.put("payableAccount", "2000");
        settings.put("defaultBank", "NONE");
        assertThat(refused(ApSettingsProcesses.SET, controller, settings, 422))
            .isEqualTo(ApSettingsProcesses.UNKNOWN_BANK);
        run(ApSettingsProcesses.SET, clerk, settings).expectStatus().isForbidden();
    }

    // ---- FIN-CT-001 acceptance 2 -----------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void aUserWhoIsBothPayablesClerkAndTreasurerIsReported() {
        String admin = as("admin", "*");
        String userName = "both-" + unique().toLowerCase();
        String userId = (String) ok("SEC_USER_CREATE", admin, Map.of("userName", userName, "displayName", userName,
            "password", "password-123")).get("userId");
        assignRole(admin, userId, FinanceRoles.PAYABLES_CLERK);
        assignRole(admin, userId, FinanceRoles.TREASURER);
        // The rules were held back until now: published, they report the user who holds both.
        for (String changeId : heldSod.values()) {
            ok("CONTROL_CHANGE_PUBLISH", as("controller-2", "control.publish"), Map.of("changeId", changeId));
        }
        List<Map<String, Object>> report = get("/api/sod/conflicts", controller).expectStatus().isOk()
            .expectBody(LIST).returnResult().getResponseBody();
        assertThat(report).filteredOn(row -> userName.equals(row.get("userName")))
            .extracting(row -> row.get("ruleCode"))
            .containsExactlyInAnyOrder(SetupProcesses.SOD_VENDOR_BANK, SetupProcesses.SOD_PAYABLES);
        // From now on such an assignment is refused (prevention, platform 18 section 4.2).
        String other = (String) ok("SEC_USER_CREATE", admin, Map.of("userName", userName + "-2", "displayName",
            userName, "password", "password-123")).get("userId");
        assignRole(admin, other, FinanceRoles.TREASURER);
        Map<String, Object> problem = post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", admin,
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", Map.of("userId", other,
                "roleId", roleId(FinanceRoles.PAYABLES_CLERK)))))).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(problem.toString()).contains("SOD_CONFLICT");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /**
     * Delivers the decisions; every consumer of an approval takes it, the subscribers of other subjects by doing nothing
     * (ROADMAP F11c: the invoices' failed on every approval not theirs and were tried again and again).
     */
    private void delivered() {
        deliverer.deliverPending().block();
        assertThat(query("SELECT consumer FROM sys_outbox_attempt")).isEmpty();
    }

    private static Map<String, Object> vendor(String code, String name, Map<String, Object> more) {
        Map<String, Object> input = new LinkedHashMap<>(Map.of("vendorCode", code, "legalName", name,
            "currency", "USD", "entityType", "SINGLE_MEMBER_LLC"));
        input.putAll(more);
        return input;
    }

    private static Map<String, Object> bankChange(String vendor, String routing, String account, String reason) {
        return Map.of("vendorCode", vendor, "bankName", "Some Bank", "routingNumber", routing,
            "bankAccountNumber", account, "reason", reason);
    }

    private void approve(Map<String, Object> change) {
        ok("APPROVAL_DECIDE", controller, Map.of("requestId", change.get("approvalRequestId"),
            "decision", "APPROVE"));
        delivered();
    }

    private Map<String, Object> account(Map<String, Object> change) {
        return read(ApEntities.VENDOR_BANK_DATASET, change.get("bankAccountId"));
    }

    private void account(String code, String name, String type, String balance) {
        ok("FIN_ACCOUNT_CREATE", controller(), Map.of("accountCode", code, "accountName", name,
            "financialType", AccountTypes.fromChart(type), "normalBalance", AccountTypes.normalBalanceFromChart(balance),
            "statementLine", name));
    }

    private static Map<String, Object> pair(Object before, Object after) {
        Map<String, Object> pair = new HashMap<>();
        pair.put("before", before);
        pair.put("after", after);
        return pair;
    }

    private String roleId(String roleCode) {
        return String.valueOf(find(SecurityEntities.ROLE_DATASET, "roleCode", roleCode).getFirst().get("roleId"));
    }

    private void assignRole(String admin, String userId, String roleCode) {
        post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", admin, Map.of("changes",
            List.of(Map.of("action", "INSERT", "attributes", Map.of("userId", userId, "roleId",
                roleId(roleCode)))))).expectStatus().isOk();
    }
}

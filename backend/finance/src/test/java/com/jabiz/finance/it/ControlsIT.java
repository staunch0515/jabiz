package com.jabiz.finance.it;

import com.jabiz.context.DataPeriod;
import com.jabiz.finance.ap.BillEntities;
import com.jabiz.finance.ap.BillProcesses;
import com.jabiz.finance.ar.InvoiceEntities;
import com.jabiz.finance.ar.InvoiceProcesses;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.finance.setup.SetupProcesses;
import com.jabiz.entity.Violation;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.retention.DeletionGuard;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The books' controls of ROADMAP F10a on January's books:
 * <ul>
 *   <li>FIN-CT-001: who keeps users and roles never prepares or posts documents ({@code FIN-SOD-ADMIN-POST}):
 *       granting both is refused;</li>
 *   <li>FIN-SC-002: an external auditor limited to fiscal 2026 sees January's entries, postings, invoices and bills,
 *       never the opening entry of 2025-12-31, nor its posting; one limited to 2025 sees the opposite;</li>
 *   <li>FIN-CT-020: in 2030 a posted 2026 invoice or bill is not deleted, the postings are kept to the end of 2033 (the
 *       retention report, the platform's deletion guard), and a legal hold on V200's bills keeps even a draft of V200 until it is released.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ControlsIT extends JanuaryBooks {

    /** Fiscal 2026 on the ledger's time line: from the start of 2026-01-01 to that of 2027-01-01 in Chicago. */
    private static final DataPeriod FISCAL_2025 = new DataPeriod(Instant.parse("2025-01-01T06:00:00Z"),
        Instant.parse("2026-01-01T06:00:00Z"));

    @Autowired
    private DeletionGuard guard;
    @Autowired
    private EntityDefinitionRegistry entities;

    private static final DataPeriod FISCAL_2026 = new DataPeriod(Instant.parse("2026-01-01T06:00:00Z"),
        Instant.parse("2027-01-01T06:00:00Z"));

    @Test
    void theBooksControls() throws IOException {
        januaryPostings();

        // FIN-CT-001: FIN_SETUP's rule keeps the administrator's role and the accountant's apart (the conflict report
        // of a user holding both before the rule: SodAdminIT).
        String admin = as("admin", "*");
        String other = user(admin, "ct-admin");
        assign(admin, other, FinanceRoles.SYSTEM_ADMINISTRATOR).expectStatus().isOk();
        Map<String, Object> refused = assign(admin, other, FinanceRoles.ACCOUNTANT).expectStatus().isEqualTo(422)
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(refused.toString()).contains("SOD_CONFLICT", SetupProcesses.SOD_ADMIN_POST);

        // FIN-SC-002: the auditor of fiscal 2026 reads January, never 2025's opening entry.
        String[] auditing = FinanceRoles.all().stream()
            .filter(r -> FinanceRoles.EXTERNAL_AUDITOR.equals(r.code())).flatMap(r -> r.permissions().stream())
            .toArray(String[]::new);
        String auditor = TestTokens.withinPeriod(tokens, FISCAL_2026, "auditor", auditing);
        Map<String, Object> opening = find(JournalEntities.JOURNAL_DATASET, "source", JournalEntities.OPENING)
            .getFirst();
        Map<String, Object> je1 = find(JournalEntities.JOURNAL_DATASET, "journalNo", "JE-0001").getFirst();
        get("/api/datasets/" + JournalEntities.JOURNAL_DATASET + "/entities/" + opening.get("journalId"), auditor)
            .expectStatus().isNotFound();
        get("/api/datasets/" + JournalEntities.JOURNAL_DATASET + "/entities/" + je1.get("journalId"), auditor)
            .expectStatus().isOk();
        List<Map<String, Object>> postings = query(JournalEntities.POSTING_DATASET, auditor);
        // The opening entry is in fiscal 2026's opening period (2026-00) but dated 2025-12-31: not the auditor's.
        assertThat(postings).isNotEmpty()
            .allSatisfy(p -> assertThat((String) p.get("postingDate")).isGreaterThanOrEqualTo("2026-01-01"));
        assertThat(postings).hasSize((int) query(JournalEntities.POSTING_DATASET, as("reader", "*")).stream()
            .filter(p -> ((String) p.get("postingDate")).compareTo("2026-01-01") >= 0).count());
        // Invoices and bills follow their ledger transaction: the auditor of 2026 sees every one posted in January,
        // the auditor of 2025 none of them but 2025's opening entry. Opening items carried in without a ledger
        // transaction of their own are outside every period (ROADMAP F10a, known limitations).
        String auditor2025 = TestTokens.withinPeriod(tokens, FISCAL_2025, "auditor-2025", auditing);
        for (String dataset : List.of(InvoiceEntities.INVOICE_DATASET, BillEntities.BILL_DATASET)) {
            List<Object> booked = query(dataset, as("reader", "*")).stream()
                .filter(d -> d.get("transactionId") != null).map(d -> d.get("transactionId")).toList();
            assertThat(booked).as(dataset).isNotEmpty();
            assertThat(query(dataset, auditor)).as(dataset).extracting(d -> d.get("transactionId"))
                .containsExactlyInAnyOrderElementsOf(booked);
            assertThat(query(dataset, auditor2025)).as(dataset).isEmpty();
        }
        get("/api/datasets/" + JournalEntities.JOURNAL_DATASET + "/entities/" + opening.get("journalId"), auditor2025)
            .expectStatus().isOk();
        get("/api/datasets/" + JournalEntities.JOURNAL_DATASET + "/entities/" + je1.get("journalId"), auditor2025)
            .expectStatus().isNotFound();

        // FIN-CT-020: in 2030 a posted 2026 document is not deleted; the postings are kept to the end of 2033.
        clock.set(Instant.parse("2030-03-01T15:00:00Z"));
        people();
        Map<String, Object> invoice = find(InvoiceEntities.INVOICE_DATASET, "status", "POSTED").getFirst();
        assertThat(refused(InvoiceProcesses.DELETE, arClerk, Map.of("invoiceId", invoice.get("invoiceId")), 422))
            .isEqualTo(InvoiceProcesses.NOT_DRAFT);
        Map<String, Object> retention = get("/api/retention", as("reviewer", "retention.read")).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policies = (List<Map<String, Object>>) retention.get("policies");
        assertThat(policies).extracting(p -> p.get("entity")).contains(JournalEntities.POSTING, "FinPayment",
            "FinBankReconciliation", "FinCloseArtifact", "FinYearClose", "FinPeriodBalance");
        assertThat(policies).filteredOn(p -> JournalEntities.POSTING.equals(p.get("entity"))).singleElement()
            .satisfies(p -> assertThat(p).containsEntry("expiredThrough", "2022-12-31"));
        // The platform refuses the deletion of a 2026 posting until the end of 2033 (no finance process deletes one).
        Violation kept = guard.refusal(null, entities.find(JournalEntities.POSTING).orElseThrow(), "p-1",
            Map.of("postingDate", LocalDate.of(2026, 1, 20))).block();
        assertThat(kept).isNotNull();
        assertThat(kept.ruleCode()).isEqualTo("RETENTION_ACTIVE");
        assertThat(kept.params()).containsEntry("until", "2033-12-31");

        // A legal hold on V200's bills keeps a draft of V200 until it is released.
        String draft = (String) ok(BillProcesses.SAVE, apClerk, Map.of("vendorCode", "V200", "vendorInvoiceNo",
            "DC-HOLD", "invoiceDate", "2030-02-27", "lines", List.of(Map.of("description", "Held",
                "amount", "10.00", "account", "6400")))).get("billId");
        String hold = (String) ok("LEGAL_HOLD_PLACE", as("counsel", "legal.hold.write"), Map.of("name",
            "V200 dispute", "reason", "Claim by V200", "entityType", BillEntities.BILL, "field", "vendorCode",
            "value", "V200")).get("holdId");
        assertThat(refused(BillProcesses.DELETE, apClerk, Map.of("billId", draft), 422)).isEqualTo("LEGAL_HOLD");
        ok("LEGAL_HOLD_RELEASE", as("counsel", "legal.hold.write"), Map.of("holdId", hold, "reason", "Settled"));
        ok(BillProcesses.DELETE, apClerk, Map.of("billId", draft));
    }

    private String user(String admin, String name) {
        return (String) ok("SEC_USER_CREATE", admin, Map.of("userName", name, "displayName", name,
            "password", "password-123")).get("userId");
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec assign(String admin,
        String userId, String role) {
        return post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", admin, Map.of("changes",
            List.of(Map.of("action", "INSERT", "attributes", Map.of("userId", userId, "roleId",
                find(SecurityEntities.ROLE_DATASET, "roleCode", role).getFirst().get("roleId"))))));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> query(String dataset, String authorization) {
        Map<String, Object> page = post("/api/datasets/" + dataset + "/query", authorization, Map.of("limit", 500))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) page.get("items")).stream()
            .map(item -> (Map<String, Object>) item.get("attributes")).toList();
    }
}

package com.jabiz.finance.it;

import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalValidator;
import com.jabiz.finance.payroll.PayrollEntities;
import com.jabiz.finance.payroll.PayrollProcesses;
import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.runtime.event.OutboxDeliverer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payroll import (FIN-DI-004): the provider's January file, read through the controller's mapping of provider codes,
 * becomes PAYROLL-2601 as FIN-EXP-02 has it, four lines; it waits for approval as any entry above 10,000.00 and
 * posts when approved. A code without a mapping or a mapping to a non-bank control account refuses the file; a run
 * is imported once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PayrollImportIT extends FinanceItSupport {

    @Autowired
    OutboxDeliverer deliverer;

    private static final String DESCRIPTION = "Payroll summary from external provider: gross 60,000.00; employer "
        + "taxes 4,590.00; net pay 45,000.00; withholdings and employer taxes 19,590.00";

    private void map(String code, String account, String side) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("providerCode", code);
        mapping.put("accountCode", account);
        mapping.put("side", side);
        mapping.put("active", true);
        post("/api/datasets/" + PayrollEntities.MAPPING_DATASET + "/commit",
            inRoles("controller", FinanceRoles.CONTROLLER), Map.of("changes", List.of(Map.of("action", "INSERT",
                "attributes", mapping)))).expectStatus().isOk();
    }

    private static String providerFile() throws IOException {
        try (var in = PayrollImportIT.class.getResourceAsStream("/payroll/provider-2026-01.csv")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, Object> params(String run) {
        return Map.of("run", run, "payDate", "2026-01-30", "description", DESCRIPTION);
    }

    @Test
    void theJanuaryRunBecomesPayroll2601() throws IOException {
        openBooks();
        map("GROSS_WAGES", "6100", "DEBIT");
        map("EMPLOYER_TAX", "6150", "DEBIT");
        map("NET_PAY", "1010", "CREDIT");
        map("EMPLOYEE_WITHHOLDING", "2150", "CREDIT");
        map("EMPLOYER_TAX_LIABILITY", "2150", "CREDIT");
        String accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);

        // An unmapped code refuses the whole file; nothing is made.
        Map<String, Object> refused = importCsv("finance.payroll", accountant,
            providerFile() + "BONUS,,100.00\n", "commit", null, params("PAYROLL-2600"), 422);
        assertThat(issues(refused)).contains("1:" + PayrollProcesses.UNMAPPED);
        assertThat(find(JournalEntities.JOURNAL_DATASET, "externalRef", "PAYROLL:PAYROLL-2600")).isEmpty();

        Map<String, Object> committed = importCsv("finance.payroll", accountant, providerFile(), "commit", null,
            params("PAYROLL-2601"), 200);
        assertThat(committed).containsEntry("committed", true).containsEntry("units", 1);
        Map<String, Object> journal = find(JournalEntities.JOURNAL_DATASET, "journalNo", "PAYROLL-2601").getFirst();
        // Above 10,000.00: it waits for approval like any entry; the bank line stands on the mapping's exception.
        assertThat(journal).containsEntry("source", "PAYROLL").containsEntry("status", "SUBMITTED")
            .containsEntry("externalRef", "PAYROLL:PAYROLL-2601").containsEntry("postingDate", "2026-01-30")
            .containsEntry("exceptionBy", "payroll mapping");
        Map<String, BigDecimal> lines = new java.util.TreeMap<>();
        for (Map<String, Object> line : find(JournalEntities.LINE_DATASET, "journalId", journal.get("journalId"))) {
            lines.put((String) line.get("accountCode"), line.get("debit") != null ? amount(line.get("debit"))
                : amount(line.get("credit")).negate());
        }
        Map<String, BigDecimal> expected = expectedDocuments("PAYROLL-2601").get("PAYROLL-2601");
        assertThat(lines).hasSize(4).isEqualTo(expected);

        // Approved by the controller, it posts with those lines.
        ok("APPROVAL_DECIDE", inRoles("controller", FinanceRoles.CONTROLLER),
            Map.of("requestId", journal.get("approvalRequestId"), "decision", "APPROVE"));
        deliverer.deliverPending().block();
        assertThat(find(JournalEntities.JOURNAL_DATASET, "journalNo", "PAYROLL-2601").getFirst())
            .containsEntry("status", "POSTED");
        assertThat(ledgerLines("PAYROLL-2601")).isEqualTo(expected);

        // The same run again: refused.
        assertThat(issues(importCsv("finance.payroll", accountant, providerFile() + "\n", "commit", null,
            params("PAYROLL-2601"), 422))).contains("1:" + PayrollProcesses.IMPORTED_ALREADY);

        // A mapping to a control account other than a bank refuses the run.
        map("RECEIVABLE_OFFSET", "1200", "CREDIT");
        map("RECEIVABLE_DEBIT", "6100", "DEBIT");
        assertThat(issues(importCsv("finance.payroll", accountant, """
            code,amount
            RECEIVABLE_DEBIT,10.00
            RECEIVABLE_OFFSET,10.00
            """, "commit", null, params("PAYROLL-2699"), 422))).contains("1:" + PayrollProcesses.CONTROL_ACCOUNT);

        // The run is the entry's number, so it keeps its own prefix: never a number the journal sequence gives out.
        Map<String, Object> direct = new java.util.LinkedHashMap<>(params("JE-0123"));
        direct.put("lines", List.of(Map.of("code", "GROSS_WAGES", "amount", 10), Map.of("code", "NET_PAY",
            "amount", 10)));
        post("/api/processes/" + PayrollProcesses.IMPORT_RUN + "/latest", accountant, direct)
            .expectStatus().isBadRequest();

        // The bank's exception covers paying out on the mapping's side, not a negative amount turning it round.
        assertThat(issues(importCsv("finance.payroll", accountant, """
            code,amount
            GROSS_WAGES,-10.00
            NET_PAY,-10.00
            """, "commit", null, params("PAYROLL-2698"), 422))).contains("1:" + PayrollProcesses.NEGATIVE_BANK);

        // Kept as a draft, a run must still be a postable entry: a numbered draft cannot be deleted.
        Map<String, Object> draft = new java.util.HashMap<>(params("PAYROLL-2697"));
        draft.put("submit", false);
        assertThat(issues(importCsv("finance.payroll", accountant, """
            code,amount
            GROSS_WAGES,10.00
            EMPLOYER_TAX,10.00
            """, "commit", null, draft, 422))).contains("1:" + JournalValidator.UNBALANCED);
        assertThat(find(JournalEntities.JOURNAL_DATASET, "journalNo", "PAYROLL-2697")).isEmpty();
        assertOnlyInserted("fi_payroll_mapping_version", "fi_journal_version", "fi_journal_line_version");
    }
}

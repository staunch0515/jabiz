package com.jabiz.finance.it;

import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalImportProcesses;
import com.jabiz.finance.gl.JournalValidator;
import com.jabiz.finance.setup.FinanceRoles;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Journal import (FIN-GL-019): every entry of the file is checked before anything posts; one unbalanced entry and
 * one invalid account refuse the whole file with both rows and reasons; a good file makes one entry per document,
 * submitted under the same approval rules as an entry typed by hand; a document is imported once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class JournalImportIT extends FinanceItSupport {

    private static final String IMPORT = "finance.journals";

    @Test
    void entriesAreCheckedAllBeforeAnyPostsAndFollowTheApprovalRules() {
        openBooks();
        String accountant = inRoles("accountant", FinanceRoles.ACCOUNTANT);

        // FIN-GL-019 acceptance 1: nothing is posted, both rows are reported with reasons.
        Map<String, Object> refused = importCsv(IMPORT, accountant, """
            document,posting_date,description,account,debit,credit
            LEG-1,2026-01-20,Legacy accrual,6400,500.00,
            LEG-1,2026-01-20,Legacy accrual,2100,,499.00
            LEG-2,2026-01-21,Legacy fees,9999,100.00,
            LEG-2,2026-01-21,Legacy fees,2100,,100.00
            """, "commit", null, null, 422);
        assertThat(issues(refused)).contains("1:" + JournalValidator.UNBALANCED, "3:" + JournalValidator.ACCOUNT_UNKNOWN);
        assertThat(find(JournalEntities.JOURNAL_DATASET, "externalRef", "IMPORT:LEG-1")).isEmpty();
        assertThat(find(JournalEntities.JOURNAL_DATASET, "externalRef", "IMPORT:LEG-2")).isEmpty();

        // A good file: one entry per document; the small one posts at once, the large one waits for approval.
        Map<String, Object> committed = importCsv(IMPORT, accountant, """
            document,posting_date,description,account,debit,credit,memo,department
            LEG-3,2026-01-20,Legacy accrual,6400,500.00,,audit fee,ADMIN
            LEG-3,2026-01-20,,2100,,500.00,,
            LEG-4,2026-01-21,Legacy bonus accrual,6100,12000.00,,,
            LEG-4,2026-01-21,,2100,,12000.00,,
            """, "commit", null, null, 200);
        assertThat(committed).containsEntry("committed", true).containsEntry("units", 2);
        Map<String, Object> small = find(JournalEntities.JOURNAL_DATASET, "externalRef", "IMPORT:LEG-3").getFirst();
        assertThat(small).containsEntry("source", "IMPORT").containsEntry("status", "POSTED")
            .containsEntry("description", "Legacy accrual");
        assertThat((String) small.get("glNo")).startsWith("GJ-IMP-2026-");
        assertThat((String) small.get("journalNo")).startsWith("JE-");
        Map<String, Object> large = find(JournalEntities.JOURNAL_DATASET, "externalRef", "IMPORT:LEG-4").getFirst();
        assertThat(large).containsEntry("status", "SUBMITTED");
        assertThat(large.get("approvalRequestId")).isNotNull();

        // A document is imported once, even in another file.
        assertThat(issues(importCsv(IMPORT, accountant, """
            document,posting_date,description,account,debit,credit
            LEG-3,2026-01-22,Again,6400,1.00,
            LEG-3,2026-01-22,Again,2100,,1.00
            """, "commit", null, null, 422))).contains("1:" + JournalImportProcesses.IMPORTED_ALREADY);

        // Asked not to submit, the entries stay drafts for the preparer.
        importCsv(IMPORT, accountant, """
            document,posting_date,description,account,debit,credit
            LEG-5,2026-01-23,Legacy rent,6200,800.00,
            LEG-5,2026-01-23,Legacy rent,2100,,800.00
            """, "commit", null, Map.of("submit", false), 200);
        assertThat(find(JournalEntities.JOURNAL_DATASET, "externalRef", "IMPORT:LEG-5").getFirst())
            .containsEntry("status", "DRAFT").containsEntry("preparer", "accountant");

        // A control account is refused as for a typed entry.
        assertThat(issues(importCsv(IMPORT, accountant, """
            document,posting_date,description,account,debit,credit
            LEG-6,2026-01-24,Legacy cash,1010,10.00,
            LEG-6,2026-01-24,Legacy cash,2100,,10.00
            """, "commit", null, null, 422))).contains("1:" + JournalValidator.CONTROL_ACCOUNT);
    }
}

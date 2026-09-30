package com.jabiz.finance.it;

import com.jabiz.finance.gl.ChartTemplate;
import com.jabiz.finance.gl.ChartTemplateProcesses;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.runtime.ledger.LedgerEntities;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The standard chart (FIN-GL-002 acceptance 1): the controller sees the proposed chart before anything is saved, then
 * copies it, leaving out what the company does not need, into books without accounts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ChartTemplateIT extends FinanceItSupport {

    @Test
    @SuppressWarnings("unchecked")
    void theTemplateIsReviewedThenCopiedIntoNewBooks() {
        Map<String, Object> preview = ok(ChartTemplateProcesses.PREVIEW, controller(), Map.of());
        List<Map<String, Object>> proposed = (List<Map<String, Object>>) preview.get("accounts");
        assertThat(proposed).hasSize(ChartTemplate.lines().size());
        assertThat(proposed.getFirst()).containsEntry("accountCode", "1000").containsEntry("summary", true);
        // Previewing saved nothing.
        assertThat(find(LedgerEntities.ACCOUNT_DATASET, "accountCode", "1000")).isEmpty();

        Map<String, Object> applied = ok(ChartTemplateProcesses.APPLY, controller(),
            Map.of("excludeCodes", List.of("1300", "1500")));
        List<Map<String, Object>> copied = (List<Map<String, Object>>) applied.get("accounts");
        // Inventory and fixed assets with the four accounts under them are left out.
        assertThat(copied).hasSize(ChartTemplate.lines().size() - 6);
        assertThat(find(LedgerEntities.ACCOUNT_DATASET, "accountCode", "1510")).isEmpty();
        Map<String, Object> cash = find(LedgerEntities.ACCOUNT_DATASET, "accountCode", "1110").getFirst();
        assertThat(cash.get("parentId"))
            .isEqualTo(find(LedgerEntities.ACCOUNT_DATASET, "accountCode", "1100").getFirst().get("accountId"));
        assertThat(find(GlEntities.ACCOUNT_DATASET, "accountCode", "1110").getFirst())
            .containsEntry("controlClass", "BANK").containsEntry("cashFlowClass", "CASH");
        assertThat(find(GlEntities.ACCOUNT_DATASET, "accountCode", "2900").getFirst())
            .containsEntry("clearing", true);

        assertThat(refused(ChartTemplateProcesses.APPLY, controller(), Map.of(), 422))
            .isEqualTo(ChartTemplateProcesses.CHART_NOT_EMPTY);
        run(ChartTemplateProcesses.PREVIEW, as("clerk", "fin.account.read"), Map.of()).expectStatus().isForbidden();
    }
}

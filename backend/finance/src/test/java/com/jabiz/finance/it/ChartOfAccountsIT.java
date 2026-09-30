package com.jabiz.finance.it;

import com.jabiz.finance.gl.AccountProcesses;
import com.jabiz.finance.gl.AccountTypes;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.context.DataPeriod;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chart of accounts (FIN-GL-001, 003, 004, 005): the sample company's 36 accounts load with their attributes, a
 * code is used once, summary and inactive accounts take no postings, an account with postings is deactivated rather
 * than deleted, and every change keeps its history. Postings are made directly on the ledger here; journal entries come
 * with phase F1b.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ChartOfAccountsIT extends FinanceItSupport {

    /** The control accounts of the sample company: its chart file does not mark them (FIN-GL-005). */
    static final Map<String, String> SAMPLE_CONTROL = Map.of("1010", "BANK", "1050", "BANK", "1200", "AR",
        "2000", "AP", "1500", "FA_COST", "1510", "FA_COST", "1520", "FA_COST", "1590", "FA_ACCUM");

    private static boolean sampleLoaded;

    private void loadSample() {
        if (sampleLoaded) {
            return;
        }
        for (Map<String, String> row : sample("chart-of-accounts.csv")) {
            Map<String, Object> input = new HashMap<>();
            input.put("accountCode", row.get("code"));
            input.put("accountName", row.get("name"));
            input.put("financialType", AccountTypes.fromChart(row.get("type")));
            input.put("normalBalance", AccountTypes.normalBalanceFromChart(row.get("normal_balance")));
            input.put("statementLine", row.get("statement_line"));
            input.put("controlClass", SAMPLE_CONTROL.get(row.get("code")));
            ok(AccountProcesses.CREATE, controller(), input);
        }
        sampleLoaded = true;
    }

    private Map<String, Object> account(String code, String type, String side, Map<String, Object> more) {
        Map<String, Object> input = new LinkedHashMap<>(Map.of("accountCode", code, "accountName", "Account " + code,
            "financialType", type, "normalBalance", side, "statementLine", "Test line"));
        input.putAll(more);
        return ok(AccountProcesses.CREATE, controller(), input);
    }

    private String post(String debit, String credit, String amount) {
        Map<String, Object> result = ok("LEDGER_POST", as("poster", "ledger.post"), Map.of(
            "bookingTime", clock.instant().toString(), "description", "test posting",
            "entries", List.of(Map.of("accountCode", debit, "direction", "DEBIT", "amount", amount),
                Map.of("accountCode", credit, "direction", "CREDIT", "amount", amount))));
        return (String) result.get("transactionId");
    }

    private String postRefused(String debit, String credit) {
        return refused("LEDGER_POST", as("poster", "ledger.post"), Map.of("description", "test posting",
            "entries", List.of(Map.of("accountCode", debit, "direction", "DEBIT", "amount", "10.00"),
                Map.of("accountCode", credit, "direction", "CREDIT", "amount", "10.00"))), 422);
    }

    /** FIN-GL-001 acceptance 1. */
    @Test
    void theSampleChartLoadsWithItsAttributes() {
        loadSample();

        List<Map<String, String>> rows = sample("chart-of-accounts.csv");
        assertThat(rows).hasSize(36);
        for (Map<String, String> row : rows) {
            String code = row.get("code");
            Map<String, Object> fin = find(GlEntities.ACCOUNT_DATASET, "accountCode", code).getFirst();
            Map<String, Object> ledger = find(LedgerEntities.ACCOUNT_DATASET, "accountCode", code).getFirst();
            assertThat(ledger).containsEntry("accountName", row.get("name")).containsEntry("enabled", true);
            assertThat(fin)
                .containsEntry("financialType", AccountTypes.fromChart(row.get("type")))
                .containsEntry("normalBalance", AccountTypes.normalBalanceFromChart(row.get("normal_balance")))
                .containsEntry("statementLine", row.get("statement_line"))
                .containsEntry("controlClass", SAMPLE_CONTROL.get(code))
                .containsEntry("ledgerAccountId", ledger.get("accountId"));
        }
        // Other income or expense follows its normal balance on the ledger; income tax is an expense.
        assertThat(find(LedgerEntities.ACCOUNT_DATASET, "accountCode", "7300").getFirst())
            .containsEntry("accountType", "REVENUE");
        assertThat(find(LedgerEntities.ACCOUNT_DATASET, "accountCode", "7100").getFirst())
            .containsEntry("accountType", "EXPENSE");
        assertThat(find(LedgerEntities.ACCOUNT_DATASET, "accountCode", "8000").getFirst())
            .containsEntry("accountType", "EXPENSE");
        assertOnlyInserted("fi_account_version");
    }

    /** FIN-GL-001 acceptance 2. */
    @Test
    void aCodeIsUsedOnce() {
        loadSample();

        assertThat(refused(AccountProcesses.CREATE, controller(), Map.of("accountCode", "1200",
            "accountName", "Receivables again", "financialType", "ASSET", "normalBalance", "DEBIT",
            "statementLine", "Accounts receivable, net"), 422)).isEqualTo(AccountProcesses.CODE_TAKEN);
        assertThat(find(GlEntities.ACCOUNT_DATASET, "accountCode", "1200")).hasSize(1);
    }

    @Test
    void valuesAndPermissionsAreChecked() {
        String code = "9" + unique();
        assertThat(refused(AccountProcesses.CREATE, controller(), Map.of("accountCode", code, "accountName", "x",
            "financialType", "GADGET", "normalBalance", "DEBIT", "statementLine", "x"), 422))
            .isEqualTo(AccountProcesses.INVALID_VALUE);
        assertThat(refused(AccountProcesses.CREATE, controller(), Map.of("accountCode", "9ab-" + code,
            "accountName", "x", "financialType", "ASSET", "normalBalance", "DEBIT", "statementLine", "x"), 400))
            .isEqualTo("FIN_ACCOUNT_CODE_FORMAT");
        run(AccountProcesses.CREATE, as("accountant", "fin.account.read", "fin.journal.prepare"), Map.of(
            "accountCode", code, "accountName", "x", "financialType", "ASSET", "normalBalance", "DEBIT",
            "statementLine", "x")).expectStatus().isForbidden();
        // Codes are read whatever their case; unknown ones are refused.
        String other = "1" + unique();
        account(other, "asset", "debit", Map.of("controlClass", " bank ", "cashFlowClass", "cash",
            "requiredDimension", "Department"));
        assertThat(find(GlEntities.ACCOUNT_DATASET, "accountCode", other).getFirst())
            .containsEntry("financialType", "ASSET").containsEntry("controlClass", "BANK")
            .containsEntry("cashFlowClass", "CASH").containsEntry("requiredDimension", "department");
        assertThat(refused(AccountProcesses.CREATE, controller(), Map.of("accountCode", code, "accountName", "x",
            "financialType", "ASSET", "normalBalance", "DEBIT", "statementLine", "x", "controlClass", "PAYROLL"),
            422)).isEqualTo(AccountProcesses.INVALID_VALUE);
        assertThat(refused(AccountProcesses.UPDATE, controller(), Map.of("accountCode", other,
            "accountName", "  "), 422)).isEqualTo(AccountProcesses.INVALID_VALUE);
        assertThat(refused(AccountProcesses.UPDATE, controller(), Map.of("accountCode", other,
            "requiredDimension", "project"), 422)).isEqualTo(AccountProcesses.INVALID_VALUE);
        // Accounts change only through their processes, never through the generic dataset API.
        assertThat(commitRefused(GlEntities.ACCOUNT_DATASET, as("admin", "*"), Map.of("action", "INSERT",
            "attributes", Map.of("accountCode", code, "ledgerAccountId", find(LedgerEntities.ACCOUNT_DATASET,
                "accountCode", other).getFirst().get("accountId"), "financialType", "ASSET", "normalBalance", "DEBIT",
                "statementLine", "x", "clearing", false))))
            .isEqualTo("PROCESS_ONLY_DATASET");
    }

    /** FIN-GL-003 acceptance 2: a summary account groups others and takes no postings. */
    @Test
    void aSummaryAccountTakesNoPostings() {
        String parent = "9" + unique();
        account(parent, "EXPENSE", "DEBIT", Map.of("summary", true));
        String child = "9" + unique();
        account(child, "EXPENSE", "DEBIT", Map.of("parentCode", parent));
        String cash = "1" + unique();
        account(cash, "ASSET", "DEBIT", Map.of());

        assertThat(find(LedgerEntities.ACCOUNT_DATASET, "accountCode", child).getFirst().get("parentId"))
            .isEqualTo(find(LedgerEntities.ACCOUNT_DATASET, "accountCode", parent).getFirst().get("accountId"));
        assertThat(postRefused(parent, cash)).isEqualTo("LEDGER_ACCOUNT_NOT_POSTABLE");
        post(child, cash, "25.00");
        // A parent must be a summary account.
        assertThat(refused(AccountProcesses.CREATE, controller(), Map.of("accountCode", "9" + unique(),
            "accountName", "x", "financialType", "EXPENSE", "normalBalance", "DEBIT", "statementLine", "x",
            "parentCode", child), 422)).isEqualTo("LEDGER_PARENT_NOT_SUMMARY");
        assertThat(refused(AccountProcesses.CREATE, controller(), Map.of("accountCode", "9" + unique(),
            "accountName", "x", "financialType", "EXPENSE", "normalBalance", "DEBIT", "statementLine", "x",
            "parentCode", "NOPE"), 422)).isEqualTo(AccountProcesses.PARENT_NOT_FOUND);
    }

    /** FIN-GL-004 acceptance 1 and 3. */
    @Test
    void anAccountWithPostingsIsDeactivatedNotDeleted() {
        String used = "6" + unique();
        account(used, "EXPENSE", "DEBIT", Map.of());
        String cash = "1" + unique();
        account(cash, "ASSET", "DEBIT", Map.of());
        post(used, cash, "100.00");

        assertThat(refused(AccountProcesses.DELETE, controller(), Map.of("accountCode", used), 422))
            .isEqualTo(AccountProcesses.HAS_POSTINGS);
        // A controller limited to a later period does not see the posting, but the ledger still keeps the account.
        String limited = TestTokens.withinPeriod(tokens, new DataPeriod(clock.instant().plus(Duration.ofDays(200)),
            null), "limited-controller", "fin.account.maintain");
        assertThat(refused(AccountProcesses.DELETE, limited, Map.of("accountCode", used), 422))
            .isEqualTo("STILL_REFERENCED");
        assertThat(find(LedgerEntities.ACCOUNT_DATASET, "accountCode", used)).hasSize(1);
        assertThat(ok(AccountProcesses.DEACTIVATE, controller(), Map.of("accountCode", used)))
            .containsEntry("active", false).containsEntry("changed", true);
        assertThat(postRefused(used, cash)).isEqualTo("LEDGER_ACCOUNT_DISABLED");
        // Still in the chart, and so in reports.
        assertThat(find(LedgerEntities.ACCOUNT_DATASET, "accountCode", used)).singleElement()
            .satisfies(a -> assertThat(a).containsEntry("enabled", false));
        assertThat(ok(AccountProcesses.DEACTIVATE, controller(), Map.of("accountCode", used)))
            .containsEntry("changed", false);
        ok(AccountProcesses.REACTIVATE, controller(), Map.of("accountCode", used));
        post(used, cash, "5.00");

        // Never posted to: it may go, both halves of it.
        String unused = "6" + unique();
        account(unused, "EXPENSE", "DEBIT", Map.of());
        ok(AccountProcesses.DELETE, controller(), Map.of("accountCode", unused));
        assertThat(find(GlEntities.ACCOUNT_DATASET, "accountCode", unused)).isEmpty();
        assertThat(find(LedgerEntities.ACCOUNT_DATASET, "accountCode", unused)).isEmpty();
        assertThat(refused(AccountProcesses.DELETE, controller(), Map.of("accountCode", unused), 422))
            .isEqualTo(AccountProcesses.NOT_FOUND);
    }

    /** FIN-GL-004 acceptance 2: the history lists the old and new names, the user and the time. */
    @Test
    void everyChangeKeepsItsHistory() {
        String code = "6" + unique();
        Map<String, Object> created = account(code, "EXPENSE", "DEBIT", Map.of());
        clock.advance(Duration.ofDays(31));

        Map<String, Object> changed = ok(AccountProcesses.UPDATE, as("controller-2", "fin.account.maintain"),
            Map.of("accountCode", code, "accountName", "Renamed", "statementLine", "Other line",
                "controlClass", "AP", "clearing", true));
        assertThat(changed).containsEntry("changed", true);
        assertThat(find(GlEntities.ACCOUNT_DATASET, "accountCode", code).getFirst())
            .containsEntry("statementLine", "Other line").containsEntry("controlClass", "AP")
            .containsEntry("clearing", true);
        assertThat(ok(AccountProcesses.UPDATE, controller(), Map.of("accountCode", code, "accountName", "Renamed")))
            .containsEntry("changed", false);
        ok(AccountProcesses.UPDATE, controller(), Map.of("accountCode", code, "controlClass", ""));
        assertThat(find(GlEntities.ACCOUNT_DATASET, "accountCode", code).getFirst().get("controlClass")).isNull();

        List<Map<String, Object>> versions = get("/api/datasets/" + LedgerEntities.ACCOUNT_DATASET + "/entities/"
            + created.get("ledgerAccountId") + "/history", as("auditor", "*")).expectStatus().isOk()
            .expectBody(LIST).returnResult().getResponseBody();
        assertThat(versions).hasSize(2);
        assertThat(versions.toString()).contains("Account " + code).contains("Renamed").contains("controller-2")
            .contains("2026-03-03");
        // The account type never changes; for other income or expense neither does its side.
        String other = "7" + unique();
        account(other, "OTHER", "DEBIT", Map.of());
        assertThat(refused(AccountProcesses.UPDATE, controller(), Map.of("accountCode", other,
            "normalBalance", "CREDIT"), 422)).isEqualTo(AccountProcesses.INVALID_VALUE);
        assertOnlyInserted("fi_account_version");
    }
}

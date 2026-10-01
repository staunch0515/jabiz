package com.jabiz.finance.it;

import com.jabiz.finance.gl.JournalProcesses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.finance.it.JournalLifecycleIT.entry;
import static com.jabiz.finance.it.JournalLifecycleIT.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The general ledger's reports over posted entries, by posting date (FIN-RP-001 basis, FIN-GL-003, FIN-GL-022): the
 * trial balance with summary accounts rolled up, the adjustment period in or out and the books as recorded at a
 * time; the account inquiry with opening, running and closing balances; the journal register.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class GlReportsIT extends FinanceItSupport {

    private static final String TRIAL_BALANCE = "finance.gl.trial_balance";
    private static final String INQUIRY = "finance.gl.account_inquiry";
    private static final String REGISTER = "finance.gl.journal_register";

    private static boolean booksOpen;

    @BeforeEach
    void books() {
        if (!booksOpen) {
            openBooks();
            booksOpen = true;
        }
    }

    private String accountant() {
        return as("accountant", "fin.journal.prepare", "fin.journal.read");
    }

    private String reader() {
        return as("reader", "ledger.read", "fin.journal.read");
    }

    private void account(String code, Map<String, Object> extra) {
        Map<String, Object> input = new HashMap<>(Map.of("accountCode", code, "accountName", "Account " + code,
            "financialType", "EXPENSE", "normalBalance", "DEBIT", "statementLine", "Operating expenses"));
        input.putAll(extra);
        ok("FIN_ACCOUNT_CREATE", controller(), input);
    }

    private Map<String, Object> post(Map<String, Object> entry) {
        String id = (String) ok(JournalProcesses.SAVE, accountant(), entry).get("journalId");
        Map<String, Object> posted = ok(JournalProcesses.SUBMIT, accountant(), Map.of("journalId", id));
        assertThat(posted).containsEntry("status", "POSTED");
        return posted;
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, String code) {
        return rows.stream().filter(r -> code.equals(r.get("accountCode"))).findFirst().orElseThrow();
    }

    /** FIN-GL-003 acceptance 2, FIN-RP-001 basis. */
    @Test
    void theTrialBalanceRollsUpByPostingDate() {
        String group = "S" + unique();
        String a = "A" + unique();
        String b = "B" + unique();
        account(group, Map.of("summary", true));
        account(a, Map.of("parentCode", group));
        account(b, Map.of("parentCode", group));

        post(entry("2026-01-10", "First", List.of(line(a, "100.00", null, null), line("2100", null, "100.00", null))));
        post(entry("2026-01-20", "Second", List.of(line(b, "50.00", null, null),
            line("2100", null, "50.00", null))));
        // The test clock stands still: the later entries are recorded a minute on.
        String knownAt = clock.instant().toString();
        clock.advance(Duration.ofMinutes(1));
        post(entry("2026-02-05", "Third", List.of(line(a, "30.00", null, null), line("2100", null, "30.00", null))));
        Map<String, Object> thirteen = entry("2026-12-31", "Audit adjustment", List.of(line(b, "7.00", null, null),
            line("2100", null, "7.00", null)));
        thirteen.put("adjusting", true);
        thirteen.put("adjustmentPeriod", true);
        post(thirteen);

        List<Map<String, Object>> january = report(TRIAL_BALANCE, reader(), Map.of("through", "2026-01-31"));
        assertThat(row(january, a)).containsEntry("level", 1).containsEntry("parentCode", group)
            .containsEntry("financialType", "EXPENSE").containsEntry("statementLine", "Operating expenses");
        assertThat(amount(row(january, a).get("debit"))).isEqualByComparingTo("100.00");
        assertThat(amount(row(january, b).get("balance"))).isEqualByComparingTo("50.00");
        assertThat(row(january, group)).containsEntry("summary", true).containsEntry("level", 0);
        assertThat(amount(row(january, group).get("debit"))).isEqualByComparingTo("150.00");
        assertThat(amount(row(january, group).get("credit"))).isEqualByComparingTo("0.00");
        assertBalanced(january);

        List<Map<String, Object>> year = report(TRIAL_BALANCE, reader(), Map.of("through", "2026-12-31"));
        assertThat(amount(row(year, group).get("balance"))).isEqualByComparingTo("187.00");
        assertBalanced(year);
        Map<String, Object> withoutThirteen = Map.of("through", "2026-12-31", "adjustments", false);
        assertThat(amount(row(report(TRIAL_BALANCE, reader(), withoutThirteen), group).get("balance")))
            .isEqualByComparingTo("180.00");
        // The books as they were recorded when the second entry posted: the later ones are not in them.
        assertThat(amount(row(report(TRIAL_BALANCE, reader(), Map.of("through", "2026-12-31", "knownAt", knownAt)),
            group).get("balance")))
            .isEqualByComparingTo("150.00");

        // Not for those without the ledger's read permission.
        post("/api/queries/" + TRIAL_BALANCE, accountant(), Map.of("params", Map.of("through", "2026-01-31")))
            .expectStatus().isForbidden();
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("params", Map.of("through", "2026-01-31"));
        String csv = new String(post("/api/queries/" + TRIAL_BALANCE + "/export?format=csv", reader(), export)
            .expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody(), StandardCharsets.UTF_8);
        assertThat(csv).contains(group).contains("150.00");
    }

    /** FIN-GL-022 acceptance 1. */
    @Test
    void theAccountInquiryShowsOpeningRunningAndClosingBalances() {
        String code = "I" + unique();
        account(code, Map.of());
        Map<String, Object> before = post(entry("2026-03-03", "Before the range", List.of(
            line(code, "200.00", null, null), line("2100", null, "200.00", null))));
        Map<String, Object> first = post(entry("2026-04-02", "In the range", List.of(
            line(code, "75.25", null, "first"), line("2100", null, "75.25", null))));
        post(entry("2026-04-09", "Back", List.of(line("2100", "25.25", null, null),
            line(code, null, "25.25", "second"))));
        post(entry("2026-05-01", "After", List.of(line(code, "1.00", null, null), line("2100", null, "1.00", null))));

        List<Map<String, Object>> rows = report(INQUIRY, reader(),
            Map.of("account", code, "from", "2026-04-01", "to", "2026-04-30"));
        assertThat(rows).extracting(r -> r.get("rowKind")).containsExactly("OPENING", "ENTRY", "ENTRY", "CLOSING");
        assertThat(amount(rows.get(0).get("runningBalance"))).isEqualByComparingTo("200.00");
        assertThat(rows.get(1)).containsEntry("postingDate", "2026-04-02").containsEntry("glNo", first.get("glNo"))
            .containsEntry("documentNo", first.get("journalNo")).containsEntry("memo", "first")
            .containsEntry("sourceEntity", "FinJournal").containsEntry("sourceId", first.get("journalId"));
        assertThat(amount(rows.get(1).get("runningBalance"))).isEqualByComparingTo("275.25");
        assertThat(amount(rows.get(2).get("credit"))).isEqualByComparingTo("25.25");
        assertThat(amount(rows.get(2).get("runningBalance"))).isEqualByComparingTo("250.00");
        assertThat(amount(rows.get(3).get("debit"))).isEqualByComparingTo("75.25");
        assertThat(amount(rows.get(3).get("credit"))).isEqualByComparingTo("25.25");
        assertThat(amount(rows.get(3).get("runningBalance"))).isEqualByComparingTo("250.00");
        assertThat(before.get("glNo")).isNotNull();

        // A range without entries still opens and closes.
        List<Map<String, Object>> empty = report(INQUIRY, reader(),
            Map.of("account", code, "from", "2026-06-01", "to", "2026-06-30"));
        assertThat(empty).extracting(r -> r.get("rowKind")).containsExactly("OPENING", "CLOSING");
        assertThat(amount(empty.get(1).get("runningBalance"))).isEqualByComparingTo("251.00");
    }

    @Test
    void theJournalRegisterListsEntriesByPostingDate() {
        Map<String, Object> posted = post(entry("2026-07-14", "Register sample", List.of(
            line("6800", "12.00", null, null), line("2100", null, "12.00", null))));
        String draftId = (String) ok(JournalProcesses.SAVE, accountant(), entry("2026-07-15", "Register draft",
            List.of(line("6800", "3.00", null, null), line("2100", null, "3.00", null)))).get("journalId");

        List<Map<String, Object>> july = report(REGISTER, reader(), Map.of("from", "2026-07-01", "to", "2026-07-31"));
        assertThat(july).extracting(r -> r.get("description")).containsExactly("Register sample", "Register draft");
        assertThat(july.getFirst()).containsEntry("journalNo", posted.get("journalNo"))
            .containsEntry("glNo", posted.get("glNo")).containsEntry("status", "POSTED")
            .containsEntry("periodKey", "2026-07").containsEntry("preparer", "accountant");
        assertThat(amount(july.getFirst().get("totalDebit"))).isEqualByComparingTo("12.00");
        assertThat(report(REGISTER, reader(), Map.of("from", "2026-07-01", "to", "2026-07-31", "status", "DRAFT")))
            .singleElement().satisfies(r -> assertThat(r).containsEntry("description", "Register draft"));
        assertThat(draftId).isNotBlank();
    }

    /** Over the accounts that take postings, the debits equal the credits. */
    private static void assertBalanced(List<Map<String, Object>> rows) {
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            if (!Boolean.TRUE.equals(row.get("summary"))) {
                debit = debit.add(amount(row.get("debit")));
                credit = credit.add(amount(row.get("credit")));
            }
        }
        assertThat(debit).isEqualByComparingTo(credit).isPositive();
    }
}

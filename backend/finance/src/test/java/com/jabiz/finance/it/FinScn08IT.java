package com.jabiz.finance.it;

import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.runtime.report.ReportProcesses;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.finance.it.JournalLifecycleIT.entry;
import static com.jabiz.finance.it.JournalLifecycleIT.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-SCN-08, a back-dated correction and "as known on" reporting (FIN-RP-020, FIN-RP-009), after January is closed:
 * BILL-P-8010 is booked on 2026-02-03 to professional fees in error and RCPT-0004 on 2026-02-05, and the trial balance
 * as of 2026-02-05 is issued; on 2026-02-20 JE-0005 reclassifies it to cost of goods sold effective 2026-02-03. Run as
 * known on 2026-02-05 and on 2026-02-28 the trial balance of 2026-02-05 is FIN-EXP-16: only 5000 and 6400 differ, by
 * 15,000.00. The issued report is verified identical against the data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FinScn08IT extends JanuaryBooks {

    @Test
    @SuppressWarnings("unchecked")
    void aBackDatedCorrectionKnownAtTwoTimes() throws IOException {
        januaryPostings();
        closeJanuary();

        // Step 1: 3 February, BILL-P-8010 to 6400 in error; 5 February, RCPT-0004; the trial balance issued.
        clock.advance(Duration.ofDays(3).plusHours(4));
        people();
        bill(new String[] {"V100", "P-8010", "2026-02-03", "6400", "15000.00"});
        clock.advance(Duration.ofDays(2));
        people();
        receipt("C100", "2026-02-05", "51135.00", "INV-1004");
        String fifth = clock.instant().toString();
        Map<String, Object> params = Map.of("through", "2026-02-05");
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("templateId", "finance.report.trial_balance");
        issue.put("params", params);
        Map<String, Object> issued = ok(ReportProcesses.ISSUE, controller, issue);

        // Step 2: 20 February, JE-0005 effective 3 February.
        clock.advance(Duration.ofDays(15));
        people();
        String je5 = (String) ok(JournalProcesses.SAVE, accountant, entry("2026-02-03",
            "Reclassify P-8010 to cost of goods sold", List.of(line("5000", "15000.00", null, null),
                line("6400", null, "15000.00", null)))).get("journalId");
        decide(ok(JournalProcesses.SUBMIT, accountant, Map.of("journalId", je5)).get("approvalRequestId"));

        // Step 3: 28 February, the trial balance of 5 February as known on the 5th and on the 28th (FIN-EXP-16).
        clock.advance(Duration.ofDays(8));
        people();
        String twentyEighth = clock.instant().toString();
        List<Map<String, Object>> compared = report("finance.gl.trial_balance_compare", controller,
            Map.of("through", "2026-02-05", "earlier", fifth, "later", twentyEighth, "changedOnly", true));
        assertThat(compared).extracting(r -> r.get("accountCode") + " " + amount(r.get("earlierBalance")) + " "
            + amount(r.get("laterBalance")) + " " + amount(r.get("difference")))
            .containsExactly("5000 22000.00 37000.00 15000.00", "6400 49000.00 34000.00 -15000.00");
        assertThat(report("finance.gl.trial_balance_compare", controller, Map.of("through", "2026-02-05",
            "earlier", fifth))).hasSizeGreaterThan(compared.size());

        // The report trial balance gives the same at each time, and as summing the entries.
        Map<String, Map<String, Object>> asFifth = sameAsEntries(controller, "2026-02-01", "2026-02-05",
            Map.of("knownAt", fifth));
        Map<String, Map<String, Object>> asLater = sameAsEntries(controller, "2026-02-01", "2026-02-05",
            Map.of("knownAt", twentyEighth));
        assertThat(amount(asFifth.get("5000").get("closing"))).isEqualByComparingTo("22000.00");
        assertThat(amount(asLater.get("5000").get("closing"))).isEqualByComparingTo("37000.00");
        assertThat(amount(asFifth.get("6400").get("closing"))).isEqualByComparingTo("49000.00");
        assertThat(amount(asLater.get("6400").get("closing"))).isEqualByComparingTo("34000.00");
        asFifth.forEach((code, row) -> {
            if (!List.of("5000", "6400").contains(code) && !Boolean.TRUE.equals(row.get("summary"))) {
                assertThat(amount(asLater.get(code).get("closing"))).as(code)
                    .isEqualByComparingTo(amount(row.get("closing")));
            }
        });

        // The issued report of step 1 is reproduced: verified against the data, identical.
        Map<String, Object> verified = post("/api/reports/runs/" + issued.get("runId") + "/verify",
            as("auditor", "report.archive.read", "ledger.read"), Map.of()).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(verified).containsEntry("verdict", "identical").containsEntry("recomputable", true);
    }
}

package com.jabiz.app.it.ledger;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.ledger.Direction;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.report.ReportProcesses;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issued reports (docs/design/19-reports.md section 5, ROADMAP 14d-3): a report issued from the ledger is reproduced
 * as the same file after later postings, verifies against the data, can be superseded once; a report over data
 * changed in place is found to differ; reading needs the archive permission and the template's; the archive is
 * append-only.
 */
@SpringBootTest
class ReportArchiveIT extends LedgerItSupport {

    private static final String TRIAL_BALANCE = "jabiz.ledger.account_balances";
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
        new ParameterizedTypeReference<>() {};

    private ReportProcesses.IssueOutput issue(String template, Map<String, Object> params, String supersedes) {
        return run(ReportProcesses.ISSUE, new ReportProcesses.IssueInput(template, params, null, null, supersedes));
    }

    private ReportProcesses.IssueOutput trialBalance(String supersedes) {
        return issue(TRIAL_BALANCE, Map.of("asOf", clock.instant().plus(Duration.ofDays(1)).toString()), supersedes);
    }

    private String reader() {
        return bearer("report.archive.read", "ledger.read");
    }

    private EntityExchangeResult<byte[]> reproduce(String runId, String format) {
        return client().get().uri("/api/reports/runs/{id}/export?format={format}", runId, format)
            .header(HttpHeaders.AUTHORIZATION, reader())
            .exchange().expectStatus().isOk().expectBody(byte[].class).returnResult();
    }

    private Map<String, Object> verify(String runId) {
        return client().post().uri("/api/reports/runs/{id}/verify", runId)
            .header(HttpHeaders.AUTHORIZATION, reader())
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    @Test
    void anIssuedReportIsReproducedExactlyAfterLaterPostingsAndVerifies() throws Exception {
        String prefix = prefix();
        openAccounts(prefix, "1000", "4000");
        post(prefix, "1000", Direction.DEBIT, "500", "4000", Direction.CREDIT, "500");
        clock.advance(Duration.ofMinutes(1));
        ReportProcesses.IssueOutput issued = trialBalance(null);
        assertThat(issued.recomputable()).isTrue();
        assertThat(issued.contentHash()).hasSize(64);

        EntityExchangeResult<byte[]> pdf = reproduce(issued.runId(), "pdf");
        byte[] csv = reproduce(issued.runId(), "csv").getResponseBody();

        // Later: more postings, an account renamed... the archive still shows what was issued.
        clock.advance(Duration.ofDays(3));
        post(prefix, "1000", Direction.DEBIT, "7777", "4000", Direction.CREDIT, "7777");
        EntityExchangeResult<byte[]> again = reproduce(issued.runId(), "pdf");

        assertThat(again.getResponseBody()).isEqualTo(pdf.getResponseBody());
        assertThat(again.getResponseHeaders().getFirst("X-Jabiz-Content-Hash")).isEqualTo(issued.contentHash());
        assertThat(reproduce(issued.runId(), "csv").getResponseBody()).isEqualTo(csv);
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).contains(prefix + "1000").doesNotContain("7777");
        try (PDDocument document = Loader.loadPDF(pdf.getResponseBody())) {
            assertThat(new PDFTextStripper().getText(document)).contains("Trial balance", "Page 1 of");
        }

        Map<String, Object> verification = verify(issued.runId());
        assertThat(verification).containsEntry("verdict", "identical").containsEntry("recomputable", true)
            .containsEntry("currentHash", issued.contentHash());

        // Its knownAt was filled in with the issue time and kept with the parameters.
        Map<String, Object> detail = client().get().uri("/api/reports/runs/{id}", issued.runId())
            .header(HttpHeaders.AUTHORIZATION, reader())
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) detail.get("params");
        assertThat(params).containsKeys("asOf", "knownAt");
        assertThat(query("SELECT count(*) AS n FROM sys_outbox_event WHERE event_type = 'jabiz.report.issued'"
            + " AND payload->>'runId' = ?", issued.runId()).getFirst().get("n")).isEqualTo(1L);
    }

    @Test
    void aTemplateOfAnotherVersionIsReportedNotRecomputed() {
        ReportProcesses.IssueOutput issued = trialBalance(null);
        // An archived run whose template has changed since: its row, as a run of that earlier version would have it.
        String older = UUID.randomUUID().toString();
        execute("INSERT INTO sys_report_run SELECT CAST(? AS uuid), template_id, repeat('0', 64), template_source,"
            + " permissions, title, company, period, language, params, parameters, as_of, read_at, known_at,"
            + " landscape, columns, rows, row_count, content_hash, recomputable, issued_by, issued_time,"
            + " process_seq_id, version FROM sys_report_run WHERE run_id = CAST(? AS uuid)", older, issued.runId());

        assertThat(verify(older)).containsEntry("verdict", "template_changed")
            .containsEntry("currentVersion", issued.templateVersion());
    }

    @Test
    void aReportOverDataChangedInPlaceDiffers() {
        String name = "soft-" + UUID.randomUUID().toString().substring(0, 8);
        execute("INSERT INTO it_soft (f_id, f_name, is_deleted) VALUES (?, ?, false)", name, name);
        ReportProcesses.IssueOutput issued = issue("it.soft_names", Map.of(), null);
        assertThat(issued.recomputable()).isFalse();

        execute("UPDATE it_soft SET f_name = ? WHERE f_id = ?", name + "-changed", name);

        Map<String, Object> verification = client().post().uri("/api/reports/runs/{id}/verify", issued.runId())
            .header(HttpHeaders.AUTHORIZATION, bearer("report.archive.read", "it.query"))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(verification).containsEntry("verdict", "differs").containsEntry("recomputable", false);
    }

    @Test
    void aRunIsSupersededOnceByARunOfTheSameTemplate() {
        ReportProcesses.IssueOutput first = trialBalance(null);
        ReportProcesses.IssueOutput second = trialBalance(first.runId());

        List<Map<String, Object>> runs = client().get().uri("/api/reports/runs?template={t}", TRIAL_BALANCE)
            .header(HttpHeaders.AUTHORIZATION, reader())
            .exchange().expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
        Map<String, Object> firstSummary = runs.stream().filter(r -> first.runId().equals(r.get("runId")))
            .findFirst().orElseThrow();
        assertThat(firstSummary).containsEntry("supersededBy", second.runId());

        assertThatThrownBy(() -> trialBalance(first.runId()))
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(Violation::ruleCode).containsExactly(PlatformErrorCodes.REPORT_ALREADY_SUPERSEDED));
        ReportProcesses.IssueOutput other = issue("jabiz.ledger.currency_balances",
            Map.of("asOf", clock.instant().toString()), null);
        assertThatThrownBy(() -> trialBalance(other.runId()))
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(Violation::ruleCode).containsExactly(PlatformErrorCodes.REPORT_SUPERSEDE_MISMATCH));
        assertThatThrownBy(() -> trialBalance(UUID.randomUUID().toString()))
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void readingNeedsTheArchivePermissionAndTheTemplates() {
        ReportProcesses.IssueOutput issued = trialBalance(null);

        client().get().uri("/api/reports/runs").header(HttpHeaders.AUTHORIZATION, bearer("ledger.read"))
            .exchange().expectStatus().isForbidden();
        client().get().uri("/api/reports/runs/{id}", issued.runId())
            .header(HttpHeaders.AUTHORIZATION, bearer("report.archive.read"))
            .exchange().expectStatus().isNotFound();
        List<Map<String, Object>> visible = client().get().uri("/api/reports/runs")
            .header(HttpHeaders.AUTHORIZATION, bearer("report.archive.read"))
            .exchange().expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
        assertThat(visible).extracting(r -> r.get("runId")).doesNotContain(issued.runId());
        client().get().uri("/api/reports/runs/{id}/export?format=docx", issued.runId())
            .header(HttpHeaders.AUTHORIZATION, reader())
            .exchange().expectStatus().isBadRequest();
        client().get().uri("/api/reports/runs/{id}", "not-a-uuid")
            .header(HttpHeaders.AUTHORIZATION, reader())
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void issuingNeedsTheTemplatesPermissionsAndTheArchiveIsAppendOnly() {
        Map<String, Object> refused = client().post().uri("/api/processes/{name}/1", ReportProcesses.ISSUE)
            .header(HttpHeaders.AUTHORIZATION, bearer("report.issue"))
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .bodyValue(Map.of("templateId", TRIAL_BALANCE, "params", Map.of("asOf", Instant.now(clock).toString())))
            .exchange().expectStatus().isForbidden().expectBody(MAP).returnResult().getResponseBody();
        assertThat(refused.toString()).contains("PERMISSION_DENIED");

        ReportProcesses.IssueOutput issued = trialBalance(null);
        assertThatThrownBy(() -> execute("UPDATE sys_report_run SET title = 'x' WHERE run_id = CAST(? AS uuid)",
            issued.runId())).hasMessageContaining("sys_report_run");
        assertThatThrownBy(() -> execute("DELETE FROM sys_report_run WHERE run_id = CAST(? AS uuid)",
            issued.runId())).hasMessageContaining("sys_report_run");
    }
}

package com.jabiz.app.it.ledger;

import com.jabiz.ledger.Direction;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exports of SQL templates (docs/design/19-reports.md section 4, ROADMAP 14d-2), on the trial balance of the platform
 * ledger: CSV with exact values, Excel with numeric cells whose sums equal the report's, PDF with the page header and
 * the same bytes for the same run; results over the limit refused; the permissions of a run.
 */
@SpringBootTest
@TestPropertySource(properties = {"jabiz.reports.export.max-rows=4", "jabiz.reports.company=Acme Inc."})
class ReportExportIT extends LedgerItSupport {

    private static final String TRIAL_BALANCE = "jabiz.ledger.account_balances";

    /** Three accounts of their own prefix: 1000 at 300, 1010 at 700 and 4000 at -1000. */
    private String ledger() {
        String prefix = prefix();
        openAccounts(prefix, "1000", "1010", "4000");
        post(prefix, "1000", Direction.DEBIT, "300.00", "1010", Direction.DEBIT, "700.00",
            "4000", Direction.CREDIT, "1000.00");
        return prefix;
    }

    private Map<String, Object> body(String prefix) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", Map.of("asOf", clock.instant().plusSeconds(60).toString()));
        body.put("filters", List.of(Map.of("field", "accountCode", "op", "like", "value", prefix + "%")));
        return body;
    }

    private WebTestClient.ResponseSpec export(String format, Map<String, Object> body, String authorization) {
        return client().post().uri("/api/queries/{id}/export?format={format}", TRIAL_BALANCE, format)
            .header(HttpHeaders.AUTHORIZATION, authorization)
            .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .exchange();
    }

    private EntityExchangeResult<byte[]> ok(String format, Map<String, Object> body) {
        return export(format, body, bearer("ledger.read")).expectStatus().isOk().expectBody(byte[].class)
            .returnResult();
    }

    @Test
    void csvHasTheLabelsAndExactValues() {
        String prefix = ledger();

        EntityExchangeResult<byte[]> result = ok("csv", body(prefix));

        assertThat(result.getResponseHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(result.getResponseHeaders().getContentDisposition().getFilename())
            .matches("jabiz\\.ledger\\.account_balances-\\d{8}-\\d{6}\\.csv");
        String text = new String(result.getResponseBody(), StandardCharsets.UTF_8).substring(1);
        String[] lines = text.split("\r\n");
        assertThat(lines[0]).startsWith("Account,Account name,Type,Parent,Summary,Level,Debit,Credit,Balance");
        assertThat(lines).hasSize(4);
        assertThat(lines[1]).startsWith(prefix + "1000,Account 1000,ASSET,").endsWith(",false,0,300,0,300");
        assertThat(lines[3]).endsWith(",0,1000,-1000");
    }

    @Test
    void excelAmountsAreNumbersWhoseSumsEqualTheReports() throws Exception {
        String prefix = ledger();

        EntityExchangeResult<byte[]> result = ok("xlsx", body(prefix));

        assertThat(result.getResponseHeaders().getContentType().toString())
            .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(result.getResponseBody()))) {
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(rows.getFirst().getCellText(0)).isEqualTo("Trial balance");
            assertThat(rows.get(1).getCellText(0)).isEqualTo("Acme Inc.");
            int header = 0;
            while (!"Account".equals(rows.get(header).getCellText(0))) {
                header++;
            }
            List<Row> data = rows.subList(header + 1, rows.size());
            assertThat(data).hasSize(3);
            BigDecimal debit = BigDecimal.ZERO;
            BigDecimal balance = BigDecimal.ZERO;
            for (Row row : data) {
                assertThat(row.getCell(6).getType()).isEqualTo(CellType.NUMBER);
                debit = debit.add(row.getCellAsNumber(6).orElseThrow());
                balance = balance.add(row.getCellAsNumber(8).orElseThrow());
            }
            assertThat(debit).isEqualByComparingTo("1000");
            assertThat(balance).isEqualByComparingTo("0");
        }
    }

    @Test
    void pdfHasThePageHeaderAndTheSameRunGivesTheSameBytes() throws Exception {
        String prefix = ledger();
        Map<String, Object> body = body(prefix);

        byte[] first = ok("pdf", body).getResponseBody();
        byte[] again = ok("pdf", body).getResponseBody();

        assertThat(again).isEqualTo(first);
        try (PDDocument document = Loader.loadPDF(first)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Acme Inc.", "Trial balance", "Run at", "asOf:", "Page 1 of 1", "As known on",
                "Version " + templates().substring(0, 12), "Balance", "300", "(1,000)");
        }
    }

    @Test
    void theRecordedTimeOfTheRunIsInThePageFooter() throws Exception {
        String prefix = ledger();
        Instant before = clock.instant().minusSeconds(3600);
        Map<String, Object> body = body(prefix);
        body.put("params", Map.of("asOf", clock.instant().plusSeconds(60).toString(), "knownAt", before.toString()));

        try (PDDocument document = Loader.loadPDF(ok("pdf", body).getResponseBody())) {
            String text = new PDFTextStripper().getText(document);
            // Known an hour ago, the accounts did not exist yet.
            assertThat(text).contains("As known on").doesNotContain(prefix);
        }
        String csv = new String(ok("csv", body).getResponseBody(), StandardCharsets.UTF_8);
        assertThat(csv.split("\r\n")).hasSize(1);
    }

    @Test
    void aResultOverTheLimitIsRefusedNotCut() {
        String prefix = ledger();
        openAccounts(prefix, "5000", "6000");

        export("csv", body(prefix), bearer("ledger.read")).expectStatus().isEqualTo(422)
            .expectBody(MAP).value(problem -> assertThat(problem.toString()).contains("REPORT_TOO_LARGE", "4"));
    }

    @Test
    void anExportNeedsThePermissionsOfARunAndAKnownFormat() {
        String prefix = ledger();

        export("csv", body(prefix), bearer()).expectStatus().isForbidden();
        export("docx", body(prefix), bearer("ledger.read")).expectStatus().isBadRequest()
            .expectBody(MAP).value(problem -> assertThat(problem.toString()).contains("format"));
        client().post().uri("/api/queries/{id}/export?format=csv", "nope")
            .header(HttpHeaders.AUTHORIZATION, bearer("ledger.read"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isNotFound();
    }

    private String templates() {
        return context.getBean(com.jabiz.runtime.query.SqlTemplateRegistry.class).find(TRIAL_BALANCE).orElseThrow()
            .version();
    }
}

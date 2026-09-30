package com.jabiz.app.it;

import com.jabiz.app.FreightBilling;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The date kind (docs/design/02-metamodel.md section 1) end to end on PostgreSQL: a {@code date} column written and
 * read through a dataset as {@code YYYY-MM-DD}, compared and sorted as days, refused when it is not a day, and used as
 * a template's parameter and result, in exports too. The sample is the due date of a freight statement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class DateKindIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
        new ParameterizedTypeReference<>() {};
    private static final String DUE = "logistics.freight_statements_due";

    @Autowired
    ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void reset() {
        client = WebTestClient.bindToApplicationContext(context).configureClient()
            .defaultHeader("X-Jabiz-Actor", "tester").defaultHeader("X-Jabiz-Permissions", "*")
            .defaultHeader("Accept-Language", "en").build();
        execute("DELETE FROM t_freight_statement");
    }

    @Test
    void datesAreWrittenReadComparedAndSortedAsDays() {
        String january = statement("2026-01", "2026-02-28");
        statement("2026-02", "2026-03-31");
        statement("2025-12", "2026-01-31");

        Map<String, Object> read = client.get().uri("/api/datasets/{d}/entities/{id}", FreightBilling.STATEMENT_DATASET,
                january).exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(attributes(read)).containsEntry("dueDate", "2026-02-28");
        assertThat(query("SELECT f_due_date::text AS due FROM t_freight_statement WHERE f_statement_id = ?", january))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("due", "2026-02-28"));

        Map<String, Object> page = search(Map.of(
            "filters", List.of(Map.of("field", "dueDate", "op", "between", "from", "2026-01-31", "to", "2026-02-28")),
            "sorts", List.of(Map.of("field", "dueDate", "asc", false))));
        assertThat(items(page)).extracting(item -> attributes(item).get("statementMonth"))
            .containsExactly("2026-01", "2025-12");
    }

    @Test
    void aValueThatIsNotADayIsRefused() {
        for (String value : List.of("2026-02-30", "2026-02-28T00:00:00Z", "02/28/2026")) {
            Map<String, Object> problem = commit(Map.of("statementMonth", "2026-01", "chargeCount", 0,
                    "totalAmount", 0, "closed", true, "dueDate", value))
                .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
            assertThat(problem.toString()).contains("dueDate").contains("INVALID_VALUE");
        }
        Map<String, Object> problem = client.post().uri("/api/datasets/{id}/query", FreightBilling.STATEMENT_DATASET)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("filters", List.of(Map.of("field", "dueDate", "op", "gte", "value", "tomorrow"))))
            .exchange().expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(problem.toString()).contains("dueDate");
    }

    @Test
    void templatesTakeAndReturnDates() throws Exception {
        statement("2026-01", "2026-02-28");
        statement("2026-02", "2026-03-31");

        Map<String, Object> page = client.post().uri("/api/queries/{id}", DUE)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("params", Map.of("dueBy", "2026-03-01")))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(items(page)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("statementMonth", "2026-01").containsEntry("dueDate", "2026-02-28"));

        client.post().uri("/api/queries/{id}", DUE)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("params", Map.of("dueBy", "2026-03-01T00:00Z")))
            .exchange().expectStatus().isBadRequest();

        String csv = new String(export("csv", "2026-12-31"), StandardCharsets.UTF_8);
        assertThat(csv).contains("2026-01,2026-02-28,").contains("2026-02,2026-03-31,");

        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(export("xlsx", "2026-12-31")))) {
            List<Row> rows = workbook.getFirstSheet().read();
            Row first = rows.stream().filter(row -> "2026-01".equals(row.getCellText(0))).findFirst().orElseThrow();
            // A real date cell (a serial day number with a date format), not text.
            assertThat(first.getCell(1).getType()).isEqualTo(CellType.NUMBER);
            assertThat(first.getCellAsDate(1).orElseThrow().toLocalDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        }
    }

    // ---------------------------------------------------------------- Helpers

    private String statement(String month, String dueDate) {
        List<Map<String, Object>> created = commit(Map.of("statementMonth", month, "chargeCount", 1,
                "totalAmount", 1000, "closed", true, "dueDate", dueDate))
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
        return String.valueOf(created.getFirst().get("id"));
    }

    private WebTestClient.ResponseSpec commit(Map<String, Object> attributes) {
        Map<String, Object> change = new HashMap<>();
        change.put("action", "INSERT");
        change.put("version", 0);
        change.put("attributes", attributes);
        return client.post().uri("/api/datasets/{id}/commit", FreightBilling.STATEMENT_DATASET)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("changes", List.of(change))).exchange();
    }

    private Map<String, Object> search(Map<String, Object> body) {
        return client.post().uri("/api/datasets/{id}/query", FreightBilling.STATEMENT_DATASET)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    private byte[] export(String format, String dueBy) {
        return client.post().uri("/api/queries/{id}/export?format={format}", DUE, format)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("params", Map.of("dueBy", dueBy)))
            .exchange().expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> instance) {
        return (Map<String, Object>) instance.get("attributes");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }
}

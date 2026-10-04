package com.jabiz.app.it;

import com.jabiz.app.it.fixture.ItFixtures;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Queries under load (phase 14q; docs/design/03-dataset.md section 2, 05-sql-template.md section 5.1): a page not
 * filled tells its total, so the query is not run a second time to count it; a query past its time limit is stopped
 * by the database and answered 503 {@code QUERY_TIMEOUT}, not left running after its caller gave up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
@TestPropertySource(properties = "it.sql-log.enabled=true")
class QueryLoadIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    @Autowired
    ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToApplicationContext(context).configureClient()
            .responseTimeout(Duration.ofSeconds(30)).build();
    }

    private static String owner(int tickets) {
        String owner = "o-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < tickets; i++) {
            execute("INSERT INTO it_ticket (f_id, f_title, f_amount, f_status, f_owner, f_created_at) VALUES"
                + " (?, ?, 1, 'OPEN', ?, now())", UUID.randomUUID().toString(), "t" + i, owner);
        }
        return owner;
    }

    private WebTestClient.ResponseSpec post(String uri, Map<String, Object> body) {
        return client.post().uri(uri).header("X-Jabiz-Actor", "alice").header("X-Jabiz-Permissions", "*")
            .header("Accept-Language", "en").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private Map<String, Object> page(String uri, Map<String, Object> body) {
        SqlStatementLog.STATEMENTS.clear();
        return post(uri, body).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    private static boolean counted() {
        return SqlStatementLog.STATEMENTS.stream().anyMatch(sql -> sql.contains("count(*)"));
    }

    @Test
    void aTemplatePageNotFilledIsNotCountedAgain() {
        assertThat(ItFixtures.TICKET_MAX_QUERY_BATCH).isEqualTo(5);
        String few = owner(3);
        assertThat(page("/api/queries/it.ticket_titles", Map.of("params", Map.of("owner", few), "limit", 5)))
            .containsEntry("total", 3);
        assertThat(counted()).isFalse();

        String many = owner(7);
        assertThat(page("/api/queries/it.ticket_titles", Map.of("params", Map.of("owner", many), "limit", 5)))
            .containsEntry("total", 7);
        assertThat(counted()).as("a full page").isTrue();
        assertThat(page("/api/queries/it.ticket_titles", Map.of("params", Map.of("owner", many), "offset", 5,
            "limit", 5))).containsEntry("total", 7);
        assertThat(counted()).as("the last page").isFalse();
        assertThat(page("/api/queries/it.ticket_titles", Map.of("params", Map.of("owner", many), "offset", 20,
            "limit", 5))).containsEntry("total", 7);
        assertThat(counted()).as("an empty page past the end").isTrue();
    }

    @Test
    void aDatasetPageNotFilledIsNotCountedAgain() {
        // The ticket dataset has no list view, so no filters: the class's own tickets, from none.
        execute("DELETE FROM it_ticket");
        String uri = "/api/datasets/" + ItFixtures.TICKET_DATASET + "/query";
        owner(3);
        assertThat(page(uri, Map.of("limit", 5))).containsEntry("total", 3);
        assertThat(counted()).isFalse();

        owner(4);
        assertThat(page(uri, Map.of("limit", 5))).containsEntry("total", 7);
        assertThat(counted()).as("a full page").isTrue();
        assertThat(page(uri, Map.of("offset", 5, "limit", 5))).containsEntry("total", 7);
        assertThat(counted()).as("the last page").isFalse();
        assertThat(page(uri, Map.of("offset", 20, "limit", 5))).containsEntry("total", 7);
        assertThat(counted()).as("an empty page past the end").isTrue();
    }

    @Test
    void aQueryPastItsTimeIsStoppedByTheDatabaseAndAnswered503() {
        String owner = owner(1);
        // Within its time it answers as any other (and the template's path is warm for what follows).
        assertThat(page("/api/queries/it.slow_titles", Map.of("params", Map.of("owner", owner, "pause", 0))))
            .containsEntry("total", 1);
        long start = System.nanoTime();
        Map<String, Object> problem = post("/api/queries/it.slow_titles",
            Map.of("params", Map.of("owner", owner, "pause", 20))).expectStatus().isEqualTo(503)
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(problem.toString()).contains(PlatformErrorCodes.QUERY_TIMEOUT,
            "did not finish within its time limit");
        // Answered after its 500 ms, not its 20 seconds; and the database no longer runs it: stopped there, not only
        // abandoned by the client (which would leave it sleeping).
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(query("SELECT pid FROM pg_stat_activity WHERE state = 'active' AND query LIKE '%pg_sleep%'"
            + " AND pid <> pg_backend_pid()")).isEmpty();
    }
}

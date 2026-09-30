package com.jabiz.finance.it;

import com.jabiz.finance.gl.Csv;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finance integration tests call the API as users would, with real access tokens holding the permissions of the
 * finance roles. The test classes share nothing: each has its own schema, and within a class every test uses data of
 * its own, since the temporal tables cannot be cleaned up.
 */
public abstract class FinanceItSupport extends PostgresIntegrationTest {

    protected static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    protected static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
        new ParameterizedTypeReference<>() {};

    /** The requirements' sample company, read in tests only (backend/finance/CLAUDE.md section 1). */
    protected static final Path SAMPLE_COMPANY = Path.of("../../docs/finance-requirements/sample-company");

    @Autowired
    ApplicationContext context;

    @Autowired
    protected JwtService tokens;

    protected WebTestClient client;

    @BeforeEach
    protected void client() {
        client = WebTestClient.bindToApplicationContext(context).configureClient()
            .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "en").build();
    }

    protected String as(String actor, String... permissions) {
        return TestTokens.bearer(tokens, actor, permissions);
    }

    /** A controller of the books: may maintain accounts, periods and master data. */
    protected String controller() {
        return as("controller", "fin.account.read", "fin.account.maintain", "fin.master.read",
            "fin.dimension.maintain", "fin.fx.maintain", "fin.period.read", "fin.period.maintain",
            "fin.period.close");
    }

    protected WebTestClient.ResponseSpec run(String process, String authorization, Object input) {
        return post("/api/processes/" + process + "/latest", authorization, input);
    }

    /** Runs a process that must succeed; returns its output. */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> ok(String process, String authorization, Object input) {
        Map<String, Object> result = run(process, authorization, input).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        return (Map<String, Object>) result.get("output");
    }

    /** Runs a process that must be refused with {@code status}; returns the first violation's rule code. */
    @SuppressWarnings("unchecked")
    protected String refused(String process, String authorization, Object input, int status) {
        Map<String, Object> problem = run(process, authorization, input).expectStatus().isEqualTo(status)
            .expectBody(MAP).returnResult().getResponseBody();
        List<Map<String, Object>> violations = (List<Map<String, Object>>) problem.get("violations");
        return violations == null || violations.isEmpty() ? String.valueOf(problem.get("title"))
            : (String) violations.getFirst().get("ruleCode");
    }

    /** A write through a dataset's generic API that must be refused; returns the first violation's rule code. */
    @SuppressWarnings("unchecked")
    protected String commitRefused(String dataset, String authorization, Map<String, Object> change) {
        Map<String, Object> problem = post("/api/datasets/" + dataset + "/commit", authorization,
            Map.of("changes", List.of(change))).expectStatus().is4xxClientError().expectBody(MAP).returnResult()
            .getResponseBody();
        return (String) ((List<Map<String, Object>>) problem.get("violations")).getFirst().get("ruleCode");
    }

    protected WebTestClient.ResponseSpec post(String path, String authorization, Object body) {
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, authorization).bodyValue(body).exchange();
    }

    protected WebTestClient.ResponseSpec get(String path, String authorization) {
        return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, authorization).exchange();
    }

    /** The current instances of a dataset matching {@code field = value}, as the API returns them. */
    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> find(String dataset, String field, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filters", List.of(Map.of("field", field, "op", "eq", "value", value)));
        body.put("limit", 500);
        Map<String, Object> page = post("/api/datasets/" + dataset + "/query", as("reader", "*"), body)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) page.get("items")).stream()
            .map(item -> (Map<String, Object>) item.get("attributes")).toList();
    }

    protected static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    /** The rows of one of the sample company's files, by column name. */
    protected static List<Map<String, String>> sample(String file) {
        try {
            List<String> lines = Files.readAllLines(SAMPLE_COMPANY.resolve(file)).stream()
                .filter(line -> !line.isBlank()).toList();
            List<String> header = Csv.fields(lines.getFirst());
            List<Map<String, String>> rows = new ArrayList<>();
            for (String line : lines.subList(1, lines.size())) {
                List<String> fields = Csv.fields(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    row.put(header.get(i), i < fields.size() ? fields.get(i) : "");
                }
                rows.add(row);
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The finance tables are append-only: no UPDATE or DELETE ever succeeded on them (the statistics count none),
     * and each carries the platform's guard trigger.
     */
    protected static void assertOnlyInserted(String... tables) {
        for (String table : tables) {
            List<Map<String, Object>> stats = query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed "
                + "FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname = ?", table);
            assertThat(stats).as(table).singleElement()
                .satisfies(row -> assertThat(((Number) row.get("changed")).longValue()).isZero());
            assertThat(query("SELECT 1 AS guarded FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() "
                + "AND c.relname = ? AND NOT t.tgisinternal", table)).as(table + " guard").isNotEmpty();
        }
    }
}

package com.jabiz.app.it.file;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.commerce.OrderPickLists;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.file.GeneratedFileProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Generated files (docs/design/14-files.md section 10, decision D31): {@code FILE_ARCHIVE} keeps the bytes as made with
 * their hash; downloading needs {@code file.generated.read} and every permission the file was kept with, checks the
 * hash and records the download; the content never reaches the process records; the store is append-only. The
 * commerce sample's pick list shows a business process keeping what it makes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class GeneratedFileIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    @Test
    void aKeptFileIsDownloadedAsMadeByWhoeverHoldsItsPermissionsAndEveryDownloadIsRecorded() {
        String marker = "ACCT-" + UUID.randomUUID();
        byte[] content = ("101 091000019 1234567890\n622 " + marker + "\n").getBytes(StandardCharsets.US_ASCII);
        Map<String, Object> kept = archive("payments 2026-10-01.txt", "text/plain", content, "it.bank.file");
        assertThat(kept.get("sha256")).isEqualTo(sha256(content));
        assertThat(kept.get("size")).isEqualTo(content.length);
        String fileId = (String) kept.get("fileId");

        EntityExchangeResult<byte[]> download = download(fileId, bearer("it-holder", "file.generated.read",
            "it.bank.file")).expectStatus().isOk().expectBody(byte[].class).returnResult();
        assertThat(download.getResponseBody()).isEqualTo(content);
        assertThat(download.getResponseHeaders().getFirst("X-Jabiz-Sha256")).isEqualTo(sha256(content));
        assertThat(download.getResponseHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(download.getResponseHeaders().getContentDisposition().getFilename())
            .isEqualTo("payments 2026-10-01.txt");
        assertThat(download.getResponseHeaders().getContentType().toString()).startsWith("text/plain");

        assertThat(query("SELECT kind, resource, entity, entity_id, fields FROM sys_reveal_record WHERE actor_id = ?",
            "it-holder")).singleElement().satisfies(row -> {
                assertThat(row).containsEntry("kind", "FILE").containsEntry("resource", "generated-file")
                    .containsEntry("entity", "PaymentRun").containsEntry("entity_id", fileId);
                assertThat(String.valueOf(row.get("fields"))).contains("content");
            });

        // The content does not reach the process records (it may hold account numbers).
        assertThat(query("SELECT input_summary::text AS s FROM op_process WHERE process_name = 'FILE_ARCHIVE'"))
            .isNotEmpty().allSatisfy(row -> assertThat(String.valueOf(row.get("s"))).doesNotContain(marker)
                .doesNotContain(Base64.getEncoder().encodeToString(content)));
    }

    @Test
    void withoutEveryPermissionTheFileDoesNotExistForTheCaller() {
        String fileId = (String) archive("vendors.csv", "text/csv", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8),
            "it.bank.file", "it.tax.file").get("fileId");
        download(fileId, bearer("it-reader", "it.bank.file", "it.tax.file")).expectStatus().isForbidden();
        download(fileId, bearer("it-reader", "file.generated.read", "it.bank.file")).expectStatus().isNotFound();
        download(UUID.randomUUID().toString(), bearer("it-reader", "file.generated.read", "it.bank.file",
            "it.tax.file")).expectStatus().isNotFound();
        download("not-a-file", bearer("it-reader", "file.generated.read")).expectStatus().isNotFound();
        download(fileId, bearer("it-reader", "file.generated.read", "it.bank.file", "it.tax.file"))
            .expectStatus().isOk();
        // Keeping a file is for processes that hold the archive permission, never for a reader.
        process(Map.of("fileName", "x.txt", "mediaType", "text/plain", "fileContent",
            Base64.getEncoder().encodeToString(new byte[] {1}), "permissions", List.of("it.bank.file")),
            bearer("it-reader", "file.generated.read")).expectStatus().isForbidden();
    }

    /**
     * Passes its input to {@code FILE_ARCHIVE} as a business process does: a sub-process's input is not bean-validated,
     * so the archive step itself must refuse what it cannot keep.
     */
    @TestConfiguration
    static class Relay {

        record RelayInput(String fileName, String mediaType, byte[] fileContent, List<String> permissions,
            String subjectId) {}

        @Bean
        ProcessDefinition<RelayInput, Map<String, Object>, ProcessContext> archiveRelayProcess() {
            @SuppressWarnings("unchecked")
            Class<Map<String, Object>> output = (Class<Map<String, Object>>) (Class<?>) Map.class;
            return ProcessDefinition.define("IT_ARCHIVE_RELAY", 1, RelayInput.class, output, ProcessContext.class,
                pb -> pb
                    .permissions("it.relay")
                    .internal()
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("in", input);
                        return ctx;
                    })
                    .outputMapper(ctx -> Map.of())
                    .step("Keep", CallProcess.<ProcessContext>of(GeneratedFileProcesses.ARCHIVE, 1, ctx -> {
                        RelayInput in = ctx.get("in", RelayInput.class);
                        return new GeneratedFileProcesses.ArchiveInput(in.fileName(), in.mediaType(), in.fileContent(),
                            in.permissions(), "PaymentRun", in.subjectId());
                    }, "kept")));
        }
    }

    @Test
    void onlyTextFilesWithPlainNamesWithinTheLimitAreKeptAlsoThroughABusinessProcess() {
        String relay = bearer("it-relay", "it.relay");
        byte[] x = "x".getBytes(StandardCharsets.UTF_8);
        int before = query("SELECT file_id FROM sys_generated_file").size();
        List<Map<String, Object>> refused = new java.util.ArrayList<>(List.of(
            relayed("page.html", "text/html", x, List.of("it.bank.file"), null),
            relayed("../etc/passwd", "text/plain", x, List.of("it.bank.file"), null),
            relayed("empty.txt", "text/plain", new byte[0], List.of("it.bank.file"), null),
            relayed("open.txt", "text/plain", x, List.of(), null),
            relayed("everyone.txt", "text/plain", x, List.of("*"), null),
            relayed("blank.txt", "text/plain", x, List.of("it.bank.file", " "), null),
            relayed("long.txt", "text/plain", x, List.of("it.bank.file"), "R".repeat(101)),
            relayed(null, "text/plain", x, List.of("it.bank.file"), null),
            relayed("none.txt", "text/plain", null, List.of("it.bank.file"), null)));
        for (Map<String, Object> input : refused) {
            assertThat(client.post().uri("/api/processes/IT_ARCHIVE_RELAY/latest")
                .header(HttpHeaders.AUTHORIZATION, relay).contentType(MediaType.APPLICATION_JSON).bodyValue(input)
                .exchange().expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody().toString())
                .as(String.valueOf(input.get("fileName")))
                .containsAnyOf("GENERATED_FILE_NOT_ALLOWED", "GENERATED_FILE_TOO_LARGE");
        }
        // Called directly, the archive's own input is validated first.
        process(input("open.txt", "text/plain", x), bearer("it-archiver", "file.generated.archive"))
            .expectStatus().isBadRequest();
        assertThat(query("SELECT file_id FROM sys_generated_file")).hasSize(before);
    }

    @Test
    void aChangedCopyIsNeverServedAndTheStoreIsAppendOnly() {
        String fileId = (String) archive("filing.xml", "application/xml",
            "<filing/>".getBytes(StandardCharsets.UTF_8), "it.tax.file").get("fileId");
        assertThatThrownBy(() -> execute("UPDATE sys_generated_file SET file_name = 'y.xml' WHERE file_id = CAST(? AS uuid)",
            fileId)).hasMessageContaining("sys_generated_file");
        assertThatThrownBy(() -> execute("DELETE FROM sys_generated_file WHERE file_id = CAST(? AS uuid)", fileId))
            .hasMessageContaining("sys_generated_file");

        bypassingTheGuard("UPDATE sys_generated_file SET content = content || '\\x00'::bytea WHERE file_id = '"
            + fileId + "'");
        download(fileId, bearer("it-auditor", "file.generated.read", "it.tax.file")).expectStatus()
            .is5xxServerError();
        assertThat(query("SELECT reveal_id FROM sys_reveal_record WHERE actor_id = ?", "it-auditor")).isEmpty();
    }

    @Test
    void aBusinessProcessKeepsWhatItMakesWithoutTheArchivePermission() {
        String code = "G" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        commit(CommerceEntities.WAREHOUSE_DATASET, Map.of("warehouseCode", code, "warehouseName", "North " + code,
            "active", true));
        commit(CommerceEntities.PRODUCT_DATASET, Map.of("sku", code + "-X", "productName", "=HYPERLINK(\"x\")",
            "unitPrice", 100, "active", true));
        runAs("STOCK_RECEIVE", Map.of("warehouseCode", code, "sku", code + "-X", "quantity", 5), admin());
        clock.advance(Duration.ofMinutes(1));
        String orderId = (String) output(runAs("ORDER_PLACE", Map.of("orderNo", code + "-1", "customerCode", code,
            "warehouseCode", code, "lines", List.of(Map.of("sku", code + "-X", "quantity", 2))), admin())).get("orderId");
        clock.advance(Duration.ofMinutes(1));

        Map<String, Object> kept = output(runAs(OrderPickLists.ARCHIVE, Map.of("orderId", orderId),
            bearer("it-clerk", "commerce.order.confirm")));
        byte[] csv = download((String) kept.get("fileId"), bearer("it-picker", "file.generated.read",
            "commerce.order.read")).expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(new String(csv, StandardCharsets.UTF_8)).isEqualTo("lineNo,sku,productName,quantity\r\n"
            + "1,\"" + code + "-X\",\"'=HYPERLINK(\"\"x\"\")\",2\r\n");
        assertThat(sha256(csv)).isEqualTo(kept.get("sha256"));
        assertThat(query("SELECT file_name, subject_entity, subject_id, created_by FROM sys_generated_file "
            + "WHERE file_id = CAST(? AS uuid)", kept.get("fileId"))).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("file_name", "pick-list " + code + "-1.csv")
                .containsEntry("subject_entity", CommerceEntities.ORDER).containsEntry("subject_id", orderId)
                .containsEntry("created_by", "it-clerk"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private Map<String, Object> archive(String fileName, String mediaType, byte[] content, String... permissions) {
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) process(input(fileName, mediaType, content, permissions),
            bearer("it-archiver", "file.generated.archive")).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody().get("output");
        return output;
    }

    private WebTestClient.ResponseSpec runAs(String name, Object input, String token) {
        return client.post().uri("/api/processes/" + name + "/latest").header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(input).exchange();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(WebTestClient.ResponseSpec response) {
        return (Map<String, Object>) response.expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody()
            .get("output");
    }

    private String admin() {
        return TestTokens.bearer(tokens, "it-admin", "*");
    }

    private void commit(String dataset, Map<String, Object> attributes) {
        client.post().uri("/api/datasets/" + dataset + "/commit").header(HttpHeaders.AUTHORIZATION, admin())
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("changes", List.of(Map.of("action", "INSERT",
                "attributes", attributes)))).exchange().expectStatus().isOk();
    }

    private static Map<String, Object> input(String fileName, String mediaType, byte[] content, String... permissions) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("fileName", fileName);
        input.put("mediaType", mediaType);
        input.put("fileContent", Base64.getEncoder().encodeToString(content));
        input.put("permissions", List.of(permissions));
        input.put("subjectEntity", "PaymentRun");
        input.put("subjectId", "RUN-1");
        return input;
    }

    private static Map<String, Object> relayed(String fileName, String mediaType, byte[] content,
        List<String> permissions, String subjectId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("fileName", fileName);
        input.put("mediaType", mediaType);
        input.put("fileContent", content == null ? null : Base64.getEncoder().encodeToString(content));
        input.put("permissions", permissions);
        input.put("subjectId", subjectId);
        return input;
    }

    private String bearer(String actor, String... permissions) {
        return TestTokens.bearer(tokens, actor, permissions);
    }

    private WebTestClient.ResponseSpec process(Object input, String token) {
        return client.post().uri("/api/processes/FILE_ARCHIVE/latest").header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(input).exchange();
    }

    private WebTestClient.ResponseSpec download(String fileId, String token) {
        return client.get().uri("/api/generated-files/{id}", fileId).header(HttpHeaders.AUTHORIZATION, token)
            .exchange();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Statements run with the append-only guard off, as a database administrator could. */
    private static void bypassingTheGuard(String... statements) {
        try (Connection connection = DB.connect(schema()); Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

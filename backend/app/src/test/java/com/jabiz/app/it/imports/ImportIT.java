package com.jabiz.app.it.imports;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.imports.ImportCodes;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Imports (docs/design/20-imports.md, ROADMAP 14e-1): a preview reads every row, runs every row's process behind a
 * savepoint and reports every problem, and leaves nothing behind; per-row and per-group imports, CSV, XLSX and XML;
 * checks of the whole file; inspecting a file for the mapping step; saved mappings; and default deny throughout.
 * Commerce and ledger tables are temporal and cannot be cleaned up, so every test works with codes of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class ImportIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
        new ParameterizedTypeReference<>() {};
    private static final String BOUNDARY = "jabiz-it-import-3c9a";

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    @Autowired
    StorageAdapterRegistry storages;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    private String admin() {
        return TestTokens.bearer(tokens, "it-admin", "*");
    }

    private String bearer(String... permissions) {
        return TestTokens.bearer(tokens, "it-importer", permissions);
    }

    private static String code() {
        return "I" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- helpers

    private WebTestClient.ResponseSpec post(String path, String authorization, Object body) {
        return client.post().uri(path).header(HttpHeaders.AUTHORIZATION, authorization)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    /** Uploads under a policy (hand-built multipart: the client's own writer draws from a blocking random source). */
    private String upload(String policy, byte[] content, String fileName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
            + fileName + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        Map<String, Object> file = client.post().uri("/api/files?policy=" + policy)
            .header(HttpHeaders.AUTHORIZATION, admin())
            .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + BOUNDARY))
            .bodyValue(out.toByteArray()).exchange()
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody();
        return String.valueOf(file.get("fileId"));
    }

    private String uploadText(String text) {
        return upload("app.import", text.getBytes(StandardCharsets.UTF_8), "data.csv");
    }

    private Map<String, Object> preview(String importId, String authorization, Map<String, Object> body) {
        return post("/api/imports/" + importId + "/preview", authorization, body)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec commit(String dataset, Map<String, Object> attributes) {
        return post("/api/datasets/" + dataset + "/commit", admin(), Map.of("changes", List.of(
            Map.of("action", "INSERT", "attributes", attributes))));
    }

    private void warehouseAndProducts(String code, String... skus) {
        commit(CommerceEntities.WAREHOUSE_DATASET, Map.of("warehouseCode", code, "warehouseName", "W " + code,
            "active", true)).expectStatus().isOk();
        for (String sku : skus) {
            commit(CommerceEntities.PRODUCT_DATASET, Map.of("sku", sku, "productName", "P " + sku, "unitPrice", 10,
                "active", true)).expectStatus().isOk();
        }
    }

    private void accounts(String prefix, String... codes) {
        for (String account : codes) {
            post("/api/processes/LEDGER_ACCOUNT_OPEN/latest", admin(), Map.of("accountCode", prefix + account,
                "accountName", "Account " + account, "accountType", account.startsWith("4") ? "REVENUE" : "ASSET"))
                .expectStatus().isOk();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> map, String key) {
        return (List<Map<String, Object>>) map.get(key);
    }

    private static List<String> issues(Map<String, Object> report) {
        return list(report, "issues").stream().map(i -> i.get("row") + ":" + i.get("code")).toList();
    }

    private static List<String> statuses(Map<String, Object> report) {
        return list(report, "results").stream().map(r -> r.get("number") + ":" + r.get("status")).toList();
    }

    private static long count(String sql, Object... params) {
        return (Long) query(sql, params).getFirst().get("n");
    }

    /** A workbook with one sheet of inline strings and numbers, written part by part. */
    private static byte[] workbook(List<List<Object>> rows) {
        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns="
            + "\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            sheet.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < rows.get(r).size(); c++) {
                Object value = rows.get(r).get(c);
                String ref = (char) ('A' + c) + String.valueOf(r + 1);
                if (value instanceof Number) {
                    sheet.append("<c r=\"").append(ref).append("\"><v>").append(value).append("</v></c>");
                } else {
                    sheet.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t>").append(value)
                        .append("</t></is></c>");
                }
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/"
            + "package/2006/content-types\"><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/"
            + "vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/></Types>");
        parts.put("xl/workbook.xml", "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/"
            + "spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/"
            + "relationships\"><sheets><sheet name=\"Prices\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas."
            + "openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"worksheet\" "
            + "Target=\"worksheets/sheet1.xml\"/></Relationships>");
        parts.put("xl/worksheets/sheet1.xml", sheet.toString());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    // ---------------------------------------------------------------- tests

    @Test
    void aPreviewRunsEveryRowReportsEveryProblemAndKeepsNothing() {
        String code = code();
        warehouseAndProducts(code, code + "-A", code + "-B");
        String fileId = uploadText("Receipt,Warehouse,SKU,Qty\n"
            + code + "-1," + code + "," + code + "-A,5\n"
            + code + "-2," + code + "," + code + "-MISSING,1\n"
            + code + "-3," + code + "," + code + "-B,abc\n"
            + code + "-1," + code + "," + code + "-A,9\n"
            + code + "-4," + code + "," + code + "-A,2\n");
        long operations = count("SELECT count(*) AS n FROM op_process");
        long stock = count("SELECT count(*) AS n FROM stock_level_version");
        SqlStatementLog.STATEMENTS.clear();

        Map<String, Object> report = preview("commerce.stock", admin(), Map.of("fileId", fileId));

        assertThat(report).containsEntry("accepted", false).containsEntry("committed", false)
            .containsEntry("records", 5).containsEntry("duplicates", 1).containsEntry("units", 3)
            .containsEntry("processed", 2);
        assertThat(issues(report)).containsExactly("3:" + ImportCodes.VALUE_INVALID, "2:COMMERCE_PRODUCT_NOT_FOUND");
        assertThat(statuses(report)).containsExactly("1:ok", "2:error", "3:error", "4:duplicate", "5:ok");
        Map<String, Object> invalid = list(report, "issues").getFirst();
        assertThat(invalid).containsEntry("field", "quantity").containsEntry("column", "Qty")
            .containsEntry("location", "line 4");
        assertThat((String) invalid.get("message")).contains("Quantity").contains("abc");
        assertThat(list(report, "results").get(2).get("values")).isEqualTo(Map.of("receipt", code + "-3",
            "warehouse", code, "sku", code + "-B", "quantity", "abc"));
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) report.get("totals");
        assertThat(new java.math.BigDecimal(String.valueOf(totals.get("quantity")))).isEqualByComparingTo("8");
        assertThat(report.get("columns")).isEqualTo(Map.of("receipt", "Receipt", "warehouse", "Warehouse", "sku",
            "SKU", "quantity", "Qty"));

        // Everything ran - rows 1 and 5 were received - and nothing stayed.
        assertThat(SqlStatementLog.STATEMENTS.stream().filter(s -> s.contains("stock_level_version")
            && s.strip().toUpperCase(Locale.ROOT).startsWith("INSERT"))).isNotEmpty();
        assertThat(count("SELECT count(*) AS n FROM op_process")).isEqualTo(operations);
        assertThat(count("SELECT count(*) AS n FROM stock_level_version")).isEqualTo(stock);
    }

    @Test
    void aFailedRowIsUndoneOnItsOwnAndLaterRowsSeeEarlierOnes() {
        StorageEngine engine = storages.getEngine("default");
        execute("CREATE TABLE it_savepoint (label varchar(10) PRIMARY KEY)");
        Mono<Void> work = engine.insert("it_savepoint", Map.of("label", "a"))
            .then(engine.inSavepoint(engine.insert("it_savepoint", Map.of("label", "b"))
                .then(engine.insert("it_savepoint", Map.of("label", "a")))).onErrorResume(e -> Mono.empty()))
            .then(engine.inSavepoint(engine.insert("it_savepoint", Map.of("label", "c"))))
            .then(engine.inSavepoint(Mono.<Void>empty()));
        asTestRequest(engine.inTransaction(work)).block();
        assertThat(query("SELECT label FROM it_savepoint ORDER BY label")).extracting(r -> r.get("label"))
            .containsExactly("a", "c");
    }

    @Test
    void groupsBecomeOneTransactionEachAndAnUnbalancedFileIsRefused() {
        String prefix = code() + "-";
        accounts(prefix, "1000", "3000");
        String balanced = uploadText("Entry,Account,Debit,Credit,Memo\n"
            + "OB-1," + prefix + "1000,\"1,500.00\",,cash\n"
            + "OB-2," + prefix + "1000,20,,\n"
            + "OB-1," + prefix + "3000,,1500.00,equity\n"
            + "OB-2," + prefix + "9999,,20,unknown account\n");
        Map<String, Object> params = Map.of("bookingTime", "2025-12-31T23:59:59Z", "description", "Opening");
        Map<String, Object> report = preview("ledger.opening", admin(), Map.of("fileId", balanced, "params",
            params));
        assertThat(report).containsEntry("units", 2).containsEntry("processed", 1);
        assertThat(issues(report)).containsExactly("2:" + PlatformErrorCodes.LEDGER_ACCOUNT_NOT_FOUND);
        assertThat(statuses(report)).containsExactly("1:ok", "2:error", "3:ok", "4:error");
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) report.get("totals");
        assertThat(new java.math.BigDecimal(String.valueOf(totals.get("debit")))).isEqualByComparingTo("1520");
        assertThat(new java.math.BigDecimal(String.valueOf(totals.get("credit")))).isEqualByComparingTo("1520");

        String unbalanced = uploadText("Entry,Account,Debit,Credit\nOB-1," + prefix + "1000,10,\nOB-1," + prefix
            + "3000,,9\n");
        Map<String, Object> refused = preview("ledger.opening", admin(), Map.of("fileId", unbalanced, "params",
            params));
        assertThat(issues(refused)).contains("0:" + PlatformErrorCodes.LEDGER_UNBALANCED);
        assertThat(list(refused, "issues").getFirst().get("message").toString()).contains("10.00").contains("9.00");
        assertThat(count("SELECT count(*) AS n FROM ledger_entry_version e JOIN ledger_account_version a"
            + " ON a.account_id = e.account_id WHERE a.account_code LIKE ?", prefix + "%")).isZero();

        // The parameters are checked before anything runs.
        post("/api/imports/ledger.opening/preview", admin(), Map.of("fileId", balanced, "params",
            Map.of("description", "x"))).expectStatus().isBadRequest();
    }

    @Test
    void readsWorkbooksAndXml() {
        String code = code();
        warehouseAndProducts(code, code + "-A");
        String xlsx = upload("app.import", workbook(List.of(List.of("SKU", "Price", "Effective"),
            List.of(code + "-A", 12, "03/01/2026"), List.of(code + "-A", "1.234", ""))), "prices.xlsx");
        Map<String, Object> prices = preview("commerce.prices", admin(), Map.of("fileId", xlsx));
        assertThat(issues(prices)).containsExactly("2:" + PlatformErrorCodes.MONETARY_SCALE);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) list(prices, "results").getFirst().get("values");
        assertThat(first).containsEntry("sku", code + "-A")
            .containsEntry("effectiveTime", "2026-03-01T05:00:00Z")
            .hasEntrySatisfying("unitPrice", price -> assertThat(new java.math.BigDecimal(String.valueOf(price)))
                .isEqualByComparingTo("12"));
        assertThat(prices).containsEntry("processed", 1);

        String xml = upload("app.import", ("<?xml version=\"1.0\"?><PriceList><Item sku=\"" + code
            + "-A\"><Price from=\"2026-04-01\">15.00</Price></Item><Item sku=\"" + code
            + "-X\"><Price>1</Price></Item></PriceList>").getBytes(StandardCharsets.UTF_8), "prices.xml");
        Map<String, Object> list = preview("commerce.price-list", admin(), Map.of("fileId", xml));
        assertThat(statuses(list)).containsExactly("1:ok", "2:error");
        assertThat(list).containsEntry("processed", 1);

        String xxe = upload("app.import", ("<?xml version=\"1.0\"?><!DOCTYPE PriceList [<!ENTITY x SYSTEM "
            + "\"file:///etc/passwd\">]><PriceList><Item sku=\"&x;\"><Price>1</Price></Item></PriceList>")
            .getBytes(StandardCharsets.UTF_8), "evil.xml");
        assertThat(issues(preview("commerce.price-list", admin(), Map.of("fileId", xxe))))
            .containsExactly("0:" + ImportCodes.FILE_INVALID);
    }

    @Test
    void inspectsAFileForTheMappingStepAndAppliesAMapping() {
        String code = code();
        warehouseAndProducts(code, code + "-A");
        String fileId = uploadText("Bank export\nRef;Store;Item;Count\n" + code + "-9;" + code + ";" + code
            + "-A;4\n");
        Map<String, Object> plain = post("/api/imports/commerce.stock/inspect", admin(), Map.of("fileId", fileId))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(plain.get("columns")).isEqualTo(List.of("Bank export"));

        Map<String, Object> options = Map.of("delimiter", ";", "skipLines", 1);
        Map<String, Object> inspected = post("/api/imports/commerce.stock/inspect", admin(), Map.of("fileId", fileId,
            "options", options)).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(inspected.get("columns")).isEqualTo(List.of("Ref", "Store", "Item", "Count"));
        assertThat(inspected).containsEntry("records", 1);
        assertThat(list(inspected, "sample").getFirst().get("cells")).isEqualTo(Map.of("Ref", code + "-9", "Store",
            code, "Item", code + "-A", "Count", "4"));
        assertThat(issues(inspected)).containsExactly("0:" + ImportCodes.COLUMN_MISSING,
            "0:" + ImportCodes.COLUMN_MISSING, "0:" + ImportCodes.COLUMN_MISSING, "0:" + ImportCodes.COLUMN_MISSING);

        Map<String, Object> mapping = Map.of("columns", Map.of("receipt", "Ref", "warehouse", "Store", "sku", "Item",
            "quantity", "Count"), "options", options);
        Map<String, Object> report = preview("commerce.stock", admin(), Map.of("fileId", fileId, "mapping",
            mapping));
        assertThat(report).containsEntry("accepted", true).containsEntry("processed", 1);

        // Saved under a name, listed, replaced, removed; only with the mapping permission.
        String importer = bearer("commerce.stock.import", "commerce.stock.receive", "app.import.read");
        client.put().uri("/api/imports/commerce.stock/mappings/{name}", "Bank " + code)
            .header(HttpHeaders.AUTHORIZATION, admin()).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("mapping", mapping)).exchange().expectStatus().isOk();
        client.put().uri("/api/imports/commerce.stock/mappings/{name}", "Bank " + code)
            .header(HttpHeaders.AUTHORIZATION, admin()).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("mapping", Map.of("constants", Map.of("warehouse", code)))).exchange()
            .expectStatus().isOk();
        List<Map<String, Object>> saved = client.get().uri("/api/imports/commerce.stock/mappings")
            .header(HttpHeaders.AUTHORIZATION, importer).exchange().expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody();
        assertThat(saved).filteredOn(m -> m.get("name").equals("Bank " + code)).singleElement()
            .satisfies(m -> assertThat(m.get("mapping").toString()).contains("constants={warehouse=" + code + "}"));
        client.put().uri("/api/imports/commerce.stock/mappings/{name}", "Other")
            .header(HttpHeaders.AUTHORIZATION, admin()).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("mapping", Map.of("columns", Map.of("ghost", "x")))).exchange()
            .expectStatus().isBadRequest();
        client.delete().uri("/api/imports/commerce.stock/mappings/{name}", "Bank " + code)
            .header(HttpHeaders.AUTHORIZATION, admin()).exchange().expectStatus().isOk();
        client.delete().uri("/api/imports/commerce.stock/mappings/{name}", "Bank " + code)
            .header(HttpHeaders.AUTHORIZATION, admin()).exchange().expectStatus().isNotFound();
        assertThat(count("SELECT count(*) AS n FROM sys_import_mapping_version WHERE mapping_name = ?",
            "Bank " + code)).isEqualTo(3);
        assertThatThrownBy(() -> execute("UPDATE sys_import_mapping_version SET mapping = '{}'"))
            .hasMessageContaining("append-only");
    }

    @Test
    void everythingIsDeniedByDefault() {
        String fileId = uploadText("Receipt,Warehouse,SKU,Qty\nR,W,S,1\n");
        String importOnly = bearer("commerce.stock.import", "app.import.read");
        String complete = bearer("commerce.stock.import", "commerce.stock.receive", "app.import.read");

        post("/api/imports/commerce.stock/preview", importOnly, Map.of("fileId", fileId))
            .expectStatus().isForbidden();
        post("/api/imports/commerce.stock/preview", bearer("commerce.stock.receive", "app.import.read"),
            Map.of("fileId", fileId)).expectStatus().isForbidden();
        post("/api/imports/commerce.stock/inspect", importOnly, Map.of("fileId", fileId))
            .expectStatus().isForbidden();
        client.put().uri("/api/imports/commerce.stock/mappings/x").header(HttpHeaders.AUTHORIZATION, complete)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("mapping", Map.of())).exchange()
            .expectStatus().isForbidden();
        // Asked to run directly, the internal mapping processes check the import's own permissions too.
        post("/api/processes/IMPORT_MAPPING_SAVE/latest", bearer("import.mapping.write"), Map.of("importId",
            "commerce.stock", "name", "x", "mapping", Map.of())).expectStatus().isForbidden();
        post("/api/processes/IMPORT_MAPPING_SAVE/latest", bearer("import.mapping.write", "commerce.stock.import",
            "commerce.stock.receive", "app.import.read"), Map.of("importId", "commerce.stock", "name", "x",
            "mapping", Map.of())).expectStatus().isForbidden();
        post("/api/processes/IMPORT_MAPPING_REMOVE/latest", bearer("import.mapping.write"), Map.of("importId",
            "commerce.stock", "name", "x")).expectStatus().isForbidden();
        post("/api/processes/IMPORT_MAPPING_SAVE/latest", admin(), Map.of("importId", "nothing.here", "name", "x",
            "mapping", Map.of())).expectStatus().isNotFound();
        post("/api/imports/nothing.here/preview", admin(), Map.of("fileId", fileId)).expectStatus().isNotFound();
        post("/api/imports/commerce.stock/preview", admin(), Map.of("fileId", UUID.randomUUID().toString()))
            .expectStatus().isNotFound();
        // Asked to run directly, the internal process checks the import's permissions itself.
        post("/api/processes/IMPORT_RUN/latest", bearer("import.run"), Map.of("importId", "commerce.stock",
            "fileId", fileId)).expectStatus().isForbidden();

        List<Map<String, Object>> catalog = client.get().uri("/api/meta/imports")
            .header(HttpHeaders.AUTHORIZATION, complete).exchange().expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody();
        assertThat(catalog).extracting(entry -> entry.get("id")).containsExactly("commerce.stock");
        assertThat(catalog.getFirst()).containsEntry("title", "Stock receipts").containsEntry("mappings", false)
            .containsEntry("externalRef", true).containsEntry("onDuplicate", "SKIP");
        assertThat(list(catalog.getFirst(), "fields")).extracting(f -> f.get("label"))
            .containsExactly("Receipt", "Warehouse", "SKU", "Quantity");
        List<Map<String, Object>> all = client.get().uri("/api/meta/imports")
            .header(HttpHeaders.AUTHORIZATION, admin()).exchange().expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody();
        assertThat(all).extracting(entry -> entry.get("id")).containsExactly("commerce.price-list",
            "commerce.prices", "commerce.stock", "ledger.opening");
        assertThat(all.getLast().get("params")).isNotNull();

        // A file uploaded for something else is not imported; import files are never public or inline.
        String pdf = upload("commerce.document", "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII), "a.pdf");
        assertThat(list(post("/api/imports/commerce.stock/preview", admin(), Map.of("fileId", pdf))
            .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody(), "violations"))
            .extracting(v -> v.get("ruleCode")).containsExactly(ImportCodes.WRONG_FILE);
        client.get().uri("/api/files/{id}/content", fileId).header(HttpHeaders.AUTHORIZATION, admin()).exchange()
            .expectStatus().isOk().expectHeader().contentType("text/plain")
            .expectHeader().valueMatches(HttpHeaders.CONTENT_DISPOSITION, "attachment.*");
    }

    // ---------------------------------------------------------------- commit (14e-2)

    private WebTestClient.ResponseSpec commitImport(String importId, String authorization, Map<String, Object> body) {
        return post("/api/imports/" + importId + "/commit", authorization, body);
    }

    private static final String STOCK_OF = "SELECT count(*) AS n FROM stock_level_version s JOIN warehouse_version w"
        + " ON w.warehouse_id = s.warehouse_id WHERE w.warehouse_code = ?";

    private static List<String> ruleCodes(Map<String, Object> problem) {
        return list(problem, "violations").stream().map(v -> String.valueOf(v.get("ruleCode"))).toList();
    }

    @Test
    void aCommitKeepsTheRowsRecordsTheImportAndAFileIsImportedOnce() {
        String code = code();
        warehouseAndProducts(code, code + "-A");
        String text = "Receipt,Warehouse,SKU,Qty\n" + code + "-1," + code + "," + code + "-A,5\n"
            + code + "-2," + code + "," + code + "-A,3\n";
        String fileId = uploadText(text);

        Map<String, Object> report = commitImport("commerce.stock", admin(), Map.of("fileId", fileId,
            "notes", "Counted twice at the dock")).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(report).containsEntry("committed", true).containsEntry("accepted", true)
            .containsEntry("processed", 2);
        String runId = String.valueOf(report.get("runId"));

        Map<String, Object> run = query("SELECT * FROM sys_import_run WHERE run_id = ?::uuid", runId).getFirst();
        assertThat(run).containsEntry("outcome", "committed").containsEntry("row_count", 2)
            .containsEntry("notes", "Counted twice at the dock").containsEntry("imported_by", "it-admin");
        assertThat(query("SELECT ref FROM sys_import_ref WHERE run_id = ?::uuid ORDER BY row_number", runId))
            .extracting(r -> r.get("ref")).containsExactly(code + "-1", code + "-2");
        // Every row's operation is a sub-operation of the import's.
        assertThat(count("SELECT count(*) AS n FROM op_process WHERE parent_seq_id = ? AND process_name = ?",
            run.get("process_seq_id"), "STOCK_RECEIVE")).isEqualTo(2);
        assertThat(count("SELECT count(*) AS n FROM sys_outbox_event WHERE event_type = 'jabiz.import.committed'"
            + " AND payload->>'runId' = ?", runId)).isEqualTo(1);
        assertThat(query("SELECT s.on_hand FROM stock_level_version s JOIN warehouse_version w"
            + " ON w.warehouse_id = s.warehouse_id WHERE w.warehouse_code = ? ORDER BY s.version_no DESC LIMIT 1", code).getFirst().get("on_hand").toString()).startsWith("8");

        // The same file again: 409, nothing run; a preview says so.
        assertThat(ruleCodes(commitImport("commerce.stock", admin(), Map.of("fileId", uploadText(text)))
            .expectStatus().isEqualTo(409).expectBody(MAP).returnResult().getResponseBody()))
            .containsExactly(ImportCodes.ALREADY_IMPORTED);
        assertThat(issues(preview("commerce.stock", admin(), Map.of("fileId", fileId))))
            .contains("0:" + ImportCodes.ALREADY_IMPORTED);

        // A new file repeating a receipt: the repeated line is left out, the new one imported.
        String next = uploadText("Receipt,Warehouse,SKU,Qty\n" + code + "-2," + code + "," + code + "-A,3\n"
            + code + "-3," + code + "," + code + "-A,1\n");
        Map<String, Object> second = commitImport("commerce.stock", admin(), Map.of("fileId", next))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(statuses(second)).containsExactly("1:duplicate", "2:ok");
        assertThat(second).containsEntry("duplicates", 1).containsEntry("processed", 1);

        assertThatThrownBy(() -> execute("UPDATE sys_import_run SET notes = 'x' WHERE run_id = ?::uuid", runId))
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("DELETE FROM sys_import_ref WHERE run_id = ?::uuid", runId))
            .hasMessageContaining("append-only");
    }

    @Test
    void aRejectedCommitKeepsNothingButItsRecord() {
        String code = code();
        warehouseAndProducts(code, code + "-A");
        String fileId = uploadText("Receipt,Warehouse,SKU,Qty\n" + code + "-1," + code + "," + code + "-A,5\n"
            + code + "-2," + code + "," + code + "-NOPE,3\n");
        long stock = count(STOCK_OF, code);

        Map<String, Object> problem = commitImport("commerce.stock", admin(), Map.of("fileId", fileId))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCodes(problem)).containsExactly(ImportCodes.REJECTED);
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) problem.get("report");
        assertThat(report).containsEntry("committed", false).containsEntry("processed", 1);
        assertThat(issues(report)).containsExactly("2:COMMERCE_PRODUCT_NOT_FOUND");
        String runId = String.valueOf(report.get("runId"));

        assertThat(count(STOCK_OF, code))
            .isEqualTo(stock);
        assertThat(query("SELECT outcome, issue_count FROM sys_import_run WHERE run_id = ?::uuid", runId).getFirst())
            .containsEntry("outcome", "rejected").containsEntry("issue_count", 1);
        assertThat(count("SELECT count(*) AS n FROM sys_import_ref WHERE import_id = 'commerce.stock' AND ref = ?",
            code + "-1")).isZero();
        assertThat(count("SELECT count(*) AS n FROM sys_outbox_event WHERE payload->>'runId' = ?", runId)).isZero();

        // Fixed, the same receipts import: a rejected attempt blocks nothing.
        commitImport("commerce.stock", admin(), Map.of("fileId", uploadText("Receipt,Warehouse,SKU,Qty\n" + code
            + "-1," + code + "," + code + "-A,5\n"))).expectStatus().isOk();
    }

    @Test
    void twoCommitsOfTheSameFileAtOnceImportItOnce() throws Exception {
        String code = code();
        warehouseAndProducts(code, code + "-A");
        StringBuilder text = new StringBuilder("Receipt,Warehouse,SKU,Qty\n");
        for (int i = 0; i < 20; i++) {
            text.append(code).append('-').append(i).append(',').append(code).append(',').append(code)
                .append("-A,1\n");
        }
        String first = uploadText(text.toString());
        String second = uploadText(text.toString());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Integer>> results = pool.invokeAll(List.of(
                () -> commitImport("commerce.stock", admin(), Map.of("fileId", first)).returnResult(String.class)
                    .getStatus().value(),
                () -> commitImport("commerce.stock", admin(), Map.of("fileId", second)).returnResult(String.class)
                    .getStatus().value()));
            List<Integer> statuses = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) AS n FROM sys_import_ref WHERE ref LIKE ?", code + "-%")).isEqualTo(20);
    }

    @Test
    void runsAreListedShownAndExportedToThoseWhoMayImport() {
        String code = code();
        warehouseAndProducts(code, code + "-A");
        String fileId = uploadText("Receipt,Warehouse,SKU,Qty\n" + code + "-1," + code + "," + code + "-A,x\n");
        Map<String, Object> problem = commitImport("commerce.stock", admin(), Map.of("fileId", fileId,
            "notes", "first try")).expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        String runId = String.valueOf(((Map<String, Object>) problem.get("report")).get("runId"));

        String importer = bearer("commerce.stock.import", "commerce.stock.receive", "app.import.read");
        List<Map<String, Object>> runs = client.get().uri("/api/imports/runs?import=commerce.stock")
            .header(HttpHeaders.AUTHORIZATION, importer).exchange().expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody();
        assertThat(runs).extracting(r -> r.get("runId")).contains(runId);
        Map<String, Object> detail = client.get().uri("/api/imports/runs/{id}", runId)
            .header(HttpHeaders.AUTHORIZATION, importer).exchange().expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) detail.get("run");
        assertThat(summary).containsEntry("outcome", "rejected").containsEntry("notes", "first try")
            .containsEntry("title", "Stock receipts");
        assertThat(list(detail, "issues").getFirst().get("message").toString()).contains("Quantity");

        byte[] csv = client.get().uri("/api/imports/runs/{id}/export?format=csv", runId)
            .header(HttpHeaders.AUTHORIZATION, importer).exchange().expectStatus().isOk().expectBody(byte[].class)
            .returnResult().getResponseBody();
        assertThat(new String(csv, StandardCharsets.UTF_8)).contains(ImportCodes.VALUE_INVALID).contains("Quantity");
        client.get().uri("/api/imports/runs/{id}/export?format=pdf", runId)
            .header(HttpHeaders.AUTHORIZATION, importer).exchange().expectStatus().isOk()
            .expectHeader().contentType("application/pdf");

        // Without the import's permissions the run does not exist.
        String other = bearer("commerce.price.import", "commerce.product.write", "app.import.read");
        client.get().uri("/api/imports/runs/{id}", runId).header(HttpHeaders.AUTHORIZATION, other).exchange()
            .expectStatus().isNotFound();
        assertThat(client.get().uri("/api/imports/runs").header(HttpHeaders.AUTHORIZATION, other).exchange()
            .expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody())
            .noneMatch(r -> r.get("runId").equals(runId));
        commitImport("commerce.stock", bearer("commerce.stock.import", "app.import.read"),
            Map.of("fileId", fileId)).expectStatus().isForbidden();
        // Notes beyond their limit are refused before anything runs.
        commitImport("commerce.stock", admin(), Map.of("fileId", fileId, "notes", "x".repeat(4001)))
            .expectStatus().isBadRequest();
    }
}

package com.jabiz.app.it.security;

import com.jabiz.app.CarrierEntityDefinitions;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Masked fields (docs/design/10-security.md section 13.1, decision D28 item 7), on the carrier's bank account: read
 * APIs, history, audit, operation records and archived reports show the masked form; holders of the permission ask
 * for one value, which is recorded; templates and exports are masked in SQL for everyone else and recorded for
 * holders; only holders write, filter and sort by the field, and nobody writes a masked form back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class MaskedFieldIT extends SecurityItSupport {

    private static final String CARRIERS = "urn:jabiz:dataset:default:Carrier";
    private static final String BANK = CarrierEntityDefinitions.BANK_ACCOUNT_PERMISSION;
    private static final String ACCOUNT = "DE44500105175407324931";
    private static final String MASKED = "****4931";
    private static final String TEMPLATE = "it.carrier_accounts";

    private String carrier(String code) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("carrierCode", code);
        attributes.put("carrierName", "Mask Lines");
        attributes.put("countryCode", "JP");
        attributes.put("creditLimit", 1000);
        attributes.put("active", true);
        attributes.put("bankAccount", ACCOUNT);
        return String.valueOf(insert(CARRIERS, attributes, null).get("id"));
    }

    private static String code() {
        return unique("M").replace("-", "").toUpperCase(Locale.ROOT);
    }

    private String as(String actor, String... permissions) {
        return TestTokens.bearer(tokens, actor, permissions);
    }

    private static List<Map<String, Object>> reveals(String actor) {
        return query("SELECT kind, resource, entity, entity_id, fields, row_count FROM sys_reveal_record "
            + "WHERE actor_id = ? ORDER BY revealed_at", actor);
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsHistoryAuditAndOperationRecordsShowTheMaskedForm() {
        String id = carrier(code());
        String reader = as(unique("reader"), "logistics.carrier.read", "audit.read", BANK);

        // Holders of the permission too: they ask for one value at a time.
        Map<String, Object> read = get("/api/datasets/" + CARRIERS + "/entities/" + id, reader)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat((Map<String, Object>) read.get("attributes")).containsEntry("bankAccount", MASKED);

        List<Map<String, Object>> history = get("/api/datasets/" + CARRIERS + "/entities/" + id + "/history",
            reader).expectStatus().isOk().expectBody(LIST).returnResult().getResponseBody();
        assertThat((Map<String, Object>) history.getFirst().get("attributes")).containsEntry("bankAccount", MASKED);

        Map<String, Object> audit = get("/api/audit/records?entityType=Carrier&entityId=" + id, reader)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        Map<String, Object> first = ((List<Map<String, Object>>) audit.get("items")).getFirst();
        assertThat((Map<String, Object>) ((Map<String, Object>) first.get("changes")).get("bankAccount"))
            .containsEntry("after", MASKED);

        // The generic entity process: its answer and its operation record.
        String other = code();
        Map<String, Object> added = post("/api/entities/Carrier", admin(), Map.of("carrierCode", other,
            "carrierName", "Mask Two", "countryCode", "JP", "creditLimit", 1, "active", true,
            "bankAccount", "GB82WEST12345698765432")).expectStatus().isCreated().expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat((Map<String, Object>) added.get("attributes")).containsEntry("bankAccount", "****5432");

        assertThat(query("SELECT process_seq_id FROM op_process WHERE input_summary::text LIKE ? OR input_summary::text LIKE ?",
            "%" + ACCOUNT + "%", "%GB82WEST12345698765432%")).isEmpty();
        assertThat(query("SELECT record_no FROM sys_audit_record WHERE changes LIKE ? OR changes LIKE ?",
            "%" + ACCOUNT + "%", "%GB82WEST12345698765432%")).isEmpty();
    }

    @Test
    void revealingAValueNeedsThePermissionAndIsRecorded() {
        String id = carrier(code());
        String without = unique("nosy");
        String holder = unique("clerk");

        post("/api/datasets/" + CARRIERS + "/reveal", as(without, "logistics.carrier.read"),
            Map.of("id", id, "field", "bankAccount")).expectStatus().isForbidden();
        post("/api/datasets/" + CARRIERS + "/reveal", as(without, BANK),
            Map.of("id", id, "field", "bankAccount")).expectStatus().isForbidden();
        assertThat(reveals(without)).isEmpty();

        post("/api/datasets/" + CARRIERS + "/reveal", as(holder, "logistics.carrier.read", BANK),
            Map.of("id", id, "field", "bankAccount")).expectStatus().isOk().expectBody(MAP)
            .value(body -> assertThat(body).containsEntry("value", ACCOUNT));
        assertThat(reveals(holder)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("kind", "VALUE").containsEntry("resource", CARRIERS).containsEntry("entity", "Carrier")
            .containsEntry("entity_id", id).containsEntry("fields", "bankAccount"));

        // Only masked fields, only existing instances.
        post("/api/datasets/" + CARRIERS + "/reveal", as(holder, "logistics.carrier.read", BANK),
            Map.of("id", id, "field", "carrierName")).expectStatus().isBadRequest();
        post("/api/datasets/" + CARRIERS + "/reveal", as(holder, "logistics.carrier.read", BANK),
            Map.of("id", java.util.UUID.randomUUID().toString(), "field", "bankAccount")).expectStatus().isNotFound();
        assertThat(reveals(holder)).hasSize(1);

        get("/api/audit/reveals?actorId=" + holder, as(unique("auditor"), "audit.read")).expectStatus().isOk()
            .expectBody(MAP).value(body -> assertThat(body).containsEntry("total", 1));
        get("/api/audit/reveals", as(unique("x"), "logistics.carrier.read")).expectStatus().isForbidden();

        assertThatThrownBy(() -> execute("UPDATE sys_reveal_record SET fields = 'x' WHERE actor_id = ?", holder))
            .hasMessageContaining("append-only table");
        assertThatThrownBy(() -> execute("DELETE FROM sys_reveal_record WHERE actor_id = ?", holder))
            .hasMessageContaining("append-only table");
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyHoldersWriteFilterAndSortAndNobodyWritesTheMaskedFormBack() {
        String code = code();
        String id = carrier(code);
        String writer = as(unique("w"), "logistics.carrier.read", "logistics.carrier.write");
        String holder = as(unique("h"), "logistics.carrier.read", "logistics.carrier.write", BANK);

        post("/api/datasets/" + CARRIERS + "/commit", writer, update(id, 1, Map.of("bankAccount", "NL91ABNA0417164300")))
            .expectStatus().isForbidden();
        // Other fields stay writable without the permission, and the masked field keeps its value.
        post("/api/datasets/" + CARRIERS + "/commit", writer, update(id, 1, Map.of("carrierName", "Renamed")))
            .expectStatus().isOk();
        Map<String, Object> problem = post("/api/datasets/" + CARRIERS + "/commit", holder,
            update(id, 2, Map.of("bankAccount", MASKED))).expectStatus().isBadRequest().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(ruleCode(problem)).isEqualTo("MASKED_VALUE");
        post("/api/datasets/" + CARRIERS + "/commit", holder, update(id, 2, Map.of("bankAccount",
            "NL91ABNA0417164300"))).expectStatus().isOk();
        assertThat(query("SELECT bank_account FROM carrier_version WHERE carrier_id = ?::uuid ORDER BY version_no",
            id)).extracting(row -> row.get("bank_account"))
            .containsExactly(ACCOUNT, ACCOUNT, "NL91ABNA0417164300");

        Map<String, Object> byAccount = Map.of("filters", List.of(Map.of("field", "bankAccount", "op", "EQ",
            "value", "NL91ABNA0417164300")));
        post("/api/datasets/" + CARRIERS + "/query", writer, byAccount).expectStatus().isBadRequest()
            .expectBody(MAP).value(body -> assertThat(ruleCode(body)).isEqualTo("FILTER_NOT_ALLOWED"));
        post("/api/datasets/" + CARRIERS + "/query", writer, Map.of("sorts", List.of(Map.of("field", "bankAccount"))))
            .expectStatus().isBadRequest()
            .expectBody(MAP).value(body -> assertThat(ruleCode(body)).isEqualTo("SORT_NOT_ALLOWED"));
        Map<String, Object> found = post("/api/datasets/" + CARRIERS + "/query", holder, byAccount)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat((List<Map<String, Object>>) found.get("items")).extracting(item -> item.get("id"))
            .containsExactly(id);
        get("/api/entities/Carrier?sort=bankAccount", writer).expectStatus().isBadRequest();
    }

    @Test
    @SuppressWarnings("unchecked")
    void templatesAndExportsAreMaskedInSqlUnlessTheCallerHoldsThePermission() throws IOException {
        String code = code();
        carrier(code);
        String reader = unique("reader");
        String holder = unique("holder");

        Map<String, Object> masked = run(as(reader, "logistics.carrier.read"), code, null);
        assertThat((List<Map<String, Object>>) masked.get("items")).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("bankAccount", MASKED));
        // The condition sees the masked form too, so it cannot be used to guess the value.
        assertThat((List<Map<String, Object>>) run(as(reader, "logistics.carrier.read"), code, ACCOUNT)
            .get("items")).isEmpty();
        assertThat(reveals(reader)).isEmpty();

        Map<String, Object> plain = run(as(holder, "logistics.carrier.read", BANK), code, ACCOUNT);
        assertThat((List<Map<String, Object>>) plain.get("items")).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("bankAccount", ACCOUNT));
        assertThat(reveals(holder)).singleElement().satisfies(row -> assertThat(row).containsEntry("kind", "QUERY")
            .containsEntry("resource", TEMPLATE).containsEntry("fields", "bankAccount")
            .containsEntry("row_count", 1L));

        String csv = client.post().uri("/api/queries/" + TEMPLATE + "/export?format=csv")
            .header(HttpHeaders.AUTHORIZATION, as(holder, "logistics.carrier.read", BANK))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("params", Map.of("code", code))).exchange()
            .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(csv).contains(ACCOUNT);
        assertThat(reveals(holder)).hasSize(2);

        // An archived report keeps the masked form whoever issues it: others read and verify it later.
        Map<String, Object> issued = post("/api/processes/REPORT_ISSUE/latest", as(holder, "report.issue",
                "logistics.carrier.read", BANK), Map.of("templateId", TEMPLATE, "params", Map.of("code", code)))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        String runId = (String) ((Map<String, Object>) issued.get("output")).get("runId");
        assertThat(query("SELECT rows FROM sys_report_run WHERE run_id = ?::uuid", runId))
            .singleElement().satisfies(row -> assertThat((String) row.get("rows")).contains(MASKED)
                .doesNotContain(ACCOUNT));

        assertThat(exported(as(reader, "data.export", "logistics.carrier.read"), code)).isEqualTo(MASKED);
        assertThat(exported(as(holder, "data.export", "logistics.carrier.read", BANK), code)).isEqualTo(ACCOUNT);
        assertThat(reveals(holder)).extracting(row -> row.get("kind")).contains("EXPORT");
        assertThat(reveals(reader)).isEmpty();
    }

    // ================= helpers =================

    private static Map<String, Object> update(String id, long version, Map<String, Object> attributes) {
        return Map.of("changes", List.of(Map.of("action", "UPDATE", "id", id, "version", version,
            "attributes", attributes)));
    }

    private Map<String, Object> run(String authorization, String code, String account) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", Map.of("code", code));
        if (account != null) {
            body.put("filters", List.of(Map.of("field", "bankAccount", "op", "EQ", "value", account)));
        }
        return post("/api/queries/" + TEMPLATE, authorization, body).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
    }

    /** The bank account of the carrier in a data export of the carriers. */
    private String exported(String authorization, String code) throws IOException {
        byte[] zip = post("/api/exports/data", authorization, Map.of("datasets", List.of(CARRIERS)))
            .expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();
        String csv = null;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (entry.getName().startsWith("data/")) {
                    csv = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertThat(csv).isNotNull();
        List<String> lines = csv.lines().toList();
        List<String> header = List.of(lines.getFirst().split(","));
        String row = lines.stream().filter(line -> line.contains(code)).findFirst().orElseThrow();
        return row.split(",")[header.indexOf("bankAccount")];
    }
}

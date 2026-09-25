package com.jabiz.app.it;

import com.jabiz.app.WaybillEntityDefinitions;
import com.jabiz.app.it.fixture.ItFixtures;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dataset API of docs/design/03-dataset.md section 3 and the metamodel features of phase 3 seen over HTTP.
 * The dev profile lets requests name their actor and tenant (X-Jabiz-* headers, 01 section 5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class DatasetApiIT extends PostgresIntegrationTest {

    private static final String TODO = "urn:jabiz:dataset:default:Todo";
    private static final String MEMBER_TODO = "urn:jabiz:dataset:member:Todo";
    private static final String WAYBILLS = "urn:jabiz:dataset:default:WaybillTracking";
    private static final String CUSTOMS = "urn:jabiz:dataset:default:CustomsDeclaration";
    private static final long PORT_CELL = 0x882f516a23ffffL;

    @Autowired
    ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void reset() {
        client = WebTestClient.bindToApplicationContext(context).build();
        execute("DELETE FROM todo");
        execute("DELETE FROM t_customs_declaration");
        execute("DELETE FROM t_legacy_waybill_2026");
        execute("DELETE FROM it_tenant");
        execute("DELETE FROM it_unique");
    }

    // ---------------------------------------------------------------- Read, query, commit

    @Test
    void commitInsertsUpdatesAndDeletesThroughTheDataset() {
        Map<String, Object> created = single(commit(TODO, "alice", insert(Map.of("title", "write docs", "done", false)))
            .expectStatus().isOk());
        String id = (String) created.get("id");
        assertThat(id).isNotBlank();

        Map<String, Object> read = read(TODO, id, "alice").expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(attributes(read)).containsEntry("title", "write docs");

        Map<String, Object> updated = single(commit(TODO, "alice",
            change("UPDATE", id, 1, Map.of("done", true))).expectStatus().isOk());
        assertThat(updated.get("version")).isEqualTo(2);
        assertThat(attributes(updated)).containsEntry("done", true);

        commit(TODO, "alice", change("DELETE", id, 2, null)).expectStatus().isOk();
        read(TODO, id, "alice").expectStatus().isNotFound();
    }

    @Test
    void queryFiltersSortsPagesAndCounts() {
        seedTodos("alice", "a1", "a2", "a3");
        seedTodos("bob", "b1");

        Map<String, Object> page = query(TODO, "alice", Map.of(
            "filters", List.of(Map.of("field", "ownerId", "op", "eq", "value", "alice")),
            "sorts", List.of(Map.of("field", "title", "asc", false)),
            "offset", 1, "limit", 1)).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();

        assertThat(page).containsEntry("total", 3).containsEntry("offset", 1).containsEntry("limit", 1);
        assertThat(titles(page)).containsExactly("a2");
    }

    /** Phase 3 acceptance: LIKE works on a Text field. */
    @Test
    void likeFiltersText() {
        seedTodos("alice", "x");
        seedTodos("alfred", "y");
        seedTodos("bob", "z");

        Map<String, Object> page = query(TODO, "admin", Map.of(
            "filters", List.of(Map.of("field", "ownerId", "op", "like", "value", "al%")),
            "sorts", List.of(Map.of("field", "title")))).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();

        assertThat(titles(page)).containsExactly("x", "y");
    }

    /** Phase 3 acceptance: ">" on a Code field is rejected. */
    @Test
    void rangeComparisonOnACodeIsRejected() {
        List<Map<String, Object>> violations = violations(query(WAYBILLS, "admin", Map.of(
            "filters", List.of(Map.of("field", "status", "op", "gt", "value", "CREATED")))), HttpStatus.BAD_REQUEST);

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v).containsEntry("field", "status").containsEntry("ruleCode", "OPERATOR_NOT_ALLOWED");
            assertThat(v.get("message")).isEqualTo("Field \"status\" cannot be compared with GT.");
        });
    }

    @Test
    void filtersAndSortsOutsideTheListViewAreRejected() {
        assertThat(violations(query(TODO, "admin", Map.of(
            "filters", List.of(Map.of("field", "recordedTime", "op", "isNotNull")))), HttpStatus.BAD_REQUEST))
            .extracting(v -> v.get("ruleCode")).containsExactly("FILTER_NOT_ALLOWED");
        assertThat(violations(query(TODO, "admin", Map.of(
            "sorts", List.of(Map.of("field", "ownerId")))), HttpStatus.BAD_REQUEST))
            .extracting(v -> v.get("ruleCode")).containsExactly("SORT_NOT_ALLOWED");
        assertThat(violations(query(TODO, "admin", Map.of(
            "filters", List.of(Map.of("field", "title", "op", "approximately", "value", "x")))), HttpStatus.BAD_REQUEST))
            .extracting(v -> v.get("ruleCode")).containsExactly("INVALID_VALUE");
    }

    @Test
    void theListViewDefaultSortApplies() {
        insertWaybill("WB-OLD", "CREATED", "2026-01-01T00:00:00Z", null);
        insertWaybill("WB-NEW", "CREATED", "2026-01-20T00:00:00Z", null);

        Map<String, Object> page = query(WAYBILLS, "admin", Map.of()).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();

        assertThat(items(page)).extracting(i -> i.get("id")).containsExactly("WB-NEW", "WB-OLD");
    }

    @Test
    void commitAcceptsTheDatasetsOwnEntityOnly() {
        // The entity type of a change is always the dataset's target, whatever the caller sends.
        Map<String, Object> created = single(client.post().uri("/api/datasets/{id}/commit", TODO)
            .header("X-Jabiz-Actor", "alice")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", Map.of("title", "t")))))
            .exchange().expectStatus().isOk());
        assertThat(created.get("entityType")).isEqualTo("Todo");

        client.post().uri("/api/datasets/{id}/query", "urn:jabiz:dataset:nope").header("X-Jabiz-Actor", "a")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of()).exchange().expectStatus().isNotFound();
    }

    // ---------------------------------------------------------------- Member scope (acceptance)

    /** Phase 3 acceptance: in the member view (scope = current actor) nobody reads or writes others' data. */
    @Test
    void memberViewShowsAndChangesOnlyTheActorsOwnEntries() {
        String bobs = (String) single(commit(MEMBER_TODO, "bob", insert(Map.of("title", "bob's"))).expectStatus().isOk())
            .get("id");
        Map<String, Object> alices = single(commit(MEMBER_TODO, "alice", insert(Map.of("title", "alice's")))
            .expectStatus().isOk());
        // The owner is filled in from the scope.
        assertThat(attributes(alices)).containsEntry("ownerId", "alice");

        // Reads: query and read by id see alice's entry only.
        Map<String, Object> page = query(MEMBER_TODO, "alice", Map.of()).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(titles(page)).containsExactly("alice's");
        assertThat(page).containsEntry("total", 1);
        read(MEMBER_TODO, bobs, "alice").expectStatus().isNotFound();

        // A filter cannot widen the scope.
        Map<String, Object> widened = query(MEMBER_TODO, "alice", Map.of(
            "filters", List.of(Map.of("field", "ownerId", "op", "eq", "value", "bob")))).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(titles(widened)).isEmpty();

        // Writes: bob's entry cannot be updated or deleted, nor can alice create an entry for bob.
        commit(MEMBER_TODO, "alice", change("UPDATE", bobs, 1, Map.of("title", "hijacked"))).expectStatus().isNotFound();
        commit(MEMBER_TODO, "alice", change("DELETE", bobs, 1, null)).expectStatus().isNotFound();
        assertThat(violations(commit(MEMBER_TODO, "alice", insert(Map.of("title", "for bob", "ownerId", "bob"))),
            HttpStatus.UNPROCESSABLE_CONTENT))
            .extracting(v -> v.get("field"), v -> v.get("ruleCode"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("ownerId", "OUT_OF_SCOPE"));

        // Nothing changed for bob.
        assertThat(query("SELECT title FROM todo WHERE owner_id = 'bob'")).extracting(r -> r.get("title"))
            .containsExactly("bob's");
    }

    /** Phase 3 acceptance: a scope value missing from the context rejects the request (reads and writes). */
    @Test
    void missingScopeValueIsForbidden() {
        String dataset = ItFixtures.TENANT_DATASET;
        List<Map<String, Object>> denied = violations(client.post().uri("/api/datasets/{id}/query", dataset)
            .header("X-Jabiz-Actor", "alice").header("Accept-Language", "ja")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of()).exchange(), HttpStatus.FORBIDDEN);
        assertThat(denied).singleElement().satisfies(v -> {
            assertThat(v).containsEntry("ruleCode", "SCOPE_UNAVAILABLE").containsEntry("field", "tenantId");
            assertThat(v.get("message")).isEqualTo("「tenantId」がないため、この画面は利用できません。");
        });
        client.get().uri("/api/datasets/{id}/entities/{e}", dataset, "X").header("X-Jabiz-Actor", "alice")
            .exchange().expectStatus().isForbidden();
        commit(dataset, "alice", change("INSERT", "T-1", 0, Map.of("tenantRowId", "T-1", "name", "n")))
            .expectStatus().isForbidden();
        assertThat(query("SELECT * FROM it_tenant")).isEmpty();

        // With a tenant the dataset works and stays within it.
        tenantCommit("t1", Map.of("tenantRowId", "T-1", "name", "one")).expectStatus().isOk();
        tenantCommit("t2", Map.of("tenantRowId", "T-2", "name", "two")).expectStatus().isOk();
        Map<String, Object> page = client.post().uri("/api/datasets/{id}/query", dataset)
            .header("X-Jabiz-Actor", "alice").header("X-Jabiz-Tenant", "t1")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of()).exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(items(page)).extracting(i -> i.get("id")).containsExactly("T-1");
    }

    // ---------------------------------------------------------------- Dictionaries, unique, guards

    @Test
    void dictionariesAreServedInTheRequestLanguage() {
        List<Map<String, Object>> ports = client.get().uri("/api/dictionaries/{urn}", "urn:jabiz:dict:customs_port")
            .header("Accept-Language", "ja").exchange().expectStatus().isOk()
            .expectBodyList(MAP).returnResult().getResponseBody();
        assertThat(ports).extracting(p -> p.get("code"), p -> p.get("label")).containsExactly(
            org.assertj.core.groups.Tuple.tuple("JPTYO", "東京港"),
            org.assertj.core.groups.Tuple.tuple("JPYOK", "横浜港"),
            org.assertj.core.groups.Tuple.tuple("JPOSA", "大阪港"));

        List<Map<String, Object>> statuses = client.get().uri("/api/dictionaries/{urn}",
                WaybillEntityDefinitions.STATUS_DICTIONARY)
            .header("Accept-Language", "zh").exchange().expectStatus().isOk()
            .expectBodyList(MAP).returnResult().getResponseBody();
        assertThat(statuses).extracting(p -> p.get("label")).containsExactly("已创建", "运输中", "已清关", "已送达");
    }

    @Test
    void codesOfDatabaseDictionariesAreValidatedOnInput() {
        insertWaybill("WB-1", "CREATED", "2026-01-01T00:00:00Z", null);

        assertThat(violations(commit(CUSTOMS, "admin", change("INSERT", "D-1", 0,
            Map.of("declarationId", "D-1", "waybillRef", "WB-1", "portCode", "XXNOP"))), HttpStatus.BAD_REQUEST))
            .singleElement().satisfies(v -> {
                assertThat(v).containsEntry("field", "portCode").containsEntry("ruleCode", "NOT_IN_DICTIONARY");
                assertThat(v.get("message")).isEqualTo("\"XXNOP\" is not a valid choice for field \"portCode\".");
            });
        commit(CUSTOMS, "admin", change("INSERT", "D-1", 0,
            Map.of("declarationId", "D-1", "waybillRef", "WB-1", "portCode", "JPTYO"))).expectStatus().isOk();
    }

    /** Review finding: a code disabled after it was stored must not block updates of other fields. */
    @Test
    void disabledCodesStillStoredDoNotBlockUnrelatedUpdates() {
        insertWaybill("WB-1", "CREATED", "2026-01-01T00:00:00Z", null);
        execute("INSERT INTO t_customs_declaration (f_decl_no, f_wb_ref_sn, f_duty_amt, f_port_code) "
            + "VALUES ('D-1', 'WB-1', 10, 'JPTEMP')");
        execute("INSERT INTO sys_dict_item (dict_urn, item_code, enabled) VALUES (?, 'JPTEMP', false)",
            "urn:jabiz:dict:customs_port");
        try {
            // The full record is sent back, as a generic form does; only the duty changes.
            commit(CUSTOMS, "admin", change("UPDATE", "D-1", 1,
                Map.of("waybillRef", "WB-1", "dutyAmount", 20, "portCode", "JPTEMP"))).expectStatus().isOk();
            // Choosing the disabled code anew is still rejected.
            execute("UPDATE t_customs_declaration SET f_port_code = 'JPTYO' WHERE f_decl_no = 'D-1'");
            assertThat(violations(commit(CUSTOMS, "admin", change("UPDATE", "D-1", 2, Map.of("portCode", "JPTEMP"))),
                HttpStatus.BAD_REQUEST)).extracting(v -> v.get("ruleCode")).containsExactly("NOT_IN_DICTIONARY");
        } finally {
            execute("DELETE FROM t_customs_declaration");
            execute("DELETE FROM sys_dict_item WHERE item_code = 'JPTEMP'");
        }
    }

    @Test
    void softDeleteFieldsAreMaintainedByDeletionsOnly() {
        commit(ItFixtures.SOFT_DATASET, "admin", change("INSERT", "S-1", 0,
            Map.of("softId", "S-1", "name", "n", "deleted", false))).expectStatus().isOk();
        assertThat(violations(commit(ItFixtures.SOFT_DATASET, "admin", change("INSERT", "S-2", 0,
            Map.of("softId", "S-2", "deleted", true))), HttpStatus.UNPROCESSABLE_CONTENT))
            .extracting(v -> v.get("field"), v -> v.get("ruleCode"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("deleted", "IMMUTABLE_FIELD"));
        assertThat(violations(commit(ItFixtures.SOFT_DATASET, "admin", change("UPDATE", "S-1", 1,
            Map.of("deleted", true))), HttpStatus.UNPROCESSABLE_CONTENT))
            .extracting(v -> v.get("ruleCode")).containsExactly("IMMUTABLE_FIELD");
        // The deletion time is system-recorded, so a supplied value is ignored like any other system field.
        commit(ItFixtures.SOFT_DATASET, "admin", change("UPDATE", "S-1", 1, Map.of("deletedAt", "2026-01-01T00:00:00Z")))
            .expectStatus().isOk();
        assertThat(query("SELECT deleted_at, is_deleted FROM it_soft WHERE f_id = 'S-1'").get(0))
            .containsEntry("deleted_at", null).containsEntry("is_deleted", false);
        execute("DELETE FROM it_soft");
    }

    /** Review finding: a duplicate key outside the declared unique constraints is a conflict, not a 500. */
    @Test
    void duplicatePrimaryKeyIsAConflict() {
        commit(ItFixtures.UNIQUE_DATASET, "admin", change("INSERT", "U-1", 0, Map.of("uniqueId", "U-1", "code", "A")))
            .expectStatus().isOk();
        commit(ItFixtures.UNIQUE_DATASET, "admin", change("INSERT", "U-1", 0, Map.of("uniqueId", "U-1", "code", "B")))
            .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void duplicateUniqueValuesAreAValidationError() {
        commit(ItFixtures.UNIQUE_DATASET, "admin", change("INSERT", "U-1", 0, Map.of("uniqueId", "U-1", "code", "A")))
            .expectStatus().isOk();

        assertThat(violations(commit(ItFixtures.UNIQUE_DATASET, "admin",
            change("INSERT", "U-2", 0, Map.of("uniqueId", "U-2", "code", "A"))), HttpStatus.BAD_REQUEST))
            .singleElement().satisfies(v -> {
                assertThat(v).containsEntry("field", "code").containsEntry("ruleCode", "UNIQUE_VIOLATION");
                assertThat(v.get("message")).isEqualTo("The value of \"code\" is already in use.");
            });
        assertThat(query("SELECT count(*) AS n FROM it_unique").get(0).get("n")).isEqualTo(1L);
    }

    @Test
    void transitionGuardOfTheGeoExtensionIsEnforced() {
        insertWaybill("WB-1", "IN_TRANSIT", "2026-01-01T00:00:00Z", null);
        insertWaybill("WB-2", "IN_TRANSIT", "2026-01-01T00:00:00Z", 1L);
        insertWaybill("WB-3", "IN_TRANSIT", "2026-01-01T00:00:00Z", 1L);

        List<Map<String, Object>> missing = violations(commit(WAYBILLS, "admin", "ja",
            change("UPDATE", "WB-1", 1, Map.of("status", "CUSTOMS_CLEARED"))), HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(missing).singleElement().satisfies(v -> {
            assertThat(v).containsEntry("ruleCode", "GEO_LOCATION_MISSING").containsEntry("field", "currentLocation");
            assertThat(v.get("message")).isEqualTo("ステータス CUSTOMS_CLEARED には項目「currentLocation」が必須です。");
        });
        assertThat(violations(commit(WAYBILLS, "admin", "en",
            change("UPDATE", "WB-2", 1, Map.of("status", "CUSTOMS_CLEARED"))), HttpStatus.UNPROCESSABLE_CONTENT))
            .extracting(v -> v.get("ruleCode")).containsExactly("GEO_LOCATION_REJECTED");

        // Moving into the customs area within the same change satisfies the guard.
        commit(WAYBILLS, "admin", change("UPDATE", "WB-3", 1,
            Map.of("status", "CUSTOMS_CLEARED", "currentLocation", "0x882f516a23ffff"))).expectStatus().isOk();
        assertThat(query("SELECT f_status_code FROM t_legacy_waybill_2026 WHERE f_wb_sn = 'WB-3'").get(0)
            .get("f_status_code")).isEqualTo("CUSTOMS_CLEARED");
    }

    // ---------------------------------------------------------------- Metamodel export

    @Test
    @SuppressWarnings("unchecked")
    void metamodelAndJsonSchemaAreExported() {
        Map<String, Object> meta = client.get().uri("/api/meta/entities/WaybillTracking").exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(meta).containsEntry("temporal", false)
            .containsEntry("dictionaries", List.of(WaybillEntityDefinitions.STATUS_DICTIONARY));
        assertThat((List<Map<String, Object>>) meta.get("listViews")).singleElement()
            .satisfies(v -> assertThat(v).containsEntry("name", "default"));
        assertThat((List<Map<String, Object>>) meta.get("fields")).filteredOn(f -> f.get("name").equals("currentLocation"))
            .singleElement().satisfies(f -> assertThat(f).containsEntry("type", "custom")
                .containsEntry("kindId", "geo.h3").containsEntry("resolution", 8));

        Map<String, Object> schema = client.get().uri("/api/meta/schema/CustomsDeclaration").exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(schema).containsEntry("$schema", "https://json-schema.org/draft/2020-12/schema")
            .containsEntry("required", List.of("declarationId", "waybillRef"));
        assertThat((Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get("waybillRef"))
            .containsEntry("x-jabiz-reference", "WaybillTracking");
        client.get().uri("/api/meta/schema/Nope").exchange().expectStatus().isNotFound();
    }

    // ---------------------------------------------------------------- Helpers

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final Class<Map<String, Object>> MAP = (Class) Map.class;

    private void seedTodos(String owner, String... titles) {
        for (String title : titles) {
            commit(MEMBER_TODO, owner, insert(Map.of("title", title))).expectStatus().isOk();
        }
    }

    private void insertWaybill(String id, String status, String shipped, Long cell) {
        execute("""
            INSERT INTO t_legacy_waybill_2026 (f_wb_sn, f_charge_amt, f_gross_wt, f_shipped_timestamp,
                f_current_h3_cell, f_status_code)
            VALUES (?, ?, ?, ?::timestamptz, ?, ?)""",
            id, BigDecimal.TEN, new BigDecimal("12.5"), shipped, cell, status);
    }

    private WebTestClient.ResponseSpec tenantCommit(String tenant, Map<String, Object> attributes) {
        return client.post().uri("/api/datasets/{id}/commit", ItFixtures.TENANT_DATASET)
            .header("X-Jabiz-Actor", "alice").header("X-Jabiz-Tenant", tenant)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("changes", List.of(change("INSERT", attributes.get("tenantRowId"), 0, attributes))))
            .exchange();
    }

    private static Map<String, Object> insert(Map<String, Object> attributes) {
        return change("INSERT", null, 0, attributes);
    }

    private static Map<String, Object> change(String action, Object id, long version, Map<String, Object> attributes) {
        Map<String, Object> change = new HashMap<>();
        change.put("action", action);
        change.put("id", id);
        change.put("version", version);
        change.put("attributes", attributes);
        return change;
    }

    private WebTestClient.ResponseSpec commit(String dataset, String actor, Map<String, Object> change) {
        return commit(dataset, actor, "en", change);
    }

    private WebTestClient.ResponseSpec commit(String dataset, String actor, String language, Map<String, Object> change) {
        return client.post().uri("/api/datasets/{id}/commit", dataset)
            .header("X-Jabiz-Actor", actor).header("Accept-Language", language)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("changes", List.of(change)))
            .exchange();
    }

    private WebTestClient.ResponseSpec query(String dataset, String actor, Map<String, Object> body) {
        return client.post().uri("/api/datasets/{id}/query", dataset)
            .header("X-Jabiz-Actor", actor).header("Accept-Language", "en")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange();
    }

    private WebTestClient.ResponseSpec read(String dataset, String id, String actor) {
        return client.get().uri("/api/datasets/{d}/entities/{id}", dataset, id).header("X-Jabiz-Actor", actor).exchange();
    }

    private static Map<String, Object> single(WebTestClient.ResponseSpec response) {
        List<Map<String, Object>> list = response.expectBodyList(MAP).returnResult().getResponseBody();
        assertThat(list).hasSize(1);
        return list.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> instance) {
        return (Map<String, Object>) instance.get("attributes");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private static List<Object> titles(Map<String, Object> page) {
        List<Object> titles = new ArrayList<>();
        items(page).forEach(item -> titles.add(attributes(item).get("title")));
        return titles;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> violations(WebTestClient.ResponseSpec response, HttpStatus status) {
        Map<String, Object> problem = response.expectStatus().isEqualTo(status.value())
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(problem).containsKey("violations");
        return (List<Map<String, Object>>) problem.get("violations");
    }
}

package com.jabiz.app.it.security;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.it.fixture.ItApprovalFixtures;
import com.jabiz.context.EntryScope;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalPermissions;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.task.TaskPermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.EntryScopeVia;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entry scopes (ROADMAP phase 17y, decision D64; docs/design/10-security.md section 13.4): the demo's warehouse entry
 * scopes each session to the warehouse its user works in; its staff see and receive that warehouse's stock only, in
 * the dataset API, the templates and STOCK_RECEIVE; a user of no warehouse does not get in, a move takes effect at the
 * next refresh, and a token of the entry without its scope is refused. Approval requests, their decisions and tasks
 * carry the scope they were made in: a scoped session sees and decides its own only, others see all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(EntryScopeIT.Staffed.class)
class EntryScopeIT extends ApprovalItSupport {

    /** Warehouses through their staff (a scope through a mapping entity): a session sees the warehouse it works in. */
    static final String STAFFED = "urn:jabiz:dataset:it:StaffedWarehouse";

    @TestConfiguration
    static class Staffed {
        @Bean
        DatasetDefinition staffedWarehouses() {
            return DatasetDefinition.define(STAFFED, d -> d
                .targetEntityType(CommerceEntities.WAREHOUSE)
                .permissions("commerce.warehouse.read", "commerce.warehouse.write")
                .scope(s -> s.withinEntryScope(CommerceEntities.WAREHOUSE_SCOPE, "warehouseId",
                    EntryScopeVia.of(CommerceEntities.WAREHOUSE_STAFF, "warehouseId", "warehouseId")))
                .storage(st -> st.driver("r2dbc-postgresql").connectionPoolRef("default")));
        }
    }

    private static final String PASSWORD = "correct horse battery";
    private static final String ROLE = "WAREHOUSE";
    private static final String SCOPE = CommerceEntities.WAREHOUSE_SCOPE;

    /** The role the warehouse entry accepts: made once per class (its schema is its own). */
    private static String roleId;

    @BeforeEach
    void role() {
        if (roleId == null) {
            roleId = createRole(ROLE, "commerce.stock.read", "commerce.stock.receive", "commerce.warehouse.read",
                "commerce.product.read");
        }
    }

    /** A bearer token of the warehouse entry carrying {@code scope} (null: none), as the platform would issue it. */
    private String scoped(String actor, String scope, String... permissions) {
        Actor issued = new Actor(actor, null, Set.of(ROLE), Set.of(permissions), null, null, "warehouse", true)
            .inScope(scope == null ? null : new EntryScope(SCOPE, scope));
        return "Bearer " + tokens.issue(issued).token();
    }

    /** A warehouse code of this test's own: capital letters and digits, at most ten. */
    private static String code(String prefix) {
        return prefix + Long.toString(System.nanoTime() % 1_000_000_000L, 36).toUpperCase();
    }

    private String warehouse(String code) {
        return String.valueOf(insert(CommerceEntities.WAREHOUSE_DATASET, Map.of("warehouseCode", code,
            "warehouseName", "Warehouse " + code, "active", true), null).get("id"));
    }

    private String product(String sku) {
        return String.valueOf(insert(CommerceEntities.PRODUCT_DATASET, Map.of("sku", sku, "productName",
            "Product " + sku, "unitPrice", 100, "active", true), null).get("id"));
    }

    private void receive(String authorization, String warehouseCode, String sku, int status) {
        post("/api/processes/STOCK_RECEIVE/latest", authorization, Map.of("warehouseCode", warehouseCode, "sku", sku,
            "quantity", 3)).expectStatus().isEqualTo(status);
    }

    /** The stock levels of {@code productId} the caller sees through the dataset API: their warehouse ids. */
    @SuppressWarnings("unchecked")
    private List<Object> stock(String authorization, String productId) {
        Map<String, Object> page = post("/api/datasets/" + CommerceEntities.STOCK_LEVEL_DATASET + "/query",
            authorization, Map.of("filters", List.of(Map.of("field", "productId", "op", "eq", "value", productId))))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) page.get("items")).stream()
            .map(row -> ((Map<String, Object>) row.get("attributes")).get("warehouseId")).toList();
    }

    /** The warehouses the caller sees {@code sku} in through the stock template. */
    @SuppressWarnings("unchecked")
    private List<Object> available(String authorization, String sku) {
        Map<String, Object> page = post("/api/queries/commerce.stock_availability", authorization,
            Map.of("params", Map.of(), "limit", 500)).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        return ((List<Map<String, Object>>) page.get("items")).stream().filter(row -> sku.equals(row.get("sku")))
            .map(row -> row.get("warehouseCode")).toList();
    }

    private Map<String, Object> signInToWarehouse(String userName) {
        return client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.USER_AGENT, "it-agent/1.0")
            .bodyValue(Map.of("userName", userName, "password", PASSWORD, "entry", "warehouse")).exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    private Map<String, Object> refresh(Map<String, Object> session, int status) {
        return post("/api/auth/refresh", null, Map.of("refreshToken", session.get("refreshToken"), "entry",
            "warehouse")).expectStatus().isEqualTo(status).expectBody(MAP).returnResult().getResponseBody();
    }

    private String staff(String userName) {
        String userId = createUser(userName, PASSWORD);
        assign(userId, roleId, null);
        return userId;
    }

    @Test
    void aWarehouseSessionWorksOnItsOwnWarehouseOnly() {
        String north = code("N");
        String south = code("S");
        String northId = warehouse(north);
        String southId = warehouse(south);
        String sku = "SKU-" + code("P");
        String productId = product(sku);
        receive(admin(), north, sku, 200);
        receive(admin(), south, sku, 200);

        String ann = unique("ann");
        String annId = staff(ann);
        Map<String, Object> row = insert(CommerceEntities.WAREHOUSE_STAFF_DATASET, Map.of("userId", annId,
            "warehouseId", northId), null);

        // Signing in at the warehouse entry: the session carries the warehouse, and sees and receives its stock only.
        Map<String, Object> session = signInToWarehouse(ann);
        String bearer = bearerOf(session);
        assertThat(tokens.verify(bearer.substring("Bearer ".length())).entryScope())
            .isEqualTo(new EntryScope(SCOPE, northId));
        assertThat(stock(bearer, productId)).containsExactly(northId);
        assertThat(available(bearer, sku)).containsExactly(north);
        receive(bearer, north, sku, 200);
        receive(bearer, south, sku, 404);
        // Headquarters (no scope) sees both.
        assertThat(stock(admin(), productId)).containsExactlyInAnyOrder(northId, southId);
        assertThat(available(admin(), sku)).containsExactlyInAnyOrder(north, south);

        // A move takes effect at the next refresh.
        post("/api/datasets/" + CommerceEntities.WAREHOUSE_STAFF_DATASET + "/commit", admin(), Map.of("changes",
            List.of(Map.of("action", "UPDATE", "id", annId, "version", row.get("version"), "attributes",
                Map.of("warehouseId", southId))))).expectStatus().isOk();
        Map<String, Object> movedSession = refresh(session, 200);
        String moved = bearerOf(movedSession);
        assertThat(stock(moved, productId)).containsExactly(southId);
        receive(moved, north, sku, 404);

        // Out of every warehouse: the next refresh ends the session.
        Map<String, Object> current = query("SELECT version FROM warehouse_staff WHERE user_id = ?", annId)
            .getFirst();
        post("/api/datasets/" + CommerceEntities.WAREHOUSE_STAFF_DATASET + "/commit", admin(), Map.of("changes",
            List.of(Map.of("action", "DELETE", "id", annId, "version", current.get("version"))))).expectStatus().isOk();
        refresh(movedSession, 401);

        // The warehouse's role counts at the warehouse entry only: the administration (every role) does not take it.
        String carl = unique("carl");
        insert(CommerceEntities.WAREHOUSE_STAFF_DATASET, Map.of("userId", staff(carl), "warehouseId", northId), null);
        client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("userName", carl, "password", PASSWORD, "entry", "admin")).exchange()
            .expectStatus().isUnauthorized();
        signInToWarehouse(carl);

        // A user of no warehouse does not get in.
        String bob = unique("bob");
        staff(bob);
        assertThat(ruleCode(client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("userName", bob, "password", PASSWORD, "entry", "warehouse")).exchange()
            .expectStatus().isForbidden().expectBody(MAP).returnResult().getResponseBody()))
            .isEqualTo("SIGN_IN_REFUSED");
    }

    @Test
    void aScopeThroughAMappingLimitsReadsAndRefusesWrites() {
        String east = code("E");
        String west = code("W");
        String eastId = warehouse(east);
        warehouse(west);
        insert(CommerceEntities.WAREHOUSE_STAFF_DATASET, Map.of("userId", createUser(unique("eve"), PASSWORD),
            "warehouseId", eastId), null);
        String bearer = scoped("it-east", eastId, "commerce.warehouse.read", "commerce.warehouse.write");
        assertThat(codesOf(bearer)).containsExactly(east);
        assertThat(codesOf(admin())).contains(east, west);
        // A session in the scope cannot write through it: whether a new row belongs to it is not in the values.
        assertThat(ruleCode(post("/api/datasets/" + STAFFED + "/commit", bearer, Map.of("changes", List.of(Map.of(
            "action", "INSERT", "attributes", Map.of("warehouseCode", code("X"), "warehouseName", "X",
                "active", true))))).expectStatus().is4xxClientError().expectBody(MAP).returnResult()
            .getResponseBody())).isEqualTo("OUT_OF_SCOPE");
    }

    /** The codes of the warehouses the caller sees through {@link #STAFFED}. */
    @SuppressWarnings("unchecked")
    private List<Object> codesOf(String authorization) {
        Map<String, Object> page = post("/api/datasets/" + STAFFED + "/query", authorization, Map.of("limit", 500))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) page.get("items")).stream()
            .map(row -> ((Map<String, Object>) row.get("attributes")).get("warehouseCode")).toList();
    }

    @Test
    void aTokenOfTheEntryWithoutItsScopeIsRefused() {
        get("/api/auth/me", scoped("it-noscope", null, "commerce.stock.read")).expectStatus().isUnauthorized();
        // Another scope's name is no scope of this entry either.
        Actor other = new Actor("it-other", null, Set.of(ROLE), Set.of("commerce.stock.read"), null, null,
            "warehouse", true).inScope(new EntryScope("region", "north"));
        get("/api/auth/me", "Bearer " + tokens.issue(other).token()).expectStatus().isUnauthorized();
        get("/api/auth/me", scoped("it-scoped", "w-1", "commerce.stock.read")).expectStatus().isOk();
        // A scope written into a token of an entry that has none is kept, and limits that session alone.
        Actor admin = new Actor("it-admin", null, Set.of(), Set.of("commerce.stock.read"), null, null, "admin", true)
            .inScope(new EntryScope(SCOPE, "w-1"));
        get("/api/auth/me", "Bearer " + tokens.issue(admin).token()).expectStatus().isOk();
    }

    @Test
    void approvalsAndTasksAreSeenAndDecidedInTheirScopeOnly() {
        String channel = unique("ch");
        rule(channel, 1, List.of(Map.of("permission", "it.approve")));
        String preparer = ItApprovalFixtures.PREPARE;
        // Prepared in the north's session: the request and its task belong to the north.
        String north = (String) pay(scoped("it-north-clerk", "north", preparer), unique("P"), channel, 5, "north")
            .get("requestId");
        String south = (String) pay(scoped("it-south-clerk", "south", preparer), unique("P"), channel, 5, "south")
            .get("requestId");
        // Prepared at headquarters: of no scope.
        String plain = (String) pay(preparer(), unique("P"), channel, 5, "plain").get("requestId");
        assertThat(query("SELECT scope_name, scope_value FROM sys_approval_request_version WHERE request_id = ?::uuid",
            north).getFirst()).containsEntry("scope_name", SCOPE).containsEntry("scope_value", "north");
        assertThat(query("SELECT scope_name FROM sys_approval_request_version WHERE request_id = ?::uuid", plain)
            .getFirst().get("scope_name")).isNull();

        String northApprover = scoped("it-north-approver", "north", ApprovalPermissions.DECIDE,
            ApprovalPermissions.READ, "it.approve", TaskPermissions.READ);
        // The requests and the tasks the north's approver sees: the north's only.
        assertThat(requests(northApprover, channel)).containsExactly(north);
        assertThat(taskSubjects(northApprover)).contains(north).doesNotContain(south, plain);
        // The others' are not found, as if they did not exist; its own is decided, and the decision is the north's.
        refused("APPROVAL_DECIDE", northApprover, decision(south), 404);
        refused("APPROVAL_DECIDE", northApprover, decision(plain), 404);
        decide(northApprover, north, "APPROVE", "ok");
        assertThat(status(north)).isEqualTo(ApprovalEntities.APPROVED);
        assertThat(query("SELECT scope_value FROM sys_approval_decision WHERE request_id = ?::uuid", north)
            .getFirst()).containsEntry("scope_value", "north");

        // Headquarters sees and decides all.
        String hq = as("it-hq-approver", ApprovalPermissions.DECIDE, ApprovalPermissions.READ, "it.approve",
            TaskPermissions.READ);
        assertThat(requests(hq, channel)).containsExactlyInAnyOrder(north, south, plain);
        decide(hq, south, "APPROVE", "ok");
        assertThat(status(south)).isEqualTo(ApprovalEntities.APPROVED);
        // A case headquarters opened is not the north's to supersede or withdraw: refused, never duplicated (the
        // platform reads a case whole, whatever the caller's scope). Headquarters supersedes the north's.
        String payment = unique("P");
        String first = (String) pay(preparer(), payment, channel, 5, "first").get("requestId");
        Map<String, Object> again = new HashMap<>(Map.of("paymentId", payment, "channel", channel, "amount", 7,
            "memo", "again"));
        assertThat(ruleCode(refused("IT_PAY", scoped("it-north-clerk", "north", preparer), again, 422)))
            .isEqualTo("APPROVAL_OTHER_SCOPE");
        assertThat(ruleCode(refused("IT_PAY_CANCEL", scoped("it-north-clerk", "north", preparer), again, 422)))
            .isEqualTo("APPROVAL_OTHER_SCOPE");
        assertThat(status(first)).isEqualTo(ApprovalEntities.PENDING);
        String northCase = unique("P");
        String northFirst = (String) pay(scoped("it-north-clerk", "north", preparer), northCase, channel, 5, "n")
            .get("requestId");
        pay(preparer(), northCase, channel, 6, "hq");
        assertThat(status(northFirst)).isEqualTo(ApprovalEntities.SUPERSEDED);

        // A task given to the north's approver personally, made where no scope was, is theirs in the north's session.
        String key = unique("K");
        Map<String, Object> open = new HashMap<>(Map.of("key", key, "item", key + "-mine"));
        open.put("user", "it-north-approver");
        open.put("permission", null);
        open.put("due", null);
        run("IT_TASK_OPEN", as("it-opener", com.jabiz.app.it.fixture.ItTaskFixtures.PERMISSION), open);
        assertThat(taskSubjects(northApprover)).contains(key + "-mine");

        // The closed tasks kept their scope.
        assertThat(query("SELECT DISTINCT scope_value FROM sys_task_version WHERE source_key = ?", "approval:" + south))
            .extracting(r -> r.get("scope_value")).containsExactly("south");
    }

    private static Map<String, Object> decision(String requestId) {
        Map<String, Object> input = new HashMap<>();
        input.put("requestId", requestId);
        input.put("decision", "APPROVE");
        input.put("reason", "ok");
        return input;
    }

    /** The approval requests of {@code channel}'s payments the caller sees through the dataset API. */
    @SuppressWarnings("unchecked")
    private List<Object> requests(String authorization, String channel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limit", 100);
        Map<String, Object> page = post("/api/datasets/" + ApprovalEntities.REQUEST_DATASET + "/query", authorization,
            body).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) page.get("items")).stream()
            .filter(row -> String.valueOf(((Map<String, Object>) row.get("attributes")).get("facts")).contains(channel))
            .map(row -> row.get("id")).toList();
    }

    /** The subjects (approval requests) of the caller's open tasks. */
    @SuppressWarnings("unchecked")
    private List<Object> taskSubjects(String authorization) {
        Map<String, Object> result = get("/api/tasks/mine", authorization).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        return ((List<Map<String, Object>>) result.get("tasks")).stream().map(task -> task.get("subjectId")).toList();
    }
}

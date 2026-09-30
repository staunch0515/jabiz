package com.jabiz.app.it.audit;

import com.jabiz.app.it.fixture.ItFixtures;
import com.jabiz.app.it.security.SecurityItSupport;
import com.jabiz.runtime.security.SecurityEntities;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audit trail (docs/design/21-audit-retention.md section 1; ROADMAP phase 14f item 1): every write of a plain or
 * temporal entity leaves who, when, why and each changed field's value before and after; secrets only as
 * {@code ***}; the records cannot be changed and need {@code audit.read}. Every test uses entities of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class AuditRecordsIT extends SecurityItSupport {

    private static final String TICKETS = "/api/datasets/" + ItFixtures.TICKET_DATASET + "/commit";

    @Test
    void plainWritesKeepTheValuesBeforeAndAfterWithTheReason() {
        String id = unique("AT");
        commit(Map.of("action", "INSERT", "attributes", Map.of("ticketId", id, "title", "first", "amount", "1500",
            "owner", "alice")), "opened by phone");
        clock.advance(Duration.ofMinutes(5));
        commit(Map.of("action", "UPDATE", "id", id, "version", 1, "attributes", Map.of("title", "second",
            "amount", "1500")), "typo");
        clock.advance(Duration.ofMinutes(5));
        commit(Map.of("action", "DELETE", "id", id, "version", 2), "duplicate");

        List<Map<String, Object>> records = items(records("entityType=ItTicket&entityId=" + id));
        assertThat(records).extracting(r -> r.get("action")).containsExactly("DELETE", "UPDATE", "INSERT");
        assertThat(records).extracting(r -> r.get("reason")).containsExactly("duplicate", "typo", "opened by phone");
        // Batches of plain entities record no operation; the audit record keeps the reason itself.
        assertThat(records).allSatisfy(r -> assertThat(r).containsEntry("actorId", "it-admin")
            .containsEntry("entityType", "ItTicket"));
        assertThat(records).extracting(r -> r.get("processSeqId")).containsOnlyNulls();

        Map<String, Object> update = records.get(1);
        // Only what changed; the amount stayed.
        assertThat(changes(update)).containsOnlyKeys("title")
            .containsEntry("title", Map.of("before", "first", "after", "second"));
        assertThat(update.get("recordedTime")).isEqualTo(START.plus(Duration.ofMinutes(5)).toString());
        assertThat(changes(records.get(2))).containsEntry("amount", pair(null, "1500"))
            .containsEntry("owner", pair(null, "alice"));
        assertThat(changes(records.get(0))).containsEntry("title", pair("second", null));

        // By changed field; the record by its number.
        assertThat(items(records("entityId=" + id + "&field=title"))).hasSize(3);
        assertThat(items(records("entityId=" + id + "&field=owner"))).extracting(r -> r.get("action"))
            .containsExactly("DELETE", "INSERT");
    }

    @Test
    void temporalWritesAreRecordedAndSecretsAreMasked() {
        String name = unique("audit-user");
        String userId = createUser(name, "Pa55word-first-9Xq");
        post("/api/processes/SEC_USER_SET_PASSWORD/latest", admin(),
            Map.of("userId", userId, "password", "Pa55word-second-3Vw")).expectStatus().isOk();

        List<Map<String, Object>> records = items(records("entityType=" + SecurityEntities.USER + "&entityId="
            + userId));
        assertThat(records).extracting(r -> r.get("action")).containsExactly("UPDATE", "INSERT");
        assertThat(records).extracting(r -> r.get("processName"))
            .containsExactly("SEC_USER_SET_PASSWORD", "SEC_USER_CREATE");
        assertThat(records).extracting(r -> r.get("versionNo")).containsExactly(2, 1);
        assertThat(changes(records.get(1))).containsEntry("userName", pair(null, name))
            .containsEntry("passwordHash", pair(null, "***"));
        // A new hash is a change; neither hash is shown.
        assertThat(changes(records.get(0))).containsOnlyKeys("passwordHash")
            .containsEntry("passwordHash", pair("***", "***"));

        List<Map<String, Object>> stored = query("SELECT changes FROM sys_audit_record WHERE entity_id = ?", userId);
        assertThat(stored).hasSize(2).allSatisfy(row -> assertThat(String.valueOf(row.get("changes")))
            .doesNotContain("$2a$", "Pa55word"));
    }

    @Test
    void recordsCannotBeChangedAndNeedTheirPermission() {
        String id = unique("AT");
        commit(Map.of("action", "INSERT", "attributes", Map.of("ticketId", id, "title", "t", "amount", "1",
            "owner", "bob")), null);
        assertThatThrownBy(() -> execute("UPDATE sys_audit_record SET reason = 'x' WHERE entity_id = ?", id))
            .hasMessageContaining("append-only table");
        assertThatThrownBy(() -> execute("DELETE FROM sys_audit_record WHERE entity_id = ?", id))
            .hasMessageContaining("append-only table");

        get("/api/audit/records", null).expectStatus().isUnauthorized();
        get("/api/audit/records", bearer("it.read")).expectStatus().isForbidden();
        get("/api/audit/records?from=yesterday&limit=0", bearer("audit.read")).expectStatus().isBadRequest()
            .expectBody(MAP).value(body -> assertThat(body.toString()).contains("field=from", "field=limit"));
    }

    // ================= helpers =================

    private void commit(Map<String, Object> change, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("changes", List.of(change));
        if (reason != null) {
            body.put("reason", reason);
        }
        post(TICKETS, admin(), body).expectStatus().isOk();
    }

    private Map<String, Object> records(String query) {
        return get("/api/audit/records?" + query, bearer("audit.read")).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
    }

    private static Map<String, Object> pair(Object before, Object after) {
        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("before", before);
        pair.put("after", after);
        return pair;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> changes(Map<String, Object> record) {
        return (Map<String, Object>) record.get("changes");
    }
}

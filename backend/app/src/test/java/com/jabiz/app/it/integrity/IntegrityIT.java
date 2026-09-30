package com.jabiz.app.it.integrity;

import com.jabiz.app.it.fixture.ItFixtures;
import com.jabiz.app.it.security.SecurityItSupport;
import com.jabiz.integrity.IntegrityKey;
import com.jabiz.integrity.SealBlock;
import com.jabiz.runtime.integrity.IntegrityChecks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The integrity seals (docs/design/21-audit-retention.md section 2; ROADMAP phase 14f-2): rows of the append-only
 * tables are sealed into an HMAC chain, and changes made past the application - rows changed or deleted with the
 * guard bypassed, blocks or seal entries changed - are found by the verification. Each test verifies from its own
 * block on, so that what another test tampered with does not count.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class IntegrityIT extends SecurityItSupport {

    @Autowired
    IntegrityKey key;

    @Autowired
    IntegrityChecks checks;

    @Test
    void anIntactChainIsSignedLinkedAndVerified() {
        String ticket = ticket();
        Map<String, Object> first = seal();
        long sealNo = ((Number) first.get("sealNo")).longValue();
        assertThat(((Number) first.get("rowCount")).intValue()).isPositive();
        assertThat(sealedKeys("sys_audit_record")).contains("[" + auditRecordNo(ticket) + "]");

        ticket();
        Map<String, Object> second = seal();
        assertThat(((Number) second.get("sealNo")).longValue()).isEqualTo(sealNo + 1);

        // Each block is the key's HMAC of its fields and links to the block before.
        Map<String, Object> row = query("SELECT * FROM sys_integrity_seal WHERE seal_no = ?", sealNo + 1).getFirst();
        SealBlock block = new SealBlock(sealNo + 1, ((java.sql.Timestamp) row.get("sealed_time")).toInstant(),
            (Integer) row.get("row_count"), (String) row.get("merkle_root"), (String) row.get("prev_hash"),
            (String) row.get("key_id"));
        assertThat(block.hash(key)).isEqualTo(row.get("seal_hash")).isEqualTo(second.get("sealHash"));
        assertThat(block.prevHash()).isEqualTo(first.get("sealHash"));
        assertThat(block.keyId()).isEqualTo(key.id());

        Map<String, Object> head = get("/api/integrity/head", bearer("integrity.read")).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(((Number) head.get("sealNo")).longValue()).isGreaterThanOrEqualTo(sealNo + 1);
        assertThat(head).containsEntry("currentKeyId", key.id());

        Map<String, Object> verified = verify(sealNo);
        assertThat(verified).containsEntry("intact", true).containsEntry("problemCount", 0);
        assertThat(((Number) verified.get("sealCount")).intValue()).isGreaterThanOrEqualTo(2);
        Map<String, Object> detail = get("/api/integrity/checks/" + verified.get("checkNo"), bearer("integrity.read"))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(detail.get("problems")).asList().isEmpty();
    }

    @Test
    void rowsChangedOrDeletedPastTheGuardAreFound() {
        String changed = ticket();
        String deleted = ticket();
        long sealNo = sealNo(seal());
        long changedNo = auditRecordNo(changed);
        long deletedNo = auditRecordNo(deleted);

        bypassingTheGuard("UPDATE sys_audit_record SET reason = 'rewritten' WHERE record_no = " + changedNo,
            "DELETE FROM sys_audit_record WHERE record_no = " + deletedNo);

        List<Map<String, Object>> problems = problems(verify(sealNo));
        assertThat(problems).extracting(p -> p.get("kind"), p -> p.get("table"), p -> p.get("key"))
            .contains(org.assertj.core.groups.Tuple.tuple("MODIFIED", "sys_audit_record", "[" + changedNo + "]"),
                org.assertj.core.groups.Tuple.tuple("MISSING", "sys_audit_record", "[" + deletedNo + "]"));
        assertThat(problems).extracting(p -> ((Number) p.get("sealNo")).longValue()).containsOnly(sealNo);
    }

    @Test
    void changedBlocksAndSealEntriesAreFound() {
        ticket();
        long altered = sealNo(seal());
        ticket();
        long resigned = sealNo(seal());
        seal();

        // A seal entry changed to match a changed row: the block's root no longer adds up.
        String entry = (String) query("SELECT row_key FROM sys_integrity_item WHERE seal_no = ? LIMIT 1", altered)
            .getFirst().get("row_key");
        bypassingTheGuard("UPDATE sys_integrity_item SET digest = '" + "0".repeat(64) + "' WHERE seal_no = "
            + altered + " AND row_key = '" + entry + "'");
        // A block whose root was changed cannot be signed again without the key.
        bypassingTheGuard("UPDATE sys_integrity_seal SET merkle_root = '" + "f".repeat(64) + "' WHERE seal_no = "
            + resigned);

        List<Map<String, Object>> problems = problems(verify(altered));
        assertThat(problems).extracting(p -> p.get("kind"), p -> ((Number) p.get("sealNo")).longValue())
            .contains(org.assertj.core.groups.Tuple.tuple("SEAL_ALTERED", altered),
                org.assertj.core.groups.Tuple.tuple("CHAIN_BROKEN", resigned),
                org.assertj.core.groups.Tuple.tuple("SEAL_ALTERED", resigned));
    }

    @Test
    void aTableWhoseGuardIsOffIsReported() {
        ticket();
        long sealNo = sealNo(seal());
        bypassingTheGuard("ALTER TABLE sys_audit_record DISABLE TRIGGER sys_audit_record_append_only");
        try {
            assertThat(problems(verify(sealNo))).extracting(p -> p.get("kind"), p -> p.get("table"))
                .contains(org.assertj.core.groups.Tuple.tuple("UNPROTECTED", "sys_audit_record"));
        } finally {
            bypassingTheGuard("ALTER TABLE sys_audit_record ENABLE TRIGGER sys_audit_record_append_only");
        }
    }

    @Test
    void aRowCommittedDuringASealIsSealedByTheNext() throws SQLException {
        seal();
        try (Connection late = DB.connect(schema())) {
            late.setAutoCommit(false);
            try (Statement statement = late.createStatement()) {
                statement.execute("INSERT INTO sys_audit_record (entity_type, entity_id, action, changes,"
                    + " changed_fields, actor_id, recorded_time) VALUES ('ItLate', 'late-1', 'INSERT', '{}', '{}',"
                    + " 'it-late', now())");
            }
            // The seal runs while the insert is not committed: it cannot see the row, and must not skip it later.
            seal();
            late.commit();
        }
        String key = "[" + query("SELECT record_no FROM sys_audit_record WHERE entity_id = 'late-1'")
            .getFirst().get("record_no") + "]";
        assertThat(sealedKeys("sys_audit_record")).doesNotContain(key);
        long next = sealNo(seal());
        assertThat(query("SELECT seal_no FROM sys_integrity_item WHERE table_name = 'sys_audit_record'"
            + " AND row_key = ?", key)).singleElement()
            .satisfies(row -> assertThat(((Number) row.get("seal_no")).longValue()).isEqualTo(next));
    }

    @Test
    void theSealsCannotBeChangedAndNeedTheirPermissions() {
        seal();
        assertThatThrownBy(() -> execute("UPDATE sys_integrity_seal SET row_count = 0"))
            .hasMessageContaining("append-only table");
        assertThatThrownBy(() -> execute("DELETE FROM sys_integrity_item"))
            .hasMessageContaining("append-only table");

        get("/api/integrity/head", null).expectStatus().isUnauthorized();
        get("/api/integrity/head", bearer("audit.read")).expectStatus().isForbidden();
        get("/api/integrity/checks", bearer("audit.read")).expectStatus().isForbidden();
        get("/api/integrity/seals?limit=0", bearer("integrity.read")).expectStatus().isBadRequest();
        get("/api/integrity/checks/999999", bearer("integrity.read")).expectStatus().isNotFound();
        post("/api/processes/INTEGRITY_SEAL/latest", bearer("integrity.read"), Map.of()).expectStatus()
            .isForbidden();
        post("/api/processes/INTEGRITY_VERIFY/latest", bearer("integrity.seal"), Map.of()).expectStatus()
            .isForbidden();
        assertThat(get("/api/integrity/seals?limit=2", bearer("integrity.read")).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody().get("items")).asList().hasSize(2);
    }

    @Test
    void anAppendOnlyTableWithoutPrimaryKeyIsReportedAtStartup() {
        execute("CREATE TABLE it_no_key (x integer)");
        query("SELECT 1 AS done FROM (SELECT jabiz_protect_append_only('it_no_key')) AS p");
        try {
            assertThat(checks.check()).extracting(p -> p.location(), p -> p.message())
                .contains(org.assertj.core.groups.Tuple.tuple("Table it_no_key",
                    "is append-only but has no primary key, so its rows cannot be sealed"));
            // Sealing goes on with the other tables.
            seal();
        } finally {
            execute("DROP TABLE it_no_key");
        }
        assertThat(checks.check()).isEmpty();
    }

    // ================= helpers =================

    /** Writes a ticket through the dataset API, which leaves audit records; returns its id. */
    private String ticket() {
        String id = unique("IT");
        post("/api/datasets/" + ItFixtures.TICKET_DATASET + "/commit", admin(), Map.of("changes", List.of(
            Map.of("action", "INSERT", "attributes", Map.of("ticketId", id, "title", "sealed", "amount", "1",
                "owner", "it"))))).expectStatus().isOk();
        return id;
    }

    private static long auditRecordNo(String entityId) {
        return ((Number) query("SELECT record_no FROM sys_audit_record WHERE entity_id = ?", entityId).getFirst()
            .get("record_no")).longValue();
    }

    private static List<String> sealedKeys(String table) {
        return query("SELECT row_key FROM sys_integrity_item WHERE table_name = ?", table).stream()
            .map(row -> (String) row.get("row_key")).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> seal() {
        Map<String, Object> input = new HashMap<>();
        input.put("scheduledTime", null);
        Map<String, Object> result = post("/api/processes/INTEGRITY_SEAL/latest", admin(), input).expectStatus()
            .isOk().expectBody(MAP).returnResult().getResponseBody();
        return (Map<String, Object>) result.get("output");
    }

    private static long sealNo(Map<String, Object> output) {
        return ((Number) output.get("sealNo")).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> verify(long fromSeal) {
        Map<String, Object> result = post("/api/processes/INTEGRITY_VERIFY/latest", admin(),
            Map.of("fromSeal", fromSeal)).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (Map<String, Object>) result.get("output");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> problems(Map<String, Object> verified) {
        assertThat(verified).containsEntry("intact", false);
        Map<String, Object> detail = get("/api/integrity/checks/" + verified.get("checkNo"),
            bearer("integrity.read")).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        return (List<Map<String, Object>>) detail.get("problems");
    }

    /** As a database administrator could: with the triggers of this session switched off. */
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

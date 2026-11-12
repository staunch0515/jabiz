package com.jabiz.app.it.process;

import com.jabiz.app.it.fixture.ItTemporalFixtures;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.app.it.security.SecurityItSupport;
import com.jabiz.context.RequestContext;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.storage.WriteBatching;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Deletions of one temporal entity that follow each other in a commit read together, before the first, what each would
 * read on its own (ROADMAP 17zz21; decision D46, note of 2026-11-12): the instances' versions, who refers to them now and
 * in scheduled versions, the legal holds. Each deletion still plans and checks row by row, so what is written, recorded
 * and refused is exactly what deleting one at a time writes, records and refuses ({@link WriteBatching#statementByStatement}
 * deletes one at a time, reading for each). The chunk read ahead is 3 instances here, so that seven take three.
 */
@SpringBootTest(properties = {"it.sql-log.enabled=true", "jabiz.storage.delete-prefetch.chunk-size=" + 3})
class TemporalDeleteBatchIT extends SecurityItSupport {

    static final int CHUNK = 3;

    private static final RequestContext WRITER = new RequestContext("it-writer", null, Locale.ENGLISH, "it-writer",
        Set.of(), Set.of("*"));

    @Autowired
    DatasetEntityManager entities;

    @Autowired
    DatasetRegistry datasets;

    // ---- what is read and written -----------------------------------------------------------------------------------

    @Test
    void deletionsReadTheirVersionsInChunksAndWriteTheirTombstonesTogether() {
        List<String> notes = notes(7, "chunked");

        SqlStatementLog.clear();
        commit(ItTemporalFixtures.NOTE_DATASET, deletions("ItNote", notes));

        List<String> statements = SqlStatementLog.transactions().getFirst();
        // Versions read ahead: one statement per chunk of three, none per deletion.
        assertThat(statements.stream().filter(sql -> sql.startsWith("SELECT") && sql.contains("FROM it_note")))
            .as(String.join("\n", statements)).hasSize((7 + CHUNK - 1) / CHUNK);
        // The tombstones go in one statement, and nothing on the table is updated or deleted.
        assertThat(statements.stream().filter(sql -> SqlStatementLog.insertedTables(sql).contains("it_note")))
            .as(String.join("\n", statements)).hasSize(1);
        assertThat(statements.stream().flatMap(sql -> SqlStatementLog.mutatedTables(sql).stream()))
            .doesNotContain("it_note");
        assertThat(current("it_note", "note_id", notes)).isZero();
    }

    @Test
    void deletingTogetherWritesAndRecordsWhatDeletingOneAtATimeDoes() {
        List<String> together = notes(7, "same");
        List<String> alone = notes(7, "same");

        commit(ItTemporalFixtures.NOTE_DATASET, deletions("ItNote", together));
        oneAtATime(ItTemporalFixtures.NOTE_DATASET, deletions("ItNote", alone));

        assertThat(trail("it_note", "note_id", "ItNote", together))
            .isEqualTo(trail("it_note", "note_id", "ItNote", alone));
    }

    // ---- what is refused ---------------------------------------------------------------------------------------------

    @Test
    void aDeletionStillReferencedIsRefusedAsWhenDeletingOneAtATime() {
        assertSameRefusal(prices -> note(prices.get(4)), Function.identity());
    }

    @Test
    void aReferenceInAScheduledVersionRefusesAsWhenDeletingOneAtATime() {
        // Price 5 will replace price 2 from next week: price 2 cannot go.
        assertSameRefusal(prices -> {
            commit(ItTemporalFixtures.PRICE_DATASET, List.of(new EntityChange(EntityAction.UPDATE,
                new EntityInstance(prices.get(5), "ItPrice", 1, null, Map.of("replacesRef", prices.get(2))),
                clock.instant().plus(Duration.ofDays(7)))));
            return null;
        }, Function.identity());
    }

    @Test
    void aSelfReferenceBlocksOnlyUntilItsHolderIsDeletedEarlierInTheCommit() {
        // Price 1 replaces price 0: deleting 1 before 0 goes through, 0 before 1 is refused, either way.
        List<String> first = prices(3, "selfA");
        replaces(first.get(1), first.get(0));
        List<String> second = prices(3, "selfB");
        replaces(second.get(1), second.get(0));
        commit(ItTemporalFixtures.PRICE_DATASET, deletions("ItPrice", List.of(first.get(1), first.get(0), first.get(2))));
        oneAtATime(ItTemporalFixtures.PRICE_DATASET,
            deletions("ItPrice", List.of(second.get(1), second.get(0), second.get(2))));
        assertThat(current("it_price", "price_id", first)).isZero();
        assertThat(current("it_price", "price_id", second)).isZero();

        assertSameRefusal(prices -> {
            replaces(prices.get(1), prices.get(0));
            return null;
        }, Function.identity());
    }

    @Test
    void aDeletionUnderLegalHoldIsRefusedAsWhenDeletingOneAtATime() {
        List<String> together = notes(5, "held");
        List<String> alone = notes(5, "held");
        hold("ItNote", together.get(3));
        hold("ItNote", alone.get(3));

        BusinessRuleViolationException batched = refused(() -> commit(ItTemporalFixtures.NOTE_DATASET,
            deletions("ItNote", together)));
        BusinessRuleViolationException single = refused(() -> oneAtATime(ItTemporalFixtures.NOTE_DATASET,
            deletions("ItNote", alone)));

        assertThat(describe(batched, together)).isEqualTo(describe(single, alone));
        assertThat(batched.violations().getFirst().ruleCode()).isEqualTo("LEGAL_HOLD");
        assertThat(current("it_note", "note_id", together)).isEqualTo(5);
    }

    // ---- what the commit did before ----------------------------------------------------------------------------------

    @Test
    void aDeletionSeesWhatItsCommitInsertedAndUpdatedBefore() {
        List<String> existing = notes(4, "mixed");
        String inserted = UUID.randomUUID().toString();
        List<EntityChange> changes = new ArrayList<>();
        changes.add(new EntityChange(EntityAction.INSERT, new EntityInstance(inserted, "ItNote", 0, null,
            Map.of("noteId", inserted, "body", "inserted")), null));
        changes.add(new EntityChange(EntityAction.UPDATE, new EntityInstance(existing.get(0), "ItNote", 1, null,
            Map.of("body", "updated")), null));
        changes.add(delete("ItNote", existing.get(1), 1));
        changes.add(delete("ItNote", inserted, 1));
        changes.add(delete("ItNote", existing.get(0), 2));
        changes.add(delete("ItNote", existing.get(2), 1));

        commit(ItTemporalFixtures.NOTE_DATASET, changes);

        assertThat(current("it_note", "note_id", List.of(inserted, existing.get(0), existing.get(1), existing.get(2))))
            .isZero();
        assertThat(current("it_note", "note_id", List.of(existing.get(3)))).isEqualTo(1);
        assertThat(query("SELECT version_no, is_deleted FROM it_note WHERE note_id = ?::uuid ORDER BY version_no",
            existing.get(0))).extracting(row -> row.get("is_deleted")).containsExactly(false, false, true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tombstonesWrittenTogetherAreSealedAndVerifiedLikeAnyRow() {
        List<String> notes = notes(7, "sealed");
        commit(ItTemporalFixtures.NOTE_DATASET, deletions("ItNote", notes));

        Map<String, Object> input = new HashMap<>();
        input.put("scheduledTime", null);
        Map<String, Object> sealed = (Map<String, Object>) post("/api/processes/INTEGRITY_SEAL/latest", admin(),
            input).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody()
            .get("output");
        long sealNo = ((Number) sealed.get("sealNo")).longValue();
        Map<String, Object> verified = (Map<String, Object>) post("/api/processes/INTEGRITY_VERIFY/latest", admin(),
            Map.of("fromSeal", sealNo)).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody()
            .get("output");
        assertThat(verified).containsEntry("intact", true);
        assertThat(query("SELECT count(*) AS n FROM sys_integrity_item WHERE table_name = 'it_note'"))
            .singleElement().satisfies(row -> assertThat(((Number) row.get("n")).longValue()).isPositive());
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    /**
     * Two sets of five prices, prepared alike by {@code prepare} (given the set, it may refer to one of them); deleting
     * each set in one commit is refused, together and one at a time, for the same price with the same violation.
     */
    private void assertSameRefusal(Function<List<String>, Object> prepare, Function<List<String>, List<String>> order) {
        List<String> together = prices(6, "refA");
        List<String> alone = prices(6, "refB");
        prepare.apply(together);
        prepare.apply(alone);

        BusinessRuleViolationException batched = refused(() -> commit(ItTemporalFixtures.PRICE_DATASET,
            deletions("ItPrice", order.apply(together))));
        BusinessRuleViolationException single = refused(() -> oneAtATime(ItTemporalFixtures.PRICE_DATASET,
            deletions("ItPrice", order.apply(alone))));

        assertThat(describe(batched, together)).isEqualTo(describe(single, alone));
        // Refused as a whole: nothing of the commit is written.
        assertThat(current("it_price", "price_id", together)).isEqualTo(together.size());
    }

    /** The violations, with the ids of {@code set} replaced by their position in it. */
    private static List<String> describe(BusinessRuleViolationException refused, List<String> set) {
        return refused.violations().stream().map(v -> {
            java.util.Map<String, Object> params = new java.util.TreeMap<>(v.params());
            // A hold is numbered when placed: the two sets have holds of their own.
            params.computeIfPresent("holdId", (key, value) -> "_");
            String text = v.ruleCode() + " " + v.field() + " " + v.message() + " " + params;
            for (int i = 0; i < set.size(); i++) {
                text = text.replace(set.get(i), "#" + i);
            }
            return text;
        }).toList();
    }

    private static BusinessRuleViolationException refused(Runnable run) {
        BusinessRuleViolationException refused = catchThrowableOfType(BusinessRuleViolationException.class, run::run);
        assertThat(refused).as("refused").isNotNull();
        return refused;
    }

    private List<String> notes(int count, String body) {
        List<EntityChange> inserts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = UUID.randomUUID().toString();
            ids.add(id);
            inserts.add(new EntityChange(EntityAction.INSERT, new EntityInstance(id, "ItNote", 0, null,
                Map.of("noteId", id, "body", body + " " + i)), null));
        }
        commit(ItTemporalFixtures.NOTE_DATASET, inserts);
        return ids;
    }

    private List<String> prices(int count, String prefix) {
        List<EntityChange> inserts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = UUID.randomUUID().toString();
            ids.add(id);
            inserts.add(new EntityChange(EntityAction.INSERT, new EntityInstance(id, "ItPrice", 0, null, Map.of(
                "priceId", id, "sku", prefix + UUID.randomUUID().toString().substring(0, 8), "region", "JP",
                "amount", 100, "status", "DRAFT")), null));
        }
        commit(ItTemporalFixtures.PRICE_DATASET, inserts);
        return ids;
    }

    /** A note referring to the price. */
    private Object note(String price) {
        String id = UUID.randomUUID().toString();
        commit(ItTemporalFixtures.NOTE_DATASET, List.of(new EntityChange(EntityAction.INSERT,
            new EntityInstance(id, "ItNote", 0, null, Map.of("noteId", id, "priceRef", price, "body", "refers")),
            null)));
        return null;
    }

    private void replaces(String price, String replaced) {
        commit(ItTemporalFixtures.PRICE_DATASET, List.of(new EntityChange(EntityAction.UPDATE,
            new EntityInstance(price, "ItPrice", 1, null, Map.of("replacesRef", replaced)), null)));
    }

    private void hold(String entity, String id) {
        post("/api/processes/LEGAL_HOLD_PLACE/latest", bearer("legal.hold.write"), Map.of("name", "hold " + id,
            "reason", "a dispute", "entityType", entity, "ids", List.of(id))).expectStatus().isOk();
    }

    /** Deletions of the instances at their current versions. */
    private static List<EntityChange> deletions(String entity, List<String> ids) {
        String table = "ItNote".equals(entity) ? "it_note" : "it_price";
        String idColumn = "ItNote".equals(entity) ? "note_id" : "price_id";
        return ids.stream().map(id -> delete(entity, id, ((Number) query("SELECT max(version_no) AS v FROM " + table
            + " WHERE " + idColumn + " = ?::uuid", id).getFirst().get("v")).longValue())).toList();
    }

    private static EntityChange delete(String entity, String id, long version) {
        return new EntityChange(EntityAction.DELETE, new EntityInstance(id, entity, version, null, Map.of()), null);
    }

    private void commit(String dataset, List<EntityChange> changes) {
        asRequest(WRITER, entities.commitBatch(datasets.findById(dataset).orElseThrow(), changes)).block();
    }

    /** The same commit, each deletion reading for itself and every row written on its own. */
    private void oneAtATime(String dataset, List<EntityChange> changes) {
        asRequest(WRITER, entities.commitBatch(datasets.findById(dataset).orElseThrow(), changes)
            .contextWrite(WriteBatching::statementByStatement)).block();
    }

    /** How many of the instances are in effect now. */
    private long current(String table, String idColumn, List<String> ids) {
        return ((Number) query("SELECT count(*) AS n FROM (SELECT DISTINCT ON (" + idColumn + ") * FROM " + table
            + " WHERE " + idColumn + " = ANY(?::uuid[]) ORDER BY " + idColumn + ", effect_start_time DESC,"
            + " version_no DESC) v WHERE NOT is_deleted", (Object) ids.toArray(String[]::new)).getFirst().get("n"))
            .longValue();
    }

    /**
     * Everything recorded of the instances, in their order, with their ids replaced by their position: the versions
     * (number, deleted, body), the operation items, the audit records and the change events.
     */
    private List<String> trail(String table, String idColumn, String entity, List<String> ids) {
        List<String> trail = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            Map<String, String> replace = new HashMap<>(Map.of(id, "#" + i));
            for (Map<String, Object> row : query("SELECT version_no, is_deleted, body FROM " + table + " WHERE "
                + idColumn + " = ?::uuid ORDER BY version_no", id)) {
                trail.add(i + " version " + row);
            }
            for (Map<String, Object> row : query("SELECT version_no, base_version_no, action, changed_fields::text AS f"
                + " FROM op_process_item WHERE entity_type = ? AND entity_id = ?::uuid ORDER BY version_no", entity, id)) {
                trail.add(i + " item " + row);
            }
            for (Map<String, Object> row : query("SELECT action, version_no, changes::text AS changes, changed_fields::text"
                + " AS fields FROM sys_audit_record WHERE entity_type = ? AND entity_id = ? ORDER BY record_no", entity,
                id)) {
                trail.add(i + " audit " + replaced(String.valueOf(row), replace));
            }
            for (Map<String, Object> row : query("SELECT event_type, payload::text AS payload FROM sys_outbox_event"
                + " WHERE payload::text LIKE ? ORDER BY event_seq", "%" + id + "%")) {
                trail.add(i + " event " + replaced(String.valueOf(row), replace));
            }
        }
        return trail;
    }

    private static String replaced(String text, Map<String, String> replace) {
        String result = text;
        for (Map.Entry<String, String> entry : replace.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        // Operation numbers and times differ between the two runs.
        return result.replaceAll("\"(processSeqId|opTime|occurredAt|recordedAt)\":[^,}]*", "\"$1\":_");
    }

    @SuppressWarnings("unused")
    private static <T> T block(Mono<T> mono) {
        return mono.block();
    }
}

package com.jabiz.app.it.process;

import com.jabiz.app.it.fixture.ItDeleteFixtures;
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
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Deletions of one temporal entity that follow each other in a commit read together, before the first, what each would
 * read on its own (ROADMAP 17zz21; decision D46, note of 2026-11-12): the instances' versions, who refers to them now and
 * in scheduled versions, the legal holds. Each deletion still plans and checks row by row, so what is written, recorded
 * and refused is exactly what deleting one at a time writes, records and refuses ({@link WriteBatching#statementByStatement}
 * deletes one at a time, reading for each). The chunk read ahead is 3 instances here, so that seven take three, and at
 * most 2 referrers are read ahead per instance of a self-reference. {@code TemporalDeleteBatchWithoutHeldWritesIT} runs
 * the same with the emergency switch off.
 */
@SpringBootTest(properties = {"it.sql-log.enabled=true", "jabiz.storage.delete-prefetch.chunk-size=" + 3,
    "jabiz.storage.delete-prefetch.max-referrers=" + 2})
class TemporalDeleteBatchIT extends SecurityItSupport {

    static final int CHUNK = 3;

    private static final RequestContext WRITER = new RequestContext("it-writer", null, Locale.ENGLISH, "it-writer",
        Set.of(), Set.of("*"));

    /** A temporal entity of the tests: its dataset, table and key column. */
    enum Kind {
        NOTE("ItNote", ItTemporalFixtures.NOTE_DATASET, "it_note", "note_id", "noteId"),
        PRICE("ItPrice", ItTemporalFixtures.PRICE_DATASET, "it_price", "price_id", "priceId"),
        TAG("ItTag", ItDeleteFixtures.TAG_DATASET, "it_tag", "tag_id", "tagId");

        final String entity;
        final String dataset;
        final String table;
        final String idColumn;
        final String key;

        Kind(String entity, String dataset, String table, String idColumn, String key) {
            this.entity = entity;
            this.dataset = dataset;
            this.table = table;
            this.idColumn = idColumn;
            this.key = key;
        }
    }

    @Autowired
    DatasetEntityManager entities;

    @Autowired
    DatasetRegistry datasets;

    @Autowired
    WriteBatching batching;

    @Autowired
    ConnectionFactory connections;

    private final JsonMapper json = JsonMapper.builder().build();

    // ---- what is read and written -----------------------------------------------------------------------------------

    @Test
    void deletionsReadTheirVersionsInChunksAndWriteTheirTombstonesTogether() {
        Assumptions.assumeTrue(batching.acrossTransaction(), "read ahead only with the writes held");
        List<String> notes = create(Kind.NOTE, 7);

        SqlStatementLog.clear();
        commit(Kind.NOTE.dataset, deletions(Kind.NOTE, notes));

        List<String> statements = SqlStatementLog.transactions().getFirst();
        // Versions read ahead: one statement per chunk of three, none per deletion.
        assertThat(statements.stream().filter(sql -> sql.startsWith("SELECT") && sql.contains("FROM it_note")))
            .as(String.join("\n", statements)).hasSize((7 + CHUNK - 1) / CHUNK);
        // The tombstones go in one statement.
        assertThat(statements.stream().filter(sql -> SqlStatementLog.insertedTables(sql).contains("it_note")))
            .as(String.join("\n", statements)).hasSize(1);
        assertThat(current(Kind.NOTE, notes)).isZero();
    }

    @Test
    void deletingTogetherWritesAndRecordsWhatDeletingOneAtATimeDoes() {
        for (Kind kind : Kind.values()) {
            List<String> together = create(kind, 7);
            List<String> alone = create(kind, 7);

            SqlStatementLog.clear();
            commit(kind.dataset, deletions(kind, together));
            assertThat(SqlStatementLog.writing(kind.table)).as(kind + ": only inserted")
                .allSatisfy(sql -> assertThat(SqlStatementLog.mutatedTables(sql)).doesNotContain(kind.table));
            oneAtATime(kind.dataset, deletions(kind, alone));

            List<String> trail = trail(kind, together);
            assertThat(trail).as(kind.name()).isEqualTo(trail(kind, alone));
            assertThat(oneOperation(kind, together)).as(kind + ": the tombstones of one commit").isTrue();
            assertThat(oneOperation(kind, alone)).isTrue();
            if (kind == Kind.TAG) {
                assertThat(trail).as("change events").anyMatch(line -> line.contains(" event "));
            }
        }
    }

    // ---- what is refused ---------------------------------------------------------------------------------------------

    @Test
    void aDeletionStillReferencedIsRefusedAsWhenDeletingOneAtATime() {
        assertSameRefusal(prices -> note(prices.get(4)));
    }

    @Test
    void aDeletionReferencedByAPlainEntityIsRefusedAsWhenDeletingOneAtATime() {
        assertSameRefusal(prices -> mark(prices.get(3)));
    }

    @Test
    void aReferenceInAScheduledVersionOfItselfRefusesAsWhenDeletingOneAtATime() {
        // Price 5 will replace price 2 from next week: price 2 cannot go.
        assertSameRefusal(prices -> commit(Kind.PRICE.dataset, List.of(new EntityChange(EntityAction.UPDATE,
            new EntityInstance(prices.get(5), "ItPrice", 1, null, Map.of("replacesRef", prices.get(2))),
            clock.instant().plus(Duration.ofDays(7))))));
    }

    @Test
    void aReferenceInAScheduledVersionOfAnotherEntityRefusesAsWhenDeletingOneAtATime() {
        // A tag will refer to price 1 from next week.
        assertSameRefusal(prices -> {
            String tag = create(Kind.TAG, 1).getFirst();
            commit(Kind.TAG.dataset, List.of(new EntityChange(EntityAction.UPDATE, new EntityInstance(tag, "ItTag", 1,
                null, Map.of("priceRef", prices.get(1))), clock.instant().plus(Duration.ofDays(7)))));
        });
    }

    @Test
    void aSelfReferenceBlocksOnlyUntilItsHolderIsDeletedEarlierInTheCommit() {
        // Price 1 replaces price 0: deleting 1 before 0 goes through, 0 before 1 is refused, either way.
        List<String> first = create(Kind.PRICE, 3);
        replaces(first.get(1), first.get(0));
        List<String> second = create(Kind.PRICE, 3);
        replaces(second.get(1), second.get(0));
        commit(Kind.PRICE.dataset, deletions(Kind.PRICE, List.of(first.get(1), first.get(0), first.get(2))));
        oneAtATime(Kind.PRICE.dataset, deletions(Kind.PRICE, List.of(second.get(1), second.get(0), second.get(2))));
        assertThat(current(Kind.PRICE, first)).isZero();
        assertThat(current(Kind.PRICE, second)).isZero();

        assertSameRefusal(prices -> replaces(prices.get(1), prices.get(0)));
    }

    @Test
    void moreSelfReferrersThanAreReadAheadAreAskedAsBefore() {
        // Three prices replace price 0, past the two read ahead: deleting them all first lets price 0 go.
        List<String> together = create(Kind.PRICE, 4);
        List<String> alone = create(Kind.PRICE, 4);
        for (List<String> set : List.of(together, alone)) {
            for (int i = 1; i <= 3; i++) {
                replaces(set.get(i), set.get(0));
            }
        }
        commit(Kind.PRICE.dataset, deletions(Kind.PRICE, List.of(together.get(1), together.get(2), together.get(3),
            together.get(0))));
        oneAtATime(Kind.PRICE.dataset, deletions(Kind.PRICE, List.of(alone.get(1), alone.get(2), alone.get(3),
            alone.get(0))));
        assertThat(current(Kind.PRICE, together)).isZero();
        assertThat(trail(Kind.PRICE, together)).isEqualTo(trail(Kind.PRICE, alone));

        // Price 0 first: refused for its three referrers, either way.
        assertSameRefusal(prices -> {
            for (int i = 1; i <= 3; i++) {
                replaces(prices.get(i), prices.get(0));
            }
        });
    }

    @Test
    void aDeletionUnderLegalHoldIsRefusedAsWhenDeletingOneAtATime() {
        List<String> together = create(Kind.NOTE, 5);
        List<String> alone = create(Kind.NOTE, 5);
        hold("ItNote", together.get(3));
        hold("ItNote", alone.get(3));

        String batched = refusal(() -> commit(Kind.NOTE.dataset, deletions(Kind.NOTE, together)), together);
        String single = refusal(() -> oneAtATime(Kind.NOTE.dataset, deletions(Kind.NOTE, alone)), alone);

        assertThat(batched).isEqualTo(single).contains("LEGAL_HOLD");
        assertThat(current(Kind.NOTE, together)).isEqualTo(5);
    }

    @Test
    void theSameInstanceTwiceInARunFailsAsWhenDeletingOneAtATime() {
        List<String> together = create(Kind.NOTE, 3);
        List<String> alone = create(Kind.NOTE, 3);
        String batched = refusal(() -> commit(Kind.NOTE.dataset, List.of(delete(Kind.NOTE, together.get(0), 1),
            delete(Kind.NOTE, together.get(1), 1), delete(Kind.NOTE, together.get(0), 1))), together);
        String single = refusal(() -> oneAtATime(Kind.NOTE.dataset, List.of(delete(Kind.NOTE, alone.get(0), 1),
            delete(Kind.NOTE, alone.get(1), 1), delete(Kind.NOTE, alone.get(0), 1))), alone);
        assertThat(batched).isEqualTo(single);
        assertThat(current(Kind.NOTE, together)).isEqualTo(3);
    }

    @Test
    void aStaleVersionInTheMiddleOfARunFailsAsWhenDeletingOneAtATime() {
        List<String> together = create(Kind.NOTE, 5);
        List<String> alone = create(Kind.NOTE, 5);
        String batched = refusal(() -> commit(Kind.NOTE.dataset, staleThird(together)), together);
        String single = refusal(() -> oneAtATime(Kind.NOTE.dataset, staleThird(alone)), alone);
        assertThat(batched).isEqualTo(single).contains("ConcurrentUpdateException");
        assertThat(current(Kind.NOTE, together)).isEqualTo(5);
    }

    @Test
    void anInstanceOutsideTheDatasetsScopeIsNotFoundAsWhenDeletingOneAtATime() {
        // Deleted through the dataset of Japanese prices, the third price, of another region, is not found.
        List<String> together = create(Kind.PRICE, 4);
        List<String> alone = create(Kind.PRICE, 4);
        for (List<String> set : List.of(together, alone)) {
            commit(Kind.PRICE.dataset, List.of(new EntityChange(EntityAction.UPDATE, new EntityInstance(set.get(2),
                "ItPrice", 1, null, Map.of("region", "US")), null)));
        }
        String batched = refusal(() -> commit(ItTemporalFixtures.PRICE_JP_DATASET, deletions(Kind.PRICE, together)),
            together);
        String single = refusal(() -> oneAtATime(ItTemporalFixtures.PRICE_JP_DATASET, deletions(Kind.PRICE, alone)),
            alone);
        assertThat(batched).isEqualTo(single).contains("NotFound");
        assertThat(current(Kind.PRICE, together)).isEqualTo(4);
    }

    @Test
    void aTargetReferredToByVeryManyRowsIsRefusedAfterReadingOneOfThem() {
        Assumptions.assumeTrue(batching.acrossTransaction(), "read ahead only with the writes held");
        int many = 50_000;
        List<String> prices = create(Kind.PRICE, 6);
        execute("INSERT INTO it_price_mark (f_id, f_price_ref) SELECT 'many-' || ? || '-' || g, ?::uuid"
            + " FROM generate_series(1, ?) g", prices.get(4), prices.get(4), many);
        execute("ANALYZE it_price_mark");

        SqlStatementLog.clear();
        String refused = refusal(() -> commit(Kind.PRICE.dataset, deletions(Kind.PRICE, prices)), prices);

        assertThat(refused).contains("STILL_REFERENCED").contains("#4");
        List<String> statements = SqlStatementLog.STATEMENTS;
        assertThat(statements).as(String.join("\n", statements)).hasSizeLessThan(30);
        // The referrers are probed per target, stopping at the first: never anything like the 50,000 rows.
        SqlStatementLog.Execution probe = SqlStatementLog.EXECUTIONS.stream()
            .filter(execution -> execution.sql().contains("it_price_mark")).findFirst().orElseThrow();
        JsonNode plan = json.readTree(SqlStatementLog.explain(connections, probe, "ANALYZE, TIMING OFF", Map.of()))
            .get(0).get("Plan");
        assertThat(mostHandled(plan)).as(plan.toString()).isLessThan(100);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tombstonesWrittenTogetherAreSealedAndVerifiedLikeAnyRow() {
        List<String> notes = create(Kind.NOTE, 7);
        commit(Kind.NOTE.dataset, deletions(Kind.NOTE, notes));

        Map<String, Object> input = new HashMap<>();
        input.put("scheduledTime", null);
        Map<String, Object> sealed = (Map<String, Object>) post("/api/processes/INTEGRITY_SEAL/latest", admin(),
            input).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("output");
        long sealNo = ((Number) sealed.get("sealNo")).longValue();
        Map<String, Object> verified = (Map<String, Object>) post("/api/processes/INTEGRITY_VERIFY/latest", admin(),
            Map.of("fromSeal", sealNo)).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody()
            .get("output");
        assertThat(verified).containsEntry("intact", true);
        assertThat(query("SELECT count(*) AS n FROM sys_integrity_item WHERE table_name = 'it_note'"))
            .singleElement().satisfies(row -> assertThat(((Number) row.get("n")).longValue()).isPositive());
    }

    // ---- what the commit did before ----------------------------------------------------------------------------------

    @Test
    void aDeletionSeesWhatItsCommitInsertedAndUpdatedBefore() {
        List<String> existing = create(Kind.NOTE, 4);
        String inserted = UUID.randomUUID().toString();
        List<EntityChange> changes = new ArrayList<>();
        changes.add(new EntityChange(EntityAction.INSERT, new EntityInstance(inserted, "ItNote", 0, null,
            Map.of("noteId", inserted, "body", "inserted")), null));
        changes.add(new EntityChange(EntityAction.UPDATE, new EntityInstance(existing.get(0), "ItNote", 1, null,
            Map.of("body", "updated")), null));
        changes.add(delete(Kind.NOTE, existing.get(1), 1));
        changes.add(delete(Kind.NOTE, inserted, 1));
        changes.add(delete(Kind.NOTE, existing.get(0), 2));
        changes.add(delete(Kind.NOTE, existing.get(2), 1));

        commit(Kind.NOTE.dataset, changes);

        assertThat(current(Kind.NOTE, List.of(inserted, existing.get(0), existing.get(1), existing.get(2)))).isZero();
        assertThat(current(Kind.NOTE, List.of(existing.get(3)))).isEqualTo(1);
        assertThat(query("SELECT version_no, is_deleted FROM it_note WHERE note_id = ?::uuid ORDER BY version_no",
            existing.get(0))).extracting(row -> row.get("is_deleted")).containsExactly(false, false, true);
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    /**
     * Two sets of six prices, prepared alike by {@code prepare}; deleting each set in one commit is refused, together
     * and one at a time, for the same price with the same violation, and nothing of the commit is written.
     */
    private void assertSameRefusal(Consumer<List<String>> prepare) {
        List<String> together = create(Kind.PRICE, 6);
        List<String> alone = create(Kind.PRICE, 6);
        prepare.accept(together);
        prepare.accept(alone);

        String batched = refusal(() -> commit(Kind.PRICE.dataset, deletions(Kind.PRICE, together)), together);
        String single = refusal(() -> oneAtATime(Kind.PRICE.dataset, deletions(Kind.PRICE, alone)), alone);

        assertThat(batched).isEqualTo(single);
        assertThat(current(Kind.PRICE, together)).isEqualTo(together.size());
    }

    /** What {@code run} fails with: its class and message, and its violations, the ids of {@code set} by position. */
    private static String refusal(Runnable run, List<String> set) {
        Throwable thrown = catchThrowable(run::run);
        assertThat(thrown).as("refused").isNotNull();
        StringBuilder text = new StringBuilder(thrown.getClass().getSimpleName()).append(": ").append(thrown.getMessage());
        if (thrown instanceof BusinessRuleViolationException refused) {
            refused.violations().forEach(v -> {
                Map<String, Object> params = new TreeMap<>(v.params());
                // A hold is numbered when placed: the two sets have holds of their own.
                params.computeIfPresent("holdId", (key, value) -> "_");
                text.append(" | ").append(v.ruleCode()).append(' ').append(v.field()).append(' ').append(v.message())
                    .append(' ').append(params);
            });
        }
        String result = text.toString();
        for (int i = 0; i < set.size(); i++) {
            result = result.replace(set.get(i), "#" + i);
        }
        return result;
    }

    private List<String> create(Kind kind, int count) {
        List<EntityChange> inserts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = UUID.randomUUID().toString();
            ids.add(id);
            Map<String, Object> values = new HashMap<>(switch (kind) {
                case NOTE -> Map.of("body", "note " + i);
                case PRICE -> Map.of("sku", "P" + UUID.randomUUID().toString().substring(0, 12), "region", "JP",
                    "amount", 100 + i, "status", "DRAFT");
                case TAG -> Map.of("label", "tag " + i);
            });
            values.put(kind.key, id);
            inserts.add(new EntityChange(EntityAction.INSERT, new EntityInstance(id, kind.entity, 0, null, values), null));
        }
        commit(kind.dataset, inserts);
        return ids;
    }

    /** A note referring to the price. */
    private void note(String price) {
        String id = UUID.randomUUID().toString();
        commit(Kind.NOTE.dataset, List.of(new EntityChange(EntityAction.INSERT,
            new EntityInstance(id, "ItNote", 0, null, Map.of("noteId", id, "priceRef", price, "body", "refers")),
            null)));
    }

    /** A plain mark referring to the price. */
    private void mark(String price) {
        String id = "mark-" + UUID.randomUUID();
        commit(ItDeleteFixtures.MARK_DATASET, List.of(new EntityChange(EntityAction.INSERT,
            new EntityInstance(id, "ItPriceMark", 0, null, Map.of("markId", id, "priceRef", price)), null)));
    }

    private void replaces(String price, String replaced) {
        long version = version(Kind.PRICE, price);
        commit(Kind.PRICE.dataset, List.of(new EntityChange(EntityAction.UPDATE,
            new EntityInstance(price, "ItPrice", version, null, Map.of("replacesRef", replaced)), null)));
    }

    private void hold(String entity, String id) {
        post("/api/processes/LEGAL_HOLD_PLACE/latest", bearer("legal.hold.write"), Map.of("name", "hold " + id,
            "reason", "a dispute", "entityType", entity, "ids", List.of(id))).expectStatus().isOk();
    }

    /** Deletions of the instances at their current versions. */
    private static List<EntityChange> deletions(Kind kind, List<String> ids) {
        return ids.stream().map(id -> delete(kind, id, version(kind, id))).toList();
    }

    /** The five deletions, the third at a version it no longer has. */
    private static List<EntityChange> staleThird(List<String> ids) {
        List<EntityChange> changes = new ArrayList<>(deletions(Kind.NOTE, ids));
        changes.set(2, delete(Kind.NOTE, ids.get(2), 2));
        return changes;
    }

    private static long version(Kind kind, String id) {
        return ((Number) query("SELECT max(version_no) AS v FROM " + kind.table + " WHERE " + kind.idColumn
            + " = ?::uuid", id).getFirst().get("v")).longValue();
    }

    private static EntityChange delete(Kind kind, String id, long version) {
        return new EntityChange(EntityAction.DELETE, new EntityInstance(id, kind.entity, version, null, Map.of()), null);
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
    private static long current(Kind kind, List<String> ids) {
        return ((Number) query("SELECT count(*) AS n FROM (SELECT DISTINCT ON (" + kind.idColumn + ") * FROM "
            + kind.table + " WHERE " + kind.idColumn + " = ANY(?::uuid[]) ORDER BY " + kind.idColumn
            + ", effect_start_time DESC, version_no DESC) v WHERE NOT is_deleted", (Object) ids.toArray(String[]::new))
            .getFirst().get("n")).longValue();
    }

    /** Whether the tombstones of the instances were written by one operation, the one their items name. */
    private static boolean oneOperation(Kind kind, List<String> ids) {
        List<Map<String, Object>> operations = query("SELECT DISTINCT t.process_seq_id AS p, i.process_seq_id AS q FROM "
            + kind.table + " t LEFT JOIN op_process_item i ON i.entity_type = ? AND i.entity_id = t." + kind.idColumn
            + " AND i.version_no = t.version_no WHERE t.is_deleted AND t." + kind.idColumn + " = ANY(?::uuid[])",
            kind.entity, ids.toArray(String[]::new));
        return operations.size() == 1 && operations.getFirst().get("p").equals(operations.getFirst().get("q"));
    }

    /**
     * Everything recorded of the instances, in their order, with their ids replaced by their position: the versions
     * (number, deleted, state, effective time), the operation items, the audit records and the change events.
     */
    private static List<String> trail(Kind kind, List<String> ids) {
        List<String> trail = new ArrayList<>();
        // Every id of the set by its position (a price may refer to another), and SKUs, unique, likewise.
        Map<String, String> replace = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            replace.put(ids.get(i), "#" + i);
            if (kind == Kind.PRICE) {
                replace.put((String) query("SELECT sku FROM it_price WHERE price_id = ?::uuid LIMIT 1", ids.get(i))
                    .getFirst().get("sku"), "sku" + i);
            }
        }
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            for (Map<String, Object> row : query("SELECT * FROM " + kind.table + " WHERE " + kind.idColumn
                + " = ?::uuid ORDER BY version_no", id)) {
                Map<String, Object> version = new TreeMap<>(row);
                version.remove("row_id");
                version.remove("process_seq_id");
                version.remove("created_time");
                version.remove("sku");
                trail.add(i + " version " + replaced(String.valueOf(version), replace));
            }
            for (Map<String, Object> row : query("SELECT version_no, base_version_no, action, effect_start_time,"
                + " changed_fields::text AS f FROM op_process_item WHERE entity_type = ? AND entity_id = ?::uuid"
                + " ORDER BY version_no", kind.entity, id)) {
                trail.add(i + " item " + row);
            }
            for (Map<String, Object> row : query("SELECT action, version_no, effect_start_time, changes::text AS changes,"
                + " changed_fields::text AS fields FROM sys_audit_record WHERE entity_type = ? AND entity_id = ?"
                + " ORDER BY record_no", kind.entity, id)) {
                trail.add(i + " audit " + replaced(String.valueOf(row), replace));
            }
            for (Map<String, Object> row : query("SELECT event_type, payload::text AS payload FROM sys_outbox_event"
                + " WHERE entity_type = ? AND entity_id = ? ORDER BY event_seq", kind.entity, id)) {
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
        // Operation numbers, event ids and SKUs differ between the two sets; times do not (the clock stands still).
        return result.replaceAll("\"(processSeqId|eventId|sku)\":(\"[^\"]*\"|[^,}]*)", "\"$1\":_")
            .replaceAll("sku=[^,}\\]]*", "sku=_");
    }

    /** The most rows one step of the plan handled: returned over all its loops, and removed by its filters. */
    private static long mostHandled(JsonNode node) {
        long handled = node.path("Actual Rows").asLong(0) * node.path("Actual Loops").asLong(1)
            + node.path("Rows Removed by Join Filter").asLong(0) + node.path("Rows Removed by Filter").asLong(0)
            + node.path("Rows Removed by Index Recheck").asLong(0);
        long most = handled;
        for (JsonNode child : node.path("Plans")) {
            most = Math.max(most, mostHandled(child));
        }
        return most;
    }
}

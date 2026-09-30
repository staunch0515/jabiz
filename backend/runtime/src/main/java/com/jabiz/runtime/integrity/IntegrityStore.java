package com.jabiz.runtime.integrity;

import com.jabiz.integrity.IntegrityProblem;
import com.jabiz.integrity.SealBlock;
import com.jabiz.integrity.SealChain;
import com.jabiz.integrity.SealedRow;
import com.jabiz.query.BoundValue;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The SQL of the integrity seals (docs/design/21-audit-retention.md section 2). A row's digest is the SHA-256 of
 * PostgreSQL's text of {@code to_jsonb(row)} - every column, keys in jsonb order, numbers with their scale - read with
 * the time zone, interval style and float digits pinned ({@link #pin}), so the same content always gives the same
 * digest. Table and column names come from the catalog and are checked ({@link SqlIdentifiers}) and quoted; every
 * value is bound.
 */
@Component
public class IntegrityStore {

    /** The seal tables themselves: covered by the chain, never sealed as rows (that would never end). */
    static final Set<String> OWN_TABLES = Set.of("sys_integrity_seal", "sys_integrity_item", "sys_integrity_check");

    /** How many sealed rows one insert statement takes. */
    private static final int INSERT_CHUNK = 5_000;

    /**
     * A table whose rows the database refuses to change (decision D5).
     *
     * @param keyColumns its primary key, in order; empty when it has none (it cannot be sealed)
     * @param guarded    whether its row trigger refusing updates and deletions is enabled
     */
    public record Table(String name, List<String> keyColumns, boolean guarded) {

        /** The row's key as the seal keeps it: the primary key as a JSON array. */
        String keyExpression(String alias) {
            return "jsonb_build_array(" + keyColumns.stream().map(column -> alias + "." + quote(column))
                .collect(Collectors.joining(", ")) + ")::text";
        }

        String quotedName() {
            return quote(name);
        }
    }

    private static final String TABLES = """
        SELECT c.relname::text AS table_name,
               ARRAY(SELECT a.attname::text
                     FROM pg_index i
                     CROSS JOIN LATERAL unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
                     JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                     WHERE i.indrelid = c.oid AND i.indisprimary
                     ORDER BY k.ord) AS key_columns,
               EXISTS (SELECT 1 FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid
                       WHERE t.tgrelid = c.oid AND NOT t.tgisinternal AND t.tgenabled <> 'D'
                         AND p.proname = 'jabiz_reject_mutation' AND (t.tgtype & 1) <> 0) AS guarded,
               EXISTS (SELECT 1 FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid
                       WHERE t.tgrelid = c.oid AND NOT t.tgisinternal
                         AND p.proname = 'jabiz_reject_mutation' AND (t.tgtype & 1) <> 0) AS append_only
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'p')
        """;

    private final StorageAdapterRegistry storages;
    private final String poolRef;

    public IntegrityStore(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.poolRef = poolRef;
    }

    StorageEngine engine() {
        return storages.getEngine(poolRef);
    }

    /**
     * The append-only tables of the schema - those with a row trigger of {@code jabiz_reject_mutation()}, enabled
     * or not - except the seal tables, by name.
     */
    public Flux<Table> appendOnlyTables(StorageEngine engine) {
        return engine.select(TABLES + " ORDER BY c.relname", Map.of())
            .filter(row -> Boolean.TRUE.equals(row.get("append_only")))
            .map(IntegrityStore::table)
            .filter(table -> !OWN_TABLES.contains(table.name()));
    }

    /** The table of that name in the schema, append-only or not; empty when there is none. */
    Mono<Table> table(StorageEngine engine, String name) {
        return engine.select(TABLES + " AND c.relname = :name", Map.of("name", BoundValue.of(name)))
            .next()
            .map(IntegrityStore::table);
    }

    private static Table table(Map<String, Object> row) {
        return new Table(Rows.string(row.get("table_name")), List.copyOf(Rows.strings(row.get("key_columns"))),
            Boolean.TRUE.equals(row.get("guarded")));
    }

    /** Pins, for this transaction, the settings the text of a row depends on. */
    Mono<Void> pin(StorageEngine engine) {
        return engine.select("""
                SELECT set_config('TimeZone', 'UTC', true) AS tz, set_config('IntervalStyle', 'iso_8601', true) AS iv,
                       set_config('extra_float_digits', '1', true) AS fd""", Map.of())
            .then();
    }

    /** One seal at a time: the next block number and link come from the head. */
    Mono<Void> lock(StorageEngine engine) {
        return engine.select("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtextextended("
                + "'jabiz.integrity.seal', 0))) AS l", Map.of())
            .then();
    }

    /** The last block, or empty before the first. */
    Mono<SealChain.Stored> head(StorageEngine engine) {
        return engine.select("SELECT * FROM sys_integrity_seal ORDER BY seal_no DESC LIMIT 1", Map.of())
            .next()
            .map(IntegrityStore::stored);
    }

    /** Rows of the table that no block holds yet, in key order, at most {@code limit}. */
    Flux<SealedRow> unsealed(StorageEngine engine, Table table, int limit) {
        return engine.select("SELECT " + table.keyExpression("t") + " AS row_key, " + digest("t") + " AS digest"
                    + " FROM " + table.quotedName() + " t WHERE NOT EXISTS (SELECT 1 FROM sys_integrity_item i"
                    + " WHERE i.table_name = :table AND i.row_key = " + table.keyExpression("t") + ")"
                    + " ORDER BY 1 LIMIT :limit",
                Map.of("table", BoundValue.of(table.name()), "limit", BoundValue.of((long) limit)))
            .map(row -> new SealedRow(table.name(), Rows.string(row.get("row_key")), Rows.string(row.get("digest"))));
    }

    /** How many rows of the table no block holds. */
    Mono<Long> unsealedCount(StorageEngine engine, Table table) {
        return engine.select("SELECT count(*) AS n FROM " + table.quotedName() + " t WHERE NOT EXISTS (SELECT 1 FROM"
                    + " sys_integrity_item i WHERE i.table_name = :table AND i.row_key = " + table.keyExpression("t")
                    + ")", Map.of("table", BoundValue.of(table.name())))
            .next()
            .map(row -> Rows.longValue(row.get("n")));
    }

    Mono<Void> insertSeal(StorageEngine engine, SealBlock block, String hash, long processSeqId,
        List<SealedRow> rows) {
        Map<String, Object> seal = new LinkedHashMap<>();
        seal.put("seal_no", block.sealNo());
        seal.put("sealed_time", block.sealedTime());
        seal.put("row_count", block.rowCount());
        seal.put("merkle_root", block.merkleRoot());
        seal.put("prev_hash", block.prevHash());
        seal.put("key_id", block.keyId());
        seal.put("seal_hash", hash);
        seal.put("process_seq_id", processSeqId);
        List<List<SealedRow>> chunks = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += INSERT_CHUNK) {
            chunks.add(rows.subList(i, Math.min(rows.size(), i + INSERT_CHUNK)));
        }
        return engine.insert("sys_integrity_seal", seal)
            .thenMany(Flux.fromIterable(chunks).concatMap(chunk -> engine.select("""
                    WITH inserted AS (
                        INSERT INTO sys_integrity_item (table_name, row_key, seal_no, digest)
                        SELECT u.t, u.k, :seal, u.d FROM unnest(:tables::text[], :keys::text[], :digests::text[])
                            AS u(t, k, d)
                        RETURNING 1)
                    SELECT count(*) AS n FROM inserted""",
                Map.of("seal", BoundValue.of(block.sealNo()),
                    "tables", BoundValue.of(chunk.stream().map(SealedRow::table).toArray(String[]::new)),
                    "keys", BoundValue.of(chunk.stream().map(SealedRow::key).toArray(String[]::new)),
                    "digests", BoundValue.of(chunk.stream().map(SealedRow::digest).toArray(String[]::new))))))
            .then();
    }

    /** The blocks from {@code fromSeal} on, in order. */
    Flux<SealChain.Stored> blocks(StorageEngine engine, long fromSeal) {
        return engine.select("SELECT * FROM sys_integrity_seal WHERE seal_no >= :from ORDER BY seal_no",
                Map.of("from", BoundValue.of(fromSeal)))
            .map(IntegrityStore::stored);
    }

    /** The block of that number. */
    Mono<SealChain.Stored> block(StorageEngine engine, long sealNo) {
        return engine.select("SELECT * FROM sys_integrity_seal WHERE seal_no = :no",
                Map.of("no", BoundValue.of(sealNo)))
            .next()
            .map(IntegrityStore::stored);
    }

    /** The rows one block holds, as stored. */
    record BlockRows(long sealNo, List<SealedRow> rows) {}

    /** The rows each block holds, block by block (in number order), as stored. */
    Flux<BlockRows> rowsBySeal(StorageEngine engine, long fromSeal, long toSeal) {
        return engine.select("""
                SELECT seal_no, table_name, row_key, digest FROM sys_integrity_item
                WHERE seal_no BETWEEN :from AND :to ORDER BY seal_no, table_name, row_key""",
                Map.of("from", BoundValue.of(fromSeal), "to", BoundValue.of(toSeal)))
            .bufferUntilChanged(row -> Rows.longValue(row.get("seal_no")))
            .map(rows -> new BlockRows(Rows.longValue(rows.getFirst().get("seal_no")), rows.stream()
                .map(row -> new SealedRow(Rows.string(row.get("table_name")), Rows.string(row.get("row_key")),
                    Rows.string(row.get("digest")))).toList()));
    }

    /** The tables that rows of the range belong to. */
    Flux<String> sealedTables(StorageEngine engine, long fromSeal, long toSeal) {
        return engine.select("SELECT DISTINCT table_name FROM sys_integrity_item WHERE seal_no BETWEEN :from AND :to"
                + " ORDER BY table_name", Map.of("from", BoundValue.of(fromSeal), "to", BoundValue.of(toSeal)))
            .map(row -> Rows.string(row.get("table_name")));
    }

    /** Sealed rows of the table whose content now has another digest, or that are gone. */
    Flux<IntegrityProblem> changedRows(StorageEngine engine, Table table, long fromSeal, long toSeal) {
        return engine.select("SELECT i.seal_no, i.row_key, i.digest AS sealed, c.digest AS current"
                    + " FROM sys_integrity_item i LEFT JOIN (SELECT " + table.keyExpression("t") + " AS row_key, "
                    + digest("t") + " AS digest FROM " + table.quotedName() + " t) c ON c.row_key = i.row_key"
                    + " WHERE i.table_name = :table AND i.seal_no BETWEEN :from AND :to"
                    + " AND (c.digest IS NULL OR c.digest <> i.digest) ORDER BY i.seal_no, i.row_key",
                Map.of("table", BoundValue.of(table.name()), "from", BoundValue.of(fromSeal),
                    "to", BoundValue.of(toSeal)))
            .map(row -> {
                Long sealNo = Rows.longValue(row.get("seal_no"));
                String key = Rows.string(row.get("row_key"));
                return row.get("current") == null
                    ? new IntegrityProblem(IntegrityProblem.Kind.MISSING, sealNo, table.name(), key,
                        "Row " + key + " of " + table.name() + " is gone")
                    : new IntegrityProblem(IntegrityProblem.Kind.MODIFIED, sealNo, table.name(), key,
                        "Row " + key + " of " + table.name() + " was changed after it was sealed");
            });
    }

    /** Every sealed row of a table that no longer exists. */
    Flux<IntegrityProblem> rowsOfMissingTable(StorageEngine engine, String table, long fromSeal, long toSeal) {
        return engine.select("SELECT seal_no, row_key FROM sys_integrity_item WHERE table_name = :table"
                + " AND seal_no BETWEEN :from AND :to ORDER BY seal_no, row_key",
                Map.of("table", BoundValue.of(table), "from", BoundValue.of(fromSeal), "to", BoundValue.of(toSeal)))
            .map(row -> new IntegrityProblem(IntegrityProblem.Kind.MISSING, Rows.longValue(row.get("seal_no")),
                table, Rows.string(row.get("row_key")), "Table " + table + " is gone"));
    }

    Mono<Long> insertCheck(StorageEngine engine, Map<String, BoundValue> check) {
        return engine.select("""
                INSERT INTO sys_integrity_check (process_seq_id, actor_id, checked_time, from_seal, to_seal,
                    seal_count, row_count, unsealed_count, problem_count, problems, key_id)
                VALUES (:seq, :actor, :time, :from, :to, :seals, :rows, :unsealed, :count, :problems::jsonb, :key)
                RETURNING check_no""", check)
            .next()
            .map(row -> Rows.longValue(row.get("check_no")));
    }

    Flux<Map<String, Object>> sealPage(long offset, int limit) {
        return engine().select("SELECT * FROM sys_integrity_seal ORDER BY seal_no DESC OFFSET :offset LIMIT :limit",
            Map.of("offset", BoundValue.of(offset), "limit", BoundValue.of((long) limit)));
    }

    Flux<Map<String, Object>> checkPage(long offset, int limit) {
        return engine().select("SELECT check_no, process_seq_id, actor_id, checked_time, from_seal, to_seal,"
                + " seal_count, row_count, unsealed_count, problem_count, key_id FROM sys_integrity_check"
                + " ORDER BY check_no DESC OFFSET :offset LIMIT :limit",
            Map.of("offset", BoundValue.of(offset), "limit", BoundValue.of((long) limit)));
    }

    Mono<Map<String, Object>> check(long checkNo) {
        return engine().select("SELECT *, problems::text AS problems_json FROM sys_integrity_check"
                + " WHERE check_no = :no", Map.of("no", BoundValue.of(checkNo)))
            .next();
    }

    static SealChain.Stored stored(Map<String, Object> row) {
        return new SealChain.Stored(new SealBlock(Rows.longValue(row.get("seal_no")),
            Rows.instant(row.get("sealed_time")), Rows.intValue(row.get("row_count")),
            Rows.string(row.get("merkle_root")), Rows.string(row.get("prev_hash")), Rows.string(row.get("key_id"))),
            Rows.string(row.get("seal_hash")));
    }

    private static String digest(String alias) {
        return "encode(pg_catalog.sha256(convert_to(to_jsonb(" + alias + ")::text, 'UTF8')), 'hex')";
    }

    private static String quote(String identifier) {
        return "\"" + SqlIdentifiers.require(identifier) + "\"";
    }
}

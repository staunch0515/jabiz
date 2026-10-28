package com.jabiz.runtime.integrity;

import com.jabiz.integrity.IntegrityKey;
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
import java.util.TreeSet;
import java.util.UUID;
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
    public static final Set<String> OWN_TABLES = Set.of("sys_integrity_seal", "sys_integrity_item",
        "sys_integrity_seal_table", "sys_integrity_check");

    /** How many sealed rows one insert statement takes. */
    private static final int INSERT_CHUNK = 5_000;

    /**
     * A table whose rows the database refuses to change (decision D5).
     *
     * @param keyColumns its primary key, in order; empty when it has none (it cannot be sealed)
     * @param guarded    whether its triggers refusing updates, deletions and truncation fire in ordinary sessions
     *                   (enabled, not only for replication)
     */
    public record Table(String name, List<String> keyColumns, boolean guarded) {

        /** The row's key as the seal keeps it: the primary key as a JSON array. */
        public String keyExpression(String alias) {
            return "jsonb_build_array(" + keyColumns.stream().map(column -> alias + "." + quote(column))
                .collect(Collectors.joining(", ")) + ")::text";
        }

        public String quotedName() {
            return quote(name);
        }
    }

    /**
     * The tables of the schema with their primary key ({@code key_columns}), whether they are append-only
     * ({@code append_only}: a row trigger of {@code jabiz_reject_mutation()}) and guarded. The seal seals the
     * append-only ones with a key, except {@link #OWN_TABLES}; tests that judge what the seal covers read it too.
     */
    public static final String TABLES = """
        SELECT c.relname::text AS table_name,
               ARRAY(SELECT a.attname::text
                     FROM pg_index i
                     CROSS JOIN LATERAL unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
                     JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                     WHERE i.indrelid = c.oid AND i.indisprimary
                     ORDER BY k.ord) AS key_columns,
               EXISTS (SELECT 1 FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid
                       WHERE t.tgrelid = c.oid AND NOT t.tgisinternal AND t.tgenabled IN ('O', 'A')
                         AND p.proname = 'jabiz_reject_mutation' AND (t.tgtype & 3) = 3 AND (t.tgtype & 24) = 24)
               AND EXISTS (SELECT 1 FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid
                       WHERE t.tgrelid = c.oid AND NOT t.tgisinternal AND t.tgenabled IN ('O', 'A')
                         AND p.proname = 'jabiz_reject_mutation' AND (t.tgtype & 1) = 0 AND (t.tgtype & 2) = 2
                         AND (t.tgtype & 32) = 32) AS guarded,
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

    public StorageEngine engine() {
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
    public Mono<Table> table(StorageEngine engine, String name) {
        return engine.select(TABLES + " AND c.relname = :name", Map.of("name", BoundValue.of(name)))
            .next()
            .map(IntegrityStore::table);
    }

    private static Table table(Map<String, Object> row) {
        return new Table(Rows.string(row.get("table_name")), List.copyOf(Rows.strings(row.get("key_columns"))),
            Boolean.TRUE.equals(row.get("guarded")));
    }

    /** The table's columns now, by name: what its rows sealed now are digested over. */
    Mono<List<String>> columns(StorageEngine engine, Table table) {
        return engine.select("""
                SELECT array_agg(a.attname::text ORDER BY a.attname::text) AS columns FROM pg_attribute a
                WHERE a.attrelid = to_regclass(:table) AND a.attnum > 0 AND NOT a.attisdropped""",
                Map.of("table", BoundValue.of(table.quotedName())))
            .next()
            .map(row -> List.copyOf(Rows.strings(row.get("columns"))));
    }

    /**
     * A column a block recorded for a table (docs/design/21-audit-retention.md section 2.1).
     *
     * @param firstSeal the first block that recorded it
     */
    public record SealedColumn(String table, String column, long firstSeal) {}

    /**
     * Every column the blocks recorded, each table's and each column once, with the first block that recorded it.
     * The recorded lists differ only where a migration changed a table, so the query skips from one distinct
     * (table, list) to the next on the index {@code sys_integrity_seal_table_columns_idx} rather than reading every
     * block's row: a few rows read whatever the number of blocks.
     */
    public Flux<SealedColumn> sealedColumns(StorageEngine engine) {
        return engine.select("""
                WITH RECURSIVE lists AS (
                    (SELECT table_name, columns, seal_no FROM sys_integrity_seal_table
                     ORDER BY table_name, columns, seal_no LIMIT 1)
                    UNION ALL
                    SELECT next.table_name, next.columns, next.seal_no FROM lists
                    CROSS JOIN LATERAL (
                        SELECT s.table_name, s.columns, s.seal_no FROM sys_integrity_seal_table s
                        WHERE (s.table_name, s.columns) > (lists.table_name, lists.columns)
                        ORDER BY s.table_name, s.columns, s.seal_no LIMIT 1) AS next)
                SELECT table_name::text AS table_name, column_name, min(seal_no) AS first_seal
                FROM lists CROSS JOIN LATERAL unnest(columns) AS c(column_name)
                GROUP BY table_name, column_name
                ORDER BY table_name, column_name""", Map.of())
            .map(row -> new SealedColumn(Rows.string(row.get("table_name")), Rows.string(row.get("column_name")),
                Rows.longValue(row.get("first_seal"))));
    }

    /** The columns the tables of these names have now in the schema, by table; a table not there is left out. */
    public Mono<Map<String, Set<String>>> currentColumns(StorageEngine engine, Set<String> tables) {
        return engine.select("""
                SELECT c.relname::text AS table_name, a.attname::text AS column_name FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = current_schema()
                JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                WHERE c.relkind IN ('r', 'p') AND c.relname = ANY(:tables)""",
                Map.of("tables", BoundValue.of(tables.toArray(String[]::new))))
            .collect(LinkedHashMap::new, (Map<String, Set<String>> map, Map<String, Object> row) -> map
                .computeIfAbsent(Rows.string(row.get("table_name")), t -> new TreeSet<>())
                .add(Rows.string(row.get("column_name"))));
    }

    /** Pins, for this transaction, the settings the text of a row depends on. */
    public Mono<Void> pin(StorageEngine engine) {
        return engine.select("""
                SELECT set_config('TimeZone', 'UTC', true) AS tz, set_config('IntervalStyle', 'iso_8601', true) AS iv,
                       set_config('extra_float_digits', '1', true) AS fd""", Map.of())
            .then();
    }

    /**
     * The transaction lock of the seal: one seal at a time, and the purge of an erasure (decision D62 item 6) takes it
     * too, so that no seal reads a row between the purge's reading and overwriting it.
     */
    public static final String LOCK = "SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtextextended("
        + "'jabiz.integrity.seal', 0))) AS l";

    Mono<Void> lock(StorageEngine engine) {
        return engine.select(LOCK, Map.of()).then();
    }

    /** The last block, or empty before the first. */
    public Mono<SealChain.Stored> head(StorageEngine engine) {
        return engine.select("SELECT * FROM sys_integrity_seal ORDER BY seal_no DESC LIMIT 1", Map.of())
            .next()
            .map(IntegrityStore::stored);
    }

    /** Rows of the table that no block holds yet, in key order, at most {@code limit}. */
    Flux<SealedRow> unsealed(StorageEngine engine, Table table, int limit) {
        return engine.select("SELECT " + table.keyExpression("t") + " AS row_key, " + fullDigest("t")
                    + " AS digest FROM " + table.quotedName() + " t WHERE NOT EXISTS (SELECT 1 FROM"
                    + " sys_integrity_item i WHERE i.table_name = :table AND i.row_key = " + table.keyExpression("t")
                    + ") ORDER BY 1 LIMIT :limit",
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
        Map<String, List<String>> columns, List<SealedRow> rows) {
        Map<String, Object> seal = new LinkedHashMap<>();
        seal.put("seal_no", block.sealNo());
        seal.put("sealed_time", block.sealedTime());
        seal.put("row_count", block.rowCount());
        seal.put("merkle_root", block.merkleRoot());
        seal.put("columns_hash", block.columnsHash());
        seal.put("prev_hash", block.prevHash());
        seal.put("key_id", block.keyId());
        seal.put("seal_hash", hash);
        seal.put("process_seq_id", processSeqId);
        List<List<SealedRow>> chunks = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += INSERT_CHUNK) {
            chunks.add(rows.subList(i, Math.min(rows.size(), i + INSERT_CHUNK)));
        }
        return engine.insert("sys_integrity_seal", seal)
            .thenMany(Flux.fromIterable(columns.entrySet()).concatMap(entry -> engine.select("""
                    INSERT INTO sys_integrity_seal_table (seal_no, table_name, columns)
                    VALUES (:seal, :table, :columns::text[]) RETURNING seal_no""",
                Map.of("seal", BoundValue.of(block.sealNo()), "table", BoundValue.of(entry.getKey()),
                    "columns", BoundValue.of(entry.getValue().toArray(String[]::new))))))
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

    /** The columns each table's rows of each block of the range were digested over, by block. */
    Mono<Map<Long, Map<String, List<String>>>> sealedColumns(StorageEngine engine, long fromSeal, long toSeal) {
        return engine.select("SELECT seal_no, table_name, columns FROM sys_integrity_seal_table"
                + " WHERE seal_no BETWEEN :from AND :to", Map.of("from", BoundValue.of(fromSeal),
                    "to", BoundValue.of(toSeal)))
            .collect(java.util.TreeMap::new, (byBlock, row) -> byBlock
                .computeIfAbsent(Rows.longValue(row.get("seal_no")), no -> new java.util.TreeMap<>())
                .put(Rows.string(row.get("table_name")), List.copyOf(Rows.strings(row.get("columns")))));
    }

    /** The tables that rows of the range belong to. */
    Flux<String> sealedTables(StorageEngine engine, long fromSeal, long toSeal) {
        return engine.select("SELECT DISTINCT table_name FROM sys_integrity_item WHERE seal_no BETWEEN :from AND :to"
                + " ORDER BY table_name", Map.of("from", BoundValue.of(fromSeal), "to", BoundValue.of(toSeal)))
            .map(row -> Rows.string(row.get("table_name")));
    }

    /** Sealed rows of the table whose content now has another digest, or that are gone. */
    Flux<IntegrityProblem> changedRows(StorageEngine engine, Table table, int currentColumns, long fromSeal,
        long toSeal, String[] authenticErasures) {
        // Each row is digested over the columns of its own block; a missing column list gives no digest at all
        // (the block's columns hash reports that). Columns are only ever added, so a list as long as the table's
        // columns now is all of them, and the whole row's text - four to six times cheaper - is the same digest.
        String firstKey = "t." + quote(table.keyColumns().getFirst());
        // A changed row is ERASED when the entries of signed purges lead from the sealed digest to the current one:
        // each entry was written with the digests over the same columns (decision D62 item 6). Entries of a purge
        // whose signature does not hold count for nothing.
        return engine.select("SELECT seal_no, row_key, gone, NOT gone AND EXISTS (WITH RECURSIVE chain(d) AS ("
                    + "SELECT c.sealed::text UNION SELECT x.digest_after::text FROM sys_erasure_item x JOIN chain"
                    + " ON x.digest_before::text = chain.d WHERE x.table_name = :table AND x.row_key = c.row_key"
                    + " AND x.erasure_id::text = ANY(:authentic))"
                    + " SELECT 1 FROM chain WHERE chain.d = c.current) AS erased"
                    + " FROM (SELECT i.seal_no, i.row_key,"
                    + " i.digest AS sealed, CASE WHEN " + firstKey + " IS NULL THEN NULL"
                    + " WHEN cardinality(st.columns) = :current THEN " + fullDigest("t")
                    + " ELSE " + digest("t", "st.columns") + " END AS current, " + firstKey + " IS NULL AS gone"
                    + " FROM sys_integrity_item i LEFT JOIN sys_integrity_seal_table st ON st.seal_no = i.seal_no"
                    + " AND st.table_name = i.table_name LEFT JOIN " + table.quotedName() + " t ON "
                    + table.keyExpression("t") + " = i.row_key"
                    + " WHERE i.table_name = :table AND i.seal_no BETWEEN :from AND :to) AS c"
                    + " WHERE gone OR current IS DISTINCT FROM sealed ORDER BY seal_no, row_key",
                Map.of("table", BoundValue.of(table.name()), "from", BoundValue.of(fromSeal),
                    "to", BoundValue.of(toSeal), "current", BoundValue.of(currentColumns),
                    "authentic", BoundValue.of(authenticErasures)))
            .map(row -> {
                Long sealNo = Rows.longValue(row.get("seal_no"));
                String key = Rows.string(row.get("row_key"));
                if (Boolean.TRUE.equals(row.get("gone"))) {
                    return new IntegrityProblem(IntegrityProblem.Kind.MISSING, sealNo, table.name(), key,
                        "Row " + key + " of " + table.name() + " is gone");
                }
                return Boolean.TRUE.equals(row.get("erased"))
                    ? new IntegrityProblem(IntegrityProblem.Kind.ERASED, sealNo, table.name(), key,
                        "Row " + key + " of " + table.name() + " was overwritten by the purge of an erasure")
                    : new IntegrityProblem(IntegrityProblem.Kind.MODIFIED, sealNo, table.name(), key,
                        "Row " + key + " of " + table.name() + " was changed after it was sealed");
            });
    }

    /** The entries of one erasure's purge, as {@link ErasureEvidence} signs them. */
    public Flux<ErasureEvidence.Item> erasureItems(StorageEngine engine, UUID erasureId) {
        return engine.select("SELECT table_name, row_key, columns, digest_before, digest_after FROM sys_erasure_item"
                + " WHERE erasure_id = :id", Map.of("id", BoundValue.of(erasureId)))
            .map(IntegrityStore::item);
    }

    /**
     * The erasures whose purge's signature holds under the key, as text: only their entries account for a changed
     * row. Each purge's entries are read together, one purge at a time.
     */
    Mono<String[]> authenticErasures(StorageEngine engine, IntegrityKey key) {
        return engine.select("SELECT p.erasure_id, p.row_count, p.signature, p.key_id, x.table_name, x.row_key,"
                + " x.columns, x.digest_before, x.digest_after FROM sys_erasure_purge p"
                + " LEFT JOIN sys_erasure_item x ON x.erasure_id = p.erasure_id ORDER BY p.erasure_id", Map.of())
            .bufferUntilChanged(row -> Rows.uuid(row.get("erasure_id")))
            .filter(rows -> {
                Map<String, Object> first = rows.getFirst();
                UUID erasureId = Rows.uuid(first.get("erasure_id"));
                List<ErasureEvidence.Item> items = rows.stream().filter(row -> row.get("table_name") != null)
                    .map(IntegrityStore::item).toList();
                return key.id().equals(Rows.string(first.get("key_id")))
                    && ErasureEvidence.sign(key, erasureId, Rows.intValue(first.get("row_count")), items)
                        .equals(Rows.string(first.get("signature")));
            })
            .map(rows -> Rows.uuid(rows.getFirst().get("erasure_id")).toString())
            .collectList()
            .map(ids -> ids.toArray(String[]::new));
    }

    private static ErasureEvidence.Item item(Map<String, Object> row) {
        return new ErasureEvidence.Item(Rows.string(row.get("table_name")), Rows.string(row.get("row_key")),
            Rows.strings(row.get("columns")), Rows.string(row.get("digest_before")),
            Rows.string(row.get("digest_after")));
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
                    seal_count, row_count, unsealed_count, problem_count, problems, key_id, erased_count)
                VALUES (:seq, :actor, :time, :from, :to, :seals, :rows, :unsealed, :count, :problems::jsonb, :key,
                    :erased)
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
            Rows.string(row.get("merkle_root")), Rows.string(row.get("columns_hash")),
            Rows.string(row.get("prev_hash")), Rows.string(row.get("key_id"))), Rows.string(row.get("seal_hash")));
    }

    /** The row's SHA-256 over all its columns: of the text of {@code to_jsonb(row)}. */
    private static String fullDigest(String alias) {
        return "encode(pg_catalog.sha256(convert_to(to_jsonb(" + alias + ".*)::text, 'UTF8')), 'hex')";
    }

    /**
     * The row's SHA-256 over the named columns: {@code to_jsonb} of the row restricted to them. Over all of a table's
     * columns it is the text of {@code to_jsonb(row)} itself, jsonb keeping its keys in its own order.
     */
    public static String digest(String alias, String columns) {
        // alias.* is the row even when the table has a column named like the alias.
        return "encode(pg_catalog.sha256(convert_to((SELECT jsonb_object_agg(e.key, e.value) FROM jsonb_each(to_jsonb("
            + alias + ".*)) AS e WHERE e.key = ANY(" + columns + "))::text, 'UTF8')), 'hex')";
    }

    private static String quote(String identifier) {
        return "\"" + SqlIdentifiers.require(identifier) + "\"";
    }
}

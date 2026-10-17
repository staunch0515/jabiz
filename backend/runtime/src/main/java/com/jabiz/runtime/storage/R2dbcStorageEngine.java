package com.jabiz.runtime.storage;

import com.jabiz.query.BoundValue;
import com.jabiz.query.PhysicalQueryPlan;
import com.jabiz.query.RawQueryPlan;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.LockNotAvailableException;
import io.r2dbc.postgresql.api.PostgresqlException;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.ColumnMetadata;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Storage engine on top of Spring's reactive {@link DatabaseClient}. All statements are
 * parameterized; identifiers are validated before they are placed in SQL text.
 * Transactions are propagated through the Reactor context by the {@link TransactionalOperator}.
 *
 * <p>Within a transaction it keeps the {@link TransactionState} of that transaction in the Reactor context, and the
 * writes held back (decision D46 rule 1): the rows {@link #withWriteBatch} work inserts and the updates at a known
 * version it makes. With {@link WriteBatching#acrossTransaction()} (ROADMAP phase 16p-2, T9) they are held for the
 * whole transaction; otherwise (phase 16p-1) only until the end of the work. They are written
 * <ul>
 *   <li>on their own, before a statement that reads or writes one of their tables (by its text, the tables it declares,
 *       or a known view over them), before any other write and any statement the engine does not know to be a query
 *       (a template, a statement of the platform that is not a {@code SELECT}), before a savepoint is set and released,
 *       before {@link #flushWrites}, at the end of the transaction and before it fails for a reason not the
 *       database's;</li>
 *   <li>otherwise, held for the transaction, rows to insert together with the next query that has to be sent anyway (a
 *       lock, a platform read of other tables), ahead of it in the same statement ({@link WriteBatch.Parts#carriedBy});
 *       a held update, or a read bound by a timeout (a dataset's), has them written on their own first.</li>
 * </ul>
 * Every read therefore sees what statement by statement execution would have shown it, and every write that fails
 * fails before anything the transaction does next.
 */
public final class R2dbcStorageEngine implements StorageEngine {

    private static final Logger log = LoggerFactory.getLogger(R2dbcStorageEngine.class);

    /** SQLSTATE of unique_violation. */
    private static final String UNIQUE_VIOLATION = "23505";
    /** SQLSTATE of invalid_text_representation: here, the term of a held update that changed no row. */
    private static final String INVALID_TEXT = "22P02";
    /** The database broke a deadlock by failing this statement, or gave up waiting for a lock (lock_timeout). */
    private static final java.util.Set<String> LOCK_FAILURES = java.util.Set.of("40P01", "55P03");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final DatabaseClient db;
    private final TransactionalOperator tx;
    private final TransactionalOperator newTx;
    private final WriteBatching batching;

    /** Keys of this engine's transaction state, write buffer and writing work in the Reactor context. */
    private final Object stateKey = new ContextKey("transaction state");
    private final Object bufferKey = new ContextKey("write buffer");
    private final Object zoneKey = new ContextKey("held writes");

    private record ContextKey(String name) {}

    /** An engine without {@link #inNewTransaction}. */
    public R2dbcStorageEngine(DatabaseClient db, TransactionalOperator tx) {
        this(db, tx, null);
    }

    /**
     * @param newTx operator of the same transaction manager that always starts a transaction of its own
     *              ({@code PROPAGATION_REQUIRES_NEW}), for {@link #inNewTransaction}; null: not supported
     */
    public R2dbcStorageEngine(DatabaseClient db, TransactionalOperator tx, TransactionalOperator newTx) {
        this(db, tx, newTx, new WriteBatching());
    }

    /** @param batching whether writes are held for whole transactions, and the views over the tables they go to */
    public R2dbcStorageEngine(DatabaseClient db, TransactionalOperator tx, TransactionalOperator newTx,
        WriteBatching batching) {
        this.db = Objects.requireNonNull(db);
        this.tx = Objects.requireNonNull(tx);
        this.newTx = newTx;
        this.batching = Objects.requireNonNull(batching, "batching must not be null");
    }

    @Override
    public Optional<TransactionState> transactionState(ContextView context) {
        return context.getOrEmpty(stateKey);
    }

    // ================= Writes =================

    @Override
    public Mono<Void> insert(String table, Map<String, Object> record) {
        return Mono.deferContextual(view -> {
            WriteBatch.Row row = new WriteBatch.Row(SqlIdentifiers.require(table), record,
                view.getOrDefault(InsertConflicts.class, null), origin(view));
            if (row.record().values().stream().allMatch(Objects::isNull)) {
                return Mono.error(new IllegalArgumentException("Insert into " + table + " has no values"));
            }
            WriteBuffer buffer = held(view);
            if (buffer != null) {
                // An insert and an update of one table never share a statement: neither would see the other.
                Mono<Void> before = buffer.updates(row.table()) ? flush(buffer) : Mono.empty();
                return before.then(Mono.fromRunnable(() -> buffer.add(row, names(row.table()))));
            }
            return beforeWrite(view, List.of(table)).then(Mono.defer(() -> execute(view, WriteBatch.render(List.of(row)))));
        });
    }

    @Override
    public Mono<Boolean> casUpdate(String table, String pkColumn, Object id, long expectedVersion,
        String versionColumn, Map<String, Object> updates) {
        return casUpdate(table, pkColumn, id, expectedVersion, versionColumn, updates, List.of());
    }

    @Override
    public Mono<Boolean> casUpdate(String table, String pkColumn, Object id, long expectedVersion,
        String versionColumn, Map<String, Object> updates, List<Insert> dependents) {
        return casUpdate(table, pkColumn, id, expectedVersion, versionColumn, updates, dependents, null);
    }

    @Override
    public Mono<Boolean> casUpdate(String table, String pkColumn, Object id, long expectedVersion,
        String versionColumn, Map<String, Object> updates, List<Insert> dependents,
        Supplier<Mono<Throwable>> notApplied) {
        return Mono.deferContextual(view -> {
            if (!dependents.isEmpty() && WriteBatching.isStatementByStatement(view)) {
                // One statement at a time, as before phase 16p (the test hook): the update, then its rows.
                return StorageEngine.super.casUpdate(table, pkColumn, id, expectedVersion, versionColumn, updates,
                    dependents);
            }
            WriteBuffer buffer = held(view);
            if (notApplied != null && buffer != null && buffer.transactional) {
                // Held with the transaction's other writes (16p-2, T9), checked when it is written. Whatever is held
                // for its table is written first: within one statement, the update would not see it.
                WriteBatch.Update update = new WriteBatch.Update(SqlIdentifiers.require(table),
                    SqlIdentifiers.require(pkColumn), id, expectedVersion, SqlIdentifiers.require(versionColumn),
                    updates, dependents, notApplied, origin(view));
                Mono<Void> before = buffer.touchesTables(List.of(update.table())) ? flush(buffer) : Mono.empty();
                return before.then(Mono.fromRunnable(() -> buffer.add(update, names(update.table()))))
                    .thenReturn(Boolean.TRUE);
            }
            String versionCol = SqlIdentifiers.require(versionColumn);
            List<String> assignments = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            for (Map.Entry<String, Object> e : updates.entrySet()) {
                String column = SqlIdentifiers.require(e.getKey());
                if (column.equalsIgnoreCase(versionCol)) {
                    continue;
                }
                if (e.getValue() == null) {
                    assignments.add(column + " = NULL");
                } else {
                    assignments.add(column + " = :u" + values.size());
                    values.add(toStorage(e.getValue()));
                }
            }
            assignments.add(versionCol + " = " + versionCol + " + 1");

            String update = "UPDATE " + SqlIdentifiers.require(table)
                            + " SET " + String.join(", ", assignments)
                            + " WHERE " + SqlIdentifiers.require(pkColumn) + " = :pk"
                            + " AND " + versionCol + " = :expectedVersion";
            List<String> touched = new ArrayList<>(List.of(table));
            String sql = update;
            Map<String, Object> dependentValues = new LinkedHashMap<>();
            if (!dependents.isEmpty()) {
                // The audit record and the event of the update exist exactly when it updated the row (16p, T7).
                StringBuilder with = new StringBuilder("WITH jabiz_u AS (").append(update)
                    .append(" RETURNING 1 AS jabiz_hit)");
                for (int i = 0; i < dependents.size(); i++) {
                    Insert row = dependents.get(i);
                    String target = SqlIdentifiers.require(row.table());
                    touched.add(target);
                    List<String> columns = new ArrayList<>();
                    row.record().forEach((column, value) -> {
                        if (value != null) {
                            columns.add(SqlIdentifiers.require(column));
                        }
                    });
                    with.append(", jabiz_d").append(i).append(" AS (INSERT INTO ").append(target)
                        .append(" (").append(String.join(", ", columns)).append(") SELECT ")
                        .append(String.join(", ", columns.stream().map(c -> "r." + c).toList()))
                        // The row's values as its table's row type: the database converts each to its column's type.
                        .append(" FROM jabiz_u, jsonb_populate_record(NULL::").append(target)
                        .append(", CAST(:d").append(i).append(" AS jsonb)) r)");
                    dependentValues.put("d" + i, Json.of(rowJson(row.record())));
                }
                sql = with.append(" SELECT count(*) AS jabiz_updated FROM jabiz_u").toString();
            }
            DatabaseClient.GenericExecuteSpec spec = db.sql(sql);
            for (int i = 0; i < values.size(); i++) {
                spec = spec.bind("u" + i, values.get(i));
            }
            for (Map.Entry<String, Object> dependent : dependentValues.entrySet()) {
                spec = spec.bind(dependent.getKey(), dependent.getValue());
            }
            spec = spec.bind("pk", id).bind("expectedVersion", expectedVersion);
            Mono<Boolean> applied = dependents.isEmpty()
                ? spec.fetch().rowsUpdated().map(count -> count > 0)
                : spec.map((r, m) -> ((Number) Objects.requireNonNull(r.get(0))).longValue()).one()
                    .map(count -> count > 0);
            return beforeWrite(view, touched)
                .then(Mono.defer(() -> {
                    counted(view);
                    return applied;
                }))
                .onErrorMap(R2dbcStorageEngine::translate)
                .doOnError(error -> failed(view));
        });
    }

    @Override
    public Mono<Boolean> delete(String table, String pkColumn, Object id, String versionColumn, long expectedVersion) {
        return Mono.deferContextual(view -> {
            String sql = "DELETE FROM " + SqlIdentifiers.require(table)
                         + " WHERE " + SqlIdentifiers.require(pkColumn) + " = :pk"
                         + " AND " + SqlIdentifiers.require(versionColumn) + " = :expectedVersion";
            return beforeWrite(view, List.of(table)).then(Mono.defer(() -> {
                counted(view);
                return db.sql(sql)
                    .bind("pk", id)
                    .bind("expectedVersion", expectedVersion)
                    .fetch().rowsUpdated()
                    .map(count -> count > 0);
            })).onErrorMap(R2dbcStorageEngine::translate).doOnError(error -> failed(view));
        });
    }

    /**
     * What is held is written before a write the engine sends itself: all of it when held for the transaction (the
     * write may fire a trigger, or check a foreign key, that reads any of it), what touches the write's tables within
     * one commit (phase 16p-1, where only that commit's own writes of combinable entities run).
     */
    private Mono<Void> beforeWrite(ContextView view, Collection<String> tables) {
        WriteBuffer buffer = view.getOrDefault(bufferKey, null);
        if (buffer == null) {
            return Mono.empty();
        }
        return buffer.transactional ? flush(buffer) : flushIfTouching(view, tables);
    }

    // ================= Reads =================

    @Override
    public Flux<Map<String, Object>> executeQuery(PhysicalQueryPlan plan) {
        return Flux.deferContextual(view -> {
            if (plan.matchesNothing()) {
                // False whatever the data (16p, T8): nothing to ask the database.
                return Flux.empty();
            }
            String sql = select(plan);
            return query(view, sql, plan.bindParams(), plan.timeout(), texts(List.of(sql)));
        });
    }

    /** The statement of a plan: its rows in order, the page of its limit and offset. */
    private static String select(PhysicalQueryPlan plan) {
        // The source is a table or the versions sub-select, built by QueryCompiler from validated identifiers.
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(plan.source());
        if (plan.whereClause() != null && !plan.whereClause().isBlank()) {
            sql.append(" WHERE ").append(plan.whereClause());
        }
        String orderBy = orderBy(plan, "");
        if (!orderBy.isEmpty()) {
            sql.append(" ORDER BY ").append(orderBy);
        }
        sql.append(" LIMIT ").append(plan.limit()).append(" OFFSET ").append(plan.offset());
        return sql.toString();
    }

    private static String orderBy(PhysicalQueryPlan plan, String alias) {
        List<String> orderBy = new ArrayList<>();
        for (PhysicalQueryPlan.PhysicalSort sort : plan.sorts()) {
            orderBy.add(alias + SqlIdentifiers.require(sort.physicalColumn()) + (sort.ascending() ? " ASC" : " DESC"));
        }
        return String.join(", ", orderBy);
    }

    /**
     * The reads as one statement (16p, T5): each plan's statement as {@link #executeQuery} sends it, the rows of each
     * joined to its number only, so every column keeps its type and nothing is converted on the way:
     * {@code SELECT … FROM (VALUES (0), (1)) k(jabiz_q) LEFT JOIN (rows of read 0) b0 ON k.jabiz_q = 0 LEFT JOIN …},
     * in the order of each read.
     */
    @Override
    public Mono<List<List<Map<String, Object>>>> executeQueries(List<PlanRead> reads) {
        return Mono.deferContextual(view -> {
            List<Integer> live = new ArrayList<>();
            for (int i = 0; i < reads.size(); i++) {
                if (!reads.get(i).plan().matchesNothing()) {
                    live.add(i);
                }
            }
            // Only reads held to the same limit are combined: each keeps the bound of its dataset (03 section 1).
            boolean sameLimit = live.stream().map(i -> reads.get(i).plan().timeout()).distinct().count() <= 1;
            if (live.size() <= 1 || !sameLimit) {
                return Flux.fromIterable(reads)
                    .concatMap(read -> executeQuery(read.plan()).collectList())
                    .collectList()
                    .map(List::copyOf);
            }
            Map<String, BoundValue> params = new LinkedHashMap<>();
            List<String> texts = new ArrayList<>();
            StringBuilder columns = new StringBuilder("SELECT k.jabiz_q");
            StringBuilder joins = new StringBuilder();
            List<String> numbers = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            Duration timeout = Duration.ZERO;
            for (int i : live) {
                PlanRead read = reads.get(i);
                PhysicalQueryPlan plan = read.plan();
                String branch = "b" + i;
                String sql = renamed(select(plan), plan.bindParams().keySet(), branch + "_");
                texts.add(sql);
                plan.bindParams().forEach((name, value) -> params.put(branch + "_" + name, value));
                List<String> selected = new ArrayList<>();
                String window = orderBy(plan, "r.");
                selected.add("row_number() OVER (" + (window.isEmpty() ? "" : "ORDER BY " + window) + ") AS jabiz_n" + i);
                for (int j = 0; j < read.columns().size(); j++) {
                    String alias = "jabiz_" + i + "_" + j;
                    selected.add("r." + SqlIdentifiers.require(read.columns().get(j)) + " AS " + alias);
                    columns.append(", ").append(branch).append('.').append(alias);
                }
                joins.append(" LEFT JOIN (SELECT ").append(String.join(", ", selected)).append(" FROM (").append(sql)
                    .append(") r) ").append(branch).append(" ON k.jabiz_q = ").append(i);
                numbers.add(branch + ".jabiz_n" + i);
                keys.add("(" + i + ")");
                if (plan.timeout().compareTo(timeout) > 0) {
                    timeout = plan.timeout();
                }
            }
            String any = "COALESCE(" + String.join(", ", numbers) + ")";
            String sql = columns + " FROM (VALUES " + String.join(", ", keys) + ") k(jabiz_q)" + joins
                + " WHERE " + any + " IS NOT NULL ORDER BY k.jabiz_q, " + any;
            return query(view, sql, params, timeout, texts(texts))
                .collectList()
                .map(rows -> {
                    List<List<Map<String, Object>>> results = new ArrayList<>();
                    for (int i = 0; i < reads.size(); i++) {
                        results.add(new ArrayList<>());
                    }
                    for (Map<String, Object> row : rows) {
                        int q = ((Number) row.get("jabiz_q")).intValue();
                        List<String> wanted = reads.get(q).columns();
                        Map<String, Object> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                        for (int j = 0; j < wanted.size(); j++) {
                            values.put(wanted.get(j), row.get("jabiz_" + q + "_" + j));
                        }
                        results.get(q).add(values);
                    }
                    return results.stream().map(List::copyOf).toList();
                });
        });
    }

    /** {@code sql} with its parameters {@code :name} renamed {@code :prefix + name}, so that several plans can meet. */
    static String renamed(String sql, Collection<String> names, String prefix) {
        String result = sql;
        for (String name : names) {
            result = Pattern.compile("(?<![:\\w]):" + Pattern.quote(name) + "(?!\\w)").matcher(result)
                .replaceAll(Matcher.quoteReplacement(":" + prefix + name));
        }
        return result;
    }

    @Override
    public Mono<Long> count(PhysicalQueryPlan plan) {
        return Mono.deferContextual(view -> {
            if (plan.matchesNothing()) {
                return Mono.just(0L);
            }
            StringBuilder sql = new StringBuilder("SELECT count(*) AS total FROM ").append(plan.source());
            if (plan.whereClause() != null && !plan.whereClause().isBlank()) {
                sql.append(" WHERE ").append(plan.whereClause());
            }
            return query(view, sql.toString(), plan.bindParams(), plan.timeout(), texts(List.of(sql.toString())))
                .next()
                .map(row -> ((Number) row.get("total")).longValue());
        });
    }

    @Override
    public Flux<Map<String, Object>> select(String sql, Map<String, BoundValue> params) {
        return Flux.deferContextual(view -> {
            if (isQuery(sql)) {
                return query(view, sql, params, null, texts(List.of(sql)));
            }
            // Not known to only read (it may write, set something, lock): what is held is written before it.
            return beforeStatement(view, List.of(sql)).thenMany(Flux.defer(() -> send(view, sql, params, Map.of(), null,
                List.of(), Map.of())));
        });
    }

    @Override
    public Flux<Map<String, Object>> select(String sql, Map<String, BoundValue> params, Set<String> tables) {
        return Flux.deferContextual(view -> query(view, sql, params, null, declared(tables)));
    }

    @Override
    public Flux<Map<String, Object>> executeRawQuery(RawQueryPlan plan) {
        // Run as given: paging is part of the statement (docs/design/05-sql-template.md section 5). A template may read
        // through functions and views the engine cannot see, so what is held is written before it, on its own.
        return Flux.deferContextual(view -> beforeStatement(view, List.of(plan.sql()))
            .thenMany(Flux.defer(() -> run(view, plan.sql(), plan.bindParams(), Map.of(), plan.timeout(), List.of(),
                Map.of()))));
    }

    /** Whether a statement of the platform is a query ({@code SELECT …}), which writes nothing of its own. */
    static boolean isQuery(String sql) {
        return QUERY.matcher(sql).lookingAt();
    }

    private static final Pattern QUERY = Pattern.compile("\\s*(?i:select)\\s");

    /** Before a statement of unknown kind: everything held for the transaction, what touches it within a commit. */
    private Mono<Void> beforeStatement(ContextView view, Collection<String> texts) {
        WriteBuffer buffer = view.getOrDefault(bufferKey, null);
        if (buffer == null) {
            return Mono.empty();
        }
        return buffer.transactional ? flush(buffer) : flushIfTouching(view, texts);
    }

    /** What a query may read of what is held: the tables its text names, or the tables it declares. */
    private sealed interface Reads permits Texts, Tables {}

    private record Texts(Collection<String> texts) implements Reads {}

    private record Tables(Set<String> tables) implements Reads {}

    private static Reads texts(Collection<String> texts) {
        return new Texts(List.copyOf(texts));
    }

    private static Reads declared(Set<String> tables) {
        return new Tables(Set.copyOf(tables));
    }

    /**
     * A query, with what is held: written on its own first when the query reads one of its tables; carried ahead of
     * the query in the same statement when held for the transaction; otherwise (within one commit, phase 16p-1) left
     * held. Never carried, but written on its own first (review of 16p-2):
     * <ul>
     *   <li>by a query bounded by {@code timeout} (a dataset's read, docs/design/03-dataset.md section 1): the held
     *       writes may wait for locks as long as the transaction's {@code lock_timeout}, which the query's own bound is
     *       not to be stretched by, nor its 503 to be given for a write's wait;</li>
     *   <li>when an update is held: whether the gate that reads it runs depends on the plan (PostgreSQL drops a
     *       condition on no row together with a sub-select it can prove empty, {@code WHERE 1 = 0}), and an update that
     *       changed no row would then pass unnoticed; written on its own, its term is in the select list and always
     *       evaluated. Rows to insert are written whatever the plan (a data-modifying {@code WITH} part always runs to
     *       completion), so carrying them is safe even then: only their order against a query that reads and locks
     *       nothing could change.</li>
     * </ul>
     *
     * @param timeout the query's bound, null for none (the platform's own statements)
     */
    private Flux<Map<String, Object>> query(ContextView view, String sql, Map<String, BoundValue> params,
        Duration timeout, Reads reads) {
        WriteBuffer buffer = view.getOrDefault(bufferKey, null);
        if (buffer == null || buffer.isEmpty()) {
            return run(view, sql, params, Map.of(), timeout, List.of(), Map.of());
        }
        boolean touching = switch (reads) {
            case Texts texts -> buffer.touches(texts.texts());
            case Tables tables -> buffer.touchesTables(tables.tables());
        };
        if (touching) {
            return flush(buffer).thenMany(Flux.defer(() -> run(view, sql, params, Map.of(), timeout, List.of(),
                Map.of())));
        }
        if (!buffer.transactional) {
            return run(view, sql, params, Map.of(), timeout, List.of(), Map.of());
        }
        if (timeout != null || buffer.holdsUpdates()) {
            return flush(buffer).thenMany(Flux.defer(() -> run(view, sql, params, Map.of(), timeout, List.of(),
                Map.of())));
        }
        return carry(view, buffer, sql, params, null);
    }

    /** The query with everything held carried ahead of it (more than one statement binds: the rest first, alone). */
    private Flux<Map<String, Object>> carry(ContextView view, WriteBuffer buffer, String sql,
        Map<String, BoundValue> params, Duration timeout) {
        return Flux.defer(() -> {
            List<List<WriteBatch.Entry>> chunks = WriteBatch.chunks(buffer.drain(), WriteBatch.MAX_BINDS);
            if (chunks.isEmpty()) {
                return run(view, sql, params, Map.of(), timeout, List.of(), Map.of());
            }
            Mono<Void> before = Flux.fromIterable(chunks.subList(0, chunks.size() - 1))
                .concatMap(chunk -> written(view, chunk)).then();
            WriteBatch.Parts parts = WriteBatch.parts(chunks.getLast(), "jabiz_v");
            return before.thenMany(Flux.defer(() -> run(view, parts.carriedBy(sql), params, parts.values(), timeout,
                parts.rows(), parts.updates())));
        });
    }

    // ================= Transactions =================

    @Override
    public <T> Mono<T> inTransaction(Mono<T> work) {
        return Mono.deferContextual(view -> view.hasKey(stateKey)
            ? tx.transactional(work)
            : transaction(tx, work));
    }

    /**
     * {@code work} in a transaction of {@code operator} with a state of its own and, held for the transaction, a buffer
     * of its own: written when the work ends, or before it fails for a reason not the database's (so that the error of
     * an earlier write is the one reported); an update held and found changing no row is answered, once the
     * transaction is rolled back, as it would have been answered on its own.
     */
    private <T> Mono<T> transaction(TransactionalOperator operator, Mono<T> work) {
        return Mono.defer(() -> {
            TransactionState state = new TransactionState();
            WriteBuffer buffer = batching.acrossTransaction() ? new WriteBuffer(true) : null;
            Mono<T> body = buffer == null ? work : writtenAfter(buffer, work);
            return operator.transactional(body.contextWrite(context -> {
                    Context own = context.put(stateKey, state).delete(zoneKey);
                    return buffer == null ? own.delete(bufferKey) : own.put(bufferKey, buffer);
                }))
                .onErrorResume(R2dbcStorageEngine::notApplied, R2dbcStorageEngine::answerNotApplied);
        });
    }

    /** {@code work}, then what {@code buffer} holds written; written as well before an error not the database's. */
    private <T> Mono<T> writtenAfter(WriteBuffer buffer, Mono<T> work) {
        return work
            .flatMap(result -> flush(buffer).thenReturn(result))
            .switchIfEmpty(Mono.defer(() -> flush(buffer).then(Mono.<T>empty())))
            // A later step failed: what was held before it is written first, so that a conflict of an earlier write
            // is what is reported, as when every write was sent at once (the transaction rolls back either way).
            // After an error of the database the transaction is aborted, and nothing more can be written in it.
            .onErrorResume(error -> fromDatabase(error) || buffer.failed ? Mono.<T>error(error)
                : flush(buffer).then(Mono.<T>error(error)));
    }

    @Override
    public <T> Mono<T> inNewTransaction(Mono<T> work) {
        if (newTx == null) {
            return Mono.error(new UnsupportedOperationException("This engine has no independent transactions"));
        }
        // A transaction of its own knows nothing of the suspended one, writes nothing of its buffer, and has its own
        // lock_timeout (none set: HoldLock bounds its wait itself). Once it has committed, the suspended transaction
        // forgets what it had read: the other may have changed it, without its writes passing through it. What the
        // suspended transaction holds for itself is written first, so that the two meet in the database (a key both
        // insert, a row both lock) as they would have.
        return Mono.deferContextual(view -> {
            WriteBuffer suspended = view.getOrDefault(bufferKey, null);
            Mono<Void> before = suspended != null && suspended.transactional ? flush(suspended) : Mono.empty();
            return before.then(transaction(newTx, work.contextWrite(context -> context
                    .delete(TransactionLockTimeout.class))))
                // Before the next step of the suspended transaction goes on (doFinally would run after it).
                .doOnTerminate(() -> transactionState(view).ifPresent(TransactionState::forgetAll))
                .doOnCancel(() -> transactionState(view).ifPresent(TransactionState::forgetAll));
        });
    }

    @Override
    public <T> Mono<T> inSavepoint(Mono<T> work) {
        // One name does: savepoints nest last in, first out, and PostgreSQL resolves a name to the most recent
        // savepoint of that name, so each RELEASE or ROLLBACK TO reaches the one this call set.
        return Mono.deferContextual(view -> {
            Optional<TransactionState> state = transactionState(view);
            TransactionState known = state.map(TransactionState::snapshot).orElse(null);
            WriteBuffer buffer = view.getOrDefault(bufferKey, null);
            boolean shared = buffer != null && buffer.transactional;
            // Held for the transaction, the work's writes go to the same buffer, empty when it starts and written
            // before the savepoint is released; within one commit (16p-1), the work collects nothing of the commit's.
            Mono<T> inside = shared
                ? writtenAfter(buffer, work)
                : work.contextWrite(context -> context.delete(bufferKey).delete(zoneKey));
            // Written before, outside the part a failure rolls back: a conflict there is not this savepoint's.
            return flush(view).then(Mono.defer(() -> statement(view, "SAVEPOINT " + SAVEPOINT)
                .then(inside)
                .flatMap(result -> statement(view, "RELEASE SAVEPOINT " + SAVEPOINT).thenReturn(result))
                .switchIfEmpty(Mono.defer(() -> statement(view, "RELEASE SAVEPOINT " + SAVEPOINT)
                    .then(Mono.<T>empty())))
                .onErrorResume(error -> {
                    state.ifPresent(current -> current.restore(known));
                    if (shared) {
                        // Rolled back: what the work still held is undone with what it wrote.
                        buffer.drain();
                    }
                    return statement(view, "ROLLBACK TO SAVEPOINT " + SAVEPOINT)
                        .then(Mono.fromRunnable(() -> {
                            if (shared) {
                                buffer.failed = false;
                            }
                        }))
                        .then(statement(view, "RELEASE SAVEPOINT " + SAVEPOINT))
                        // The transaction goes on, so an update that changed no row is answered here.
                        .then(notApplied(error) ? answerNotApplied(error) : Mono.<T>error(error));
                })));
        });
    }

    private static final String SAVEPOINT = "jabiz_unit";

    private Mono<Void> statement(ContextView view, String sql) {
        return Mono.defer(() -> {
            counted(view);
            return db.sql(sql).then();
        });
    }

    // ================= Held writes =================

    @Override
    public <T> Mono<T> withWriteBatch(Mono<T> work) {
        return Mono.deferContextual(view -> {
            if (view.hasKey(zoneKey) || WriteBatching.isStatementByStatement(view)) {
                // Within an outer batch, whose end writes these rows too; or one statement at a time, as asked.
                return work;
            }
            WriteBuffer transaction = view.getOrDefault(bufferKey, null);
            if (transaction != null && transaction.transactional) {
                // Held for the transaction (16p-2, T9): the work's writes join it.
                return work.contextWrite(context -> context.put(zoneKey, Boolean.TRUE));
            }
            WriteBuffer buffer = new WriteBuffer(false);
            return work
                .flatMap(result -> flush(buffer).thenReturn(result))
                .switchIfEmpty(Mono.defer(() -> flush(buffer).then(Mono.<T>empty())))
                // A later write failed: the rows collected before it are written first, so that a conflict of an
                // earlier write is what is reported, as when every write was sent at once (the transaction rolls
                // back either way).
                .onErrorResume(error -> fromDatabase(error) || buffer.failed ? Mono.<T>error(error)
                    : flush(buffer).then(Mono.<T>error(error)))
                .contextWrite(context -> context.put(bufferKey, buffer).put(zoneKey, Boolean.TRUE));
        });
    }

    @Override
    public Mono<Void> flushWrites() {
        return Mono.deferContextual(this::flush);
    }

    /** The buffer the writes made in {@code view} are held in; null when they are sent as they come. */
    private WriteBuffer held(ContextView view) {
        return view.hasKey(zoneKey) ? view.getOrDefault(bufferKey, null) : null;
    }

    private static String origin(ContextView view) {
        return view.getOrEmpty(WriteOrigin.class).map(origin -> ((WriteOrigin) origin).description()).orElse(null);
    }

    /** Marks the buffer of {@code view}: a statement failed, so the transaction is aborted until rolled back. */
    private void failed(ContextView view) {
        WriteBuffer buffer = view.getOrDefault(bufferKey, null);
        if (buffer != null) {
            buffer.failed = true;
        }
    }

    /**
     * The writes held so far, in the order they were made.
     *
     * @see WriteBatch
     */
    private static final class WriteBuffer {
        /** Held for the whole transaction (T9), not one commit. */
        final boolean transactional;
        private final List<WriteBatch.Entry> entries = new ArrayList<>();
        /** Names (lower case) of the tables written to and of the views over them. */
        private final Set<String> names = new LinkedHashSet<>();
        /** Tables (lower case) written to, by inserts or updates, and updated. */
        private final Set<String> tables = new HashSet<>();
        private final Set<String> updated = new HashSet<>();
        /** A statement failed since the transaction (or savepoint) began: nothing more can be written. */
        volatile boolean failed;

        WriteBuffer(boolean transactional) {
            this.transactional = transactional;
        }

        synchronized void add(WriteBatch.Entry entry, Set<String> names) {
            entries.add(entry);
            String table = entry.table().toLowerCase(Locale.ROOT);
            tables.add(table);
            this.names.addAll(names);
            if (entry instanceof WriteBatch.Update update) {
                updated.add(table);
                for (Insert dependent : update.dependents()) {
                    tables.add(dependent.table().toLowerCase(Locale.ROOT));
                    this.names.add(dependent.table().toLowerCase(Locale.ROOT));
                }
            }
        }

        synchronized List<WriteBatch.Entry> drain() {
            List<WriteBatch.Entry> drained = List.copyOf(entries);
            entries.clear();
            names.clear();
            tables.clear();
            updated.clear();
            return drained;
        }

        synchronized boolean isEmpty() {
            return entries.isEmpty();
        }

        synchronized boolean holdsUpdates() {
            return !updated.isEmpty();
        }

        synchronized boolean updates(String table) {
            return updated.contains(table.toLowerCase(Locale.ROOT));
        }

        /** Whether one of the texts (statements, or table names) names a table written to, or a view over one. */
        synchronized boolean touches(Collection<String> texts) {
            for (String name : names) {
                Pattern pattern = TABLE_NAMES.computeIfAbsent(name,
                    t -> Pattern.compile("(?i)(?<![\\w$])" + Pattern.quote(t) + "(?![\\w$])"));
                for (String text : texts) {
                    if (pattern.matcher(text).find()) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Whether one of {@code tables} (or a view among them) is written to. */
        synchronized boolean touchesTables(Collection<String> tables) {
            for (String table : tables) {
                if (names.contains(table.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final Map<String, Pattern> TABLE_NAMES = new ConcurrentHashMap<>();

    /**
     * The platform's views over tables whose rows may be held: a statement naming one reads the table. Views of the
     * application are learnt by the startup check ({@link WriteBatching#viewsOver}).
     */
    private static final Map<String, Set<String>> VIEWS = Map.of("sys_dict_item_version", Set.of("sys_dict_item"));

    /** The names a statement reading {@code table} may use: the table and the views known to read it. */
    private Set<String> names(String table) {
        String lower = table.toLowerCase(Locale.ROOT);
        Set<String> names = new LinkedHashSet<>();
        names.add(lower);
        names.addAll(VIEWS.getOrDefault(lower, Set.of()));
        names.addAll(batching.viewsOver(lower));
        return names;
    }

    /**
     * Writes the collected rows before a statement that reads or writes one of their tables (judged by its text, so a
     * name that only looks like a table's writes them as well, which costs a statement and no correctness).
     */
    private Mono<Void> flushIfTouching(ContextView view, Collection<String> texts) {
        WriteBuffer buffer = view.getOrDefault(bufferKey, null);
        return buffer != null && buffer.touches(texts) ? flush(buffer) : Mono.empty();
    }

    private Mono<Void> flush(ContextView view) {
        WriteBuffer buffer = view.getOrDefault(bufferKey, null);
        return buffer == null ? Mono.empty() : flush(buffer);
    }

    private Mono<Void> flush(WriteBuffer buffer) {
        return Mono.deferContextual(view -> {
            List<WriteBatch.Entry> entries = buffer.drain();
            if (entries.isEmpty()) {
                return Mono.empty();
            }
            return Flux.fromIterable(WriteBatch.chunks(entries, WriteBatch.MAX_BINDS))
                .concatMap(chunk -> written(view, chunk))
                .then();
        });
    }

    /** One statement of held writes on its own: rows alone as phase 16p-1 renders them, else with the updates. */
    private Mono<Void> written(ContextView view, List<WriteBatch.Entry> chunk) {
        if (chunk.stream().allMatch(WriteBatch.Row.class::isInstance)) {
            return execute(view, WriteBatch.render(chunk.stream().map(WriteBatch.Row.class::cast).toList()));
        }
        WriteBatch.Parts parts = WriteBatch.parts(chunk, "v");
        return run(view, parts.alone(), Map.of(), parts.values(), null, parts.rows(), parts.updates()).then();
    }

    /**
     * Whether the database refused a statement: the transaction is aborted then, and nothing more can be written in it.
     */
    private static boolean fromDatabase(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof io.r2dbc.spi.R2dbcException || cause instanceof org.springframework.dao.DataAccessException
                || cause instanceof UniqueKeyViolationException || cause instanceof AppendOnlyViolationException
                || cause instanceof LockNotAvailableException || cause instanceof HeldUpdateNotApplied) {
                return true;
            }
        }
        return false;
    }

    /** Executes rendered inserts one after another; a conflict is mapped back to the write of the row that caused it. */
    private Mono<Void> execute(ContextView view, List<WriteBatch.Statement> statements) {
        return Flux.fromIterable(statements).concatMap(statement -> Mono.defer(() -> {
            counted(view);
            DatabaseClient.GenericExecuteSpec spec = db.sql(statement.sql());
            for (int i = 0; i < statement.values().size(); i++) {
                spec = spec.bind("v" + i, toStorage(statement.values().get(i)));
            }
            return spec.then()
                .onErrorMap(R2dbcStorageEngine::translate)
                .onErrorMap(error -> heldError(error, statement.rows(), Map.of(), statement.rows().size() > 1))
                .doOnError(error -> failed(view));
        })).then();
    }

    private static final Pattern DUPLICATE_KEY = Pattern.compile("Key \\((.+)\\)=\\((.*)\\) already exists");

    /**
     * The error of the write whose row broke the unique index: the row of the reported table whose values are the
     * reported key, else the first row of that table.
     */
    static Throwable mapped(UniqueKeyViolationException conflict, List<WriteBatch.Row> rows) {
        List<WriteBatch.Row> candidates = rows.stream()
            .filter(row -> conflict.table() == null || row.table().equalsIgnoreCase(conflict.table()))
            .toList();
        if (candidates.isEmpty()) {
            // A table none of the rows went to: no write to blame; the caller maps it as it would any conflict.
            return conflict;
        }
        WriteBatch.Row culprit = culprit(conflict, candidates);
        return culprit.conflicts() == null ? conflict
            : culprit.conflicts().map(conflict, culprit.table(), culprit.record());
    }

    private static WriteBatch.Row culprit(UniqueKeyViolationException conflict, List<WriteBatch.Row> candidates) {
        Matcher key = conflict.detail() == null ? null : DUPLICATE_KEY.matcher(conflict.detail());
        if (key != null && key.find() && candidates.size() > 1) {
            List<String> columns = List.of(key.group(1).split(", "));
            for (WriteBatch.Row row : candidates) {
                Map<String, Object> byColumn = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                row.record().forEach((column, value) -> byColumn.put(column, value));
                String values = String.join(", ", columns.stream().map(c -> String.valueOf(byColumn.get(c))).toList());
                if (values.equals(key.group(2))) {
                    return row;
                }
            }
        }
        return candidates.getFirst();
    }

    /**
     * The error of a statement that wrote held writes, as the write that caused it reports it: a conflict of a row as
     * that row's write; an update that changed no row as {@link HeldUpdateNotApplied}; anything else unchanged.
     */
    static Throwable heldError(Throwable error, List<WriteBatch.Row> rows, Map<Integer, WriteBatch.Update> updates,
        boolean held) {
        if (error instanceof UniqueKeyViolationException conflict && !rows.isEmpty()) {
            if (held) {
                // The step that failed may be a later one, which carried the write (P-7): the log names the write's.
                List<WriteBatch.Row> candidates = rows.stream()
                    .filter(row -> conflict.table() == null || row.table().equalsIgnoreCase(conflict.table())).toList();
                if (!candidates.isEmpty() && culprit(conflict, candidates).origin() != null) {
                    log.warn("A write held back from {} conflicts as it is written ({}): {}",
                        culprit(conflict, candidates).origin(), conflict.table(), conflict.getMessage());
                }
            }
            Throwable mapped = mapped(conflict, rows);
            return held ? HeldWriteErrors.mark(mapped) : mapped;
        }
        if (!updates.isEmpty()) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof PostgresqlException pg && INVALID_TEXT.equals(pg.getErrorDetails().getCode())) {
                    // Exactly the cast of the term, of nothing else the statement may have converted.
                    Matcher stale = STALE_TERM.matcher(String.valueOf(pg.getErrorDetails().getMessage()));
                    if (stale.matches() && pg.getErrorDetails().getRoutine().map(STALE_ROUTINES::contains)
                        .orElse(true)) {
                        WriteBatch.Update update = updates.get(Integer.parseInt(stale.group(1)));
                        if (update != null) {
                            if (update.origin() != null) {
                                log.warn("An update held back from {} changed no row as it was written ({} [{}])",
                                    update.origin(), update.table(), update.id());
                            }
                            return new HeldUpdateNotApplied(update, error);
                        }
                    }
                }
            }
        }
        return error;
    }

    private static final Pattern STALE_TERM = Pattern.compile("invalid input syntax for type bigint: \""
        + Pattern.quote(WriteBatch.STALE) + "(\\d+)\"");

    /** Where PostgreSQL 16 reports a text that is no {@code bigint} ({@code int8in}); other versions may differ. */
    private static final Set<String> STALE_ROUTINES = Set.of("pg_strtoint64_safe", "pg_strtoint64", "scanint8",
        "int8in");

    /** An update held back changed no row when it was written: the transaction is aborted and is to be answered. */
    static final class HeldUpdateNotApplied extends RuntimeException {
        private final transient WriteBatch.Update update;

        HeldUpdateNotApplied(WriteBatch.Update update, Throwable cause) {
            super("Update of " + update.table() + " [" + update.id() + "] at version " + update.expectedVersion()
                + " changed no row", cause, false, false);
            this.update = update;
        }

        WriteBatch.Update update() {
            return update;
        }
    }

    private static boolean notApplied(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HeldUpdateNotApplied) {
                return true;
            }
        }
        return false;
    }

    /** The error the update reports as changing no row, found once its transaction (or savepoint) is rolled back. */
    private static <T> Mono<T> answerNotApplied(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HeldUpdateNotApplied held) {
                return held.update().notApplied().get()
                    .switchIfEmpty(Mono.error(error))
                    .flatMap(answer -> Mono.error(HeldWriteErrors.mark(answer)));
            }
        }
        return Mono.error(error);
    }

    // ================= Statements =================

    /** Counts a statement against the transaction it is sent in (the observation of a process). */
    private void counted(ContextView view) {
        transactionState(view).ifPresent(TransactionState::countStatement);
    }

    /**
     * A statement and its rows: with {@code timeout}, a query bounded by it for its whole result, not the gap between
     * rows; without, a statement of the platform, as its rows come. Outside a transaction (the query and dataset APIs,
     * exports) a bounded query runs in one of its own with {@code SET LOCAL statement_timeout}, so that the database
     * stops it (phase 14q): stopped at the client only, it would go on running, holding its connection, after its caller
     * gave up, and an overloaded server would fall further behind with every timeout. The client's deadline, a moment
     * later, stays as the backstop. Inside a transaction (a process) the client's deadline alone applies: the
     * transaction's rollback then waits for the statement, and its connection is clean afterwards.
     *
     * @param held    values of held writes the statement carries, by name
     * @param rows    rows of held writes it carries, to report their conflicts as theirs
     * @param updates updates it carries, by the number of their part
     */
    private Flux<Map<String, Object>> run(ContextView view, String sql, Map<String, BoundValue> params,
        Map<String, Object> held, Duration timeout, List<WriteBatch.Row> rows, Map<Integer, WriteBatch.Update> updates) {
        return send(view, sql, params, held, timeout, rows, updates);
    }

    private Flux<Map<String, Object>> send(ContextView view, String sql, Map<String, BoundValue> params,
        Map<String, Object> held, Duration timeout, List<WriteBatch.Row> rows,
        Map<Integer, WriteBatch.Update> updates) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(sql);
        for (Map.Entry<String, BoundValue> e : params.entrySet()) {
            BoundValue bound = e.getValue();
            spec = bound.value() == null
                ? spec.bindNull(e.getKey(), storageType(bound.type()))
                : spec.bind(e.getKey(), toStorage(bound.value()));
        }
        for (Map.Entry<String, Object> e : held.entrySet()) {
            spec = spec.bind(e.getKey(), toStorage(e.getValue()));
        }
        Flux<Map<String, Object>> result;
        if (timeout == null) {
            DatabaseClient.GenericExecuteSpec platform = spec;
            result = Flux.defer(() -> {
                counted(view);
                return platform.map(R2dbcStorageEngine::toMap).all();
            }).onErrorMap(R2dbcStorageEngine::translate);
        } else {
            Mono<List<Map<String, Object>>> all = spec.map(R2dbcStorageEngine::toMap).all().collectList();
            result = inTransaction()
                .flatMap(active -> {
                    if (active) {
                        counted(view);
                        return all.timeout(timeout);
                    }
                    return tx.transactional(statementTimeout(timeout).then(all)).timeout(timeout.plus(BACKSTOP));
                })
                .onErrorMap(TimeoutException.class, ex -> new QueryTimeoutException("Query exceeded " + timeout, ex))
                .onErrorMap(R2dbcStorageEngine::canceled, ex -> new QueryTimeoutException("Query exceeded " + timeout, ex))
                .onErrorMap(R2dbcStorageEngine::translate)
                .flatMapMany(Flux::fromIterable);
        }
        if (!rows.isEmpty() || !updates.isEmpty()) {
            result = result.onErrorMap(error -> heldError(error, rows, updates, true));
        }
        return result.doOnError(error -> failed(view));
    }

    /** How long the client waits beyond a query's own limit, for the database to stop it first. */
    static final Duration BACKSTOP = Duration.ofSeconds(1);

    /** SQLSTATE of query_canceled: here, a statement past its statement_timeout. */
    private static final String QUERY_CANCELED = "57014";

    private Mono<Void> statementTimeout(Duration timeout) {
        return db.sql("SELECT set_config('statement_timeout', :limit, true) AS t")
            .bind("limit", Math.max(1, timeout.toMillis()) + "ms").then();
    }

    private static Mono<Boolean> inTransaction() {
        return TransactionSynchronizationManager.forCurrentTransaction()
            .map(TransactionSynchronizationManager::isActualTransactionActive)
            .onErrorResume(NoTransactionException.class, e -> Mono.just(false));
    }

    private static boolean canceled(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof PostgresqlException pg && QUERY_CANCELED.equals(pg.getErrorDetails().getCode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unique index violations become {@link UniqueKeyViolationException} carrying the index name; refusals of the
     * append-only guard become {@link AppendOnlyViolationException}; a deadlock or a lock not obtained in time becomes
     * {@link LockNotAvailableException} (409: the request may be retried; a busy item of a job).
     */
    private static Throwable translate(Throwable error) {
        if (error instanceof UniqueKeyViolationException || error instanceof AppendOnlyViolationException
            || error instanceof LockNotAvailableException || error instanceof HeldUpdateNotApplied) {
            return error;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof PostgresqlException pg) {
                String code = pg.getErrorDetails().getCode();
                if (UNIQUE_VIOLATION.equals(code)) {
                    return new UniqueKeyViolationException(pg.getErrorDetails().getConstraintName().orElse(null),
                        pg.getErrorDetails().getTableName().orElse(null), pg.getErrorDetails().getDetail().orElse(null),
                        error);
                }
                if (AppendOnlyViolationException.SQL_STATE.equals(code)) {
                    return new AppendOnlyViolationException(pg.getErrorDetails().getMessage(), error);
                }
                if (LOCK_FAILURES.contains(code)) {
                    return new LockNotAvailableException(code, error);
                }
            }
        }
        return error;
    }

    /** Type of a NULL: JSON for values stored as JSON. */
    private static Class<?> storageType(Class<?> type) {
        return Map.class.isAssignableFrom(type) || type == JsonText.class ? Json.class : type;
    }

    /** JSON values are sent as JSON; everything else as it is. */
    private static Object toStorage(Object value) {
        if (value instanceof JsonText text) {
            return Json.of(text.json());
        }
        if (value instanceof Map<?, ?> map) {
            return Json.of(JSON.writeValueAsString(map));
        }
        return value;
    }

    /**
     * A row as a JSON object for {@code jsonb_populate_record}: every column it sets, {@link StorageEngine#NULL} as
     * JSON null, JSON values as they are, times and keys as their ISO text, arrays as arrays.
     */
    static String rowJson(Map<String, Object> record) {
        ObjectNode node = NODES.objectNode();
        record.forEach((column, value) -> {
            if (value != null) {
                node.set(SqlIdentifiers.require(column).toLowerCase(Locale.ROOT), jsonValue(value));
            }
        });
        return JSON.writeValueAsString(node);
    }

    private static JsonNode jsonValue(Object value) {
        if (value == StorageEngine.NULL) {
            return NODES.nullNode();
        }
        return switch (value) {
            case JsonText text -> JSON.readTree(text.json());
            case Map<?, ?> map -> JSON.valueToTree(map);
            case String text -> NODES.textNode(text);
            case Boolean flag -> NODES.booleanNode(flag);
            case BigDecimal decimal -> NODES.numberNode(decimal);
            case Long number -> NODES.numberNode(number);
            case Integer number -> NODES.numberNode(number);
            case UUID uuid -> NODES.textNode(uuid.toString());
            case TemporalAccessor time -> NODES.textNode(time.toString());
            case Object[] array -> {
                ArrayNode items = NODES.arrayNode();
                for (Object item : array) {
                    items.add(item == null ? NODES.nullNode() : jsonValue(item));
                }
                yield items;
            }
            case Collection<?> list -> {
                ArrayNode items = NODES.arrayNode();
                for (Object item : list) {
                    items.add(item == null ? NODES.nullNode() : jsonValue(item));
                }
                yield items;
            }
            default -> throw new IllegalArgumentException("Cannot pass a " + value.getClass().getName()
                + " as a column value in JSON");
        };
    }

    private static Map<String, Object> toMap(Row row, RowMetadata metadata) {
        Map<String, Object> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int index = 0;
        for (ColumnMetadata column : metadata.getColumnMetadatas()) {
            Object value = row.get(index++);
            result.put(column.getName(), value instanceof Json json ? json.asString() : value);
        }
        return result;
    }
}

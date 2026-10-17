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
import org.springframework.dao.QueryTimeoutException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Storage engine on top of Spring's reactive {@link DatabaseClient}. All statements are
 * parameterized; identifiers are validated before they are placed in SQL text.
 * Transactions are propagated through the Reactor context by the {@link TransactionalOperator}.
 *
 * <p>Within a transaction it keeps the {@link TransactionState} of that transaction in the Reactor context, and within
 * {@link #withWriteBatch} the rows inserted so far, which it writes before any statement that reads or writes one of
 * their tables (decision D46 rule 1).
 */
public final class R2dbcStorageEngine implements StorageEngine {

    /** SQLSTATE of unique_violation. */
    private static final String UNIQUE_VIOLATION = "23505";
    /** The database broke a deadlock by failing this statement, or gave up waiting for a lock (lock_timeout). */
    private static final java.util.Set<String> LOCK_FAILURES = java.util.Set.of("40P01", "55P03");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final DatabaseClient db;
    private final TransactionalOperator tx;
    private final TransactionalOperator newTx;

    /** Keys of this engine's transaction state and write buffer in the Reactor context. */
    private final Object stateKey = new ContextKey("transaction state");
    private final Object bufferKey = new ContextKey("write buffer");

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
        this.db = Objects.requireNonNull(db);
        this.tx = Objects.requireNonNull(tx);
        this.newTx = newTx;
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
                view.getOrDefault(InsertConflicts.class, null));
            if (row.record().values().stream().allMatch(Objects::isNull)) {
                return Mono.error(new IllegalArgumentException("Insert into " + table + " has no values"));
            }
            WriteBuffer buffer = view.getOrDefault(bufferKey, null);
            if (buffer != null) {
                buffer.add(row);
                return Mono.empty();
            }
            return execute(view, WriteBatch.render(List.of(row)));
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
        return Mono.deferContextual(view -> {
            if (!dependents.isEmpty() && WriteBatching.isStatementByStatement(view)) {
                // One statement at a time, as before phase 16p (the test hook): the update, then its rows.
                return StorageEngine.super.casUpdate(table, pkColumn, id, expectedVersion, versionColumn, updates,
                    dependents);
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
            return flushIfTouching(view, touched)
                .then(Mono.defer(() -> {
                    counted(view);
                    return applied;
                }))
                .onErrorMap(R2dbcStorageEngine::translate);
        });
    }

    @Override
    public Mono<Boolean> delete(String table, String pkColumn, Object id, String versionColumn, long expectedVersion) {
        return Mono.deferContextual(view -> {
            String sql = "DELETE FROM " + SqlIdentifiers.require(table)
                         + " WHERE " + SqlIdentifiers.require(pkColumn) + " = :pk"
                         + " AND " + SqlIdentifiers.require(versionColumn) + " = :expectedVersion";
            return flushIfTouching(view, List.of(table)).then(Mono.defer(() -> {
                counted(view);
                return db.sql(sql)
                    .bind("pk", id)
                    .bind("expectedVersion", expectedVersion)
                    .fetch().rowsUpdated()
                    .map(count -> count > 0);
            })).onErrorMap(R2dbcStorageEngine::translate);
        });
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
            return flushIfTouching(view, List.of(sql)).thenMany(run(view, sql, plan.bindParams(), plan.timeout()));
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
            Duration limit = timeout;
            return flushIfTouching(view, texts)
                .thenMany(Flux.defer(() -> run(view, sql, params, limit)))
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
            return flushIfTouching(view, List.of(sql.toString()))
                .thenMany(run(view, sql.toString(), plan.bindParams(), plan.timeout()))
                .next()
                .map(row -> ((Number) row.get("total")).longValue());
        });
    }

    @Override
    public Flux<Map<String, Object>> select(String sql, Map<String, BoundValue> params) {
        return Flux.deferContextual(view -> flushIfTouching(view, List.of(sql)).thenMany(Flux.defer(() -> {
            counted(view);
            DatabaseClient.GenericExecuteSpec spec = db.sql(sql);
            for (Map.Entry<String, BoundValue> e : params.entrySet()) {
                BoundValue bound = e.getValue();
                spec = bound.value() == null
                    ? spec.bindNull(e.getKey(), storageType(bound.type()))
                    : spec.bind(e.getKey(), toStorage(bound.value()));
            }
            return spec.map(R2dbcStorageEngine::toMap).all();
        })).onErrorMap(R2dbcStorageEngine::translate));
    }

    @Override
    public Flux<Map<String, Object>> executeRawQuery(RawQueryPlan plan) {
        // Run as given: paging is part of the statement (docs/design/05-sql-template.md section 5).
        return Flux.deferContextual(view -> flushIfTouching(view, List.of(plan.sql()))
            .thenMany(run(view, plan.sql(), plan.bindParams(), plan.timeout())));
    }

    // ================= Transactions =================

    @Override
    public <T> Mono<T> inTransaction(Mono<T> work) {
        return Mono.deferContextual(view -> view.hasKey(stateKey)
            ? tx.transactional(work)
            : tx.transactional(work.contextWrite(context -> context.put(stateKey, new TransactionState()))));
    }

    @Override
    public <T> Mono<T> inNewTransaction(Mono<T> work) {
        if (newTx == null) {
            return Mono.error(new UnsupportedOperationException("This engine has no independent transactions"));
        }
        // A transaction of its own knows nothing of the suspended one, writes nothing of its buffer, and has its own
        // lock_timeout (none set: HoldLock bounds its wait itself). Once it has committed, the suspended transaction
        // forgets what it had read: the other may have changed it, without its writes passing through it.
        return Mono.deferContextual(view -> newTx.transactional(work.contextWrite(context -> context
                .delete(bufferKey).delete(TransactionLockTimeout.class).put(stateKey, new TransactionState())))
            // Before the next step of the suspended transaction goes on (doFinally would run after it).
            .doOnTerminate(() -> transactionState(view).ifPresent(TransactionState::forgetAll))
            .doOnCancel(() -> transactionState(view).ifPresent(TransactionState::forgetAll)));
    }

    @Override
    public <T> Mono<T> inSavepoint(Mono<T> work) {
        // One name does: savepoints nest last in, first out, and PostgreSQL resolves a name to the most recent
        // savepoint of that name, so each RELEASE or ROLLBACK TO reaches the one this call set.
        return Mono.deferContextual(view -> {
            Optional<TransactionState> state = transactionState(view);
            TransactionState known = state.map(TransactionState::snapshot).orElse(null);
            // What was collected before belongs before the savepoint; what the work collects, within it.
            Mono<T> inside = work.contextWrite(context -> context.delete(bufferKey));
            // Written before, outside the part a failure rolls back: a conflict there is not this savepoint's.
            return flush(view).then(Mono.defer(() -> statement(view, "SAVEPOINT " + SAVEPOINT)
                .then(inside)
                .flatMap(result -> statement(view, "RELEASE SAVEPOINT " + SAVEPOINT).thenReturn(result))
                .switchIfEmpty(Mono.defer(() -> statement(view, "RELEASE SAVEPOINT " + SAVEPOINT)
                    .then(Mono.<T>empty())))
                .onErrorResume(error -> {
                    state.ifPresent(current -> current.restore(known));
                    return statement(view, "ROLLBACK TO SAVEPOINT " + SAVEPOINT)
                        .then(statement(view, "RELEASE SAVEPOINT " + SAVEPOINT))
                        .then(Mono.error(error));
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

    // ================= Write batches =================

    @Override
    public <T> Mono<T> withWriteBatch(Mono<T> work) {
        return Mono.deferContextual(view -> {
            if (view.hasKey(bufferKey) || WriteBatching.isStatementByStatement(view)) {
                // Within an outer batch, whose end writes these rows too; or one statement at a time, as asked.
                return work;
            }
            WriteBuffer buffer = new WriteBuffer();
            return work
                .flatMap(result -> flush(buffer).thenReturn(result))
                .switchIfEmpty(Mono.defer(() -> flush(buffer).then(Mono.<T>empty())))
                // A later write failed: the rows collected before it are written first, so that a conflict of an
                // earlier write is what is reported, as when every write was sent at once (the transaction rolls
                // back either way).
                .onErrorResume(error -> fromDatabase(error) ? Mono.<T>error(error)
                    : flush(buffer).then(Mono.<T>error(error)))
                .contextWrite(context -> context.put(bufferKey, buffer));
        });
    }

    /** The rows collected so far in this transaction's batch, in the order they were inserted. */
    private static final class WriteBuffer {
        private final List<WriteBatch.Row> rows = new ArrayList<>();
        private final Set<String> tables = new LinkedHashSet<>();

        synchronized void add(WriteBatch.Row row) {
            rows.add(row);
            String table = row.table().toLowerCase(Locale.ROOT);
            tables.add(table);
            tables.addAll(VIEWS.getOrDefault(table, Set.of()));
        }

        synchronized List<WriteBatch.Row> drain() {
            List<WriteBatch.Row> drained = List.copyOf(rows);
            rows.clear();
            tables.clear();
            return drained;
        }

        /** Whether one of the texts (statements, or table names) names a table rows are collected for. */
        synchronized boolean touches(Collection<String> texts) {
            for (String table : tables) {
                Pattern name = TABLE_NAMES.computeIfAbsent(table,
                    t -> Pattern.compile("(?i)(?<![\\w$])" + Pattern.quote(t) + "(?![\\w$])"));
                for (String text : texts) {
                    if (name.matcher(text).find()) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static final Map<String, Pattern> TABLE_NAMES = new ConcurrentHashMap<>();

    /**
     * The platform's views over tables whose rows may be collected: a statement naming one reads the table. Within a
     * batch (one commit) only the platform's own statements run, and these are the views they may read.
     */
    private static final Map<String, Set<String>> VIEWS = Map.of("sys_dict_item_version", Set.of("sys_dict_item"));

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
            List<WriteBatch.Row> rows = buffer.drain();
            return rows.isEmpty() ? Mono.empty() : execute(view, WriteBatch.render(rows));
        });
    }

    /**
     * Whether the database refused a statement: the transaction is aborted then, and nothing more can be written in it.
     */
    private static boolean fromDatabase(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof io.r2dbc.spi.R2dbcException || cause instanceof org.springframework.dao.DataAccessException
                || cause instanceof UniqueKeyViolationException || cause instanceof AppendOnlyViolationException
                || cause instanceof LockNotAvailableException) {
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
                .onErrorMap(UniqueKeyViolationException.class, conflict -> mapped(conflict, statement.rows()));
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
        WriteBatch.Row culprit = candidates.getFirst();
        Matcher key = conflict.detail() == null ? null : DUPLICATE_KEY.matcher(conflict.detail());
        if (key != null && key.find() && candidates.size() > 1) {
            List<String> columns = List.of(key.group(1).split(", "));
            for (WriteBatch.Row row : candidates) {
                Map<String, Object> byColumn = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                row.record().forEach((column, value) -> byColumn.put(column, value));
                String values = String.join(", ", columns.stream().map(c -> String.valueOf(byColumn.get(c))).toList());
                if (values.equals(key.group(2))) {
                    culprit = row;
                    break;
                }
            }
        }
        return culprit.conflicts() == null ? conflict
            : culprit.conflicts().map(conflict, culprit.table(), culprit.record());
    }

    // ================= Statements =================

    /** Counts a statement against the transaction it is sent in (the observation of a process). */
    private void counted(ContextView view) {
        transactionState(view).ifPresent(TransactionState::countStatement);
    }

    /**
     * A query bounded by {@code timeout} for its whole result, not the gap between rows. Outside a transaction (the
     * query and dataset APIs, exports) it runs in one of its own with {@code SET LOCAL statement_timeout}, so that the
     * database stops it (phase 14q): stopped at the client only, it would go on running, holding its connection,
     * after its caller gave up, and an overloaded server would fall further behind with every timeout. The client's
     * deadline, a moment later, stays as the backstop. Inside a transaction (a process) the client's deadline alone
     * applies: the transaction's rollback then waits for the statement, and its connection is clean afterwards.
     */
    private Flux<Map<String, Object>> run(ContextView view, String sql, Map<String, BoundValue> params,
        Duration timeout) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(sql);
        for (Map.Entry<String, BoundValue> e : params.entrySet()) {
            BoundValue bound = e.getValue();
            spec = bound.value() == null
                ? spec.bindNull(e.getKey(), storageType(bound.type()))
                : spec.bind(e.getKey(), toStorage(bound.value()));
        }
        Mono<List<Map<String, Object>>> rows = spec.map(R2dbcStorageEngine::toMap).all().collectList();
        return inTransaction()
            .flatMap(active -> {
                if (active) {
                    counted(view);
                    return rows.timeout(timeout);
                }
                return tx.transactional(statementTimeout(timeout).then(rows)).timeout(timeout.plus(BACKSTOP));
            })
            .onErrorMap(TimeoutException.class, ex -> new QueryTimeoutException("Query exceeded " + timeout, ex))
            .onErrorMap(R2dbcStorageEngine::canceled, ex -> new QueryTimeoutException("Query exceeded " + timeout, ex))
            .onErrorMap(R2dbcStorageEngine::translate)
            .flatMapMany(Flux::fromIterable);
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
            || error instanceof LockNotAvailableException) {
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

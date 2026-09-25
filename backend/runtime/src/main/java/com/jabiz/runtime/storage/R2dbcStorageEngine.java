package com.jabiz.runtime.storage;

import com.jabiz.query.BoundValue;
import com.jabiz.query.PhysicalQueryPlan;
import com.jabiz.query.RawQueryPlan;
import com.jabiz.query.SqlIdentifiers;
import io.r2dbc.postgresql.api.PostgresqlException;
import io.r2dbc.spi.ColumnMetadata;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;

/**
 * Storage engine on top of Spring's reactive {@link DatabaseClient}. All statements are
 * parameterized; identifiers are validated before they are placed in SQL text.
 * Transactions are propagated through the Reactor context by the {@link TransactionalOperator}.
 */
public final class R2dbcStorageEngine implements StorageEngine {

    /** SQLSTATE of unique_violation. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final DatabaseClient db;
    private final TransactionalOperator tx;

    public R2dbcStorageEngine(DatabaseClient db, TransactionalOperator tx) {
        this.db = Objects.requireNonNull(db);
        this.tx = Objects.requireNonNull(tx);
    }

    @Override
    public Mono<Void> insert(String table, Map<String, Object> record) {
        return Mono.defer(() -> {
            List<String> columns = new ArrayList<>();
            List<String> markers = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            for (Map.Entry<String, Object> e : record.entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                columns.add(SqlIdentifiers.require(e.getKey()));
                markers.add(":v" + values.size());
                values.add(e.getValue());
            }
            if (columns.isEmpty()) {
                return Mono.error(new IllegalArgumentException("Insert into " + table + " has no values"));
            }
            String sql = "INSERT INTO " + SqlIdentifiers.require(table)
                         + " (" + String.join(", ", columns) + ") VALUES (" + String.join(", ", markers) + ")";

            DatabaseClient.GenericExecuteSpec spec = db.sql(sql);
            for (int i = 0; i < values.size(); i++) {
                spec = spec.bind("v" + i, values.get(i));
            }
            return spec.fetch().rowsUpdated().then().onErrorMap(R2dbcStorageEngine::translate);
        });
    }

    @Override
    public Mono<Boolean> casUpdate(String table, String pkColumn, Object id, long expectedVersion,
        String versionColumn, Map<String, Object> updates) {
        return Mono.defer(() -> {
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
                    values.add(e.getValue());
                }
            }
            assignments.add(versionCol + " = " + versionCol + " + 1");

            String sql = "UPDATE " + SqlIdentifiers.require(table)
                         + " SET " + String.join(", ", assignments)
                         + " WHERE " + SqlIdentifiers.require(pkColumn) + " = :pk"
                         + " AND " + versionCol + " = :expectedVersion";

            DatabaseClient.GenericExecuteSpec spec = db.sql(sql);
            for (int i = 0; i < values.size(); i++) {
                spec = spec.bind("u" + i, values.get(i));
            }
            spec = spec.bind("pk", id).bind("expectedVersion", expectedVersion);
            return spec.fetch().rowsUpdated().map(count -> count > 0).onErrorMap(R2dbcStorageEngine::translate);
        });
    }

    @Override
    public Mono<Boolean> delete(String table, String pkColumn, Object id, String versionColumn, long expectedVersion) {
        return Mono.defer(() -> {
            String sql = "DELETE FROM " + SqlIdentifiers.require(table)
                         + " WHERE " + SqlIdentifiers.require(pkColumn) + " = :pk"
                         + " AND " + SqlIdentifiers.require(versionColumn) + " = :expectedVersion";
            return db.sql(sql)
                .bind("pk", id)
                .bind("expectedVersion", expectedVersion)
                .fetch().rowsUpdated()
                .map(count -> count > 0);
        });
    }

    @Override
    public Flux<Map<String, Object>> executeQuery(PhysicalQueryPlan plan) {
        return Flux.defer(() -> {
            StringBuilder sql = new StringBuilder("SELECT * FROM ").append(SqlIdentifiers.require(plan.targetTable()));
            if (plan.whereClause() != null && !plan.whereClause().isBlank()) {
                sql.append(" WHERE ").append(plan.whereClause());
            }
            if (!plan.sorts().isEmpty()) {
                List<String> orderBy = new ArrayList<>();
                for (PhysicalQueryPlan.PhysicalSort sort : plan.sorts()) {
                    orderBy.add(SqlIdentifiers.require(sort.physicalColumn()) + (sort.ascending() ? " ASC" : " DESC"));
                }
                sql.append(" ORDER BY ").append(String.join(", ", orderBy));
            }
            sql.append(" LIMIT ").append(plan.limit()).append(" OFFSET ").append(plan.offset());
            return run(sql.toString(), plan.bindParams(), plan.timeout());
        });
    }

    @Override
    public Mono<Long> count(PhysicalQueryPlan plan) {
        return Mono.defer(() -> {
            StringBuilder sql = new StringBuilder("SELECT count(*) AS total FROM ")
                .append(SqlIdentifiers.require(plan.targetTable()));
            if (plan.whereClause() != null && !plan.whereClause().isBlank()) {
                sql.append(" WHERE ").append(plan.whereClause());
            }
            return run(sql.toString(), plan.bindParams(), plan.timeout())
                .next()
                .map(row -> ((Number) row.get("total")).longValue());
        });
    }

    @Override
    public Flux<Map<String, Object>> executeRawQuery(RawQueryPlan plan) {
        return Flux.defer(() -> {
            String sql = plan.sql().strip();
            while (sql.endsWith(";")) {
                sql = sql.substring(0, sql.length() - 1).stripTrailing();
            }
            return run(sql + " LIMIT " + plan.limit(), plan.bindParams(), plan.timeout());
        });
    }

    @Override
    public <T> Mono<T> inTransaction(Mono<T> work) {
        return tx.transactional(work);
    }

    private Flux<Map<String, Object>> run(String sql, Map<String, BoundValue> params, Duration timeout) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(sql);
        for (Map.Entry<String, BoundValue> e : params.entrySet()) {
            BoundValue bound = e.getValue();
            spec = bound.value() == null
                ? spec.bindNull(e.getKey(), bound.type())
                : spec.bind(e.getKey(), bound.value());
        }
        // The timeout covers the whole result, not the gap between rows.
        return spec.map(R2dbcStorageEngine::toMap)
            .all()
            .collectList()
            .timeout(timeout)
            .onErrorMap(TimeoutException.class, ex -> new QueryTimeoutException("Query exceeded " + timeout, ex))
            .flatMapMany(Flux::fromIterable);
    }

    /** Unique index violations become {@link UniqueKeyViolationException} carrying the index name. */
    private static Throwable translate(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof PostgresqlException pg
                && UNIQUE_VIOLATION.equals(pg.getErrorDetails().getCode())) {
                return new UniqueKeyViolationException(pg.getErrorDetails().getConstraintName().orElse(null), error);
            }
        }
        return error;
    }

    private static Map<String, Object> toMap(Row row, RowMetadata metadata) {
        Map<String, Object> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int index = 0;
        for (ColumnMetadata column : metadata.getColumnMetadatas()) {
            result.put(column.getName(), row.get(index++));
        }
        return result;
    }
}

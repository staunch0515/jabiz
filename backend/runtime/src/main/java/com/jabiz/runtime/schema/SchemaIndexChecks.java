package com.jabiz.runtime.schema;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Startup check of the schema's indexes: an INVALID index ({@code pg_index.indisvalid} false) is an error. A
 * {@code CREATE [UNIQUE] INDEX CONCURRENTLY} that fails half way (a deadlock, a cancel, duplicate rows for a unique
 * index) leaves one behind: PostgreSQL maintains it on every write but never reads it, and nothing else would notice.
 * Two kinds are only warnings, because they are not (yet) a failure: an index some session is building right now
 * ({@code pg_stat_progress_create_index}, for instance another instance running the migration), and the temporaries
 * of {@code REINDEX CONCURRENTLY} ({@code *_ccnew}, {@code *_ccold}), which that command cleans up or leaves for the
 * operator to drop. Generic over every index of the schema, not only the platform's.
 *
 * <p>Covers the current schema of the default pool only; other pools are out of scope for now. Blocks on the
 * reactive client on the startup thread, as the other schema checks do.
 */
@Component
public class SchemaIndexChecks implements PlatformCheck {

    public static final String CATEGORY = "SCHEMA";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final String INVALID_INDEXES = """
        SELECT c.relname::text AS table_name, i.relname::text AS index_name,
               EXISTS (SELECT 1 FROM pg_stat_progress_create_index p WHERE p.index_relid = i.oid) AS building
        FROM pg_index x
        JOIN pg_class i ON i.oid = x.indexrelid
        JOIN pg_class c ON c.oid = x.indrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = current_schema() AND NOT x.indisvalid
        ORDER BY c.relname, i.relname""";

    private final StorageAdapterRegistry storages;
    private final String poolRef;

    public SchemaIndexChecks(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.poolRef = poolRef;
    }

    @Override
    public List<CheckProblem> check() {
        List<Map<String, Object>> rows = storages.getEngine(poolRef).select(INVALID_INDEXES, Map.of())
            .collectList().block(TIMEOUT);
        return (rows == null ? List.<Map<String, Object>>of() : rows).stream()
            .map(row -> classify(Rows.string(row.get("table_name")), Rows.string(row.get("index_name")),
                Boolean.TRUE.equals(row.get("building"))))
            .toList();
    }

    /** The problem an INVALID index is: a warning while it is being built or is a REINDEX temporary, else an error. */
    static CheckProblem classify(String table, String index, boolean building) {
        String location = "Index " + index;
        if (building) {
            return CheckProblem.warning(CATEGORY, location, "on table " + table + " is INVALID because it is being"
                + " built right now; if no session is building it later, it failed (see the next start's check)");
        }
        if (index.endsWith("_ccnew") || index.endsWith("_ccold")) {
            return CheckProblem.warning(CATEGORY, location, "on table " + table + " is INVALID: a temporary of REINDEX"
                + " CONCURRENTLY that is not finished. If no REINDEX is running, DROP INDEX CONCURRENTLY " + index);
        }
        return CheckProblem.error(CATEGORY, location, "on table " + table + " is INVALID (a CREATE INDEX CONCURRENTLY"
            + " that failed half way): it slows every write and serves no query. Run DROP INDEX CONCURRENTLY " + index
            + ", then create it again; if a platform migration (V66) built it and the next start stops at validation,"
            + " run flyway repair on jabiz_schema_history first (docs/guide/platform-operations.md section 6)");
    }
}

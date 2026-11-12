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
 * index) leaves one behind: PostgreSQL maintains it on every write but never reads it, and the migration's
 * {@code IF NOT EXISTS} skips it on the next run, so nothing else would notice. Generic over every index of the
 * schema, not only the platform's. Blocks on the reactive client on the startup thread, as the other schema checks do.
 */
@Component
public class SchemaIndexChecks implements PlatformCheck {

    public static final String CATEGORY = "SCHEMA";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final String INVALID_INDEXES = """
        SELECT c.relname::text AS table_name, i.relname::text AS index_name
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
            .map(row -> CheckProblem.error(CATEGORY, "Index " + Rows.string(row.get("index_name")),
                "on table " + Rows.string(row.get("table_name")) + " is INVALID (a CREATE INDEX CONCURRENTLY that"
                    + " failed half way): it slows every write and serves no query. Run DROP INDEX CONCURRENTLY "
                    + Rows.string(row.get("index_name")) + ", then create it again (rerun the migration's statement;"
                    + " docs/guide/platform-operations.md section 6)"))
            .toList();
    }
}

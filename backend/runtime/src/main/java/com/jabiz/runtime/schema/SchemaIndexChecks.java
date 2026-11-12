package com.jabiz.runtime.schema;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Startup check of the schema's indexes (ROADMAP 17zz18): every INVALID index ({@code pg_index.indisvalid} false) is a
 * warning, never an error, so that it never stops a start. A {@code CREATE [UNIQUE] INDEX CONCURRENTLY} that fails half
 * way (a lock timeout, a deadlock, a cancel, duplicate rows for a unique index) leaves one behind: PostgreSQL maintains
 * it on every write but never reads it, and nothing else would notice. The count of the last check, leaving out the
 * indexes probably being built (below), is the gauge {@value PlatformObservations#SCHEMA_INVALID_INDEXES} (no index
 * name in it), alerted on above zero for 15 minutes; it is taken at each start (and by {@code platformCheck}), so an
 * index dropped or rebuilt by hand counts until the next start.
 *
 * <p>The message tells three cases apart, as far as the database lets it see:
 * <ul>
 *   <li>being built right now (another instance running the migration, an operator's build): a session reports the
 *       build in {@code pg_stat_progress_create_index}, or another session holds a SHARE UPDATE EXCLUSIVE lock on the
 *       index's table, as a concurrent build does from start to end. The progress view shows the index of another
 *       role's session only to superusers and members of {@code pg_read_all_stats}, so where the migrations run as
 *       another role than the application the lock is what tells; {@code pg_locks} is readable by everyone. A VACUUM
 *       or ANALYZE of the table (autovacuum included) holds the same lock, so this case reads "probably being built";
 *       the next start tells.</li>
 *   <li>a temporary of {@code REINDEX CONCURRENTLY} ({@code *_ccnew}, {@code *_ccold}), which that command cleans up
 *       or leaves for the operator to drop;</li>
 *   <li>otherwise a build that failed: drop it and build it again (docs/guide/platform-operations.md section 6).</li>
 * </ul>
 * Generic over every index of the schema, not only the platform's. Covers the current schema of the default pool only;
 * other pools are out of scope for now. Blocks on the reactive client on the startup thread, as the other schema
 * checks do.
 */
@Component
public class SchemaIndexChecks implements PlatformCheck {

    public static final String CATEGORY = "SCHEMA";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final String INVALID_INDEXES = """
        SELECT c.relname::text AS table_name, i.relname::text AS index_name,
               EXISTS (SELECT 1 FROM pg_stat_progress_create_index p WHERE p.index_relid = i.oid)
               OR EXISTS (SELECT 1 FROM pg_locks l
                          WHERE l.locktype = 'relation' AND l.database = (SELECT oid FROM pg_database
                                                                          WHERE datname = current_database())
                            AND l.relation = c.oid AND l.mode = 'ShareUpdateExclusiveLock' AND l.granted
                            AND l.pid <> pg_backend_pid()) AS building
        FROM pg_index x
        JOIN pg_class i ON i.oid = x.indexrelid
        JOIN pg_class c ON c.oid = x.indrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = current_schema() AND NOT x.indisvalid
        ORDER BY c.relname, i.relname""";

    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final AtomicLong lastCount = new AtomicLong();

    public SchemaIndexChecks(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.poolRef = poolRef;
    }

    @Override
    public List<CheckProblem> check() {
        List<Map<String, Object>> rows = storages.getEngine(poolRef).select(INVALID_INDEXES, Map.of())
            .collectList().block(TIMEOUT);
        List<Map<String, Object>> found = rows == null ? List.of() : rows;
        // The gauge leaves out what is probably being built: a build running at the start is no failure (yet).
        lastCount.set(found.stream().filter(row -> !Boolean.TRUE.equals(row.get("building"))).count());
        return found.stream()
            .map(row -> problem(Rows.string(row.get("table_name")), Rows.string(row.get("index_name")),
                Boolean.TRUE.equals(row.get("building"))))
            .toList();
    }

    /** INVALID indexes found by the last check that are not probably being built (0 before one). */
    public long lastCount() {
        return lastCount.get();
    }

    /** The warning an INVALID index is, worded by what it probably is. */
    static CheckProblem problem(String table, String index, boolean building) {
        String location = "Index " + index;
        if (building) {
            return CheckProblem.warning(CATEGORY, location, "on table " + table + " is INVALID and probably being"
                + " built right now (a session builds an index of the table, or holds the lock a concurrent build"
                + " holds; a VACUUM holds it too). If the next start still reports it, the build failed: see"
                + " docs/guide/platform-operations.md section 6");
        }
        if (index.endsWith("_ccnew") || index.endsWith("_ccold")) {
            return CheckProblem.warning(CATEGORY, location, "on table " + table + " is INVALID: a temporary of REINDEX"
                + " CONCURRENTLY that is not finished. If no REINDEX is running, DROP INDEX CONCURRENTLY " + index);
        }
        return CheckProblem.warning(CATEGORY, location, "on table " + table + " is INVALID (a CREATE INDEX CONCURRENTLY"
            + " that failed half way): it slows every write and serves no query. Run DROP INDEX CONCURRENTLY " + index
            + ", then create it again; if a platform migration (V66) built it, its next run rebuilds it, and if the"
            + " start stops at validation, run flyway repair on jabiz_schema_history first"
            + " (docs/guide/platform-operations.md section 6)");
    }

    @Autowired(required = false)
    void meters(MeterRegistry registry) {
        if (registry == null) {
            return;
        }
        Gauge.builder(PlatformObservations.SCHEMA_INVALID_INDEXES, this, SchemaIndexChecks::lastCount)
            .description("INVALID indexes in the current schema of the default pool not being built, at the last"
                + " startup check")
            .register(registry);
    }
}

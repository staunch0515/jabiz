package com.jabiz.runtime.config;

import java.util.HashMap;
import java.util.Map;

/**
 * The Flyway settings every run of the platform scripts needs (ROADMAP 17zz18, decision D81): their placeholders with
 * the production values, and the session-level lock {@link #NON_TRANSACTIONAL_LOCK}.
 * {@link PlatformSchemaMigration} sets the lock and each placeholder the application's configuration does not; a
 * runner that runs the platform scripts itself (a test fixture, a tool) sets the lock and starts from
 * {@link #defaults()}. Kept apart from
 * {@link PlatformSchemaMigration} so that such a runner needs no Spring Boot on its classpath.
 */
public final class PlatformScriptPlaceholders {

    /**
     * {@code ${concurrently}}: the keyword of an index built without blocking writers (V66), {@code CONCURRENTLY}.
     * Tests that migrate several schemas of one database at once set it empty, because CREATE INDEX CONCURRENTLY waits
     * for the transactions of the whole database. A runner that does not define it fails fast on the unresolved
     * placeholder instead of hanging.
     */
    public static final String CONCURRENTLY = "concurrently";

    /**
     * {@code ${drop_lock_timeout}}: how long V66 waits for the lock to drop an INVALID index of its name before it
     * fails (a PostgreSQL interval, {@code 3s}). A backstop: the drop takes an ACCESS EXCLUSIVE lock on the ledger, and
     * every posting would queue behind a drop that waits.
     */
    public static final String DROP_LOCK_TIMEOUT = "drop_lock_timeout";

    /**
     * Flyway's session-level advisory lock, which whoever runs the platform scripts must set (the test fixture
     * {@code SealedColumnsHistory} does). With the default transactional lock, Flyway takes {@code
     * pg_advisory_xact_lock} on a second connection and keeps that connection's transaction open ("idle in
     * transaction") until the whole run ends. A migration outside a transaction that builds an index CONCURRENTLY (V66)
     * waits for every transaction older than its snapshots, that one included (a ShareLock on its virtualxid, never
     * granted), while Flyway waits for the migration: neither ends, and PostgreSQL sees no deadlock, since one side
     * waits in the client (observed on PostgreSQL 16 with Flyway 11, ROADMAP 17zz18). The session-level lock is held
     * by the connection without a transaction. Applies to the platform's run only: the configuration is copied, and the
     * application's own Flyway ({@code spring.flyway.*}) keeps its lock.
     */
    public static final Map<String, String> NON_TRANSACTIONAL_LOCK =
        Map.of("flyway.postgresql.transactional.lock", "false");

    private PlatformScriptPlaceholders() {
    }

    /** Every placeholder with its production value. */
    public static Map<String, String> defaults() {
        return Map.of(CONCURRENTLY, "CONCURRENTLY", DROP_LOCK_TIMEOUT, "3s");
    }

    /** {@link #defaults()} with these replaced. */
    public static Map<String, String> with(Map<String, String> replaced) {
        Map<String, String> placeholders = new HashMap<>(defaults());
        placeholders.putAll(replaced);
        return Map.copyOf(placeholders);
    }
}

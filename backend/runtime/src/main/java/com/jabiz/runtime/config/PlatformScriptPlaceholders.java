package com.jabiz.runtime.config;

import java.util.HashMap;
import java.util.Map;

/**
 * The Flyway placeholders of the platform scripts and their production values (ROADMAP 17zz18, decision D81).
 * {@link PlatformSchemaMigration} sets each one the application's configuration does not; a runner that runs the
 * platform scripts itself (a test fixture, a tool) starts from {@link #defaults()}. Kept apart from
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

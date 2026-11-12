package com.jabiz.quizbuks.it;

import com.jabiz.runtime.config.PlatformScriptPlaceholders;
import com.jabiz.runtime.test.PostgresTestDatabase;
import org.flywaydb.core.Flyway;

import java.util.Map;

/**
 * Runs the platform's scripts into a fresh schema the way {@code PlatformSchemaMigration} does, for the tests of the
 * application's own data migrations. Two settings follow the platform (D81): the session-level advisory lock (with
 * Flyway's transactional lock, a script that builds an index CONCURRENTLY waits forever for Flyway's own idle
 * transaction), and {@code ${concurrently}} empty, since several test schemas of one database migrate at once and a
 * concurrent build waits for the transactions of the whole database.
 */
final class PlatformScripts {

    private PlatformScripts() {
    }

    static void migrate(PostgresTestDatabase db, String schema) {
        Flyway.configure().dataSource(db.jdbcUrl(), db.username(), db.password()).schemas(schema)
            .defaultSchema(schema).createSchemas(true).locations("classpath:db/jabiz").table("jabiz_schema_history")
            .baselineOnMigrate(true).baselineVersion("0")
            .configuration(PlatformScriptPlaceholders.NON_TRANSACTIONAL_LOCK)
            .placeholders(PlatformScriptPlaceholders.with(Map.of(PlatformScriptPlaceholders.CONCURRENTLY, "")))
            .load().migrate();
    }
}

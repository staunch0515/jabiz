package com.jabiz.runtime.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;

/**
 * Runs the platform's migrations before the application's (docs/design/01-core-vs-runtime.md section 8).
 *
 * <p>The platform ships its scripts in {@value #PLATFORM_LOCATION} and records them in
 * {@value #PLATFORM_HISTORY_TABLE}; the application keeps its own scripts and history table as configured
 * through {@code spring.flyway.*}. Separate histories let both sides number their scripts independently.
 * Running the platform first lets application tables reference platform tables.
 *
 * <p>Each side sees the other's objects as a "non-empty schema without history", which Flyway rejects
 * unless it may baseline; both baseline at version 0 so that every real script (version 1 and up) still
 * runs. An explicit baseline configured for the application is left untouched.
 */
public final class PlatformSchemaMigration implements FlywayMigrationStrategy {

    public static final String PLATFORM_LOCATION = "classpath:db/jabiz";
    public static final String PLATFORM_HISTORY_TABLE = "jabiz_schema_history";

    @Override
    public void migrate(Flyway application) {
        Configuration config = application.getConfiguration();
        Flyway.configure()
            .configuration(config)
            .locations(PLATFORM_LOCATION)
            .table(PLATFORM_HISTORY_TABLE)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate();

        Flyway applicationWithBaseline = config.isBaselineOnMigrate()
            ? application
            : Flyway.configure().configuration(config).baselineOnMigrate(true).baselineVersion("0").load();
        applicationWithBaseline.migrate();
    }
}

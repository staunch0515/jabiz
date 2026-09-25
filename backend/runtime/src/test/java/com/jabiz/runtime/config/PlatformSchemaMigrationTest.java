package com.jabiz.runtime.config;

import com.jabiz.runtime.test.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Platform and application scripts keep separate histories; platform first, on new and existing schemas. */
class PlatformSchemaMigrationTest {

    private static final PostgresTestDatabase DB = PostgresTestDatabase.get();

    private final String schema = "mig_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    @AfterEach
    void dropSchema() {
        DB.dropSchema(schema);
    }

    private FluentConfiguration application() {
        return Flyway.configure()
            .dataSource(DB.jdbcUrl(), DB.username(), DB.password())
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .locations("classpath:db/legacyapp");
    }

    @Test
    void freshSchemaGetsPlatformThenApplicationScripts() {
        new PlatformSchemaMigration().migrate(application().load());

        assertThat(versions(PlatformSchemaMigration.PLATFORM_HISTORY_TABLE)).contains("1");
        assertThat(versions("flyway_schema_history")).contains("0", "1");
        assertThat(single("SELECT count(*) FROM legacy_item")).isEqualTo(0L);
        assertThat(single("SELECT nextval('op_process_seq')")).isEqualTo(1L);
        // op_process_seq existed when the application script ran: platform first.
        assertThat(single("SELECT sequence_seen FROM legacy_item_meta")).isEqualTo(1L);
    }

    @Test
    void existingApplicationSchemaIsBaselinedForThePlatform() {
        // A database migrated before the platform had its own scripts.
        Flyway.configure().configuration(application()).locations("classpath:db/legacyapp-v1").load().migrate();
        assertThat(versions("flyway_schema_history")).containsExactly("1");

        new PlatformSchemaMigration().migrate(application().locations("classpath:db/legacyapp-v1").load());

        assertThat(versions(PlatformSchemaMigration.PLATFORM_HISTORY_TABLE)).contains("0", "1");
        assertThat(versions("flyway_schema_history")).containsExactly("1");
        assertThat(single("SELECT nextval('op_process_seq')")).isEqualTo(1L);
    }

    @Test
    void migratingTwiceChangesNothing() {
        new PlatformSchemaMigration().migrate(application().load());
        List<String> platform = versions(PlatformSchemaMigration.PLATFORM_HISTORY_TABLE);
        List<String> app = versions("flyway_schema_history");

        new PlatformSchemaMigration().migrate(application().load());

        assertThat(versions(PlatformSchemaMigration.PLATFORM_HISTORY_TABLE)).isEqualTo(platform);
        assertThat(versions("flyway_schema_history")).isEqualTo(app);
    }

    private List<String> versions(String historyTable) {
        List<String> versions = new ArrayList<>();
        try (Connection connection = DB.connect(schema);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                 "SELECT version FROM " + historyTable + " WHERE version IS NOT NULL AND success ORDER BY installed_rank")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return versions;
    }

    private Object single(String sql) {
        try (Connection connection = DB.connect(schema);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getObject(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

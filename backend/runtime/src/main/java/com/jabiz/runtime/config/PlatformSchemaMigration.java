package com.jabiz.runtime.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/**
 * Runs the platform's migrations before the application's (docs/design/01-core-vs-runtime.md section 8).
 *
 * <p>The platform ships its scripts in {@value #PLATFORM_LOCATION} and records them in
 * {@value #PLATFORM_HISTORY_TABLE}; the application keeps its own scripts and history table as configured
 * through {@code spring.flyway.*}. Separate histories let both sides number their scripts independently.
 * Running the platform first lets application tables reference platform tables.
 *
 * <p>Flyway refuses a "non-empty schema without history" unless it may baseline. The two sides are
 * handled differently because only the application's own objects say anything about its history:
 * <ul>
 *   <li>The platform always baselines at version 0, so on a database that predates it every platform
 *       script (version 1 and up) still runs.</li>
 *   <li>The application is baselined at version 0 only when it is new to the database: no history table
 *       and no tables besides the platform's. Then all its scripts run, whatever baseline settings it has
 *       (a configured baseline would otherwise skip scripts just because platform objects exist). In every
 *       other case its configuration applies unchanged, exactly as without the platform.</li>
 * </ul>
 */
public final class PlatformSchemaMigration implements FlywayMigrationStrategy {

    public static final String PLATFORM_LOCATION = "classpath:db/jabiz";
    public static final String PLATFORM_HISTORY_TABLE = "jabiz_schema_history";

    /** Every table the platform scripts create; PlatformSchemaMigrationTest keeps this list honest. */
    public static final Set<String> PLATFORM_TABLES = Set.of(PLATFORM_HISTORY_TABLE, "sys_dict_item",
        "sys_dict_item_version", "op_process", "op_process_item", "op_process_result", "entity_registry",
        "op_process_after_commit", "sec_user_version", "sec_role_version", "sec_role_permission_version",
        "sec_user_role_version", "sec_menu_version", "sec_login_record_version", "sec_refresh_token",
        "sec_refresh_token_use", "sec_refresh_family_revocation", "sys_param_version", "ledger_account_version",
        "ledger_transaction_version", "ledger_entry_version", "sys_outbox_event", "sys_event_consumption",
        "sys_outbox_attempt", "jabiz_shedlock", "sys_job_run", "sys_file", "sys_number_counter",
        "sys_number_assignment", "sys_approval_rule_version", "sys_approval_limit_version", "sys_sod_rule_version",
        "sys_control_change_version", "sys_approval_request_version", "sys_approval_decision",
        "sys_approval_evaluation", "sys_task_version", "sys_notification", "sys_notification_attempt",
        "sys_report_run", "sys_report_run_supersede", "sys_import_mapping_version", "sys_import_run", "sys_import_ref", "sys_audit_record",
        "sys_integrity_seal", "sys_integrity_item", "sys_integrity_seal_table",
        "sys_integrity_check", "sys_legal_hold_version", "sec_user_mfa_version",
        "sec_user_identity_version", "sec_oidc_state", "sec_oidc_state_use", "sys_reveal_record", "sys_access_review",
        "sys_document_run", "sys_document_delivery", "sys_document_delivery_attempt", "sys_generated_file");

    @Override
    public void migrate(Flyway application) {
        Configuration config = application.getConfiguration();
        boolean applicationIsNew = isNewToApplication(config);

        Flyway.configure()
            .configuration(config)
            .locations(PLATFORM_LOCATION)
            .table(PLATFORM_HISTORY_TABLE)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate();

        Flyway applicationMigration = applicationIsNew
            ? Flyway.configure().configuration(config).baselineOnMigrate(true).baselineVersion("0").load()
            : application;
        applicationMigration.migrate();
    }

    /** No application history and no tables other than the platform's in the application's schema. */
    private static boolean isNewToApplication(Configuration config) {
        try (Connection connection = config.getDataSource().getConnection()) {
            String schema = defaultSchema(config, connection);
            Set<String> tables = new HashSet<>();
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ?")) {
                statement.setString(1, schema);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        tables.add(rs.getString(1));
                    }
                }
            }
            tables.removeAll(PLATFORM_TABLES);
            return tables.isEmpty();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not inspect the schema before migrating", e);
        }
    }

    /** The schema Flyway will migrate: its default schema, else the first listed, else the connection's. */
    private static String defaultSchema(Configuration config, Connection connection) throws SQLException {
        if (config.getDefaultSchema() != null) {
            return config.getDefaultSchema();
        }
        if (config.getSchemas().length > 0) {
            return config.getSchemas()[0];
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT current_schema()")) {
            rs.next();
            return rs.getString(1);
        }
    }
}

package com.jabiz.app.it.temporal;

import com.jabiz.app.it.fixture.ItTemporalFixtures;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.storage.AppendOnlyViolationException;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The append-only guard of decision D5: triggers refuse UPDATE, DELETE and TRUNCATE on temporal and operation
 * tables, the controlled purge needs the maintenance role, and normal flows never even send such statements.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "it.sql-log.enabled=true")
class AppendOnlyGuardIT extends TemporalItSupport {

    /** Every append-only table with one of its columns. */
    private static final Map<String, String> TABLES = Map.of(
        "it_price", "note",
        "sys_dict_item_version", "sort_order",
        "op_process", "reason",
        "op_process_item", "action",
        "op_process_result", "output",
        "entity_registry", "entity_type");

    private static final Pattern MUTATION = Pattern.compile("^\\s*(UPDATE|DELETE|TRUNCATE)\\b",
        Pattern.CASE_INSENSITIVE);

    @Autowired
    StorageAdapterRegistry storages;

    private static String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /** Acceptance: UPDATE, DELETE and TRUNCATE are refused on every temporal and operation table. */
    @Test
    void theTriggersRefuseUpdatesDeletionsAndTruncation() {
        newPrice(sku(), 100);

        TABLES.forEach((table, column) -> {
            assertThat(query("SELECT count(*) AS n FROM " + table).getFirst().get("n")).as(table).isNotEqualTo(0L);
            for (String sql : List.of("UPDATE " + table + " SET " + column + " = " + column,
                "DELETE FROM " + table, "TRUNCATE " + table + " CASCADE")) {
                assertThatThrownBy(() -> execute(sql)).as(sql)
                    .satisfies(error -> assertThat(sqlState(error)).isEqualTo("JZ001"));
            }
        });
    }

    /** The platform reports a refusal as the defect it is. */
    @Test
    void aRefusedWriteIsReportedAsAppendOnlyViolation() {
        EntityInstance price = newPrice(sku(), 100);

        assertThatThrownBy(() -> storages.getEngine("default")
            .casUpdate("it_price", "price_id", price.id(), 1, "version_no", Map.of("note", "x")).block())
            .isInstanceOf(AppendOnlyViolationException.class);
    }

    /** Acceptance: in purge mode a role outside jabiz_maintenance is still refused. */
    @Test
    void purgeModeNeedsTheMaintenanceRole() throws SQLException {
        EntityInstance price = newPrice(sku(), 100);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String plain = "it_plain_" + suffix;
        String maintainer = "it_maint_" + suffix;
        boolean createdMaintenanceRole = false;
        try (Connection admin = DB.connect(schema()); Statement ddl = admin.createStatement()) {
            if (query("SELECT 1 FROM pg_roles WHERE rolname = 'jabiz_maintenance'").isEmpty()) {
                ddl.execute("CREATE ROLE jabiz_maintenance NOLOGIN");
                createdMaintenanceRole = true;
            }
            ddl.execute("CREATE ROLE " + plain + " NOLOGIN");
            ddl.execute("CREATE ROLE " + maintainer + " NOLOGIN IN ROLE jabiz_maintenance");
            for (String role : List.of(plain, maintainer)) {
                ddl.execute("GRANT USAGE ON SCHEMA " + schema() + " TO " + role);
                ddl.execute("GRANT SELECT, DELETE ON it_price TO " + role);
            }
            try {
                assertThat(sqlState(purge(admin, plain, true, price.id()))).isEqualTo("JZ001");
                assertThat(sqlState(purge(admin, maintainer, false, price.id()))).isEqualTo("JZ001");
                assertThat((Object) purge(admin, maintainer, true, price.id())).isNull();
            } finally {
                for (String role : List.of(plain, maintainer)) {
                    ddl.execute("DROP OWNED BY " + role);
                    ddl.execute("DROP ROLE " + role);
                }
                if (createdMaintenanceRole) {
                    ddl.execute("DROP ROLE jabiz_maintenance");
                }
            }
        }
        // Every attempt was rolled back.
        assertThat(versions(price.id())).hasSize(1);
    }

    /** Tries to delete the price as {@code role}; returns the error, or null when the deletion was allowed. */
    private static SQLException purge(Connection connection, String role, boolean purgeMode, Object id)
        throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL ROLE " + role);
            if (purgeMode) {
                statement.execute("SET LOCAL jabiz.maintenance_mode = 'purge'");
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM it_price WHERE price_id = ?")) {
                delete.setObject(1, id);
                assertThat(delete.executeUpdate()).isEqualTo(1);
            }
            return null;
        } catch (SQLException e) {
            return e;
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    /** Acceptance: the SQL of normal flows contains no UPDATE, DELETE or TRUNCATE. */
    @Test
    void normalFlowsOnlyInsert() {
        SqlStatementLog.STATEMENTS.clear();

        EntityInstance price = newPrice(sku(), 100);
        advance(Duration.ofMinutes(1));
        commit(update(price.id(), 1, attrs("amount", 200), now().plus(Duration.ofDays(1))));
        commit(update(price.id(), 1, attrs("note", "rebased"), null));
        long noteOperation = lastOperation(price.id());
        commit(cancel(price.id(), 4, now().plus(Duration.ofDays(1))));
        commit(ItTemporalFixtures.PRICE_DATASET, ADMIN, "correction",
            update(price.id(), 1, attrs("status", "ACTIVE"), now().minusSeconds(30)));
        revert(noteOperation, "undo the note");
        read(price.id());
        queryAll(ItTemporalFixtures.PRICE_DATASET);
        asRequest(ADMIN, entities.history(dataset(ItTemporalFixtures.PRICE_DATASET), PRICE, price.id())).block();
        advance(Duration.ofMinutes(1));
        commit(delete(price.id(), read(price.id()).version(), null));

        List<String> statements = List.copyOf(SqlStatementLog.STATEMENTS);
        assertThat(statements).anyMatch(sql -> sql.toLowerCase(Locale.ROOT).startsWith("insert into it_price"));
        assertThat(statements).anyMatch(sql -> sql.toLowerCase(Locale.ROOT).startsWith("insert into op_process_item"));
        assertThat(statements).noneMatch(sql -> MUTATION.matcher(sql).find());
    }
}

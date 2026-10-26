package com.jabiz.app.it.integrity;

import com.jabiz.app.it.security.SecurityItSupport;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.integrity.IntegrityChecks;
import com.jabiz.runtime.integrity.IntegrityStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup check of sealed columns (decision D58): a column a block recorded that the schema no longer has, or a
 * sealed table that is gone, is a warning naming the cause, before the verification reports the rows. In a class of
 * its own: its schema keeps what the tests did to it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class SealedColumnsCheckIT extends SecurityItSupport {

    @Autowired
    IntegrityChecks checks;

    @Autowired
    IntegrityStore store;

    @Test
    void sealedColumnsRenamedOrDroppedAndSealedTablesDroppedAreWarnedOf() {
        execute("CREATE TABLE it_sc_renamed (id bigint PRIMARY KEY, note text, amount numeric(12, 2))");
        execute("CREATE TABLE it_sc_widened (id bigint PRIMARY KEY, note text)");
        execute("CREATE TABLE it_sc_dropped (id bigint PRIMARY KEY, note text)");
        for (String table : List.of("it_sc_renamed", "it_sc_widened", "it_sc_dropped")) {
            query("SELECT 1 AS done FROM (SELECT jabiz_protect_append_only('" + table + "')) AS p");
            execute("INSERT INTO " + table + " (id, note) VALUES (1, 'a')");
        }
        long first = seal();
        assertThat(warnings()).isEmpty();

        // A column added and sealed again: fine.
        execute("ALTER TABLE it_sc_widened ADD COLUMN extra text");
        execute("INSERT INTO it_sc_widened (id, note, extra) VALUES (2, 'b', 'x')");
        seal();
        // A sealed column renamed, another dropped, a sealed table dropped (as a migration would).
        ddl("ALTER TABLE it_sc_renamed RENAME COLUMN note TO remark", "ALTER TABLE it_sc_renamed DROP COLUMN amount",
            "DROP TABLE it_sc_dropped");

        assertThat(warnings()).containsExactlyInAnyOrder(
            "Table it_sc_dropped: is gone, but blocks from " + first + " on sealed its rows: verifying reports"
                + " them as MISSING",
            "Table it_sc_renamed: has lost sealed columns amount (sealed from block " + first + "), note (sealed"
                + " from block " + first + "): verifying reports every row sealed with them as MODIFIED. A migration"
                + " renamed or dropped them; sealed columns may only be added"
                + " (docs/design/21-audit-retention.md section 2.1), so a migration giving them back mends it");
        assertThat(checks.check()).filteredOn(p -> p.location().startsWith("Table it_sc_"))
            .allMatch(p -> p.severity() == CheckProblem.Severity.WARNING);

        // Giving the column back mends the warning (the dropped one stays lost).
        ddl("ALTER TABLE it_sc_renamed RENAME COLUMN remark TO note",
            "ALTER TABLE it_sc_renamed ADD COLUMN amount numeric(12, 2)");
        assertThat(warnings()).singleElement().asString().startsWith("Table it_sc_dropped:");
    }

    @Test
    void theDistinctListsAreReadWithoutReadingEveryBlock() {
        execute("CREATE TABLE it_sc_many (id bigint PRIMARY KEY, v text)");
        query("SELECT 1 AS done FROM (SELECT jabiz_protect_append_only('it_sc_many')) AS p");
        for (int i = 0; i < 5; i++) {
            execute("INSERT INTO it_sc_many VALUES (" + i + ", 'v')");
            seal();
        }
        // The skip scan gives what a plain DISTINCT over every row gives.
        List<Map<String, Object>> plain = query("SELECT st.table_name::text AS table_name, c AS column_name,"
            + " min(st.seal_no) AS first_seal FROM sys_integrity_seal_table st CROSS JOIN LATERAL unnest(st.columns)"
            + " AS c GROUP BY 1, 2 ORDER BY 1, 2");
        assertThat(store.sealedColumns(store.engine()).collectList().block())
            .extracting(c -> List.<Object>of(c.table(), c.column(), c.firstSeal()))
            .containsExactlyElementsOf(plain.stream().map(row -> List.<Object>of(row.get("table_name"),
                row.get("column_name"), ((Number) row.get("first_seal")).longValue())).toList());
        // And it reads through the index.
        assertThat(query("SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema()"
            + " AND indexname = 'sys_integrity_seal_table_columns_idx'")).singleElement()
            .satisfies(row -> assertThat((String) row.get("indexdef")).contains("(table_name, columns, seal_no)"));
    }

    private List<String> warnings() {
        return checks.check().stream().filter(p -> p.location().startsWith("Table it_sc_"))
            .map(p -> p.location() + ": " + p.message()).toList();
    }

    @SuppressWarnings("unchecked")
    private long seal() {
        Map<String, Object> input = new HashMap<>();
        input.put("scheduledTime", null);
        Map<String, Object> result = post("/api/processes/INTEGRITY_SEAL/latest", admin(), input).expectStatus()
            .isOk().expectBody(MAP).returnResult().getResponseBody();
        return ((Number) ((Map<String, Object>) result.get("output")).get("sealNo")).longValue();
    }

    /** DDL as a migration runs it: the append-only triggers stop UPDATE, DELETE and TRUNCATE, not ALTER or DROP. */
    private static void ddl(String... statements) {
        try (Connection connection = DB.connect(schema()); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.jabiz.app.it.ledger;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.ledger.Direction;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The index of V66 (ROADMAP 17zz18): the sum of one account's history for one {@code dimension_1} value is read
 * from {@code ledger_entry_version_account_dimension_1_idx}. Here the migration builds the index plainly (the tests
 * migrate their schemas side by side); {@code PlatformSchemaMigrationTest} runs the concurrent build.
 */
@SpringBootTest
class LedgerAccountDimensionIndexIT extends LedgerItSupport {

    static final String INDEX = "ledger_entry_version_account_dimension_1_idx";

    static final String SUM = "SELECT direction, sum(amount) FROM ledger_entry_version WHERE account_id = ?::uuid"
        + " AND dimension_1 = ? GROUP BY direction";

    /** A new warehouse; returns its code. */
    String warehouse() {
        String code = "W" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT).replace("-", "");
        String id = UUID.randomUUID().toString();
        asRequest(ACCOUNTANT, entities.commitBatch(datasets.findById(CommerceEntities.WAREHOUSE_DATASET).orElseThrow(),
            List.of(new EntityChange(EntityAction.INSERT, new EntityInstance(id, CommerceEntities.WAREHOUSE, 0, null,
                Map.of("warehouseId", id, "warehouseCode", code, "warehouseName", "IT " + code, "active", true)),
                null)))).block();
        return code;
    }

    @Test
    void theSumOfAnAccountForOneDimensionValueIsReadThroughTheIndex() throws SQLException {
        assertThat(query("SELECT indisvalid, pg_get_indexdef(indexrelid) AS def FROM pg_index WHERE indexrelid ="
            + " (current_schema() || '." + INDEX + "')::regclass").getFirst())
            .containsEntry("indisvalid", true)
            .satisfies(row -> assertThat((String) row.get("def")).contains("(account_id, dimension_1) INCLUDE"
                + " (direction, amount)", "WHERE (dimension_1 IS NOT NULL)"));

        String p = prefix();
        openAccounts(p, "1100", "4000");
        // The first dimension is the warehouse: postings carry the code of an existing one.
        List<String> owners = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            owners.add(warehouse());
        }
        for (String owner : owners) {
            for (int n = 1; n <= 3; n++) {
                run(LedgerProcesses.POST, new LedgerProcesses.PostInput(null, "wallet", null, List.of(
                    new LedgerProcesses.Line(p + "1100", Direction.DEBIT, BigDecimal.valueOf(10L * n), null,
                        Map.of("warehouse", owner)),
                    new LedgerProcesses.Line(p + "4000", Direction.CREDIT, BigDecimal.valueOf(10L * n)))));
            }
        }
        // The values written by the posting are the ones the query asks for.
        String account = (String) query("SELECT account_id::text AS id FROM ledger_account_version WHERE"
            + " account_code = ?", p + "1100").getFirst().get("id");
        String owner = owners.get(2);
        assertThat(query(SUM, account, owner)).singleElement()
            .satisfies(row -> assertThat(amount(row.get("sum"))).isEqualByComparingTo("60"));

        try (Connection connection = DB.connect(schema()); Statement statement = connection.createStatement()) {
            // VACUUM (outside a transaction) sets the visibility map, which an index-only scan relies on.
            statement.execute("VACUUM (ANALYZE) ledger_entry_version");
            // A table this small is cheaper to read whole whatever the indexes are, so the planner is held to indexes
            // to show which one serves the query; the plan's shape (equality on both columns, the columns of the sum
            // included) is what the test asserts, not the planner's cost model at this volume.
            statement.execute("SET enable_seqscan = off");
            statement.execute("SET enable_bitmapscan = off");
            String plan;
            try (PreparedStatement explain = connection.prepareStatement("EXPLAIN (FORMAT JSON) SELECT direction,"
                + " sum(amount) FROM ledger_entry_version WHERE account_id = ?::uuid AND dimension_1 = ?"
                + " GROUP BY direction")) {
                explain.setString(1, account);
                explain.setString(2, owner);
                try (ResultSet rs = explain.executeQuery()) {
                    rs.next();
                    plan = rs.getString(1);
                }
            }
            assertThat(plan).contains("\"Index Only Scan\"", "\"Index Name\": \"" + INDEX + "\"");
        }
    }
}

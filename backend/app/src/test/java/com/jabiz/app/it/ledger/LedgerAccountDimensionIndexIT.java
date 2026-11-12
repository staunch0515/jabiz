package com.jabiz.app.it.ledger;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.ledger.Direction;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The index of V66 (ROADMAP 17zz18): the sum of one account's history for one {@code dimension_1} value, run as a SQL
 * template ({@code it.ledger_account_dimension_sum}) the way an application runs it, reads
 * {@code ledger_entry_version_account_dimension_1_idx}. The statement explained is the one the platform sent, with the
 * values it bound. Here the migration builds the index plainly (the tests migrate their schemas side by side);
 * {@code PlatformSchemaMigrationTest} runs the concurrent build.
 */
@SpringBootTest(properties = "it.sql-log.enabled=true")
class LedgerAccountDimensionIndexIT extends LedgerItSupport {

    static final String INDEX = "ledger_entry_version_account_dimension_1_idx";
    static final String TEMPLATE = "it.ledger_account_dimension_sum";
    /** The plan nodes that read an index; which one the planner picks depends on the volume, not on the index. */
    static final Set<String> INDEX_READS = Set.of("Index Scan", "Index Only Scan", "Bitmap Index Scan");

    static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    ConnectionFactory connections;

    private final JsonMapper json = JsonMapper.builder().build();

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
    void theTemplateSummingAnAccountForOneDimensionValueReadsTheIndex() throws SQLException {
        assertThat(query("SELECT indisvalid, pg_get_indexdef(indexrelid) AS def FROM pg_index WHERE indexrelid ="
            + " (current_schema() || '." + INDEX + "')::regclass").getFirst())
            .containsEntry("indisvalid", true)
            .satisfies(row -> assertThat((String) row.get("def"))
                .contains("(account_id, dimension_1) WHERE (dimension_1 IS NOT NULL)").doesNotContain("INCLUDE"));

        String p = prefix();
        openAccounts(p, "1100", "1200", "4000");
        // The first dimension is the warehouse: postings carry the code of an existing one. Each owner has entries in
        // both debit accounts, so that neither single-column index reaches only the rows asked for.
        List<String> owners = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            owners.add(warehouse());
        }
        for (String owner : owners) {
            for (int n = 1; n <= 3; n++) {
                for (String debit : List.of("1100", "1200")) {
                    run(LedgerProcesses.POST, new LedgerProcesses.PostInput(null, "wallet", null, List.of(
                        new LedgerProcesses.Line(p + debit, Direction.DEBIT, BigDecimal.valueOf(10L * n), null,
                            Map.of("warehouse", owner)),
                        new LedgerProcesses.Line(p + "4000", Direction.CREDIT, BigDecimal.valueOf(10L * n)))));
                }
            }
        }
        String account = (String) query("SELECT account_id::text AS id FROM ledger_account_version WHERE"
            + " account_code = ?", p + "1100").getFirst().get("id");
        String owner = owners.get(2);

        SqlStatementLog.clear();
        assertThat(rows(TEMPLATE, Map.of("accountId", account, "owner", owner), null)).singleElement()
            .satisfies(row -> {
                assertThat(row).containsEntry("direction", "DEBIT");
                assertThat(amount(row.get("total"))).isEqualByComparingTo("60");
            });
        SqlStatementLog.Execution ran = SqlStatementLog.EXECUTIONS.stream()
            .filter(execution -> execution.sql().contains("ledger_entry_version")
                && execution.sql().contains("dimension_1") && !execution.values().isEmpty())
            .reduce((first, second) -> second).orElseThrow();
        assertThat(ran.values()).contains(owner);

        JsonNode plan = explain(ran);
        List<String> reads = new ArrayList<>();
        collect(plan, reads);
        assertThat(reads).as(plan.toString()).anyMatch(read -> read.endsWith(" " + INDEX));
    }

    /**
     * The plan of the statement as it ran: sent again on an application connection, prefixed with EXPLAIN, with what
     * the platform bound to it. A table this small is cheaper to read whole whatever the indexes are, so the planner
     * is held to indexes (in a transaction rolled back, the pooled connection keeps its settings): the test shows which
     * index serves the template's conditions, not the planner's cost model at this volume.
     */
    private JsonNode explain(SqlStatementLog.Execution ran) {
        io.r2dbc.spi.Connection connection = Mono.from(connections.create()).block(TIMEOUT);
        try {
            run(connection, "ANALYZE ledger_entry_version");
            Mono.from(connection.beginTransaction()).block(TIMEOUT);
            run(connection, "SET LOCAL enable_seqscan = off");
            io.r2dbc.spi.Statement explain = connection.createStatement("EXPLAIN (FORMAT JSON) " + ran.sql());
            for (int i = 0; i < ran.bound().size(); i++) {
                Object bound = ran.bound().get(i);
                if (bound instanceof SqlStatementLog.NullValue nothing) {
                    explain.bindNull(i, nothing.type());
                } else {
                    explain.bind(i, bound);
                }
            }
            String plan = Flux.from(explain.execute())
                .flatMap(result -> result.map((row, meta) -> row.get(0, String.class))).blockFirst(TIMEOUT);
            Mono.from(connection.rollbackTransaction()).block(TIMEOUT);
            return json.readTree(plan).get(0).get("Plan");
        } finally {
            Mono.from(connection.close()).block(TIMEOUT);
        }
    }

    private static void run(io.r2dbc.spi.Connection connection, String sql) {
        Flux.from(connection.createStatement(sql).execute()).flatMap(io.r2dbc.spi.Result::getRowsUpdated)
            .blockLast(TIMEOUT);
    }

    /** Every index read of the plan, as "node type index name". */
    private static void collect(JsonNode node, List<String> reads) {
        String type = node.path("Node Type").asText();
        if (INDEX_READS.contains(type)) {
            reads.add(type + " " + node.path("Index Name").asText());
        }
        for (JsonNode child : node.path("Plans")) {
            collect(child, reads);
        }
    }
}

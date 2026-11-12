package com.jabiz.app.it.process;

import com.jabiz.app.it.fixture.ItFixtures;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Several reads combined into one statement (16p, T5) cost what the reads cost one after another, not what all their
 * pairs of rows cost (ROADMAP 17zz20): the reads of a QuizBuks question bank (questions, options, translations) once
 * took 8.9 seconds as one statement, past the query time limit, while each alone took milliseconds. The statement the
 * platform sent is explained as it ran, with what it bound: no step of its plan handles more than a small multiple of
 * the rows the reads give together.
 */
@SpringBootTest(properties = "it.sql-log.enabled=true")
class CombinedReadCostIT extends PostgresIntegrationTest {

    private static final RequestContext ADMIN = new RequestContext("it-writer", null, Locale.ENGLISH, "it-writer",
        Set.of(), Set.of("*"));

    /** Rows per read: joining them pair by pair tests about five million pairs, a thousand times their sum. */
    static final List<Integer> SIZES = List.of(300, 1200, 3000);

    /**
     * How many times the rows read together a step of the plan may handle. The combined statement handles each read's
     * rows a few times (the read, its numbering, the result); joining pairs handles a thousand times as many here.
     */
    static final int ALLOWANCE = 20;

    @Autowired
    DatasetEntityManager entities;

    @Autowired
    DatasetRegistry datasets;

    @Autowired
    EntityDefinitionRegistry definitions;

    @Autowired
    ConnectionFactory connections;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void largeReadsCombinedGiveEachReadsRowsAndCostTheirSum() {
        DatasetDefinition tickets = datasets.findById(ItFixtures.TICKET_DATASET).orElseThrow();
        EntityDefinition ticket = definitions.getOrThrow(tickets.targetEntityType());
        List<String> owners = List.of("cost-a", "cost-b", "cost-c");
        for (int i = 0; i < SIZES.size(); i++) {
            execute("INSERT INTO it_ticket (f_id, f_title, f_status, f_owner, f_created_at) SELECT ? || g, 'title ' || g,"
                + " 'OPEN', ?, ? FROM generate_series(1, ?) g", owners.get(i) + "-", owners.get(i),
                java.sql.Timestamp.from(clock.instant()), SIZES.get(i));
        }
        execute("ANALYZE it_ticket");
        List<DatasetEntityManager.EntityRead> reads = owners.stream()
            .map(owner -> new DatasetEntityManager.EntityRead(tickets, ticket, EntityQuery.builder()
                .where(new QueryPredicate.Eq("owner", owner)).limit(10_000).build()))
            .toList();
        List<List<EntityInstance>> alone = reads.stream()
            .map(read -> asRequest(ADMIN, entities.queryAll(read.dataset(), read.def(), read.query()).collectList())
                .block())
            .toList();

        SqlStatementLog.clear();
        List<List<EntityInstance>> combined = asRequest(ADMIN, entities.queryMany(reads)).block();

        // One statement reads them all (besides the time limit it sets).
        assertThat(SqlStatementLog.STATEMENTS.stream().filter(sql -> sql.contains("it_ticket")))
            .as("one statement").hasSize(1);
        for (int i = 0; i < SIZES.size(); i++) {
            assertThat(combined.get(i)).as("read " + i).hasSize(SIZES.get(i));
            assertThat(combined.get(i)).as("read " + i).isEqualTo(alone.get(i));
        }

        // Taken before the plans below: they are explained through the same connections and recorded too.
        SqlStatementLog.Execution ran = SqlStatementLog.EXECUTIONS.stream()
            .filter(execution -> execution.sql().contains("it_ticket")).findFirst().orElseThrow();
        long sum = SIZES.stream().mapToLong(Integer::longValue).sum();
        // With the planner's caching of the inner reads and without it: the bound holds whichever plan is chosen.
        for (boolean memoize : List.of(true, false)) {
            JsonNode plan = json.readTree(SqlStatementLog.explain(connections, ran, "ANALYZE, TIMING OFF",
                Map.of("enable_memoize", memoize ? "on" : "off"))).get(0).get("Plan");
            assertThat(mostHandled(plan)).as(plan.toString()).isLessThanOrEqualTo(ALLOWANCE * sum);
            // Each read reads its table once, however often the steps above it are started again.
            assertThat(tableReads(plan)).as(plan.toString())
                .allSatisfy(loops -> assertThat(loops).isLessThanOrEqualTo(1));
        }
    }

    /** The loops of every step of the plan that reads {@code it_ticket}. */
    private static List<Long> tableReads(JsonNode node) {
        List<Long> loops = new java.util.ArrayList<>();
        if ("it_ticket".equals(node.path("Relation Name").asText())) {
            loops.add(node.path("Actual Loops").asLong());
        }
        for (JsonNode child : node.path("Plans")) {
            loops.addAll(tableReads(child));
        }
        return loops;
    }

    /**
     * The most rows one step of the plan handled: what it returned over all its loops and what its filters removed
     * (a join that tests every pair of rows removes all but a few of them).
     */
    private static long mostHandled(JsonNode node) {
        long loops = node.path("Actual Loops").asLong(1);
        long handled = node.path("Actual Rows").asLong(0) * loops
            + node.path("Rows Removed by Join Filter").asLong(0)
            + node.path("Rows Removed by Filter").asLong(0);
        long most = handled;
        for (JsonNode child : node.path("Plans")) {
            most = Math.max(most, mostHandled(child));
        }
        return most;
    }
}

package com.jabiz.app.it.process;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ROADMAP 17zz14: a transaction that writes thousands of rows is written in about linear time. A process holds its
 * inserts and writes them as multi-row statements of up to 30,000 values; each value used to be bound one by one, and
 * each bind copied every value bound before it, so a statement of 30,000 values took tens of seconds (a quiz of a
 * thousand questions: minutes). Bound at once, three thousand rows in one commit take a second or two (fifteen before).
 */
@Import(BulkWriteIT.Bulk.class)
class BulkWriteIT extends PostgresIntegrationTest {

    static final String PROCESS = "IT_BULK_ROWS";
    static final String ENTITY = "ItBulkRow";
    static final String DATASET = "urn:jabiz:dataset:default:" + ENTITY;
    static final int ROWS = 3000;

    private static final RequestContext ADMIN = new RequestContext("it-bulk", null, Locale.ENGLISH, "it-bulk",
        Set.of(), Set.of("*"));

    public record BulkInput(String prefix) {}

    public record BulkOutput(int rows) {}

    @TestConfiguration
    static class Bulk extends BaseEntityDefinitions {
        @Bean
        EntityDefinition itBulkRow() {
            return EntityDefinition.define(ENTITY, eb -> {
                eb.physicalTable("it_bulk_row");
                eb.primaryKey("rowId");
                eb.field("rowId", semanticIdentity("f_id", "urn:jabiz:entity:it:bulk-row"));
                eb.field("name", f -> f.physicalColumn("f_name").required(true).asText(100));
                eb.field("n", f -> f.physicalColumn("f_n").required(true).asNumeric(9, 0));
                eb.field("rowVersion", rowVersion("f_version"));
            });
        }

        @Bean
        DatasetDefinition itBulkRows(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define(DATASET, d -> d
                .targetEntityType(ENTITY).asDefault().permissions("it.read", "it.write")
                .policy(p -> p.maxWriteBatchSize(ROWS)).storage(s -> s.connectionPoolRef(pool)));
        }

        /** Inserts {@link #ROWS} rows in one commit. */
        @Bean
        ProcessDefinition<BulkInput, BulkOutput, ProcessContext> itBulkRowsProcess() {
            return ProcessDefinition.define(PROCESS, 1, BulkInput.class, BulkOutput.class, ProcessContext.class,
                pb -> pb
                    .permissions("it.write")
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("input", input);
                        return ctx;
                    })
                    .outputMapper(ctx -> new BulkOutput(ROWS))
                    .compute("Insert", (metadata, ctx) -> {
                        String prefix = ctx.get("input", BulkInput.class).prefix();
                        for (int i = 0; i < ROWS; i++) {
                            ctx.changes().insert(ENTITY, Map.of("rowId", prefix + i, "name", "Row " + i + " of a bulk "
                                + "write, with some text", "n", i));
                        }
                    })
                    .step("Save", SaveChanges.now()));
        }
    }

    @Autowired
    ProcessExecutor executor;

    @Autowired
    ProcessDefinition<BulkInput, BulkOutput, ProcessContext> itBulkRowsProcess;

    @Test
    void thousandsOfRowsInOneCommitAreWrittenInLinearTime() {
        String prefix = "bulk-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        long start = System.nanoTime();
        BulkOutput output = asRequest(ADMIN, executor.execute(itBulkRowsProcess, new BulkInput(prefix)))
            .block(Duration.ofMinutes(10));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(output.rows()).isEqualTo(ROWS);
        assertThat(((Number) query("SELECT count(*) AS n FROM it_bulk_row WHERE f_id LIKE ?", prefix + "%").getFirst()
            .get("n")).intValue()).isEqualTo(ROWS);
        // Generous for a slow machine: about 1.5 s here, and bound one by one some 15 s.
        assertThat(took).as("writing %d rows took %s", ROWS, took).isLessThan(Duration.ofSeconds(8));
    }
}

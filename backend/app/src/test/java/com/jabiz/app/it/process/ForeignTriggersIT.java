package com.jabiz.app.it.process;

import com.jabiz.app.it.fixture.ItBudgetFixtures;
import com.jabiz.app.it.fixture.ItFixtures;
import com.jabiz.app.it.fixture.ItProcessFixtures;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.entity.MetaModelConsistencyChecker;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.WriteBatching;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.r2dbc.core.DatabaseClient;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Triggers of the application beside the platform's (decision D46): wherever one is, it may write rows the platform
 * does not see written, so no transaction relies on what it read; on a dataset's override table its entity's commits,
 * on a table every combined commit writes all commits, are written one statement at a time. The class has a context of
 * its own: it changes what the application's {@link WriteBatching} allows.
 */
@SpringBootTest(properties = "it.sql-log.enabled=true")
class ForeignTriggersIT extends PostgresIntegrationTest {

    private static final RequestContext ADMIN = new RequestContext("it-triggers", null, Locale.ENGLISH, "it-triggers",
        Set.of(), Set.of("*"));

    @Autowired
    DatabaseClient databaseClient;

    @Autowired
    EntityDefinitionRegistry entities;

    @Autowired
    DatasetRegistry datasets;

    @Autowired
    WriteBatching batching;

    @Autowired
    StorageAdapterRegistry storages;

    @Autowired
    Environment environment;

    @Autowired
    ProcessExecutor executor;

    @Autowired
    ProcessRegistry processes;

    @SuppressWarnings("unchecked")
    private <I, O> O run(String name, I input) {
        ProcessDefinition<I, O, ProcessContext> definition =
            (ProcessDefinition<I, O, ProcessContext>) processes.find(name, 1).orElseThrow();
        return asRequest(ADMIN, executor.execute(definition, input)).block();
    }

    private static void trigger(String table, String name) {
        execute("CREATE OR REPLACE FUNCTION it_noop() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$");
        execute("CREATE TRIGGER " + name + " BEFORE INSERT ON " + table + " FOR EACH ROW EXECUTE FUNCTION it_noop()");
    }

    @Test
    void aTriggerAnywhereStopsReadsBeingReliedOn() {
        String id = "ft-" + UUID.randomUUID();
        run(ItProcessFixtures.CREATE_TICKET.name(), new ItProcessFixtures.TicketInput(id, "first", 1L));
        trigger("it_memo", "it_memo_noop");
        try {
            List<CheckProblem> problems = new MetaModelConsistencyChecker(databaseClient, entities, batching, datasets)
                .check();
            assertThat(problems).filteredOn(p -> p.message().contains("it_memo_noop")).isNotEmpty()
                .allSatisfy(p -> assertThat(p.isError()).isFalse());
            assertThat(batching.reusesReads()).isFalse();

            SqlStatementLog.clear();
            run(ItBudgetFixtures.TOUCH.name(), new ItBudgetFixtures.TouchInput(id, Map.of("title", "second")));
            // The update reads the ticket again rather than relying on the load.
            assertThat(SqlStatementLog.STATEMENTS.stream().filter(sql -> sql.contains("it_ticket"))
                .map(sql -> sql.startsWith("SELECT") ? "read" : "update").toList())
                .containsExactly("read", "read", "update");
        } finally {
            execute("DROP TRIGGER it_memo_noop ON it_memo");
        }
    }

    @Test
    void aTriggerOnAnOverrideTableOrASharedTableStopsCombining() {
        execute("CREATE TABLE it_ticket_copy (LIKE it_ticket INCLUDING ALL)");
        trigger("it_ticket_copy", "it_ticket_copy_noop");
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("copy", DatasetDefinition.define("urn:jabiz:dataset:it:ItTicketCopy", d -> d
            .targetEntityType("ItTicket").permissions("it.read", "it.write")
            .storage(s -> s.connectionPoolRef("default").physicalTableOverride("it_ticket_copy"))));
        DatasetRegistry copies = new DatasetRegistry(beans.getBeanProvider(DatasetDefinition.class), entities,
            storages, environment);
        WriteBatching own = new WriteBatching();

        List<CheckProblem> problems = new MetaModelConsistencyChecker(databaseClient, entities, own, copies).check();

        assertThat(problems).filteredOn(p -> p.message().contains("it_ticket_copy_noop")).isNotEmpty();
        assertThat(own.combines("ItTicket")).isFalse();
        assertThat(own.combines("ItPrice")).isTrue();
        assertThat(own.reusesReads()).isFalse();

        trigger("sys_audit_record", "sys_audit_record_noop");
        try {
            WriteBatching shared = new WriteBatching();
            assertThat(new MetaModelConsistencyChecker(databaseClient, entities, shared, datasets).check())
                .filteredOn(p -> p.message().contains("sys_audit_record_noop")).isNotEmpty();
            assertThat(shared.combines("ItPrice")).isFalse();
        } finally {
            execute("DROP TRIGGER sys_audit_record_noop ON sys_audit_record");
            execute("DROP TABLE it_ticket_copy");
        }
        assertThat(ItFixtures.TICKET_DATASET).isNotBlank();
    }
}

package com.jabiz.app.it.process;

import com.jabiz.app.it.fixture.ItFixtures;
import com.jabiz.app.it.fixture.ItHeldWriteFixtures;
import com.jabiz.app.it.fixture.ItProcessFixtures;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.context.RequestContext;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.BoundValue;
import com.jabiz.query.RawQueryPlan;
import com.jabiz.runtime.ConcurrentUpdateException;
import com.jabiz.runtime.LockNotAvailableException;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.WriteBatching;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.web.ProblemStatuses;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Mono;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The writes a transaction holds back (ROADMAP phase 16p-2, T9; decision D46 rule 1) keep every guarantee of writing
 * them as they come: a held write is written ahead of the statement that carries it, so its conflict is reported
 * before what that statement would have run into; before an error that is not the database's, a blocking step and a
 * transaction of its own; an event is held with the writes and gone with a rollback; a sub-process's record is held
 * with a number drawn ahead in its parent's first statement; an update held and found changing no row is answered as
 * before, even when a later write in the same statement would conflict; a statement that reads a held table through a
 * view, a template, or declares its tables sees the held rows. With the switch off
 * ({@link HeldWritesWithoutHeldWritesIT}) the same answers come, statement by commit.
 */
@SpringBootTest(properties = "it.sql-log.enabled=true")
class HeldWritesIT extends PostgresIntegrationTest {

    private static final RequestContext ADMIN = new RequestContext("it-held", null, Locale.ENGLISH, "it-held",
        Set.of(), Set.of("*"));

    @Autowired
    ProcessExecutor executor;

    @Autowired
    ProcessRegistry processes;

    @Autowired
    StorageAdapterRegistry storages;

    @Autowired
    WriteBatching batching;

    @SuppressWarnings("unchecked")
    private <I, O> O run(String name, I input) {
        ProcessDefinition<I, O, ProcessContext> definition =
            (ProcessDefinition<I, O, ProcessContext>) processes.find(name, 1).orElseThrow();
        return asRequest(ADMIN, executor.execute(definition, input)).block();
    }

    private Throwable failing(String name, Object input) {
        return catchThrowable(() -> run(name, input));
    }

    private static String sku() {
        return "H-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static long count(String sql, Object... params) {
        return ((Number) query(sql, params).getFirst().get("n")).longValue();
    }

    private UUID existingPrice() {
        UUID id = UUID.randomUUID();
        run("IT_HELD", new ItHeldWriteFixtures.HeldInput(id, sku(), null, null));
        return id;
    }

    private static void assertAlreadyExists(Throwable error, UUID id) {
        assertThat(error).isInstanceOf(ConcurrentUpdateException.class)
            .isNotInstanceOf(LockNotAvailableException.class)
            .hasMessage("ItPrice [ID: " + id + "] already exists");
        assertThat(ProblemStatuses.status(error)).isEqualTo(409);
    }

    // ---- what is held is written first --------------------------------------------------------------------------

    @Test
    void aHeldWriteGoesAheadOfTheLockThatCarriesItSoItsConflictIsReported() throws Exception {
        UUID id = existingPrice();
        String lock = "held-" + UUID.randomUUID();
        try (Connection other = DB.connect(schema());
             PreparedStatement hold = other.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?, 0))")) {
            // Another session holds the lock: had the lock gone first, the process would give up waiting (409 busy).
            hold.setString(1, "jabiz.app-lock:" + lock);
            hold.execute();
            SqlStatementLog.clear();

            Throwable error = failing("IT_HELD", new ItHeldWriteFixtures.HeldInput(id, sku(), "lock", lock));

            assertAlreadyExists(error, id);
            if (batching.acrossTransaction()) {
                // One statement: the held price, then (never reached) the lock.
                assertThat(SqlStatementLog.STATEMENTS).anySatisfy(sql -> assertThat(sql)
                    .contains("INSERT INTO it_price").contains("jabiz_xact_lock").contains("jabiz_m WHERE"));
            }
        }
    }

    @Test
    void aFailureOfTheProcessAfterTheSaveReportsTheEarlierConflict() {
        UUID id = existingPrice();

        assertAlreadyExists(failing("IT_HELD", new ItHeldWriteFixtures.HeldInput(id, sku(), "fail", null)), id);
    }

    @Test
    void aBlockingStepRunsOnlyOnceWhatIsHeldIsWritten() {
        UUID id = existingPrice();
        int before = ItHeldWriteFixtures.BLOCKING_RUNS.get();

        assertAlreadyExists(failing("IT_HELD", new ItHeldWriteFixtures.HeldInput(id, sku(), "blocking", null)), id);
        // It may call another system: it never ran for a write that was going to fail.
        assertThat(ItHeldWriteFixtures.BLOCKING_RUNS.get()).isEqualTo(before);

        run("IT_HELD", new ItHeldWriteFixtures.HeldInput(UUID.randomUUID(), sku(), "blocking", null));
        assertThat(ItHeldWriteFixtures.BLOCKING_RUNS.get()).isEqualTo(before + 1);
    }

    // ---- events, sub-processes -----------------------------------------------------------------------------------

    @Test
    void anEventIsHeldWithTheWritesAndARollbackLeavesNone() {
        UUID failed = UUID.randomUUID();
        assertThat(failing("IT_HELD", new ItHeldWriteFixtures.HeldInput(failed, sku(), "event-fail", null)))
            .isInstanceOf(IllegalStateException.class).hasMessage("the step after the save failed");
        assertThat(count("SELECT count(*) AS n FROM sys_outbox_event WHERE event_type = ? AND payload->>'priceId' = ?",
            ItHeldWriteFixtures.EVENT, failed.toString())).isZero();
        assertThat(count("SELECT count(*) AS n FROM it_price WHERE price_id = ?", failed)).isZero();

        UUID done = UUID.randomUUID();
        SqlStatementLog.clear();
        run("IT_HELD", new ItHeldWriteFixtures.HeldInput(done, sku(), "event", null));

        assertThat(count("SELECT count(*) AS n FROM sys_outbox_event WHERE event_type = ? AND payload->>'priceId' = ?",
            ItHeldWriteFixtures.EVENT, done.toString())).isEqualTo(1);
        if (batching.acrossTransaction()) {
            // Written with the price, in one statement.
            assertThat(SqlStatementLog.STATEMENTS).anySatisfy(sql -> assertThat(sql)
                .contains("INSERT INTO sys_outbox_event").contains("INSERT INTO it_price"));
        }
    }

    @Test
    void aSubProcessesRecordIsHeldWithANumberItsRootDrewAhead() {
        UUID parent = UUID.randomUUID();
        SqlStatementLog.clear();
        run("IT_HELD", new ItHeldWriteFixtures.HeldInput(parent, sku(), "child", null));

        Map<String, Object> root = query("SELECT p.process_seq_id FROM op_process p JOIN it_price v"
            + " ON v.process_seq_id = p.process_seq_id WHERE v.price_id = ?", parent).getFirst();
        long rootSeq = ((Number) root.get("process_seq_id")).longValue();
        List<Map<String, Object>> children = query("SELECT process_seq_id, process_name FROM op_process"
            + " WHERE parent_seq_id = ?", rootSeq);
        assertThat(children).singleElement().satisfies(child -> {
            assertThat(child.get("process_name")).isEqualTo("IT_HELD_INSERT");
            // After its parent, in the order of execution.
            assertThat(((Number) child.get("process_seq_id")).longValue()).isGreaterThan(rootSeq);
        });
        // Its writes carry its number.
        long childSeq = ((Number) children.getFirst().get("process_seq_id")).longValue();
        assertThat(count("SELECT count(*) AS n FROM it_price WHERE process_seq_id = ?", childSeq)).isEqualTo(1);
        if (batching.acrossTransaction()) {
            assertThat(childSeq).isEqualTo(rootSeq + 1);
            // Never a statement of its own.
            assertThat(SqlStatementLog.STATEMENTS).noneSatisfy(sql -> assertThat(sql)
                .startsWith("INSERT INTO op_process (process_seq_id, parent_seq_id"));
            assertThat(SqlStatementLog.STATEMENTS.getFirst()).contains("jabiz_reserved");
        }
    }

    // ---- an update held -----------------------------------------------------------------------------------------

    @Test
    void anUpdateFoundChangedMeanwhileIsAConflictEvenWithALaterConflictInTheSameStatement() {
        String ticket = "held-" + UUID.randomUUID();
        run(ItProcessFixtures.CREATE_TICKET.name(), new ItProcessFixtures.TicketInput(ticket, "first", 1L));
        String code = "C" + UUID.randomUUID().toString().substring(0, 8);
        asRequest(ADMIN, storages.getEngine("default").inTransaction(storages.getEngine("default")
            .insert("it_unique", Map.of("f_id", UUID.randomUUID().toString(), "f_code", code)))).block();
        SqlStatementLog.clear();

        // Written as they came, the update finds the row changed first; held, both are one statement, read in turn.
        Throwable error = failing("IT_HELD_TOUCH_THEN_INSERT", new ItHeldWriteFixtures.TouchThenInsert(ticket, code));

        assertThat(error).isInstanceOf(ConcurrentUpdateException.class)
            .hasMessageContaining("Optimistic lock conflict on ItTicket [ID: " + ticket + "]");
        assertThat(ProblemStatuses.status(error)).isEqualTo(409);
        assertThat(count("SELECT count(*) AS n FROM sys_audit_record WHERE entity_id = ? AND action = 'UPDATE'"
            + " AND changes LIKE '%late%'", ticket)).isZero();
        if (batching.acrossTransaction()) {
            assertThat(SqlStatementLog.STATEMENTS).anySatisfy(sql -> assertThat(sql)
                .contains("UPDATE it_ticket").contains("INSERT INTO it_unique").contains("jabiz_stale_"));
        }
    }

    // ---- statements that read what is held ----------------------------------------------------------------------

    private StorageEngine engine() {
        return storages.getEngine("default");
    }

    private Mono<Void> heldTicket(String id) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("f_id", id);
        row.put("f_title", "held");
        row.put("f_amount", 1L);
        row.put("f_status", "OPEN");
        row.put("f_owner", "it-owner");
        row.put("f_created_at", START);
        row.put("f_version", 1L);
        return engine().withWriteBatch(engine().insert("it_ticket", row));
    }

    private long inTransaction(String id, java.util.function.Function<String, Mono<Long>> read) {
        return asRequest(ADMIN, engine().inTransaction(heldTicket(id).then(read.apply(id)))).block();
    }

    @Test
    void aViewOverAHeldTableSeesItsRows() {
        execute("CREATE VIEW it_ticket_titles AS SELECT f_id, f_title FROM it_ticket");
        try {
            // Learnt as the startup check learns the views of the schema.
            batching.view("it_ticket_titles", List.of("it_ticket"));
            String id = "held-" + UUID.randomUUID();

            long seen = inTransaction(id, key -> engine().select("SELECT count(*) AS n FROM it_ticket_titles"
                    + " WHERE f_id = :id", Map.of("id", BoundValue.of(key))).next()
                .map(row -> ((Number) row.get("n")).longValue()));

            assertThat(seen).isEqualTo(1);
        } finally {
            execute("DROP VIEW it_ticket_titles");
        }
    }

    @Test
    void aTemplateAndADeclaredReadSeeTheHeldRows() {
        String id = "held-" + UUID.randomUUID();
        long template = inTransaction(id, key -> engine().executeRawQuery(new RawQueryPlan(
                "SELECT count(*) AS n FROM it_ticket WHERE f_id = :id", Map.of("id", BoundValue.of(key)),
                Duration.ofSeconds(5))).next()
            .map(row -> ((Number) row.get("n")).longValue()));
        assertThat(template).isEqualTo(1);

        String other = "held-" + UUID.randomUUID();
        // The text names another table only; the declared table is what counts.
        long declared = inTransaction(other, key -> engine().select("SELECT count(*) AS n FROM it_ticket t"
                + " WHERE t.f_id = :id", Map.of("id", BoundValue.of(key)), Set.of("it_ticket")).next()
            .map(row -> ((Number) row.get("n")).longValue()));
        assertThat(declared).isEqualTo(1);
    }

    @Test
    void aQueryOfOtherTablesCarriesTheHeldRowsAndAReadOfTheirTableSeesThem() {
        String id = "held-" + UUID.randomUUID();
        SqlStatementLog.clear();
        long seen = inTransaction(id, key -> engine().select("SELECT 1 AS n FROM it_unique LIMIT 1", Map.of())
            .then(engine().select("SELECT count(*) AS n FROM it_ticket WHERE f_id = :id",
                Map.of("id", BoundValue.of(key))).next().map(row -> ((Number) row.get("n")).longValue())));

        assertThat(seen).isEqualTo(1);
        if (batching.acrossTransaction()) {
            assertThat(SqlStatementLog.STATEMENTS).anySatisfy(sql -> assertThat(sql)
                .startsWith("WITH jabiz_w0 AS (INSERT INTO it_ticket").contains("FROM it_unique"));
        }
    }

    @Test
    void aTransactionOfItsOwnStartsAfterWhatIsHeldIsWritten() {
        String id = "held-" + UUID.randomUUID();
        // The suspended transaction's insert holds its lock on the table by the time the other one looks.
        long locks = asRequest(ADMIN, engine().inTransaction(engine().select("SELECT pg_backend_pid() AS pid", Map.of())
            .next().map(row -> ((Number) row.get("pid")).intValue())
            .flatMap(pid -> heldTicket(id).then(engine().inNewTransaction(engine().select(
                "SELECT count(*) AS n FROM pg_locks WHERE pid = :pid AND relation = 'it_ticket'::regclass"
                    + " AND mode = 'RowExclusiveLock'", Map.of("pid", BoundValue.of(pid))).next()
                .map(row -> ((Number) row.get("n")).longValue())))))).block();

        assertThat(locks).isEqualTo(1);
        assertThat(count("SELECT count(*) AS n FROM it_ticket WHERE f_id = ?", id)).isEqualTo(1);
    }

    @Autowired
    com.jabiz.runtime.DatasetEntityManager entities;

    @Autowired
    com.jabiz.runtime.dataset.DatasetRegistry datasets;

    private Mono<List<com.jabiz.runtime.EntityInstance>> commitTicket(String id) {
        Map<String, Object> ticket = Map.of("ticketId", id, "title", "row " + id, "amount", 1L, "status", "OPEN",
            "owner", "it-owner");
        return entities.commitBatch(datasets.findById(ItFixtures.TICKET_DATASET).orElseThrow(), List.of(
            new com.jabiz.runtime.EntityChange(com.jabiz.runtime.EntityAction.INSERT,
                new com.jabiz.runtime.EntityInstance(id, "ItTicket", 0, null, ticket))));
    }

    @Test
    void aRowThatFailsBehindItsSavepointLeavesNoneOfItsHeldWritesItsAuditRecordIncluded() {
        // How an import runs its rows (docs/design/20-imports.md section 5): each behind a savepoint of its own.
        String failed = "held-" + UUID.randomUUID();
        String kept = "held-" + UUID.randomUUID();
        Mono<Void> rows = engine().inSavepoint(commitTicket(failed)
                .then(Mono.<Void>error(new IllegalStateException("the row fails after its commit"))))
            .onErrorResume(IllegalStateException.class, e -> Mono.empty())
            .then(engine().inSavepoint(commitTicket(kept)).then());

        asRequest(ADMIN, engine().inTransaction(rows)).block();

        assertThat(count("SELECT count(*) AS n FROM it_ticket WHERE f_id = ?", failed)).isZero();
        assertThat(count("SELECT count(*) AS n FROM sys_audit_record WHERE entity_id = ?", failed)).isZero();
        assertThat(count("SELECT count(*) AS n FROM it_ticket WHERE f_id = ?", kept)).isEqualTo(1);
        assertThat(count("SELECT count(*) AS n FROM sys_audit_record WHERE entity_id = ?", kept)).isEqualTo(1);
    }

    // ---- review of 16p-2 ------------------------------------------------------------------------------------------

    private String ticketAt(String title) {
        String id = "held-" + UUID.randomUUID();
        run(ItProcessFixtures.CREATE_TICKET.name(), new ItProcessFixtures.TicketInput(id, title, 1L));
        return id;
    }

    /** An update of the ticket at {@code version}, held; changing no row, it fails with 409 "not applied". */
    private Mono<Boolean> heldUpdate(String id, long version, String title) {
        // As DatasetEntityManager answers an update that changed no row, whether it was held (16p-2) or not (16p-1).
        java.util.function.Supplier<Mono<Throwable>> notApplied =
            () -> Mono.just(ConcurrentUpdateException.stale("not applied: " + id));
        return engine().withWriteBatch(engine().casUpdate("it_ticket", "f_id", id, version, "f_version",
                Map.of("f_title", title), List.of(), notApplied))
            .flatMap(applied -> applied ? Mono.just(true) : notApplied.get().flatMap(Mono::<Boolean>error));
    }

    private static String title(String id) {
        return String.valueOf(query("SELECT f_title FROM it_ticket WHERE f_id = ?", id).getFirst().get("f_title"));
    }

    @Test
    void aHeldUpdateIsNeverLostToAQueryThePlannerKnowsIsEmpty() {
        // PostgreSQL drops a condition on no row with a sub-select it proves empty: an update is never carried.
        String stale = ticketAt("stale");
        Throwable error = catchThrowable(() -> asRequest(ADMIN, engine().inTransaction(heldUpdate(stale, 7L, "lost")
            .thenMany(engine().select("SELECT * FROM it_unique WHERE 1 = 0", Map.of())).then())).block());
        assertThat(error).isInstanceOf(ConcurrentUpdateException.class).hasMessageContaining("not applied: " + stale);
        assertThat(ProblemStatuses.status(error)).isEqualTo(409);

        String applied = ticketAt("before");
        asRequest(ADMIN, engine().inTransaction(heldUpdate(applied, 1L, "after")
            .thenMany(engine().select("SELECT * FROM it_unique WHERE 1 = 0", Map.of())).then())).block();
        assertThat(title(applied)).isEqualTo("after");
    }

    @Test
    void aReadBoundByItsTimeoutWritesWhatIsHeldOnItsOwnFirst() {
        String id = "held-" + UUID.randomUUID();
        SqlStatementLog.clear();
        long seen = asRequest(ADMIN, engine().inTransaction(heldTicket(id).then(engine().executeQuery(
                new com.jabiz.query.PhysicalQueryPlan("it_unique", null, Map.of(), List.of(), 0, 1,
                    Duration.ofSeconds(5))).count()))).block();

        assertThat(seen).isNotNegative();
        assertThat(SqlStatementLog.STATEMENTS).anySatisfy(sql -> assertThat(sql)
            .startsWith("INSERT INTO it_ticket"));
        // The read's own bound is not stretched by a write's lock wait: never carried.
        assertThat(SqlStatementLog.STATEMENTS).noneSatisfy(sql -> assertThat(sql).contains("jabiz_m"));
        assertThat(count("SELECT count(*) AS n FROM it_ticket WHERE f_id = ?", id)).isEqualTo(1);
    }

    @Test
    void aHeldUpdateThatChangesNoRowBehindASavepointIsAnsweredThereAndTheTransactionGoesOn() {
        String stale = ticketAt("stale");
        String kept = "held-" + UUID.randomUUID();
        List<Throwable> answered = new java.util.ArrayList<>();
        asRequest(ADMIN, engine().inTransaction(engine().inSavepoint(heldUpdate(stale, 7L, "lost"))
            .onErrorResume(error -> {
                answered.add(error);
                return Mono.empty();
            })
            .then(heldTicket(kept)))).block();

        assertThat(answered).singleElement().isInstanceOf(ConcurrentUpdateException.class);
        assertThat(title(stale)).isEqualTo("stale");
        assertThat(count("SELECT count(*) AS n FROM it_ticket WHERE f_id = ?", kept)).isEqualTo(1);
    }

    @Test
    void aHeldUpdateThatChangesNoRowInATransactionOfItsOwnFailsItAloneAndTheSuspendedOneGoesOn() {
        String stale = ticketAt("stale");
        String kept = "held-" + UUID.randomUUID();
        List<Throwable> answered = new java.util.ArrayList<>();
        asRequest(ADMIN, engine().inTransaction(heldTicket(kept).then(engine().inNewTransaction(
                heldUpdate(stale, 7L, "lost"))
            .onErrorResume(error -> {
                answered.add(error);
                return Mono.empty();
            })).then())).block();

        assertThat(answered).singleElement().isInstanceOf(ConcurrentUpdateException.class);
        assertThat(title(stale)).isEqualTo("stale");
        assertThat(count("SELECT count(*) AS n FROM it_ticket WHERE f_id = ?", kept)).isEqualTo(1);
    }

    @Test
    void aConflictOfAHeldWriteSurfacingLaterIsReportedAloneNotWithViolationsCollectedAfterIt() {
        String code = "C" + UUID.randomUUID().toString().substring(0, 8);
        asRequest(ADMIN, engine().inTransaction(engine().insert("it_unique", Map.of("f_id", "other-" + code,
            "f_code", code)))).block();

        Throwable error = failing("IT_HELD_UNIQUE_THEN_REJECT", new ItHeldWriteFixtures.TouchThenInsert(null, code));

        // Written as it came, the insert failed at its save, before the later step collected IT_HELD_LATER.
        assertThat(error).isInstanceOf(com.jabiz.entity.ValidationException.class);
        assertThat(((com.jabiz.entity.ValidationException) error).violations())
            .extracting(com.jabiz.entity.Violation::ruleCode).containsExactly("UNIQUE_VIOLATION");
    }

    // ---- real concurrency ---------------------------------------------------------------------------------------

    private static Connection openTransaction() throws java.sql.SQLException {
        Connection other = DB.connect(schema());
        other.setAutoCommit(false);
        return other;
    }

    private static void sql(Connection connection, String sql, Object... params) throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            statement.execute();
        }
    }

    private static java.util.concurrent.CompletableFuture<Throwable> inBackground(Runnable work) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> catchThrowable(work::run));
    }

    @Test
    void aHeldInsertWaitsForAnotherTransactionsSameKeyAndConflictsWhenItCommits() throws Exception {
        String id = "held-" + UUID.randomUUID();
        try (Connection other = openTransaction()) {
            sql(other, "INSERT INTO it_ticket (f_id, f_title, f_created_at) VALUES (?, 'other', now())", id);
            var process = inBackground(() -> run(ItProcessFixtures.CREATE_TICKET.name(),
                new ItProcessFixtures.TicketInput(id, "mine", 1L)));
            Thread.sleep(700);
            assertThat(process).isNotDone();
            other.commit();

            Throwable error = process.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(ProblemStatuses.status(error)).isEqualTo(409);
        }
        assertThat(title(id)).isEqualTo("other");
    }

    @Test
    void aHeldInsertGivesUpAtTheTransactionsLockTimeout() throws Exception {
        String id = "held-" + UUID.randomUUID();
        @SuppressWarnings("unchecked")
        ProcessDefinition<ItProcessFixtures.TicketInput, Object, ProcessContext> create =
            (ProcessDefinition<ItProcessFixtures.TicketInput, Object, ProcessContext>)
                processes.find(ItProcessFixtures.CREATE_TICKET.name(), 1).orElseThrow();
        try (Connection other = openTransaction()) {
            sql(other, "INSERT INTO it_ticket (f_id, f_title, f_created_at) VALUES (?, 'other', now())", id);

            Throwable error = catchThrowable(() -> asRequest(ADMIN, executor.run(create,
                new ItProcessFixtures.TicketInput(id, "mine", 1L),
                com.jabiz.runtime.process.ExecutionOptions.NONE.withLockWait(Duration.ofMillis(400)))).block());

            assertThat(error).isInstanceOf(LockNotAvailableException.class);
            assertThat(ProblemStatuses.status(error)).isEqualTo(409);
            other.rollback();
        }
        assertThat(count("SELECT count(*) AS n FROM it_ticket WHERE f_id = ?", id)).isZero();
    }

    @Test
    void aHeldUpdateWaitsForAnotherTransactionsChangeAndIsAConflictWhenItCommits() throws Exception {
        String id = ticketAt("first");
        try (Connection other = openTransaction()) {
            sql(other, "UPDATE it_ticket SET f_title = 'other', f_version = f_version + 1 WHERE f_id = ?", id);
            var process = inBackground(() -> run("IT_BUDGET_TOUCH", new com.jabiz.app.it.fixture.ItBudgetFixtures
                .TouchInput(id, Map.of("title", "mine"))));
            Thread.sleep(700);
            assertThat(process).isNotDone();
            other.commit();

            Throwable error = process.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(error).isInstanceOf(ConcurrentUpdateException.class)
                .hasMessageContaining("Optimistic lock conflict on ItTicket [ID: " + id + "]");
            assertThat(ProblemStatuses.status(error)).isEqualTo(409);
        }
        assertThat(title(id)).isEqualTo("other");
    }

    // ---- sealing, numbering, the driver -------------------------------------------------------------------------

    @Test
    void whatIsHeldIsSealedBySealingInTheSameTransactionAndVerifies() {
        run(com.jabiz.runtime.integrity.IntegrityProcesses.SEAL,
            new com.jabiz.runtime.integrity.IntegrityProcesses.SealInput(null));

        ItHeldWriteFixtures.SealedOutput sealed = run("IT_HELD_THEN_SEAL",
            new ItHeldWriteFixtures.HeldInput(UUID.randomUUID(), sku(), null, null));

        // Its operation, and the price's registry entry, version, item and audit record, all written before sealing.
        assertThat(sealed.rowCount()).isGreaterThanOrEqualTo(5);
        com.jabiz.runtime.integrity.IntegrityProcesses.VerifyOutput verified = run(
            com.jabiz.runtime.integrity.IntegrityProcesses.VERIFY,
            new com.jabiz.runtime.integrity.IntegrityProcesses.VerifyInput(null, null));
        assertThat(verified.intact()).isTrue();
        assertThat(verified.problemCount()).isZero();
    }

    @Test
    void subProcessesAreNumberedInTheOrderTheyRunAForEachAndALaterCallAlike() {
        String prefix = "M-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        List<String> skus = List.of(prefix + "a", prefix + "b", prefix + "c", prefix + "d");
        run("IT_HELD_MANY", new ItHeldWriteFixtures.ManyInput(skus));

        List<Map<String, Object>> children = query("SELECT p.process_seq_id, v.sku FROM op_process p"
            + " JOIN it_price v ON v.process_seq_id = p.process_seq_id WHERE v.sku LIKE ? ORDER BY p.process_seq_id",
            prefix + "%");
        assertThat(children).extracting(row -> row.get("sku")).containsExactlyElementsOf(skus);
        long parent = ((Number) query("SELECT parent_seq_id FROM op_process WHERE process_seq_id = ?",
            children.getFirst().get("process_seq_id")).getFirst().get("parent_seq_id")).longValue();
        assertThat(((Number) children.getFirst().get("process_seq_id")).longValue()).isGreaterThan(parent);
    }

    @Autowired
    io.r2dbc.spi.ConnectionFactory connections;

    @Test
    void theDriversStatementCacheIsBounded() throws Exception {
        Object factory = connections;
        while (factory instanceof io.r2dbc.spi.Wrapped<?> wrapped && wrapped.unwrap() != null
            && wrapped.unwrap() != factory) {
            factory = wrapped.unwrap();
        }
        assertThat(factory.getClass().getName()).isEqualTo("io.r2dbc.postgresql.PostgresqlConnectionFactory");
        java.lang.reflect.Method configuration = factory.getClass().getDeclaredMethod("getConfiguration");
        configuration.setAccessible(true);
        Object config = configuration.invoke(factory);
        java.lang.reflect.Method cache = config.getClass().getDeclaredMethod("getPreparedStatementCacheQueries");
        cache.setAccessible(true);
        assertThat(cache.invoke(config)).isEqualTo(256);
    }

    @Test
    void aTicketCreatedAfterAHeldInsertIsThere() {
        // The plain path end to end: insert, held, written before COMMIT.
        String id = "held-" + UUID.randomUUID();
        run(ItProcessFixtures.CREATE_TICKET.name(), new ItProcessFixtures.TicketInput(id, "plain", 2L));
        assertThat(count("SELECT count(*) AS n FROM it_ticket WHERE f_id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) AS n FROM sys_audit_record WHERE entity_type = ? AND entity_id = ?",
            ItFixtures.TICKET.name, id)).isEqualTo(1);
    }
}

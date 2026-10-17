package com.jabiz.app.it.process;

import com.jabiz.app.it.fixture.ItBudgetFixtures;
import com.jabiz.app.it.fixture.ItFixtures;
import com.jabiz.app.it.fixture.ItProcessFixtures;
import com.jabiz.app.it.fixture.ItTemporalFixtures;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.ConcurrentUpdateException;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.storage.WriteBatching;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.web.ProblemStatuses;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The guarantees behind fewer statements per process (ROADMAP phase 16p, decision D46): a commit's rows written
 * together keep every constraint, conflict and audit record they had written one by one; a new temporal instance
 * whose key exists is refused by the database with the same 409; a reference is looked up unless the transaction
 * inserted the target or read it through its default dataset, and never after it wrote it again; an update at the
 * version the transaction read refuses a concurrent change (409) or deletion (404) as before, and its audit record and
 * event exist only when it updated the row.
 */
@SpringBootTest(properties = "it.sql-log.enabled=true")
class WriteCombiningIT extends PostgresIntegrationTest {

    private static final RequestContext ADMIN = new RequestContext("it-writer", null, Locale.ENGLISH, "it-writer",
        Set.of(), Set.of("*"));

    @Autowired
    DatasetEntityManager entities;

    @Autowired
    DatasetRegistry datasets;

    @Autowired
    EntityDefinitionRegistry definitions;

    @Autowired
    ProcessExecutor executor;

    @Autowired
    ProcessRegistry processes;

    // ---- helpers -------------------------------------------------------------------------------------------------

    private List<EntityInstance> commit(String dataset, EntityChange... changes) {
        return asRequest(ADMIN, entities.commitBatch(datasets.findById(dataset).orElseThrow(), List.of(changes)))
            .block();
    }

    private Throwable failure(String dataset, EntityChange... changes) {
        return catchThrowable(() -> commit(dataset, changes));
    }

    private static EntityChange insert(String entity, Object id, Map<String, Object> attributes) {
        return new EntityChange(EntityAction.INSERT, new EntityInstance(id, entity, 0, null, attributes));
    }

    private static Map<String, Object> price(UUID id, String sku) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("priceId", id);
        attributes.put("sku", sku);
        attributes.put("region", "JP");
        attributes.put("amount", 100);
        attributes.put("status", "DRAFT");
        return attributes;
    }

    private static EntityChange note(UUID price, String body) {
        UUID id = UUID.randomUUID();
        return insert("ItNote", id, Map.of("noteId", id, "priceRef", price, "body", body));
    }

    private static String sku() {
        return "W-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @SuppressWarnings("unchecked")
    private <I, O> O run(String name, I input) {
        ProcessDefinition<I, O, ProcessContext> definition =
            (ProcessDefinition<I, O, ProcessContext>) processes.find(name, 1).orElseThrow();
        return asRequest(ADMIN, executor.execute(definition, input)).block();
    }

    private Throwable runFailing(String name, Object input) {
        return catchThrowable(() -> run(name, input));
    }

    private static List<String> codes(Throwable error) {
        List<Violation> violations = error instanceof BusinessRuleViolationException rule ? rule.violations()
            : ((ValidationException) error).violations();
        return violations.stream().map(Violation::ruleCode).toList();
    }

    private static long count(String sql, Object... params) {
        return ((Number) query(sql, params).getFirst().get("n")).longValue();
    }

    // ---- a new temporal instance (T3) ----------------------------------------------------------------------------

    @Test
    void aNewInstanceWhoseKeyExistsIsTheSameConflictAsBefore() {
        UUID id = UUID.randomUUID();
        commit(ItTemporalFixtures.PRICE_DATASET, insert("ItPrice", id, price(id, sku())));

        Throwable again = failure(ItTemporalFixtures.PRICE_DATASET, insert("ItPrice", id, price(id, sku())));

        // The message the check before writing gave (it read the instance's versions first).
        assertThat(again).isInstanceOf(ConcurrentUpdateException.class)
            .hasMessage("ItPrice [ID: " + id + "] already exists");
        assertThat(ProblemStatuses.status(again)).isEqualTo(409);
        assertThat(count("SELECT count(*) AS n FROM it_price WHERE price_id = ?", id)).isEqualTo(1);
    }

    @Test
    void twoNewInstancesOfOneKeyInOneCommitAreAConflictAndNothingIsWritten() {
        UUID id = UUID.randomUUID();
        String first = sku();

        Throwable twice = failure(ItTemporalFixtures.PRICE_DATASET, insert("ItPrice", id, price(id, first)),
            insert("ItPrice", id, price(id, sku())));

        assertThat(twice).isInstanceOf(ConcurrentUpdateException.class)
            .hasMessage("ItPrice [ID: " + id + "] already exists");
        assertThat(count("SELECT count(*) AS n FROM entity_registry WHERE entity_id = ?", id)).isZero();
        assertThat(count("SELECT count(*) AS n FROM it_price WHERE sku = ?", first)).isZero();
    }

    @Test
    void aWriteOnceInstanceWhoseKeyExistsIsAConflictToo() {
        UUID id = UUID.randomUUID();
        Map<String, Object> transaction = Map.of("transactionId", id, "bookingTime", clock.instant(),
            "description", "written once");
        commit(LedgerEntities.TRANSACTION_DATASET, insert(LedgerEntities.TRANSACTION, id, transaction));

        assertThat(failure(LedgerEntities.TRANSACTION_DATASET, insert(LedgerEntities.TRANSACTION, id, transaction)))
            .isInstanceOf(ConcurrentUpdateException.class)
            .hasMessage(LedgerEntities.TRANSACTION + " [ID: " + id + "] already exists");
    }

    // ---- rows written together (T6) ------------------------------------------------------------------------------

    @Test
    void aDeclaredUniqueConstraintAndADuplicateKeyMapAsBeforeWithinOneStatement() {
        String code = "U" + UUID.randomUUID().toString().substring(0, 8);
        Throwable unique = failure(ItFixtures.UNIQUE_DATASET,
            insert("ItUnique", "u-" + UUID.randomUUID(), Map.of("code", code)),
            insert("ItUnique", "u-" + UUID.randomUUID(), Map.of("code", code)));
        assertThat(unique).isInstanceOf(ValidationException.class);
        assertThat(codes(unique)).containsExactly(PlatformErrorCodes.UNIQUE_VIOLATION);
        assertThat(ProblemStatuses.status(unique)).isEqualTo(400);

        String key = "t-" + UUID.randomUUID();
        Map<String, Object> ticket = Map.of("ticketId", key, "title", "twice", "amount", 1, "status", "OPEN",
            "owner", "it-owner");
        Throwable duplicate = failure(ItFixtures.TICKET_DATASET, insert("ItTicket", key, ticket),
            insert("ItTicket", key, ticket));
        assertThat(duplicate).isInstanceOf(ConcurrentUpdateException.class).hasMessageContaining("ItTicket");
        assertThat(count("SELECT count(*) AS n FROM it_ticket WHERE f_id = ?", key)).isZero();
    }

    @Test
    void theLedgersBalanceIsCheckedAtCommitForRowsWrittenTogether() {
        UUID transaction = UUID.randomUUID();
        commit(LedgerEntities.TRANSACTION_DATASET, insert(LedgerEntities.TRANSACTION, transaction,
            Map.of("transactionId", transaction, "bookingTime", clock.instant(), "description", "unbalanced")));
        String prefix = "WC" + UUID.randomUUID().toString().substring(0, 6) + "-";
        List<Object> accounts = new ArrayList<>();
        for (String code : List.of("1100", "4000")) {
            UUID account = UUID.randomUUID();
            commit(LedgerEntities.ACCOUNT_DATASET, insert(LedgerEntities.ACCOUNT, account, Map.of("accountId", account,
                "accountCode", prefix + code, "accountName", code, "accountType", "ASSET", "enabled", true)));
            accounts.add(account);
        }
        SqlStatementLog.clear();

        // Past the check before writing (only LEDGER_POST makes it): debit 10, credit 4, in one statement.
        Throwable error = failure(LedgerEntities.ENTRY_DATASET,
            entry(transaction, accounts.get(0), 1, Direction.DEBIT, "10"),
            entry(transaction, accounts.get(1), 2, Direction.CREDIT, "4"));

        assertThat(sqlState(error)).isEqualTo("JZ002");
        assertThat(SqlStatementLog.STATEMENTS).filteredOn(sql -> SqlStatementLog.insertedTables(sql)
            .contains("ledger_entry_version")).singleElement()
            .satisfies(sql -> assertThat(sql).contains("), ("));
        assertThat(count("SELECT count(*) AS n FROM ledger_entry_version WHERE transaction_id = ?", transaction))
            .isZero();
    }

    private static EntityChange entry(UUID transaction, Object account, int line, Direction direction,
        String amount) {
        UUID id = UUID.randomUUID();
        return insert(LedgerEntities.ENTRY, id, Map.of("entryId", id, "transactionId", transaction, "accountId", account,
            "lineNo", line, "direction", direction.name(), "amount", new BigDecimal(amount)));
    }

    private static String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof io.r2dbc.spi.R2dbcException r2dbc && r2dbc.getSqlState() != null) {
                return r2dbc.getSqlState();
            }
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /**
     * The audit trail of a posting is the same whether its rows are written together or one statement at a time:
     * the same rows in the same order, the same values before and after.
     */
    @Test
    void theAuditRecordsAreTheSameWrittenTogetherOrOneByOne() {
        String prefix = "WA" + UUID.randomUUID().toString().substring(0, 6) + "-";
        for (String code : List.of("1100", "4000")) {
            UUID account = UUID.randomUUID();
            commit(LedgerEntities.ACCOUNT_DATASET, insert(LedgerEntities.ACCOUNT, account, Map.of("accountId", account,
                "accountCode", prefix + code, "accountName", code, "accountType", "ASSET", "enabled", true)));
        }
        LedgerProcesses.PostInput posting = new LedgerProcesses.PostInput(null, "audited", "ref", List.of(
            new LedgerProcesses.Line(prefix + "1100", Direction.DEBIT, new BigDecimal("12"), "memo", Map.of()),
            new LedgerProcesses.Line(prefix + "4000", Direction.CREDIT, new BigDecimal("12"))));

        SqlStatementLog.clear();
        LedgerProcesses.PostOutput together = run(LedgerProcesses.POST, posting);
        long combined = SqlStatementLog.STATEMENTS.size();
        SqlStatementLog.clear();
        ProcessDefinition<?, ?, ?> post = processes.find(LedgerProcesses.POST, 1).orElseThrow();
        @SuppressWarnings("unchecked")
        LedgerProcesses.PostOutput oneByOne = asRequest(ADMIN, executor.execute(
                (ProcessDefinition<LedgerProcesses.PostInput, LedgerProcesses.PostOutput, ProcessContext>) post, posting)
            .contextWrite(WriteBatching::statementByStatement)).block();
        long separate = SqlStatementLog.STATEMENTS.size();

        assertThat(separate).isGreaterThan(combined);
        assertThat(audit(together.transactionId())).isEqualTo(audit(oneByOne.transactionId())).hasSize(3);
    }

    /** The audit records of a posting's operation, ids and numbers replaced by their order of appearance. */
    private List<String> audit(String transactionId) {
        Object seq = query("SELECT process_seq_id FROM ledger_transaction_version WHERE transaction_id = ?::uuid",
            transactionId).getFirst().get("process_seq_id");
        List<Map<String, Object>> rows = query("SELECT entity_type, action, version_no, effect_start_time, changes::text"
            + " AS changes, changed_fields::text AS fields, actor_id, recorded_time, reason FROM sys_audit_record"
            + " WHERE process_seq_id = ? ORDER BY record_no", seq);
        Map<String, String> ids = new LinkedHashMap<>();
        List<String> normalized = new ArrayList<>();
        Pattern uuid = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        for (Map<String, Object> row : rows) {
            Matcher matcher = uuid.matcher(row.toString());
            StringBuilder text = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(text, ids.computeIfAbsent(matcher.group(), k -> "id" + ids.size()));
            }
            matcher.appendTail(text);
            normalized.add(text.toString());
        }
        return normalized;
    }

    // ---- references (T4) -----------------------------------------------------------------------------------------

    @Test
    void aMissingOrDeletedTargetIsStillReported() {
        UUID missing = UUID.randomUUID();
        Throwable none = failure(ItTemporalFixtures.NOTE_DATASET, note(missing, "of nothing"));
        assertThat(codes(none)).containsExactly(PlatformErrorCodes.REFERENCE_NOT_FOUND);

        UUID id = UUID.randomUUID();
        EntityInstance price = commit(ItTemporalFixtures.PRICE_DATASET, insert("ItPrice", id, price(id, sku())))
            .getFirst();
        commit(ItTemporalFixtures.PRICE_DATASET, new EntityChange(EntityAction.DELETE,
            new EntityInstance(id, "ItPrice", price.version(), null, Map.of())));
        Throwable deleted = failure(ItTemporalFixtures.NOTE_DATASET, note(id, "of a deleted price"));
        assertThat(codes(deleted)).containsExactly(PlatformErrorCodes.REFERENCE_NOT_FOUND);
    }

    @Test
    void aTargetInsertedInTheSameCommitIsKnownWithoutALookup() {
        UUID id = UUID.randomUUID();
        SqlStatementLog.clear();
        commit(ItTemporalFixtures.PRICE_DATASET, insert("ItPrice", id, price(id, sku())),
            note(id, "same commit"));

        assertThat(count("SELECT count(*) AS n FROM it_note WHERE price_ref = ?", id)).isEqualTo(1);
        // The price's uniqueness is looked up; the note's reference to it is not.
        assertThat(SqlStatementLog.STATEMENTS).filteredOn(sql -> sql.startsWith("SELECT * FROM (SELECT DISTINCT ON "
            + "(price_id) * FROM it_price")).isEmpty();
    }

    @Test
    void onlyAReadThroughTheDefaultDatasetSparesTheLookup() {
        UUID id = UUID.randomUUID();
        commit(ItTemporalFixtures.PRICE_DATASET, insert("ItPrice", id, price(id, sku())));

        SqlStatementLog.clear();
        run(ItBudgetFixtures.NOTE_AFTER_DEFAULT_READ.name(), new ItBudgetFixtures.NoteInput(id.toString(), false));
        assertThat(priceReads()).isEqualTo(1);

        SqlStatementLog.clear();
        run(ItBudgetFixtures.NOTE_AFTER_SCOPED_READ.name(), new ItBudgetFixtures.NoteInput(id.toString(), false));
        // Read within region JP: the reference is judged by the default dataset, so it is looked up there.
        assertThat(priceReads()).isEqualTo(2);
    }

    private static long priceReads() {
        return SqlStatementLog.STATEMENTS.stream()
            .filter(sql -> sql.contains("FROM it_price") && sql.startsWith("SELECT * FROM (SELECT DISTINCT ON"))
            .count();
    }

    @Test
    void aTargetReadAndThenDeletedInTheTransactionIsNotFound() {
        UUID id = UUID.randomUUID();
        commit(ItTemporalFixtures.PRICE_DATASET, insert("ItPrice", id, price(id, sku())));

        Throwable error = runFailing(ItBudgetFixtures.NOTE_AFTER_DEFAULT_READ.name(),
            new ItBudgetFixtures.NoteInput(id.toString(), true));

        assertThat(codes(error)).containsExactly(PlatformErrorCodes.REFERENCE_NOT_FOUND);
        assertThat(count("SELECT count(*) AS n FROM it_price WHERE price_id = ?", id)).isEqualTo(1);
    }

    // ---- an update at the version read (T7) ----------------------------------------------------------------------

    private String ticket(String title) {
        String id = "wc-" + UUID.randomUUID();
        run(ItProcessFixtures.CREATE_TICKET.name(), new ItProcessFixtures.TicketInput(id, title, 1L));
        return id;
    }

    @Test
    void anUpdateAtTheVersionReadIsOneStatementWithItsAuditRecord() {
        String id = ticket("first");
        SqlStatementLog.clear();

        ItBudgetFixtures.TouchOutput out = run(ItBudgetFixtures.TOUCH.name(),
            new ItBudgetFixtures.TouchInput(id, Map.of("title", "second")));

        assertThat(out.version()).isEqualTo(2);
        assertThat(SqlStatementLog.STATEMENTS).filteredOn(sql -> sql.contains("it_ticket")).hasSize(2);
        // The values before are those stored, as a read before the update shows them.
        assertThat(query("SELECT changes::text AS c, version_no FROM sys_audit_record WHERE entity_id = ? AND"
            + " action = 'UPDATE'", id)).singleElement().satisfies(row -> {
                assertThat(row.get("c")).isEqualTo("{\"title\":[\"first\",\"second\"]}");
                assertThat(((Number) row.get("version_no")).longValue()).isEqualTo(2);
            });
    }

    @Test
    void aConcurrentChangeIsAConflictAndLeavesNoAuditRecord() {
        String id = ticket("first");

        Throwable error = runFailing(ItBudgetFixtures.TOUCH.name(),
            new ItBudgetFixtures.TouchInput(id, Map.of("title", "mine"), "update"));

        assertThat(error).isInstanceOf(ConcurrentUpdateException.class)
            .hasMessage("Optimistic lock conflict on ItTicket [ID: " + id + "]: expected version [1], stored version "
                + "[2]");
        assertThat(((ConcurrentUpdateException) error).busy()).isTrue();
        assertThat(query("SELECT f_title FROM it_ticket WHERE f_id = ?", id).getFirst())
            .containsEntry("f_title", "changed meanwhile");
        assertThat(count("SELECT count(*) AS n FROM sys_audit_record WHERE entity_id = ? AND action = 'UPDATE'", id))
            .isEqualTo(1);
    }

    @Test
    void aConcurrentDeletionIsNotFound() {
        String id = ticket("first");

        Throwable error = runFailing(ItBudgetFixtures.TOUCH.name(),
            new ItBudgetFixtures.TouchInput(id, Map.of("title", "mine"), "delete"));

        assertThat(error).isInstanceOf(EntityNotFoundException.class).hasMessage("ItTicket [ID: " + id + "] not found");
    }

    @Test
    void theRulesOfAnUpdateJudgeTheStateRead() {
        String id = ticket("first");

        assertThat(codes(runFailing(ItBudgetFixtures.TOUCH.name(),
            new ItBudgetFixtures.TouchInput(id, Map.of("status", "DONE")))))
            .containsExactly(PlatformErrorCodes.ILLEGAL_TRANSITION);
        assertThat(codes(runFailing(ItBudgetFixtures.TOUCH.name(),
            new ItBudgetFixtures.TouchInput(id, Map.of("owner", "someone else")))))
            .containsExactly(PlatformErrorCodes.IMMUTABLE_FIELD);
        ItBudgetFixtures.TouchOutput started = run(ItBudgetFixtures.TOUCH.name(), new ItBudgetFixtures.TouchInput(id,
            Map.of("status", "IN_PROGRESS")));
        assertThat(started).isEqualTo(new ItBudgetFixtures.TouchOutput(id, 2));
    }

    @Test
    void anInstanceWrittenInTheTransactionIsReadAgainBeforeTheNextUpdate() {
        String id = ticket("first");
        SqlStatementLog.clear();

        ItBudgetFixtures.TouchOutput out = run(ItBudgetFixtures.TOUCH_TWICE.name(),
            new ItBudgetFixtures.TouchInput(id, Map.of("title", "twice")));

        assertThat(out.version()).isEqualTo(3);
        List<String> ticketStatements = SqlStatementLog.STATEMENTS.stream().filter(sql -> sql.contains("it_ticket"))
            .map(sql -> sql.startsWith("SELECT") ? "read" : "update").toList();
        assertThat(ticketStatements).containsExactly("read", "update", "read", "update");
        assertThat(query("SELECT changes::text AS c FROM sys_audit_record WHERE entity_id = ? AND version_no = 3", id))
            .singleElement().satisfies(row -> assertThat(row.get("c")).isEqualTo("{\"title\":[\"once\",\"twice\"]}"));
    }

    // ---- combined reads (T5) -------------------------------------------------------------------------------------

    /**
     * Every dataset of the application read through {@code queryMany} gives what {@code queryAll} gives: the same
     * instances, every value of the same type, or the same refusal. Each read is combined with another, so that it goes
     * through the one statement of several reads.
     */
    @Test
    void combinedReadsGiveExactlyWhatEachReadGivesAlone() {
        seedSomeRows();
        DatasetDefinition tickets = datasets.findById(ItFixtures.TICKET_DATASET).orElseThrow();
        EntityDefinition ticket = definitions.getOrThrow(tickets.targetEntityType());
        int compared = 0;
        for (DatasetDefinition dataset : datasets.all()) {
            EntityDefinition def = definitions.find(dataset.targetEntityType()).orElse(null);
            if (def == null) {
                continue;
            }
            EntityQuery query = EntityQuery.builder().limit(5).build();
            Object alone;
            try {
                alone = asRequest(ADMIN, entities.queryAll(dataset, def, query).collectList()).block();
            } catch (RuntimeException refused) {
                alone = refused.getClass();
            }
            Object combined;
            try {
                combined = asRequest(ADMIN, entities.queryMany(List.of(
                    new DatasetEntityManager.EntityRead(tickets, ticket, EntityQuery.builder().limit(1).build()),
                    new DatasetEntityManager.EntityRead(dataset, def, query)))).block().get(1);
            } catch (RuntimeException refused) {
                combined = refused.getClass();
            }
            assertThat(combined).as(dataset.resourceId()).isEqualTo(alone);
            if (alone instanceof List<?> list && !list.isEmpty()) {
                compared++;
                assertTypes(dataset.resourceId(), (List<?>) alone, (List<?>) combined);
            }
        }
        assertThat(compared).as("datasets with rows").isGreaterThanOrEqualTo(4);
    }

    private static void assertTypes(String dataset, List<?> alone, List<?> combined) {
        for (int i = 0; i < alone.size(); i++) {
            Map<String, Object> a = ((EntityInstance) alone.get(i)).attributes();
            Map<String, Object> b = ((EntityInstance) combined.get(i)).attributes();
            a.forEach((field, value) -> assertThat(b.get(field) == null ? null : b.get(field).getClass())
                .as(dataset + "." + field).isEqualTo(value == null ? null : value.getClass()));
        }
    }

    private void seedSomeRows() {
        ticket("seeded");
        UUID id = UUID.randomUUID();
        commit(ItTemporalFixtures.PRICE_DATASET, insert("ItPrice", id, price(id, sku())),
            note(id, "seeded"));
        UUID account = UUID.randomUUID();
        commit(LedgerEntities.ACCOUNT_DATASET, insert(LedgerEntities.ACCOUNT, account, Map.of("accountId", account,
            "accountCode", "WS" + UUID.randomUUID().toString().substring(0, 6), "accountName", "seeded",
            "accountType", "ASSET", "enabled", true)));
    }

    @Test
    void aReadOfNoValuesSendsNothing() {
        DatasetDefinition tickets = datasets.findById(ItFixtures.TICKET_DATASET).orElseThrow();
        EntityDefinition ticket = definitions.getOrThrow(tickets.targetEntityType());
        SqlStatementLog.clear();

        List<EntityInstance> none = asRequest(ADMIN, entities.queryAll(tickets, ticket, EntityQuery.builder()
            .where(new com.jabiz.query.QueryPredicate.In("ticketId", List.of())).limit(5).build()).collectList())
            .block();
        Long counted = asRequest(ADMIN, entities.count(tickets, ticket, EntityQuery.builder()
            .where(new com.jabiz.query.QueryPredicate.In("ticketId", List.of())).build())).block();

        assertThat(none).isEmpty();
        assertThat(counted).isZero();
        assertThat(SqlStatementLog.STATEMENTS).isEmpty();
    }

    // ---- review of 16p-1 ------------------------------------------------------------------------------------------

    @Autowired
    com.jabiz.runtime.dictionary.DictionaryRegistry dictionaries;

    @Autowired
    com.jabiz.runtime.storage.StorageAdapterRegistry storages;

    @Autowired
    com.jabiz.runtime.process.steps.HoldLock<ProcessContext> holdLock;

    /**
     * The audit trail keeps the compact text it always stored ({@code changes} is a text column, written as JSON
     * text): both when the update reads first and when it updates at the version this transaction read.
     */
    @Test
    void auditChangesKeepTheirCompactTextOnEveryPath() {
        String known = ticket("first");
        run(ItBudgetFixtures.TOUCH.name(), new ItBudgetFixtures.TouchInput(known, Map.of("title", "second")));
        String read = ticket("first");
        commit(ItFixtures.TICKET_DATASET, new EntityChange(EntityAction.UPDATE,
            new EntityInstance(read, "ItTicket", 1, null, Map.of("title", "second"))));

        for (String id : List.of(known, read)) {
            assertThat(query("SELECT changes FROM sys_audit_record WHERE entity_id = ? AND action = 'UPDATE'", id))
                .singleElement().satisfies(row -> assertThat(row.get("changes"))
                    .isEqualTo("{\"title\":[\"first\",\"second\"]}"));
            assertThat(query("SELECT changes FROM sys_audit_record WHERE entity_id = ? AND action = 'INSERT'", id))
                .singleElement().satisfies(row -> assertThat((String) row.get("changes")).doesNotContain(": ")
                    .startsWith("{\"amount\":[null,\"1\"],"));
        }
    }

    private EntityChange dictItem(String code) {
        UUID id = UUID.randomUUID();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("dictItemId", id);
        item.put("dictUrn", ItBudgetFixtures.KINDS);
        item.put("itemCode", code);
        item.put("labels", Map.of("en", "Kind " + code));
        item.put("sortOrder", 1);
        item.put("enabled", true);
        return insert("SysDictItem", id, item);
    }

    private static EntityChange audited(String id, String kind) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("auditedId", id);
        attributes.put("note", "n");
        attributes.put("kind", kind);
        return insert("ItAudited", id, attributes);
    }

    /**
     * A dictionary item and a row using its code in one commit (decision D46 rule 1: the item's row, not yet written,
     * is written before the dictionary is read); and the codes read inside a transaction that then rolls back are not
     * remembered for later requests.
     */
    @Test
    void aDictionaryItemCanBeUsedInTheCommitThatAddsItAndAnUncommittedOneIsNotCached() {
        String used = "K" + UUID.randomUUID().toString().substring(0, 6);
        String auditedId = "a-" + UUID.randomUUID();
        commit("urn:jabiz:dataset:platform:SysDictItem", dictItem(used), audited(auditedId, used));
        assertThat(query("SELECT f_kind FROM it_audited WHERE f_id = ?", auditedId).getFirst())
            .containsEntry("f_kind", used);

        String rolledBack = "R" + UUID.randomUUID().toString().substring(0, 6);
        dictionaries.invalidate("*");
        Throwable failed = failure("urn:jabiz:dataset:platform:SysDictItem", dictItem(rolledBack),
            audited("a-" + UUID.randomUUID(), rolledBack), audited("a-" + UUID.randomUUID(), "NOT-A-KIND"));
        assertThat(codes(failed)).containsExactly(PlatformErrorCodes.NOT_IN_DICTIONARY);
        assertThat(asRequest(ADMIN, dictionaries.enabledCodes(ItBudgetFixtures.KINDS)).block())
            .contains(used).doesNotContain(rolledBack);
    }

    /**
     * When a commit has several problems, the earliest write's is reported, as when every write was sent at once: a
     * temporal key that exists before a later change's rule; of two conflicts in one statement, the earlier row's.
     */
    @Test
    void theEarliestWritesConflictIsReported() {
        UUID id = UUID.randomUUID();
        commit(ItTemporalFixtures.PRICE_DATASET, insert("ItPrice", id, price(id, sku())));
        UUID other = UUID.randomUUID();
        Map<String, Object> invalid = price(other, sku());
        invalid.put("amount", -1);
        assertThat(failure(ItTemporalFixtures.PRICE_DATASET, insert("ItPrice", id, price(id, sku())),
            insert("ItPrice", other, invalid))).isInstanceOf(ConcurrentUpdateException.class)
            .hasMessage("ItPrice [ID: " + id + "] already exists");

        String code = "U" + UUID.randomUUID().toString().substring(0, 8);
        commit(ItFixtures.UNIQUE_DATASET, insert("ItUnique", "u-" + UUID.randomUUID(), Map.of("code", code)));
        String key = "t-" + UUID.randomUUID();
        Map<String, Object> ticket = Map.of("ticketId", key, "title", "t", "amount", 1, "status", "OPEN",
            "owner", "it-owner");
        commit(ItFixtures.TICKET_DATASET, insert("ItTicket", key, ticket));
        // Registered first: the unique code (400); second: the ticket's existing key (409).
        Throwable both = failure(ItFixtures.UNIQUE_DATASET,
            insert("ItUnique", "u-" + UUID.randomUUID(), Map.of("code", code)), insert("ItTicket", key, ticket));
        assertThat(codes(both)).containsExactly(PlatformErrorCodes.UNIQUE_VIOLATION);
    }

    /** Two transactions racing for one value of a plain unique index, each with rows written together. */
    @Test
    void concurrentCombinedCommitsOnOnePlainUniqueValue() throws Exception {
        String code = "R" + UUID.randomUUID().toString().substring(0, 8);
        List<Throwable> outcomes = race(() -> commit(ItFixtures.UNIQUE_DATASET,
            insert("ItUnique", "u-" + UUID.randomUUID(), Map.of("code", code)),
            insert("ItUnique", "u-" + UUID.randomUUID(), Map.of("code", code + "-" + UUID.randomUUID()
                .toString().substring(0, 4)))));
        assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
        assertThat(outcomes).filteredOn(java.util.Objects::nonNull).singleElement()
            .satisfies(error -> assertThat(codes(error)).containsExactly(PlatformErrorCodes.UNIQUE_VIOLATION));
        assertThat(count("SELECT count(*) AS n FROM it_unique WHERE f_code = ?", code)).isEqualTo(1);
    }

    /** Two transactions racing for one value of a temporal entity's uniqueness (decision D6), rows written together. */
    @Test
    void concurrentCombinedCommitsOnOneTemporalUniqueValue() throws Exception {
        String sku = sku();
        List<Throwable> outcomes = race(() -> {
            UUID id = UUID.randomUUID();
            UUID note = UUID.randomUUID();
            commit(ItTemporalFixtures.PRICE_DATASET, insert("ItPrice", id, price(id, sku)),
                insert("ItNote", note, Map.of("noteId", note, "priceRef", id, "body", "raced")));
        });
        assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
        assertThat(outcomes).filteredOn(java.util.Objects::nonNull).singleElement()
            .satisfies(error -> assertThat(codes(error)).containsExactly(PlatformErrorCodes.UNIQUE_VIOLATION));
        assertThat(count("SELECT count(DISTINCT price_id) AS n FROM it_price WHERE sku = ?", sku)).isEqualTo(1);
    }

    /** Runs {@code work} twice at once; the error of each, or null. */
    private static List<Throwable> race(Runnable work) throws Exception {
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<Throwable>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return catchThrowable(work::run);
                }));
            }
            List<Throwable> outcomes = new ArrayList<>();
            for (var run : runs) {
                outcomes.add(run.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
            return outcomes;
        }
    }

    /**
     * An update at the version read writes its audit record and event in its own statement: the same rows as the
     * update followed by each row on its own, a secret shown as *** and an audit-masked value masked alike.
     */
    @Test
    void anUpdatesAuditRecordAndEventAreTheSameInOneStatementOrOneAtATime() {
        String kind = "K" + UUID.randomUUID().toString().substring(0, 6);
        commit("urn:jabiz:dataset:platform:SysDictItem", dictItem(kind));
        Map<String, Object> changes = Map.of("note", "after", "secret", "new secret", "fileName", "after.pdf");
        List<String> written = new ArrayList<>();
        for (boolean oneStatement : List.of(true, false)) {
            String id = "a-" + UUID.randomUUID();
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("auditedId", id);
            attributes.put("note", "before");
            attributes.put("secret", "old secret");
            attributes.put("fileName", "before.pdf");
            attributes.put("kind", kind);
            commit(ItBudgetFixtures.AUDITED_DATASET, insert("ItAudited", id, attributes));
            ProcessDefinition<?, ?, ?> touch = processes.find(ItBudgetFixtures.TOUCH_AUDITED.name(), 1).orElseThrow();
            @SuppressWarnings("unchecked")
            var run = asRequest(ADMIN, executor.execute(
                (ProcessDefinition<ItBudgetFixtures.TouchInput, ItBudgetFixtures.TouchOutput, ProcessContext>) touch,
                new ItBudgetFixtures.TouchInput(id, changes)));
            assertThat((oneStatement ? run : run.contextWrite(WriteBatching::statementByStatement)).block().version())
                .isEqualTo(2);
            Map<String, Object> audit = query("SELECT action, version_no, changes, changed_fields::text AS fields,"
                + " actor_id, reason, effect_start_time FROM sys_audit_record WHERE entity_id = ? AND action = 'UPDATE'",
                id).getFirst();
            Map<String, Object> event = query("SELECT event_type, entity_type, payload::text AS payload FROM"
                + " sys_outbox_event WHERE entity_id = ? AND payload ->> 'action' = 'UPDATE'", id).getFirst();
            written.add((audit + " " + event).replace(id, "<id>"));
            assertThat((String) audit.get("changes")).contains("\"secret\":[\"***\",\"***\"]")
                .doesNotContain("secret\",").doesNotContain("old secret").doesNotContain("before.pdf");
        }
        assertThat(written.get(0)).isEqualTo(written.get(1));
    }

    /**
     * A target inserted within a savepoint that was rolled back (an import's failed row) is no longer known: a later
     * reference to it is looked up and not found.
     */
    @Test
    void aTargetInsertedInARolledBackSavepointIsNotFound() {
        var engine = storages.getEngine("default");
        UUID id = UUID.randomUUID();
        UUID note = UUID.randomUUID();
        var dataset = datasets.findById(ItTemporalFixtures.PRICE_DATASET).orElseThrow();
        var notes = datasets.findById(ItTemporalFixtures.NOTE_DATASET).orElseThrow();
        Throwable error = catchThrowable(() -> asRequest(ADMIN, engine.inTransaction(
            engine.inSavepoint(entities.commitBatch(dataset, List.of(insert("ItPrice", id, price(id, sku()))))
                    .then(reactor.core.publisher.Mono.error(new IllegalStateException("the row fails"))))
                .onErrorResume(IllegalStateException.class, failed -> reactor.core.publisher.Mono.empty())
                .then(entities.commitBatch(notes, List.of(insert("ItNote", note,
                    Map.of("noteId", note, "priceRef", id, "body", "of a rolled back price"))))))).block());
        assertThat(codes(error)).containsExactly(PlatformErrorCodes.REFERENCE_NOT_FOUND);
        assertThat(count("SELECT count(*) AS n FROM it_price WHERE price_id = ?", id)).isZero();
    }

    /**
     * After a transaction of its own (another connection) has committed, the suspended one no longer relies on what it
     * read: the update reads again, and refuses as before.
     */
    @Test
    void aTransactionOfItsOwnMakesTheSuspendedOneReadAgain() {
        String id = ticket("first");
        SqlStatementLog.clear();

        Throwable error = runFailing(ItBudgetFixtures.TOUCH.name(),
            new ItBudgetFixtures.TouchInput(id, Map.of("title", "mine"), "update-new-tx"));

        assertThat(error).isInstanceOf(ConcurrentUpdateException.class).hasMessageContaining("stored version [2]");
        // The load; the other transaction's read and update; the read again, which finds the newer version and refuses
        // without updating (before the fix the update relied on the first read and only its version check refused).
        assertThat(SqlStatementLog.STATEMENTS.stream().filter(sql -> sql.contains("it_ticket"))
            .map(sql -> sql.startsWith("SELECT") ? "read" : "update").toList())
            .containsExactly("read", "read", "update", "read");
    }

    /** {@code HoldLock} in a transaction of its own sets its wait: the suspended transaction's is not that one's. */
    @Test
    void aLockInATransactionOfItsOwnSetsItsOwnWait() {
        var engine = storages.getEngine("default");
        String outer = "own-" + UUID.randomUUID();
        String inner = "own-" + UUID.randomUUID();
        var outerLock = new com.jabiz.runtime.process.steps.HoldLock.Metadata<ProcessContext>(ctx -> outer, false);
        var innerLock = new com.jabiz.runtime.process.steps.HoldLock.Metadata<ProcessContext>(ctx -> inner, false);
        SqlStatementLog.clear();
        asRequest(ADMIN, engine.inTransaction(holdLock.execute(outerLock, null)
            .then(engine.inNewTransaction(holdLock.execute(innerLock, null))))
            .contextWrite(context -> context.put(com.jabiz.runtime.storage.TransactionLockTimeout.class,
                new com.jabiz.runtime.storage.TransactionLockTimeout("30000ms")))).block();

        assertThat(SqlStatementLog.STATEMENTS).filteredOn(sql -> sql.contains("advisory_xact_lock")
            || sql.contains("jabiz_xact_lock")).extracting(sql -> sql.contains("jabiz_xact_lock") ? "function" : "lock")
            .containsExactly("lock", "function");
    }
}

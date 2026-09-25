package com.jabiz.it;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.DatasetRegistry;
import com.jabiz.entity.CustomsDeclarationEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.entity.WaybillEntityDefinitions;
import com.jabiz.it.fixture.ItFixtures;
import com.jabiz.it.support.PostgresIntegrationTest;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.ConcurrentUpdateException;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Write and read paths of {@link DatasetEntityManager} against a real PostgreSQL (ROADMAP phase 1, item 3). */
class DatasetEntityManagerIT extends PostgresIntegrationTest {

    @Autowired
    DatasetEntityManager manager;

    @Autowired
    DatasetRegistry datasets;

    @BeforeEach
    void cleanTables() {
        execute("DELETE FROM t_customs_declaration");
        execute("DELETE FROM t_legacy_waybill_2026");
        execute("DELETE FROM it_ticket");
        execute("DELETE FROM it_soft");
        execute("DELETE FROM it_regional");
        execute("DELETE FROM it_readonly");
    }

    private DatasetDefinition dataset(String resourceId) {
        return datasets.findById(resourceId).orElseThrow();
    }

    private List<EntityInstance> commit(String datasetId, EntityChange... changes) {
        return manager.commitBatch(dataset(datasetId), List.of(changes)).block();
    }

    private static EntityInstance instance(EntityDefinition def, Object id, long version, Map<String, Object> attrs) {
        return new EntityInstance(id, def.name, version, null, attrs);
    }

    private static Map<String, Object> attrs(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Instant instant(Object stored) {
        return stored instanceof Timestamp t ? t.toInstant() : ((OffsetDateTime) stored).toInstant();
    }

    // ---------------------------------------------------------------- ItTicket helpers

    private EntityInstance insertTicket(String id, Object... extra) {
        Map<String, Object> values = attrs(extra);
        values.putIfAbsent("title", "ticket " + id);
        values.putIfAbsent("amount", 100);
        return commit(ItFixtures.TICKET_DATASET,
            EntityChange.insert(instance(ItFixtures.TICKET, id, 0, values))).get(0);
    }

    private EntityInstance updateTicket(String id, long version, Object... changes) {
        return commit(ItFixtures.TICKET_DATASET,
            EntityChange.update(instance(ItFixtures.TICKET, id, version, attrs(changes)))).get(0);
    }

    private Map<String, Object> ticketRow(String id) {
        List<Map<String, Object>> rows = query("SELECT * FROM it_ticket WHERE f_id = ?", id);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private int count(String table) {
        return ((Number) query("SELECT count(*) AS n FROM " + table).get(0).get("n")).intValue();
    }

    // ---------------------------------------------------------------- Insert

    @Test
    void insertsRowAndReturnsSnapshot() {
        EntityInstance created = insertTicket("T-1", "amount", "1500", "owner", "alice");

        assertThat(created.id()).isEqualTo("T-1");
        assertThat(created.version()).isEqualTo(1L);
        assertThat(created.state()).isEqualTo("OPEN");
        assertThat(created.attributes())
            .containsEntry("amount", new BigDecimal("1500"))
            .containsEntry("recordedTime", START)
            .containsEntry("status", "OPEN");

        Map<String, Object> row = ticketRow("T-1");
        assertThat(row).containsEntry("f_title", "ticket T-1")
            .containsEntry("f_status", "OPEN")
            .containsEntry("f_owner", "alice")
            .containsEntry("f_version", 1L);
        assertThat((BigDecimal) row.get("f_amount")).isEqualByComparingTo("1500");
        assertThat(instant(row.get("f_created_at"))).isEqualTo(START);
    }

    @Test
    void systemManagedValuesFromCallerAreIgnored() {
        insertTicket("T-1", "rowVersion", 42, "recordedTime", "2000-01-01T00:00:00Z");

        Map<String, Object> row = ticketRow("T-1");
        assertThat(row.get("f_version")).isEqualTo(1L);
        assertThat(instant(row.get("f_created_at"))).isEqualTo(START);
    }

    @Test
    void invalidInputIsRejectedWithAllViolations() {
        assertThatThrownBy(() -> commit(ItFixtures.TICKET_DATASET, EntityChange.insert(
            instance(ItFixtures.TICKET, "T-1", 0, attrs("amount", -1, "colour", "red")))))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).violations())
                .extracting(Violation::ruleCode)
                .containsExactlyInAnyOrder("UNKNOWN_FIELD", "REQUIRED", "NON_NEGATIVE_AMOUNT"));
        assertThat(count("it_ticket")).isZero();
    }

    @Test
    void idAttributeMustMatchInstanceId() {
        assertThatThrownBy(() -> insertTicket("T-1", "ticketId", "T-2"))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).violations())
                .extracting(Violation::ruleCode).containsExactly("ID_MISMATCH"));
    }

    // ---------------------------------------------------------------- Update

    @Test
    void writesOnlyChangedFieldsAndIncrementsVersion() {
        insertTicket("T-1", "owner", "alice");
        clock.advance(Duration.ofHours(1));

        EntityInstance updated = updateTicket("T-1", 1, "title", "renamed", "amount", 100, "owner", "alice");

        assertThat(updated.version()).isEqualTo(2L);
        assertThat(updated.attributes()).containsEntry("title", "renamed").containsEntry("recordedTime", START);
        Map<String, Object> row = ticketRow("T-1");
        assertThat(row).containsEntry("f_title", "renamed").containsEntry("f_version", 2L);
        // The system-recorded time is stamped on insert only.
        assertThat(instant(row.get("f_created_at"))).isEqualTo(START);
    }

    @Test
    void updateWithoutRealChangesKeepsVersion() {
        insertTicket("T-1", "amount", 100);

        EntityInstance same = updateTicket("T-1", 1, "amount", "100.0");

        assertThat(same.version()).isEqualTo(1L);
        assertThat(ticketRow("T-1")).containsEntry("f_version", 1L);
    }

    @Test
    void missingEntityIsNotFound() {
        assertThatThrownBy(() -> updateTicket("NOPE", 1, "title", "x"))
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void invalidChangeIsRejected() {
        insertTicket("T-1");

        assertThatThrownBy(() -> updateTicket("T-1", 1, "amount", -5))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> updateTicket("T-1", 1, "title", null))
            .isInstanceOf(ValidationException.class);
        assertThat(ticketRow("T-1")).containsEntry("f_version", 1L);
    }

    // ---------------------------------------------------------------- OptimisticLock

    @Test
    void staleVersionOnUpdateIsAConflict() {
        insertTicket("T-1");
        updateTicket("T-1", 1, "title", "first");

        assertThatThrownBy(() -> updateTicket("T-1", 1, "title", "second"))
            .isInstanceOf(ConcurrentUpdateException.class)
            .hasMessageContaining("expected version [1], stored version [2]");
        assertThat(ticketRow("T-1")).containsEntry("f_title", "first").containsEntry("f_version", 2L);
    }

    @Test
    void staleVersionOnDeleteIsAConflict() {
        insertTicket("T-1");
        updateTicket("T-1", 1, "title", "first");

        assertThatThrownBy(() -> commit(ItFixtures.TICKET_DATASET,
            EntityChange.delete(instance(ItFixtures.TICKET, "T-1", 1, Map.of()))))
            .isInstanceOf(ConcurrentUpdateException.class);
        assertThat(count("it_ticket")).isEqualTo(1);
    }

    @Test
    void concurrentUpdatesOfTheSameVersionLetExactlyOneWin() {
        insertTicket("T-1");
        DatasetDefinition tickets = dataset(ItFixtures.TICKET_DATASET);

        List<Object> outcomes = Flux.merge(
                Flux.range(1, 2).map(i -> manager.commitBatch(tickets, List.of(EntityChange.update(
                        instance(ItFixtures.TICKET, "T-1", 1, attrs("title", "writer " + i)))))
                    .<Object>map(result -> result.get(0))
                    .onErrorResume(Mono::just)
                    .subscribeOn(Schedulers.parallel())))
            .collectList().block();

        assertThat(outcomes).filteredOn(EntityInstance.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ConcurrentUpdateException.class::isInstance).hasSize(1);
        assertThat(ticketRow("T-1")).containsEntry("f_version", 2L);
    }

    @Test
    void rowChangedByAnotherWriterIsDetected() {
        insertTicket("T-1");
        // Another writer (outside this manager) bumps the version.
        execute("UPDATE it_ticket SET f_title = 'external', f_version = f_version + 1 WHERE f_id = 'T-1'");

        assertThatThrownBy(() -> updateTicket("T-1", 1, "title", "mine"))
            .isInstanceOf(ConcurrentUpdateException.class);
        assertThat(ticketRow("T-1")).containsEntry("f_title", "external");
    }

    // ---------------------------------------------------------------- Immutability

    @Test
    void immutableFieldCannotChange() {
        insertTicket("T-1", "owner", "alice");

        assertThatThrownBy(() -> updateTicket("T-1", 1, "owner", "bob"))
            .isInstanceOf(BusinessRuleViolationException.class)
            .hasMessageContaining("field [owner] cannot be altered");
        assertThat(ticketRow("T-1")).containsEntry("f_owner", "alice");
    }

    @Test
    void primaryKeyCannotChange() {
        insertTicket("T-1");

        assertThatThrownBy(() -> updateTicket("T-1", 1, "ticketId", "T-2"))
            .isInstanceOf(BusinessRuleViolationException.class)
            .hasMessageContaining("field [ticketId] cannot be altered");
    }

    @Test
    void supplyingTheSameImmutableValueIsAllowed() {
        insertTicket("T-1", "owner", "alice");

        assertThat(updateTicket("T-1", 1, "owner", "alice", "title", "x").version()).isEqualTo(2L);
    }

    // ---------------------------------------------------------------- Lifecycle

    @Test
    void initialStateIsDerivedWhenOmitted() {
        assertThat(insertTicket("T-1").state()).isEqualTo("OPEN");
        assertThat(insertTicket("T-2", "status", "OPEN").state()).isEqualTo("OPEN");
    }

    @Test
    void nonInitialStateOnInsertIsRejected() {
        assertThatThrownBy(() -> insertTicket("T-1", "status", "IN_PROGRESS"))
            .isInstanceOf(BusinessRuleViolationException.class)
            .hasMessageContaining("Illegal initial state [IN_PROGRESS]");
        assertThat(count("it_ticket")).isZero();
    }

    @Test
    void stateOutsideDictionaryIsRejected() {
        assertThatThrownBy(() -> insertTicket("T-1", "status", "LOST"))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void legalTransitionsAreApplied() {
        insertTicket("T-1");

        EntityInstance inProgress = updateTicket("T-1", 1, "status", "IN_PROGRESS");
        EntityInstance done = updateTicket("T-1", 2, "status", "DONE");

        assertThat(inProgress.state()).isEqualTo("IN_PROGRESS");
        assertThat(done.state()).isEqualTo("DONE");
        assertThat(ticketRow("T-1")).containsEntry("f_status", "DONE").containsEntry("f_version", 3L);
    }

    @Test
    void illegalTransitionsAreRejected() {
        insertTicket("T-1");

        assertThatThrownBy(() -> updateTicket("T-1", 1, "status", "DONE"))
            .isInstanceOf(BusinessRuleViolationException.class)
            .hasMessageContaining("Illegal transition from [OPEN] to [DONE]");

        updateTicket("T-1", 1, "status", "IN_PROGRESS");
        assertThatThrownBy(() -> updateTicket("T-1", 2, "status", "OPEN"))
            .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(ticketRow("T-1")).containsEntry("f_status", "IN_PROGRESS");
    }

    @Test
    void stateCannotBeCleared() {
        insertTicket("T-1");

        assertThatThrownBy(() -> updateTicket("T-1", 1, "status", null))
            .isInstanceOf(BusinessRuleViolationException.class)
            .hasMessageContaining("cannot be cleared");
    }

    // ---------------------------------------------------------------- Delete

    @Test
    void hardDeleteRemovesTheRow() {
        insertTicket("T-1");

        List<EntityInstance> result = commit(ItFixtures.TICKET_DATASET,
            EntityChange.delete(instance(ItFixtures.TICKET, "T-1", 1, Map.of())));

        assertThat(result).isEmpty();
        assertThat(count("it_ticket")).isZero();
        assertThat(manager.findById(dataset(ItFixtures.TICKET_DATASET), ItFixtures.TICKET, "T-1").blockOptional())
            .isEmpty();
    }

    @Test
    void deletingMissingEntityIsNotFound() {
        assertThatThrownBy(() -> commit(ItFixtures.TICKET_DATASET,
            EntityChange.delete(instance(ItFixtures.TICKET, "NOPE", 1, Map.of()))))
            .isInstanceOf(EntityNotFoundException.class);
    }

    // ---------------------------------------------------------------- SoftDelete

    private EntityInstance insertSoft(String id) {
        return commit(ItFixtures.SOFT_DATASET,
            EntityChange.insert(instance(ItFixtures.SOFT, id, 0, attrs("name", "n-" + id)))).get(0);
    }

    @Test
    void deleteMarksTheRowAndHidesIt() {
        insertSoft("S-1");
        insertSoft("S-2");
        clock.advance(Duration.ofMinutes(5));

        commit(ItFixtures.SOFT_DATASET, EntityChange.delete(instance(ItFixtures.SOFT, "S-1", 1, Map.of())));

        Map<String, Object> row = query("SELECT * FROM it_soft WHERE f_id = 'S-1'").get(0);
        assertThat(row).containsEntry("is_deleted", true).containsEntry("f_version", 2L);
        assertThat(instant(row.get("deleted_at"))).isEqualTo(START.plus(Duration.ofMinutes(5)));

        DatasetDefinition soft = dataset(ItFixtures.SOFT_DATASET);
        assertThat(manager.findById(soft, ItFixtures.SOFT, "S-1").blockOptional()).isEmpty();
        assertThat(manager.query(soft, ItFixtures.SOFT, EntityQuery.builder().build()).collectList().block())
            .extracting(EntityInstance::id).containsExactly("S-2");
    }

    @Test
    void softDeletedEntityCannotBeUpdatedOrDeletedAgain() {
        insertSoft("S-1");
        commit(ItFixtures.SOFT_DATASET, EntityChange.delete(instance(ItFixtures.SOFT, "S-1", 1, Map.of())));

        assertThatThrownBy(() -> commit(ItFixtures.SOFT_DATASET,
            EntityChange.delete(instance(ItFixtures.SOFT, "S-1", 2, Map.of()))))
            .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> commit(ItFixtures.SOFT_DATASET,
            EntityChange.update(instance(ItFixtures.SOFT, "S-1", 2, attrs("name", "back")))))
            .isInstanceOf(EntityNotFoundException.class);
    }

    // ---------------------------------------------------------------- PartitionScope

    private DatasetDefinition regional() {
        return dataset(ItFixtures.REGIONAL_DATASET);
    }

    private EntityChange insert(String id, Object... values) {
        return EntityChange.insert(instance(ItFixtures.REGIONAL, id, 0, attrs(values)));
    }

    private void seedBothRegions() {
        execute("INSERT INTO it_regional (f_id, f_region, f_name) VALUES ('JP-1', 'JP', 'tokyo'), ('US-1', 'US', 'ny')");
    }

    @Test
    void readsSeeOnlyThePartition() {
        seedBothRegions();
        assertThat(manager.query(regional(), ItFixtures.REGIONAL, EntityQuery.builder().build()).collectList().block())
            .extracting(EntityInstance::id).containsExactly("JP-1");
        assertThat(manager.query(regional(), ItFixtures.REGIONAL, EntityQuery.builder()
                .where(new QueryPredicate.Eq("region", "US")).build()).collectList().block())
            .isEmpty();
        assertThat(manager.findById(regional(), ItFixtures.REGIONAL, "US-1").blockOptional()).isEmpty();
        assertThat(manager.findById(regional(), ItFixtures.REGIONAL, "JP-1").blockOptional()).isPresent();
    }

    @Test
    void insertFillsMissingPartitionValue() {
        seedBothRegions();
        EntityInstance created = commit(ItFixtures.REGIONAL_DATASET, insert("JP-2", "name", "osaka")).get(0);

        assertThat(created.attributes()).containsEntry("region", "JP");
        assertThat(query("SELECT f_region FROM it_regional WHERE f_id = 'JP-2'").get(0))
            .containsEntry("f_region", "JP");
    }

    @Test
    void insertOutsideThePartitionIsRejected() {
        seedBothRegions();
        assertThatThrownBy(() -> commit(ItFixtures.REGIONAL_DATASET, insert("US-2", "region", "US")))
            .isInstanceOf(BusinessRuleViolationException.class)
            .hasMessageContaining("field [region] of ItRegional must be [JP]");
        assertThat(count("it_regional")).isEqualTo(2);
    }

    @Test
    void rowsOutsideThePartitionCannotBeUpdatedOrDeleted() {
        seedBothRegions();
        assertThatThrownBy(() -> commit(ItFixtures.REGIONAL_DATASET,
            EntityChange.update(instance(ItFixtures.REGIONAL, "US-1", 1, attrs("name", "hacked")))))
            .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> commit(ItFixtures.REGIONAL_DATASET,
            EntityChange.delete(instance(ItFixtures.REGIONAL, "US-1", 1, Map.of()))))
            .isInstanceOf(EntityNotFoundException.class);
        assertThat(query("SELECT f_name FROM it_regional WHERE f_id = 'US-1'").get(0)).containsEntry("f_name", "ny");
    }

    @Test
    void updateCannotMoveARowOutOfThePartition() {
        seedBothRegions();
        assertThatThrownBy(() -> commit(ItFixtures.REGIONAL_DATASET,
            EntityChange.update(instance(ItFixtures.REGIONAL, "JP-1", 1, attrs("region", "US")))))
            .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(query("SELECT f_region FROM it_regional WHERE f_id = 'JP-1'").get(0))
            .containsEntry("f_region", "JP");
    }

    // ---------------------------------------------------------------- BatchLimits

    @Test
    void batchLargerThanTheLimitIsRejectedWithoutWriting() {
        EntityChange[] changes = IntStream.rangeClosed(1, ItFixtures.TICKET_MAX_WRITE_BATCH + 1)
            .mapToObj(i -> EntityChange.insert(instance(ItFixtures.TICKET, "T-" + i, 0,
                attrs("title", "t" + i))))
            .toArray(EntityChange[]::new);

        assertThatThrownBy(() -> commit(ItFixtures.TICKET_DATASET, changes))
            .isInstanceOf(BusinessRuleViolationException.class)
            .hasMessageContaining("Batch size [4] exceeds dataset limit [3]");
        assertThat(count("it_ticket")).isZero();
    }

    @Test
    void batchAtTheLimitIsAppliedInOrder() {
        List<EntityInstance> result = commit(ItFixtures.TICKET_DATASET,
            EntityChange.insert(instance(ItFixtures.TICKET, "T-1", 0, attrs("title", "a"))),
            EntityChange.update(instance(ItFixtures.TICKET, "T-1", 1, attrs("title", "b"))),
            EntityChange.insert(instance(ItFixtures.TICKET, "T-2", 0, attrs("title", "c"))));

        assertThat(result).extracting(EntityInstance::id, EntityInstance::version)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("T-1", 1L),
                org.assertj.core.groups.Tuple.tuple("T-1", 2L),
                org.assertj.core.groups.Tuple.tuple("T-2", 1L));
        assertThat(ticketRow("T-1")).containsEntry("f_title", "b");
    }

    @Test
    void failingChangeRollsBackTheWholeBatch() {
        assertThatThrownBy(() -> commit(ItFixtures.TICKET_DATASET,
            EntityChange.insert(instance(ItFixtures.TICKET, "T-1", 0, attrs("title", "ok"))),
            EntityChange.insert(instance(ItFixtures.TICKET, "T-2", 0, attrs()))))
            .isInstanceOf(ValidationException.class);

        assertThat(count("it_ticket")).isZero();
    }

    @Test
    void queryRowCountIsCappedByTheDataset() {
        IntStream.rangeClosed(1, 7).forEach(i -> insertTicket("T-" + i));

        List<EntityInstance> page = manager.query(dataset(ItFixtures.TICKET_DATASET), ItFixtures.TICKET,
            EntityQuery.builder().limit(100).build()).collectList().block();

        assertThat(page).extracting(EntityInstance::id).containsExactly("T-1", "T-2", "T-3", "T-4", "T-5");
    }

    @Test
    void queryAppliesPredicateSortAndOffset() {
        insertTicket("T-1", "amount", 300);
        insertTicket("T-2", "amount", 100);
        insertTicket("T-3", "amount", 200);
        insertTicket("T-4", "amount", 50);

        List<EntityInstance> page = manager.query(dataset(ItFixtures.TICKET_DATASET), ItFixtures.TICKET,
            EntityQuery.builder()
                .where(new QueryPredicate.Gte("amount", 100))
                .orderBy("amount", false)
                .offset(1).limit(2)
                .build()).collectList().block();

        assertThat(page).extracting(EntityInstance::id).containsExactly("T-3", "T-2");
        assertThat(page.get(0).version()).isEqualTo(1L);
    }

    @Test
    void queryBindsInListsAgainstTheDatabase() {
        insertTicket("T-1");
        insertTicket("T-2");
        insertTicket("T-3");

        List<EntityInstance> found = manager.query(dataset(ItFixtures.TICKET_DATASET), ItFixtures.TICKET,
            EntityQuery.builder().where(new QueryPredicate.In("ticketId", List.<Object>of("T-1", "T-3", "T-9"))).build())
            .collectList().block();

        assertThat(found).extracting(EntityInstance::id).containsExactly("T-1", "T-3");
    }

    @Test
    @Disabled("Known bug 1 (phase-1 PR): QueryCompiler keeps parameters bound by OR branches it discarded; "
        + "R2DBC then fails with '\"p0\" is not a valid identifier'")
    void orWithAlwaysTrueBranchReturnsEveryRow() {
        insertTicket("T-1", "amount", 10);
        insertTicket("T-2", "amount", 20);

        List<EntityInstance> found = manager.query(dataset(ItFixtures.TICKET_DATASET), ItFixtures.TICKET,
            EntityQuery.builder().where(new QueryPredicate.Or(List.of(
                new QueryPredicate.Eq("amount", 10),
                new QueryPredicate.And(List.of())))).build())
            .collectList().block();

        assertThat(found).extracting(EntityInstance::id).containsExactly("T-1", "T-2");
    }

    // ---------------------------------------------------------------- ReadOnly

    private void seedReadonly() {
        execute("INSERT INTO it_readonly (f_id, f_name) VALUES ('R-1', 'fixed')");
    }

    @Test
    void everyWriteIsRejected() {
        seedReadonly();
        List<EntityChange> writes = List.of(
            EntityChange.insert(instance(ItFixtures.READONLY, "R-2", 0, attrs("name", "new"))),
            EntityChange.update(instance(ItFixtures.READONLY, "R-1", 1, attrs("name", "changed"))),
            EntityChange.delete(instance(ItFixtures.READONLY, "R-1", 1, Map.of())));

        for (EntityChange write : writes) {
            assertThatThrownBy(() -> commit(ItFixtures.READONLY_DATASET, write))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("is read-only");
        }
        assertThat(query("SELECT f_id, f_name FROM it_readonly"))
            .containsExactly(Map.of("f_id", "R-1", "f_name", "fixed"));
    }

    @Test
    void readsAreAllowed() {
        seedReadonly();
        assertThat(manager.findById(dataset(ItFixtures.READONLY_DATASET), ItFixtures.READONLY, "R-1").block())
            .extracting(EntityInstance::version)
            .isEqualTo(1L);
    }

    @Test
    void emptyBatchIsANoOp() {
        assertThat(manager.commitBatch(dataset(ItFixtures.READONLY_DATASET), List.of()).block()).isEmpty();
    }

    // ---------------------------------------------------------------- References

    private static final String WAYBILLS = "urn:jabiz:dataset:default:WaybillTracking";
    private static final String DECLARATIONS = "urn:jabiz:dataset:default:CustomsDeclaration";

    private void insertWaybill(String id) {
        commit(WAYBILLS, EntityChange.insert(instance(WaybillEntityDefinitions.WAYBILL, id, 0,
            attrs("freightCharge", 1000, "totalWeight", 20, "shippedTime", START.minusSeconds(60)))));
    }

    private EntityChange declaration(String id, String waybill) {
        return EntityChange.insert(instance(CustomsDeclarationEntityDefinitions.CUSTOMS_DECLARATION, id, 0,
            attrs("waybillRef", waybill, "dutyAmount", 10)));
    }

    @Test
    void referenceToMissingEntityIsRejected() {
        assertThatThrownBy(() -> commit(DECLARATIONS, declaration("D-1", "WB-missing")))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).violations())
                .extracting(Violation::ruleCode).containsExactly("REFERENCE_NOT_FOUND"));
    }

    @Test
    void referencedEntityCannotBeDeleted() {
        insertWaybill("WB-1");
        commit(DECLARATIONS, declaration("D-1", "WB-1"));

        assertThatThrownBy(() -> commit(WAYBILLS, EntityChange.delete(
            instance(WaybillEntityDefinitions.WAYBILL, "WB-1", 1, Map.of()))))
            .isInstanceOf(BusinessRuleViolationException.class)
            .hasMessageContaining("still referenced by CustomsDeclaration.waybillRef");
        assertThat(count("t_legacy_waybill_2026")).isEqualTo(1);
    }
}
